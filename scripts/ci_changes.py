#!/usr/bin/env python3
"""Select checks for PRs and main; uncertain history and sensitive changes fail open."""
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess


def documentation(path):
    return PurePosixPath(path).suffix.lower() == '.md' or path.startswith('docs/')


def agent_tooling(path):
    """Agent workflows have their own always-on validation job."""
    return path.startswith((".agents/", ".codex/", "evals/agent/")) or path in {
        "AGENTS.md", "skills-lock.json", "scripts/quality.py", "scripts/agent_hooks.py",
        "scripts/tests/test_quality.py", "scripts/tests/test_agent_hooks.py",
    }


def product_paths(paths):
    return [p for p in paths if not documentation(p) and not agent_tooling(p)]


def validation_jobs(paths):
    product = product_paths(paths)
    return {"code": bool(product), "eclipse": any(desktop_path(p) for p in product)}


def changed_paths(event, event_name, ref, head):
    if event_name not in ('push', 'pull_request') or ref.startswith('refs/tags/'):
        return None
    if event_name == 'pull_request':
        comparison = f"{event['pull_request']['base']['sha']}...{head}"
    else:
        base = event.get('before', '')
        if not base or set(base) == {'0'}:
            return None
        comparison = f'{base}..{head}'
    # Include the old path when code is deleted or moved into documentation.
    result = subprocess.run(['git', 'diff', '--no-renames', '--name-only', '-z', comparison],
                            capture_output=True, check=False)
    if result.returncode:
        return None
    paths = result.stdout.decode().rstrip('\0').split('\0')
    return paths if paths[0] else None


LAYOUT_SPECS = ['design.spec.js', 'themes.spec.js', 'today.spec.js', 'mobile-navigation.spec.js']
FEATURE_SPECS = {
    'today.js': ['today.spec.js', 'day-notes.spec.js'],
    'calendar.js': ['calendar.spec.js', 'period-swipe.spec.js', 'session-editor.spec.js'],
    'focus.js': ['focus.spec.js'],
    'notes.js': ['notes.spec.js', 'day-notes.spec.js'],
    'notes-folder-import.js': ['notes.spec.js'],
    'notes-account.js': ['notes-account.spec.js', 'notes.spec.js'],
    'calendar-subscriptions.js': ['calendar-subscriptions.spec.js'],
}


def browser_specs(paths):
    specs = set()
    for path in paths:
        name = PurePosixPath(path).name
        if path.startswith('server/web/src/') and path.endswith('.css'):
            specs.update(LAYOUT_SPECS)
            if name == 'markdown.css':
                specs.update(['markdown-editor.spec.js', 'day-notes.spec.js', 'notes.spec.js'])
        elif path.startswith('server/web/src/') and name in FEATURE_SPECS:
            specs.update(FEATURE_SPECS[name])
        elif path.startswith('server/web/tests/') and name.endswith('.spec.js'):
            specs.add(name)
        else:
            return []  # Unknown/shared web behavior: run the complete browser suite.
    root = Path(__file__).resolve().parents[1] / 'server/web/tests'
    return sorted(specs) if all((root / spec).is_file() for spec in specs) else []


def browser_matrix(specs):
    count = len(specs) if specs else len(list((Path(__file__).resolve().parents[1] / 'server/web/tests').glob('*.spec.js')))
    shards = max(1, min(4, (count + 5) // 6))
    return {'include': [{'shard': index, 'shards': shards} for index in range(1, shards + 1)]}


def sensitive_path(path):
    if path.startswith('server/web/'):
        return path.endswith(('package.json', 'package-lock.json')) or PurePosixPath(path).name in {
            'api.js', 'credentials.js', 'webauthn.js', 'app.js'}
    if path.startswith('server/app/src/'):
        return not path.endswith('.java') or bool(re.search(
            r'(Auth|Token|Credential|Passkey|Recovery|Backup|Restore|Archive|Schema|Migration|Database|Store|Repository|Sql|Vault)',
            path, re.I))
    return True


def desktop_path(path):
    # Server and CI/deployment tooling do not compile into the Eclipse client.
    # Shared code, root build inputs and unknown product paths still select all
    # desktop platforms. Release/nightly/manual/full overrides do so separately.
    return not path.startswith(('server/', 'scripts/', '.github/'))


def select_jobs(event, event_name, ref, head):
    paths = changed_paths(event, event_name, ref, head)
    product = product_paths(paths or [])
    override = any(label.get('name') == 'ci:full' for label in event.get('pull_request', {}).get('labels', []))
    sensitive = any(sensitive_path(p) for p in product)
    # Main uses the same selection as PRs. Nightly/manual/tag runs certify everything.
    certify_all = paths is None or override
    full = certify_all or bool(sensitive)
    code = full or bool(product)
    web_paths = [p for p in product if p.startswith('server/web/')]
    backend = any(not p.startswith('server/web/') for p in product)
    specs = [] if full or backend else browser_specs(web_paths)
    return {
        'code': code,
        'synology_distribution': code or ref == 'refs/heads/main',
        'full': full,
        'eclipse': certify_all or any(desktop_path(p) for p in product),
        'remote_eclipse': full,
        'server': code,  # The exact packaged server supplies browser fixtures.
        'server_tests': full or backend,
        'api': full or backend,
        'web': code,
        'web_full': code and (full or backend or not specs),
        'containers': full and event_name != 'push',
        'container_full': full or backend,
        'site': full or any(documentation(p) for p in (paths or [])),
        'browser_specs': specs,
        'browser_matrix': browser_matrix(specs),
    }


def requires_build(event, event_name, ref, head):
    return select_jobs(event, event_name, ref, head)['code']


if __name__ == '__main__':
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    jobs = select_jobs(event, os.environ['GITHUB_EVENT_NAME'], os.environ['GITHUB_REF'], os.environ['GITHUB_SHA'])
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for name, value in jobs.items():
            output.write(f'{name}={json.dumps(value, separators=(",", ":"))}\n')
    report = json.dumps(jobs, indent=2)
    print(report)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write('## Selected validation\n```json\n' + report + '\n```\n')
