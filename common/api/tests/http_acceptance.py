#!/usr/bin/env python3
"""Black-box acceptance of a real, isolated Timekeeper HTTP server.

Requires two test owners and a read-only token for owner A. Never run on real data.
No mock server is included; missing configuration is an error, not a skipped test.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
import re
import sys
import unittest
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import uuid4

from jsonschema import Draft202012Validator, FormatChecker
from test_contract import CONTRACT, REGISTRY, URI

BASE_URL = None
TOKENS = {}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward a fixture token to a redirected destination.


def operation(method, path):
    clean = path.split('?')[0]
    item = CONTRACT['paths'].get(clean)
    if item is None:
        for template, candidate in CONTRACT['paths'].items():
            if re.fullmatch(re.sub(r'\\\{[^}]+\\\}', '[^/]+', re.escape(template)), clean):
                item = candidate
                break
    if item is None or method.lower() not in item:
        raise AssertionError(f'No contract operation for {method} {clean}')
    return item[method.lower()]


def resolve(value):
    while '$ref' in value:
        reference = value['$ref']
        value = CONTRACT
        for part in reference.removeprefix('#/').split('/'):
            value = value[part]
    return value


class HttpAcceptance(unittest.TestCase):
    def call(self, method, path, *, owner='a', body=None, headers=None, status=200, code=None):
        request_headers = {'Accept': 'application/json, application/problem+json'}
        if owner is not None:
            request_headers['Authorization'] = 'Bearer ' + TOKENS.get(owner, owner)
        request_headers.update(headers or {})
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            request_headers['Content-Type'] = 'application/json'
        request = Request(BASE_URL + path, data=data, headers=request_headers, method=method)
        try:
            response = build_opener(NoRedirect()).open(request, timeout=35)
        except HTTPError as error:
            response = error
        with response:
            actual = response.status
            response_headers = response.headers
            raw = response.read()
            self.assertIn(actual, status if isinstance(status, tuple) else (status,),
                          f'{method} {path}: expected {status}, got {actual}')
            declared = operation(method, path)['responses']
            self.assertIn(str(actual), declared)
            definition = resolve(declared[str(actual)])
            if actual == 204:
                self.assertEqual(b'', raw)
                return None, response_headers
            media = response.headers.get_content_type()
            self.assertIn(media, definition['content'])
            payload = json.loads(raw)
            schema = definition['content'][media]['schema']
            if '$ref' in schema:
                schema = {'$ref': URI + schema['$ref']}
            Draft202012Validator(schema, registry=REGISTRY, format_checker=FormatChecker()).validate(payload)
            if actual >= 400:
                self.assertEqual(actual, payload['status'])
                if code:
                    self.assertEqual(code, payload['code'])
            return payload, response_headers

    def setUp(self):
        self.created = []
        self.addCleanup(self.clean_records)
        self.prefix = 'HTTP acceptance ' + str(uuid4())
        self.project = self.create('projects', {'name': self.prefix})
        self.task = self.create('tasks', {'summary': self.prefix, 'projectId': self.project['id'],
                                          'parentTaskId': None, 'url': None})

    def create(self, resource, body, owner='a'):
        value, headers = self.call('POST', '/' + resource, body=body, owner=owner, status=201)
        self.assertRegex(headers.get('ETag', ''), CONTRACT['components']['parameters']['IfMatch']['schema']['pattern'])
        self.assertIn('Location', headers)
        self.created.append((owner, resource, value['id']))
        return value

    def clean_records(self):
        # Daily splitting can create extra IDs beneath this suite's own tasks.
        for owner, resource, identifier in list(dict.fromkeys(self.created)):
            if resource != 'tasks': continue
            query = urlencode({'from':'2000-01-01T00:00:00Z','to':'2100-01-01T00:00:00Z','taskId':identifier,'limit':500})
            page, _ = self.call('GET', '/activities?' + query, owner=owner)
            self.assertIsNone(page.get('nextCursor'), 'Fixture cleanup must include every daily record')
            self.created.extend((owner,'activities',value['id']) for value in page['items'])
        # Clean only records created by this suite, including commands/imports.
        order = {'activities': 0, 'tasks': 1, 'labels': 2, 'projects': 3}
        unique = list(dict.fromkeys(reversed(self.created)))
        for owner, resource, identifier in sorted(unique, key=lambda row: order[row[1]]):
            value, headers = self.call('GET', f'/{resource}/{identifier}', owner=owner)
            self.call('DELETE', f'/{resource}/{identifier}', owner=owner,
                      headers={'If-Match': headers['ETag']}, status=204)

    @staticmethod
    def key():
        return {'Idempotency-Key': str(uuid4())}

    def start(self, task, owner='a', expected=None, headers=None, status=201):
        body = {'taskId': task['id'], 'summary': '', 'labelIds': [],
                'expectedActiveId': None if expected is None else expected['id'],
                'expectedActiveVersion': None if expected is None else expected['version']}
        value, response_headers = self.call('POST', '/activities/start', owner=owner,
                                             body=body, headers=headers or self.key(), status=status)
        self.created.append((owner, 'activities', value['id']))
        return value, response_headers, body

    def test_authentication_and_read_only_permission(self):
        for owner in (None, 'invalid-fixture-token'):
            _, headers = self.call('GET', '/projects', owner=owner, status=401, code='UNAUTHENTICATED')
            self.assertIn('Bearer', headers.get('WWW-Authenticate', ''))
        self.call('GET', '/projects/' + self.project['id'], owner='read')
        self.call('POST', '/projects', owner='read', body={'name': self.prefix},
                  status=403, code='FORBIDDEN')
        self.call('POST', '/projects', body={'name': self.prefix, 'ownerId': 'injected'},
                  status=400, code='BAD_REQUEST')

    def test_missing_stale_and_malformed_versions_never_overwrite(self):
        path = '/projects/' + self.project['id']
        original, headers = self.call('GET', path)
        etag = headers['ETag']
        self.call('PUT', path, body={'name': 'Unconditional'}, status=428, code='PRECONDITION_REQUIRED')
        for value in ['*', 'W/' + etag, '""', '"0", "1"']:
            self.call('PUT', path, body={'name': 'Malformed'}, headers={'If-Match': value},
                      status=400, code='BAD_REQUEST')
        updated, _ = self.call('PUT', path, body={'name': self.prefix + ' renamed'}, headers={'If-Match': etag})
        self.assertEqual(original['version'] + 1, updated['version'])
        self.call('PUT', path, body={'name': 'Stale'}, headers={'If-Match': etag},
                  status=412, code='STALE_VERSION')
        self.call('DELETE', path, headers={'If-Match': etag}, status=412, code='STALE_VERSION')
        current, _ = self.call('GET', path)
        self.assertEqual(updated, current)

    def test_concurrent_edits_and_identical_commands(self):
        path = '/projects/' + self.project['id']
        _, current_headers = self.call('GET', path)
        def edit(suffix):
            return self.call('PUT', path, body={'name': self.prefix + suffix},
                             headers={'If-Match': current_headers['ETag']}, status=(200, 412))[0]
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(edit, [' first', ' second']))
        self.assertEqual(1, sum(row.get('code') == 'STALE_VERSION' for row in results))
        self.assertEqual(1, sum('id' in row for row in results))
        self.call('GET', '/activities/active', status=204)
        key = self.key()
        body = {'taskId': self.task['id'], 'summary': '', 'labelIds': [],
                'expectedActiveId': None, 'expectedActiveVersion': None}
        def start(_):
            value, _ = self.call('POST', '/activities/start', body=body, headers=key, status=201)
            self.created.append(('a', 'activities', value['id']))
            return value
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(start, range(2)))
        self.assertEqual(results[0], results[1])
        self.assertFalse(results[0]['manual'])

    def test_profile_zone_is_explicit_and_versioned(self):
        original, headers = self.call('GET', '/me')
        def restore():
            _, latest_headers = self.call('GET', '/me')
            self.call('PUT', '/me', body={'zoneId': original['zoneId']},
                      headers={'If-Match': latest_headers['ETag']})
        self.addCleanup(restore)
        self.call('PUT', '/me', body={'zoneId': 'Invalid/Zone'},
                  headers={'If-Match': headers['ETag']}, status=422, code='VALIDATION')
        zone = 'Europe/Oslo' if original['zoneId'] != 'Europe/Oslo' else 'UTC'
        updated, _ = self.call('PUT', '/me', body={'zoneId': zone}, headers={'If-Match': headers['ETag']})
        self.assertEqual(zone, updated['zoneId'])
        self.assertEqual(original['version'] + 1, updated['version'])
        self.call('PUT', '/me', body={'zoneId': original['zoneId']},
                  headers={'If-Match': headers['ETag']}, status=412, code='STALE_VERSION')
        report, _ = self.call('GET', '/reports/activities?' + urlencode({
            'from': '2026-03-28T23:00:00Z', 'to': '2026-03-29T22:00:00Z', 'taskId': self.task['id']}))
        self.assertEqual(zone, report['zoneId'])

    def test_cross_owner_reads_writes_relations_and_lists(self):
        label = self.create('labels', {'name': self.prefix, 'color': '#19ABCD'})
        activity = self.create('activities', {'taskId': self.task['id'],
            'start': '2026-03-29T00:00:00Z', 'end': '2026-03-29T01:00:00Z',
            'summary': '', 'labelIds': [label['id']]})
        fixtures = [('projects', self.project, {'name': 'Foreign'}),
                    ('tasks', self.task, {'summary': 'Foreign', 'projectId': None,
                                         'parentTaskId': None, 'url': None}),
                    ('labels', label, {'name': 'Foreign', 'color': None}),
                    ('activities', activity, {'start': activity['start'], 'end': activity['end'],
                                             'summary': 'Foreign', 'labelIds': []})]
        for resource, value, body in fixtures:
            path = f'/{resource}/{value["id"]}'
            self.call('GET', path, owner='b', status=404, code='NOT_FOUND')
            self.call('PUT', path, owner='b', body=body, headers={'If-Match': '"999"'},
                      status=404, code='NOT_FOUND')
            self.call('DELETE', path, owner='b', headers={'If-Match': '"999"'},
                      status=404, code='NOT_FOUND')
            own, _ = self.call('GET', path)
            self.assertEqual(value, own)
        self.call('POST', '/tasks', owner='b', body={'projectId': self.project['id'],
                  'parentTaskId': None, 'summary': 'Foreign relation', 'url': None},
                  status=404, code='NOT_FOUND')
        other = self.create('tasks', {'projectId': None, 'parentTaskId': None,
                                     'summary': self.prefix, 'url': None}, owner='b')
        self.call('POST', '/activities', owner='b', body={'taskId': other['id'],
                  'start': activity['start'], 'end': activity['end'], 'summary': '',
                  'labelIds': [label['id']]}, status=404, code='NOT_FOUND')
        page, _ = self.call('GET', '/projects?limit=500', owner='b')
        self.assertNotIn(self.project['id'], [row['id'] for row in page['items']])
        query = urlencode({'from': activity['start'], 'to': activity['end'], 'projectId': self.project['id']})
        self.call('GET', '/activities?' + query, owner='b', status=404, code='NOT_FOUND')

    def test_duplicate_start_stop_and_owner_scoped_command_keys(self):
        self.call('GET', '/activities/active', status=204)
        key = self.key()
        first, first_headers, body = self.start(self.task, headers=key)
        replay, headers = self.call('POST', '/activities/start', body=dict(reversed(list(body.items()))),
                                    headers=key, status=201)
        self.assertEqual(first, replay)
        self.assertEqual(first_headers['ETag'], headers['ETag'])
        other = self.create('tasks', {'projectId': None, 'parentTaskId': None,
                                     'summary': self.prefix, 'url': None}, owner='b')
        second, _, _ = self.start(other, owner='b', headers=key)
        self.assertNotEqual(first['id'], second['id'])
        changed = {**body, 'summary': 'Different command'}
        self.call('POST', '/activities/start', body=changed, headers=key,
                  status=409, code='IDEMPOTENCY_KEY_REUSED')
        stop_key = {**self.key(), 'If-Match': first_headers['ETag']}
        stopped, stopped_headers = self.call('POST', f'/activities/{first["id"]}/stop', headers=stop_key)
        repeated, repeated_headers = self.call('POST', f'/activities/{first["id"]}/stop', headers=stop_key)
        self.assertEqual(stopped, repeated)
        self.assertEqual(stopped_headers['ETag'], repeated_headers['ETag'])
        self.assertIsNotNone(stopped['end'])
        replay_after_stop, _ = self.call('POST', '/activities/start', body=body, headers=key, status=201)
        self.assertEqual(first, replay_after_stop)
        self.call('GET', '/activities/active', status=204)

    def test_atomic_switch_and_active_state_conflicts(self):
        self.call('GET', '/activities/active', status=204)
        first, _, _ = self.start(self.task)
        next_task = self.create('tasks', {'projectId': None, 'parentTaskId': None,
                                         'summary': self.prefix, 'url': None})
        key = self.key()
        second, _, request_body = self.start(next_task, expected=first, headers=key)
        old, _ = self.call('GET', '/activities/' + first['id'])
        self.assertEqual(second['start'], old['end'])
        conflict = {**request_body, 'taskId': self.task['id']}
        self.call('POST', '/activities/start', body=conflict, headers=self.key(),
                  status=409, code='ACTIVE_STATE_CHANGED')
        current, _ = self.call('GET', '/activities/active')
        self.assertEqual(second, current)
        replay, _ = self.call('POST', '/activities/start', body=request_body, headers=key, status=201)
        self.assertEqual(second, replay)
        same, _, _ = self.start(next_task, expected=second, status=200)
        self.assertEqual(second, same)

    def test_provider_identity_is_unique_per_owner_and_history_survives_unlink(self):
        reference = {'providerId': 'jira', 'repositoryId': 'https://jira.invalid',
                     'externalId': str(uuid4()), 'externalUrl': None}
        other = self.create('tasks', {'projectId': None, 'parentTaskId': None,
                                     'summary': self.prefix, 'url': None}, owner='b')
        for owner, task in [('a', self.task), ('b', other)]:
            linked, _ = self.call('PUT', f'/tasks/{task["id"]}/external-references', owner=owner,
                                  body=reference, headers={'If-Match': self.call('GET', '/tasks/' + task['id'], owner=owner)[1]['ETag']})
            query = urlencode({k: reference[k] for k in ['providerId', 'repositoryId', 'externalId']})
            found, _ = self.call('GET', '/tasks/by-external-reference?' + query, owner=owner)
            self.assertEqual(linked['id'], found['id'])
        before, _ = self.call('GET', '/tasks?' + urlencode({'q': self.prefix + ' rejected create'}))
        self.call('POST', '/tasks', body={'projectId': None, 'parentTaskId': None,
                  'summary': self.prefix + ' rejected create', 'url': None,
                  'externalReferences': [reference]}, status=409, code='CONFLICT')
        after, _ = self.call('GET', '/tasks?' + urlencode({'q': self.prefix + ' rejected create'}))
        self.assertEqual(before, after)
        duplicate = self.create('tasks', {'projectId': None, 'parentTaskId': None,
                                         'summary': self.prefix + ' duplicate', 'url': None})
        _, duplicate_headers = self.call('GET', '/tasks/' + duplicate['id'])
        self.call('PUT', f'/tasks/{duplicate["id"]}/external-references', body=reference,
                  headers={'If-Match': duplicate_headers['ETag']}, status=409, code='CONFLICT')
        linked, headers = self.call('GET', '/tasks/' + self.task['id'])
        activity = self.create('activities', {'taskId': linked['id'], 'start': '2026-03-29T00:00:00Z',
            'end': '2026-03-29T01:00:00Z', 'summary': '', 'labelIds': []})
        unlinked, _ = self.call('DELETE', f'/tasks/{linked["id"]}/external-references?' + query,
                                headers={'If-Match': headers['ETag']})
        self.assertEqual([], unlinked['externalReferences'])
        retained, _ = self.call('GET', '/activities/' + activity['id'])
        self.assertEqual(activity, retained)

    def test_daylight_saving_report_preserves_actual_duration(self):
        activity = self.create('activities', {'taskId': self.task['id'], 'start': '2026-03-28T23:00:00Z',
            'end': '2026-03-29T22:00:00Z', 'summary': '', 'labelIds': []})
        query = urlencode({'from': '2026-03-28T23:00:00Z', 'to': '2026-03-29T22:00:00Z',
                           'zoneId': 'Europe/Oslo', 'taskId': self.task['id']})
        report, _ = self.call('GET', '/reports/activities?' + query)
        self.assertEqual('PT23H', report['total'])
        self.assertEqual([{'date': '2026-03-29', 'total': 'PT23H',
                           'tasks': [{'taskId': self.task['id'], 'total': 'PT23H'}]}], report['days'])
        bad = urlencode({'from': activity['start'], 'to': activity['end'], 'zoneId': 'Invalid/Zone'})
        self.call('GET', '/reports/activities?' + bad, status=422, code='VALIDATION')

    def test_cursor_rejects_foreign_owner_and_changed_revision(self):
        for suffix in [' two', ' three']:
            self.create('projects', {'name': self.prefix + suffix})
        query = {'limit': 2, 'q': self.prefix}
        first, _ = self.call('GET', '/projects?' + urlencode(query))
        self.assertEqual(2, len(first['items']))
        self.assertIsNotNone(first['nextCursor'])
        continuation = '/projects?' + urlencode({**query, 'cursor': first['nextCursor']})
        self.call('GET', continuation, owner='b', status=400, code='BAD_REQUEST')
        second, _ = self.call('GET', continuation)
        self.assertEqual(1, len(second['items']))
        self.assertIsNone(second['nextCursor'])
        self.assertEqual(first['revision'], second['revision'])
        self.assertEqual(3, len({row['id'] for row in first['items'] + second['items']}))
        self.create('projects', {'name': self.prefix + ' four'})
        self.call('GET', continuation, status=409, code='CURSOR_EXPIRED')

    def test_import_is_atomic_and_replayed_with_fresh_ids(self):
        source_project, source_task, missing_label = [str(uuid4()) for _ in range(3)]
        data = {'format': 'timekeeper-domain-v1', 'zoneId': 'UTC',
                'projects': [{'id': source_project, 'name': self.prefix}],
                'tasks': [{'id': source_task, 'projectId': source_project, 'parentTaskId': None,
                           'summary': self.prefix, 'url': None, 'externalReferences': []}],
                'labels': [], 'activities': [{'id': str(uuid4()), 'taskId': source_task,
                    'start': '2026-03-29T00:00:00Z', 'end': '2026-03-29T01:00:00Z',
                    'summary': '', 'labelIds': [missing_label], 'manual': True}]}
        before, _ = self.call('GET', '/data/export')
        self.call('POST', '/data/import', body=data, headers=self.key(), status=422, code='VALIDATION')
        after, _ = self.call('GET', '/data/export')
        self.assertEqual(before, after)
        data['activities'][0]['labelIds'] = []
        key = self.key()
        imported, _ = self.call('POST', '/data/import', body=data, headers=key, status=201)
        kinds = {'PROJECT': 'projects', 'TASK': 'tasks', 'LABEL': 'labels', 'ACTIVITY': 'activities'}
        for mapping in imported['ids']:
            self.assertNotEqual(mapping['sourceId'], mapping['id'])
            self.created.append(('a', kinds[mapping['entityType']], mapping['id']))
        self.assertEqual(3, len(imported['ids']))
        replay, _ = self.call('POST', '/data/import', body=data, headers=key, status=201)
        self.assertEqual(imported, replay)
        mapping = {row['sourceId']: row['id'] for row in imported['ids']}
        task, _ = self.call('GET', '/tasks/' + mapping[source_task])
        self.assertEqual(mapping[source_project], task['projectId'])

    def test_change_feed_is_owner_scoped_and_replay_emits_no_duplicate(self):
        head_a, _ = self.call('GET', '/projects')
        head_b, _ = self.call('GET', '/projects', owner='b')
        project = self.create('projects', {'name': self.prefix + ' change'})
        changes, _ = self.call('GET', '/changes?' + urlencode({'after': head_a['revision']}))
        self.assertTrue(any(row['entityId'] == project['id'] for row in changes['items']))
        self.assertTrue(all(row['sequence'] > head_a['revision'] for row in changes['items']))
        foreign, _ = self.call('GET', '/changes?' + urlencode({'after': head_b['revision']}), owner='b')
        self.assertEqual([], foreign['items'])
        self.assertEqual(head_b['revision'], foreign['nextSequence'])
        self.call('GET', '/changes?after=9007199254740991', status=409, code='RESYNC_REQUIRED')
        self.call('GET', '/activities/active', status=204)
        key = self.key()
        first, _, body = self.start(self.task, headers=key)
        head, _ = self.call('GET', '/projects')
        self.call('POST', '/activities/start', body=body, headers=key, status=201)
        replay_changes, _ = self.call('GET', '/changes?' + urlencode({'after': head['revision']}))
        self.assertEqual([], replay_changes['items'])


def main():
    global BASE_URL, TOKENS
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True, help='Isolated test server API base URL')
    args = parser.parse_args()
    BASE_URL = args.base_url.rstrip('/')
    url = urlsplit(BASE_URL)
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password:
        parser.error('Use an HTTP(S) base URL without embedded credentials')
    env = {'a': 'TIMEKEEPER_TEST_TOKEN_A', 'b': 'TIMEKEEPER_TEST_TOKEN_B',
           'read': 'TIMEKEEPER_TEST_TOKEN_READ_ONLY'}
    missing = [name for name in env.values() if not os.environ.get(name)]
    if missing:
        parser.error('Missing test fixture configuration: ' + ', '.join(missing))
    TOKENS = {owner: os.environ[name] for owner, name in env.items()}
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(HttpAcceptance))
    return 0 if result.wasSuccessful() else 1


if __name__ == '__main__':
    sys.exit(main())
