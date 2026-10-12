#!/usr/bin/env python3
"""Append a generated bearer token hash to an administrator-owned registry."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import secrets
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner', required=True)
    parser.add_argument('--file', required=True, type=Path)
    parser.add_argument('--read-only', action='store_true')
    parser.add_argument('--expires-at', help='UTC timestamp ending in Z')
    args = parser.parse_args()
    if not args.owner.strip() or args.owner.strip() != args.owner or len(args.owner) > 200 or args.owner == 'local':
        parser.error('Owner must be a nonblank stable ID of at most 200 characters; local is reserved')
    if args.expires_at:
        try:
            expiry = datetime.fromisoformat(args.expires_at.replace('Z', '+00:00'))
            if not args.expires_at.endswith('Z') or expiry <= datetime.now(timezone.utc):
                raise ValueError()
        except (ValueError, TypeError):
            parser.error('Expiry must be a future UTC timestamp ending in Z')
    try:
        records = json.loads(args.file.read_text()) if args.file.exists() else []
        if not isinstance(records, list):
            raise ValueError()
    except (ValueError, OSError):
        parser.error('Existing token registry is unreadable or not a JSON array')
    token = secrets.token_urlsafe(32)
    record = {'ownerId': args.owner, 'sha256': hashlib.sha256(token.encode()).hexdigest(),
              'permissions': ['read'] if args.read_only else ['read', 'write']}
    if args.expires_at:
        record['expiresAt'] = args.expires_at
    records.append(record)
    args.file.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=args.file.parent, delete=False, encoding='utf-8') as output:
            temporary = Path(output.name)
            json.dump(records, output, indent=2)
            output.write('\n')
        temporary.replace(args.file)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    # Printed once for the administrator to save in their client's credential store.
    print(token)


if __name__ == '__main__':
    main()
