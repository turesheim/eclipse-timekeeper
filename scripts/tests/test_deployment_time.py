"""Deployment timestamps survive Compose restarts and keep unrelated settings."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DeploymentTimeTest(unittest.TestCase):
    def test_redeployment_replaces_only_timestamp_and_secures_environment_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copy(ROOT / 'server/deploy/set-deployment-time.sh', root)
            environment = root / '.env'
            environment.write_text('# operator settings\nTIMEKEEPER_STATE_DIR=/srv/timekeeper\n'
                                   'TIMEKEEPER_DEPLOYED_AT=2026-01-01T00:00:00Z\n'
                                   'export TIMEKEEPER_DEPLOYED_AT=2026-02-01T00:00:00Z\n'
                                   'OTHER_SETTING="keep spaces"\n')
            for timestamp in ('2026-10-13T08:30:00Z', '2026-10-14T09:15:00Z'):
                subprocess.run(['sh', str(root / 'set-deployment-time.sh'), timestamp], check=True)
                self.assertEqual('# operator settings\nTIMEKEEPER_STATE_DIR=/srv/timekeeper\n'
                                 'OTHER_SETTING="keep spaces"\n'
                                 f'TIMEKEEPER_DEPLOYED_AT={timestamp}\n', environment.read_text())
                self.assertEqual(0o600, environment.stat().st_mode & 0o777)
                self.assertEqual([], list(root.glob('.env.deployment.*')))

    def test_invalid_timestamp_does_not_change_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copy(ROOT / 'server/deploy/set-deployment-time.sh', root)
            environment = root / '.env'
            environment.write_text('TIMEKEEPER_STATE_DIR=/srv/timekeeper\n')
            result = subprocess.run(['sh', str(root / 'set-deployment-time.sh'), 'not-a-timestamp'],
                                    capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual('TIMEKEEPER_STATE_DIR=/srv/timekeeper\n', environment.read_text())
