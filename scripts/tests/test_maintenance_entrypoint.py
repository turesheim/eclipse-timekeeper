"""A partially restored DSM database must never be exposed by a restarted container."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]

class MaintenanceEntrypointTest(unittest.TestCase):
    def test_pending_maintenance_prevents_java_start_even_without_database_password(self):
        with tempfile.TemporaryDirectory() as temp:
            pending=Path(temp)/'pending';pending.write_text('{}')
            result=subprocess.run(['sh',str(ROOT/'server/deploy/server-entrypoint.sh')],
                env={**os.environ,'TIMEKEEPER_MAINTENANCE_FILE':str(pending),
                     'TIMEKEEPER_SERVER_DB_PASSWORD_FILE':str(Path(temp)/'unreadable')},capture_output=True,text=True)
            self.assertEqual(1,result.returncode)
            self.assertIn('maintenance is incomplete',result.stderr)
            self.assertNotIn('unreadable',result.stderr)

    def test_completed_maintenance_starts_the_server_with_normal_java_options(self):
        with tempfile.TemporaryDirectory() as temp:
            java=Path(temp)/'java';java.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n');java.chmod(0o755)
            environment={key:value for key,value in os.environ.items() if not key.startswith('TIMEKEEPER_')}
            result=subprocess.run(['sh',str(ROOT/'server/deploy/server-entrypoint.sh')],
                env={**environment,'PATH':temp+os.pathsep+os.environ['PATH'],
                     'TIMEKEEPER_MAINTENANCE_FILE':str(Path(temp)/'completed'), 'JAVA_OPTS':'-Xmx384m'},capture_output=True,text=True)
            self.assertEqual(0,result.returncode)
            self.assertEqual(['-Xmx384m','-jar','/app/quarkus-run.jar'],result.stdout.splitlines())
