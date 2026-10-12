#!/usr/bin/env python3
"""Package the verified JVM distribution without test plugins or credential files."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]


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
    output.mkdir(parents=True, exist_ok=True)
    archive = output / 'timekeeper-server.zip'
    manifest = {'format': 1, 'kind': 'candidate' if tag is None else 'release', 'tag': tag, 'commit': commit, 'java': 21,
                'quarkus': '3.40.1', 'h2': '2.5.250', 'apiMajor': 1, 'serverSchema': 1}
    files = [(p, 'timekeeper-server/' + p.relative_to(app).as_posix()) for p in sorted(app.rglob('*')) if p.is_file()]
    files += [(ROOT / 'server/README.md', 'timekeeper-server/README.md'),
              (ROOT / 'api/openapi.json', 'timekeeper-server/openapi.json'),
              (ROOT / 'api/README.md', 'timekeeper-server/API-POLICY.md'),
              (ROOT / 'server/scripts/create_token.py', 'timekeeper-server/create_token.py'),
              (ROOT / 'LICENSE', 'timekeeper-server/LICENSE')]
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as target:
        for source, name in files:
            if source.is_symlink() or 'test-tokens' in name or '/surefire-reports/' in name:
                raise ValueError('Unexpected distribution input')
            if name == 'timekeeper-server/README.md':
                target.writestr(name, source.read_text().replace('(../api/README.md)', '(API-POLICY.md)')
                               .replace('(deploy/README.md)', f'(https://github.com/turesheim/eclipse-timekeeper/blob/{commit}/server/deploy/README.md)'))
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
