#!/usr/bin/env python3
"""Assemble separate DSM channels in Pages from verified CI/release artifacts."""
import argparse
import hashlib
import html
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

import synology_release as release
from synology_acceptance import RECORD
from synology_catalog import build as catalog, entry
from validate_synology import validate


def ancestor(commit):
    return subprocess.run(['git', 'merge-base', '--is-ancestor', commit, 'HEAD'],
                          capture_output=True, check=False).returncode == 0


def copy_catalog(package, output, url, download=None):
    source = catalog(package, url, download)
    output.mkdir(parents=True, exist_ok=True)
    for name in ('catalog.json', 'timekeeper-64.png', 'timekeeper-256.png'):
        shutil.copyfile(source.parent / name, output / name)


def preview(repo, output, base):
    branch = release.api(repo, '')['default_branch']
    runs = release.pages(repo, f'actions/workflows/server-containers.yml/runs?branch={branch}&event=push&status=success&per_page=100', 'workflow_runs')
    for run in sorted(runs, key=lambda item: item['id'], reverse=True):
        commit = run.get('head_sha', '')
        if (not re.fullmatch('[a-f0-9]{40}', commit) or
            not release.trusted_run(run, repo, branch, commit, 'server-containers.yml') or not ancestor(commit)):
            continue
        if not {'verify', 'Synology candidate and PostgreSQL 17'}.issubset(release.passed_jobs(repo, run['id'])):
            continue  # Runs before Synology support, or documentation-only runs.
        try:
            artifact = release.candidate_artifact(repo, run['id'], branch, commit)
        except release.CandidateUnavailable:
            continue
        try:
            matrix = release.matrix_run(repo, branch, commit)
        except ValueError:
            continue  # The matching matrix must finish successfully too.
        with tempfile.TemporaryDirectory(prefix='timekeeper-preview-site-') as temp:
            temp = Path(temp)
            archive = temp / 'candidate.zip'
            release.download_testing_artifact(repo, artifact['id'], archive)
            if 'sha256:' + hashlib.sha256(archive.read_bytes()).hexdigest() != artifact['digest']:
                raise ValueError('Preview CI artifact SHA-256 mismatch')
            source = temp / 'candidate'
            source.mkdir()
            package, checked = release.unpack_candidate(archive, source)
            if checked['sourceCommit'] != commit:
                raise ValueError('Preview SPK source differs from the verified main build')
            copy_catalog(package, output, base + 'preview/r1000/')
            for path in (package, package.with_suffix('.spk.sha256'), package.with_suffix('.json')):
                shutil.copyfile(path, output / path.name)
        return {'available': True, 'commit': commit, 'version': checked['version'],
                'run': run['id'], 'matrixRun': matrix, 'artifact': artifact['id'], 'sha256': checked['sha256']}
    return {'available': False, 'reason': 'No complete, unexpired main candidate and matching matrix are available.'}


def stable(repo, output, base):
    releases = json.loads(release.gh('api', f'repos/{repo}/releases?per_page=100', '--paginate', '--slurp'))
    found = [item for page in releases for item in page if item.get('tag_name') in ('v2.0.0', '2.0.0')
             and not item.get('draft') and not item.get('prerelease') and item.get('published_at')]
    if len(found) > 1:
        raise ValueError('Use one published 2.0.0 release identity for the stable DSM channel')
    if not found:
        return {'available': False, 'reason': 'No published NAS-accepted stable distribution is available.'}
    published, commit = release.release_identity(repo, found[0]['tag_name'])
    assets = {asset['name']: asset for asset in published['assets']}
    if len(assets) != len(published['assets']):
        raise ValueError('Ambiguous stable release asset names')
    if release.EVIDENCE not in assets or release.ACCEPTANCE not in assets:
        return {'available': False, 'reason': 'The release has no complete Synology acceptance/publication evidence.'}
    with tempfile.TemporaryDirectory(prefix='timekeeper-stable-site-') as temp:
        temp = Path(temp)
        evidence_path = temp / release.EVIDENCE
        release.download_asset(repo, assets[release.EVIDENCE], evidence_path)
        evidence = json.loads(evidence_path.read_text())
        name = evidence.get('package', '')
        if not re.fullmatch(r'timekeeper-2\.0\.0-[1-9][0-9]*-r1000\.spk', name):
            raise ValueError('Invalid stable package evidence')
        required = (name, name + '.sha256', name[:-4] + '.json', release.ACCEPTANCE)
        if not all(item in assets for item in required):
            return {'available': False, 'reason': 'The stable distribution upload is incomplete.'}
        for item in required:
            release.download_asset(repo, assets[item], temp / item)
        package = temp / name
        checked = validate(package)
        if (evidence.get('format') != 1 or evidence.get('tag') != published['tag_name'] or
            evidence.get('releaseId') != published['id'] or evidence.get('commit') != commit or
            checked['sourceCommit'] != commit or evidence.get('packageSha256') != checked['sha256'] or
            evidence.get('acceptanceSha256') != hashlib.sha256((temp / release.ACCEPTANCE).read_bytes()).hexdigest()):
            raise ValueError('Stable release source/checksums do not match publication evidence')
        if package.with_suffix('.spk.sha256').read_text().split() != [checked['sha256'], name]:
            raise ValueError('Stable SPK checksum differs from its sidecar')
        with tarfile.open(package) as archive:
            if json.load(archive.extractfile(RECORD)) != json.loads((temp / release.ACCEPTANCE).read_text()):
                raise ValueError('Published and embedded NAS acceptance reports differ')
        download = f"https://github.com/{repo}/releases/download/{published['tag_name']}/{name}"
        copy_catalog(package, output, base + 'stable/r1000/', download)
        for item in (name + '.sha256', name[:-4] + '.json', release.ACCEPTANCE, release.EVIDENCE):
            shutil.copyfile(temp / item, output / item)
    return {'available': True, 'commit': commit, 'version': checked['version'], 'tag': published['tag_name'], 'sha256': checked['sha256']}


def site(repo, output, base, include_preview=False):
    # entry() performs the channel URL validation, even for empty catalogs.
    entry('1.99.0-1', base + 'preview/r1000/', 0, '0' * 32)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('Use a GitHub repository name')
    root = Path(output) / 'synology'
    if root.exists():
        shutil.rmtree(root)  # Reassemble channels; never preserve a stale candidate.
    channels = {}
    for channel in ('stable', 'preview'):
        destination = root / channel / 'r1000'
        destination.mkdir(parents=True)
        (destination / 'catalog.json').write_text('{"packages": []}\n')
        channels[channel] = (stable(repo, destination, base) if channel == 'stable' else
                             preview(repo, destination, base) if include_preview else
                             {'available': False, 'reason': 'Preview publication has not been enabled.'})
    (root / 'channels.json').write_text(json.dumps(channels, indent=2) + '\n')
    text = '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Timekeeper for Synology</title><h1>Timekeeper for Synology</h1><p>DS923+ / r1000. Install Container Manager and SynoCommunity PostgreSQL 17 first.</p>'
    for channel, state in channels.items():
        url = base + channel + '/r1000/catalog.json'
        text += '<h2>' + channel.title() + '</h2><p>' + html.escape(state.get('version', state.get('reason', ''))) + '</p>'
        if state['available']:
            text += '<p>Package Center source: <a href="' + html.escape(url, quote=True) + '">' + html.escape(url) + '</a></p>'
    (root / 'index.html').write_text(text + '</html>\n')
    return channels


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--include-preview', action='store_true')
    args = parser.parse_args()
    print(json.dumps(site(args.repo, args.output, args.base_url, args.include_preview)))
