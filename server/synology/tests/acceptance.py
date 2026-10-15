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
                     'SYNOPKG_PKGDEST_VOL':'/volume1', 'SYNOPKG_PKGVAR':'/var/packages/timekeeper/var',
                     'SYNOPKG_PKGINST_TEMP_DIR':'/tmp/timekeeper-incoming-payload',
                     'wizard_database':'timekeeper_fixture', 'wizard_database_role':'timekeeper_fixture_app',
                     'wizard_database_port':'5433',
                     'wizard_public_origin':'https://timekeeper.example.invalid', 'wizard_owner':'existing-owner',
                     'wizard_database_admin':'postgres', 'wizard_database_admin_password':master_password,
                     'wizard_setup_code':code}
        env_file = folder / 'wizard.env'
        env_file.write_text(''.join(key+'='+value+'\n' for key,value in variables.items()))
        env_file.chmod(0o600)
        command('docker','run','-d','--name',name,'--network','none',
                '-e','POSTGRES_PASSWORD_FILE=/run/secrets/test-admin',
                '-e','POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256',
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
                    'mkdir -p /var/packages/timekeeper/scripts /var/packages/timekeeper/target/project '
                    '/volume1/@appdata/timekeeper /var/packages/postgresql/target/bin /var/packages/ContainerManager; '
                    'ln -s /volume1/@appdata/timekeeper /var/packages/timekeeper/var; '
                    'printf \'version="24.0.2-1432"\n\' > /var/packages/ContainerManager/INFO; '
                    'for tool in psql pg_dump pg_restore; do ln -s /usr/lib/postgresql/17/bin/$tool /var/packages/postgresql/target/bin/$tool; done; '
                    'chown -R 10001:10001 /var/packages/timekeeper /volume1/@appdata/timekeeper')
            command('docker','cp',str(binary),name+':/var/packages/timekeeper/scripts/timekeeper-dsm')
            command('docker','cp',str(release),name+':/var/packages/timekeeper/scripts/release.json')
            # DSM stages package.tgz separately from its SPK control scripts.
            # Leave the incoming identity absent first to reproduce the real
            # NAS preinst failure, then supply the payload copy shipped by SPK.
            command('docker','exec',name,'mkdir','-p','/tmp/timekeeper-incoming-payload/scripts')
            blueprint=folder/'compose.yaml'
            blueprint.write_text((ROOT/'server/synology/project/compose.json').read_text().replace('@TIMEKEEPER_IMAGE@',json.loads(release.read_text())['image']))
            command('docker','exec',name,'mkdir','-p','/var/packages/timekeeper/target/project')
            command('docker','cp',str(blueprint),name+':/var/packages/timekeeper/target/project/compose.yaml')
            command('docker','exec',name,'chmod','755','/var/packages/timekeeper/scripts/timekeeper-dsm')

            def helper(action, *arguments, expected=0):
                result = command('docker','exec','--user','10001:10001','--env-file',str(env_file),name,
                                 '/var/packages/timekeeper/scripts/timekeeper-dsm',action,*arguments,check=False)
                if (result.returncode == 0) != (expected == 0):
                    raise AssertionError('Installer action returned unexpected status: '+action)
                for secret in (master_password,code):
                    if secret in result.stdout+result.stderr:
                        raise AssertionError('Installer leaked a credential')
                return result

            def read(relative):
                path=folder/('read-'+secrets.token_hex(4))
                command('docker','cp',name+':/volume1/@appdata/timekeeper/server/'+relative,str(path))
                return path.read_bytes()

            # DSM supplies permanent appdata before postinst. No data-share link
            # exists at this point; its worker would run only at enable/startup.
            command('docker','exec',name,'sh','-ec','test -d /var/packages/timekeeper/var; test ! -e /var/packages/timekeeper/shares')
            missing = helper('validate', expected=1)
            if 'Invalid or incompatible package release identity' not in missing.stderr:
                raise AssertionError('Missing staged identity did not reproduce the DSM preinst failure')
            command('docker','cp',str(release),name+':/tmp/timekeeper-incoming-payload/scripts/release.json')
            wrong_password=secrets.token_urlsafe(32)
            bad_env=folder/'wrong-password.env'
            bad_env.write_text(''.join(key+'='+(wrong_password if key=='wizard_database_admin_password' else value)+'\n' for key,value in variables.items()))
            bad_env.chmod(0o600)
            failure=command('docker','exec','--user','10001:10001','--env-file',str(bad_env),
                            '-e','SYNOPKG_TEMP_LOGFILE=/tmp/timekeeper-dsm-visible-error',name,
                            '/var/packages/timekeeper/scripts/timekeeper-dsm','validate',check=False)
            if failure.returncode==0:
                raise AssertionError('Wrong PostgreSQL password was accepted')
            visible=command('docker','exec','--user','10001:10001',name,'cat','/tmp/timekeeper-dsm-visible-error').stdout
            if 'PostgreSQL rejected the username or password' not in visible:
                raise AssertionError('Wrong PostgreSQL password did not produce a useful DSM error')
            if any(secret in failure.stdout+failure.stderr+visible for secret in (wrong_password,master_password,code)):
                raise AssertionError('Wrong-password diagnostic leaked a credential')
            helper('validate')
            # DSM moves the incoming payload to target before postinst. The
            # staging variable can still point at its now-missing old location.
            command('docker','exec',name,'sh','-ec',
                    'mv /tmp/timekeeper-incoming-payload/scripts /var/packages/timekeeper/target/scripts; '
                    'rmdir /tmp/timekeeper-incoming-payload')
            helper('install')
            command('docker','exec',name,'mkdir','-p','/tmp/timekeeper-incoming-payload/scripts')
            command('docker','cp',str(release),name+':/tmp/timekeeper-incoming-payload/scripts/release.json')
            command('docker','exec',name,'sh','-ec','test ! -e /var/packages/timekeeper/shares; test "$(stat -c %a /var/packages/timekeeper/var/server)" = 700')
            if helper('status',expected=1).returncode!=3:raise AssertionError('Stopped package does not return DSM status 3')
            helper('prestart')
            settings=json.loads(read('settings.json'))
            application_password=read('secrets/database_password').decode().strip()
            if settings['phase']!='READY' or settings['ownerId']!='existing-owner':
                raise AssertionError('Installer lost its explicit owner mapping')
            selected_port=settings['httpPort']
            if not 1024<=selected_port<=65535 or selected_port==5433:
                raise AssertionError('Installer did not select a valid local HTTP port automatically')
            runtime_port=command('docker','exec','--user','10001:10001',name,'cat','/var/packages/timekeeper/target/private/config/http-port').stdout.strip()
            if runtime_port!=str(selected_port):raise AssertionError('Health check does not use the automatically selected port')
            challenge=json.loads(read('config/account-setup.json'))
            if challenge['sha256']!=hashlib.sha256(code.encode()).hexdigest() or code in json.dumps(challenge):
                raise AssertionError('Installer retained an invalid bootstrap challenge')
            snapshot={key:read(key) for key in ('settings.json','secrets/database_password','config/tokens.json','config/account-setup.json')}
            helper('validate'); helper('install')
            if json.loads(read('settings.json'))['httpPort']!=selected_port:
                raise AssertionError('Reinstallation changed the automatically selected port')
            for key in ('secrets/database_password','config/tokens.json'):
                if read(key)!=snapshot[key]:
                    raise AssertionError('Reinstallation replaced retained credentials')
            role=command('docker','exec',name,'psql','-U','postgres','-p','5433','-Atc',
                         "SELECT concat_ws('|',rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls) FROM pg_roles WHERE rolname='timekeeper_fixture_app'").stdout.strip()
            if role!='f|f|f|f|f':
                raise AssertionError('Application role has administrative privileges')
            command('docker','exec',name,'psql','-U','postgres','-p','5433','-d','timekeeper_fixture','-c',
                    "SET ROLE timekeeper_fixture_app; CREATE TABLE tk_schema(id INTEGER PRIMARY KEY,schema_version INTEGER,state TEXT); INSERT INTO tk_schema VALUES(1,2,'READY'); CREATE TABLE preserved_fixture(value TEXT); INSERT INTO preserved_fixture VALUES('owned private data');")
            # Real DSM upgrade order removes the old var/target links before
            # incoming preinst, without supplying fresh-install wizard fields.
            original_variables=dict(variables)
            variables={key:value for key,value in variables.items() if not key.startswith('wizard_')}
            variables['SYNOPKG_PKG_STATUS']='UPGRADE'
            env_file.write_text(''.join(key+'='+value+'\n' for key,value in variables.items()))
            command('docker','exec',name,'sh','-ec',
                    'unlink /var/packages/timekeeper/var; rm -r /var/packages/timekeeper/target')
            retained_wizard=helper('wizard').stdout
            if 'wizard_database_admin_password' in retained_wizard or 'Retained Timekeeper installation' not in retained_wizard:
                raise AssertionError('Upgrade without package links did not find retained configuration')
            helper('validate')
            command('docker','exec',name,'test','!','-e','/var/packages/timekeeper/var')
            variables['SYNOPKG_PKGDEST_VOL']='/volume2'
            env_file.write_text(''.join(key+'='+value+'\n' for key,value in variables.items()))
            missing_state=helper('validate',expected=1)
            if 'Cannot locate retained Timekeeper appdata' not in missing_state.stderr:
                raise AssertionError('Upgrade without retained state fell back to fresh provisioning')
            variables['SYNOPKG_PKGDEST_VOL']='/volume1'
            env_file.write_text(''.join(key+'='+value+'\n' for key,value in variables.items()))
            command('docker','exec',name,'sh','-ec',
                    'ln -s /volume1/@appdata/timekeeper /var/packages/timekeeper/var; '
                    'mkdir -p /var/packages/timekeeper/target/project; '
                    'chown -R 10001:10001 /var/packages/timekeeper/target')
            command('docker','cp',str(blueprint),name+':/var/packages/timekeeper/target/project/compose.yaml')
            helper('install');helper('upgrade');helper('prestart')
            after_upgrade=json.loads(read('settings.json'))
            for key in ('installationId','database','role','ownerId','httpPort','publicOrigin'):
                if after_upgrade[key]!=settings[key]:raise AssertionError('Upgrade changed retained '+key)
            if read('secrets/database_password').decode().strip()!=application_password:
                raise AssertionError('Upgrade after link removal replaced the database credential')
            value=command('docker','exec',name,'psql','-U','postgres','-p','5433','-d','timekeeper_fixture','-Atc','SELECT value FROM preserved_fixture').stdout.strip()
            if value!='owned private data':raise AssertionError('Upgrade after link removal changed native data')
            variables=original_variables
            # First setup can expire before the user reaches the web client. Reinstall
            # renews it and invalidates the earlier pending code without master access.
            command('docker','exec',name,'psql','-U','postgres','-p','5433','-d','timekeeper_fixture','-c',
                    "SET ROLE timekeeper_fixture_app; CREATE TABLE tk_auth_control(id INTEGER PRIMARY KEY,bootstrap_completed BOOLEAN); INSERT INTO tk_auth_control VALUES(1,FALSE); CREATE TABLE tk_auth_challenge(digest TEXT,purpose TEXT,consumed BOOLEAN); INSERT INTO tk_auth_challenge VALUES('old-code','SETUP',FALSE);")
            code=secrets.token_urlsafe(32)
            variables['wizard_setup_code']=code
            variables.pop('wizard_database_admin_password')
            env_file.write_text(''.join(key+'='+value+'\n' for key,value in variables.items()))
            helper('validate');helper('install')
            if json.loads(read('config/account-setup.json'))['sha256']!=hashlib.sha256(code.encode()).hexdigest():
                raise AssertionError('Reinstallation failed to renew incomplete setup')
            consumed=command('docker','exec',name,'psql','-U','postgres','-p','5433','-d','timekeeper_fixture','-Atc',"SELECT consumed FROM tk_auth_challenge WHERE digest='old-code'").stdout.strip()
            if consumed!='t': raise AssertionError('Earlier setup code was not invalidated')
            command('docker','exec',name,'psql','-U','postgres','-p','5433','-d','timekeeper_fixture','-c',"UPDATE tk_auth_control SET bootstrap_completed=TRUE")
            completed=read('config/account-setup.json')
            variables['wizard_setup_code']=secrets.token_urlsafe(32)
            env_file.write_text(''.join(key+'='+value+'\n' for key,value in variables.items()))
            helper('install')
            if read('config/account-setup.json')!=completed:
                raise AssertionError('Reinstallation reopened completed administrator setup')
            variables['wizard_database_admin_password']=master_password
            env_file.write_text(''.join(key+'='+value+'\n' for key,value in variables.items()))
            settings=json.loads(read('settings.json'));settings['phase']='PROVISIONING'
            pending=folder/'pending.json';pending.write_text(json.dumps(settings));pending.chmod(0o600)
            command('docker','cp',str(pending),name+':/var/packages/timekeeper/var/server/settings.json')
            command('docker','exec',name,'chown','10001:10001','/var/packages/timekeeper/var/server/settings.json')
            helper('validate'); helper('install')
            if read('secrets/database_password').decode().strip()!=application_password:
                raise AssertionError('Interrupted provisioning replaced the application credential')
            value=command('docker','exec',name,'psql','-U','postgres','-p','5433','-d','timekeeper_fixture','-Atc','SELECT value FROM preserved_fixture').stdout.strip()
            if value!='owned private data':
                raise AssertionError('Repair changed retained data')
            backup_path=helper('backup').stdout.strip()
            command('docker','exec',name,'psql','-U','postgres','-p','5433','-d','timekeeper_fixture','-c',"UPDATE preserved_fixture SET value='must disappear after restore'")
            helper('restore',backup_path)
            value=command('docker','exec',name,'psql','-U','postgres','-p','5433','-d','timekeeper_fixture','-Atc','SELECT value FROM preserved_fixture').stdout.strip()
            if value!='owned private data':raise AssertionError('Native backup restore lost retained data')
            # A separate malformed backup is refused without replacing restored data.
            command('docker','exec',name,'sh','-ec',"printf corrupt > /var/packages/timekeeper/var/server/backups/corrupt.tar; chown 10001:10001 /var/packages/timekeeper/var/server/backups/corrupt.tar")
            helper('restore','/var/packages/timekeeper/var/server/backups/corrupt.tar',expected=1)
            log=command('docker','logs',name).stdout+command('docker','logs',name).stderr
            if any(secret in log for secret in (master_password,application_password,code)):
                raise AssertionError('PostgreSQL statement log contains a plaintext credential')
            print(json.dumps({'postgresql':args.postgres_image,'architecture':architecture,
                              'unprivilegedInstaller':True,'dedicatedNonSuperuser':True,'permanentAppdataBeforeResourceStartup':True,
                              'upgradeSurvivesRemovedPackageLinks':True,'missingUpgradeStateRefused':True,
                              'reinstallPreservesCredentials':True,'renewIncompleteSetupWithoutResettingAccounts':True,'interruptedProvisioningPreservesData':True,
                              'nativeBackupRestore':True,'corruptRestoreRefused':True,'noPlaintextCredentialsInStatementLog':True}))
        finally:
            command('docker','rm','-f','-v',name,check=False)


if __name__ == '__main__':
    main()
