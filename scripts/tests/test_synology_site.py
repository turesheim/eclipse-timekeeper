"""Pages must expose only accepted releases or explicitly enabled verified previews."""
import json
import unittest
from unittest.mock import patch

import test_synology_release as fixtures
import synology_site as site

BASE = 'https://example.invalid/timekeeper/synology/'


class SynologySiteTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.SynologyReleaseTest(methodName='runTest')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root / 'website'
        self.root.mkdir()
        self.published = []
        def gh(*args):
            path = args[1] if args[0] == 'api' else ''
            if path.endswith('/releases?per_page=100'):
                return json.dumps([self.published])
            if '/actions/workflows/server-containers.yml/runs?' in path:
                return json.dumps([{'workflow_runs': [self.fixture.run]}])
            return self.fixture.gh(*args)
        patch.object(site.release, 'gh', side_effect=gh).start()
        patch.object(site, 'ancestor', return_value=True).start()

    def build(self, preview=False):
        return site.site(fixtures.REPO, self.root, BASE, preview)

    def publish_fixture(self):
        evidence = self.fixture.prepare()
        site.release.publish(fixtures.REPO, 'v2.0.0', self.fixture.output, evidence)
        self.published = [self.fixture.remote]

    def test_no_release_and_disabled_preview_produce_honest_empty_channels(self):
        stale = self.root / 'synology/preview/r1000/stale.spk'
        stale.parent.mkdir(parents=True)
        stale.write_bytes(b'stale')
        result = self.build()
        self.assertFalse(result['stable']['available'])
        self.assertFalse(result['preview']['available'])
        self.assertFalse(stale.exists())
        for channel in ('stable', 'preview'):
            self.assertEqual([], json.loads((self.root / f'synology/{channel}/r1000/catalog.json').read_text())['packages'])

    def test_enabled_preview_requires_same_source_matrix_and_contains_self_contained_spk(self):
        result = self.build(preview=True)
        self.assertTrue(result['preview']['available'])
        entry = json.loads((self.root / 'synology/preview/r1000/catalog.json').read_text())['packages'][0]
        package = self.root / 'synology/preview/r1000' / self.fixture.package.name
        self.assertEqual(self.fixture.package.read_bytes(), package.read_bytes())
        self.assertEqual(BASE + 'preview/r1000/' + package.name, entry['link'])
        self.assertIn('preview', entry['dname'])
        self.assertFalse(entry['qinst'])

    def test_pr_runs_expired_candidates_nonancestor_sources_and_incomplete_matrix_are_omitted(self):
        self.fixture.run['event'] = 'pull_request'
        self.assertFalse(self.build(True)['preview']['available'])
        self.fixture.run['event'] = 'push'
        self.fixture.artifact['expired'] = True
        self.assertFalse(self.build(True)['preview']['available'])
        self.fixture.artifact['expired'] = False
        with patch.object(site, 'ancestor', return_value=False):
            self.assertFalse(self.build(True)['preview']['available'])
        self.fixture.matrix_jobs.remove('Web')
        self.assertFalse(self.build(True)['preview']['available'])

    def test_corrupt_preview_fails_instead_of_publishing_or_falling_back(self):
        self.fixture.artifact['digest'] = 'sha256:' + '0' * 64
        with self.assertRaisesRegex(ValueError, 'artifact SHA-256'):
            self.build(True)

    def test_stable_catalog_requires_complete_report_and_links_to_immutable_release_asset(self):
        self.publish_fixture()
        result = self.build()
        self.assertTrue(result['stable']['available'])
        self.assertFalse(result['preview']['available'])
        folder = self.root / 'synology/stable/r1000'
        entry = json.loads((folder / 'catalog.json').read_text())['packages'][0]
        self.assertEqual('https://github.com/' + fixtures.REPO + '/releases/download/v2.0.0/timekeeper-2.0.0-1-r1000.spk', entry['link'])
        self.assertFalse((folder / 'timekeeper-2.0.0-1-r1000.spk').exists())
        self.assertTrue((folder / 'timekeeper-dsm-acceptance.json').exists())

    def test_partial_stable_upload_is_unavailable_and_changed_report_is_refused(self):
        self.publish_fixture()
        assets = self.fixture.remote['assets']
        self.fixture.remote['assets'] = [asset for asset in assets if not asset['name'].endswith('.spk')]
        self.assertFalse(self.build()['stable']['available'])
        self.fixture.remote['assets'] = assets
        original = self.fixture.binaries[55]
        report = json.loads(original)
        report['checks']['nasRestart'] = False
        self.fixture.binaries[55] = json.dumps(report).encode()
        self.fixture.remote['assets'][0] = self.fixture.asset(55, site.release.ACCEPTANCE)
        with self.assertRaisesRegex(ValueError, 'publication evidence'):
            self.build()
