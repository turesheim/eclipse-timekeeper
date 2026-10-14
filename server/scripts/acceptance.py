#!/usr/bin/env python3
"""Build-artifact acceptance: real JVM, file database, HTTP contracts and restart."""
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
import zipfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'common/api/tests'))
import http_acceptance as contract


def atomic_json(path, value):
    temporary = path.with_suffix('.new')
    temporary.write_text(json.dumps(value))
    temporary.chmod(0o600)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--distribution', type=Path, help='Verify the packaged ZIP/checksum and run its extracted JVM')
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/acceptance')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    jar = ROOT / 'server/app/target/quarkus-app/quarkus-run.jar'
    if not args.distribution and not jar.is_file():
        parser.error('Build the server with ./mvnw -f server/pom.xml clean verify first')
    data = Path(tempfile.mkdtemp(prefix='Timekeeper HTTP acceptance ', dir=tempfile.gettempdir()))
    distribution_hash = None
    if args.distribution:
        distribution_hash = hashlib.sha256(args.distribution.read_bytes()).hexdigest()
        expected = args.distribution.with_suffix('.zip.sha256').read_text().split()[0]
        if distribution_hash != expected:
            shutil.rmtree(data)
            raise ValueError('Server distribution checksum mismatch')
        with zipfile.ZipFile(args.distribution) as archive:
            for name in archive.namelist():
                if not name.startswith('timekeeper-server/') or '..' in Path(name).parts or Path(name).is_absolute():
                    shutil.rmtree(data)
                    raise ValueError('Unexpected server archive path')
            archive.extractall(data / 'distribution')
        jar = data / 'distribution/timekeeper-server/quarkus-run.jar'
    token_file = data / 'tokens.json'
    tokens = {role: secrets.token_urlsafe(32) for role in ['a', 'b', 'read', 'expired', 'a2']}
    owners = {'a': 'owner-a', 'b': 'owner-b', 'read': 'owner-a', 'expired': 'expired-owner', 'a2': 'owner-a'}
    definitions = [{'ownerId': owners[role], 'sha256': hashlib.sha256(token.encode()).hexdigest(),
                    'permissions': ['read'] if role == 'read' else ['read', 'write'],
                    **({'expiresAt': '2000-01-01T00:00:00Z'} if role == 'expired' else {})}
                   for role, token in tokens.items()]
    atomic_json(token_file, definitions)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    base = f'http://127.0.0.1:{port}/api/v1'
    contract.BASE_URL, contract.TOKENS = base, tokens
    environment = {**os.environ, 'TIMEKEEPER_SERVER_DB_URL': 'jdbc:h2:' + str(data / 'database'),
                   'TIMEKEEPER_SERVER_TOKENS_FILE': str(token_file), 'TIMEKEEPER_SERVER_SETUP_FILE':'NONE', 'TIMEKEEPER_PUBLIC_ORIGIN':'NONE'}
    process = None
    streams = []
    pids = []

    def start(phase):
        nonlocal process
        stream = (output / f'server-{phase}.log').open('w')
        streams.append(stream)
        process = subprocess.Popen([args.java, '-Dquarkus.http.port=' + str(port), '-jar', str(jar)],
                                   cwd=ROOT, env=environment, stdout=stream, stderr=subprocess.STDOUT)
        pids.append(process.pid)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('Server stopped during startup; inspect its acceptance log')
            try:
                with urlopen(base.replace('/api/v1', '/q/health/ready'), timeout=1) as response:
                    if response.status == 200:
                        return
            except (HTTPError, URLError, TimeoutError):
                time.sleep(.1)
        raise RuntimeError('Server did not become ready within the acceptance deadline')

    def stop(force=False):
        nonlocal process
        if process is not None:
            if force:
                process.kill()
            else:
                process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            process = None

    client = contract.HttpAcceptance('test_authentication_and_read_only_permission')
    client.created = []
    receipt = {'quarkus': '3.40.1', 'h2': '2.5.250', 'pids': pids,
               'httpContractCases': 0, 'restartVerified': False, 'forcedRestartVerified': False,
               'revocationVerified': False, 'longPollVerified': False, 'distributionSha256': distribution_hash}
    try:
        start('first')
        expected_labels = {'Production issue': '#F46758', 'Testing': '#F5A651',
                           'Prototyping': '#F6D05A', 'Planning': '#FF00FF',
                           'Programming': '#57CE69', 'Debugging': '#B16FD1',
                           'Communication': '#4288F3', 'Meeting': '#9C9CA0'}
        initial_labels = {}
        for owner in ('a', 'b'):
            page, _ = client.call('GET', '/labels', owner=owner)
            client.assertEqual(expected_labels, {item['name']: item['color'] for item in page['items']})
            initial_labels[owner] = {item['id'] for item in page['items']}
        client.assertTrue(initial_labels['a'].isdisjoint(initial_labels['b']))
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(contract.HttpAcceptance)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        if not result.wasSuccessful() or result.skipped:
            raise RuntimeError('HTTP contract acceptance failed or skipped required cases')
        receipt['httpContractCases'] = result.testsRun
        # Exercise hierarchy conflicts through HTTP and verify late import rollback.
        hierarchy_project = client.create('projects', {'name': 'Hierarchy acceptance'})
        parent = client.create('tasks', {'summary': 'Parent', 'projectId': hierarchy_project['id'],
                                        'parentTaskId': None, 'url': None})
        child = client.create('tasks', {'summary': 'Child', 'projectId': hierarchy_project['id'],
                                       'parentTaskId': parent['id'], 'url': None})
        _, parent_headers = client.call('GET', '/tasks/' + parent['id'])
        client.call('PUT', '/tasks/' + parent['id'], body={'summary': 'Cycle', 'projectId': hierarchy_project['id'],
                    'parentTaskId': child['id'], 'url': None}, headers={'If-Match': parent_headers['ETag']},
                    status=409, code='CONFLICT')
        client.call('DELETE', '/tasks/' + parent['id'], headers={'If-Match': parent_headers['ETag']},
                    status=409, code='CONFLICT')
        other_project = client.create('projects', {'name': 'Other hierarchy project'})
        client.call('PUT', '/tasks/' + parent['id'], body={'summary': 'Move', 'projectId': other_project['id'],
                    'parentTaskId': None, 'url': None}, headers={'If-Match': parent_headers['ETag']},
                    status=409, code='CONFLICT')
        source_project, source_first, source_late = [str(uuid4()) for _ in range(3)]
        provider = {'providerId': 'jira', 'repositoryId': 'https://jira.invalid', 'externalId': 'rollback-' + str(uuid4()), 'externalUrl': None}
        linked = client.create('tasks', {'summary': 'Existing identity', 'projectId': None,
                                        'parentTaskId': None, 'url': None, 'externalReferences': [provider]})
        transfer = {'format': 'timekeeper-domain-v1', 'zoneId': 'UTC', 'labels': [], 'activities': [],
                    'projects': [{'id': source_project, 'name': 'Must roll back'}],
                    'tasks': [{'id': source_first, 'summary': 'Earlier write', 'projectId': source_project,
                               'parentTaskId': None, 'url': None, 'externalReferences': []},
                              {'id': source_late, 'summary': 'Late conflict', 'projectId': source_project,
                               'parentTaskId': None, 'url': None, 'externalReferences': [provider]}]}
        import_key = client.key()
        before_failure, _ = client.call('GET', '/data/export')
        before_head, _ = client.call('GET', '/projects')
        client.call('POST', '/data/import', body=transfer, headers=import_key, status=409, code='CONFLICT')
        after_failure, _ = client.call('GET', '/data/export')
        after_head, _ = client.call('GET', '/projects')
        client.assertEqual(before_failure, after_failure)
        client.assertEqual(before_head['revision'], after_head['revision'])
        transfer['tasks'][1]['externalReferences'] = []
        imported, _ = client.call('POST', '/data/import', body=transfer, headers=import_key, status=201)
        for mapping in imported['ids']:
            client.created.append(('a', 'projects' if mapping['entityType'] == 'PROJECT' else 'tasks', mapping['id']))
        # Exact nanoseconds and the 25-hour autumn DST day survive the real persistence adapter.
        client.create('activities', {'taskId': child['id'], 'start': '2026-03-29T00:00:00.123456789Z',
                                     'end': '2026-03-29T00:00:00.123456790Z', 'summary': '', 'labelIds': []})
        nano, _ = client.call('GET', '/reports/activities?' + urlencode({'taskId': child['id'],
                             'from': '2026-03-29T00:00:00Z', 'to': '2026-03-29T01:00:00Z'}))
        client.assertEqual('PT0.000000001S', nano['total'])
        client.create('activities', {'taskId': child['id'], 'start': '2025-10-25T22:00:00Z',
                                     'end': '2025-10-26T23:00:00Z', 'summary': '', 'labelIds': []})
        autumn, _ = client.call('GET', '/reports/activities?' + urlencode({'taskId': child['id'],
                               'from': '2025-10-25T22:00:00Z', 'to': '2025-10-26T23:00:00Z', 'zoneId': 'Europe/Oslo'}))
        client.assertEqual('PT25H', autumn['total'])
        receipt['hierarchyRollbackAndPrecisionVerified'] = True
        # JSON Schema length is measured in code points; SQL columns must also fit surrogate pairs.
        unicode_ref = {'providerId': '😀' * 100, 'repositoryId': '😀' * 3000,
                       'externalId': '😀' * 800, 'externalUrl': None}
        unicode_task = client.create('tasks', {'summary': 'Unicode provider identity', 'projectId': None,
                                             'parentTaskId': None, 'url': None, 'externalReferences': [unicode_ref]})
        found, _ = client.call('GET', '/tasks/by-external-reference?' + urlencode({k: unicode_ref[k]
                              for k in ['providerId', 'repositoryId', 'externalId']}))
        client.assertEqual(unicode_task['id'], found['id'])
        # Persist a provider link, assigned label and open interval, then reopen in another JVM.
        project = client.create('projects', {'name': 'Restart acceptance'})
        task = client.create('tasks', {'summary': 'Durable issue', 'projectId': project['id'],
                                      'parentTaskId': None, 'url': None,
                                      'externalReferences': [{'providerId': 'jira', 'repositoryId': 'https://jira.invalid',
                                                              'externalId': 'restart-123', 'externalUrl': None}]})
        label = client.create('labels', {'name': 'Durable label', 'color': '#19ABCD'})
        key = client.key()
        body = {'taskId': task['id'], 'summary': 'Durable tracking', 'labelIds': [label['id']],
                'expectedActiveId': None, 'expectedActiveVersion': None}
        active, active_headers = client.call('POST', '/activities/start', body=body, headers=key, status=201)
        client.created.append(('a', 'activities', active['id']))
        before, _ = client.call('GET', '/data/export')
        stop()
        start('restart')
        after, _ = client.call('GET', '/data/export')
        client.assertEqual(before, after)
        current, _ = client.call('GET', '/activities/active')
        client.assertEqual(active, current)
        replay, headers = client.call('POST', '/activities/start', body=body, headers=key, status=201)
        client.assertEqual(active, replay)
        client.assertEqual(active_headers['ETag'], headers['ETag'])
        receipt['restartVerified'] = True
        receipt['defaultLabelsVerified'] = True
        stop_key = {**client.key(), 'If-Match': active_headers['ETag']}
        ended, ended_headers = client.call('POST', f'/activities/{active["id"]}/stop', headers=stop_key)
        stop(force=True)
        start('forced-restart')
        replay_end, headers = client.call('POST', f'/activities/{active["id"]}/stop', headers=stop_key)
        client.assertEqual(ended, replay_end)
        client.assertEqual(ended_headers['ETag'], headers['ETag'])
        client.call('GET', '/activities/active', status=204)
        receipt['forcedRestartVerified'] = True
        # Revocation and downgraded permission are checked before any saved response is replayed.
        client.call('GET', '/projects', owner='expired', status=401, code='UNAUTHENTICATED')
        without_a = [row for row in definitions if row['sha256'] != hashlib.sha256(tokens['a'].encode()).hexdigest()]
        atomic_json(token_file, without_a)
        _, challenge = client.call('POST', f'/activities/{active["id"]}/stop', headers=stop_key,
                                    status=401, code='UNAUTHENTICATED')
        client.assertIn('invalid_token', challenge['WWW-Authenticate'])
        reduced = [dict(row, permissions=['read']) if row['sha256'] == hashlib.sha256(tokens['a'].encode()).hexdigest()
                   else row for row in definitions]
        atomic_json(token_file, reduced)
        client.call('POST', f'/activities/{active["id"]}/stop', headers=stop_key, status=403, code='FORBIDDEN')
        atomic_json(token_file, definitions)
        client.call('POST', f'/activities/{active["id"]}/stop', headers=stop_key)
        receipt['revocationVerified'] = True
        # A real pending long poll observes a committed operation without holding the owner lock.
        head, _ = client.call('GET', '/projects')
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(client.call, 'GET', '/changes?' + urlencode({'after': head['revision'], 'waitSeconds': 2}))
            time.sleep(.2)
            changed = client.create('projects', {'name': 'Pending poll change'})
            page, _ = future.result(timeout=5)
        client.assertTrue(any(row['entityId'] == changed['id'] for row in page['items']))
        head, _ = client.call('GET', '/projects')
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(client.call, 'GET', '/changes?' + urlencode({'after': head['revision'], 'waitSeconds': 2}),
                                 status=401, code='UNAUTHENTICATED')
            time.sleep(.2)
            atomic_json(token_file, without_a)
            future.result(timeout=5)
        atomic_json(token_file, definitions)
        receipt['longPollVerified'] = True
        # Readiness reflects a malformed registry rather than merely a live TCP port.
        atomic_json(token_file, {})
        client.call('GET', '/projects', status=503, code='UNAVAILABLE')
        try:
            urlopen(base.replace('/api/v1', '/q/health/ready'), timeout=3)
            raise AssertionError('Malformed token registry unexpectedly reported ready')
        except HTTPError as error:
            client.assertEqual(503, error.code)
        atomic_json(token_file, definitions)
        receipt['readinessVerified'] = True
        client.clean_records()
        receipt['resourceIds'] = {'project': project['id'], 'task': task['id'], 'label': label['id'], 'activity': active['id']}
        receipt['openActivity'] = active
        receipt['endedActivity'] = ended
        (output / 'acceptance.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print('Server restart, forced restart, durable replay, revocation and long polling: PASS')
        return 0
    finally:
        stop()
        for stream in streams:
            stream.close()
        shutil.rmtree(data)


if __name__ == '__main__':
    raise SystemExit(main())
