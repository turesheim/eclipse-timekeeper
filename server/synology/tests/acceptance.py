#!/usr/bin/env python3
"""Exercise the unprivileged installer with real PostgreSQL in an isolated DSM layout.

This verifies installer/database behavior, not Package Center or real DSM workers.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[3]


def command(*args, input=None, check=True, environment=None):
    result = subprocess.run(args, input=input, text=True, capture_output=True, env=environment)
    if check and result.returncode:
        # Commands and stderr can contain credentials. Keep failure reporting bounded.
        raise RuntimeError('Installer acceptance command failed: ' + args[0])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres-image', default='postgres:17.11-bookworm')
    args = parser.parse_args()
    command('docker', 'pull', args.postgres_image)
    architecture = command('docker', 'image', 'inspect', '--format', '{{.Architecture}}', args.postgres_image).stdout.strip()
    if architecture not in ('amd64', 'arm64'):
        raise ValueError('Expected an x86_64 or arm64 test image')
    name = 'timekeeper-dsm-installer-' + secrets.token_hex(4)
    with tempfile.TemporaryDirectory(prefix='timekeeper-dsm-') as temporary:
        folder = Path(temporary)
        master_password, code = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        admin_file = folder / 'postgres-password'
        admin_file.write_text(master_password)
        admin_file.chmod(0o444)
        commit = command('git', '-C', str(ROOT), 'rev-parse', 'HEAD').stdout.strip()
        release = folder / 'release.json'
        release.write_text(json.dumps({'commit':commit, 'image':'ghcr.io/turesheim/eclipse-timekeeper-server:sha-'+commit, 'serverSchema':2}))
        binary = folder / 'timekeeper-dsm'
        command('go', '-C', str(ROOT/'server/synology/installer'), 'build', '-trimpath', '-o', str(binary), '.',
                environment={**os.environ, 'GOOS':'linux', 'GOARCH':architecture, 'CGO_ENABLED':'0', 'GOCACHE':str(folder/'go-cache')})
        variables = {'SYNOPKG_PKGNAME':'timekeeper', 'SYNOPKG_PKGDEST':'/var/packages/timekeeper/target',
                     'wizard_database':'timekeeper_fixture', 'wizard_database_role':'timekeeper_fixture_app',
                     'wizard_database_port':'5433', 'wizard_http_port':'8098',
                     'wizard_public_origin':'https://timekeeper.example.invalid', 'wizard_owner':'existing-owner',
                     'wizard_database_admin':'postgres', 'wizard_database_admin_password':master_password,
                     'wizard_setup_code':code}
        env_file = folder / 'wizard.env'
        env_file.write_text(''.join(key+'='+value+'\n' for key,value in variables.items()))
        env_file.chmod(0o600)
        command('docker','run','-d','--name',name,'--network','none',
                '-e','POSTGRES_PASSWORD_FILE=/run/secrets/test-admin',
                '-v',str(admin_file)+':/run/secrets/test-admin:ro',args.postgres_image,
                'postgres','-p','5433','-c','log_statement=all')
        try:
            for _ in range(100):
                if command('docker','exec',name,'pg_isready','-U','postgres','-p','5433',check=False).returncode == 0:
                    break
                time.sleep(.1)
            else:
                raise RuntimeError('Isolated PostgreSQL did not become ready')
            command('docker','exec',name,'sh','-ec',
                    'mkdir -p /var/packages/timekeeper/scripts /var/packages/timekeeper/target '
                    '/var/packages/timekeeper/shares/timekeeper-data /var/packages/postgresql/target/bin; '
                    'ln -s /usr/lib/postgresql/17/bin/psql /var/packages/postgresql/target/bin/psql; '
                    'chown -R 10001:10001 /var/packages/timekeeper')
            command('docker','cp',str(binary),name+':/var/packages/timekeeper/scripts/timekeeper-dsm')
            command('docker','cp',str(release),name+':/var/packages/timekeeper/scripts/release.json')
            command('docker','exec',name,'chmod','755','/var/packages/timekeeper/scripts/timekeeper-dsm')

            def helper(action, expected=0):
                result = command('docker','exec','--user','10001:10001','--env-file',str(env_file),name,
                                 '/var/packages/timekeeper/scripts/timekeeper-dsm',action,check=False)
                if (result.returncode == 0) != (expected == 0):
                    raise AssertionError('Installer action returned unexpected status: '+action)
                for secret in (master_password,code):
                    if secret in result.stdout+result.stderr:
                        raise AssertionError('Installer leaked a credential')
                return result

            def read(relative):
                path=folder/('read-'+secrets.token_hex(4))
                command('docker','cp',name+':/var/packages/timekeeper/shares/timekeeper-data/server/'+relative,str(path))
                return path.read_bytes()

            helper('validate'); helper('install')
            settings=json.loads(read('settings.json'))
            application_password=read('secrets/database_password').decode().strip()
            if settings['phase']!='READY' or settings['ownerId']!='existing-owner':
                raise AssertionError('Installer lost its explicit owner mapping')
            challenge=json.loads(read('config/account-setup.json'))
            if challenge['sha256']!=hashlib.sha256(code.encode()).hexdigest() or code in json.dumps(challenge):
                raise AssertionError('Installer retained an invalid bootstrap challenge')
            snapshot={key:read(key) for key in ('settings.json','secrets/database_password','config/tokens.json','config/account-setup.json')}
            helper('validate'); helper('install')
            for key in ('secrets/database_password','config/tokens.json','config/account-setup.json'):
                if read(key)!=snapshot[key]:
                    raise AssertionError('Reinstallation replaced retained credentials')
            role=command('docker','exec',name,'psql','-U','postgres','-p','5433','-Atc',
                         "SELECT concat_ws('|',rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls) FROM pg_roles WHERE rolname='timekeeper_fixture_app'").stdout.strip()
            if role!='f|f|f|f|f':
                raise AssertionError('Application role has administrative privileges')
            command('docker','exec',name,'psql','-U','postgres','-p','5433','-d','timekeeper_fixture','-c',
                    "CREATE TABLE preserved_fixture(value TEXT); INSERT INTO preserved_fixture VALUES('owned private data');")
            settings=json.loads(read('settings.json'));settings['phase']='PROVISIONING'
            pending=folder/'pending.json';pending.write_text(json.dumps(settings));pending.chmod(0o600)
            command('docker','cp',str(pending),name+':/var/packages/timekeeper/shares/timekeeper-data/server/settings.json')
            command('docker','exec',name,'chown','10001:10001','/var/packages/timekeeper/shares/timekeeper-data/server/settings.json')
            helper('validate'); helper('install')
            if read('secrets/database_password').decode().strip()!=application_password:
                raise AssertionError('Interrupted provisioning replaced the application credential')
            value=command('docker','exec',name,'psql','-U','postgres','-p','5433','-d','timekeeper_fixture','-Atc','SELECT value FROM preserved_fixture').stdout.strip()
            if value!='owned private data':
                raise AssertionError('Repair changed retained data')
            log=command('docker','logs',name).stdout+command('docker','logs',name).stderr
            if any(secret in log for secret in (master_password,application_password,code)):
                raise AssertionError('PostgreSQL statement log contains a plaintext credential')
            print(json.dumps({'postgresql':args.postgres_image,'architecture':architecture,
                              'unprivilegedInstaller':True,'dedicatedNonSuperuser':True,
                              'reinstallPreservesCredentials':True,'interruptedProvisioningPreservesData':True,
                              'noPlaintextCredentialsInStatementLog':True}))
        finally:
            command('docker','rm','-f','-v',name,check=False)


if __name__ == '__main__':
    main()
