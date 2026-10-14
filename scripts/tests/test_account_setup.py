import importlib.util
import json
import hashlib
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('configure_accounts',ROOT/'server/scripts/configure_accounts.py')
accounts=importlib.util.module_from_spec(spec);spec.loader.exec_module(accounts)

class AccountSetupTest(unittest.TestCase):
    def test_challenge_contains_only_hash_and_explicit_owner_and_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'setup.json'
            code,value=accounts.create_challenge(path,owner='existing-owner')
            self.assertEqual(43,len(code));self.assertNotIn(code,path.read_text())
            self.assertEqual(hashlib.sha256(code.encode()).hexdigest(),value['sha256'])
            self.assertEqual('existing-owner',value['ownerId']);self.assertEqual('SETUP',value['purpose'])
            self.assertEqual(0o600,path.stat().st_mode & 0o777)
            self.assertGreater(datetime.fromisoformat(value['expiresAt']),datetime.now(timezone.utc))
            original=path.read_bytes()
            with self.assertRaises(ValueError):accounts.create_challenge(path)
            self.assertEqual(original,path.read_bytes())
            next_code,recovery=accounts.create_challenge(path,purpose='RECOVERY',owner='existing-owner',replace=True)
            self.assertNotEqual(next_code,code);self.assertEqual('RECOVERY',recovery['purpose']);self.assertEqual(recovery,json.loads(path.read_text()))
    def test_recovery_requires_owner_and_symlinks_and_invalid_lifetimes_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'setup.json'
            for options in [{'purpose':'RECOVERY'},{'owner':'local'},{'owner':' alice '},{'minutes':0},{'minutes':61}]:
                with self.assertRaises(ValueError):accounts.create_challenge(path,**options)
            target=Path(directory)/'target';target.write_text('untouched');path.symlink_to(target)
            with self.assertRaises(ValueError):accounts.create_challenge(path,replace=True)
            self.assertEqual('untouched',target.read_text())
