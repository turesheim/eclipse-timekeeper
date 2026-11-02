#!/usr/bin/env python3
"""Run only selector-owned browser spec names, never shell-expanded PR data."""
import json
import os
from pathlib import Path
import subprocess
import sys


def command(specs):
    root = Path(__file__).resolve().parents[1]
    result = [sys.executable, str(root / 'server/web/tests/acceptance.py'),
              '--distribution', 'server/target/distribution/timekeeper-server.zip']
    for spec in specs:
        if not isinstance(spec, str) or Path(spec).name != spec or not spec.endswith('.spec.js') or not (root / 'server/web/tests' / spec).is_file():
            raise ValueError(f'Unknown browser spec: {spec!r}')
        result += ['--spec', spec]
    return result


if __name__ == '__main__':
    subprocess.run(command(json.loads(os.environ.get('BROWSER_SPECS', '[]'))), check=True)
