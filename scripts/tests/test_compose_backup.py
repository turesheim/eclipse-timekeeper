"""Backups must work after rollback to an image whose mutable tag has moved."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BACKUP_SCRIPT = ROOT / 'server/deploy/backup.sh'


class ComposeBackupTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        shutil.copy(BACKUP_SCRIPT, self.root / 'backup.sh')
        for name in ('instance_material.py', 'restore-database.py'):
            shutil.copy(ROOT / 'server/deploy' / name, self.root / name)
        self.state = self.root / 'state with spaces'
        for name, content in {'config/tokens.json': '[]',
                              'config/mail.properties': '# mail fixture\n',
                              'secrets/timekeeper_smtp_password': 'fixture-password'}.items():
            target = self.state / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)
        (self.root / '.env').write_text("TIMEKEEPER_STATE_DIR='./state with spaces'\n")
        binary = self.root / 'bin'
        binary.mkdir()
        docker = binary / 'docker'
        docker.write_text(f'#!{sys.executable}\n' + '''
import os
import sys

args = sys.argv[1:]
if args[:2] == ['compose', 'run']:
    if '--user' in args:
        if any('flock -n 9' in arg for arg in args):
            print('LOCKED', flush=True); sys.stdin.read(); sys.exit(0)
        sys.exit(3)
    if '--table=tk_schema' in args:
        print('COPY public.tk_schema (id, schema_version, state) FROM stdin;\\n1\\t9\\tREADY\\n\\\\.\\n-- dump done')
        sys.exit(0)
    if '--table=tk_instance' in args:
        print('COPY public.tk_instance (id, kind, version, operation_id, actor_owner, occurred_at, payload) FROM stdin;\\n00000000-0000-0000-0000-000000000000\\tCONTROL\\t1\\t\\\\N\\t\\\\N\\t1970-01-01T00:00:00Z\\t{}\\n\\\\.')
        sys.exit(0)
    if os.environ.get('FAIL_DUMP'):
        sys.exit('Database backup failed')
    print('PGDMP fixture')
elif args == ['compose', 'images']:
    sys.exit('Error response from daemon: No such image: sha256:previous')
elif args == ['compose', 'ps', '--all', '--quiet']:
    print('replica-one\\nreplica-two')
elif args[:3] == ['inspect', '--type', 'container']:
    if os.environ.get('FAIL_INSPECT'):
        sys.exit('Container inspection failed')
    print('/' + args[-1] + ' sha256:previous sha256:previous')
else:
    sys.exit('Unexpected Docker command: ' + repr(args))
''')
        docker.chmod(0o755)
        self.environment = {key: value for key, value in os.environ.items()
                            if not key.startswith('TIMEKEEPER_')}
        self.environment['PATH'] = str(binary) + os.pathsep + os.environ['PATH']

    def backup(self, **environment):
        return subprocess.run(['sh', str(self.root / 'backup.sh')],
                              env={**self.environment, **environment}, capture_output=True, text=True)

    def test_backup_records_deployed_images_after_the_candidate_tag_moves(self):
        result = self.backup()
        self.assertEqual(0, result.returncode, result.stderr)
        backup = (self.root / result.stdout.strip()).resolve()
        self.assertEqual('PGDMP fixture\n', (backup / 'database.dump').read_text())
        self.assertEqual('CONTAINER IMAGE IMAGE_ID\n'
                         '/replica-one sha256:previous sha256:previous\n'
                         '/replica-two sha256:previous sha256:previous\n',
                         (backup / 'images.txt').read_text())
        for source, destination in [('config/tokens.json', 'tokens.json'),
                                    ('config/mail.properties', 'mail.properties'),
                                    ('secrets/timekeeper_smtp_password', 'smtp_password')]:
            self.assertEqual((self.state / source).read_bytes(), (backup / destination).read_bytes())
        self.assertEqual(0o700, backup.stat().st_mode & 0o777)
        self.assertTrue(all(path.stat().st_mode & 0o777 == 0o600 for path in backup.iterdir()))

    def test_failed_dump_never_reports_a_completed_backup(self):
        result = self.backup(FAIL_DUMP='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Database backup failed', result.stderr)
        self.assertEqual('', result.stdout)
        self.assertEqual([], list(self.state.glob('backups/*/database.dump')))

    def test_failed_container_inspection_is_reported(self):
        result = self.backup(FAIL_INSPECT='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Container inspection failed', result.stderr)
        self.assertEqual('', result.stdout)
