"""Stable packages must preserve NAS-tested bytes and reject incomplete evidence."""
from datetime import datetime, timedelta, timezone
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'server/scripts'))
import test_synology_package as fixtures
from promote_synology import preview, promote
from synology_acceptance import RECORD, template
from synology_catalog import build
from validate_synology import validate


class SynologyPromotionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.package = self.root / 'timekeeper-1.99.0-1-r1000.spk'
        self.package.write_bytes(fixtures.SynologyPackageTest().candidate())
        self.checked = validate(self.package)
        self.provenance = {'format': 1, 'channel': 'preview', 'package': 'timekeeper',
                           'version': self.checked['version'], 'commit': self.checked['sourceCommit'],
                           'architecture': 'r1000', 'imageId': self.checked['imageId'],
                           'sha256': self.checked['sha256']}
        self.package.with_suffix('.json').write_text(json.dumps(self.provenance))
        self.record = template(self.checked)
        # Synthetic acceptance is confined to this archive fixture, never a real SPK.
        self.record['checks'] = dict.fromkeys(self.record['checks'], True)
        self.record['testedAt'] = (datetime.now(timezone.utc) - timedelta(hours=1)).strftime('%Y-%m-%dT%H:%M:%SZ')
        self.record['target'].update(dsmVersion='7.4.1-90080', containerManagerVersion='24.0.2-1706', postgresqlVersion='17.11-6')
        self.acceptance = self.root / 'acceptance.json'

    def promote(self):
        self.acceptance.write_text(json.dumps(self.record))
        return promote(self.package, self.acceptance, self.root / 'stable')

    def test_promotion_preserves_all_installed_bytes_and_binds_catalog_to_acceptance(self):
        stable = self.promote()
        checked = validate(stable)
        self.assertEqual(self.checked['contentSha256'], checked['contentSha256'])
        self.assertEqual('2.0.0-1', checked['version'])
        self.assertEqual('recorded', checked['realDsmAcceptance'])
        self.assertNotEqual(self.checked['sha256'], checked['sha256'])
        with tarfile.open(self.package) as before, tarfile.open(stable) as after:
            self.assertEqual(set(before.getnames()) | {RECORD}, set(after.getnames()))
            for member in before.getmembers():
                if member.isfile() and member.name != 'INFO':
                    self.assertEqual(before.extractfile(member).read(), after.extractfile(member.name).read(), member.name)
        catalog = build(stable, 'https://example.invalid/synology/stable/r1000/')
        self.assertEqual('Timekeeper', json.loads(catalog.read_text())['packages'][0]['dname'])
        provenance = json.loads(stable.with_suffix('.json').read_text())
        provenance['testedPreviewSha256'] = '0' * 64
        stable.with_suffix('.json').write_text(json.dumps(provenance))
        with self.assertRaisesRegex(ValueError, 'acceptance provenance'):
            build(stable, 'https://example.invalid/synology/stable/r1000/')

    def test_incomplete_or_mismatched_record_never_produces_stable_package(self):
        original = json.loads(json.dumps(self.record))
        mutations = (
            lambda r: r['checks'].update(nasRestart=False),
            lambda r: r['checks'].update(backupRestore='true'),
            lambda r: r.update(sourceCommit='b' * 40),
            lambda r: r.update(previewSha256='b' * 64),
            lambda r: r.update(imageId='sha256:' + 'b' * 64),
            lambda r: r.update(contentSha256='b' * 64),
            lambda r: r.update(testedAt=None),
            lambda r: r.update(testedAt='9999-01-01T00:00:00Z'),
            lambda r: r['target'].update(postgresqlVersion='18.6-1'),
            lambda r: r['target'].update(postgresqlVersion='17.0-1'),
            lambda r: r['target'].update(containerManagerVersion='24.0.2-1000'),
            lambda r: r['target'].update(dsmVersion='7.2.1-69057'),
            lambda r: r.update(administratorPassword='must-not-be-published'),
        )
        for mutation in mutations:
            self.record = json.loads(json.dumps(original))
            mutation(self.record)
            with self.subTest(record=self.record), self.assertRaises(ValueError):
                self.promote()
            self.assertFalse((self.root / 'stable').exists())

    def test_provenance_image_identity_is_checked_and_existing_outputs_are_never_replaced(self):
        self.provenance['imageId'] = 'sha256:' + '0' * 64
        self.package.with_suffix('.json').write_text(json.dumps(self.provenance))
        with self.assertRaisesRegex(ValueError, 'imageId'):
            preview(self.package)
        self.provenance['imageId'] = self.checked['imageId']
        self.package.with_suffix('.json').write_text(json.dumps(self.provenance))
        stable = self.promote()
        original = stable.read_bytes()
        with self.assertRaisesRegex(ValueError, 'Refusing to replace'):
            self.promote()
        self.assertEqual(original, stable.read_bytes())

    def test_stable_archive_requires_acceptance_and_detects_tampered_script(self):
        stable = self.promote()
        for remove_record in (True, False):
            contents = {}
            with tarfile.open(stable) as source:
                for member in source.getmembers():
                    if remove_record and member.name == RECORD:
                        continue
                    contents[member.name] = source.extractfile(member).read() if member.isfile() else None
            if not remove_record:
                contents['scripts/postinst'] = b'#!/bin/sh\necho changed\n'
            # Keep all file attributes identical except the intended script change.
            modified = self.root / 'modified.spk'
            with tarfile.open(stable) as source, tarfile.open(modified, 'w') as target:
                for member in source.getmembers():
                    if member.name not in contents:
                        continue
                    value = contents[member.name]
                    if value is not None:
                        member.size = len(value)
                    target.addfile(member, io.BytesIO(value) if value is not None else None)
            with self.subTest(remove_record=remove_record), self.assertRaises(ValueError):
                validate(modified)
