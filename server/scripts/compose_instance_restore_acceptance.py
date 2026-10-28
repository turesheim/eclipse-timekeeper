#!/usr/bin/env python3
"""Real administrator restore and later material recovery on packaged Compose.

Two disposable projects use the actual deployment artifact and application role.
Canonical HTTPS Origin/cookies model TLS proxy forwarding over backend loopback.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import selectors
import shutil
import socket
import subprocess
import tarfile
import tempfile
import time

from configure_accounts import create_challenge
from package_deployment import package
from instance_acceptance_client import API, seed, export, verify_data, ADMIN_PASSWORD, DESTINATION_PASSWORD, PASSPHRASE

ROOT = Path(__file__).resolve().parents[2]


class Installation:
    def __init__(self, folder, image, identity):
        self.folder = folder; self.folder.mkdir()
        self.project = 'timekeeper-instance-' + secrets.token_hex(5)
        self.origin = 'https://localhost:' + ('18443' if identity == 'timekeeper' else '19443')
        self.environment = {key: value for key, value in os.environ.items()
                            if not key.startswith(('TIMEKEEPER_', 'COMPOSE_'))}
        self.environment['COMPOSE_PROJECT_NAME'] = self.project
        self.identity = identity
        self.started = False
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
        self.state = folder / 'state'
        archive = folder / 'deployment.tar.gz'
        package(archive, subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip())
        with tarfile.open(archive) as source:
            source.extractall(folder, filter='data')
        (folder / '.env').write_text('TIMEKEEPER_STATE_DIR=' + str(self.state) + '\nTIMEKEEPER_SERVER_IMAGE=' + image
            + '\nTIMEKEEPER_PACKAGE_ID=' + identity + '\nTIMEKEEPER_PUBLIC_ORIGIN=' + self.origin
            + '\nTIMEKEEPER_HTTP_PORT=' + str(port) + '\n')
        self.run('python3', str(folder / 'setup.py'), '--state-dir', str(self.state))
        self.code, _ = create_challenge(self.state / 'config/account-setup.json', replace=True)
        (self.state / 'config/account-setup.json').chmod(0o644)

    def run(self, *arguments, expected=0):
        result = subprocess.run(arguments, cwd=self.folder, env=self.environment, capture_output=True, timeout=240)
        if (result.returncode == 0) != (expected == 0):
            # Installer output may contain one-time setup credentials.
            raise RuntimeError('Disposable Compose operation failed: ' + arguments[0])
        return result.stdout.decode().strip()

    def compose(self, *arguments, expected=0):
        return self.run('docker', 'compose', *arguments, expected=expected)

    def start(self):
        self.started = True
        self.compose('up', '-d', '--wait', '--wait-timeout', '150', 'api-1')

    def stop(self):
        self.compose('stop', 'api-1', 'api-2')

    def api(self):
        return API(self.compose('ps', '-q', 'api-1'), 8080, self.origin)

    def backup(self, expected=0):
        return self.run('sh', './backup.sh', expected=expected)

    def restore(self, backup, expected=0):
        return self.run('sh', './restore.sh', str(backup), expected=expected)

    def sql(self, sql):
        result = subprocess.run(['docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'timekeeper', '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1'],
                                cwd=self.folder, env=self.environment, input=sql.encode(), capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError('Disposable Compose SQL fixture failed')
        return result.stdout.decode().strip()

    def close(self):
        if self.started:
            self.compose('down', '--volumes', '--remove-orphans')
        # Delete only this test's private host state, including PostgreSQL files.
        self.run('docker', 'run', '--rm', '-v', str(self.folder) + ':/cleanup', 'postgres:18.6-bookworm',
                 'sh', '-ec', 'rm -rf /cleanup/state')
        shutil.rmtree(self.folder)


def restore_api(destination, archive):
    api = destination.api()
    api.call('GET', '/auth/status')
    api.call('POST', '/auth/setup', {'code': destination.code, 'username': 'destination-admin', 'password': DESTINATION_PASSWORD,
                                  'displayName': 'Disposable destination administrator', 'email': 'destination@example.org'})
    operation = api.call('POST', '/admin/instance/uploads', {'currentPassword': DESTINATION_PASSWORD,
        'bytes': len(archive), 'sha256': hashlib.sha256(archive).hexdigest()})['operation']['id']
    path = '/admin/instance/uploads/' + operation
    for offset in range(0, len(archive), 1048576):
        api.call('PUT', path + '/content?offset=' + str(offset), archive[offset:offset + 1048576])
    api.call('POST', path + '/validate', {'currentPassword': DESTINATION_PASSWORD, 'passphrase': PASSPHRASE})
    api.until(path, lambda value: value['operation']['state'] == 'REVIEW')
    stage = api.call('GET', path + '/staging')
    api.call('POST', path + '/staging', {'currentPassword': DESTINATION_PASSWORD, 'passphrase': PASSPHRASE,
        'operationVersion': stage['operationVersion'], 'publicOrigin': destination.origin, 'passkeyRpId': 'AUTO', 'passkeysEnabled': True})
    stage = api.until(path + '/staging', lambda value: value['phase'] == 'READY')
    if stage['target']['packageId'] != destination.identity:
        raise RuntimeError('Compose restore did not review its actual package identity')
    api.call('POST', path + '/confirm', {'currentPassword': DESTINATION_PASSWORD, 'operationVersion': stage['operationVersion'],
        'generation': stage['generation'], 'confirmation': 'RESTORE ' + destination.identity})
    api.call('GET', '/clients', expected=(503,))
    destination.backup(expected=1)  # Pending consent cannot enter maintenance archives.
    destination.stop(); destination.start()
    api.call('GET', '/clients', expected=(401,))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-image', default='timekeeper-server:test')
    parser.add_argument('--expected-commit')
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/acceptance/instance-compose.json')
    args = parser.parse_args()
    args.output.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='timekeeper-compose-instance-') as temporary:
        source = destination = None
        try:
            folder = Path(temporary)
            source = Installation(folder / 'source', args.server_image, 'timekeeper'); source.start()
            original, expected = seed(source)
            information = original.call('GET', '/server')
            if args.expected_commit and information['commit'] != args.expected_commit:
                raise RuntimeError('Compose acceptance image does not match requested source commit')
            archive = export(original)
            print('Administrator exported healthy Compose source', flush=True)
            destination = Installation(folder / 'destination', args.server_image, 'dagsverk'); destination.start()
            restore_api(destination, archive)
            restored = verify_data(destination, expected)
            print('Public Compose restore preserved account access, data and attachments', flush=True)
            backup = Path(destination.backup())
            if not (backup / 'instance-material.tar').is_file():
                raise RuntimeError('Maintenance backup omitted committed material')
            # Another operator cannot interleave maintenance on this volume.
            with tempfile.TemporaryFile() as errors:
                held = subprocess.Popen(['docker', 'compose', 'run', '--rm', '--no-deps', '-T', '--user', '10001:10001',
                    '--entrypoint', 'sh', 'maintenance', '-ec',
                    'umask 077; exec 9>>/instance/maintenance.lock; flock -n 9; printf "LOCKED\\n"; cat > /dev/null'],
                    cwd=destination.folder, env=destination.environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors)
                try:
                    with selectors.DefaultSelector() as selector:
                        selector.register(held.stdout, selectors.EVENT_READ)
                        if not selector.select(30) or held.stdout.readline(16) != b'LOCKED\n':
                            raise RuntimeError('Disposable maintenance lock did not become ready')
                    destination.backup(expected=1)
                finally:
                    held.stdin.close(); held.wait(timeout=30); held.stdout.close()
            destination.stop()
            # Remove the active root while retaining the logical database. A new
            # container cannot start it, and another backup must refuse omission.
            destination.compose('run', '--rm', '--no-deps', '-T', '--user', '10001:10001', '--entrypoint', 'sh', 'maintenance', '-ec',
                                'mv /instance/restore /instance/restore-lost')
            destination.backup(expected=1)
            destination.run('python3', './instance_material.py', 'prepare', str(backup))
            destination.compose('run', '--rm', '--no-deps', '-T', 'api-1', expected=1)
            destination.restore(backup)
            verify_data(destination, expected)
            print('Maintenance restored missing active material and retained usable application data', flush=True)
            # A valid dump with its material stripped must roll back SQL cleanup,
            # retaining the destination's later write and active generation.
            stripped = backup.parent / 'stripped'; shutil.copytree(backup, stripped)
            (stripped / 'instance-material.tar').unlink()
            sentinel = restored.call('POST', '/clients', {'name': 'Must survive rejected restore', 'color': '#123456'})
            destination.stop()
            destination.run('python3', './restore-database.py', str(stripped / 'database.dump'), expected=1)
            destination.start()
            live = destination.api(); live.login('original-admin', ADMIN_PASSWORD)
            live.call('GET', '/clients/' + sentinel['id'])
            destination.restore(stripped, expected=1)
            destination.start()
            # Retry the complete backup after rejection; active files may already
            # exist, so installation must be immutable and idempotent.
            destination.restore(backup)
            final = verify_data(destination, expected)
            final.call('GET', '/clients/' + sentinel['id'], expected=(404,))
            verify_data(source, expected)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            receipt = {'sourceCommit': information['commit'], 'publicAdministratorRestore': True, 'twoAccountsAndOwnerIsolation': True,
                'dataIdsVersionsAndRelationships': True, 'journalsAndAttachmentBytes': True, 'pendingRestoreBackupRefused': True,
                'materialLossBackupRefused': True, 'maintenanceMaterialRecovery': True, 'concurrentMaintenanceRefused': True, 'interruptedMaintenanceRestartRefused': True, 'strippedMaterialSqlRolledBack': True,
                'completeBackupRetry': True, 'sourceApplicationUnchanged': True}
            args.output.write_text(json.dumps(receipt, indent=2) + '\n')
            print(json.dumps(receipt, indent=2))
        finally:
            if destination is not None: destination.close()
            if source is not None: source.close()


if __name__ == '__main__':
    main()
