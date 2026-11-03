#!/usr/bin/env python3
"""Run only selector-owned browser spec names, never shell-expanded PR data."""
import json
import os
import re
from pathlib import Path
import subprocess
import sys


def command(specs, shard=None):
    root = Path(__file__).resolve().parents[1]
    result = [sys.executable, str(root / 'server/web/tests/acceptance.py'),
              '--distribution', 'server/target/distribution/timekeeper-server.zip']
    for spec in specs:
        if not isinstance(spec, str) or Path(spec).name != spec or not spec.endswith('.spec.js') or not (root / 'server/web/tests' / spec).is_file():
            raise ValueError(f'Unknown browser spec: {spec!r}')
        result += ['--spec', spec]
    if shard is not None:
        if not isinstance(shard, str) or not re.fullmatch(r'[1-4]/[1-4]', shard):
            raise ValueError('Expected a CI browser shard between 1/1 and 4/4')
        index, total = map(int, shard.split('/'))
        if index > total:raise ValueError('Browser shard index exceeds shard count')
        result += ['--shard', shard]
    return result


if __name__ == '__main__':
    subprocess.run(command(json.loads(os.environ.get('BROWSER_SPECS', '[]')), os.environ.get('BROWSER_SHARD')), check=True)
