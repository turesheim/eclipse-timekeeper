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
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from configure_accounts import create_challenge
from recovery_fixture import RecoveryFixture

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', type=Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--grep', help='Run selected browser tests for diagnosis')
    parser.add_argument('--browser', choices=('chromium','webkit','firefox'), default='chromium', help='Browser engine for this acceptance run')
    parser.add_argument('--https', action='store_true', help='Use a loopback HTTPS reverse proxy with a test-only certificate')
    parser.add_argument('--mail', action='store_true', help='Exercise delivery with a loopback-only SMTP capture fixture (requires --https)')
    parser.add_argument('--passkeys', action='store_true', help='Use the HTTPS localhost RP for virtual WebAuthn browser tests (requires --https)')
    args = parser.parse_args()
    if args.mail and not args.https:parser.error('--mail requires --https')
    if args.passkeys and not args.https:parser.error('--passkeys requires --https')
    digest = hashlib.sha256(args.distribution.read_bytes()).hexdigest()
    if args.distribution.with_suffix('.zip.sha256').read_text().split()[0] != digest:
        raise ValueError('Server distribution checksum mismatch')
    output = ROOT / 'server/web/test-results'
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
        code, _ = create_challenge(data / 'setup.json', owner='web-owner')
        password = secrets.token_urlsafe(32)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        backend = f'http://127.0.0.1:{port}'
        fixture = RecoveryFixture(data,port,https=args.https,mail=args.mail,hostname='localhost' if args.passkeys else '127.0.0.1',calendars=True)
        base = fixture.base
        environment = {**os.environ, 'TIMEKEEPER_SERVER_DB_URL':'jdbc:h2:' + str(data / 'database'), 'TIMEKEEPER_SERVER_TOKENS_FILE':str(registry), 'TIMEKEEPER_SERVER_SETUP_FILE':str(data / 'setup.json'),
                       'TIMEKEEPER_PUBLIC_ORIGIN':base if args.https else 'NONE', 'TIMEKEEPER_DEPLOYED_AT':'2026-10-13T08:30:00Z', 'TIMEKEEPER_WEB_BUILD_COMMIT':manifest['commit'], 'TIMEKEEPER_SPK_VERSION':'1.99.0-146', **fixture.environment}
        server_log = ROOT / 'server/web/target/server.log'
        server_log.parent.mkdir(parents=True, exist_ok=True)
        with server_log.open('w') as log:
            process = subprocess.Popen([args.java, '-Dquarkus.http.port=' + str(port), '-jar', str(data / 'timekeeper-server/quarkus-run.jar')], env=environment, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 30
                while True:
                    if process.poll() is not None: raise RuntimeError('Server exited during startup')
                    try:
                        with urlopen(backend + '/q/health/ready', timeout=1) as response:
                            if response.status == 200: break
                    except (URLError, TimeoutError): pass
                    if time.monotonic() >= deadline: raise RuntimeError('Server did not become ready')
                    time.sleep(.1)
                # Verify that this distribution actually serves the bundled frontend.
                with urlopen(backend + '/web/', timeout=3) as response:
                    if b'id="login-form"' not in response.read(): raise AssertionError('Missing bundled web client')
                    if "frame-ancestors 'none'" not in response.headers.get('Content-Security-Policy',''): raise AssertionError('Missing web security policy')
                options=[]
                if args.https:
                    config=data/'playwright.config.mjs'
                    config.write_text('export default '+json.dumps({'testDir':str(ROOT/'server/web/tests'),'testMatch':'*.spec.js','workers':1,'retries':0,'timeout':45000,'expect':{'timeout':12000},
                        'reporter':[['list'],['junit',{'outputFile':str(output/'browser.xml')}]],'use':{'baseURL':base,'ignoreHTTPSErrors':True,'trace':'off','video':'off','screenshot':'only-on-failure'},
                        'projects':[{'name':args.browser+'-https','use':{'browserName':args.browser}}]})+';\n')
                    options+=['--config',str(config)]
                if args.grep:options+=['--grep',args.grep]
                command = ['npm', 'run', 'test:browser'] + (['--',*options] if options else [])
                result = subprocess.run(command, cwd=ROOT / 'server/web', env={**environment, 'TIMEKEEPER_WEB_URL':base, 'TIMEKEEPER_WEB_TOKEN':tokens[0], 'TIMEKEEPER_WEB_READ_TOKEN':tokens[1], 'TIMEKEEPER_WEB_REGISTRY':str(registry), 'TIMEKEEPER_WEB_SETUP_CODE':code, 'TIMEKEEPER_WEB_PASSWORD':password, 'TIMEKEEPER_WEB_BROWSER':args.browser,'TIMEKEEPER_WEB_PASSKEY_FIXTURE':'true' if args.passkeys else 'false'}, timeout=600)
                if result.returncode: raise RuntimeError('Browser acceptance failed')
                (output / 'acceptance.json').write_text(json.dumps({'distributionSha256':digest, 'sourceCommit':manifest['commit'], 'serverPid':process.pid, 'browser':args.browser, 'https':args.https,'loopbackSmtp':args.mail,'virtualPasskeys':args.passkeys,'passed':True}, indent=2) + '\n')
            finally:
                (output / 'transport.json').write_text(json.dumps({'authenticationRequests':fixture.events},indent=2)+'\n')
                fixture.close()
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
