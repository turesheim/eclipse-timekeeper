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
        order = {'activities': 0, 'work-sessions': 1, 'tasks': 2, 'labels': 3, 'projects': 4, 'clients': 5}
        unique = list(dict.fromkeys(reversed(self.created)))
        for owner, resource, identifier in sorted(unique, key=lambda row: order[row[1]]):
            value, headers = self.call('GET', f'/{resource}/{identifier}', owner=owner)
            self.call('DELETE', f'/{resource}/{identifier}', owner=owner,
                      headers={'If-Match': headers['ETag']}, status=204)

    def test_private_journal_bodies_have_lightweight_summaries_and_conditional_lifecycles(self):
        date = '2070-02-03'
        path = '/day-notes/' + date
        query = '/day-notes?' + urlencode({'fromDate': date, 'toDate': date})

        def cleanup():
            _, current = self.call('GET', path)
            self.call('DELETE', path, headers={'If-Match': current['ETag']}, status=204)
        self.addCleanup(cleanup)
        initial, headers = self.call('GET', path)
        self.assertEqual('', initial['body'])
        markdown = '\r\n# Private journal fixture\r\n- [ ] Øvelse\t\r\n  keep spaces  \r\n'
        note, saved = self.call('PUT', path, headers={'If-Match': headers['ETag']}, body={'body': markdown})
        self.assertEqual(markdown, note['body'])
        self.assertEqual('private, no-store', saved['Cache-Control'])
        summaries, _ = self.call('GET', query, owner='read')
        self.assertEqual([date], [row['date'] for row in summaries['items']])
        self.assertTrue(summaries['items'][0]['hasBody'])
        self.assertNotIn('body', summaries['items'][0])
        self.assertNotIn('Private journal fixture', json.dumps(summaries))
        foreign, _ = self.call('GET', path, owner='b')
        self.assertEqual('', foreign['body'])
        self.call('PUT', path, owner='b', headers={'If-Match': saved['ETag']}, body={'body': 'foreign'}, status=412)
        self.call('PUT', path, owner='read', headers={'If-Match': saved['ETag']}, body={'body': markdown}, status=403)
        self.call('DELETE', path, owner='read', headers={'If-Match': saved['ETag']}, status=403)
        self.call('GET', path, owner=None, status=401)
        self.call('GET', query, owner=None, status=401)
        labeled, labeled_headers = self.call('PUT', path, headers={'If-Match': saved['ETag']}, body={'text': 'Planning'})
        self.assertEqual(markdown, labeled['body'])
        self.call('PUT', path, headers={'If-Match': saved['ETag']}, body={'body': 'stale'}, status=412)
        self.call('PUT', path, headers={'If-Match': labeled_headers['ETag']}, body={'body': 'ø' * 100001}, status=422)
        _, deleted = self.call('DELETE', path, headers={'If-Match': labeled_headers['ETag']}, status=204)
        summaries, _ = self.call('GET', query)
        self.assertEqual([], summaries['items'])
        self.call('PUT', path, headers={'If-Match': saved['ETag']}, body={'body': 'stale recreate'}, status=412)
        recreated, _ = self.call('PUT', path, headers={'If-Match': deleted['ETag']}, body={'body': markdown})
        self.assertEqual('', recreated['text'])
        self.assertEqual(markdown, recreated['body'])
        self.assertGreater(recreated['version'], labeled['version'])

    def test_clients_own_projects_and_session_summaries_with_portable_relations(self):
        client = self.create('clients', {'name': self.prefix, 'color': '#ffee00'})
        second = self.create('projects', {'name': self.prefix + ' second', 'clientId': client['id']})
        _, project_headers = self.call('GET', '/projects/' + self.project['id'])
        project, _ = self.call('PUT', '/projects/' + self.project['id'],
                               headers={'If-Match': project_headers['ETag']},
                               body={'name': self.project['name'], 'clientId': client['id']})
        session = self.create('work-sessions', {'clientId': client['id'],
                              'summary': 'Planning\n\nDescription', 'start': '2025-10-20T08:00:00.123456789Z',
                              'end': '2025-10-20T16:00:00.987654321Z'})
        report, _ = self.call('GET', '/work-sessions/' + session['id'] + '/report')
        self.assertEqual(session, report['session'])
        projects, _ = self.call('GET', '/projects?clientId=' + client['id'])
        self.assertEqual({project['id'], second['id']}, {item['id'] for item in projects['items']})
        _, headers = self.call('GET', '/clients/' + client['id'])
        changed, _ = self.call('PUT', '/clients/' + client['id'], headers={'If-Match': headers['ETag']},
                               body={'name': self.prefix + ' renamed', 'color': '#102030'})
        self.assertEqual(client['version'] + 1, changed['version'])
        self.call('PUT', '/clients/' + client['id'], headers={'If-Match': headers['ETag']},
                  body={'name': 'Stale', 'color': '#ffffff'}, status=412, code='STALE_VERSION')
        _, headers = self.call('GET', '/clients/' + client['id'])
        self.call('DELETE', '/clients/' + client['id'], headers={'If-Match': headers['ETag']}, status=409)
        self.call('GET', '/clients/' + client['id'], owner='b', status=404)
        self.call('POST', '/clients', owner='read', body={'name': 'Read only', 'color': '#ffffff'}, status=403)
        self.call('POST', '/projects', owner='b', body={'name': 'Foreign', 'clientId': client['id']}, status=404)
        exported, _ = self.call('GET', '/data/export')
        self.assertEqual('timekeeper-domain-v3', exported['format'])
        wire = lambda item: {key: value for key, value in item.items() if key not in ('ownerId', 'version', 'state')}
        data = {'format': 'timekeeper-domain-v3', 'zoneId': 'UTC', 'clients': [wire(changed)],
                'projects': [wire(second)], 'tasks': [], 'labels': [], 'activities': [], 'workSessions': [wire(session)]}
        result, _ = self.call('POST', '/data/import', owner='b', body=data, headers=self.key(), status=201)
        kinds = {'CLIENT': 'clients', 'PROJECT': 'projects', 'WORK_SESSION': 'work-sessions'}
        remapped = {item['sourceId']: item['id'] for item in result['ids']}
        for item in result['ids']:
            self.created.append(('b', kinds[item['entityType']], item['id']))
        copied, _ = self.call('GET', '/work-sessions/' + remapped[session['id']], owner='b')
        copied_project, _ = self.call('GET', '/projects/' + remapped[second['id']], owner='b')
        self.assertEqual(remapped[client['id']], copied['clientId'])
        self.assertEqual(copied['clientId'], copied_project['clientId'])
        self.assertNotIn('title', copied)
        self.assertEqual('Planning\n\nDescription', copied['summary'])
        self.assertEqual(session['start'], copied['start'])
        self.assertNotEqual(client['id'], copied['clientId'])

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

    def test_work_session_commands_are_durable_independent_and_owner_scoped(self):
        self.call('GET', '/work-sessions/active', status=204)
        keys = [self.key(), self.key()]
        body = {'summary': self.prefix}
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(lambda key: self.call('POST', '/work-sessions/start',
                body=body, headers=key, status=(201, 409)), keys))
        winners = [(index, value, headers) for index, (value, headers) in enumerate(results) if 'id' in value]
        for _, value, _ in winners:
            self.created.append(('a', 'work-sessions', value['id']))
        self.assertEqual(1, len(winners), 'Concurrent starts must create one running session')
        index, session, session_headers = winners[0]
        replay, _ = self.call('POST', '/work-sessions/start', body=body, headers=keys[index], status=201)
        self.assertEqual(session, replay)
        other, _ = self.call('POST', '/work-sessions/start', body=body, headers=keys[index], owner='b', status=201)
        self.created.append(('b', 'work-sessions', other['id']))
        self.assertNotEqual(session['id'], other['id'])
        self.call('POST', '/work-sessions/start', body=body, headers=self.key(), owner='read', status=403, code='FORBIDDEN')
        activity, activity_headers, _ = self.start(self.task)
        self.assertIsNone(activity.get('workSessionId'), 'Existing tracking may remain standalone')
        self.call('PUT', f'/activities/{activity["id"]}/work-session',
            body={'workSessionId': session['id']}, headers={'If-Match': activity_headers['ETag']}, status=409, code='CONFLICT')
        detached, _ = self.call('PUT', f'/activities/{activity["id"]}/work-session',
            body={'workSessionId': None}, headers={'If-Match': activity_headers['ETag']})
        self.assertEqual(activity, detached['activity'])
        stop_key = {**self.key(), 'If-Match': session_headers['ETag']}
        stopped, stopped_headers = self.call('POST', f'/work-sessions/{session["id"]}/stop', headers=stop_key)
        repeated, repeated_headers = self.call('POST', f'/work-sessions/{session["id"]}/stop', headers=stop_key)
        self.assertEqual(stopped, repeated)
        self.assertEqual(stopped_headers['ETag'], repeated_headers['ETag'])
        active, _ = self.call('GET', '/activities/active')
        self.assertEqual(activity, active, 'Stopping the session must not modify its active activity')
        self.assertIsNone(active['end'])
        self.call('GET', '/work-sessions/active', status=204)
        second, _ = self.call('POST', '/work-sessions/start', body=body, headers=self.key(), status=201)
        self.created.append(('a', 'work-sessions', second['id']))
        self.assertNotEqual(session['id'], second['id'])

    def test_work_session_reports_follow_overlap_and_deletion_preserves_independent_activities(self):
        interval = {'start': '2025-10-25T22:00:00.123456789Z',
                    'end': '2025-10-26T23:00:00.123456789Z', 'summary': self.prefix}
        session = self.create('work-sessions', interval)
        other_project = self.create('projects', {'name': self.prefix + ' second project'})
        other_task = self.create('tasks', {'projectId': other_project['id'], 'parentTaskId': None,
                                         'summary': self.prefix + ' second task', 'url': None})
        for task, start, end in ((self.task, '08:00:00', '12:00:00'), (other_task, '10:00:00', '14:00:00')):
            self.create('activities', {'taskId': task['id'], 'start': f'2025-10-26T{start}Z',
                'end': f'2025-10-26T{end}Z', 'summary': self.prefix, 'labelIds': [], 'workSessionId': session['id']})
        outside = self.create('activities', {'taskId': None, 'projectId': self.project['id'],
            'start': '2025-10-27T00:00:00Z', 'end': '2025-10-27T01:00:00Z', 'summary': self.prefix, 'labelIds': []})
        _, tag = self.call('GET', f'/activities/{outside["id"]}')
        self.call('PUT', f'/activities/{outside["id"]}/work-session',
            body={'workSessionId': session['id']}, headers={'If-Match': tag['ETag']}, status=409, code='CONFLICT')
        detached, _ = self.call('PUT', f'/activities/{outside["id"]}/work-session',
            body={'workSessionId': None}, headers={'If-Match': tag['ETag']})
        self.assertEqual(outside, detached['activity'])
        report, _ = self.call('GET', f'/work-sessions/{session["id"]}/report')
        self.assertEqual('PT25H', report['time']['duration'])
        self.assertEqual('PT8H', report['time']['trackedActivityTime'])
        self.assertEqual('PT6H', report['time']['allocatedTime'])
        self.assertEqual('PT19H', report['time']['unallocatedTime'])
        self.assertEqual([], report['time']['outsideSession'])
        self.assertEqual(2,len(report['activities']))
        _, tag = self.call('GET', f'/work-sessions/{session["id"]}')
        changed, _ = self.call('PUT', f'/work-sessions/{session["id"]}',
            body={**interval, 'summary': self.prefix + ' reviewed'}, headers={'If-Match': tag['ETag']})
        self.call('PUT', f'/work-sessions/{session["id"]}', body=interval,
            headers={'If-Match': tag['ETag']}, status=412, code='STALE_VERSION')
        self.assertEqual(interval['start'], changed['start'])
        self.call('GET', f'/work-sessions/{session["id"]}', owner='b', status=404, code='NOT_FOUND')
        self.call('GET', f'/work-sessions/{session["id"]}/report', owner='b', status=404, code='NOT_FOUND')
        foreign = self.create('activities', {'taskId': None, 'start': outside['start'], 'end': outside['end'],
            'summary': self.prefix, 'labelIds': []}, owner='b')
        _, foreign_tag = self.call('GET', f'/activities/{foreign["id"]}', owner='b')
        self.call('PUT', f'/activities/{foreign["id"]}/work-session', owner='b', body={'workSessionId': session['id']},
            headers={'If-Match': foreign_tag['ETag']}, status=409, code='CONFLICT')
        self.call('PUT', f'/activities/{foreign["id"]}/work-session', body={'workSessionId': None},
            headers={'If-Match': foreign_tag['ETag']}, status=404, code='NOT_FOUND')
        before={record['id']:record for record in report['activities']+[outside]}
        _, current_tag=self.call('GET',f'/work-sessions/{session["id"]}')
        moved, moved_tag=self.call('PUT',f'/work-sessions/{session["id"]}',headers={'If-Match':current_tag['ETag']},
            body={'start':outside['start'],'end':outside['end'],'summary':self.prefix+' moved'})
        context,_=self.call('GET',f'/work-sessions/{session["id"]}/report')
        self.assertEqual([outside],context['activities'])
        self.assertEqual('PT1H',context['time']['allocatedTime'])
        self.call('DELETE',f'/work-sessions/{session["id"]}',headers={'If-Match':moved_tag['ETag']},status=204)
        self.created.remove(('a','work-sessions',session['id']))
        for identity,record in before.items():
            retained,_=self.call('GET',f'/activities/{identity}')
            self.assertEqual(record,retained)

    def test_domain_v2_transfer_remaps_taskless_session_graph_atomically(self):
        project, session, activity = [str(uuid4()) for _ in range(3)]
        start, end = '2026-03-29T08:00:00.123456789Z', '2026-03-29T12:00:00.987654321Z'
        data = {'format': 'timekeeper-domain-v2', 'zoneId': 'UTC',
            'projects': [{'id': project, 'name': self.prefix + ' imported'}], 'tasks': [], 'labels': [],
            'workSessions': [{'id': session, 'start': start, 'end': end, 'summary': self.prefix}],
            'activities': [{'id': activity, 'taskId': None, 'projectId': str(uuid4()), 'workSessionId': str(uuid4()),
                'start': start, 'end': end, 'summary': self.prefix, 'labelIds': [], 'manual': True}]}
        before, _ = self.call('GET', '/data/export', owner='b')
        self.call('POST', '/data/import', owner='b', body=data, headers=self.key(), status=422, code='VALIDATION')
        after, _ = self.call('GET', '/data/export', owner='b')
        self.assertEqual(before, after)
        data['activities'][0]['projectId'] = project
        key = self.key()
        imported, _ = self.call('POST', '/data/import', owner='b', body=data, headers=key, status=201)
        kinds = {'PROJECT': 'projects', 'WORK_SESSION': 'work-sessions', 'ACTIVITY': 'activities'}
        for value in imported['ids']:
            self.created.append(('b', kinds[value['entityType']], value['id']))
            self.assertNotEqual(value['sourceId'], value['id'])
        self.assertEqual(3, len(imported['ids']))
        repeated, _ = self.call('POST', '/data/import', owner='b', body=data, headers=key, status=201)
        self.assertEqual(imported, repeated)
        ids = {value['sourceId']: value['id'] for value in imported['ids']}
        stored, _ = self.call('GET', f'/activities/{ids[activity]}', owner='b')
        self.assertIsNone(stored['taskId'])
        self.assertEqual(ids[project], stored['projectId'])
        self.assertNotIn('workSessionId', stored)
        self.assertEqual((start, end), (stored['start'], stored['end']))
        report, _ = self.call('GET', f'/work-sessions/{ids[session]}/report', owner='b')
        self.assertEqual('PT4H0.864197532S', report['time']['allocatedTime'])

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

    def test_task_planning_round_trips_and_legacy_writes_preserve_it(self):
        path='/tasks/'+self.task['id']
        original, headers=self.call('GET',path)
        self.assertEqual(('NOT_STARTED',None,None),(original['status'],original['scheduledDate'],original['completedAt']))
        self.call('PUT',path+'/planning',owner='read',body={'status':'NOT_STARTED','scheduledDate':'2026-10-26','completedAt':None},
                  headers={'If-Match':headers['ETag']},status=403,code='FORBIDDEN')
        planned, headers=self.call('PUT',path+'/planning',body={'status':'NOT_STARTED','scheduledDate':'2026-10-26','completedAt':None},
                                   headers={'If-Match':headers['ETag']})
        self.assertEqual('2026-10-26',planned['scheduledDate'])
        self.call('PUT',path+'/planning',body={'status':'COMPLETED','scheduledDate':'2026-10-27','completedAt':'2026-10-27T11:12:13Z'},
                  headers={'If-Match':'"wrong"'},status=412,code='STALE_VERSION')
        legacy={'projectId':planned['projectId'],'parentTaskId':planned['parentTaskId'],'summary':self.prefix+' renamed','url':None}
        updated, headers=self.call('PUT',path,body=legacy,headers={'If-Match':headers['ETag']})
        self.assertEqual(('NOT_STARTED','2026-10-26',None),(updated['status'],updated['scheduledDate'],updated['completedAt']))
        completed, _=self.call('PUT',path+'/planning',body={'status':'COMPLETED','scheduledDate':'2026-10-26','completedAt':'2026-10-26T11:12:13Z'},
                               headers={'If-Match':headers['ETag']})
        self.assertEqual('2026-10-26T11:12:13Z',completed['completedAt'])
        exported, _=self.call('GET','/data/export')
        imported, _=self.call('POST','/data/import',owner='b',body=exported,headers=self.key(),status=201)
        resources={'CLIENT':'clients','PROJECT':'projects','TASK':'tasks','LABEL':'labels','ACTIVITY':'activities','WORK_SESSION':'work-sessions'}
        for item in imported['ids']:
            self.created.append(('b',resources[item['entityType']],item['id']))
            if item['entityType']=='TASK' and item['sourceId']==self.task['id']:
                restored, _=self.call('GET','/tasks/'+item['id'],owner='b')
                self.assertEqual(('COMPLETED','2026-10-26','2026-10-26T11:12:13Z'),
                                 (restored['status'],restored['scheduledDate'],restored['completedAt']))

    def test_task_checklist_is_ordered_and_survives_legacy_edits_and_export(self):
        path='/tasks/'+self.task['id']
        original, headers=self.call('GET',path)
        self.assertEqual([],original['checklistItems'])
        first={'id':str(uuid4()),'text':'Write tests','completed':False,'completedAt':None,'position':0}
        second={'id':str(uuid4()),'text':'Review','completed':True,'completedAt':'2026-10-26T11:12:13Z','position':1}
        body={'items':[first,second]}
        self.call('PUT',path+'/checklist',owner='read',body=body,headers={'If-Match':headers['ETag']},status=403,code='FORBIDDEN')
        updated, headers=self.call('PUT',path+'/checklist',body=body,headers={'If-Match':headers['ETag']})
        self.assertEqual(body['items'],updated['checklistItems'])
        self.call('PUT',path+'/checklist',body={'items':[]},headers={'If-Match':'"wrong"'},status=412,code='STALE_VERSION')
        self.call('PUT',path+'/checklist',body={'items':[{**first,'completedAt':second['completedAt']}]},
                  headers={'If-Match':headers['ETag']},status=422,code='VALIDATION')
        self.call('PUT',path+'/checklist',body={'items':[first,{**second,'position':0}]},
                  headers={'If-Match':headers['ETag']},status=422,code='VALIDATION')
        legacy={'projectId':updated['projectId'],'parentTaskId':updated['parentTaskId'],'summary':self.prefix+' renamed','url':None}
        updated, headers=self.call('PUT',path,body=legacy,headers={'If-Match':headers['ETag']})
        self.assertEqual(body['items'],updated['checklistItems'])
        updated, _=self.call('PUT',path+'/planning',body={'status':'NOT_STARTED','scheduledDate':'2026-10-26','completedAt':None},
                             headers={'If-Match':headers['ETag']})
        self.assertEqual(body['items'],updated['checklistItems'])
        exported, _=self.call('GET','/data/export')
        imported, _=self.call('POST','/data/import',owner='b',body=exported,headers=self.key(),status=201)
        resources={'CLIENT':'clients','PROJECT':'projects','TASK':'tasks','LABEL':'labels','ACTIVITY':'activities','WORK_SESSION':'work-sessions'}
        for item in imported['ids']:
            self.created.append(('b',resources[item['entityType']],item['id']))
            if item['entityType']=='TASK' and item['sourceId']==self.task['id']:
                restored, _=self.call('GET','/tasks/'+item['id'],owner='b')
                self.assertEqual(body['items'],restored['checklistItems'])

    def test_task_start_and_due_keep_date_and_wall_time_meaning(self):
        path='/tasks/'+self.task['id']
        original, headers=self.call('GET',path)
        self.assertIsNone(original['start']);self.assertIsNone(original['due'])
        start={'kind':'DATE','date':'2026-03-29'}
        due={'kind':'DATE_TIME','date':'2026-03-29','time':'02:30','timeZone':'Europe/Oslo'}
        body={'start':start,'due':due}
        self.call('PUT',path+'/timing',owner='read',body=body,headers={'If-Match':headers['ETag']},status=403,code='FORBIDDEN')
        timed, headers=self.call('PUT',path+'/timing',body=body,headers={'If-Match':headers['ETag']})
        self.assertEqual(start,timed['start']);self.assertEqual(due,timed['due'])
        self.assertIsNone(timed['scheduledDate'])
        self.call('PUT',path+'/timing',body={'start':None,'due':None},headers={'If-Match':'"wrong"'},status=412,code='STALE_VERSION')
        self.call('PUT',path+'/timing',body={'start':start,'due':{'kind':'DATE_TIME','date':'2026-03-29','time':'02:30'}},
                  headers={'If-Match':headers['ETag']},status=400,code='BAD_REQUEST')
        legacy={'projectId':timed['projectId'],'parentTaskId':timed['parentTaskId'],'summary':self.prefix+' renamed','url':None}
        updated, headers=self.call('PUT',path,body=legacy,headers={'If-Match':headers['ETag']})
        self.assertEqual((start,due),(updated['start'],updated['due']))
        updated, headers=self.call('PUT',path+'/planning',body={'status':'NOT_STARTED','scheduledDate':'2026-03-29','completedAt':None},
                                   headers={'If-Match':headers['ETag']})
        self.assertEqual((start,due),(updated['start'],updated['due']))
        updated, headers=self.call('PUT',path+'/checklist',body={'items':[]},headers={'If-Match':headers['ETag']})
        self.assertEqual((start,due),(updated['start'],updated['due']))
        exported, _=self.call('GET','/data/export')
        imported, _=self.call('POST','/data/import',owner='b',body=exported,headers=self.key(),status=201)
        resources={'CLIENT':'clients','PROJECT':'projects','TASK':'tasks','LABEL':'labels','ACTIVITY':'activities','WORK_SESSION':'work-sessions'}
        for item in imported['ids']:
            self.created.append(('b',resources[item['entityType']],item['id']))
            if item['entityType']=='TASK' and item['sourceId']==self.task['id']:
                restored, _=self.call('GET','/tasks/'+item['id'],owner='b')
                self.assertEqual((start,due),(restored['start'],restored['due']))
        windows={'start':None,'due':{'kind':'DATE_TIME','date':'2026-11-01','time':'01:30','timeZone':'Pacific Standard Time'}}
        windows_result, headers=self.call('PUT',path+'/timing',body=windows,headers={'If-Match':headers['ETag']})
        self.assertEqual(windows['due'],windows_result['due'])
        cleared, _=self.call('PUT',path+'/timing',body={'start':None,'due':None},headers={'If-Match':headers['ETag']})
        self.assertIsNone(cleared['start']);self.assertIsNone(cleared['due'])

    def test_task_priority_flag_and_urgency_remain_independent(self):
        path='/tasks/'+self.task['id']
        original, headers=self.call('GET',path)
        self.assertEqual(('UNSPECIFIED',False,False),(original['priority'],original['flagged'],original['urgent']))
        important={'priority':'HIGH','flagged':True,'urgent':False}
        self.call('PUT',path+'/importance',owner='read',body=important,headers={'If-Match':headers['ETag']},status=403,code='FORBIDDEN')
        updated, headers=self.call('PUT',path+'/importance',body=important,headers={'If-Match':headers['ETag']})
        self.assertEqual(important,{key:updated[key] for key in important})
        self.call('PUT',path+'/importance',body={'priority':'LOW','flagged':False,'urgent':True},
                  headers={'If-Match':'"wrong"'},status=412,code='STALE_VERSION')
        self.call('PUT',path+'/importance',body={'priority':'UNKNOWN','flagged':False,'urgent':False},
                  headers={'If-Match':headers['ETag']},status=422,code='VALIDATION')
        legacy={'projectId':updated['projectId'],'parentTaskId':updated['parentTaskId'],'summary':self.prefix+' renamed','url':None}
        updated, headers=self.call('PUT',path,body=legacy,headers={'If-Match':headers['ETag']})
        updated, headers=self.call('PUT',path+'/planning',body={'status':'NOT_STARTED','scheduledDate':None,'completedAt':None},
                                   headers={'If-Match':headers['ETag']})
        updated, headers=self.call('PUT',path+'/checklist',body={'items':[]},headers={'If-Match':headers['ETag']})
        updated, headers=self.call('PUT',path+'/timing',body={'start':None,'due':None},headers={'If-Match':headers['ETag']})
        self.assertEqual(important,{key:updated[key] for key in important})
        exported, _=self.call('GET','/data/export')
        imported, _=self.call('POST','/data/import',owner='b',body=exported,headers=self.key(),status=201)
        resources={'CLIENT':'clients','PROJECT':'projects','TASK':'tasks','LABEL':'labels','ACTIVITY':'activities','WORK_SESSION':'work-sessions'}
        for item in imported['ids']:
            self.created.append(('b',resources[item['entityType']],item['id']))
            if item['entityType']=='TASK' and item['sourceId']==self.task['id']:
                restored, _=self.call('GET','/tasks/'+item['id'],owner='b')
                self.assertEqual(important,{key:restored[key] for key in important})
        normal={'priority':'NORMAL','flagged':False,'urgent':True}
        changed, headers=self.call('PUT',path+'/importance',body=normal,headers={'If-Match':headers['ETag']})
        self.assertEqual(normal,{key:changed[key] for key in normal})
        cleared, _=self.call('PUT',path+'/importance',body={'priority':'UNSPECIFIED','flagged':False,'urgent':False},
                              headers={'If-Match':headers['ETag']})
        self.assertEqual(('UNSPECIFIED',False,False),(cleared['priority'],cleared['flagged'],cleared['urgent']))

    def test_task_notes_survive_legacy_updates_and_archive(self):
        path='/tasks/'+self.task['id']
        original, headers=self.call('GET',path)
        self.assertEqual({'format':'TEXT','content':''},original['body'])
        note={'format':'HTML','content':'<p>Review <strong>draft</strong></p>'}
        self.call('PUT',path+'/body',owner='read',body=note,headers={'If-Match':headers['ETag']},status=403,code='FORBIDDEN')
        updated, headers=self.call('PUT',path+'/body',body=note,headers={'If-Match':headers['ETag']})
        self.assertEqual(note,updated['body'])
        self.call('PUT',path+'/body',body={'format':'TEXT','content':'wrong'},headers={'If-Match':'"wrong"'},status=412,code='STALE_VERSION')
        self.call('PUT',path+'/body',body={'format':'MARKDOWN','content':'wrong'},headers={'If-Match':headers['ETag']},status=422,code='VALIDATION')
        legacy={'projectId':updated['projectId'],'parentTaskId':updated['parentTaskId'],'summary':self.prefix+' renamed','url':None}
        updated, headers=self.call('PUT',path,body=legacy,headers={'If-Match':headers['ETag']})
        updated, headers=self.call('PUT',path+'/planning',body={'status':'NOT_STARTED','scheduledDate':None,'completedAt':None},headers={'If-Match':headers['ETag']})
        updated, headers=self.call('PUT',path+'/importance',body={'priority':'HIGH','flagged':True,'urgent':False},headers={'If-Match':headers['ETag']})
        self.assertEqual(note,updated['body'])
        exported, _=self.call('GET','/data/export')
        imported, _=self.call('POST','/data/import',owner='b',body=exported,headers=self.key(),status=201)
        resources={'CLIENT':'clients','PROJECT':'projects','TASK':'tasks','LABEL':'labels','ACTIVITY':'activities','WORK_SESSION':'work-sessions'}
        for item in imported['ids']:
            self.created.append(('b',resources[item['entityType']],item['id']))
            if item['entityType']=='TASK' and item['sourceId']==self.task['id']:
                restored, _=self.call('GET','/tasks/'+item['id'],owner='b')
                self.assertEqual(note,restored['body'])
        cleared, _=self.call('PUT',path+'/body',body={'format':'TEXT','content':''},headers={'If-Match':headers['ETag']})
        self.assertEqual({'format':'TEXT','content':''},cleared['body'])

    def test_external_source_metadata_is_scoped_and_survives_legacy_link_and_archive(self):
        path='/tasks/'+self.task['id']
        ref={'providerId':'google','repositoryId':'account/list','externalId':str(uuid4()),'externalUrl':None}
        source={'createdAt':'2026-10-01T10:00:00Z','modifiedAt':'2026-10-26T10:00:00Z',
                'remoteEtag':'"remote-1"','remoteRevision':'revision-1'}
        original, headers=self.call('GET',path)
        linked, headers=self.call('PUT',path+'/external-references',body={**ref,'source':source},headers={'If-Match':headers['ETag']})
        self.assertEqual(source,linked['externalReferences'][0]['source'])
        linked, headers=self.call('PUT',path+'/external-references',body=ref,headers={'If-Match':headers['ETag']})
        self.assertEqual(source,linked['externalReferences'][0]['source'])
        replacement={**source,'remoteEtag':'"remote-2"','modifiedAt':None}
        body={key:ref[key] for key in ('providerId','repositoryId','externalId')}
        body['source']=replacement
        self.call('PUT',path+'/external-references/source',owner='read',body=body,
                  headers={'If-Match':headers['ETag']},status=403,code='FORBIDDEN')
        self.call('PUT',path+'/external-references/source',body=body,
                  headers={'If-Match':'"wrong"'},status=412,code='STALE_VERSION')
        updated, headers=self.call('PUT',path+'/external-references/source',body=body,headers={'If-Match':headers['ETag']})
        self.assertEqual(replacement,updated['externalReferences'][0]['source'])
        legacy={'projectId':updated['projectId'],'parentTaskId':updated['parentTaskId'],'summary':self.prefix+' renamed','url':None}
        updated, headers=self.call('PUT',path,body=legacy,headers={'If-Match':headers['ETag']})
        self.assertEqual(replacement,updated['externalReferences'][0]['source'])
        exported, _=self.call('GET','/data/export')
        imported, _=self.call('POST','/data/import',owner='b',body=exported,headers=self.key(),status=201)
        resources={'CLIENT':'clients','PROJECT':'projects','TASK':'tasks','LABEL':'labels','ACTIVITY':'activities','WORK_SESSION':'work-sessions'}
        for item in imported['ids']:
            self.created.append(('b',resources[item['entityType']],item['id']))
            if item['entityType']=='TASK' and item['sourceId']==self.task['id']:
                restored, _=self.call('GET','/tasks/'+item['id'],owner='b')
                self.assertEqual(replacement,restored['externalReferences'][0]['source'])
        cleared={key:None for key in source}
        cleared_result, _=self.call('PUT',path+'/external-references/source',body={**body,'source':cleared},
                                    headers={'If-Match':headers['ETag']})
        self.assertEqual(cleared,cleared_result['externalReferences'][0]['source'])

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
