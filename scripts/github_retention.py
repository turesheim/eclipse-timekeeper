#!/usr/bin/env python3
"""Bound temporary Synology artifacts and GitHub Pages deployment history."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import subprocess

from p2_release import gh

SPK_ARTIFACTS = {'synology-installer', 'synology-web-preview',
                 'synology-preview-candidate', 'synology-migration-test-packages'}
BUSY = {'pending', 'queued', 'in_progress', 'waiting'}
VERSION = re.compile(r'v?\d+(?:\.\d+){1,2}[A-Za-z0-9.+-]*\Z')


def api(repo, path, method='GET', **fields):
    args = ['api', f'repos/{repo}/{path}', '--method', method]
    for key, value in fields.items():
        args += ['-f', f'{key}={value}']
    output = gh(*args)
    return json.loads(output) if output.strip() else None


def pages(repo, path, field=None):
    result = json.loads(gh('api', f'repos/{repo}/{path}', '--paginate', '--slurp'))
    return [item for page in result for item in (page[field] if field else page)]


def artifact_plan(artifacts, retained):
    """Only older known SPK artifacts; preserve tags, other outputs and newer work."""
    return [item for item in artifacts if item['name'] in SPK_ARTIFACTS
            and item['id'] != retained['id'] and item['created_at'] < retained['created_at']
            and not VERSION.fullmatch(item.get('workflow_run', {}).get('head_branch', ''))]


def deployment_plan(deployments, states, keep=3):
    if keep < 1:
        raise ValueError('Keep at least one deployment')
    ordered = sorted((item for item in deployments if item['environment'] == 'github-pages'),
                     key=lambda item: (item['created_at'], item['id']), reverse=True)
    protected = {item['id'] for item in ordered[:keep]}
    active = next((item['id'] for item in ordered if states.get(item['id']) == 'success'), None)
    if active is not None:
        protected.add(active)
    return [item for item in ordered if item['id'] not in protected
            and states.get(item['id']) is not None and states[item['id']] not in BUSY]


def clean_pages(repo, keep=3, apply=False):
    deployments = pages(repo, 'deployments?environment=github-pages&per_page=100')

    def status(item):
        values = api(repo, f"deployments/{item['id']}/statuses?per_page=1")
        return item['id'], values[0]['state'] if values else None

    with ThreadPoolExecutor(max_workers=8) as pool:
        states = dict(pool.map(status, deployments))
    selected = deployment_plan(deployments, states, keep)
    report = {'environment': 'github-pages', 'keep': keep, 'total': len(deployments),
              'selected': [item['id'] for item in selected], 'deleted': []}
    if apply:
        # Recompute the protected set immediately before mutation. Another Pages
        # deployment may have started while the inventory was being collected.
        current = pages(repo, 'deployments?environment=github-pages&per_page=100')
        for item in current:
            if item['id'] not in states:
                identity, state = status(item)
                states[identity] = state
        allowed = {item['id'] for item in deployment_plan(current, states, keep)}
        for item in selected:
            identity = item['id']
            if identity not in allowed:
                continue
            _, state = status(item)
            if state is None or state in BUSY or state != states[identity]:
                continue
            if state != 'inactive':
                api(repo, f'deployments/{identity}/statuses', 'POST', state='inactive',
                    description='Old Pages deployment history removed by retention')
            api(repo, f'deployments/{identity}', 'DELETE')
            report['deleted'].append(identity)
    return report


def clean_artifacts(repo, retained_id, apply=False):
    artifacts = pages(repo, 'actions/artifacts?per_page=100', 'artifacts')
    retained = next((item for item in artifacts if item['id'] == retained_id and item['name'] in SPK_ARTIFACTS), None)
    if retained is None or retained.get('expired'):
        raise ValueError('The current Synology artifact must exist before old artifacts are removed')
    selected = artifact_plan(artifacts, retained)
    report = {'retained': retained_id, 'selected': [item['id'] for item in selected], 'deleted': []}
    if apply:
        for item in selected:
            api(repo, f"actions/artifacts/{item['id']}", 'DELETE')
            report['deleted'].append(item['id'])
    return report


def registry_plan(versions, protected=()):
    return [item for item in versions if item.get('name') not in protected and not any(VERSION.fullmatch(tag)
            for tag in item.get('metadata', {}).get('container', {}).get('tags', []))]


def versioned_manifests(versions, inspect):
    """Versioned multiarch indexes also retain their untagged platform manifests."""
    pending = [item['name'] for item in versions if item not in registry_plan([item])]
    protected = set()
    while pending:
        digest = pending.pop()
        if digest in protected:
            continue
        if not re.fullmatch('sha256:[a-f0-9]{64}', digest):
            raise ValueError('Invalid versioned container digest')
        protected.add(digest)
        manifest = inspect(digest)
        pending += [item['digest'] for item in manifest.get('manifests', [])]
    return protected


def clean_registry(repo, apply=False):
    # The retired registry name is fixed. Never sweep an account's other packages.
    path = f"users/{repo.split('/')[0]}/packages/container/eclipse-timekeeper-server"
    package = json.loads(gh('api', path))
    if package.get('repository', {}).get('full_name') != repo:
        raise ValueError('The retired container package is not linked to this repository')
    versions = [item for page in json.loads(gh('api', path+'/versions?per_page=100', '--paginate', '--slurp')) for item in page]
    def inspect(digest):
        return json.loads(subprocess.check_output(['docker', 'buildx', 'imagetools', 'inspect',
                          f"ghcr.io/{repo.split('/')[0]}/eclipse-timekeeper-server@{digest}", '--raw'], text=True))
    protected = versioned_manifests(versions, inspect)
    selected = registry_plan(versions, protected)
    report = {'package': 'eclipse-timekeeper-server', 'total': len(versions),
              'selected': [item['id'] for item in selected],
              'retained': [item['id'] for item in versions if item not in selected], 'deleted': []}
    if apply:
        for item in selected:
            current = json.loads(gh('api', path+f"/versions/{item['id']}"))
            if not registry_plan([current], protected):
                continue  # A version tag added after inventory always wins.
            gh('api', path+f"/versions/{item['id']}", '--method', 'DELETE')
            report['deleted'].append(item['id'])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=('pages', 'artifacts', 'registry'))
    parser.add_argument('--repo', required=True)
    parser.add_argument('--keep', type=int, default=3)
    parser.add_argument('--retained-artifact', type=int)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo):
        parser.error('Use owner/repository')
    report = (clean_pages(args.repo, args.keep, args.apply) if args.kind == 'pages'
              else clean_registry(args.repo, args.apply) if args.kind == 'registry'
              else clean_artifacts(args.repo, args.retained_artifact, args.apply))
    text = json.dumps(report, indent=2)+'\n'
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text)
    print(text)


if __name__ == '__main__':
    main()
