"""Later browser invocations must not discard the full suite's server diagnostics."""
from pathlib import Path
import importlib.util
import shutil
import sys
import tempfile
import unittest

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

    def test_identity_never_becomes_a_filesystem_path_or_reveals_its_contents(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);source=root/'server.log';source.write_text('safe diagnostic\n')
            saved=retain_server_logs(source,root/'output',{'selector':'../../private-token\ntext'})
            self.assertEqual(source.parent/'server-logs',saved.parent)
            self.assertRegex(saved.name,r'^[a-f0-9]{20}-[a-f0-9]{16}\.log$')
            self.assertNotIn('private-token',saved.name)
