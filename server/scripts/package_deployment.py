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
         'restore.sh', 'restore-database.py', 'export-synology.py', 'update.sh', 'set-deployment-time.sh', 'postgres/bootstrap.sql')
GUIDES = {'docs/operations/DEPLOYMENT.md': 'README.md',
          'docs/operations/LOCAL-DOCKER.md': 'LOCAL.md',
          'docs/operations/TAILSCALE.md': 'TAILSCALE.md',
              'docs/operations/ACCOUNTS.md': 'ACCOUNTS.md',
          'docs/operations/ACCOUNT-DATA.md': 'ACCOUNT-DATA.md',
          'docs/operations/API-DIAGNOSTICS.md': 'API-DIAGNOSTICS.md',
          'docs/operations/TOGGL-IMPORT.md': 'TOGGL-IMPORT.md',
          'docs/operations/JOURNAL-WEBDAV.md': 'JOURNAL-WEBDAV.md',
          'docs/operations/JOURNAL-STORAGE.md': 'JOURNAL-STORAGE.md',
          'docs/operations/SYNOLOGY-PACKAGE.md': 'SYNOLOGY-PACKAGE.md',
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
        archive.add(root / 'server/scripts/configure_accounts.py', arcname='configure_accounts.py', recursive=False)
        for name in ('postgresql-shape.sql','schema-v2-postgresql.shape','schema-v3-postgresql.shape','schema-v4-postgresql.shape','schema-v5-postgresql.shape','schema-v6-postgresql.shape','schema-v7-postgresql.shape','schema-v8-postgresql.shape','recovery-v3.sql','passkeys-v4.sql','settings-v5.sql','locale-v6.sql','day-labels-v7.sql','day-note-history-v8.sql'):
            archive.add(root / 'server/app/src/main/resources/db' / name, arcname='migration/'+name,recursive=False)
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
