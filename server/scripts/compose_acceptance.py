#!/usr/bin/env python3
"""Exercise the shipped Compose stack, HTTP contract, primary failover and durable restart."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'api/tests'))
import http_acceptance as contract


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-image', default='timekeeper-server:test')
    parser.add_argument('--postgres-image', default='timekeeper-postgres:test')
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/compose-acceptance')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='timekeeper-compose-'))
    deployment = temporary / 'deploy'
    shutil.copytree(ROOT / 'server/deploy', deployment, ignore=shutil.ignore_patterns('.env', 'state', '__pycache__'))
    state = temporary / 'state'
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    project = 'timekeeper-test-' + secrets.token_hex(4)
    (deployment / '.env').write_text(f'TIMEKEEPER_STATE_DIR={state}\nTIMEKEEPER_SERVER_IMAGE={args.server_image}\n'
                                    f'TIMEKEEPER_POSTGRES_IMAGE={args.postgres_image}\nTIMEKEEPER_HTTP_PORT={port}\n')
    subprocess.run([sys.executable, str(deployment / 'setup.py'), '--owner', 'owner-a'],
                   check=True, stdout=subprocess.DEVNULL)
    tokens = {role: secrets.token_urlsafe(32) for role in ('a', 'b', 'read', 'expired')}
    registry = [{'ownerId': 'owner-b' if role == 'b' else 'owner-a',
                 'sha256': hashlib.sha256(token.encode()).hexdigest(),
                 'permissions': ['read'] if role == 'read' else ['read', 'write'],
                 **({'expiresAt': '2000-01-01T00:00:00Z'} if role == 'expired' else {})}
                for role, token in tokens.items()]
    (state / 'config/tokens.json').write_text(json.dumps(registry))
    environment = dict(os.environ)
    for name in ('TIMEKEEPER_STATE_DIR', 'TIMEKEEPER_SERVER_IMAGE', 'TIMEKEEPER_POSTGRES_IMAGE', 'TIMEKEEPER_HTTP_PORT'):
        environment.pop(name, None)
    command = ['docker', 'compose', '-p', project]

    def compose(*arguments, input=None):
        result = subprocess.run(command + list(arguments), cwd=deployment, env=environment,
                                input=input, text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError(f'Compose {arguments[0]} failed: {result.stderr}')
        return result.stdout

    base = f'http://127.0.0.1:{port}'
    contract.BASE_URL, contract.TOKENS = base + '/api/v1', tokens
    client = contract.HttpAcceptance('test_authentication_and_read_only_permission')
    client.created = []

    def wait_ready(timeout=120):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                with urlopen(base + '/q/health/ready', timeout=2) as response:
                    if response.status == 200:
                        return
            except (HTTPError, URLError, TimeoutError):
                pass
            time.sleep(.5)
        raise AssertionError('Compose gateway did not recover readiness')

    def cluster():
        for member in ('postgres-1', 'postgres-2', 'postgres-3'):
            try:
                return json.loads(compose('exec', '-T', member, 'curl', '-fsS', 'http://localhost:8008/cluster'))
            except RuntimeError:
                pass
        raise AssertionError('No Patroni member is reachable')

    receipt = {'httpContractCases': 0, 'twoReplicas': False, 'primaryFailover': False,
               'primaryCrashFailover': False,
               'serverFailover': False, 'durableRestart': False, 'backupRestore': False,
               'rollingUpdate': False, 'failedUpdateRollback': False}
    registry_container = project + '-registry'
    registry_started = False
    try:
        compose('up', '-d', '--wait', '--wait-timeout', '180')
        wait_ready()
        for member in ('api-1', 'api-2'):
            identity = json.loads(compose('exec', '-T', member, 'curl', '-fsS', '-H', '@-',
                                         'http://localhost:8080/api/v1/me',
                                         input='Authorization: Bearer ' + tokens['a'] + '\n'))
            client.assertEqual('owner-a', identity['ownerId'])
        receipt['twoReplicas'] = True
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(contract.HttpAcceptance)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        if not result.wasSuccessful() or result.skipped:
            raise AssertionError('Required HTTP contract case failed or skipped')
        receipt['httpContractCases'] = result.testsRun
        project_record = client.create('projects', {'name': 'Durable compose project'})
        reference = {'providerId': '😀' * 100, 'repositoryId': '😀' * 3000,
                     'externalId': '😀' * 800, 'externalUrl': None}
        task = client.create('tasks', {'summary': 'Unicode compose task', 'projectId': project_record['id'],
                                     'parentTaskId': None, 'url': None, 'externalReferences': [reference]})
        found, _ = client.call('GET', '/tasks/by-external-reference?' + urlencode({key: reference[key]
                              for key in ('providerId', 'repositoryId', 'externalId')}))
        client.assertEqual(task['id'], found['id'])
        client.create('activities', {'taskId': task['id'], 'start': '2026-03-29T00:00:00.123456789Z',
                                     'end': '2026-03-29T00:00:00.123456790Z', 'summary': '', 'labelIds': []})
        report, _ = client.call('GET', '/reports/activities?' + urlencode({'taskId': task['id'],
                                'from': '2026-03-29T00:00:00Z', 'to': '2026-03-29T01:00:00Z'}))
        client.assertEqual('PT0.000000001S', report['total'])
        # Both nodes receive concurrent starts; the database must permit one active interval only.
        body = {'taskId': task['id'], 'summary': 'Concurrent compose start', 'labelIds': [],
                'expectedActiveId': None, 'expectedActiveVersion': None}
        with ThreadPoolExecutor(max_workers=2) as pool:
            def start():
                from urllib.request import Request
                request = Request(base + '/api/v1/activities/start', data=json.dumps(body).encode(),
                                  headers={'Authorization': 'Bearer ' + tokens['a'], 'Content-Type': 'application/json',
                                           **client.key()}, method='POST')
                try:
                    with urlopen(request, timeout=10) as response: return response.status
                except HTTPError as error: return error.code
            statuses = sorted(pool.map(lambda _: start(), range(2)))
        client.assertEqual([201, 409], statuses)
        active, headers = client.call('GET', '/activities/active')
        stop_key = {**client.key(), 'If-Match': headers['ETag']}
        stopped, stopped_headers = client.call('POST', '/activities/' + active['id'] + '/stop', headers=stop_key)
        expected, _ = client.call('GET', '/data/export')
        leader = next(member['name'] for member in cluster()['members'] if member['role'] == 'leader')
        compose('stop', leader)
        wait_ready()
        new_leader = next(member['name'] for member in cluster()['members'] if member['role'] == 'leader')
        client.assertNotEqual(leader, new_leader)
        actual, _ = client.call('GET', '/data/export')
        client.assertEqual(expected, actual)
        replay, replay_headers = client.call('POST', '/activities/' + active['id'] + '/stop', headers=stop_key)
        client.assertEqual(stopped, replay)
        client.assertEqual(stopped_headers['ETag'], replay_headers['ETag'])
        client.create('projects', {'name': 'Write after primary failover'})
        receipt['primaryFailover'] = True
        print('Primary failover preserved data and exact command replay: PASS', flush=True)
        compose('start', leader)
        compose('up', '-d', '--wait', '--wait-timeout', '120')
        # Abrupt primary loss must preserve acknowledged writes, not only graceful switchover.
        expected, _ = client.call('GET', '/data/export')
        crash_leader = next(member['name'] for member in cluster()['members'] if member['role'] == 'leader')
        crash_container = compose('ps', '-q', crash_leader).strip()
        subprocess.run(['docker', 'update', '--restart=no', crash_container], check=True, capture_output=True)
        compose('kill', '-s', 'SIGKILL', crash_leader)
        wait_ready()
        actual, _ = client.call('GET', '/data/export')
        client.assertEqual(expected, actual)
        replay, replay_headers = client.call('POST', '/activities/' + active['id'] + '/stop', headers=stop_key)
        client.assertEqual(stopped, replay)
        client.assertEqual(stopped_headers['ETag'], replay_headers['ETag'])
        client.create('projects', {'name': 'Write after abrupt primary loss'})
        receipt['primaryCrashFailover'] = True
        print('SIGKILL primary loss preserved acknowledged data and command replay: PASS', flush=True)
        compose('start', crash_leader)
        compose('up', '-d', '--wait', '--wait-timeout', '120')
        compose('stop', 'api-1')
        wait_ready()
        replay, replay_headers = client.call('POST', '/activities/' + active['id'] + '/stop', headers=stop_key)
        client.assertEqual(stopped, replay)
        client.assertEqual(stopped_headers['ETag'], replay_headers['ETag'])
        receipt['serverFailover'] = True
        print('API replica failure preserved exact command replay: PASS', flush=True)
        compose('start', 'api-1')
        compose('up', '-d', '--wait', '--wait-timeout', '120')
        expected, _ = client.call('GET', '/data/export')
        # Back up from a separate client; it remains usable after any member changes roles.
        backup_result = subprocess.run(['sh', str(deployment / 'backup.sh')], cwd=deployment,
                                       env={**environment, 'COMPOSE_PROJECT_NAME': project}, check=True, capture_output=True, text=True)
        backup_dir = backup_result.stdout.strip().splitlines()[-1]
        client.create('projects', {'name': 'Must disappear on restore'})
        subprocess.run(['sh', str(deployment / 'restore.sh'), backup_dir], cwd=deployment,
                       env={**environment, 'COMPOSE_PROJECT_NAME': project}, check=True, capture_output=True)
        wait_ready()
        actual, _ = client.call('GET', '/data/export')
        client.assertEqual(expected, actual)
        receipt['backupRestore'] = True
        print('Logical backup/restore preserved all API data: PASS', flush=True)
        # Exercise the real updater against a local registry, including a broken replacement.
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            registry_port = sock.getsockname()[1]
        subprocess.run(['docker', 'run', '-d', '--name', registry_container,
                        '-p', f'127.0.0.1:{registry_port}:5000', 'registry:3'],
                       check=True, capture_output=True)
        registry_started = True
        image = f'localhost:{registry_port}/timekeeper:candidate'
        image_context = temporary / 'image'
        image_context.mkdir()
        dockerfile = image_context / 'Dockerfile'

        def publish_replacement(broken=False):
            dockerfile.write_text(f'FROM {args.server_image}\nLABEL compose-acceptance="{secrets.token_hex(8)}"\n'
                                  + ('ENTRYPOINT ["sh", "-c", "exit 1"]\n' if broken else ''))
            subprocess.run(['docker', 'build', '-t', image, str(image_context)], check=True, capture_output=True)
            subprocess.run(['docker', 'push', image], check=True, capture_output=True)

        def deployed_image(member):
            container = compose('ps', '-q', member).strip()
            return subprocess.check_output(['docker', 'inspect', '--format', '{{.Image}}', container], text=True).strip()

        env_file = deployment / '.env'
        original_environment = env_file.read_text()
        env_file.write_text(original_environment.replace(args.server_image, image))
        publish_replacement()
        update_environment = {**environment, 'COMPOSE_PROJECT_NAME': project, 'TIMEKEEPER_UPDATE_ATTEMPTS': '15'}
        subprocess.run(['sh', str(deployment / 'update.sh')], cwd=deployment, env=update_environment,
                       check=True, capture_output=True)
        replacement_id = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', image], text=True).strip()
        client.assertEqual(replacement_id, deployed_image('api-1'))
        client.assertEqual(replacement_id, deployed_image('api-2'))
        receipt['rollingUpdate'] = True
        publish_replacement(broken=True)
        failed = subprocess.run(['sh', str(deployment / 'update.sh')], cwd=deployment,
                                env=update_environment, capture_output=True)
        client.assertNotEqual(0, failed.returncode)
        client.assertEqual(replacement_id, deployed_image('api-1'))
        client.assertEqual(replacement_id, deployed_image('api-2'))
        wait_ready()
        receipt['failedUpdateRollback'] = True
        env_file.write_text(original_environment)
        print('Rolling image update and broken-image rollback: PASS', flush=True)
        compose('down')
        compose('up', '-d', '--wait', '--wait-timeout', '180')
        wait_ready()
        actual, _ = client.call('GET', '/data/export')
        client.assertEqual(expected, actual)
        receipt['durableRestart'] = True
        (output / 'acceptance.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt, indent=2))
    finally:
        try: (output / 'compose.log').write_text(compose('logs', '--no-color'))
        finally:
            compose('down')
            if registry_started:
                subprocess.run(['docker', 'rm', '-f', registry_container], check=True, capture_output=True)
            # Container data may be owned by root/postgres. Cleanup through Docker below.
            subprocess.run(['docker', 'run', '--rm', '-v', f'{temporary}:/cleanup', 'postgres:18.6-bookworm',
                            'sh', '-c', 'rm -rf /cleanup/state'], check=True, capture_output=True)
            shutil.rmtree(temporary)


if __name__ == '__main__':
    main()
