#!/usr/bin/env python3
"""Package deployment configuration with complete, standalone operational guides."""
import argparse
from pathlib import Path
import re
import tarfile
from io import BytesIO

from documentation import render_document

ROOT = Path(__file__).resolve().parents[2]
FILES = ('compose.yaml', '.env.example', 'haproxy.cfg', 'setup.py', 'backup.sh',
         'restore.sh', 'update.sh', 'postgres/bootstrap.sql')
GUIDES = {'docs/operations/DEPLOYMENT.md': 'README.md',
          'docs/operations/LOCAL-DOCKER.md': 'LOCAL.md',
          'docs/operations/TAILSCALE.md': 'TAILSCALE.md',
          'docs/operations/SERVER.md': 'SERVER.md',
          'docs/architecture/API-V1.md': 'API-POLICY.md'}


def package(output, commit, root=ROOT):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Expected the full source commit ID')
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, 'w:gz') as archive:
        for name in FILES:
            source = root / 'server/deploy' / name
            if source.is_symlink() or not source.is_file():
                raise ValueError('Missing or unexpected deployment input: ' + name)
            archive.add(source, arcname=name, recursive=False)
        for source, destination in GUIDES.items():
            content = render_document(root, source, GUIDES, commit).encode()
            entry = tarfile.TarInfo(destination)
            entry.size = len(content)
            entry.mode = 0o644
            archive.addfile(entry, BytesIO(content))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--commit', required=True)
    arguments = parser.parse_args()
    package(arguments.output, arguments.commit)
