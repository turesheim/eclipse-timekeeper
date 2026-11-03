#!/usr/bin/env python3
"""Run bounded, independent native acceptance groups without dropping scenarios."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


GROUPS = ('installer', 'upgrade-early', 'upgrade-late', 'restore',
          'migrate-early', 'migrate-late', 'migrate-runtime')


def commands(group, image):
    if group not in GROUPS:raise ValueError('Unknown Synology acceptance group')
    python = sys.executable
    migrate = [python, 'server/synology/tests/migration_acceptance.py']
    upgrade = [python, 'server/synology/tests/schema_upgrade_acceptance.py', '--source-schema']
    if group == 'installer':
        return [['go', 'test', '-C', 'server/synology/installer', './...'],
                ['go', 'vet', '-C', 'server/synology/installer', './...'],
                [python, 'server/synology/tests/acceptance.py']]
    if group.startswith('upgrade-'):
        schemas = range(2,6) if group == 'upgrade-early' else range(6,10)
        return [upgrade + [str(schema) for schema in schemas]]
    if group == 'restore':
        return [[python, 'server/scripts/schema_restore_acceptance.py', '--postgres-image', 'postgres:17.11-bookworm']]
    if group in ('migrate-early', 'migrate-late'):
        schemas = range(2,6) if group == 'migrate-early' else range(6,11)
        return [migrate + ['--source-schema', str(schema)] for schema in schemas]
    if group == 'migrate-runtime':
        if not image:raise ValueError('The runtime group requires the exact candidate image')
        return [migrate + ['--server-image', image]]
    raise ValueError('Unknown Synology acceptance group')


def run_group(group, image, output):
    receipt = {'group': group, 'passed': False, 'commands': []}
    try:
        for command in commands(group, image):
            print('Running ' + ' '.join(command), flush=True)
            started=time.monotonic()
            subprocess.run(command, check=True, timeout=600, cwd=ROOT)
            receipt['commands'].append({'command': command[1:], 'seconds': round(time.monotonic()-started,2)})
        receipt['passed']=True
    finally:
        output.mkdir(parents=True,exist_ok=True)
        (output/(group+'.json')).write_text(json.dumps(receipt,indent=2)+'\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('group', choices=GROUPS)
    parser.add_argument('--server-image')
    args = parser.parse_args()
    run_group(args.group, args.server_image, ROOT/'server/target/synology-native')
