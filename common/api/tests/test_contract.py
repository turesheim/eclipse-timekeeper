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

    def test_instance_operations_require_browser_proof_and_expose_only_status(self):
        value = {'currentPassword':'current private credential','passphrase':'private encrypted backup phrase'}
        self.assertTrue(validator('InstanceBackupCreateInput').is_valid(value))
        self.assertFalse(validator('InstanceBackupCreateInput').is_valid({**value,'path':'/private/source'}))
        self.assertFalse(validator('InstanceBackupCreateInput').is_valid({'passphrase':value['passphrase']}))
        self.assertTrue(CONTRACT['components']['schemas']['InstanceBackupCreateInput']['properties']['passphrase']['writeOnly'])
        self.assertEqual({'id','type','state','version','updatedAt'},set(CONTRACT['components']['schemas']['InstanceOperation']['properties']))
        operation = CONTRACT['paths']['/admin/instance/backups']['post']
        self.assertIn('202',operation['responses'])
        self.assertIn({'$ref':'#/components/parameters/Csrf'},operation['parameters'])

    def test_instance_download_grant_is_a_cookie_not_a_url_credential(self):
        operation = CONTRACT['paths']['/admin/instance/operations/{id}/content']['get']
        self.assertEqual([{'browserSession': [],'instanceDownloadGrant': []}],operation['security'])
        self.assertIn('application/octet-stream',operation['responses']['200']['content'])
        self.assertEqual('cookie',CONTRACT['components']['securitySchemes']['instanceDownloadGrant']['in'])
        self.assertEqual({'id','downloadUrl','expiresAt'},set(CONTRACT['components']['schemas']['InstanceDownload']['properties']))

    def test_instance_upload_has_bounded_immutable_chunks_and_cannot_claim_restore_readiness(self):
        op=CONTRACT['paths']['/admin/instance/uploads/{id}/content']['put']
        self.assertTrue(any(p.get('name')=='offset' and p.get('required') for p in op['parameters']))
        self.assertEqual(1048576,op['requestBody']['content']['application/octet-stream']['schema']['maxLength'])
        self.assertIn('409',op['responses'])
        self.assertFalse(CONTRACT['components']['schemas']['InstanceArchiveReview']['properties']['restoreReady']['const'])
        self.assertEqual({'INVALID_INSTANCE_BACKUP','INVALID_INSTANCE_AUTHENTICATION'},set(CONTRACT['components']['schemas']['InstanceRestoreUpload']['properties']['validationCode']['enum']))
        self.assertTrue(validator('InstanceRestoreUploadInput').is_valid({'currentPassword':'private proof','bytes':1024,'sha256':'a'*64}))
        self.assertFalse(validator('InstanceRestoreUploadInput').is_valid({'currentPassword':'private proof','bytes':1024,'sha256':'a'*64,'path':'/outside'}))

    def test_target_review_is_advisory_and_requires_password_proof(self):
        operation=CONTRACT['paths']['/admin/instance/uploads/{id}/target']['post']
        self.assertEqual([{'browserSession':[]}],operation['security'])
        self.assertEqual({'$ref':'#/components/schemas/InstanceOperationProofInput'},operation['requestBody']['content']['application/json']['schema'])
        self.assertFalse(CONTRACT['components']['schemas']['InstanceRestoreTarget']['properties']['restoreReady']['const'])
        self.assertTrue(CONTRACT['components']['schemas']['InstanceRestoreTarget']['properties']['blockers']['uniqueItems'])

    def test_all_private_operations_require_authentication_and_permission(self):
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
                if path == '/admin/instance' or path.startswith('/admin/instance/'):
                    self.assertEqual([{'browserSession': [], 'instanceDownloadGrant': []}] if path == '/admin/instance/operations/{id}/content' else [{'browserSession': []}], operation['security'])
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

    def test_provider_identity_replacement_retains_typed_aliases(self):
        old = {'providerId': 'microsoft', 'repositoryId': 'account/list-a', 'externalId': 'old'}
        new = {'providerId': 'microsoft', 'repositoryId': 'account/list-b', 'externalId': 'new', 'externalUrl': None}
        validator('ExternalReferenceReplacementInput').validate({'previous': old, 'current': new})
        self.assertFalse(validator('ExternalReferenceReplacementInput').is_valid({'previous': {**old, 'url': 'wrong'}, 'current': new}))
        self.assertFalse(validator('TaskCreateInput').is_valid({'summary': 'Task', 'projectId': None, 'parentTaskId': None, 'url': None, 'externalAliases': [old, old]}))
        self.assertEqual('replaceExternalReference', CONTRACT['paths']['/tasks/{id}/external-references/replace']['put']['operationId'])

    def test_recurrence_rule_and_occurrence_edits_are_distinct(self):
        day={'kind':'DATE','date':'2026-10-26'}
        series={'rule':'FREQ=WEEKLY;BYDAY=MO','end':'COUNT','until':None,'count':8}
        occurrence={'original':day,'movedTo':None,'state':'COMPLETED','completedAt':'2026-10-26T12:00:00Z'}
        validator('TaskRecurrence').validate({'series':series,'occurrences':[occurrence]})
        self.assertFalse(validator('TaskSeries').is_valid({**series,'until':day}))
        self.assertFalse(validator('TaskSeries').is_valid({**series,'rule':'FREQ=WEEKLY;COUNT=8'}))
        self.assertEqual('updateTaskRecurrence',CONTRACT['paths']['/tasks/{id}/recurrence']['put']['operationId'])
        self.assertEqual('updateTaskOccurrences',CONTRACT['paths']['/tasks/{id}/occurrences']['put']['operationId'])

    def test_reminder_kinds_do_not_collapse_into_due_dates(self):
        base={'id':ID}
        examples=[{'kind':'ABSOLUTE','at':{'kind':'DATE_TIME','date':'2026-10-26','time':'09:00:00','timeZone':'Europe/Oslo'}},
                  {'kind':'RELATIVE','anchor':'DUE','offsetMinutes':30},
                  {'kind':'LOCATION','latitude':59.9,'longitude':10.7,'radiusMeters':100,'transition':'ARRIVE'},
                  {'kind':'CAR','event':'DISCONNECT'}, {'kind':'MESSAGE','contactKey':'contact-1'}]
        for example in examples:
            validator('TaskReminder').validate({**base,**example})
        self.assertFalse(validator('TaskReminder').is_valid({**base,'kind':'LOCATION','at':examples[0]['at']}))
        self.assertFalse(validator('TaskReminder').is_valid({**base,'kind':'ABSOLUTE','at':{'kind':'DATE','date':'2026-10-26'}}))
        self.assertEqual('updateTaskReminders',CONTRACT['paths']['/tasks/{id}/reminders']['put']['operationId'])

    def test_connection_capabilities_are_versioned_and_field_scoped(self):
        field={'readable':True,'writable':False,'deletable':False,'note':'Read-only source'}
        body={'version':1,'providerApiVersion':'v1.0','fields':{'linkedResources':field}}
        validator('TaskCapabilities').validate(body)
        self.assertFalse(validator('TaskCapabilities').is_valid({**body,'fields':{'bad key':field}}))
        self.assertFalse(validator('TaskCapabilities').is_valid({**body,'fields':{'linkedResources':{**field,'ownerId':'other'}}}))
        self.assertEqual('updateTaskConnectionCapabilities',CONTRACT['paths']['/task-connections/{id}/capabilities']['put']['operationId'])

    def test_sync_preview_is_bounded_and_uses_field_presence(self):
        identity={'operationId':ID,'providerId':'google','repositoryId':'account/list',
                  'externalId':'remote-1','deleted':False}
        body={'connectionId':ID,'listId':ID,'sourceState':'HEALTHY','scanComplete':False,
              'items':[{**identity,'fields':{'summary':'Import','completedAt':None}}]}
        validator('TaskSyncPreviewInput').validate(body)
        self.assertFalse(validator('TaskSyncPreviewInput').is_valid({**body,'items':[{**identity,'fields':{'accessToken':'secret'}}]}))
        self.assertFalse(validator('TaskSyncPreviewInput').is_valid({**body,'items':body['items']*101}))
        self.assertFalse(validator('TaskSyncPreviewInput').is_valid({**body,'ownerId':'other'}))
        self.assertEqual('previewTaskSync',CONTRACT['paths']['/task-sync/preview']['post']['operationId'])

    def test_sync_import_uses_stable_provider_identity_and_operation_receipt(self):
        item={'operationId':ID,'providerId':'google','repositoryId':'account/list','externalId':'remote-1',
              'deleted':False,'fields':{'summary':'Imported','status':'NOT_STARTED','completedAt':None}}
        body={'connectionId':ID,'listId':ID,'sourceState':'HEALTHY','item':item}
        validator('TaskSyncImportInput').validate(body)
        self.assertFalse(validator('TaskSyncImportInput').is_valid({**body,'item':{**item,'fields':{'token':'secret'}}}))
        self.assertEqual('importTaskSyncItem',CONTRACT['paths']['/task-sync/import']['post']['operationId'])

    def test_sync_apply_requires_conditional_task_version(self):
        item={'operationId':ID,'providerId':'google','repositoryId':'account/list','externalId':'remote-1',
              'deleted':False,'fields':{'status':'COMPLETED','completedAt':'2026-10-26T12:00:00Z'}}
        validator('TaskSyncApplyInput').validate({'connectionId':ID,'listId':ID,'sourceState':'HEALTHY','item':item})
        operation=CONTRACT['paths']['/task-sync/apply']['post']
        self.assertEqual('applyTaskSyncItem',operation['operationId'])
        self.assertIn({'$ref':'#/components/parameters/IfMatch'},operation['parameters'])

    def test_sync_export_preview_reports_capability_gaps(self):
        body={'connectionId':ID,'listId':ID,'taskId':ID,'providerId':'google',
              'repositoryId':'account/list','externalId':'remote-1'}
        validator('TaskSyncExportInput').validate(body)
        self.assertFalse(validator('TaskSyncExportInput').is_valid({**body,'ownerId':'other'}))
        self.assertEqual('previewTaskSyncExport',CONTRACT['paths']['/task-sync/export-preview']['post']['operationId'])

    def test_task_tags_categories_and_provider_links_are_distinct(self):
        link={'id':ID,'kind':'PROVIDER_SOURCE','url':'https://tasks.google.com/task/1',
              'title':'Original task','providerId':'google','remoteId':None}
        body={'tags':['Personal'],'categories':['Blue Category'],'links':[link]}
        validator('TaskMetadata').validate(body)
        self.assertFalse(validator('TaskMetadata').is_valid({**body,'tags':['Personal','Personal']}))
        self.assertFalse(validator('TaskLink').is_valid({**link,'url':'javascript:alert(1)'}))
        self.assertEqual('updateTaskMetadata',CONTRACT['paths']['/tasks/{id}/metadata']['put']['operationId'])

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

    def test_task_importance_keeps_priority_flag_and_urgency_separate(self):
        value={'priority':'HIGH','flagged':True,'urgent':False}
        validator('TaskImportanceInput').validate(value)
        self.assertFalse(validator('TaskImportanceInput').is_valid({**value,'priority':'URGENT'}))
        self.assertFalse(validator('TaskImportanceInput').is_valid({**value,'flagged':None}))
        self.assertFalse(validator('TaskImportanceInput').is_valid({'priority':'HIGH','flagged':True}))
        self.assertEqual('updateTaskImportance',CONTRACT['paths']['/tasks/{id}/importance']['put']['operationId'])

    def test_task_body_has_explicit_format_and_clear_content(self):
        validator('TaskBodyInput').validate({'format':'TEXT','content':''})
        validator('TaskBodyInput').validate({'format':'HTML','content':'<p>Notes</p>'})
        self.assertFalse(validator('TaskBodyInput').is_valid({'format':'MARKDOWN','content':'Notes'}))
        self.assertFalse(validator('TaskBodyInput').is_valid({'format':'HTML','content':None}))
        self.assertFalse(validator('TaskBodyInput').is_valid({'format':'TEXT'}))
        self.assertEqual('updateTaskBody',CONTRACT['paths']['/tasks/{id}/body']['put']['operationId'])

    def test_external_source_has_per_reference_timestamps_and_remote_versions(self):
        source={'createdAt':'2026-10-01T10:00:00Z','modifiedAt':None,'remoteEtag':'"remote"','remoteRevision':None}
        value={'providerId':'google','repositoryId':'account/list','externalId':'item','source':source}
        validator('ExternalTaskSourceInput').validate(value)
        self.assertFalse(validator('ExternalTaskSourceInput').is_valid({**value,'source':{**source,'remoteEtag':42}}))
        self.assertFalse(validator('ExternalTaskSourceInput').is_valid({**value,'source':{**source,'remoteRevision':''}}))
        self.assertFalse(validator('ExternalTaskSourceInput').is_valid({**value,'source':{'createdAt':None}}))
        self.assertEqual('updateExternalTaskSource',CONTRACT['paths']['/tasks/{id}/external-references/source']['put']['operationId'])

    def test_last_common_baseline_is_versioned_and_scoped_to_normalized_fields(self):
        source={'createdAt':None,'modifiedAt':None,'remoteEtag':'"r1"','remoteRevision':None}
        baseline={'version':1,'capturedAt':'2026-10-26T10:00:00Z','fields':{'summary':'Call customer','status':'NOT_STARTED'}}
        value={'providerId':'google','repositoryId':'account/list','externalId':'item','source':source,'baseline':baseline}
        validator('ExternalTaskSyncStateInput').validate(value)
        validator('ExternalTaskSyncStateInput').validate({**value,'baseline':None})
        self.assertFalse(validator('ExternalTaskSyncStateInput').is_valid({**value,'baseline':{**baseline,'version':0}}))
        self.assertFalse(validator('ExternalTaskSyncStateInput').is_valid({**value,'baseline':{**baseline,'fields':{'accessToken':'secret'}}}))
        self.assertEqual('updateExternalTaskSyncState',CONTRACT['paths']['/tasks/{id}/external-references/sync-state']['put']['operationId'])

    def test_explicit_local_task_deletions_have_reconcilable_tombstones(self):
        tombstone={'ownerId':'owner','id':ID,'taskId':ID,'version':0,'taskVersion':2,'deletedAt':'2026-10-26T10:00:00Z',
                   'reason':'LOCAL_DELETED','externalReferences':[{'providerId':'google','repositoryId':'account/list','externalId':'item'}]}
        validator('TaskTombstone').validate(tombstone)
        self.assertFalse(validator('TaskTombstone').is_valid({**tombstone,'reason':'REMOTE_DELETED'}))
        self.assertEqual('listTaskTombstones',CONTRACT['paths']['/task-tombstones']['get']['operationId'])
        self.assertEqual('findTaskTombstoneByExternalReference',CONTRACT['paths']['/task-tombstones/by-external-reference']['get']['operationId'])

    def test_linked_task_tombstones_are_optional_in_legacy_transfers(self):
        tombstone={'id':ID,'taskId':ID,'taskVersion':2,'deletedAt':'2026-10-26T10:00:00Z',
                   'reason':'LOCAL_DELETED','externalReferences':[{'providerId':'google','repositoryId':'account/list','externalId':'item'}]}
        validator('TransferTaskTombstone').validate(tombstone)
        self.assertFalse(validator('TransferTaskTombstone').is_valid({**tombstone,'externalReferences':[]}))
        self.assertIn('taskTombstones',CONTRACT['components']['schemas']['TransferSnapshot']['properties'])
        self.assertNotIn('taskTombstones',CONTRACT['components']['schemas']['TransferSnapshot']['required'])

    def test_provider_connections_and_lists_keep_remote_identity_separate_from_projects(self):
        connection={'providerId':'google','remoteAccountId':'account-1','name':'Personal',
                    'readable':True,'writable':False,'enabled':True}
        task_list={'connectionId':ID,'remoteListId':'list-1','name':'Tasks','kind':'STANDARD',
                   'readable':True,'writable':False,'shared':False,'owner':True,'present':True,'position':0}
        validator('TaskConnectionCreateInput').validate(connection)
        validator('TaskListCreateInput').validate(task_list)
        self.assertFalse(validator('TaskListCreateInput').is_valid({**task_list,'kind':'PROJECT'}))
        self.assertFalse(validator('TaskListCreateInput').is_valid({**task_list,'position':-1}))
        self.assertFalse(validator('TaskConnectionCreateInput').is_valid({**connection,'accessToken':'secret'}))
        self.assertEqual('listTaskLists',CONTRACT['paths']['/task-lists']['get']['operationId'])
        reference={'providerId':'google','repositoryId':'account/list','externalId':'task-1',
                   'externalUrl':None,'taskListId':ID}
        validator('ExternalReference').validate(reference)
        self.assertFalse(validator('ExternalReference').is_valid({**reference,'taskListId':'not-a-uuid'}))
        validator('ExternalReference').validate({**reference,'sectionId':ID})
        self.assertFalse(validator('ExternalReference').is_valid({**reference,'sectionId':'not-a-uuid'}))
        location={'providerId':'google','repositoryId':'account/list','externalId':'task-1',
                  'taskListId':ID,'sectionId':ID}
        validator('ExternalTaskLocationInput').validate(location)
        validator('ExternalTaskLocationInput').validate({**location,'taskListId':None,'sectionId':None})
        self.assertFalse(validator('ExternalTaskLocationInput').is_valid({**location,'sectionId':'invalid'}))
        self.assertEqual('updateExternalTaskLocation',CONTRACT['paths']['/tasks/{id}/external-references/location']['put']['operationId'])
        assignment={'assigneeId':'person-1','sourceId':'document-1','sourceUrl':'https://example.test/1','writable':False}
        validator('TaskAssignment').validate(assignment)
        self.assertFalse(validator('TaskAssignment').is_valid({'writable':False}))
        validator('ExternalReference').validate({**reference,'assignment':assignment})
        validator('ExternalTaskAssignmentInput').validate({'providerId':'google','repositoryId':'account/list',
            'externalId':'task-1','assignment':None})
        self.assertEqual('updateExternalTaskAssignment',CONTRACT['paths']['/tasks/{id}/external-references/assignment']['put']['operationId'])

    def test_provider_list_sections_are_distinct_from_projects_and_grocery_categories(self):
        section={'taskListId':ID,'remoteSectionId':'produce','name':'Produce','kind':'GROCERY_CATEGORY',
                 'position':2,'present':True,'writable':False}
        validator('TaskListSectionCreateInput').validate(section)
        self.assertFalse(validator('TaskListSectionCreateInput').is_valid({**section,'kind':'PROJECT'}))
        self.assertFalse(validator('TaskListSectionCreateInput').is_valid({**section,'position':-1}))
        self.assertEqual('createTaskListSection',CONTRACT['paths']['/task-list-sections']['post']['operationId'])
        self.assertEqual('listTaskListSections',CONTRACT['paths']['/task-list-sections']['get']['operationId'])
        transferred={'id':ID,**section}
        validator('TransferTaskListSection').validate(transferred)
        self.assertIn('taskListSections',CONTRACT['components']['schemas']['TransferSnapshot']['properties'])
        self.assertNotIn('taskListSections',CONTRACT['components']['schemas']['TransferSnapshot']['required'])

    def test_shared_list_participants_are_distinct_from_account_access(self):
        sharing={'remoteOwnerId':'owner-1','participants':[
            {'remotePrincipalId':'owner-1','displayName':'Alex','role':'OWNER'},
            {'remotePrincipalId':'member-2','role':'VIEWER'}]}
        validator('TaskListSharing').validate(sharing)
        self.assertFalse(validator('TaskListSharing').is_valid({'participants':[
            {'remotePrincipalId':'member-2','role':'ADMIN'}]}))
        self.assertEqual('updateTaskListSharing',CONTRACT['paths']['/task-lists/{id}/sharing']['put']['operationId'])
        self.assertIn('sharing',CONTRACT['components']['schemas']['TransferTaskList']['properties'])

    def test_task_attachments_have_bounded_content_and_separate_metadata_pages(self):
        data={'fileName':'note.txt','contentType':'text/plain','bytes':4,
              'sha256':'a'*64,'data':'dGVzdA==','providerId':'apple','remoteId':'attachment-1'}
        validator('TaskAttachmentInput').validate(data)
        self.assertFalse(validator('TaskAttachmentInput').is_valid({**data,'bytes':1048577}))
        self.assertFalse(validator('TaskAttachmentInput').is_valid({**data,'remoteId':None}))
        self.assertNotIn('data',CONTRACT['components']['schemas']['TaskAttachmentSummary']['properties'])
        self.assertEqual('downloadTaskAttachmentContent',CONTRACT['paths']['/tasks/{taskId}/attachments/{id}/content']['get']['operationId'])
        transferred={'id':ID,'taskId':ID,**data}
        validator('TransferTaskAttachment').validate(transferred)
        self.assertIn('taskAttachments',CONTRACT['components']['schemas']['TransferSnapshot']['properties'])
        self.assertNotIn('taskAttachments',CONTRACT['components']['schemas']['TransferSnapshot']['required'])

    def test_task_sibling_order_is_a_conditional_section(self):
        validator('TaskOrderInput').validate({'siblingOrder':7})
        self.assertFalse(validator('TaskOrderInput').is_valid({'siblingOrder':-1}))
        self.assertFalse(validator('TaskOrderInput').is_valid({'siblingOrder':7,'unknown':True}))
        self.assertEqual('updateTaskOrder',CONTRACT['paths']['/tasks/{id}/order']['put']['operationId'])

    def test_provider_data_is_versioned_and_explicitly_clearable(self):
        envelope={'namespace':'google.tasks','version':1,'payload':{'future':{'nested':[1,True,None]}}}
        validator('ExternalTaskProviderData').validate(envelope)
        self.assertFalse(validator('ExternalTaskProviderData').is_valid({**envelope,'version':0}))
        self.assertFalse(validator('ExternalTaskProviderData').is_valid({**envelope,'namespace':'Google Tasks'}))
        command={'providerId':'google','repositoryId':'account/list','externalId':'item','providerData':envelope}
        validator('ExternalTaskProviderDataInput').validate(command)
        validator('ExternalTaskProviderDataInput').validate({**command,'providerData':None})
        self.assertEqual('updateExternalTaskProviderData',CONTRACT['paths']['/tasks/{id}/external-references/provider-data']['put']['operationId'])

    def test_sync_cursors_are_opaque_per_connection_or_list(self):
        value={'connectionId':ID,'listId':None,'mode':'INITIAL','cursor':None,
               'lastObservedAt':None,'lastCompleteAt':None}
        validator('TaskSyncCursorCreateInput').validate(value)
        validator('TaskSyncCursorCreateInput').validate({**value,'listId':ID,'mode':'INCREMENTAL','cursor':'opaque-state'})
        self.assertFalse(validator('TaskSyncCursorCreateInput').is_valid({**value,'cursor':'x'*32769}))
        self.assertFalse(validator('TaskSyncCursorCreateInput').is_valid({**value,'mode':'DELETE_ALL'}))
        self.assertEqual('listTaskSyncCursors',CONTRACT['paths']['/task-sync-cursors']['get']['operationId'])

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
                if method in item and path not in ('/auth/tokens/{id}', '/data/account/uploads/{id}/chunks/{index}', '/admin/instance/uploads/{id}/content'):
                    op = item[method]
                    if path == '/journal-assets/{filename}' and method == 'put':
                        self.assertIn({'$ref': '#/components/parameters/IfMatchOptional'}, op['parameters'])
                        self.assertIn({'$ref': '#/components/parameters/IfNoneMatchStar'}, op['parameters'])
                    else:
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
        validator('ArchiveDayNote').validate({'date': '2026-10-25', 'text': '', 'body': '', 'markdown': ''})
        validator('ArchiveJournalAsset').validate({'filename': 'photo.png', 'data': 'AQID'})
        self.assertFalse(validator('ArchiveJournalAsset').is_valid({'filename': '../photo.png', 'data': 'AQID'}))
        for path in ['2026-10-27.md', '2026-W44.md', 'assets/photo.png', f'assets/task attachments/{ID}.bin']:
            validator('ArchiveJournalFile').validate({'path': path, 'bytes': 0, 'sha256': 'a' * 64})
        for path in ['../outside', '/absolute.md', 'assets/../outside', 'assets/nested/file.png']:
            self.assertFalse(validator('ArchiveJournalFile').is_valid({'path': path, 'bytes': 0, 'sha256': 'a' * 64}))

    def test_diagnostic_history_rejects_body_and_credential_fields(self):
        event={'id':ID,'sequence':1,'occurredAt':'2026-10-28T00:00:00Z','resource':'LABEL','operation':'CREATE',
               'source':'REST_LABEL','previousNoteVersion':0,'noteVersion':0,'previousLabelVersion':0,'labelVersion':1,
               'previousLabel':'','newLabel':'Planning','hadBody':False,'hasBody':False,'noteDeleted':False,
               'previousModifiedAt':None,'changedFields':['text','presence'],
               'client':{'authentication':'API_TOKEN','tokenId':ID},'build':{'version':'2.0.0','commit':None,'dirty':False}}
        validator('DayNoteHistoryEvent').validate(event)
        for field in ['body','markdown','digest','csrf','token']:
            invalid=copy.deepcopy(event);invalid[field]='private'
            self.assertFalse(validator('DayNoteHistoryEvent').is_valid(invalid))
        invalid=copy.deepcopy(event);invalid['client']['digest']='secret hash'
        self.assertFalse(validator('DayNoteHistoryEvent').is_valid(invalid))
        self.assertEqual('read',CONTRACT['paths']['/day-notes/{date}/history']['get']['x-required-permission'])

    def test_day_entry_commands_validate_only_independently_changed_resources(self):
        for value in [{'text':'Label','labelVersion':0}, {'body':'','noteVersion':2},
                      {'text':'','labelVersion':3,'body':'Note','noteVersion':4}]:
            validator('DayEntryInput').validate(value)
        for value in [{}, {'text':'Missing version'}, {'body':'Missing version'},
                      {'body':'Note','noteVersion':1,'labelVersion':2}, {'text':'Label','labelVersion':None}]:
            self.assertFalse(validator('DayEntryInput').is_valid(value))
        validator('DayLabelInput').validate({'text':''})
        self.assertFalse(validator('DayNoteContentInput').is_valid({'body':'Note','text':'Unrelated label'}))

    def test_every_reference_and_relation_workflow_is_present(self):
        expected = {
            'listDayNotes', 'getDayNote', 'saveDayNote', 'deleteDayNote',
            'listDayLabels', 'getDayLabel', 'saveDayLabel', 'clearDayLabel',
            'getDayNoteHistory', 'getDayNoteContent', 'saveDayNoteContent', 'deleteDayNoteContent', 'getDayEntry', 'editDayEntry',
            'listWeekNotes', 'getWeekNote', 'saveWeekNote', 'deleteWeekNote', 'downloadWeekNote',
            'getJournalAsset', 'putJournalAsset', 'deleteJournalAsset',
            'downloadAccountArchive', 'createAccountArchiveUpload', 'listPendingAccountArchives',
            'getAccountArchiveUpload', 'writeAccountArchiveChunk', 'discardAccountArchiveUpload',
            'previewAccountArchive', 'restoreAccountArchive', 'listAccountArchiveRestores',
            'createClient', 'updateClient', 'deleteClient', 'getClient', 'listClients',
            'listCalendarSubscriptions', 'getCalendarSubscription', 'createCalendarSubscription', 'updateCalendarSubscription',
            'deleteCalendarSubscription', 'refreshCalendarSubscription', 'listCalendarEvents', 'convertCalendarEvent',
            'createProject', 'updateProject', 'deleteProject', 'getProject', 'listProjects',
            'createTaskConnection', 'updateTaskConnection', 'updateTaskConnectionCapabilities', 'deleteTaskConnection', 'getTaskConnection', 'listTaskConnections',
            'createTaskList', 'updateTaskList', 'updateTaskListSharing', 'deleteTaskList', 'getTaskList', 'listTaskLists',
            'createTaskListSection', 'updateTaskListSection', 'deleteTaskListSection', 'getTaskListSection', 'listTaskListSections',
            'createTaskSyncCursor', 'updateTaskSyncCursor', 'deleteTaskSyncCursor', 'getTaskSyncCursor', 'listTaskSyncCursors', 'previewTaskSync', 'importTaskSyncItem', 'applyTaskSyncItem', 'previewTaskSyncExport',
            'getTaskTombstone', 'listTaskTombstones', 'findTaskTombstoneByExternalReference',
            'createTask', 'updateTask', 'updateTaskPlanning', 'updateTaskChecklist', 'updateTaskTiming', 'updateTaskImportance', 'updateTaskBody', 'updateTaskRecurrence', 'updateTaskOccurrences', 'updateTaskReminders', 'updateTaskMetadata', 'updateTaskOrder', 'updateExternalTaskSource', 'updateExternalTaskSyncState', 'updateExternalTaskProviderData', 'deleteTask', 'getTask', 'listTasks',
            'listTaskAttachments', 'createTaskAttachment', 'getTaskAttachment', 'updateTaskAttachment', 'deleteTaskAttachment', 'downloadTaskAttachmentContent',
            'linkExternalReference', 'replaceExternalReference', 'updateExternalTaskLocation', 'updateExternalTaskAssignment', 'unlinkExternalReference', 'findTaskByExternalReference',
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
            'getInstanceBackupSummary', 'createInstanceBackup', 'getInstanceOperation', 'discardInstanceArtifact', 'authorizeInstanceDownload', 'downloadInstanceBackup', 'createInstanceRestoreUpload', 'getInstanceRestoreUpload', 'appendInstanceRestoreUpload', 'validateInstanceRestoreUpload', 'checkInstanceRestoreTarget',
            'getAdministrationSettings', 'updateAdministrationSettings', 'getProfile', 'updateProfile', 'exportData', 'importData', 'listChanges', 'getOpenApi', 'getServerInformation',
        }
        operations = {v['operationId'] for item in CONTRACT['paths'].values()
                      for key, v in item.items() if key in ('get', 'put', 'post', 'delete')}
        self.assertEqual(expected, operations)


if __name__ == '__main__':
    unittest.main()
