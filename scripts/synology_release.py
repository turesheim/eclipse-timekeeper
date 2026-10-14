#!/usr/bin/env python3
"""Attach a NAS-tested, unchanged Synology candidate to its published release."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server/scripts'))
from promote_synology import preview, promote
from p2_release import download_testing_artifact, gh

ACCEPTANCE = 'timekeeper-dsm-acceptance.json'
EVIDENCE = 'timekeeper-dsm-release.json'
MATRIX_JOBS = {'API-contract', 'Server', 'Web', 'Build (ubuntu-latest)',
               'Build (macos-latest)', 'Build (windows-latest)',
               'Installed client tests', 'Packaging and channel tests',
               'Build website and update site'}


class CandidateUnavailable(ValueError):
    """A successful run's candidate is absent or has expired."""


def api(repo, path):
    return json.loads(gh('api', f'repos/{repo}' + ('/' + path if path else '')))


def pages(repo, path, field):
    return [item for page in json.loads(gh('api', f'repos/{repo}/{path}', '--paginate', '--slurp'))
            for item in page[field]]


def release_identity(repo, tag):
    if tag not in ('v2.0.0', '2.0.0'):
        raise ValueError('This package format supports the published 2.0.0 release')
    release = api(repo, 'releases/tags/' + tag)
    if release.get('tag_name') != tag or release.get('draft') or release.get('prerelease') or not release.get('published_at'):
        raise ValueError('Use a published, non-prerelease GitHub release')
    obj = api(repo, 'git/ref/tags/' + tag)['object']
    for _ in range(5):
        if not re.fullmatch('[a-f0-9]{40}', obj.get('sha', '')):
            raise ValueError('Invalid release source commit')
        if obj['type'] == 'commit':
            return release, obj['sha']
        if obj['type'] != 'tag':
            break
        obj = api(repo, 'git/tags/' + obj['sha'])['object']
    raise ValueError('Release tag must resolve to a source commit')


def trusted_run(run, repo, branch, commit, workflow):
    return (run.get('status') == 'completed' and run.get('conclusion') == 'success'
            and run.get('event') == 'push' and run.get('head_branch') == branch
            and run.get('head_sha') == commit
            and run.get('head_repository', {}).get('full_name') == repo
            and run.get('path') == '.github/workflows/' + workflow)


def passed_jobs(repo, run_id):
    jobs = pages(repo, f'actions/runs/{run_id}/jobs?per_page=100', 'jobs')
    return {job['name'] for job in jobs if job.get('status') == 'completed' and job.get('conclusion') == 'success'}


def require_jobs(repo, run_id, required):
    passed = passed_jobs(repo, run_id)
    if not required.issubset(passed):
        raise ValueError('Required jobs did not pass: ' + ', '.join(sorted(required - passed)))


def candidate_artifact(repo, run_id, branch, commit):
    run = api(repo, f'actions/runs/{run_id}')
    if run.get('id') != run_id or not trusted_run(run, repo, branch, commit, 'server-containers.yml'):
        raise ValueError('Candidate must come from a successful main Server containers push at the release commit')
    require_jobs(repo, run_id, {'verify', 'Synology candidate and PostgreSQL 17'})
    found = [artifact for artifact in pages(repo, f'actions/runs/{run_id}/artifacts?per_page=100', 'artifacts')
             if artifact['name'] == 'synology-preview-candidate' and not artifact['expired']]
    if not found:
        raise CandidateUnavailable('A unique, unexpired Synology candidate artifact is required')
    if len(found) != 1:
        raise ValueError('Ambiguous Synology candidate artifacts')
    artifact = found[0]
    source = artifact.get('workflow_run', {})
    if source.get('id') != run_id or source.get('head_sha') != commit or source.get('head_branch') != branch:
        raise ValueError('Candidate artifact source differs from its successful workflow')
    if not re.fullmatch('sha256:[a-f0-9]{64}', artifact.get('digest', '')):
        raise ValueError('Candidate artifact has no SHA-256 digest')
    return artifact


def matrix_run(repo, branch, commit):
    runs = pages(repo, f'actions/workflows/build.yml/runs?head_sha={commit}&event=push&status=success&per_page=100', 'workflow_runs')
    for run in sorted(runs, key=lambda item: item['id']):
        if trusted_run(run, repo, branch, commit, 'build.yml'):
            require_jobs(repo, run['id'], MATRIX_JOBS)
            return run['id']
    raise ValueError('A successful main build matrix at the release commit is required')


def unpack_candidate(archive, destination):
    """Read only the three verified package files; never extract artifact paths."""
    with zipfile.ZipFile(archive) as zipped:
        files = {}
        for member in zipped.infolist():
            path = PurePosixPath(member.filename)
            if path.is_absolute() or '..' in path.parts or '\\' in member.filename or (member.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Unsafe candidate artifact member')
            if member.is_dir():
                continue
            files.setdefault(path.name, []).append(member)
        names = [name for name in files if re.fullmatch(r'timekeeper-1\.99\.0-[1-9][0-9]*-r1000\.spk', name)]
        if len(names) != 1:
            raise ValueError('Candidate artifact needs exactly one versioned preview SPK')
        name = names[0]
        for required in (name, name + '.sha256', name[:-4] + '.json'):
            if required not in files or len(files[required]) != 1:
                raise ValueError('Candidate artifact has missing/ambiguous package provenance/checksum')
            with zipped.open(files[required][0]) as source, (destination / required).open('xb') as target:
                shutil.copyfileobj(source, target)
    package = destination / name
    checked, _ = preview(package)
    checksum = package.with_suffix('.spk.sha256').read_text().split()
    if checksum != [checked['sha256'], name]:
        raise ValueError('Candidate package checksum differs from its sidecar')
    return package, checked


def download_asset(repo, asset, path):
    if asset.get('state') != 'uploaded' or type(asset.get('id')) is not int:
        raise ValueError('Release asset is not completely uploaded')
    with path.open('xb') as output:
        subprocess.run(['gh', 'api', f"repos/{repo}/releases/assets/{asset['id']}",
                        '-H', 'Accept: application/octet-stream'], stdout=output, check=True)
    digest = 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()
    if asset.get('digest') != digest:
        raise ValueError('Downloaded release asset differs from its SHA-256 digest')


def prepare(repo, tag, run_id, output):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo) or run_id <= 0:
        raise ValueError('Use a repository name and positive CI run ID')
    output = Path(output)
    if output.exists() and any(output.iterdir()):
        raise ValueError('Use an empty output directory')
    release, commit = release_identity(repo, tag)
    branch = api(repo, '')['default_branch']
    artifact = candidate_artifact(repo, run_id, branch, commit)
    matrix = matrix_run(repo, branch, commit)
    records = [asset for asset in release['assets'] if asset['name'] == ACCEPTANCE]
    if len(records) != 1:
        raise ValueError('Attach the completed ' + ACCEPTANCE + ' report to the published release first')
    with tempfile.TemporaryDirectory(prefix='timekeeper-dsm-release-') as temp:
        temp = Path(temp)
        archive = temp / 'candidate.zip'
        download_testing_artifact(repo, artifact['id'], archive)
        if 'sha256:' + hashlib.sha256(archive.read_bytes()).hexdigest() != artifact['digest']:
            raise ValueError('Downloaded CI artifact SHA-256 mismatch')
        source = temp / 'candidate'
        source.mkdir()
        package, checked = unpack_candidate(archive, source)
        if checked['sourceCommit'] != commit:
            raise ValueError('SPK source does not match the release tag')
        acceptance = temp / ACCEPTANCE
        download_asset(repo, records[0], acceptance)
        stable = promote(package, acceptance, temp / 'stable')
        evidence = {'format': 1, 'tag': tag, 'releaseId': release['id'], 'commit': commit,
                    'candidateRun': run_id, 'matrixRun': matrix,
                    'artifactId': artifact['id'], 'artifactSha256': artifact['digest'].removeprefix('sha256:'),
                    'acceptanceSha256': hashlib.sha256(acceptance.read_bytes()).hexdigest(),
                    'package': stable.name, 'packageSha256': hashlib.sha256(stable.read_bytes()).hexdigest()}
        (stable.parent / EVIDENCE).write_text(json.dumps(evidence, indent=2) + '\n')
        # Nothing is exposed as ready until all downloaded evidence has passed.
        shutil.copytree(stable.parent, output, dirs_exist_ok=True)
    return evidence


def publish(repo, tag, output, evidence):
    release, commit = release_identity(repo, tag)
    if release['id'] != evidence['releaseId'] or commit != evidence['commit']:
        raise ValueError('Release identity changed after preparation')
    records = [asset for asset in release['assets'] if asset['name'] == ACCEPTANCE]
    if (len(records) != 1 or records[0].get('state') != 'uploaded' or
        records[0].get('digest') != 'sha256:' + evidence['acceptanceSha256']):
        raise ValueError('NAS acceptance report changed after preparation')
    expected = sorted(Path(output).iterdir())
    name = evidence['package']
    if {path.name for path in expected} != {name, name + '.sha256', name[:-4] + '.json', EVIDENCE}:
        raise ValueError('Prepared release contains unexpected or missing files')
    missing = []
    # Compare every existing asset before uploading anything. A retry may complete
    # a partial upload but must never clobber different bytes under the same name.
    with tempfile.TemporaryDirectory(prefix='timekeeper-dsm-existing-') as temp:
        for path in expected:
            found = [asset for asset in release['assets'] if asset['name'] == path.name]
            if len(found) > 1:
                raise ValueError('Ambiguous existing release asset: ' + path.name)
            if not found:
                missing.append(path)
                continue
            downloaded = Path(temp) / path.name
            download_asset(repo, found[0], downloaded)
            if hashlib.sha256(downloaded.read_bytes()).digest() != hashlib.sha256(path.read_bytes()).digest():
                raise ValueError('Refusing to replace different release asset: ' + path.name)
    if missing:
        gh('release', 'upload', tag, '--repo', repo, *(str(path) for path in missing))
    confirmed, commit = release_identity(repo, tag)
    if confirmed['id'] != evidence['releaseId'] or commit != evidence['commit']:
        raise ValueError('Release identity changed during publication')
    for path in expected:
        found = [asset for asset in confirmed['assets'] if asset['name'] == path.name]
        if (len(found) != 1 or found[0].get('state') != 'uploaded' or
            found[0].get('digest') != 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()):
            raise ValueError('Published release is incomplete or has a different checksum: ' + path.name)
    return [path.name for path in expected]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--candidate-run', required=True, type=int)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--publish', action='store_true', help='Upload verified assets without replacing existing files')
    args = parser.parse_args()
    evidence = prepare(args.repo, args.tag, args.candidate_run, args.output)
    if args.publish:
        publish(args.repo, args.tag, args.output, evidence)
    print(json.dumps(evidence))


if __name__ == '__main__':
    main()
