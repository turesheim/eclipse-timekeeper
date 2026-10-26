"""Validate the published contract and representative client/error boundaries."""
import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

CONTRACT = json.loads((Path(__file__).parents[1] / 'openapi.json').read_text())
URI = 'urn:timekeeper:openapi:v1'
REGISTRY = Registry().with_resource(URI, Resource.from_contents(
    CONTRACT, default_specification=DRAFT202012))
ID = '00000000-0000-4000-8000-000000000001'


def validator(name):
    return Draft202012Validator({'$ref': f'{URI}#/components/schemas/{name}'},
                                registry=REGISTRY, format_checker=FormatChecker())


class ContractTest(unittest.TestCase):
    def test_complete_openapi_document(self):
        validate(CONTRACT)
        for schema in CONTRACT['components']['schemas'].values():
            Draft202012Validator.check_schema(schema)

    def test_all_private_operations_require_token_and_permission(self):
        self.assertEqual([{'bearerAuth': []}, {'browserSession': []}], CONTRACT['security'])
        ids = []
        for path, item in CONTRACT['paths'].items():
            for method in ('get', 'post', 'put', 'delete'):
                if method not in item:
                    continue
                operation = item[method]
                ids.append(operation['operationId'])
                if path in ('/openapi.json', '/auth/status', '/auth/login', '/auth/setup', '/auth/recover',
                            '/auth/email/forgot', '/auth/email/preview', '/auth/email/verify', '/auth/email/reset',
                            '/auth/passkeys/login/options', '/auth/passkeys/login/finish', '/auth/passkeys/cancel'):
                    self.assertEqual([], operation['security'])
                    continue
                if path in ('/journal/settings', '/journal/devices') or path.startswith('/journal/devices/') and path.endswith('/revoke'):
                    self.assertEqual([{'browserSession': []}], operation['security'])
                else:
                    self.assertNotIn('security', operation)
                self.assertEqual(('write' if path.startswith('/admin/') else 'read') if method == 'get' else ('read' if path in ('/auth/logout', '/auth/token-login') else 'write'),
                                 operation['x-required-permission'])
                self.assertIn('401', operation['responses'])
                self.assertIn('403', operation['responses'])
                for parameter in item.get('parameters', []) + operation.get('parameters', []):
                    if '$ref' in parameter:
                        parameter = CONTRACT['components']['parameters'][parameter['$ref'].split('/')[-1]]
                    self.assertNotEqual('ownerId', parameter['name'])
        self.assertEqual(len(ids), len(set(ids)))

    def test_owner_and_version_injection_are_rejected(self):
        inputs = {
            'ProjectInput': {'name': 'Project'},
            'TaskInput': {'summary': 'Task', 'projectId': None, 'parentTaskId': None, 'url': None},
            'LabelInput': {'name': 'Billable', 'color': '#19ABcd'},
            'ActivityInput': {'taskId': ID, 'start': '2026-10-12T08:00:00.123456789Z',
                              'end': None, 'summary': '', 'labelIds': []},
            'StartActivityInput': {'taskId': ID, 'summary': '', 'labelIds': [],
                                   'expectedActiveId': None, 'expectedActiveVersion': None},
            'ProfileInput': {'zoneId': 'Europe/Oslo'},
            'UndoInput': {},
        }
        for name, body in inputs.items():
            with self.subTest(schema=name):
                validator(name).validate(body)
                for field, value in [('ownerId', 'other'), ('version', 100), ('id', ID), ('manual', False)]:
                    self.assertFalse(validator(name).is_valid({**body, field: value}))
                for field in body:
                    missing = dict(body)
                    del missing[field]
                    self.assertFalse(validator(name).is_valid(missing))

    def test_locale_is_optional_nullable_and_restricted_to_supported_regions(self):
        for locale in (None, 'en-GB', 'en-US', 'nb-NO'):
            validator('ProfileInput').validate({'zoneId': 'Europe/Oslo', 'locale': locale})
        validator('ProfileInput').validate({'zoneId': 'Europe/Oslo'})
        for locale in ('en', 'no-NO', 'de-DE', '', '<script>', 1, [], {}):
            self.assertFalse(validator('ProfileInput').is_valid({'zoneId': 'Europe/Oslo', 'locale': locale}))
        for schema in ('Profile', 'ProfileInput', 'AuthSession'):
            self.assertNotIn('locale', CONTRACT['components']['schemas'][schema].get('required', []))

    def test_optional_project_icons_are_bounded_metadata(self):
        for icon in ('code', 'globe', 'hammer', 'rocket', 'airplane-engines', 'calendar3', 'phone', None):
            validator('ProjectInput').validate({'name': 'Project', 'icon': icon})
            validator('TransferProject').validate({'id': ID, 'name': 'Project', 'icon': icon})
        validator('ProjectInput').validate({'name': 'Earlier client'})
        for icon in ('', 'unknown', 'gavel', 'mobile', 'chart', 'dragon', '<svg onload=alert(1)>', 'https://example.invalid/icon.svg', 1, {}, []):
            self.assertFalse(validator('ProjectInput').is_valid({'name': 'Project', 'icon': icon}))

    def test_atomic_provider_linking_is_available_at_task_creation(self):
        body = {'summary': 'Jira issue', 'projectId': None, 'parentTaskId': None, 'url': None}
        validator('TaskCreateInput').validate(body)
        reference = {'providerId': 'jira', 'repositoryId': 'https://jira.invalid',
                     'externalId': '123', 'externalUrl': None}
        validator('TaskCreateInput').validate({**body, 'externalReferences': [reference]})
        self.assertFalse(validator('TaskCreateInput').is_valid({**body, 'externalReferences': [reference, reference]}))
        self.assertFalse(validator('TaskInput').is_valid({**body, 'externalReferences': [reference]}))
        self.assertFalse(validator('TaskCreateInput').is_valid({**body, 'ownerId': 'other'}))

    def test_task_planning_has_a_date_only_schedule_and_conditional_update_contract(self):
        body = {'summary': 'Review', 'projectId': None, 'parentTaskId': None, 'url': None,
                'status': 'COMPLETED', 'scheduledDate': '2026-10-26', 'completedAt': '2026-10-26T11:12:13Z'}
        validator('TaskCreateInput').validate(body)
        validator('TaskPlanningInput').validate({key: body[key] for key in ('status', 'scheduledDate', 'completedAt')})
        self.assertFalse(validator('TaskPlanningInput').is_valid({'status': 'COMPLETED', 'scheduledDate': None}))
        self.assertFalse(validator('TaskPlanningInput').is_valid({'status': 'UNKNOWN', 'scheduledDate': None, 'completedAt': None}))
        self.assertFalse(validator('TaskPlanningInput').is_valid({'status': 'NOT_STARTED', 'scheduledDate': '2026-02-30', 'completedAt': None}))
        self.assertEqual('updateTaskPlanning', CONTRACT['paths']['/tasks/{id}/planning']['put']['operationId'])

    def test_task_checklist_items_are_distinct_from_subtasks(self):
        item={'id':'e5fb0a24-7713-4e73-9e5c-bdf80a0180a5','text':'Review','completed':False,'completedAt':None,'position':0}
        validator('TaskChecklistInput').validate({'items':[item]})
        self.assertFalse(validator('TaskChecklistInput').is_valid({'items':[{**item,'completedAt':'2026-10-26T11:12:13Z','position':-1}]}))
        self.assertFalse(validator('TaskChecklistInput').is_valid({'items':[{**item,'parentTaskId':item['id']}]}))
        self.assertEqual('updateTaskChecklist',CONTRACT['paths']['/tasks/{id}/checklist']['put']['operationId'])

    def test_task_timing_distinguishes_civil_dates_from_zoned_wall_times(self):
        date={'kind':'DATE','date':'2026-03-29'}
        wall={'kind':'DATE_TIME','date':'2026-03-29','time':'02:30','timeZone':'Europe/Oslo'}
        validator('TaskTimingInput').validate({'start':date,'due':wall})
        self.assertFalse(validator('TaskTimingInput').is_valid({'start':date,'due':{**wall,'timeZone':None}}))
        self.assertFalse(validator('TaskTimingInput').is_valid({'start':{**date,'time':'00:00'},'due':None}))
        self.assertFalse(validator('TaskTimingInput').is_valid({'start':date}))
        self.assertEqual('updateTaskTiming',CONTRACT['paths']['/tasks/{id}/timing']['put']['operationId'])

    def test_timestamp_precision_and_zone_boundary(self):
        for value in ['2026-03-29T00:00:00Z', '2026-03-29T00:00:00.123456789Z']:
            validator('Instant').validate(value)
        for value in ['2026-03-29T02:00:00+02:00', '2026-03-29T02:00:00',
                      '2026-02-30T00:00:00Z', '2026-03-29T00:00:00.1234567890Z']:
            self.assertFalse(validator('Instant').is_valid(value), value)
        for duration in ['PT0S', 'PT23H', 'PT25H', 'PT0.000000001S']:
            validator('Duration').validate(duration)
        for duration in ['-PT1S', 'PT', 'P1DT', 'PT0.1234567890S']:
            self.assertFalse(validator('Duration').is_valid(duration))

    def test_bad_colors_blank_names_duplicate_labels(self):
        for color in ['blue', '#FFAA', '#FFAA0000', 'FFAA00']:
            self.assertFalse(validator('Color').is_valid(color))
        for name in ['', '   ', '\n\t']:
            self.assertFalse(validator('Name').is_valid(name))
        body = {'taskId': ID, 'start': '2026-10-12T08:00:00Z', 'end': None,
                'summary': '', 'labelIds': [ID, ID]}
        self.assertFalse(validator('ActivityInput').is_valid(body))
        body['labelIds'] = [ID]
        validator('ActivityInput').validate(body)

    def test_versioned_mutations_and_durable_commands(self):
        for path, item in CONTRACT['paths'].items():
            for method in ('put', 'delete'):
                # Chunks are immutable: index plus exact content identifies a
                # write, and conflicting bytes return 409 instead of overwriting.
                if method in item and path not in ('/auth/tokens/{id}', '/data/account/uploads/{id}/chunks/{index}'):
                    op = item[method]
                    self.assertIn({'$ref': '#/components/parameters/IfMatch'}, op['parameters'])
                    self.assertIn('412', op['responses'])
                    self.assertIn('428', op['responses'])
        for path in ['/activities/start', '/activities/{id}/stop', '/work-sessions/start', '/work-sessions/{id}/stop', '/data/import']:
            self.assertIn({'$ref': '#/components/parameters/IdempotencyKey'},
                          CONTRACT['paths'][path]['post']['parameters'])
            self.assertIn('409', CONTRACT['paths'][path]['post']['responses'])
        schema = CONTRACT['components']['parameters']['IfMatch']['schema']
        check = Draft202012Validator(schema)
        for tag in ['"0"', '"12"']:
            check.validate(tag)
        for tag in ['*', 'W/"1"', '""', '1', '"1", "2"', '"space tag"']:
            self.assertFalse(check.is_valid(tag), tag)

    def test_specified_problem_responses_are_valid(self):
        for status, response in CONTRACT['components']['responses'].items():
            example = response['content']['application/problem+json']['example']
            validator('Problem').validate(example)
            self.assertEqual(int(status), example['status'])
        validator('Problem').validate({'type': 'urn:timekeeper:problem:future',
                                       'title': 'Future problem', 'status': 409,
                                       'code': 'FUTURE_CONFLICT', 'futureField': True})
        self.assertIn('WWW-Authenticate', CONTRACT['components']['responses']['401']['headers'])

    def test_new_response_fields_are_compatible(self):
        value = {'id': ID, 'ownerId': 'alice', 'version': 0, 'name': 'Project', 'clientId': ID,
                 'futureField': {'value': 1}}
        validator('Project').validate(value)
        self.assertFalse(validator('ProjectInput').is_valid({'name': 'Project', 'futureField': 1}))
        validator('ExternalReferenceSnapshot').validate({'providerId': 'jira',
            'repositoryId': 'https://jira.invalid', 'externalId': '123',
            'externalUrl': None, 'futureField': True})
        for version in [-1, 1.5, 9007199254740992]:
            self.assertFalse(validator('Version').is_valid(version))

    def test_portable_snapshot_rejects_owner_and_persistence_fields(self):
        value = {'format': 'timekeeper-domain-v1', 'zoneId': 'UTC',
                 'projects': [{'id': ID, 'name': 'Project'}], 'tasks': [],
                 'labels': [], 'activities': []}
        validator('TransferSnapshot').validate(value)
        for field in ['ownerId', 'version', 'persistenceContext', 'databaseTable']:
            changed = copy.deepcopy(value)
            changed['projects'][0][field] = 'injected'
            self.assertFalse(validator('TransferSnapshot').is_valid(changed))
        changed = copy.deepcopy(value)
        changed['format'] = 'legacy-h2'
        self.assertFalse(validator('TransferSnapshot').is_valid(changed))

    def test_client_graph_is_required_and_summary_replaces_titles_in_v3_transfer(self):
        value = {'format': 'timekeeper-domain-v3', 'zoneId': 'UTC',
                 'clients': [{'id': ID, 'name': 'Client', 'color': '#ffdd88'}],
                 'projects': [{'id': ID, 'name': 'Project', 'clientId': ID}],
                 'tasks': [], 'labels': [], 'activities': [],
                 'workSessions': [{'id': ID, 'clientId': ID, 'title': 'Planning',
                                   'summary': '', 'start': '2025-10-20T08:00:00Z', 'end': None}]}
        validator('TransferSnapshot').validate(value)
        without_title = copy.deepcopy(value)
        del without_title['workSessions'][0]['title']
        validator('TransferSnapshot').validate(without_title)
        for collection, field in [('projects', 'clientId'), ('workSessions', 'clientId')]:
            missing = copy.deepcopy(value)
            del missing[collection][0][field]
            self.assertFalse(validator('TransferSnapshot').is_valid(missing))
        self.assertFalse(validator('ClientInput').is_valid({'name': 'Client', 'color': None}))
        self.assertFalse(validator('WorkSessionInput').is_valid({'start': '2025-10-20T08:00:00Z', 'end': None, 'summary': '', 'clientId': None, 'title': 'Planning'}))
        older = copy.deepcopy(value)
        older['format'] = 'timekeeper-domain-v2'
        self.assertFalse(validator('TransferSnapshot').is_valid(older))

    def test_journal_bodies_are_optional_writes_and_excluded_from_summaries(self):
        for value in [{'text': 'Planning'}, {'body': '\r\n# Notes\r\n\t'}, {'text': '', 'body': ''}]:
            validator('DayNoteInput').validate(value)
        for value in [{}, {'body': None}, {'body': 'private', 'ownerId': 'another'}]:
            self.assertFalse(validator('DayNoteInput').is_valid(value))
        note = {'id': ID, 'ownerId': 'a', 'version': 1, 'date': '2026-10-25',
                'text': '', 'body': '# Private body\n', 'hasBody': True}
        validator('DayNote').validate(note)
        summary = {key: value for key, value in note.items() if key != 'body'}
        validator('DayNoteList').validate({'items': [summary], 'revision': 1})
        self.assertFalse(validator('DayNoteSummary').is_valid(note))
        for value in [{'date': '2026-10-25', 'text': 'Old label'},
                      {'date': '2026-10-25', 'text': '', 'body': '\n\t'}]:
            validator('ArchiveDayNote').validate(value)
        self.assertFalse(validator('ArchiveDayNote').is_valid({'date': '2026-10-25', 'text': '', 'body': ''}))

    def test_every_reference_and_relation_workflow_is_present(self):
        expected = {
            'listDayNotes', 'getDayNote', 'saveDayNote', 'deleteDayNote',
            'downloadAccountArchive', 'createAccountArchiveUpload', 'listPendingAccountArchives',
            'getAccountArchiveUpload', 'writeAccountArchiveChunk', 'discardAccountArchiveUpload',
            'previewAccountArchive', 'restoreAccountArchive', 'listAccountArchiveRestores',
            'createClient', 'updateClient', 'deleteClient', 'getClient', 'listClients',
            'listCalendarSubscriptions', 'getCalendarSubscription', 'createCalendarSubscription', 'updateCalendarSubscription',
            'deleteCalendarSubscription', 'refreshCalendarSubscription', 'listCalendarEvents', 'convertCalendarEvent',
            'createProject', 'updateProject', 'deleteProject', 'getProject', 'listProjects',
            'createTask', 'updateTask', 'updateTaskPlanning', 'updateTaskChecklist', 'updateTaskTiming', 'deleteTask', 'getTask', 'listTasks',
            'linkExternalReference', 'unlinkExternalReference', 'findTaskByExternalReference',
            'createLabel', 'updateLabel', 'deleteLabel', 'getLabel', 'listLabels',
            'createActivity', 'updateActivity', 'deleteActivity', 'getActivity', 'listActivities',
            'getActiveActivity', 'startActivity', 'stopActivity', 'reportActivities',
            'createWorkSession', 'getWorkSession', 'updateWorkSession', 'deleteWorkSession', 'listWorkSessions',
            'undoWorkSessionChange', 'getActiveWorkSession', 'startWorkSession', 'stopWorkSession', 'associateWorkSession', 'reportWorkSession',
            'getAuthStatus', 'login', 'setupAccount', 'recoverAccount', 'exchangeApiToken', 'getAuthSession', 'logout', 'getAccount', 'updateAccount', 'changePassword', 'listPersonalTokens', 'createPersonalToken', 'revokePersonalToken', 'listUserAccounts', 'createUserAccount', 'getUserAccount', 'updateUserAccount',
            'requestEmailRecovery', 'previewEmailLink', 'verifyRecoveryEmail', 'resetPasswordByEmail', 'getRecoveryEmail', 'changeRecoveryEmail', 'resendRecoveryEmailVerification',
            'passkeyLoginOptions', 'passkeyLogin', 'cancelPasskeyCeremonies', 'listPasskeys', 'passkeyRegistrationOptions', 'registerPasskey',
            'passkeyProofOptions', 'verifyPasskeyProof', 'renamePasskey', 'removePasskey',
            'listImportJobs', 'previewTogglImport', 'createTogglImportJob', 'getImportJob', 'listImportItems',
            'previewTogglImportBatch', 'commitTogglImportBatch', 'beginImportUndo', 'previewImportUndoBatch', 'commitImportUndoBatch',
            'getAdministrationSettings', 'updateAdministrationSettings', 'getProfile', 'updateProfile', 'exportData', 'importData', 'listChanges', 'getOpenApi', 'getServerInformation',
            'journalStatus', 'journalSettings', 'enrollJournalDevice', 'revokeJournalDevice', 'journalDeviceProfile',
            'rescanJournal', 'journalCandidate', 'journalCandidateSource', 'journalCalendarSource', 'resolveJournalConflict', 'discardJournalCandidate',
        }
        operations = {v['operationId'] for item in CONTRACT['paths'].values()
                      for key, v in item.items() if key in ('get', 'put', 'post', 'delete')}
        self.assertEqual(expected, operations)


if __name__ == '__main__':
    unittest.main()
