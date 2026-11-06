"""Rolling SPKs require successful main source and cannot replace versioned releases."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import synology_latest as latest
import test_synology_release as fixtures


class LatestTest(unittest.TestCase):
    def test_prepare_joins_exact_successful_main_workflows_and_checks_the_revision(self):
        fixture = fixtures.SynologyReleaseTest(methodName='runTest')
        fixture.setUp();self.addCleanup(fixture.doCleanups)
        fixture.matrix['run_number'] = 1
        fixture.matrix_jobs.add('PR validation')
        original = latest.release.pages
        def pages(repo, path, field):
            if path.startswith('actions/workflows/server-containers.yml/runs?'):
                return [fixture.run]
            return original(repo, path, field)
        fixture.output.mkdir()
        with patch.object(latest.release, 'pages', side_effect=pages):
            state = latest.prepare(fixtures.REPO, fixtures.COMMIT, fixture.output)
            self.assertEqual(1, state['revision'])
            self.assertEqual(50, state['artifact'])
            self.assertTrue((fixture.output/latest.EVIDENCE).is_file())
            fixture.matrix['run_number'] = 2
            second = fixture.root/'second';second.mkdir()
            with self.assertRaisesRegex(ValueError, 'revision'):
                latest.prepare(fixtures.REPO, fixtures.COMMIT, second)

    def test_one_completed_workflow_waits_for_its_counterpart(self):
        with patch.object(latest.release, 'api', return_value={'default_branch':'main'}), patch.object(latest.release, 'pages', return_value=[]):
            self.assertIsNone(latest.prepare('owner/repo', 'a'*40, Path('/unused')))

    def test_unmanaged_release_is_never_overwritten(self):
        for release in ({'tag_name':latest.TAG,'prerelease':False,'body':''},
                        {'tag_name':latest.TAG,'prerelease':True,'body':'user release'}):
            with patch.object(latest, 'pages', return_value=[release]), self.assertRaises(ValueError):
                latest.current_release('owner/repo')

    def test_an_older_completion_cannot_replace_the_latest_build(self):
        with patch.object(latest, 'current_release', return_value=({}, {'revision':100})), patch.object(latest.release, 'gh') as gh:
            result = latest.publish('owner/repo', Path('/unused'), {'revision':99})
            self.assertFalse(result['published']);gh.assert_not_called()

    def test_publish_verifies_uploads_before_pruning_only_the_managed_release(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)/'distribution';directory.mkdir()
            name = 'timekeeper-1.99.0-2-r1000.spk'
            state = {'revision':2,'commit':'a'*40,'package':name,'validation':'web-smoke','artifact':55}
            for file in (name,name+'.sha256',name[:-4]+'.json',latest.EVIDENCE):
                (directory/file).write_text('synthetic '+file)
            assets = [{'id':i,'name':file.name,'digest':'sha256:'+hashlib.sha256(file.read_bytes()).hexdigest()}
                      for i,file in enumerate(directory.iterdir(),1)]
            assets.append({'id':99,'name':'timekeeper-1.99.0-1-r1000.spk'})
            with patch.object(latest, 'current_release', return_value=({'id':1},{'revision':1})), \
                 patch.object(latest.release, 'api', side_effect=lambda repo,path: [] if path.startswith('git/') else {'assets':assets}), \
                 patch.object(latest.release, 'gh') as gh, \
                 patch.object(latest, 'clean_artifacts', return_value={}) as cleanup:
                self.assertTrue(latest.publish('owner/repo',directory,state)['published'])
                deletes = [call.args for call in gh.call_args_list if 'DELETE' in call.args]
                self.assertEqual([('api','repos/owner/repo/releases/assets/99','--method','DELETE')],deletes)
                cleanup.assert_called_once_with('owner/repo',55,apply=True)
                gh.reset_mock();assets[0]['digest']='sha256:'+'0'*64
                with self.assertRaisesRegex(ValueError,'incomplete'):latest.publish('owner/repo',directory,state)
                self.assertFalse(any('DELETE' in call.args for call in gh.call_args_list))

    def test_first_draft_is_verified_by_release_id_and_its_missing_tag_is_created(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary)/'distribution';directory.mkdir()
            name='timekeeper-1.99.0-1-r1000.spk'
            state={'revision':1,'commit':'a'*40,'package':name,'validation':'web-smoke','artifact':55}
            for file in (name,name+'.sha256',name[:-4]+'.json',latest.EVIDENCE):(directory/file).write_text(file)
            assets=[{'id':i,'name':p.name,'digest':'sha256:'+hashlib.sha256(p.read_bytes()).hexdigest()} for i,p in enumerate(directory.iterdir())]
            def api(repo,path):
                self.assertNotIn('releases/tags',path)
                return [] if path.startswith('git/') else {'assets':assets}
            with patch.object(latest,'current_release',side_effect=[(None,None),({'id':77},{})]), \
                 patch.object(latest.release,'api',side_effect=api), patch.object(latest.release,'gh') as gh, \
                 patch.object(latest,'clean_artifacts',return_value={}):
                self.assertTrue(latest.publish('owner/repo',directory,state)['published'])
                self.assertTrue(any(call.args[:3]==('api','repos/owner/repo/git/refs','--method') and 'POST' in call.args for call in gh.call_args_list))
