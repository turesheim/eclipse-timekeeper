#!/usr/bin/env python3
"""Verify the shipped offline recovery entry point with a disposable protocol fixture.

The normal administrator proof boundary is covered by application tests. This
check ensures the helper and its dependencies actually work from the distribution.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import socket
import time
import urllib.request
import urllib.error
from http.cookies import SimpleCookie
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from public_restore_acceptance import verify_public_restore

ROOT = Path(__file__).resolve().parents[2]


def verify_http_recovery(app, fixture, environment, restored=True):
    """Recover before HTTP, then prove restored credentials/data survive a restart."""
    for _ in range(2):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        origin = 'http://127.0.0.1:' + str(port)
        command = ['java', '-Xmx384m', '-Dquarkus.http.host=127.0.0.1',
                   '-Dquarkus.http.port=' + str(port),
                   '-Dquarkus.config.locations=' + (fixture / 'destination.properties').as_uri(),
                   '-jar', str(app / 'quarkus-run.jar')]
        with tempfile.TemporaryFile() as log:
            process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 60
                while True:
                    if process.poll() is not None:
                        raise ValueError('Packaged server exited before restored HTTP readiness')
                    try:
                        with urllib.request.urlopen(origin + '/q/health/ready', timeout=2) as response:
                            if response.status == 200:
                                break
                    except (urllib.error.URLError, TimeoutError):
                        pass
                    if time.monotonic() >= deadline:
                        raise ValueError('Packaged restored server did not become ready')
                    time.sleep(0.25)
                request = urllib.request.Request(origin + '/api/v1/auth/login',
                    data=json.dumps({'username': 'user-a' if restored else 'bootstrap',
                                     'password': 'Preserved archive account password' if restored else 'Disposable bootstrap password', 'remember': False}).encode(),
                    headers={'Content-Type': 'application/json', 'Origin': origin})
                with urllib.request.urlopen(request, timeout=10) as response:
                    cookies = SimpleCookie()
                    for header in response.headers.get_all('Set-Cookie', []):
                        cookies.load(header)
                    json.load(response)
                # Explicitly forward the Secure cookie for this disposable loopback
                # protocol test; deployed browser acceptance uses real HTTPS.
                cookie = '; '.join(key + '=' + value.value for key, value in cookies.items())
                if not cookie:
                    raise ValueError('Restored password login did not issue a session')
                request = urllib.request.Request(origin + '/api/v1/clients', headers={'Cookie': cookie})
                with urllib.request.urlopen(request, timeout=10) as response:
                    data = json.load(response)
                clients = data['items']
                if (restored and (len(clients) != 1 or clients[0]['id'] != '11111111-1111-4111-8111-111111111111' or clients[0]['name'] != 'Restored client æ' or clients[0]['version'] != 17)) or (not restored and clients):
                    raise ValueError('Restored client data was not preserved by packaged startup')
            finally:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    if not restored:
        return
    # A completed database without its bound material must fail before Quarkus
    # can initialize TLS, listeners, scheduled jobs, or account provisioning.
    missing_settings = fixture / 'missing-material.properties'
    missing_settings.write_text('\n'.join(line for line in (fixture / 'destination.properties').read_text().splitlines()
                                            if not line.startswith('timekeeper.instance-restore.storage=')) + '\n')
    missing_settings.chmod(0o600)
    missing = [('-Dquarkus.config.locations=' + missing_settings.as_uri())
               if argument.startswith('-Dquarkus.config.locations=') else argument for argument in command]
    rejected = subprocess.run(missing, cwd=ROOT, env=environment, capture_output=True, timeout=30)
    if rejected.returncode != 1 or b'Timekeeper startup recovery could not be verified.' not in rejected.stderr or rejected.stdout:
        raise ValueError('Completed restore without its material did not fail before HTTP startup')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', type=Path, required=True)
    args = parser.parse_args()
    digest = hashlib.sha256(args.distribution.read_bytes()).hexdigest()
    if args.distribution.with_suffix('.zip.sha256').read_text().split()[0] != digest:
        raise ValueError('Distribution checksum mismatch')
    report = ROOT / 'server/app/target/surefire-reports/TEST-net.resheim.timekeeper.server.InstanceRestoreRecoveryTest.xml'
    properties = {entry.attrib['name']: entry.attrib['value'] for entry in ET.parse(report).getroot().find('properties')}
    fixture_classpath = properties.get('surefire.test.class.path', properties.get('java.class.path'))
    if not fixture_classpath:
        raise ValueError('Run the server tests before packaged recovery acceptance')
    with tempfile.TemporaryDirectory(prefix='timekeeper-instance-restore-') as temporary:
        stage = Path(temporary)
        fixture = stage / 'fixture'
        fixture.mkdir(mode=0o700)
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(('TIMEKEEPER_', 'QUARKUS_', 'SMALLRYE_'))
                       and key not in {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'}}
        prepared = subprocess.run(['java', '-cp', fixture_classpath,
                                   'net.resheim.timekeeper.server.InstanceRestorePackagedFixture', str(fixture)],
                                  cwd=ROOT, env=environment, capture_output=True, timeout=40)
        if prepared.returncode:
            raise ValueError('Disposable restore fixture failed')
        with zipfile.ZipFile(args.distribution) as archive:
            for entry in archive.infolist():
                path = Path(entry.filename)
                if not entry.filename.startswith('timekeeper-server/') or path.is_absolute() or '..' in path.parts or stat.S_ISLNK(entry.external_attr >> 16):
                    raise ValueError('Unexpected distribution entry')
            manifest = json.loads(archive.read('timekeeper-server/timekeeper-release.json'))
            archive.extractall(stage / 'distribution')
        app = stage / 'distribution/timekeeper-server'
        # Boot libraries contain logging and SmallRye classes that are not in lib/main.
        runtime_classpath = os.pathsep.join(str(app / pattern) for pattern in ('app/*', 'lib/main/*', 'lib/boot/*'))
        environment['TIMEKEEPER_SERVER_DB_PASSWORD_FILE'] = str(fixture / 'database-secret')
        command = ['java', '-Dquarkus.config.locations=' + (fixture / 'destination.properties').as_uri(),
                   '-cp', runtime_classpath, 'net.resheim.timekeeper.server.InstanceRestoreMain', str(fixture / 'intents, café')]
        verify_http_recovery(app, fixture, environment)
        for _ in range(2):
            restored = subprocess.run(command, cwd=ROOT, env=environment, capture_output=True, timeout=40)
            if restored.returncode or not restored.stdout.startswith(b'INSTANCE_RESTORE_COMPLETE\n'):
                raise ValueError('Packaged offline recovery failed')
        if (fixture / 'intents, café/prepared.json').exists():
            raise ValueError('Completed recovery retained its plaintext handoff')
        cancelled_fixture = stage / 'cancelled-fixture'
        cancelled_fixture.mkdir(mode=0o700)
        prepared = subprocess.run(['java', '-cp', fixture_classpath,
                                   'net.resheim.timekeeper.server.InstanceRestorePackagedFixture', str(cancelled_fixture)],
                                  cwd=ROOT, env={key: value for key, value in environment.items() if not key.startswith('TIMEKEEPER_')},
                                  capture_output=True, timeout=40)
        if prepared.returncode:
            raise ValueError('Disposable cancellation fixture failed')
        environment['TIMEKEEPER_SERVER_DB_PASSWORD_FILE'] = str(cancelled_fixture / 'database-secret')
        cancel = ['java', '-Dquarkus.config.locations=' + (cancelled_fixture / 'destination.properties').as_uri(),
                  '-cp', runtime_classpath, 'net.resheim.timekeeper.server.InstanceRestoreMain', '--cancel',
                  str(cancelled_fixture / 'intents, café'), (cancelled_fixture / 'operation-id').read_text()]
        for _ in range(2):
            result = subprocess.run(cancel, cwd=ROOT, env=environment,
                                    input=json.dumps({'username': 'bootstrap', 'currentPassword': 'Disposable bootstrap password'}).encode(),
                                    capture_output=True, timeout=40)
            if result.returncode or result.stdout != b'INSTANCE_RESTORE_CANCELLED\n':
                raise ValueError('Packaged offline cancellation failed')
        if (cancelled_fixture / 'intents, café/prepared.json').exists():
            raise ValueError('Cancelled recovery retained its plaintext handoff')
        verify_http_recovery(app, cancelled_fixture, environment, restored=False)
        verify_public_restore(ROOT, app, stage / 'public-fixture', fixture_classpath,
                              {key: value for key, value in environment.items() if not key.startswith('TIMEKEEPER_')})
        evidence = {'sourceCommit': manifest['commit'], 'distributionSha256': digest,
                    'packagedRuntimeOnly': True, 'automaticPreboot': True, 'restoredPasswordLogin': True, 'restoredClientRead': True, 'httpRestart': True, 'missingMaterialRejectedBeforeHttp': True, 'fileBackedCredentials': True, 'completedRetry': True, 'plaintextStagingRemoved': True, 'offlineCancellation': True, 'cancelledStartup': True, 'publicStageConfirm': True, 'restoredJournalLabelAsset': True, 'sourceArchiveUnchanged': True}
        output = ROOT / 'server/target/acceptance'
        output.mkdir(parents=True, exist_ok=True)
        (output / 'instance-restore.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print('Packaged automatic restore, authenticated HTTP restart, offline cancellation, and cancelled startup passed.')


if __name__ == '__main__':
    main()
