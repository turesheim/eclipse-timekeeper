#!/usr/bin/env python3
"""Package the shared sign-in JVM and unconfigured deployment templates for review."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def verify_identity(app, commit):
    identities = []
    for jar in (app / 'app').glob('*.jar'):
        with zipfile.ZipFile(jar) as source:
            if 'connect-build.properties' in source.namelist():
                identities.append(dict(line.strip().split('=', 1) for line in
                                       source.read('connect-build.properties').decode().splitlines() if '=' in line))
    if identities != [{'commit': commit, 'dirty': 'false'}]:
        raise ValueError('Rebuild the shared sign-in service from this exact clean commit')


def package(output, commit):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Expected a full source commit')
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip() != commit or subprocess.check_output(
            ['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip():
        raise ValueError('Packaging requires this exact clean source checkout')
    app = ROOT / 'server/connect/target/quarkus-app'
    if not all((app / name).exists() for name in ('quarkus-run.jar', 'app', 'lib', 'quarkus')):
        raise ValueError('Build the complete shared sign-in JVM before packaging')
    verify_identity(app, commit)
    output.mkdir(parents=True, exist_ok=True)
    archive = output / 'dagsverk-connect.zip'
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as target:
        for path in sorted(app.rglob('*')):
            if path.is_file():
                target.write(path, 'dagsverk-connect/' + path.relative_to(app).as_posix())
        for source, destination in (
                ('docs/operations/SHARED-SIGN-IN.md', 'README.md'), ('LICENSE', 'LICENSE'),
                ('server/connect/deploy/compose.yaml', 'deploy/compose.yaml'),
                ('server/connect/deploy/service.env.example', 'deploy/service.env.example')):
            target.write(ROOT / source, 'dagsverk-connect/' + destination)
        target.writestr('dagsverk-connect/build.json', json.dumps({'format': 1, 'commit': commit, 'java': 21,
                                                                'database': 'h2', 'protocol': 1}, indent=2) + '\n')
    archive.with_suffix('.zip.sha256').write_text(hashlib.sha256(archive.read_bytes()).hexdigest() + '  ' + archive.name + '\n')
    return archive


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    print(package(args.output, args.commit))
