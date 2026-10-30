#!/usr/bin/env python3
"""Run browser acceptance against the checksum-verified, packaged server JVM."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.error import URLError
from urllib.request import urlopen, build_opener, HTTPCookieProcessor, HTTPSHandler, Request
from http.cookiejar import CookieJar, DefaultCookiePolicy
import ssl
import re
import zipfile
import xml.etree.ElementTree as ET
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from configure_accounts import create_challenge
from recovery_fixture import RecoveryFixture

ROOT = Path(__file__).resolve().parents[3]


def retain_server_logs(server_log, output, identity):
    """Playwright clears its output between invocations; retain earlier JVM evidence."""
    history=server_log.parent/'server-logs';history.mkdir(parents=True,exist_ok=True)
    key=hashlib.sha256(json.dumps(identity,sort_keys=True).encode()).hexdigest()[:20]
    saved=history/(key+'-'+secrets.token_hex(8)+'.log')
    shutil.copyfile(server_log,saved)
    destination=output/'server-logs';destination.mkdir(parents=True,exist_ok=True)
    for path in history.glob('*.log'):
        if path.is_file() and not path.is_symlink():shutil.copyfile(path,destination/path.name)
    return saved


def bootstrap_batch(base,code,password,certificate=None):
    """Use the normal public setup flow; bootstrap UI coverage keeps its fresh fixture."""
    # Browsers accept Secure cookies on the trusted HTTP loopback fixture.
    # Match that fixture-only behavior; HTTPS still validates its own certificate.
    handlers=[HTTPCookieProcessor(CookieJar(policy=DefaultCookiePolicy(secure_protocols=('https','http'))))]
    if certificate:handlers.append(HTTPSHandler(context=ssl.create_default_context(cafile=str(certificate))))
    opener=build_opener(*handlers)
    with opener.open(base+'/api/v1/auth/status',timeout=10) as reply:
        if not json.load(reply)['setupRequired']:raise RuntimeError('Expected a fresh browser fixture')
    body={'code':code,'username':'browser-admin','password':password,'displayName':'browser-admin','email':'browser-admin@example.org'}
    request=Request(base+'/api/v1/auth/setup',data=json.dumps(body).encode(),headers={'Origin':base,'Content-Type':'application/json'},method='POST')
    with opener.open(request,timeout=15) as reply:
        if reply.status!=200:raise RuntimeError('Browser fixture bootstrap failed')
        csrf=json.load(reply)['csrf']
    with opener.open(Request(base+'/api/v1/auth/logout',data=b'',headers={'Origin':base,'X-CSRF-Token':csrf},method='POST'),timeout=10) as reply:
        if reply.status!=204:raise RuntimeError('Browser fixture bootstrap logout failed')

def spec_pattern(name):
    return r'(^|[\\/])'+re.escape(name)+'$'


def spec_batches(specs, size=6):
    """Bound shared fixture lifetime; unrelated features must not share login budgets."""
    values=sorted(specs)
    return [values[index:index+size] for index in range(0,len(values),size)]


def restore_batch_artifacts(saved, output):
    """Playwright clears its output on every invocation; retain all batch evidence."""
    reports=ET.Element('testsuites')
    totals={'tests':0,'failures':0,'errors':0,'skipped':0}
    elapsed=0.0;transport=[]
    for folder in sorted(saved.iterdir()):
        shutil.copytree(folder,output,dirs_exist_ok=True)
        shutil.copytree(folder,output/'batches'/folder.name,dirs_exist_ok=True)
        audit=folder/'transport.json'
        if audit.exists():transport.extend(json.loads(audit.read_text()).get('authenticationRequests',[]))
        report=folder/'browser.xml'
        if report.exists():
            root=ET.parse(report).getroot()
            suites=[root] if root.tag=='testsuite' else list(root)
            for suite in suites:
                reports.append(suite)
                for key in totals:totals[key]+=int(suite.get(key,'0'))
                elapsed+=float(suite.get('time','0'))
    (output/'transport.json').write_text(json.dumps({'authenticationRequests':transport},indent=2)+'\n')
    for key,value in totals.items():reports.set(key,str(value))
    reports.set('time',str(elapsed))
    ET.ElementTree(reports).write(output/'browser.xml',encoding='utf-8',xml_declaration=True)


def run_batches(args,output,digest):
    # Fresh JVM/database/credentials per small group. Keep production throttles,
    # test assertions and individual timeouts intact; never retry a failed login.
    batches=spec_batches(path.name for path in (ROOT/'server/web/tests').glob('*.spec.js'))
    if not batches:raise ValueError('No browser spec files found')
    receipts=[];passed=False;deadline=time.monotonic()+1200
    with tempfile.TemporaryDirectory(prefix='timekeeper-browser-batches-') as temporary:
        saved=Path(temporary)
        try:
            for index,specs in enumerate(batches,1):
                if output.exists():shutil.rmtree(output)
                output.mkdir(parents=True)
                command=[sys.executable,str(Path(__file__).resolve()),'--distribution',str(args.distribution.resolve()),'--java',args.java,'--browser',args.browser,'--deadline',str(deadline)]
                for flag in ('https','mail','passkeys','restore'):
                    if getattr(args,flag):command.append('--'+flag)
                for spec in specs:command.extend(['--spec',spec])
                print(f'Browser fixture {index}/{len(batches)}: '+', '.join(specs),flush=True)
                try:
                    result=subprocess.run(command,timeout=max(.001,deadline-time.monotonic())+60)
                finally:
                    shutil.copytree(output,saved/f'{index:02d}')
                if result.returncode:raise RuntimeError(f'Browser fixture {index} failed')
                receipt=json.loads((output/'acceptance.json').read_text())
                if not receipt.get('passed') or receipt['distributionSha256']!=digest:raise RuntimeError('Browser fixture receipt mismatch')
                receipts.append({'specs':specs,**receipt})
            passed=True
        finally:
            restore_batch_artifacts(saved,output)
            summary={'distributionSha256':digest,'browser':args.browser,'https':args.https,'loopbackSmtp':args.mail,'virtualPasskeys':args.passkeys,'passed':passed,'batches':receipts}
            if receipts:summary['sourceCommit']=receipts[0]['sourceCommit']
            (output/'acceptance.json').write_text(json.dumps(summary,indent=2)+'\n')
    return 0

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', type=Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--grep', help='Run selected browser tests for diagnosis')
    parser.add_argument('--browser', choices=('chromium','webkit','firefox'), default='chromium', help='Browser engine for this acceptance run')
    parser.add_argument('--https', action='store_true', help='Use a loopback HTTPS reverse proxy with a test-only certificate')
    parser.add_argument('--mail', action='store_true', help='Exercise delivery with a loopback-only SMTP capture fixture (requires --https)')
    parser.add_argument('--passkeys', action='store_true', help='Use the HTTPS localhost RP for virtual WebAuthn browser tests (requires --https)')
    parser.add_argument('--restore', action='store_true', help='Enable private restore staging for isolated confirmation acceptance')
    parser.add_argument('--spec', action='append', choices=sorted(path.name for path in (ROOT/'server/web/tests').glob('*.spec.js')), help='Run selected spec files in one isolated fixture')
    parser.add_argument('--deadline', type=float, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.mail and not args.https:parser.error('--mail requires --https')
    if args.passkeys and not args.https:parser.error('--passkeys requires --https')
    digest = hashlib.sha256(args.distribution.read_bytes()).hexdigest()
    if args.distribution.with_suffix('.zip.sha256').read_text().split()[0] != digest:
        raise ValueError('Server distribution checksum mismatch')
    output = ROOT / 'server/web/test-results'
    output.mkdir(parents=True, exist_ok=True)
    if not args.grep and not args.spec:return run_batches(args,output,digest)
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
                       'TIMEKEEPER_INSTANCE_BACKUP_STORAGE':str(data / 'instance-backups'), 'TIMEKEEPER_INSTANCE_RESTORE_STORAGE':str(data / 'instance-restore') if args.restore else 'NONE', 'TIMEKEEPER_PUBLIC_ORIGIN':base if args.https else 'NONE', 'TIMEKEEPER_DEPLOYED_AT':'2026-10-13T08:30:00Z', 'TIMEKEEPER_WEB_BUILD_COMMIT':manifest['commit'], 'TIMEKEEPER_SPK_VERSION':'1.99.0-146', **fixture.environment}
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
                if args.spec and 'accounts.spec.js' not in args.spec:
                    bootstrap_batch(base,code,password,data/'fixture-cert.pem' if args.https else None)
                options=[spec_pattern(name) for name in (args.spec or [])]
                if args.https:
                    config=data/'playwright.config.mjs'
                    config.write_text('export default '+json.dumps({'testDir':str(ROOT/'server/web/tests'),'testMatch':'*.spec.js','workers':1,'retries':0,'timeout':45000,'expect':{'timeout':12000},
                        'reporter':[['list'],['junit',{'outputFile':str(output/'browser.xml')}]],'use':{'baseURL':base,'ignoreHTTPSErrors':True,'trace':'off','video':'off','screenshot':'only-on-failure'},
                        'projects':[{'name':args.browser+'-https','use':{'browserName':args.browser}}]})+';\n')
                    options+=['--config',str(config)]
                if args.grep:options+=['--grep',args.grep]
                command = ['npm', 'run', 'test:browser'] + (['--',*options] if options else [])
                # The serial suite exceeds 12 minutes on shared CI runners.
                # Keep individual test limits; bound the whole suite separately,
                # with dependency-installation/HTTPS headroom in the CI job.
                result = subprocess.run(command, cwd=ROOT / 'server/web', env={**environment, 'TIMEKEEPER_WEB_URL':base, 'TIMEKEEPER_WEB_TOKEN':tokens[0], 'TIMEKEEPER_WEB_READ_TOKEN':tokens[1], 'TIMEKEEPER_WEB_REGISTRY':str(registry), 'TIMEKEEPER_WEB_SETUP_CODE':code, 'TIMEKEEPER_WEB_PASSWORD':password, 'TIMEKEEPER_WEB_BROWSER':args.browser,'TIMEKEEPER_WEB_RESTORE_FIXTURE':'true' if args.restore else 'false','TIMEKEEPER_WEB_PASSKEY_FIXTURE':'true' if args.passkeys else 'false'}, timeout=max(.001,args.deadline-time.monotonic()) if args.deadline else 1200)
                if result.returncode: raise RuntimeError('Browser acceptance failed')
                (output / 'acceptance.json').write_text(json.dumps({'distributionSha256':digest, 'sourceCommit':manifest['commit'], 'serverPid':process.pid, 'browser':args.browser, 'https':args.https,'loopbackSmtp':args.mail,'virtualPasskeys':args.passkeys,'passed':True}, indent=2) + '\n')
            finally:
                (output / 'transport.json').write_text(json.dumps({'authenticationRequests':fixture.events},indent=2)+'\n')
                fixture.close()
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
                log.flush()
                retain_server_logs(server_log,output,{'distribution':digest,'browser':args.browser,'https':args.https,'mail':args.mail,'passkeys':args.passkeys,'restore':args.restore,'selector':args.grep,'specs':args.spec})
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
