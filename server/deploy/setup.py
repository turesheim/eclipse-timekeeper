#!/usr/bin/env python3
"""Create deployment credentials once, without replacing existing data or tokens."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import sys
sys.path[:0] = [str(Path(__file__).resolve().parent), str(Path(__file__).resolve().parents[1] / "scripts")]
from configure_accounts import create_challenge

HERE = Path(__file__).resolve().parent
SECRET_NAMES = ('postgres_admin_password', 'timekeeper_database_password')


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
    parser.add_argument('--owner', help='Explicit legacy owner to claim; omit for a new identity')
    parser.add_argument('--state-dir', type=Path)
    args = parser.parse_args()
    if args.owner is not None and (not args.owner.strip() or args.owner != args.owner.strip()
            or args.owner == 'local' or len(args.owner) > 200):
        parser.error('Choose a stable owner ID of 1–200 characters; local is reserved')
    state = (args.state_dir or Path(configuration().get('TIMEKEEPER_STATE_DIR', HERE / 'state'))).resolve()
    credentials = state / 'secrets'
    if any((state / f'postgres-{i}' / 'data' / 'PG_VERSION').exists() for i in (1, 2, 3)):
        parser.error('Existing HA data requires a logical backup and restore into a new state directory; see README.md')
    if any((state / 'postgres').rglob('PG_VERSION')):
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
    # Optional transport configuration is retained across setup/upgrade. Keep the
    # password out of Compose's interpolated environment and generated metadata.
    optional_files = {
        config / 'mail.properties': ('timekeeper.mail.enabled=false\nquarkus.mailer.host=localhost\n'
            'quarkus.mailer.port=587\nquarkus.mailer.from=timekeeper@localhost\n'
            'quarkus.mailer.username=\nquarkus.mailer.tls=false\nquarkus.mailer.start-tls=REQUIRED\n', 0o644),
        credentials / 'timekeeper_smtp_password': ('', 0o444),
    }
    for path, (value, mode) in optional_files.items():
        try:
            with path.open('x') as output:output.write(value)
        except FileExistsError:pass
        path.chmod(mode)
    token_file = config / 'tokens.json'
    setup_created = False
    if not token_file.exists():
        if any((state / 'postgres').rglob('PG_VERSION')):
            parser.error('Existing data requires its original token registry or explicit account migration; see SERVER.md')
        with token_file.open('x') as output:
            output.write('[]\n')
        token_file.chmod(0o644)
        code, _ = create_challenge(config / 'account-setup.json', owner=args.owner)
        # Only the digest is mounted; make it readable by the server's container UID.
        (config / 'account-setup.json').chmod(0o644)
        setup_created = True
        print('First administrator code (expires in 15 minutes; shown once):')
        print(code)
        print('Open /web/ and choose First administrator setup. API tokens can be created after signing in.')
    else:
        print('Existing token registry and account setup preserved.')
    for name in ('postgres', 'backups'):
        (state / name).mkdir(parents=True, exist_ok=True)
    env_file = HERE / '.env'
    if not env_file.exists():
        env_file.write_text(f'TIMEKEEPER_STATE_DIR={state}\nTIMEKEEPER_BIND_ADDRESS=127.0.0.1\nTIMEKEEPER_HTTP_PORT=8080\n')
    if setup_created:
        with env_file.open('a') as output:
            output.write('\nTIMEKEEPER_SERVER_SETUP_FILE=/config/account-setup.json\n')
    print(f'Durable state: {state}')
    print('Start with: docker compose up -d')


if __name__ == '__main__':
    main()
