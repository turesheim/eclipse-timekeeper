#!/usr/bin/env python3
"""Package the verified JVM distribution without test plugins or credential files."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

from documentation import render_document

ROOT = Path(__file__).resolve().parents[2]


def verify_build_identity(app, commit):
    """The embedded runtime identity must agree with the distribution manifest."""
    identities = []
    for jar in (app / 'app').glob('*.jar'):
        with zipfile.ZipFile(jar) as archive:
            if 'timekeeper-build.properties' in archive.namelist():
                properties = dict(line.split('=', 1) for line in archive.read('timekeeper-build.properties').decode().splitlines()
                                  if '=' in line and not line.startswith('#'))
                identities.append(properties)
    if len(identities) != 1 or identities[0].get('commit') != commit or identities[0].get('dirty') != 'false':
        raise ValueError('Packaged server build identity must match the exact clean source commit; rebuild before packaging')


def package(output, tag, commit):
    if tag is not None and not re.fullmatch(r'v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', tag):
        raise ValueError('Expected a semantic release/candidate tag')
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Expected the full source commit ID')
    if tag is not None:
        tagged = subprocess.check_output(['git', 'rev-parse', '--verify', 'refs/tags/' + tag + '^{commit}'], cwd=ROOT, text=True).strip()
        if tagged != commit:
            raise ValueError('Release tag and source commit do not match')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if head != commit or subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip():
        raise ValueError('Distribution provenance requires the exact clean committed source')
    app = ROOT / 'server/app/target/quarkus-app'
    for path in [app / 'quarkus-run.jar', app / 'app', app / 'lib', app / 'quarkus']:
        if not path.exists():
            raise ValueError('Build the complete Quarkus JVM distribution before packaging')
    verify_build_identity(app, commit)
    output.mkdir(parents=True, exist_ok=True)
    archive = output / 'timekeeper-server.zip'
    manifest = {'format': 1, 'kind': 'candidate' if tag is None else 'release', 'tag': tag, 'commit': commit, 'java': 21,
                'quarkus': '3.40.1', 'h2': '2.4.240', 'apiMajor': 1, 'serverSchema': 6}
    files = [(p, 'timekeeper-server/' + p.relative_to(app).as_posix()) for p in sorted(app.rglob('*')) if p.is_file()]
    guides = {'docs/operations/SERVER.md': 'README.md',
              'docs/architecture/API-V1.md': 'API-POLICY.md',
              'docs/operations/DEPLOYMENT.md': 'DEPLOYMENT.md',
              'docs/operations/LOCAL-DOCKER.md': 'LOCAL-DOCKER.md',
              'docs/operations/TAILSCALE.md': 'TAILSCALE.md',
              'docs/operations/ACCOUNTS.md': 'ACCOUNTS.md',
              'docs/operations/ACCOUNT-DATA.md': 'ACCOUNT-DATA.md',
              'docs/operations/TOGGL-IMPORT.md': 'TOGGL-IMPORT.md',
              'docs/operations/JOURNAL-SYNC.md': 'JOURNAL-SYNC.md',
              'docs/operations/JOURNAL-STORAGE.md': 'JOURNAL-STORAGE.md',
              'docs/development/WEB-CLIENT.md': 'WEB-CLIENT.md'}
    illustrations = {
        'docs/assets/screenshots/day-notes-desktop.png': 'images/day-notes-desktop.png',
        'docs/assets/screenshots/day-notes-editor.png': 'images/day-notes-editor.png',
        'docs/assets/screenshots/journal-editor-mobile.png': 'images/journal-editor-mobile.png',
        'docs/assets/screenshots/about-references-desktop.png': 'images/about-references-desktop.png',
        'docs/assets/screenshots/work-session-editor-delete-confirm.png': 'images/work-session-editor-delete-confirm.png',
        'docs/assets/screenshots/holiday-preferences-mobile.png': 'images/holiday-preferences-mobile.png',
        'docs/assets/screenshots/public-holidays-desktop.png': 'images/public-holidays-desktop.png',
        'docs/assets/screenshots/public-holidays-details-mobile.png': 'images/public-holidays-details-mobile.png',
        'docs/assets/screenshots/calendar-subscriptions-desktop.png': 'images/calendar-subscriptions-desktop.png',
        'docs/assets/screenshots/calendar-subscriptions-details-mobile.png': 'images/calendar-subscriptions-details-mobile.png',
        'docs/assets/screenshots/locale-norwegian-account.png': 'images/locale-norwegian-account.png',
        'docs/assets/screenshots/locale-norwegian-administration.png': 'images/locale-norwegian-administration.png',
        'docs/assets/screenshots/activities-mobile.png': 'images/activities-mobile.png',
        'docs/assets/screenshots/project-icon-picker.png': 'images/project-icon-picker.png',
        'docs/assets/screenshots/track-project-icons.png': 'images/track-project-icons.png',
        'docs/assets/screenshots/track-project-icons-mobile.png': 'images/track-project-icons-mobile.png',
        'docs/assets/screenshots/compact-workweek-desktop.png': 'images/compact-workweek-desktop.png',
        'docs/assets/screenshots/compact-workweek-mobile.png': 'images/compact-workweek-mobile.png',
        'docs/assets/screenshots/projects-grouped-mobile.png': 'images/projects-grouped-mobile.png',
        'docs/assets/screenshots/account-menu-mobile.png': 'images/account-menu-mobile.png',
        'docs/assets/screenshots/work-session-labels-desktop.png': 'images/work-session-labels-desktop.png',
        'docs/assets/screenshots/work-session-labels-mobile.png': 'images/work-session-labels-mobile.png',
        'docs/assets/screenshots/account-data-review-desktop.png': 'images/account-data-review-desktop.png',
        'docs/assets/screenshots/account-data-review-mobile.png': 'images/account-data-review-mobile.png',
        'docs/assets/screenshots/passkeys-desktop.png': 'images/passkeys-desktop.png',
        'docs/assets/screenshots/passkeys-mobile.png': 'images/passkeys-mobile.png',
        'docs/assets/screenshots/bootstrap-work-session-desktop.png': 'images/work-session-desktop.png',
        'docs/assets/screenshots/bootstrap-work-session-narrow.png': 'images/work-session-narrow.png',
        'docs/assets/screenshots/track-month-desktop.png': 'images/track-month-desktop.png',
        'docs/assets/screenshots/track-month-narrow.png': 'images/track-month-narrow.png',
        'docs/assets/screenshots/track-month-picker.png': 'images/track-month-picker.png',
        'docs/assets/screenshots/toggl-import-review.png': 'images/toggl-import-review.png',
        'docs/assets/screenshots/toggl-import-history-narrow.png': 'images/toggl-import-history-narrow.png',
    }
    destinations = {**guides, **illustrations, 'common/api/openapi.json': 'openapi.json'}
    files += [(ROOT / source, 'timekeeper-server/' + destination)
              for source, destination in guides.items()]
    files += [(ROOT / source, 'timekeeper-server/' + destination)
              for source, destination in illustrations.items()]
    files += [(ROOT / 'common/api/openapi.json', 'timekeeper-server/openapi.json'),
              (ROOT / 'server/scripts/create_token.py', 'timekeeper-server/create_token.py'),
              (ROOT / 'server/scripts/configure_accounts.py', 'timekeeper-server/configure_accounts.py'),
              (ROOT / 'LICENSE', 'timekeeper-server/LICENSE')]
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as target:
        for source, name in files:
            if source.is_symlink() or 'test-tokens' in name or '/surefire-reports/' in name:
                raise ValueError('Unexpected distribution input')
            relative = source.relative_to(ROOT).as_posix()
            if relative in guides:
                target.writestr(name, render_document(ROOT, relative, destinations, commit))
            else:
                target.write(source, name)
        target.writestr('timekeeper-server/timekeeper-release.json', json.dumps(manifest, indent=2) + '\n')
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    (output / 'timekeeper-server.zip.sha256').write_text(checksum + '  timekeeper-server.zip\n')
    with zipfile.ZipFile(archive) as check:
        if check.testzip() is not None:
            raise ValueError('Invalid distribution archive')
        if json.loads(check.read('timekeeper-server/timekeeper-release.json')) != manifest:
            raise ValueError('Invalid distribution provenance')
    print(json.dumps({'archive': str(archive), 'sha256': checksum, 'manifest': manifest}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--tag', help='Actual checked-out release tag; omitted for untagged candidates')
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    package(args.output, args.tag, args.commit)


if __name__ == '__main__':
    main()
