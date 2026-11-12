"""Operator app registrations survive setup and logical restore without leaking."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class TaskProviderDeploymentTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.state = self.root / 'state with spaces'
        self.deploy = self.root / 'deploy'
        self.deploy.mkdir()
        for name in ('setup.py', 'restore.sh', 'environment.sh'):
            shutil.copy(ROOT / 'server/deploy' / name, self.deploy / name)
        shutil.copy(ROOT / 'server/scripts/configure_accounts.py', self.deploy / 'configure_accounts.py')
        self.environment = {key: value for key, value in os.environ.items() if not key.startswith(('TIMEKEEPER_', 'DAGSVERK_'))}
        self.registration = self.state / 'secrets/task-providers/application.properties'

    def setup(self):
        result = subprocess.run([sys.executable, str(self.deploy / 'setup.py'), '--state-dir', str(self.state)],
                                env=self.environment, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return result

    def test_setup_preserves_existing_registration_and_keeps_host_parent_private(self):
        self.setup()
        self.assertEqual(0o700, self.registration.parents[1].stat().st_mode & 0o777)
        self.assertEqual(0o755, self.registration.parent.stat().st_mode & 0o777)
        self.assertEqual(0o444, self.registration.stat().st_mode & 0o777)
        self.assertTrue(all(line.startswith('#') for line in self.registration.read_text().splitlines()))
        self.registration.chmod(0o600)
        value = 'timekeeper.tasks.google.client-secret=synthetic-registration\n'
        self.registration.write_text(value)
        result = self.setup()
        self.assertEqual(value, self.registration.read_text())
        self.assertNotIn('synthetic-registration', result.stdout + result.stderr + (self.deploy / '.env').read_text())

    def test_restore_uses_matching_registration_and_clears_destination_for_old_backups(self):
        self.setup()
        binary = self.root / 'bin'
        binary.mkdir()
        docker = binary / 'docker'
        docker.write_text('#!/bin/sh\nexit 0\n')
        docker.chmod(0o755)
        for name in ('instance_material.py', 'restore-database.py'):
            (self.deploy / name).write_text('')
        backup = self.root / 'backup with spaces'
        backup.mkdir()
        (backup / 'database.dump').write_text('PGDMP fixture')
        (backup / 'tokens.json').write_text('[]')
        for legacy in (False, True):
            with self.subTest(legacy=legacy):
                self.registration.chmod(0o600)
                self.registration.write_text('destination-registration')
                source = backup / 'task_providers.properties'
                source.unlink(missing_ok=True)
                if not legacy:
                    source.write_text('timekeeper.tasks.microsoft.client-secret=synthetic-source-registration\n')
                result = subprocess.run(['sh', str(self.deploy / 'restore.sh'), str(backup)],
                    env={**self.environment, 'PATH': str(binary) + os.pathsep + self.environment['PATH'],
                         'TIMEKEEPER_COMPOSE_LOCK_HELD': '1'}, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertNotIn('destination-registration', self.registration.read_text())
                self.assertEqual(source.read_text() if not legacy else '# No task-provider app registrations in this backup.\n', self.registration.read_text())
                self.assertEqual(0o444, self.registration.stat().st_mode & 0o777)
                self.assertNotIn('synthetic-source-registration', result.stdout + result.stderr)
