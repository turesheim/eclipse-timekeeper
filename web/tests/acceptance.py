#!/usr/bin/env python3
"""Run browser acceptance against the checksum-verified, packaged server JVM."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
from urllib.error import URLError
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', type=Path, required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    digest = hashlib.sha256(args.distribution.read_bytes()).hexdigest()
    if args.distribution.with_suffix('.zip.sha256').read_text().split()[0] != digest:
        raise ValueError('Server distribution checksum mismatch')
    output = ROOT / 'web/test-results'
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='timekeeper-web-') as directory:
        data = Path(directory)
        with zipfile.ZipFile(args.distribution) as archive:
            if any(not name.startswith('timekeeper-server/') or '..' in Path(name).parts or Path(name).is_absolute() for name in archive.namelist()):
                raise ValueError('Unexpected archive paths')
            manifest = json.loads(archive.read('timekeeper-server/timekeeper-release.json'))
            archive.extractall(data)
        tokens = [secrets.token_urlsafe(32), secrets.token_urlsafe(32)]
        registry = data / 'tokens.json'
        registry.write_text(json.dumps([{'ownerId':'web-owner', 'sha256':hashlib.sha256(token.encode()).hexdigest(), 'permissions':['read','write'] if index == 0 else ['read']} for index, token in enumerate(tokens)]))
        registry.chmod(0o600)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        environment = {**os.environ, 'TIMEKEEPER_SERVER_DB_URL':'jdbc:h2:' + str(data / 'database'), 'TIMEKEEPER_SERVER_TOKENS_FILE':str(registry)}
        with (output / 'server.log').open('w') as log:
            process = subprocess.Popen([args.java, '-Dquarkus.http.port=' + str(port), '-jar', str(data / 'timekeeper-server/quarkus-run.jar')], env=environment, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 30
                while True:
                    if process.poll() is not None: raise RuntimeError('Server exited during startup')
                    try:
                        with urlopen(base + '/q/health/ready', timeout=1) as response:
                            if response.status == 200: break
                    except (URLError, TimeoutError): pass
                    if time.monotonic() >= deadline: raise RuntimeError('Server did not become ready')
                    time.sleep(.1)
                # Verify that this distribution actually serves the bundled frontend.
                with urlopen(base + '/web/', timeout=3) as response:
                    if b'id="login-form"' not in response.read(): raise AssertionError('Missing bundled web client')
                    if "frame-ancestors 'none'" not in response.headers.get('Content-Security-Policy',''): raise AssertionError('Missing web security policy')
                result = subprocess.run(['npm', 'run', 'test:browser'], cwd=ROOT / 'web', env={**environment, 'TIMEKEEPER_WEB_URL':base, 'TIMEKEEPER_WEB_TOKEN':tokens[0], 'TIMEKEEPER_WEB_READ_TOKEN':tokens[1], 'TIMEKEEPER_WEB_REGISTRY':str(registry)}, timeout=600)
                if result.returncode: raise RuntimeError('Browser acceptance failed')
                (output / 'acceptance.json').write_text(json.dumps({'distributionSha256':digest, 'sourceCommit':manifest['commit'], 'serverPid':process.pid, 'browser':'chromium', 'passed':True}, indent=2) + '\n')
            finally:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
