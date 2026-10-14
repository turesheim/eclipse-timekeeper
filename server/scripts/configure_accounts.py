#!/usr/bin/env python3
"""Generate an administrator-controlled, expiring setup/recovery challenge; never stores the raw code."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets


def create_challenge(output, *, purpose='SETUP', owner=None, minutes=15, replace=False):
    if purpose not in ('SETUP', 'RECOVERY') or not 1 <= minutes <= 60:
        raise ValueError('Use SETUP or RECOVERY and a validity of 1–60 minutes')
    if owner is not None and (not owner.strip() or owner != owner.strip() or len(owner) > 200 or owner == 'local'):
        raise ValueError('Choose an explicit existing owner ID or omit it for a new setup account')
    if purpose == 'RECOVERY' and owner is None:
        raise ValueError('Recovery requires the exact existing owner ID')
    output = Path(output)
    if output.is_symlink() or (output.exists() and not replace):
        raise ValueError('Refusing to replace an existing setup file; use --replace explicitly')
    output.parent.mkdir(parents=True, exist_ok=True)
    code = secrets.token_urlsafe(32)
    expires = datetime.now(timezone.utc) + timedelta(minutes=minutes)
    value = {'purpose': purpose, 'sha256': hashlib.sha256(code.encode()).hexdigest(),
             'expiresAt': expires.isoformat().replace('+00:00', 'Z')}
    if owner is not None:
        value['ownerId'] = owner
    # Replace atomically without following an existing destination symlink.
    temporary = output.with_name(output.name + '.' + secrets.token_hex(8) + '.tmp')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            json.dump(value, stream, indent=2)
            stream.write('\n')
        if not replace:
            os.link(temporary, output)
            temporary.unlink()
        else:
            os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    return code, value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--purpose', choices=('SETUP', 'RECOVERY'), default='SETUP')
    parser.add_argument('--owner', help='Explicit existing owner to claim/recover; never inferred from username')
    parser.add_argument('--minutes', type=int, default=15)
    parser.add_argument('--replace', action='store_true')
    args = parser.parse_args()
    try:
        code, value = create_challenge(args.output, purpose=args.purpose, owner=args.owner, minutes=args.minutes, replace=args.replace)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print('Account setup/recovery code (shown once; do not place it in a URL or routine logs):')
    print(code)
    print('Expires: ' + value['expiresAt'])
    print('Set TIMEKEEPER_SERVER_SETUP_FILE to the generated file and restart the server replicas.')


if __name__ == '__main__':
    main()
