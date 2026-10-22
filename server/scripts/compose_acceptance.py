#!/usr/bin/env python3
"""Exercise the shipped Compose stack, HTTP contract, rolling updates and durable restart."""
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
from threading import Event
import time
import unittest
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'common/api/tests'))
import http_acceptance as contract
from configure_accounts import create_challenge
from compose_readiness import wait_ready as wait_for_replicas
from package_deployment import package as package_deployment
import tarfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-image', default='timekeeper-server:test')
    parser.add_argument('--postgres-image', default='postgres:18.6-bookworm')
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/compose-acceptance')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    (output / 'acceptance.json').unlink(missing_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='timekeeper-compose-'))
    deployment = temporary / 'deploy'
    # Exercise the actual deployment artifact, including its offline migration
    # resources, rather than an incomplete copy of the source directory.
    archive=temporary/'deployment.tar.gz'
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    package_deployment(archive,commit)
    with tarfile.open(archive) as packaged:
        packaged.extractall(deployment,filter='data')
    state = temporary / 'state'
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    project = 'timekeeper-test-' + secrets.token_hex(4)
    (deployment / '.env').write_text(f'TIMEKEEPER_STATE_DIR={state}\nTIMEKEEPER_SERVER_IMAGE={args.server_image}\n'
                                    f'TIMEKEEPER_POSTGRES_IMAGE={args.postgres_image}\nTIMEKEEPER_HTTP_PORT={port}\n'
                                    'TIMEKEEPER_DEPLOYED_AT=2026-10-13T08:30:00Z\n')
    subprocess.run([sys.executable, str(deployment / 'setup.py'), '--owner', 'owner-a'],
                   check=True, stdout=subprocess.DEVNULL)
    setup_code, _ = create_challenge(state / 'config/account-setup.json', owner='owner-a', replace=True)
    (state / 'config/account-setup.json').chmod(0o644)
    tokens = {role: secrets.token_urlsafe(32) for role in ('a', 'b', 'read', 'expired')}
    registry = [{'ownerId': 'owner-b' if role == 'b' else 'owner-a',
                 'sha256': hashlib.sha256(token.encode()).hexdigest(),
                 'permissions': ['read'] if role == 'read' else ['read', 'write'],
                 **({'expiresAt': '2000-01-01T00:00:00Z'} if role == 'expired' else {})}
                for role, token in tokens.items()]
    (state / 'config/tokens.json').write_text(json.dumps(registry))
    environment = dict(os.environ)
    for name in ('TIMEKEEPER_STATE_DIR', 'TIMEKEEPER_SERVER_IMAGE', 'TIMEKEEPER_POSTGRES_IMAGE', 'TIMEKEEPER_HTTP_PORT', 'TIMEKEEPER_DEPLOYED_AT', 'TIMEKEEPER_SERVER_SETUP_FILE', 'TIMEKEEPER_PUBLIC_ORIGIN'):
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

    def wait_ready(members=('api-1', 'api-2')):
        wait_for_replicas(compose, base, members)

    receipt = {'httpContractCases': 0, 'bundledWeb': False, 'twoReplicas': False, 'singleDatabase': False,
               'databaseRestart': False, 'databaseCrashRecovery': False,
               'serverFailover': False, 'durableRestart': False, 'backupRestore': False,
               'rollingUpdate': False, 'failedUpdateRollback': False, 'secondReplicaRollback': False,
               'requestsDuringUpdate': 0, 'requestsDuringRollback': 0,
               'longPollsDuringUpdate': 0, 'longPollsDuringRollback': 0,
               'requestsDuringSecondRollback': 0, 'longPollsDuringSecondRollback': 0}
    registry_container = project + '-registry'
    registry_started = False
    try:
        compose('up', '-d', '--wait', '--wait-timeout', '180')
        wait_ready()
        def server_information(member):
            return json.loads(compose('exec', '-T', member, 'curl', '-fsS', '-H', '@-',
                                     'http://localhost:8080/api/v1/server',
                                     input='Authorization: Bearer ' + tokens['a'] + '\n'))
        initial_information = {member: server_information(member) for member in ('api-1', 'api-2')}
        for information in initial_information.values():
            client.assertEqual('2026-10-13T08:30:00Z', information['deployedAt'])
            client.assertRegex(information['commit'], '^[0-9a-f]{40}$')
        client.assertEqual(initial_information['api-1']['commit'], initial_information['api-2']['commit'])
        with urlopen(base + '/web/', timeout=3) as response:
            client.assertIn(b'id="login-form"', response.read())
            client.assertIn("frame-ancestors 'none'", response.headers.get('Content-Security-Policy', ''))
        for member in ('api-1', 'api-2'):
            for asset in ('', 'app.js', 'api.js', 'model.js', 'style.css', 'favicon.svg'):
                compose('exec', '-T', member, 'curl', '-fsS', '-o', '/dev/null',
                        'http://localhost:8080/web/' + asset)
            identity = json.loads(compose('exec', '-T', member, 'curl', '-fsS', '-H', '@-',
                                         'http://localhost:8080/api/v1/me',
                                         input='Authorization: Bearer ' + tokens['a'] + '\n'))
            client.assertEqual('owner-a', identity['ownerId'])
        # Exercise the same cookie on both actual JVMs; configuration contains no raw credentials.
        def node_request(member, method, path, body=None, cookie=None, csrf=None, expected_status=200, bearer=None):
            options = {'url': 'http://localhost:8080/api/v1' + path, 'request': method,
                       'write-out': '\n%{http_code}'}
            lines = ['silent', 'show-error', 'include']
            if body is not None:
                lines += ['header = ' + json.dumps('Content-Type: application/json')]
                options['data'] = json.dumps(body)
            if cookie: lines += ['header = ' + json.dumps('Cookie: ' + cookie)]
            if csrf: lines += ['header = ' + json.dumps('X-CSRF-Token: ' + csrf)]
            if bearer: lines += ['header = ' + json.dumps('Authorization: Bearer ' + bearer)]
            lines += [key + ' = ' + json.dumps(value) for key,value in options.items()]
            raw = compose('exec', '-T', member, 'curl', '--config', '-', input='\n'.join(lines)+'\n')
            message, status = raw.rsplit('\n', 1)
            # Do not include response text in failures: token issuance is deliberately one-time.
            client.assertEqual(expected_status, int(status), method + ' ' + path)
            header_text, payload = message.split('\n\n', 1)
            headers = dict(line.split(': ',1) for line in header_text.splitlines()[1:] if ': ' in line)
            return (json.loads(payload) if payload.strip() else None), headers
        password = secrets.token_urlsafe(32)
        signed, headers = node_request('api-1','POST','/auth/setup',
                                      {'code':setup_code,'username':'compose-admin','password':password,'displayName':'Compose administrator','email':'compose-admin@example.org'})
        cookie = next(value for key,value in headers.items() if key.lower()=='set-cookie').split(';',1)[0]
        for member in ('api-1','api-2'):
            context, _ = node_request(member,'GET','/auth/session',cookie=cookie)
            client.assertEqual('owner-a',context['ownerId'])
        node_request('api-2','POST','/auth/setup',{'code':setup_code,'username':'imposter','password':password,'displayName':'Imposter','email':'imposter@example.org'},expected_status=401)
        issued, _ = node_request('api-1','POST','/auth/tokens',{'name':'Cross-replica Eclipse','permissions':['read','write'],'expiresAt':None},cookie,signed['csrf'],201)
        raw_token=issued.pop('token')
        for member in ('api-1','api-2'):
            identity=json.loads(compose('exec','-T',member,'curl','-fsS','-H','@-','http://localhost:8080/api/v1/me',input='Authorization: Bearer '+raw_token+'\n'))
            client.assertEqual('owner-a',identity['ownerId'])
        node_request('api-2','DELETE','/auth/tokens/'+issued['id'],cookie=cookie,csrf=signed['csrf'],expected_status=204)
        node_request('api-1','GET','/me',bearer=raw_token,expected_status=401)
        node_request('api-2','POST','/auth/logout',cookie=cookie,csrf=signed['csrf'],expected_status=204)
        node_request('api-1','GET','/auth/session',cookie=cookie,expected_status=401)
        node_request('api-2','POST','/auth/setup',{'code':setup_code,'username':'again','password':password,'displayName':'Again','email':'again@example.org'},expected_status=401)
        receipt['sharedAccountSessionsAndRevocation'] = True
        receipt['twoReplicas'] = True
        receipt['bundledWeb'] = True
        client.assertEqual({'postgres', 'api-1', 'api-2', 'gateway'}, set(compose('ps', '--services').split()))
        roles = compose('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-Atc',
                        "SELECT rolsuper FROM pg_roles WHERE rolname='timekeeper'").strip()
        client.assertEqual('f', roles)
        receipt['singleDatabase'] = True
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
        # One database means planned/failed database restarts interrupt the API.
        compose('stop', 'postgres')
        compose('start', 'postgres')
        compose('up', '-d', '--wait', '--wait-timeout', '120')
        wait_ready()
        actual, _ = client.call('GET', '/data/export')
        client.assertEqual(expected, actual)
        replay, replay_headers = client.call('POST', '/activities/' + active['id'] + '/stop', headers=stop_key)
        client.assertEqual(stopped, replay)
        client.assertEqual(stopped_headers['ETag'], replay_headers['ETag'])
        receipt['databaseRestart'] = True
        container = compose('ps', '-q', 'postgres').strip()
        subprocess.run(['docker', 'update', '--restart=no', container], check=True, capture_output=True)
        compose('kill', '-s', 'SIGKILL', 'postgres')
        compose('start', 'postgres')
        compose('up', '-d', '--wait', '--wait-timeout', '120')
        wait_ready()
        actual, _ = client.call('GET', '/data/export')
        client.assertEqual(expected, actual)
        replay, replay_headers = client.call('POST', '/activities/' + active['id'] + '/stop', headers=stop_key)
        client.assertEqual(stopped, replay)
        client.assertEqual(stopped_headers['ETag'], replay_headers['ETag'])
        receipt['databaseCrashRecovery'] = True
        print('Single PostgreSQL restart and crash recovery preserved data and command replay: PASS', flush=True)
        compose('stop', 'api-1')
        wait_ready(('api-2',))
        replay, replay_headers = client.call('POST', '/activities/' + active['id'] + '/stop', headers=stop_key)
        client.assertEqual(stopped, replay)
        client.assertEqual(stopped_headers['ETag'], replay_headers['ETag'])
        receipt['serverFailover'] = True
        print('API replica failure preserved exact command replay: PASS', flush=True)
        compose('start', 'api-1')
        compose('up', '-d', '--wait', '--wait-timeout', '120')
        wait_ready()
        expected, _ = client.call('GET', '/data/export')
        # Back up and restore through a separate client using the actual application role.
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
        platform = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Os}}/{{.Architecture}}', args.server_image], text=True).strip()
        image_context = temporary / 'image'
        image_context.mkdir()
        dockerfile = image_context / 'Dockerfile'

        def publish_replacement(broken=False, broken_second=False):
            if broken_second:
                (image_context / 'entrypoint.sh').write_text(
                    '#!/bin/sh\nif [ "$(hostname)" = api-2 ]; then exit 1; fi\nexec /app/entrypoint.sh\n')
            dockerfile.write_text(f'FROM {args.server_image}\nLABEL compose-acceptance="{secrets.token_hex(8)}"\n'
                                  + ('ENTRYPOINT ["sh", "-c", "exit 1"]\n' if broken else '')
                                  + ('COPY --chmod=755 entrypoint.sh /app/acceptance-entrypoint.sh\n'
                                     'ENTRYPOINT ["/app/acceptance-entrypoint.sh"]\n' if broken_second else ''))
            subprocess.run(['docker', 'build', '--platform', platform, '-t', image, str(image_context)], check=True, capture_output=True)
            subprocess.run(['docker', 'push', image], check=True, capture_output=True)

        def deployed_image(member):
            container = compose('ps', '-q', member).strip()
            return subprocess.check_output(['docker', 'inspect', '--format', '{{.Image}}', container], text=True).strip()

        env_file = deployment / '.env'
        original_environment = env_file.read_text()
        env_file.write_text(original_environment.replace(args.server_image, image))
        publish_replacement()
        update_environment = {**environment, 'COMPOSE_PROJECT_NAME': project, 'TIMEKEEPER_UPDATE_ATTEMPTS': '15'}
        database_container = compose('ps', '-q', 'postgres').strip()

        def update_with_requests(label):
            finished = Event()
            polling = Event()
            head, _ = client.call('GET', '/projects')

            def probe():
                requests = 0
                while not finished.is_set():
                    projects, _ = client.call('GET', '/projects')
                    client.assertEqual(head['revision'], projects['revision'])
                    requests += 1
                    time.sleep(.1)
                return requests

            def long_polls():
                requests = 0
                while not finished.is_set():
                    polling.set()
                    changes, _ = client.call('GET', '/changes?' + urlencode(
                        {'after': head['revision'], 'waitSeconds': 25}))
                    client.assertEqual([], changes['items'])
                    requests += 1
                return requests

            with ThreadPoolExecutor(max_workers=2) as pool:
                pending = pool.submit(probe)
                pending_polls = pool.submit(long_polls)
                client.assertTrue(polling.wait(5))
                time.sleep(.5)
                try:
                    update = subprocess.run(['sh', str(deployment / 'update.sh')], cwd=deployment,
                                            env=update_environment, capture_output=True, text=True)
                    (output / f'update-{label}.log').write_text(update.stdout + update.stderr)
                finally:
                    finished.set()
                requests = pending.result()
                polls = pending_polls.result()
            client.assertGreater(requests, 10)
            client.assertGreater(polls, 0)
            client.assertEqual(database_container, compose('ps', '-q', 'postgres').strip())
            return update, requests, polls

        updated, receipt['requestsDuringUpdate'], receipt['longPollsDuringUpdate'] = update_with_requests('success')
        if updated.returncode:
            raise AssertionError('Rolling update failed: ' + updated.stderr + updated.stdout)
        replacement_id = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', image], text=True).strip()
        client.assertEqual(replacement_id, deployed_image('api-1'))
        client.assertEqual(replacement_id, deployed_image('api-2'))
        receipt['rollingUpdate'] = True
        deployed_information = {member: server_information(member) for member in ('api-1', 'api-2')}
        deployment_time = deployed_information['api-1']['deployedAt']
        client.assertNotEqual(initial_information['api-1']['deployedAt'], deployment_time)
        client.assertEqual(deployment_time, deployed_information['api-2']['deployedAt'])
        client.assertIn('TIMEKEEPER_DEPLOYED_AT=' + deployment_time, env_file.read_text())
        publish_replacement(broken=True)
        failed, receipt['requestsDuringRollback'], receipt['longPollsDuringRollback'] = update_with_requests('first-rollback')
        client.assertNotEqual(0, failed.returncode)
        client.assertIn('First replica failed replacement', failed.stderr, failed.stdout + failed.stderr)
        client.assertEqual(replacement_id, deployed_image('api-1'), failed.stdout + failed.stderr)
        client.assertEqual(replacement_id, deployed_image('api-2'), failed.stdout + failed.stderr)
        wait_ready()
        receipt['failedUpdateRollback'] = True
        for member in ('api-1', 'api-2'):
            client.assertEqual(deployment_time, server_information(member)['deployedAt'])
        publish_replacement(broken_second=True)
        failed_second, receipt['requestsDuringSecondRollback'], receipt['longPollsDuringSecondRollback'] = update_with_requests('second-rollback')
        client.assertNotEqual(0, failed_second.returncode)
        client.assertIn('Second replica failed replacement', failed_second.stderr, failed_second.stdout + failed_second.stderr)
        client.assertEqual(replacement_id, deployed_image('api-1'))
        client.assertEqual(replacement_id, deployed_image('api-2'))
        wait_ready()
        receipt['secondReplicaRollback'] = True
        for member in ('api-1', 'api-2'):
            client.assertEqual(deployment_time, server_information(member)['deployedAt'])
        receipt['buildAndDeploymentInformation'] = True
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
