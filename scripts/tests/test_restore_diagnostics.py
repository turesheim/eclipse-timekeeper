"""Restore acceptance failures must remain useful without publishing private SQL."""
import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
path=ROOT/'server/scripts/schema_restore_acceptance.py'
spec=importlib.util.spec_from_file_location('restore_acceptance_diagnostics',path)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class RestoreDiagnosticsTest(unittest.TestCase):
    def test_known_guards_have_only_fixed_categories(self):
        for message,code in [('Stop all other database clients before maintenance','other-database-clients'),('Noncanonical Timekeeper schema','noncanonical-schema'),('Incomplete Timekeeper schema','incomplete-schema'),('Missing instance-operation control','missing-instance-control')]:
            self.assertEqual(code,module.failure_reason(('ERROR: '+message+'\nDETAIL: private credential and SQL row').encode()))

    def test_arbitrary_sql_and_secret_content_never_escape(self):
        for stderr in [b'ERROR: private SQL\nDETAIL: password=secret',b'\xff\x00private row',None,b'']:
            self.assertEqual('command-rejected',module.failure_reason(stderr))
