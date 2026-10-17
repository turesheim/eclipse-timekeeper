#!/usr/bin/env python3
"""Validate import/Undo authority on two disposable JVM replicas and PostgreSQL.

Never run against an existing database: this script creates and removes its own
containers, credentials, registry and protected backup. Only fixture API ports
are published, bound to loopback. No live account or NAS is touched.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def command(*args, check=True):
    reply = subprocess.run(args, capture_output=True, text=True, timeout=60)
    if check and reply.returncode:
        raise RuntimeError('Isolated fixture operation failed: ' + args[1])
    return reply


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-image', required=True)
    parser.add_argument('--postgres-image', default='postgres:18.6-bookworm')
    parser.add_argument('--output', type=Path, default=Path('server/target/toggl-replica-acceptance.json'))
    args = parser.parse_args()
    suffix = secrets.token_hex(5)
    database = 'timekeeper-import-db-' + suffix
    names = []
    password = secrets.token_urlsafe(32)
    tokens = {owner: secrets.token_urlsafe(32) for owner in ('a', 'b')}
    evidence = {'twoReplicas': False, 'concurrentBatch': False, 'restartReceipt': False,
                'exactTime': False, 'ownerIsolation': False, 'laterImportProtection': False,
                'editedDerivedParts': False, 'boundedUndo': False, 'protectedRestore': False,
                'deliberateReimport': False, 'oldUndoReplay': False, 'workSessionDurability': False,
                'workSessionUndo': False, 'workSessionModeIsolation': False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.unlink(missing_ok=True)
    args.output.with_suffix('.failure.log').unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='timekeeper-import-replicas-') as temporary:
        folder = Path(temporary)
        config = folder / 'config'
        config.mkdir(mode=0o755)
        registry = config / 'tokens.json'
        registry.write_text(json.dumps([{'ownerId': 'import-' + owner, 'sha256': digest(token),
                                        'permissions': ['read', 'write']} for owner, token in tokens.items()]))
        registry.chmod(0o644)
        try:
            env = folder / 'database.env'
            env.write_text('POSTGRES_PASSWORD=' + password + '\n')
            env.chmod(0o600)
            command('docker', 'run', '-d', '--name', database, '--env-file', str(env),
                    '-p', '127.0.0.1::8081', '-p', '127.0.0.1::8082', args.postgres_image)
            names.append(database)

            def database_ready():
                for _ in range(120):
                    if command('docker', 'exec', database, 'pg_isready', '-U', 'postgres', check=False).returncode == 0:
                        return
                    time.sleep(.1)
                raise RuntimeError('Isolated import database did not become ready')

            database_ready()
            bases = {}
            def refresh_ports():
                # Docker may reallocate ephemeral published ports when its namespace container restarts.
                bases.update({index: 'http://127.0.0.1:' + command('docker', 'port', database, str(8080 + index) + '/tcp').stdout.strip().rsplit(':', 1)[1]
                              for index in (1, 2)})
            refresh_ports()

            def stop(index):
                name = 'timekeeper-import-api-' + str(index) + '-' + suffix
                if name in names:
                    command('docker', 'rm', '-f', name)
                    names.remove(name)

            def start(index):
                stop(index)
                name = 'timekeeper-import-api-' + str(index) + '-' + suffix
                environment = {'TIMEKEEPER_SERVER_DB_URL': 'jdbc:postgresql://127.0.0.1:5432/postgres',
                               'TIMEKEEPER_SERVER_DB_USER': 'postgres', 'TIMEKEEPER_SERVER_DB_PASSWORD': password,
                               'TIMEKEEPER_SERVER_TOKENS_FILE': '/config/tokens.json',
                               'QUARKUS_HTTP_PORT': str(8080 + index), 'TIMEKEEPER_PUBLIC_ORIGIN': 'NONE'}
                env_file = folder / ('api-' + str(index) + '.env')
                env_file.write_text(''.join(key + '=' + value + '\n' for key, value in environment.items()))
                env_file.chmod(0o600)
                command('docker', 'run', '-d', '--name', name, '--network', 'container:' + database,
                        '--read-only', '--tmpfs', '/tmp', '--env-file', str(env_file),
                        '-v', str(config) + ':/config:ro', args.server_image)
                names.append(name)
                for _ in range(160):
                    try:
                        with urlopen(bases[index] + '/q/health/ready', timeout=1) as response:
                            if response.status == 200:
                                return
                    except (OSError, HTTPError):
                        pass
                    time.sleep(.1)
                failure = command('docker', 'logs', '--tail', '60', name, check=False)
                log = failure.stdout + failure.stderr
                for secret in [password, *tokens.values()]:
                    log = log.replace(secret, '[redacted]')
                log_path = args.output.with_suffix('.failure.log')
                log_path.write_text(log)
                log_path.chmod(0o600)
                raise RuntimeError('Isolated import replica did not become ready; see the sanitized failure log')

            def api(index, method, path, body=None, owner='a', key=None, etag=None):
                headers = {'Authorization': 'Bearer ' + tokens[owner]}
                if body is not None:
                    headers['Content-Type'] = 'application/json'
                if key:
                    headers['Idempotency-Key'] = key
                if etag:
                    headers['If-Match'] = etag
                request = Request(bases[index] + '/api/v1' + path, method=method, headers=headers,
                                  data=None if body is None else json.dumps(body).encode())
                try:
                    response = urlopen(request, timeout=30)
                except HTTPError as failure:
                    response = failure
                with response:
                    data = response.read()
                    return response.status, json.loads(data) if data else None, response.headers

            def expect(reply, status=200):
                if reply[0] != status:
                    raise AssertionError('Import fixture API returned ' + str(reply[0]) + ' instead of ' + str(status))
                return reply[1]

            def get(index, path, **kwargs):
                return expect(api(index, 'GET', path, **kwargs))

            def job_path(job):
                return '/imports/jobs/' + job['id']

            def batch(job, rows, index, finished):
                preview = get_preview(job, rows)
                return {'rows': rows, 'reviewToken': preview['reviewToken'], 'finished': finished}

            def get_preview(job, rows):
                return expect(api(1, 'POST', job_path(job) + '/preview', {'rows': rows}))

            def begin_undo(job):
                current = api(1, 'GET', job_path(job))
                return expect(api(2, 'POST', job_path(job) + '/undo', key=str(uuid.uuid4()), etag=current[2]['ETag']))

            def undo(job, *, review_edits=False):
                if get(1, job_path(job))['state'] != 'UNDOING':
                    begin_undo(job)
                first = None
                for _ in range(100):
                    current = get(1, job_path(job))
                    if current['state'].startswith('UNDONE'):
                        return current, first
                    preview = get(2, job_path(job) + '/undo/preview')
                    if len(preview['targets']) > 100:
                        raise AssertionError('Undo exceeded its materialized record bound')
                    choices = {}
                    for target in preview['targets']:
                        if target['status'] == 'REVIEW' and review_edits:
                            choices[target['key']] = 'DELETE'
                        elif target['status'] not in ('DELETE', 'MISSING', 'REUSED'):
                            raise AssertionError('Unreviewed fixture Undo conflict: ' + target['status'])
                    body = {'reviewToken': preview['reviewToken'], 'choices': choices}
                    index = current['undoNextBatch']
                    reply = expect(api(2, 'POST', job_path(job) + '/undo/batches/' + str(index), body))
                    if first is None:
                        first = (index, body, reply)
                    assert expect(api(1, 'POST', job_path(job) + '/undo/batches/' + str(index), body)) == reply
                raise AssertionError('Fixture Undo failed to make bounded progress')

            def mapping(key, kind, name, parent=None):
                value = {'key': digest(key), 'resourceType': kind, 'action': 'CREATE', 'name': name, 'color': '#6750a4'}
                if parent:
                    value['parentKey'] = digest(parent)
                return value

            definitions = [mapping('client', 'CLIENT', 'Private import client'), mapping('project', 'PROJECT', 'Private import project', 'client'),
                           mapping('task', 'TASK', 'Task title', 'project'), mapping('label', 'LABEL', 'Source tag')]
            source_key = digest('selected source member and workspace')
            rows = []
            for n in range(201):
                start_time = datetime(2025, 10, 27, 8, tzinfo=timezone.utc) + timedelta(seconds=n)
                start_text = start_time.isoformat().replace('+00:00', 'Z')
                end_text = (start_time + timedelta(seconds=1)).isoformat().replace('+00:00', 'Z')
                if n == 0:
                    start_text, end_text = '2025-10-25T20:00:00.000000001Z', '2025-10-26T03:00:00.000000003Z'
                rows.append({'sourceKey': source_key, 'sourceId': 'source-' + str(n), 'start': start_text, 'end': end_text,
                             'durationNanos': '25200000000002' if n == 0 else '1000000000',
                             'client': 'Source client', 'project': 'Source project', 'task': 'Task title',
                             'summary': 'Independent description ' + str(n), 'tags': ['Source tag'],
                             'projectKey': digest('project'), 'taskKey': digest('task'), 'labelKeys': [digest('label')], 'occurrence': 0})

            def source(selected, label='Fixture history'):
                return {'label': label, 'sourceKey': source_key, 'sourceZone': 'Europe/Oslo', 'sourceDateFormat': 'ISO',
                        'fileFingerprint': digest(label), 'mappings': definitions, 'selectedEntries': len(selected),
                        'selectedNanos': str(sum(int(row['durationNanos']) for row in selected))}

            start(1)
            start(2)
            evidence['twoReplicas'] = True
            profile = api(1, 'GET', '/me')
            expect(api(2, 'PUT', '/me', {'zoneId': 'Europe/Oslo'}, etag=profile[2]['ETag']))
            preview = expect(api(1, 'POST', '/imports/toggl/preview', {'sourceKey': source_key, 'mappings': definitions, 'rows': rows[:100]}))
            assert preview['dailyActivities'] == 101 and get(2, '/projects')['items'] == []
            key = str(uuid.uuid4())
            job = expect(api(1, 'POST', '/imports/toggl/jobs', source(rows), key=key), 201)
            assert expect(api(2, 'POST', '/imports/toggl/jobs', source(rows), key=key), 201) == job
            first_body = batch(job, rows[:100], 0, False)
            with ThreadPoolExecutor(2) as workers:
                requests = [workers.submit(api, index, 'POST', job_path(job) + '/batches/0', first_body) for index in (1, 2)]
                replies = [expect(request.result()) for request in requests]
            assert replies[0] == replies[1]
            assert get(2, job_path(job))['importedEntries'] == 100
            evidence['concurrentBatch'] = True

            # An explicit session job shares the ledger but has independent source claims.
            session_rows = [{**{key: value for key, value in row.items() if key not in ('projectKey', 'taskKey', 'labelKeys')},
                             'clientKey': digest('client'), 'title': row['summary']} for row in rows[:2]]
            session_input = {**source(session_rows, 'Session history'), 'mode': 'WORK_SESSION', 'mappings': definitions[:1]}
            session_job = expect(api(2, 'POST', '/imports/toggl/jobs', session_input, key=str(uuid.uuid4())), 201)
            session_body = batch(session_job, session_rows[:1], 0, False)
            with ThreadPoolExecutor(2) as workers:
                session_replies = [expect(future.result()) for future in [workers.submit(api, index, 'POST', job_path(session_job) + '/batches/0', session_body) for index in (1, 2)]]
            assert session_replies[0] == session_replies[1] and session_replies[0]['workSessionCount'] == 1
            assert get(1, job_path(session_job))['importedNanos'] == rows[0]['durationNanos']
            expect(api(1, 'GET', job_path(session_job), owner='b'), 404)
            evidence['workSessionModeIsolation'] = True

            backup = folder / 'database.dump'
            with backup.open('wb') as output:
                result = subprocess.run(['docker', 'exec', database, 'pg_dump', '-U', 'postgres', '--format=custom', 'postgres'], stdout=output, stderr=subprocess.PIPE, timeout=60)
                if result.returncode:
                    raise RuntimeError('Protected fixture backup failed')
            backup.chmod(0o600)
            stop(1)
            stop(2)
            command('docker', 'restart', database)
            database_ready()
            refresh_ports()
            start(1)
            start(2)
            assert expect(api(2, 'POST', job_path(job) + '/batches/0', first_body)) == replies[0]
            assert get(1, job_path(job))['nextBatch'] == 1
            assert expect(api(1, 'POST', job_path(session_job) + '/batches/0', session_body)) == session_replies[0]
            assert get(2, job_path(session_job))['nextBatch'] == 1
            evidence['restartReceipt'] = True
            changed = {**first_body, 'finished': True}
            expect(api(1, 'POST', job_path(job) + '/batches/0', changed), 409)
            expect(api(2, 'POST', job_path(job) + '/batches/1', batch(job, rows[100:200], 1, False)))
            expect(api(1, 'POST', job_path(job) + '/batches/2', batch(job, rows[200:], 2, True)))
            current = get(2, job_path(job))
            assert current['importedEntries'] == 201 and current['activityCount'] == 202 and current['importedNanos'] == '25400000000002'
            report = get(1, '/reports/activities?from=2025-10-25T00:00:00Z&to=2025-10-28T00:00:00Z&zoneId=Europe%2FOslo')
            assert report['total'] == 'PT7H3M20.000000002S'
            evidence['exactTime'] = True
            expect(api(1, 'GET', job_path(job), owner='b'), 404)
            expect(api(2, 'GET', job_path(job) + '/items', owner='b'), 404)
            assert get(2, '/imports', owner='b')['items'] == []
            evidence['ownerIsolation'] = True

            repeated = expect(api(2, 'POST', '/imports/toggl/jobs', source(rows, 'Renamed overlapping export'), key=str(uuid.uuid4())), 201)
            for index in range(3):
                selected = rows[index * 100:(index + 1) * 100]
                expect(api(1, 'POST', job_path(repeated) + '/batches/' + str(index), batch(repeated, selected, index, index == 2)))
            assert get(2, job_path(repeated))['catalogCount'] == 0
            assert get(1, job_path(repeated))['skippedNanos'] == '25400000000002'
            begin_undo(job)
            blocked = get(2, job_path(job) + '/undo/preview')
            assert blocked['targets'] and all(target['status'] == 'REFERENCED' for target in blocked['targets'])
            expect(api(1, 'POST', job_path(job) + '/undo/batches/0', {'reviewToken': blocked['reviewToken'], 'choices': {target['key']: 'DELETE' for target in blocked['targets']}}), 409)
            assert undo(repeated)[0]['state'] == 'UNDONE'
            expect(api(2, 'POST', job_path(job) + '/undo/batches/0', {'reviewToken': blocked['reviewToken'], 'choices': {}}), 412)
            evidence['laterImportProtection'] = True

            exported = get(1, '/data/export')
            activity = next(value for value in exported['activities'] if value['summary'] == 'Independent description 0' and value['start'].startswith('2025-10-25T20:'))
            item = api(2, 'GET', '/activities/' + activity['id'])
            expect(api(1, 'PUT', '/activities/' + activity['id'], {'start': '2025-10-24T21:00:00Z', 'end': activity['end'], 'summary': 'Edited after import', 'labelIds': activity['labelIds']}, etag=item[2]['ETag']))
            before_undo = get(2, job_path(job) + '/items')['items']
            entry = next(value for value in before_undo if value.get('kind') == 'ENTRY')
            assert len(entry['parts']) == 3 and entry['changedSinceImport']
            assert undo(job, review_edits=True)[0]['state'] == 'UNDONE'
            assert get(2, '/data/export')['activities'] == [] and get(1, '/projects')['items'] == []
            evidence['editedDerivedParts'] = evidence['boundedUndo'] = True

            stop(1)
            stop(2)
            with backup.open('rb') as input_file:
                result = subprocess.run(['docker', 'exec', '-i', database, 'pg_restore', '-U', 'postgres', '--clean', '--if-exists', '--dbname', 'postgres'], stdin=input_file, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=60)
                if result.returncode:
                    raise RuntimeError('Protected fixture restore failed')
            start(1)
            start(2)
            restored = get(2, job_path(job))
            assert restored['state'] == 'IMPORTING' and restored['nextBatch'] == 1
            assert expect(api(1, 'POST', job_path(job) + '/batches/0', first_body)) == replies[0]
            expect(api(2, 'GET', job_path(repeated)), 404)
            assert len(get(1, '/data/export')['activities']) == 101
            evidence['protectedRestore'] = True
            undone, old_receipt = undo(job)
            assert undone['state'] == 'UNDONE'
            replacement = expect(api(2, 'POST', '/imports/toggl/jobs', source(rows[:100], 'Deliberate reimport'), key=str(uuid.uuid4())), 201)
            expect(api(1, 'POST', job_path(replacement) + '/batches/0', batch(replacement, rows[:100], 0, True)))
            assert get(2, job_path(replacement))['activityCount'] == 101
            evidence['deliberateReimport'] = True
            index, body, receipt = old_receipt
            assert expect(api(1, 'POST', job_path(job) + '/undo/batches/' + str(index), body)) == receipt
            assert len(get(2, '/data/export')['activities']) == 101
            evidence['oldUndoReplay'] = True
            assert get(2, job_path(session_job))['state'] == 'IMPORTING'
            assert expect(api(1, 'POST', job_path(session_job) + '/batches/0', session_body)) == session_replies[0]
            before_sessions = get(2, '/data/export')
            expect(api(1, 'POST', job_path(session_job) + '/batches/1', batch(session_job, session_rows[1:], 1, True)))
            completed_sessions = get(2, '/data/export')
            assert len(completed_sessions['workSessions']) == 2
            for collection in ('activities', 'projects', 'tasks', 'labels'):
                assert len(completed_sessions[collection]) == len(before_sessions[collection])
            assert get(2, job_path(session_job))['workSessionCount'] == 2
            evidence['workSessionDurability'] = True
            current_session = completed_sessions['workSessions'][0]
            session_current = api(2, 'GET', '/work-sessions/' + current_session['id'])
            expect(api(1, 'PUT', '/work-sessions/' + current_session['id'],
                       {**{key: current_session[key] for key in ('start', 'end', 'title', 'clientId')}, 'summary': 'Edited attendance'}, etag=session_current[2]['ETag']))
            repeat_sessions = expect(api(2, 'POST', '/imports/toggl/jobs', session_input, key=str(uuid.uuid4())), 201)
            assert any(row['status'] == 'REUSED_EDITED' for row in get_preview(repeat_sessions, session_rows)['rows'])
            expect(api(1, 'POST', job_path(repeat_sessions) + '/batches/0', batch(repeat_sessions, session_rows, 0, True)))
            assert get(2, job_path(repeat_sessions))['skippedEntries'] == 2
            begin_undo(session_job)
            assert all(target['status'] == 'REFERENCED' for target in get(2, job_path(session_job) + '/undo/preview')['targets'])
            assert undo(repeat_sessions)[0]['state'] == 'UNDONE'
            assert undo(session_job, review_edits=True)[0]['state'] == 'UNDONE'
            assert get(2, '/data/export')['workSessions'] == []
            assert len(get(1, '/data/export')['activities']) == 101
            new_sessions = expect(api(2, 'POST', '/imports/toggl/jobs', session_input, key=str(uuid.uuid4())), 201)
            expect(api(1, 'POST', job_path(new_sessions) + '/batches/0', batch(new_sessions, session_rows, 0, True)))
            assert len(get(2, '/data/export')['workSessions']) == 2
            evidence['workSessionUndo'] = True
            evidence['sourceCommit'] = get(1, '/server')['commit']
            evidence['postgresImage'] = args.postgres_image
            args.output.write_text(json.dumps(evidence, indent=2) + '\n')
            print('PASS: PostgreSQL import/Undo replicas, exact time, races, restart, protected restore and re-import')
        finally:
            for name in reversed(names):
                command('docker', 'rm', '-f', '-v', name, check=False)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
