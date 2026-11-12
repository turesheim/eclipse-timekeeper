"""External prefix migration preserves values and never interprets or prints secrets."""
import importlib.util
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'server/deploy/environment.sh'


class EnvironmentAliasesTest(unittest.TestCase):
    def test_shell_aliases_preserve_legacy_and_prefer_new_values_without_evaluating_them(self):
        base = {key: value for key, value in os.environ.items() if not key.startswith(('TIMEKEEPER_', 'DAGSVERK_'))}
        secret = 'private $(exit 91) `exit 92` " quote ; spaces'
        for suffix in ('STATE_DIR', 'SERVER_DB_PASSWORD', 'CONNECT_ORIGIN', 'CONNECT_ENCRYPTION_KEY'):
            for modern in (False, True):
                with self.subTest(suffix=suffix, modern=modern):
                    env = {**base, 'TIMEKEEPER_' + suffix: 'legacy-private'}
                    if modern:
                        env['DAGSVERK_' + suffix] = secret
                    # A child only reports equality; no credential reaches tool output.
                    env['EXPECTED_ALIAS_VALUE'] = secret if modern else 'legacy-private'
                    command = '. "$1"; test "$(printenv "$2")" = "$EXPECTED_ALIAS_VALUE"'
                    result = subprocess.run(['sh', '-eu', '-c', command, 'alias-test', str(HELPER), 'DAGSVERK_' + suffix], env=env, capture_output=True, text=True)
                    self.assertEqual(0, result.returncode)
                    self.assertNotIn(secret, result.stdout + result.stderr)
                    self.assertNotIn('legacy-private', result.stdout + result.stderr)
                    if modern:
                        self.assertIn('DAGSVERK_' + suffix + ' overrides deprecated TIMEKEEPER_' + suffix, result.stderr)

    def test_setup_configuration_maps_existing_files_and_preserves_new_precedence(self):
        import sys
        sys.path.insert(0, str(ROOT / 'server/scripts'))
        self.addCleanup(lambda: sys.path.remove(str(ROOT / 'server/scripts')))
        spec = importlib.util.spec_from_file_location('dagsverk_setup_alias_test', ROOT / 'server/deploy/setup.py')
        setup = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(setup)
        from unittest.mock import patch
        with patch.dict(os.environ, {'TIMEKEEPER_STATE_DIR': '/legacy/state', 'DAGSVERK_STATE_DIR': '/new/state'}, clear=True):
            with patch('sys.stderr') as output:
                values = setup.configuration()
            self.assertEqual('/new/state', values['DAGSVERK_STATE_DIR'])
            self.assertTrue(output.write.called)
