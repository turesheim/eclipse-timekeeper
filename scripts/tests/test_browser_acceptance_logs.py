"""Later browser invocations must not discard the full suite's server diagnostics."""
from pathlib import Path
import importlib.util
import shutil
import sys
import tempfile
import unittest
import json
import re
from types import SimpleNamespace
from unittest.mock import patch
import xml.etree.ElementTree as ET

web_tests=Path(__file__).resolve().parents[2]/'server/web/tests'
original_path=sys.path[:]
try:
    sys.path.insert(0,str(web_tests))
    spec=importlib.util.spec_from_file_location('browser_acceptance_log_fixture',web_tests/'acceptance.py')
    browser_acceptance=importlib.util.module_from_spec(spec);spec.loader.exec_module(browser_acceptance)
finally:sys.path[:]=original_path
retain_server_logs=browser_acceptance.retain_server_logs


class BrowserAcceptanceLogsTest(unittest.TestCase):
    def test_later_browser_output_cleanup_preserves_every_server_invocation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);source=root/'target/server.log';source.parent.mkdir();output=root/'test-results'
            source.write_text('full suite diagnostic\n');first=retain_server_logs(source,output,{'stage':'full'})
            shutil.rmtree(output)
            source.write_text('later HTTPS diagnostic\n');second=retain_server_logs(source,output,{'stage':'https'})
            self.assertEqual('full suite diagnostic\n',(output/'server-logs'/first.name).read_text())
            self.assertEqual('later HTTPS diagnostic\n',(output/'server-logs'/second.name).read_text())
            self.assertEqual('later HTTPS diagnostic\n',source.read_text())
            third=retain_server_logs(source,output,{'stage':'https'})
            self.assertNotEqual(second,third);self.assertEqual(3,len(list((output/'server-logs').glob('*.log'))))

    def test_full_suite_batches_cover_every_spec_once_with_bounded_fixture_lifetime(self):
        specs=[path.name for path in web_tests.glob('*.spec.js')]
        batches=browser_acceptance.spec_batches(reversed(specs))
        self.assertEqual(sorted(specs),[name for batch in batches for name in batch])
        self.assertTrue(all(1<=len(batch)<=6 for batch in batches))
        self.assertGreater(len(batches),1)
        for name in specs:
            for prefix in ('/fixture/tests/','C:/fixture/tests/'):
                selected=[spec for spec in specs if re.search(browser_acceptance.spec_pattern(spec),prefix+name)]
                self.assertEqual([name],selected)

    def test_batch_artifacts_keep_earlier_failures_screenshots_and_authentication_audits(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);saved=root/'saved';output=root/'output';output.mkdir()
            for index in range(2):
                folder=saved/str(index);folder.mkdir(parents=True)
                (folder/f'screenshot-{index}.png').write_bytes(bytes([index]))
                (folder/'browser.xml').write_text(f'<testsuites><testsuite name="batch-{index}" tests="2" failures="{index}" errors="0" skipped="1" time="1.5"><testcase name="case-{index}"/></testsuite></testsuites>')
                (folder/'transport.json').write_text(json.dumps({'authenticationRequests':[{'batch':index}]}))
            browser_acceptance.restore_batch_artifacts(saved,output)
            report=ET.parse(output/'browser.xml').getroot()
            self.assertEqual('4',report.get('tests'));self.assertEqual('1',report.get('failures'));self.assertEqual('2',report.get('skipped'))
            self.assertEqual(['batch-0','batch-1'],[suite.get('name') for suite in report])
            for index in range(2):
                self.assertEqual(bytes([index]),(output/f'screenshot-{index}.png').read_bytes())
                self.assertTrue((output/'batches'/str(index)/'browser.xml').exists())
            self.assertEqual([{'batch':0},{'batch':1}],json.loads((output/'transport.json').read_text())['authenticationRequests'])

    def test_a_failed_batch_is_not_retried_or_hidden_by_an_earlier_success_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);output=root/'output';args=SimpleNamespace(distribution=root/'server.zip',java='java',browser='chromium',https=False,mail=False,passkeys=False,restore=False)
            calls=[]
            def run(command,timeout):
                calls.append(command)
                (output/'browser.xml').write_text('<testsuites><testsuite tests="1" failures="0"/></testsuites>')
                (output/f'batch-{len(calls)}.png').write_bytes(b'evidence')
                if len(calls)==1:(output/'acceptance.json').write_text(json.dumps({'passed':True,'distributionSha256':'digest','sourceCommit':'commit'}))
                return SimpleNamespace(returncode=0 if len(calls)==1 else 1)
            with patch.object(browser_acceptance,'spec_batches',return_value=[['first.spec.js'],['second.spec.js']]),patch.object(browser_acceptance.subprocess,'run',side_effect=run):
                with self.assertRaisesRegex(RuntimeError,'fixture 2 failed'):browser_acceptance.run_batches(args,output,'digest')
            self.assertEqual(2,len(calls));self.assertEqual(['--spec','first.spec.js'],calls[0][-2:]);self.assertEqual(['--spec','second.spec.js'],calls[1][-2:])
            receipt=json.loads((output/'acceptance.json').read_text())
            self.assertFalse(receipt['passed']);self.assertEqual(1,len(receipt['batches']))
            self.assertTrue((output/'batch-1.png').exists());self.assertTrue((output/'batch-2.png').exists())

    def test_identity_never_becomes_a_filesystem_path_or_reveals_its_contents(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);source=root/'server.log';source.write_text('safe diagnostic\n')
            saved=retain_server_logs(source,root/'output',{'selector':'../../private-token\ntext'})
            self.assertEqual(source.parent/'server-logs',saved.parent)
            self.assertRegex(saved.name,r'^[a-f0-9]{20}-[a-f0-9]{16}\.log$')
            self.assertNotIn('private-token',saved.name)
