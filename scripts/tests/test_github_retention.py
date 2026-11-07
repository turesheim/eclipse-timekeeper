"""Retention must preserve current work, versioned outputs and unrelated data."""
import unittest
import json
from unittest.mock import patch

import github_retention as retention


def deployment(identity, environment='github-pages'):
    return {'id': identity, 'created_at': f'2026-11-06T00:00:{identity:02d}Z', 'environment': environment}


class RetentionTest(unittest.TestCase):
    def test_recent_active_and_in_progress_deployments_survive(self):
        items = [deployment(i) for i in range(1, 7)]+[deployment(7, 'production')]
        states = {1: 'success', 2: 'inactive', 3: 'success', 4: 'inactive', 5: 'failure', 6: 'in_progress'}
        self.assertEqual([2, 1], [x['id'] for x in retention.deployment_plan(items, states)])
        with self.assertRaises(ValueError):retention.deployment_plan(items, states, 0)

    def test_apply_rechecks_status_and_never_deletes_newly_busy_records(self):
        items = [deployment(i) for i in range(1, 6)]
        calls = []
        def api(repo, path, method='GET', **fields):
            calls.append((path, method, fields))
            identity = int(path.split('/')[1])
            if method == 'GET':
                if identity == 1 and sum(p.startswith('deployments/1/') and m == 'GET' for p,m,_ in calls) > 1:
                    return [{'state': 'in_progress'}]
                return [{'state': 'success' if identity == 5 else 'inactive'}]
        with patch.object(retention, 'pages', return_value=items), patch.object(retention, 'api', side_effect=api):
            result = retention.clean_pages('owner/repo', apply=True)
        self.assertEqual([2], result['deleted'])
        self.assertEqual(['deployments/2'], [path for path,method,_ in calls if method == 'DELETE'])

    def test_only_old_temporary_spk_artifacts_are_removed(self):
        def artifact(identity, name='synology-installer', branch='main'):
            return {'id': identity, 'created_at': str(identity), 'name': name, 'workflow_run': {'head_branch': branch}}
        current = artifact(5)
        items = [artifact(1), artifact(2, branch='v2.0.0'), artifact(3, branch='v1.0.1a'),
                 artifact(4, name='server-compose'), current, artifact(6)]
        self.assertEqual([1], [x['id'] for x in retention.artifact_plan(items, current)])

    def test_dry_run_never_mutates_and_missing_current_artifact_refuses_cleanup(self):
        items = [deployment(i) for i in range(1, 6)]
        with patch.object(retention, 'pages', return_value=items), patch.object(retention, 'api', return_value=[{'state':'inactive'}]) as api:
            result = retention.clean_pages('owner/repo')
            self.assertEqual([2, 1], result['selected'])
            self.assertTrue(all(len(call.args) == 2 for call in api.call_args_list))
        with patch.object(retention, 'pages', return_value=[]), self.assertRaises(ValueError):
            retention.clean_artifacts('owner/repo', 123, apply=True)

    def test_registry_keeps_all_version_tags_even_when_they_also_have_temporary_tags(self):
        versions = [{'id': i, 'metadata': {'container': {'tags': tags}}}
                    for i,tags in enumerate((['edge','sha-abc'],[],['2.0.0','sha-def'],['v1.0.1a']),1)]
        self.assertEqual([1,2], [item['id'] for item in retention.registry_plan(versions)])

    def test_registry_preserves_untagged_manifests_referenced_by_versioned_indexes(self):
        root, child, obsolete = ['sha256:'+value*64 for value in ('a','b','c')]
        versions = [{'id':i,'name':digest,'metadata':{'container':{'tags':tags}}}
                    for i,(digest,tags) in enumerate(((root,['2.0.0']),(child,[]),(obsolete,[])),1)]
        manifests = {root:{'manifests':[{'digest':child}]}, child:{'schemaVersion':2}}
        protected = retention.versioned_manifests(versions, manifests.__getitem__)
        self.assertEqual({root,child}, protected)
        self.assertEqual([3], [item['id'] for item in retention.registry_plan(versions,protected)])

    def test_retired_package_is_deleted_only_when_a_fresh_inventory_has_no_version_tags(self):
        versions=[{'id':1,'name':'sha256:'+'a'*64,'metadata':{'container':{'tags':['edge']}}}]
        calls=[]
        fresh_tags=[]
        def gh(*args):
            calls.append(args)
            if '--method' in args:return ''
            if '/versions?' in args[1]:
                result=json.loads(json.dumps(versions))
                if sum('/versions?' in item[1] for item in calls)>1:result[0]['metadata']['container']['tags']+=fresh_tags
                return json.dumps([result])
            return json.dumps({'repository':{'full_name':'owner/repo'}})
        with patch.object(retention,'gh',side_effect=gh):
            result=retention.clean_registry('owner/repo',apply=True)
            self.assertTrue(result['packageDeleted'])
            self.assertEqual([1],result['deleted'])
            self.assertEqual(1,len([item for item in calls if 'DELETE' in item]))
            calls.clear();fresh_tags.append('2.0.0')
            with self.assertRaisesRegex(ValueError,'version tag'):retention.clean_registry('owner/repo',apply=True)
            self.assertFalse(any('DELETE' in item for item in calls))
