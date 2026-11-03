"""Parallel native acceptance must retain every supported upgrade and migration."""
from test_ci_changes import module
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ci=module('ci_synology')


class SynologyGroupsTest(unittest.TestCase):
    def test_all_schema_migrations_and_interrupted_upgrades_remain_covered_once(self):
        commands=[command for group in ci.GROUPS for command in ci.commands(group,'candidate:image')]
        migrations=[command for command in commands if 'server/synology/tests/migration_acceptance.py' in command]
        schemas=[int(command[command.index('--source-schema')+1]) for command in migrations if '--source-schema' in command]
        self.assertEqual(list(range(2,11)),sorted(schemas))
        runtime=[command for command in migrations if '--server-image' in command]
        self.assertEqual(1,len(runtime));self.assertEqual('candidate:image',runtime[0][-1])
        upgrades=[command for command in commands if 'server/synology/tests/schema_upgrade_acceptance.py' in command]
        self.assertEqual(list(range(2,10)),sorted(int(schema) for command in upgrades for schema in command[command.index('--source-schema')+1:]))
        for script in ('acceptance.py','schema_restore_acceptance.py'):
            self.assertEqual(1,sum(any(argument.endswith('/'+script) for argument in command) for command in commands))

    def test_unknown_groups_and_missing_candidate_fail_before_launch(self):
        with self.assertRaises(ValueError):ci.commands('upgrade-unknown','candidate:image')
        with self.assertRaises(ValueError):ci.commands('migrate-runtime',None)

    def test_failed_commands_propagate_and_do_not_produce_a_success_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory)
            with patch.object(ci.subprocess,'run',side_effect=[None,subprocess.CalledProcessError(1,['go','vet'])]) as run:
                with self.assertRaises(subprocess.CalledProcessError):ci.run_group('installer',None,output)
            receipt=json.loads((output/'installer.json').read_text())
            self.assertFalse(receipt['passed']);self.assertEqual(1,len(receipt['commands']))
            self.assertEqual(2,run.call_count)
            self.assertTrue(run.call_args.kwargs['check']);self.assertEqual(600,run.call_args.kwargs['timeout'])
