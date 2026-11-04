"""Regression checks for selective CI, including failed history and skipped gates."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / (name + '.py'))
    result = importlib.util.module_from_spec(spec);spec.loader.exec_module(result)
    return result
ci, browser, result = (module(name) for name in ('ci_changes', 'ci_browser', 'ci_result'))


class ChangesTest(unittest.TestCase):
    def select(self, paths, event=None, name='pull_request', ref='refs/pull/1/merge'):
        event = event or {'pull_request': {'base': {'sha': 'abc'}}}
        with patch.object(ci.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, ('\0'.join(paths)+'\0').encode(), b'')):
            return ci.select_jobs(event, name, ref, 'def')

    def test_css_only_builds_fixture_and_layout_suites_without_backend_or_deployment(self):
        selected = self.select(['server/web/src/style.css'])
        for key in ('code', 'server', 'web'):self.assertTrue(selected[key], key)
        for key in ('server_tests', 'api', 'eclipse', 'remote_eclipse', 'containers', 'site', 'full', 'web_full'):self.assertFalse(selected[key], key)
        self.assertEqual(sorted(ci.LAYOUT_SPECS), selected['browser_specs'])
        self.assertFalse(self.select(['server/web/src/markdown.css'])['web_full'])

    def test_feature_and_shared_web_changes(self):
        self.assertEqual(['day-notes.spec.js', 'today.spec.js'], self.select(['server/web/src/today.js'])['browser_specs'])
        selected = self.select(['server/web/src/markdown-field.js'])
        self.assertTrue(selected['web_full']);self.assertFalse(selected['full'])
        self.assertFalse(self.select(['server/web/tests/today.spec.js'])['server_tests'])
        self.assertTrue(self.select(['server/web/tests/acceptance.py'])['web_full'])

    def test_larger_known_feature_unions_are_selected_without_expanding_to_every_spec(self):
        selected = self.select(['server/web/src/style.css', 'server/web/src/calendar.js'])
        self.assertFalse(selected['web_full']);self.assertEqual(7,len(selected['browser_specs']))
        self.assertEqual(2,len(selected['browser_matrix']['include']))

    def test_ordinary_backend_and_sensitive_changes(self):
        selected = self.select(['server/app/src/main/java/net/ProjectResource.java'])
        self.assertTrue(selected['server_tests']);self.assertTrue(selected['api']);self.assertTrue(selected['web_full'])
        self.assertFalse(selected['containers'])
        for path in ('server/app/src/main/java/net/AuthenticationFilter.java',
                     'server/app/src/test/java/net/SchemaTest.java', 'server/app/src/main/resources/application.properties',
                     'server/app/pom.xml', 'server/Dockerfile', 'server/synology/INFO',
                     'server/web/package-lock.json', 'server/web/src/api.js', '.github/workflows/build.yml',
                     'scripts/ci_changes.py', 'eclipse/default.target', 'common/api/openapi.yaml', 'unknown/file'):
            with self.subTest(path=path):
                self.assertTrue(self.select([path])['full'])

    def test_sensitive_server_and_tooling_changes_keep_linux_integration_without_desktop_matrix(self):
        for path in ('server/app/src/main/java/net/AuthenticationFilter.java',
                     'server/app/src/main/java/net/VaultDavResource.java',
                     'server/app/src/test/java/net/SchemaTest.java', 'server/app/pom.xml',
                     'server/core-domain/pom.xml', 'server/web/package-lock.json',
                     'server/web/src/api.js', 'server/synology/INFO',
                     '.github/workflows/build.yml', '.github/workflows/server-containers.yml',
                     'scripts/ci_changes.py', 'scripts/tests/test_browser_tls_fixture.py'):
            with self.subTest(path=path):
                for name, event, ref in (('pull_request', None, 'refs/pull/1/merge'),
                                         ('push', {'before': 'abc'}, 'refs/heads/main')):
                    selected = self.select([path], event, name, ref)
                    self.assertFalse(selected['eclipse'])
                    for key in ('full', 'remote_eclipse', 'server_tests', 'api', 'web_full'):
                        self.assertTrue(selected[key], key)

    def test_desktop_shared_build_inputs_unknown_and_mixed_changes_keep_all_platforms(self):
        for path in ('eclipse/default.target', 'eclipse/net.resheim.eclipse.timekeeper.ui/plugin.xml',
                     'common/timekeeper-domain/pom.xml', 'common/api/openapi.yaml',
                     'pom.xml', 'mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties', 'unknown/file'):
            with self.subTest(path=path):
                selected = self.select(['server/app/pom.xml', path])
                self.assertTrue(selected['eclipse'])
                self.assertTrue(selected['remote_eclipse'])

    def test_docs_skip_product_but_run_site_and_overrides_certify_all(self):
        selected = self.select(['README.md', 'docs/assets/screenshot.png'])
        self.assertTrue(selected['site']);self.assertFalse(selected['code'])
        event = {'pull_request': {'base': {'sha': 'abc'}, 'labels': [{'name':'ci:full'}]}}
        for paths in (['README.md'], ['server/app/pom.xml']):
            selected = self.select(paths, event)
            for key in ('full', 'eclipse', 'remote_eclipse'):self.assertTrue(selected[key], key)
        self.assertFalse(self.select(['server/web/src/style.css'], {'before':'abc'}, 'push', 'refs/heads/main')['full'])
        self.assertFalse(self.select(['server/web/src/style.css'], {'before':'abc'}, 'push', 'refs/heads/main')['containers'])
        for name, ref in [('schedule','refs/heads/main'), ('workflow_dispatch','refs/heads/main'), ('push','refs/tags/v2')]:
            with patch.object(ci.subprocess,'run') as git:
                selected = ci.select_jobs({},name,ref,'def')
                for key in ('full', 'eclipse', 'remote_eclipse'):self.assertTrue(selected[key], key)
                git.assert_not_called()

    def test_selected_suites_always_have_their_required_fixture_and_tests(self):
        for path in ('README.md', 'server/web/src/style.css', 'server/web/src/today.js',
                     'server/web/src/markdown-field.js', 'server/app/src/main/java/net/ProjectResource.java',
                     'server/app/src/main/java/net/AuthenticationFilter.java', '.github/workflows/build.yml'):
            selected = self.select([path])
            with self.subTest(path=path):
                if selected['web']:self.assertTrue(selected['server'])
                if selected['containers']:self.assertTrue(selected['server_tests'])
                if selected['eclipse']:self.assertTrue(selected['server_tests'])
                if selected['remote_eclipse']:self.assertTrue(selected['server_tests'])
                if selected['server_tests']:self.assertTrue(selected['api'])

    def test_bad_or_empty_history_cannot_skip_validation(self):
        for response in (subprocess.CompletedProcess([],128,b'',b''),subprocess.CompletedProcess([],0,b'',b'')):
            with patch.object(ci.subprocess,'run',return_value=response):
                selected = ci.select_jobs({'before':'abc'},'push','refs/heads/main','def')
                for key in ('full', 'eclipse', 'remote_eclipse'):self.assertTrue(selected[key], key)

    def test_main_web_merge_does_not_repeat_platform_or_database_certification(self):
        paths=['server/web/editor/structured-commands.js','server/web/src/markdown-field.js',
               'server/web/src/assets/milkdown/milkdown.js','server/web/src/locales/en.json',
               'server/web/tests/editor-experience.spec.js','docs/operations/MARKDOWN-EDITOR.md']
        selected=self.select(paths,{'before':'abc'},'push','refs/heads/main')
        for key in ('code','server','web','web_full'):self.assertTrue(selected[key],key)
        for key in ('full','server_tests','api','eclipse','remote_eclipse','containers','container_full'):self.assertFalse(selected[key],key)
        self.assertEqual(4,len(selected['browser_matrix']['include']))
        for path in ('server/app/src/main/java/net/ProjectResource.java','server/deploy/compose.yaml',
                     'server/app/src/main/java/net/DatabaseStore.java'):
            self.assertTrue(self.select([path],{'before':'abc'},'push','refs/heads/main')['container_full'])

    def test_browser_matrix_has_one_job_per_bounded_share(self):
        for size,expected in ((1,1),(6,1),(7,2),(19,4),(80,4)):
            rows=ci.browser_matrix(['fixture']*size)['include']
            self.assertEqual(list(range(1,expected+1)),[row['shard'] for row in rows])
            self.assertEqual({expected},{row['shards'] for row in rows})

    def test_pr_compares_merge_base_and_push_previous_head_without_renames(self):
        with patch.object(ci.subprocess,'run',return_value=subprocess.CompletedProcess([],0,b'README.md\0',b'')) as git:
            ci.select_jobs({'pull_request':{'base':{'sha':'abc'}}},'pull_request','refs/pull/1/merge','def')
            self.assertEqual('abc...def',git.call_args.args[0][-1]);self.assertIn('--no-renames',git.call_args.args[0])
            ci.select_jobs({'before':'abc'},'push','refs/heads/main','def')
            self.assertEqual('abc..def',git.call_args.args[0][-1])

    def test_real_code_move_to_docs_still_validates(self):
        original=Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                def git(*args):return subprocess.check_output(['git',*args],text=True).strip()
                git('init','-q');git('config','user.name','CI fixture');git('config','user.email','fixture@example.invalid')
                Path('Example.java').write_text('class Example {}');git('add','.');git('commit','-qm','Baseline');base=git('rev-parse','HEAD')
                Path('docs').mkdir();Path('Example.java').rename('docs/Example.java');git('add','.');git('commit','-qm','Move code')
                self.assertTrue(ci.select_jobs({'pull_request':{'base':{'sha':base}}},'pull_request','refs/pull/1/merge',git('rev-parse','HEAD'))['full'])
            finally:os.chdir(original)

    def test_browser_arguments_are_bounded_and_unknown_specs_fail(self):
        self.assertEqual('--spec',browser.command(['today.spec.js'])[-2])
        self.assertNotIn('--spec',browser.command([]))
        for spec in ['../today.spec.js','missing.spec.js','--help',123]:
            with self.assertRaises(ValueError):browser.command([spec])
        self.assertEqual(['--shard','2/4'],browser.command(['today.spec.js'],'2/4')[-2:])
        for shard in ('0/4','5/4','4/2','1/0','1/4 --help',123):
            with self.assertRaises(ValueError):browser.command([],shard)

    def test_aggregate_rejects_skipped_selected_jobs_and_failed_selector(self):
        needs={'Changes':{'result':'success','outputs':{**{key:'false' for key in ('server','web','api','eclipse','remote_eclipse','containers','site')},'server':'true'}},
               'Server':{'result':'success'},'Web':{'result':'skipped'},'Publication-tooling':{'result':'success'}}
        self.assertEqual([],result.failures(needs))
        for state in ('skipped','failure','cancelled'):
            needs['Server']['result']=state;self.assertEqual(['Server'],result.failures(needs))
        needs['Changes']['result']='failure';self.assertEqual(['Changes'],result.failures(needs))
        needs['Changes']['result']='success';needs['Server']['result']='success'
        needs['Changes']['outputs']['containers']='true';needs['Containers']={'result':'failure'}
        self.assertEqual(['Containers'],result.failures(needs))
        del needs['Containers'];self.assertEqual(['Containers'],result.failures(needs))
        del needs['Changes']['outputs']['containers'];self.assertEqual(['Invalid validation selection'],result.failures(needs))

    def test_server_gate_requires_installed_client_but_accepts_unselected_desktop_build(self):
        outputs={key:'false' for key in ('server','web','api','eclipse','remote_eclipse','containers','site')}
        outputs['remote_eclipse']='true'
        needs={'Changes':{'result':'success','outputs':outputs},
               'Remote-Eclipse':{'result':'success'},'Build':{'result':'skipped'},
               'Publication-tooling':{'result':'success'}}
        self.assertEqual([],result.failures(needs))
        for state in ('skipped','failure','cancelled'):
            needs['Remote-Eclipse']['result']=state
            self.assertEqual(['Remote-Eclipse'],result.failures(needs))
        needs['Remote-Eclipse']['result']='success'
        del needs['Remote-Eclipse'];self.assertEqual(['Remote-Eclipse'],result.failures(needs))
        del outputs['remote_eclipse'];self.assertEqual(['Invalid validation selection'],result.failures(needs))
