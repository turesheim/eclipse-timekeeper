"""Release publication must join trusted CI, exact source and real-NAS evidence."""
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import synology_release as release
import test_synology_package as fixtures
from synology_acceptance import template
from validate_synology import validate

REPO = 'example/timekeeper'
COMMIT = fixtures.COMMIT


def digest(value):
    return 'sha256:' + hashlib.sha256(value).hexdigest()


class SynologyReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.package = self.root / 'timekeeper-1.99.0-1-r1000.spk'
        self.package.write_bytes(fixtures.SynologyPackageTest().candidate())
        checked = validate(self.package)
        provenance = {'format': 1, 'channel': 'preview', 'package': 'timekeeper',
                      'version': checked['version'], 'commit': COMMIT, 'architecture': 'r1000',
                      'imageId': checked['imageId'], 'sha256': checked['sha256']}
        self.package.with_suffix('.json').write_text(json.dumps(provenance))
        self.package.with_suffix('.spk.sha256').write_text(checked['sha256'] + '  ' + self.package.name + '\n')
        self.zip = self.root / 'artifact.zip'
        self.write_zip()
        record = template(checked)
        record['checks'] = dict.fromkeys(record['checks'], True)
        record['testedAt'] = (datetime.now(timezone.utc) - timedelta(hours=1)).strftime('%Y-%m-%dT%H:%M:%SZ')
        record['target'].update(dsmVersion='7.4.1-90080', containerManagerVersion='24.0.2-1706', postgresqlVersion='17.11-6')
        self.binaries = {55: json.dumps(record).encode()}
        self.remote = {'id': 20, 'tag_name': 'v2.0.0', 'draft': False, 'prerelease': False,
                       'published_at': '2026-10-14T00:00:00Z',
                       'assets': [self.asset(55, release.ACCEPTANCE)]}
        self.run = {'id': 123, 'status': 'completed', 'conclusion': 'success', 'event': 'push',
                    'head_branch': 'main', 'head_sha': COMMIT,
                    'head_repository': {'full_name': REPO}, 'path': '.github/workflows/server-containers.yml'}
        self.matrix = {**self.run, 'id': 124, 'path': '.github/workflows/build.yml'}
        self.artifact = {'id': 50, 'name': 'synology-installer', 'expired': False,
                         'digest': digest(self.zip.read_bytes()),
                         'workflow_run': {'id': 123, 'head_sha': COMMIT, 'head_branch': 'main'}}
        self.artifacts = [self.artifact]
        self.matrix_jobs = set(release.MATRIX_JOBS)
        self.uploads = []
        self.addCleanup(patch.stopall)
        patch.object(release, 'gh', side_effect=self.gh).start()
        patch.object(release, 'download_testing_artifact', side_effect=lambda repo, identity, output: shutil.copyfile(self.zip, output)).start()
        patch.object(release.subprocess, 'run', side_effect=self.download).start()
        self.output = self.root / 'ready'

    def write_zip(self, extra=None, prefix=''):
        with zipfile.ZipFile(self.zip, 'w') as archive:
            for path in (self.package, self.package.with_suffix('.json'), self.package.with_suffix('.spk.sha256')):
                archive.write(path, prefix + path.name)
            # Unrelated reports can share basenames; only the three package files
            # are copied out and each must have a unique identity.
            archive.writestr('reports/a/result.txt', 'a')
            archive.writestr('reports/b/result.txt', 'b')
            if extra:
                archive.writestr(*extra)

    def asset(self, identity, name):
        return {'id': identity, 'name': name, 'state': 'uploaded', 'digest': digest(self.binaries[identity])}

    def gh(self, *args):
        if args[:2] == ('release', 'upload'):
            self.uploads.append(args)
            for value in args[5:]:
                path = Path(value)
                identity = max(self.binaries) + 1
                self.binaries[identity] = path.read_bytes()
                self.remote['assets'].append(self.asset(identity, path.name))
            return ''
        self.assertEqual('api', args[0])
        path = args[1].removeprefix('repos/' + REPO)
        values = {
            '': {'default_branch': 'main'},
            '/releases/tags/v2.0.0': self.remote,
            '/git/ref/tags/v2.0.0': {'object': {'type': 'commit', 'sha': COMMIT}},
            '/actions/runs/123': self.run,
            '/actions/runs/123/jobs?per_page=100': {'jobs': self.jobs({'verify', 'Synology candidate and PostgreSQL 17'})},
            '/actions/runs/124/jobs?per_page=100': {'jobs': self.jobs(self.matrix_jobs)},
            '/actions/runs/123/artifacts?per_page=100': {'artifacts': self.artifacts},
            f'/actions/workflows/build.yml/runs?head_sha={COMMIT}&event=push&status=success&per_page=100': {'workflow_runs': [self.matrix]},
        }
        self.assertIn(path, values, args)
        return json.dumps([values[path]] if '--paginate' in args else values[path])

    @staticmethod
    def jobs(names):
        return [{'name': name, 'status': 'completed', 'conclusion': 'success'} for name in names]

    def download(self, args, stdout, check):
        self.assertEqual(['-H', 'Accept: application/octet-stream'], args[-2:])
        identity = int(args[2].rsplit('/', 1)[1])
        stdout.write(self.binaries[identity])

    def prepare(self):
        return release.prepare(REPO, 'v2.0.0', 123, self.output)

    def test_full_preparation_proves_source_matrix_artifact_and_nas_before_any_upload(self):
        evidence = self.prepare()
        self.assertEqual(COMMIT, evidence['commit'])
        self.assertEqual(123, evidence['candidateRun'])
        self.assertEqual(124, evidence['matrixRun'])
        self.assertEqual(4, len(list(self.output.iterdir())))
        self.assertEqual('recorded', validate(self.output / evidence['package'])['realDsmAcceptance'])
        self.assertFalse(self.uploads)
        release.publish(REPO, 'v2.0.0', self.output, evidence)
        self.assertEqual(1, len(self.uploads))
        self.assertNotIn('--clobber', self.uploads[0])

    def test_installer_artifact_rename_preserves_old_runs_and_refuses_ambiguity(self):
        for name, prefix in (('synology-installer', ''), ('synology-preview-candidate', 'server/target/synology/')):
            self.artifact['name'] = name
            self.write_zip(prefix=prefix)
            self.artifact['digest'] = digest(self.zip.read_bytes())
            with self.subTest(name=name):
                self.assertEqual(self.artifact, release.candidate_artifact(REPO, 123, 'main', COMMIT))
                destination = self.root / name
                destination.mkdir()
                package, checked = release.unpack_candidate(self.zip, destination)
                self.assertEqual(self.package.read_bytes(), package.read_bytes())
                self.assertEqual(COMMIT, checked['sourceCommit'])
        self.artifacts.append({**self.artifact, 'id': 51, 'name': 'synology-installer'})
        with self.assertRaisesRegex(ValueError, 'Ambiguous'):
            release.candidate_artifact(REPO, 123, 'main', COMMIT)

    def test_untrusted_or_incomplete_ci_and_wrong_release_are_refused(self):
        original = json.loads(json.dumps(self.run))
        for change in ({'event': 'pull_request'}, {'head_branch': 'feature'},
                       {'head_sha': 'b' * 40}, {'head_repository': {'full_name': 'fork/timekeeper'}},
                       {'path': '.github/workflows/unrelated.yml'}, {'conclusion': 'failure'}):
            self.run = {**original, **change}
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.prepare()
            self.assertFalse(self.output.exists())
        self.run = original
        self.matrix_jobs.remove('Build (windows-latest)')
        with self.assertRaisesRegex(ValueError, 'windows'):
            self.prepare()
        self.matrix_jobs = set(release.MATRIX_JOBS)
        self.remote['draft'] = True
        with self.assertRaisesRegex(ValueError, 'published'):
            self.prepare()
        self.assertFalse(self.uploads)

    def test_missing_expired_corrupt_or_unaccepted_artifacts_are_refused(self):
        self.artifact['expired'] = True
        with self.assertRaisesRegex(ValueError, 'unexpired'):
            self.prepare()
        self.artifact['expired'] = False
        self.artifact['digest'] = 'sha256:' + '0' * 64
        with self.assertRaisesRegex(ValueError, 'artifact SHA-256'):
            self.prepare()
        self.artifact['digest'] = digest(self.zip.read_bytes())
        self.remote['assets'] = []
        with self.assertRaisesRegex(ValueError, release.ACCEPTANCE):
            self.prepare()
        record = json.loads(self.binaries[55])
        record['checks']['nasRestart'] = False
        self.binaries[55] = json.dumps(record).encode()
        self.remote['assets'] = [self.asset(55, release.ACCEPTANCE)]
        with self.assertRaisesRegex(ValueError, 'real-NAS'):
            self.prepare()
        self.assertFalse(self.output.exists())
        self.assertFalse(self.uploads)

    def test_archive_paths_and_duplicate_package_identities_are_refused(self):
        for extra in (('../escape', 'bad'), ('other/' + self.package.name, 'duplicate')):
            self.write_zip(extra)
            self.artifact['digest'] = digest(self.zip.read_bytes())
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                self.prepare()
            self.assertFalse(self.output.exists())

    def test_existing_assets_are_compared_before_partial_upload_retries(self):
        evidence = self.prepare()
        existing = self.output / evidence['package']
        self.binaries[56] = existing.read_bytes()
        self.remote['assets'].append(self.asset(56, existing.name))
        release.publish(REPO, 'v2.0.0', self.output, evidence)
        self.assertFalse(any(arg == str(existing) for arg in self.uploads[-1]))
        self.uploads.clear()
        release.publish(REPO, 'v2.0.0', self.output, evidence)
        self.assertFalse(self.uploads)  # Complete identical uploads are reused.
        self.binaries[56] = b'different existing release'
        self.remote['assets'] = [self.asset(56, existing.name) if asset['id'] == 56 else asset
                                 for asset in self.remote['assets']]
        with self.assertRaisesRegex(ValueError, 'Refusing to replace'):
            release.publish(REPO, 'v2.0.0', self.output, evidence)
        self.assertFalse(self.uploads)

    def test_missing_server_acknowledgement_and_replaced_report_are_refused(self):
        evidence = self.prepare()
        def no_upload(*args):
            if args[:2] == ('release', 'upload'):
                return ''
            return self.gh(*args)
        with patch.object(release, 'gh', side_effect=no_upload), self.assertRaisesRegex(ValueError, 'incomplete'):
            release.publish(REPO, 'v2.0.0', self.output, evidence)
        self.remote['assets'][0]['digest'] = 'sha256:' + '0' * 64
        with self.assertRaisesRegex(ValueError, 'report changed'):
            release.publish(REPO, 'v2.0.0', self.output, evidence)
        self.assertFalse(self.uploads)

    def test_asset_digest_and_changed_tag_are_refused(self):
        self.remote['assets'][0]['digest'] = 'sha256:' + '0' * 64
        with self.assertRaisesRegex(ValueError, 'release asset'):
            self.prepare()
        self.remote['assets'][0] = self.asset(55, release.ACCEPTANCE)
        evidence = self.prepare()
        self.remote['id'] = 999
        with self.assertRaisesRegex(ValueError, 'identity changed'):
            release.publish(REPO, 'v2.0.0', self.output, evidence)
        self.assertFalse(self.uploads)
