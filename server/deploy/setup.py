#!/usr/bin/env python3
"""Create deployment credentials once, without replacing existing data or tokens."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets

HERE = Path(__file__).resolve().parent
SECRET_NAMES = ('postgres_admin_password', 'postgres_replication_password',
                'patroni_api_password', 'timekeeper_database_password')


def configuration():
    values = {}
    env_file = HERE / '.env'
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                values[key.strip()] = value.strip().strip("\"'")
    values.update(os.environ)
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner', required=True)
    parser.add_argument('--state-dir', type=Path)
    args = parser.parse_args()
    if (not args.owner.strip() or args.owner != args.owner.strip()
            or args.owner == 'local' or len(args.owner) > 200):
        parser.error('Choose a stable owner ID of 1–200 characters; local is reserved')
    state = (args.state_dir or Path(configuration().get('TIMEKEEPER_STATE_DIR', HERE / 'state'))).resolve()
    credentials = state / 'secrets'
    if any((state / f'postgres-{i}' / 'data' / 'PG_VERSION').exists() for i in (1, 2, 3)):
        missing = [name for name in SECRET_NAMES if not (credentials / name).is_file()]
        if missing:
            parser.error('Existing database requires its original secrets; restore them instead of generating replacements')
    credentials.mkdir(parents=True, exist_ok=True)
    credentials.chmod(0o700)
    for name in SECRET_NAMES:
        path = credentials / name
        try:
            with path.open('x') as output:
                output.write(secrets.token_urlsafe(48) + '\n')
        except FileExistsError:
            pass
        # Compose file secrets are bind mounts; uid/gid remapping is not supported.
        # The enclosing host directory is private; the mounted file must be readable by UID 10001.
        path.chmod(0o444)
    config = state / 'config'
    config.mkdir(parents=True, exist_ok=True)
    config.chmod(0o755)
    token_file = config / 'tokens.json'
    if not token_file.exists():
        token = secrets.token_urlsafe(32)
        with token_file.open('x') as output:
            json.dump([{'ownerId': args.owner, 'sha256': hashlib.sha256(token.encode()).hexdigest(),
                        'permissions': ['read', 'write']}], output, indent=2)
            output.write('\n')
        token_file.chmod(0o644)
        print('Save this bearer token in the client credential store; it is shown once:')
        print(token)
    else:
        print('Existing token registry preserved.')
    for name in ('postgres-1', 'postgres-2', 'postgres-3', 'etcd-1', 'etcd-2', 'etcd-3', 'backups'):
        (state / name).mkdir(parents=True, exist_ok=True)
    env_file = HERE / '.env'
    if not env_file.exists():
        env_file.write_text(f'TIMEKEEPER_STATE_DIR={state}\nTIMEKEEPER_BIND_ADDRESS=127.0.0.1\nTIMEKEEPER_HTTP_PORT=8080\n')
    print(f'Durable state: {state}')
    print('Start with: docker compose up -d')


if __name__ == '__main__':
    main()
