#!/usr/bin/env python3
"""Publish one rolling Synology prerelease from verified main CI artifacts."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tempfile

import synology_release as release
from github_retention import clean_artifacts, pages

TAG = 'synology-latest'
MARKER = re.compile(r'<!-- timekeeper-synology-latest: (\{[^\n]+\}) -->')
EVIDENCE = 'timekeeper-synology-latest.json'


def current_release(repo):
    found = [item for item in pages(repo, 'releases?per_page=100') if item['tag_name'] == TAG]
    if len(found) > 1:
        raise ValueError('Ambiguous rolling release')
    if not found:
        return None, None
    item = found[0]
    markers = MARKER.findall(item.get('body') or '')
    if not item['prerelease'] or len(markers) != 1:
        raise ValueError('Refusing to replace a release not managed by Synology latest')
    state = json.loads(markers[0])
    if type(state.get('revision')) is not int or state['revision'] < 1:
        raise ValueError('Invalid existing rolling release state')
    return item, state


def prepare(repo, commit, destination):
    if not re.fullmatch('[a-f0-9]{40}', commit):
        raise ValueError('Use a complete source commit')
    branch = release.api(repo, '')['default_branch']
    if branch != 'main':
        raise ValueError('Synology latest is restricted to main')
    builds = release.pages(repo, f'actions/workflows/build.yml/runs?head_sha={commit}&event=push&status=success&per_page=100', 'workflow_runs')
    builds = [item for item in builds if release.trusted_run(item, repo, branch, commit, 'build.yml')]
    runs = release.pages(repo, f'actions/workflows/server-containers.yml/runs?head_sha={commit}&event=push&status=success&per_page=100', 'workflow_runs')
    runs = [item for item in runs if release.trusted_run(item, repo, branch, commit, 'server-containers.yml')]
    if not builds or not runs:
        return None  # The other concurrently started workflow will retry on completion.
    build = max(builds, key=lambda item: item['run_number'])
    release.require_jobs(repo, build['id'], {'PR validation', 'Packaging and channel tests'})
    run = max(runs, key=lambda item: item['id'])
    artifact = release.candidate_artifact(repo, run['id'], branch, commit, allow_web_preview=True)
    with tempfile.TemporaryDirectory(prefix='dagsverk-latest-') as temporary:
        archive = Path(temporary)/'candidate.zip'
        release.download_testing_artifact(repo, artifact['id'], archive)
        if 'sha256:'+hashlib.sha256(archive.read_bytes()).hexdigest() != artifact['digest']:
            raise ValueError('CI artifact digest mismatch')
        package, checked = release.unpack_candidate(archive, destination)
    revision = int(checked['version'].rsplit('-', 1)[1])
    if checked['sourceCommit'] != commit or revision != build['run_number']:
        raise ValueError('SPK source/revision differs from its successful main Build')
    state = {'format': 1, 'commit': commit, 'revision': revision, 'buildRun': build['id'],
             'containerRun': run['id'], 'artifact': artifact['id'], 'package': package.name,
             'sha256': checked['sha256'], 'validation': 'full' if release.FULL_CANDIDATE_JOBS.issubset(release.passed_jobs(repo, run['id'])) else 'web-smoke'}
    (destination/EVIDENCE).write_text(json.dumps(state, indent=2)+'\n')
    return state


def publish(repo, directory, state):
    existing, previous = current_release(repo)
    if previous and previous['revision'] > state['revision']:
        return {'published': False, 'reason': 'An equal or newer main build is already available.'}
    if previous and previous['revision'] == state['revision'] and (previous.get('commit') != state['commit'] or previous.get('sha256') != state.get('sha256')):
        raise ValueError('The same rolling revision has conflicting source or bytes')
    files = sorted(directory.iterdir())
    expected = {state['package'], state['package']+'.sha256', state['package'][:-4]+'.json', EVIDENCE}
    if {item.name for item in files} != expected:
        raise ValueError('Unexpected rolling distribution contents')
    notes = directory.parent/'latest-notes.md'
    notes.write_text('Latest successful main build of Dagsverk for Synology.\n\n'
                     f"Commit: `{state['commit']}`. Build revision: `{state['revision']}`. Validation: `{state['validation']}`.\n\n"
                     'Install the SPK through DSM Package Center. Real NAS acceptance is required before stable promotion. '
                     'This prerelease is replaced by the next successful main build; versioned releases are retained.\n\n'
                     '<!-- timekeeper-synology-latest: '+json.dumps(state, separators=(',', ':'))+' -->\n')
    if existing is None:
        release.gh('release', 'create', TAG, '--repo', repo, '--target', state['commit'], '--title', 'Dagsverk for Synology — latest build', '--prerelease', '--draft', '--notes-file', str(notes))
        existing, _ = current_release(repo)
        if existing is None:
            raise ValueError('The draft rolling release was not created')
    release.gh('release', 'upload', TAG, '--repo', repo, *(str(item) for item in files), '--clobber')
    # GitHub's by-tag endpoint omits unpublished drafts and their pending tags.
    assets = release.api(repo, f"releases/{existing['id']}")['assets']
    for item in files:
        found = [asset for asset in assets if asset['name'] == item.name]
        if len(found) != 1 or found[0].get('digest') != 'sha256:'+hashlib.sha256(item.read_bytes()).hexdigest():
            raise ValueError('Uploaded rolling distribution is incomplete')
    refs = release.api(repo, 'git/matching-refs/tags/'+TAG)
    if any(item['ref'] == 'refs/tags/'+TAG for item in refs):
        release.gh('api', f'repos/{repo}/git/refs/tags/{TAG}', '--method', 'PATCH', '-f', 'sha='+state['commit'], '-F', 'force=true')
    else:
        release.gh('api', f'repos/{repo}/git/refs', '--method', 'POST', '-f', 'ref=refs/tags/'+TAG, '-f', 'sha='+state['commit'])
    release.gh('release', 'edit', TAG, '--repo', repo, '--draft=false', '--prerelease', '--latest=false', '--notes-file', str(notes))
    # Only this explicitly managed, unversioned prerelease may lose old assets.
    for asset in assets:
        if asset['name'] not in expected:
            release.gh('api', f"repos/{repo}/releases/assets/{asset['id']}", '--method', 'DELETE')
    return {'published': True, 'url': f'https://github.com/{repo}/releases/tag/{TAG}',
            'artifactRetention': clean_artifacts(repo, state['artifact'], apply=True)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo):
        parser.error('Use owner/repository')
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.iterdir()):
        parser.error('Use an empty output directory')
    state = prepare(args.repo, args.commit, args.output)
    result = ({'published': False, 'reason': 'Waiting for both successful main workflows.'} if state is None
              else publish(args.repo, args.output, state) if args.publish else state)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
