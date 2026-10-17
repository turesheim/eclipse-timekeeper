#!/usr/bin/env python3
"""Verify the real, unprivileged schema-2-to-3 package upgrade and interrupted retries."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import tarfile
import tempfile
import time
from acceptance import command, ROOT
from migration_acceptance import fixture_sql, quote

DB = ROOT / 'server/app/src/main/resources/db'
TABLES = ('tk_owner','tk_resource','tk_relation','tk_external_reference','tk_active',
          'tk_receipt','tk_change','tk_account','tk_auth_control','tk_auth_challenge',
          'tk_api_token','tk_auth_session','tk_auth_throttle')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres-image', default='postgres:17.11-bookworm')
    args = parser.parse_args()
    architecture = command('docker','image','inspect','--format','{{.Architecture}}',args.postgres_image).stdout.strip()
    with tempfile.TemporaryDirectory(prefix='timekeeper-schema-upgrade-') as temporary:
        folder = Path(temporary)
        binary = folder/'timekeeper-dsm'
        command('go','-C',str(ROOT/'server/synology/installer'),'build','-o',str(binary),'.',
                environment={**os.environ,'GOOS':'linux','GOARCH':architecture,'CGO_ENABLED':'0',
                             'GOCACHE':str(ROOT/'server/target/schema-upgrade-go-cache')})
        for interruption in ('before-commit','after-commit'):
            container = 'timekeeper-schema-upgrade-'+secrets.token_hex(4)
            password = secrets.token_urlsafe(32)
            command('docker','run','-d','--name',container,'--network','none',
                    '-e','POSTGRES_PASSWORD='+secrets.token_urlsafe(32),args.postgres_image,'postgres','-p','5433')
            try:
                for _ in range(100):
                    if command('docker','exec',container,'pg_isready','-h','127.0.0.1','-U','postgres','-p','5433',check=False).returncode==0:break
                    time.sleep(.1)
                else:raise RuntimeError('Isolated PostgreSQL did not start')
                def sql(text,database='timekeeper_fixture'):
                    return command('docker','exec','-i',container,'psql','-X','-q','-A','-t','-U','postgres','-p','5433','-d',database,'-v','ON_ERROR_STOP=1',input=text).stdout.strip()
                sql("CREATE ROLE timekeeper_fixture_app LOGIN PASSWORD "+quote(password)+"; CREATE DATABASE timekeeper_fixture OWNER timekeeper_fixture_app;",'postgres')
                sql('SET ROLE timekeeper_fixture_app; '+fixture_sql(2))
                fingerprints = '\n'.join("SELECT '"+name+"|'||count(*)||'|'||COALESCE(md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY md5(row_to_json(t)::text) COLLATE \"C\")),'empty') FROM "+name+' t;' for name in TABLES)
                before = sql(fingerprints)
                command('docker','exec',container,'sh','-ec',
                    'mkdir -p /var/packages/timekeeper/scripts/migration /var/packages/timekeeper/target/project '
                    '/var/packages/timekeeper/var/server/config /var/packages/timekeeper/var/server/secrets '
                    '/var/packages/timekeeper/var/server/backups /var/packages/timekeeper/target/private/config '
                    '/var/packages/timekeeper/target/private/secrets /var/packages/postgresql/target/bin /var/packages/ContainerManager; '
                    'for tool in psql pg_dump pg_restore; do ln -s /usr/lib/postgresql/17/bin/$tool /var/packages/postgresql/target/bin/$tool; done; '
                    'printf \'version="24.0.2-1432"\n\' > /var/packages/ContainerManager/INFO')
                commit='a'*40; image='ghcr.io/turesheim/eclipse-timekeeper-server:sha-'+commit
                settings={'format':1,'installationId':secrets.token_urlsafe(32),'database':'timekeeper_fixture','role':'timekeeper_fixture_app',
                          'databasePort':5433,'httpPort':18098,'publicOrigin':'https://timekeeper.example.invalid','phase':'READY',
                          'deployedAt':'2026-10-17T00:00:00Z','release':{'commit':commit,'image':image,'serverSchema':2}}
                inputs={
                    'settings.json':json.dumps(settings), 'config/tokens.json':'[]',
                    'config/account-setup.json':json.dumps({'sha256':hashlib.sha256(secrets.token_bytes(32)).hexdigest(),'purpose':'SETUP','expiresAt':'2027-01-01T00:00:00Z'}),
                    'secrets/database_password':password,
                }
                for name,value in inputs.items():
                    file=folder/('fixture-'+secrets.token_hex(4));file.write_text(value);file.chmod(0o600)
                    command('docker','cp',str(file),container+':/var/packages/timekeeper/var/server/'+name)
                release=folder/'release.json';release.write_text(json.dumps({'commit':commit,'image':image,'serverSchema':3}))
                blueprint=folder/'compose.yaml';blueprint.write_text((ROOT/'server/synology/project/compose.json').read_text().replace('@TIMEKEEPER_IMAGE@',image))
                command('docker','cp',str(binary),container+':/var/packages/timekeeper/scripts/timekeeper-dsm')
                command('docker','cp',str(release),container+':/var/packages/timekeeper/scripts/release.json')
                command('docker','exec',container,'mkdir','-p','/tmp/timekeeper-incoming/package/scripts')
                command('docker','cp',str(release),container+':/tmp/timekeeper-incoming/package/scripts/release.json')
                command('docker','cp',str(blueprint),container+':/var/packages/timekeeper/target/project/compose.yaml')
                for name in ('postgresql-shape.sql','schema-v2-postgresql.shape','schema-v3-postgresql.shape','recovery-v3.sql'):
                    command('docker','cp',str(DB/name),container+':/var/packages/timekeeper/scripts/migration/'+name)
                command('docker','exec',container,'chown','-R','10001:10001','/var/packages/timekeeper')
                def helper(action,expected=0,argument=None):
                    args=['docker','exec','--user','10001:10001','-e','SYNOPKG_PKGNAME=timekeeper',
                          '-e','SYNOPKG_PKGINST_TEMP_DIR=/tmp/timekeeper-incoming/package',
                          '-e','SYNOPKG_PKGDEST=/var/packages/timekeeper/target',container,
                          '/var/packages/timekeeper/scripts/timekeeper-dsm',action]
                    if argument is not None:args.append(argument)
                    result=command(*args,check=False)
                    if result.returncode!=expected:raise AssertionError('Unexpected installer result: '+action)
                    if password in result.stdout+result.stderr:raise AssertionError('Installer leaked a fixture password')
                    return result
                missing=helper('validate',expected=1)
                assert '/tmp/timekeeper-incoming/package/scripts/migration/postgresql-shape.sql' in missing.stderr
                assert sql('SELECT schema_version FROM tk_schema')=='2'
                assert sql(fingerprints)==before
                assert command('docker','exec',container,'find','/var/packages/timekeeper/var/server/backups','-type','f').stdout.strip()==''
                command('docker','exec',container,'cp','-R','/var/packages/timekeeper/scripts/migration','/tmp/timekeeper-incoming/package/scripts/migration')
                helper('validate')
                assert sql('SELECT schema_version FROM tk_schema')=='2'
                assert command('docker','exec',container,'find','/var/packages/timekeeper/var/server/backups','-type','f').stdout.strip()==''
                # A canonical-shape failure must roll all DDL/data back and retain the backup/journal.
                damaged=folder/'damaged.sql';damaged.write_text((DB/'recovery-v3.sql').read_text()+'\nCREATE TABLE unexpected_upgrade_table(id INT);\n')
                command('docker','cp',str(damaged),container+':/var/packages/timekeeper/scripts/migration/recovery-v3.sql')
                helper('upgrade',expected=1)
                assert sql('SELECT schema_version FROM tk_schema')=='2'
                assert sql(fingerprints)==before
                assert sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name LIKE 'tk_recovery_%'")=='0'
                helper('prestart',expected=1)
                journal=json.loads(command('docker','exec',container,'cat','/var/packages/timekeeper/var/server/config/maintenance-pending.json').stdout)
                assert journal['operation']=='schema-upgrade'
                archive=folder/('backup-'+interruption+'.tar')
                command('docker','cp',container+':'+journal['archivePath'],str(archive))
                assert hashlib.sha256(archive.read_bytes()).hexdigest()==journal['archiveSha256']
                with tarfile.open(archive) as backup:
                    manifest=json.load(backup.extractfile('manifest.json'));assert manifest['serverSchema']==2
                command('docker','cp',str(DB/'recovery-v3.sql'),container+':/var/packages/timekeeper/scripts/migration/recovery-v3.sql')
                if interruption=='after-commit':
                    # Reproduce a process ending after the DB transaction but before settings publication.
                    sql('SET ROLE timekeeper_fixture_app; BEGIN; '+(DB/'recovery-v3.sql').read_text()+"UPDATE tk_schema SET schema_version=3; COMMIT;")
                    helper('prestart',expected=1)
                helper('validate');helper('upgrade');helper('prestart')
                assert sql('SELECT schema_version FROM tk_schema')=='3'
                assert sql((DB/'postgresql-shape.sql').read_text())==(DB/'schema-v3-postgresql.shape').read_text().strip()
                assert sql(fingerprints)==before
                retained=json.loads(command('docker','exec',container,'cat','/var/packages/timekeeper/var/server/settings.json').stdout)
                assert retained['release']['serverSchema']==3
                command('docker','exec',container,'test','!','-e','/var/packages/timekeeper/var/server/config/maintenance-pending.json')
                backups=command('docker','exec',container,'find','/var/packages/timekeeper/var/server/backups','-type','f').stdout.splitlines()
                assert backups==[journal['archivePath']], 'Retry must reuse the original backup'
                if interruption=='before-commit':
                    # Retained operator mail settings survive prestart, backup and
                    # restore; SMTP credentials stay out of generated properties.
                    mail=folder/'mail.properties';mail.write_text('timekeeper.mail.enabled=false\nquarkus.mailer.host=smtp.example.invalid\ntimekeeper.auth.origin-epoch=4\n')
                    smtp=folder/'smtp_password';smtp.write_text('synthetic SMTP credential for retention test')
                    for source,destination in ((mail,'config/mail.properties'),(smtp,'secrets/smtp_password')):
                        command('docker','cp',str(source),container+':/var/packages/timekeeper/var/server/'+destination)
                        command('docker','exec',container,'chown','10001:10001','/var/packages/timekeeper/var/server/'+destination)
                    helper('prestart')
                    for source,destination in ((mail,'config/mail.properties'),(smtp,'secrets/smtp_password')):
                        assert command('docker','exec',container,'cat','/var/packages/timekeeper/target/private/'+destination).stdout==source.read_text()
                    sql("SET ROLE timekeeper_fixture_app; UPDATE tk_recovery_control SET origin='https://timekeeper.example.invalid',epoch=4; INSERT INTO tk_recovery_address VALUES('owner-a','PRIMARY','verified@example.org',NULL,9);")
                    recovery_before=sql('SELECT row_to_json(t)::text FROM tk_recovery_address t; SELECT row_to_json(t)::text FROM tk_recovery_control t;')
                    native_backup=helper('backup').stdout.strip()
                    command('docker','exec',container,'sh','-ec',"printf '%s' 'changed mail configuration' > /var/packages/timekeeper/var/server/config/mail.properties; printf '%s' 'changed SMTP credential' > /var/packages/timekeeper/var/server/secrets/smtp_password")
                    sql("UPDATE tk_account SET display_name='changed after backup'; UPDATE tk_recovery_address SET verified_address='changed@example.org';")
                    helper('restore',argument=native_backup)
                    assert sql(fingerprints)==before
                    assert sql('SELECT row_to_json(t)::text FROM tk_recovery_address t; SELECT row_to_json(t)::text FROM tk_recovery_control t;')==recovery_before
                    for source,destination in ((mail,'config/mail.properties'),(smtp,'secrets/smtp_password')):
                        assert command('docker','exec',container,'cat','/var/packages/timekeeper/var/server/'+destination).stdout==source.read_text()
                    # An old backup must restore into the current package without leftover new tables
                    # or partial commits; the original schema-2 accounts become canonical schema 3.
                    helper('restore',argument=journal['archivePath'])
                    assert sql(fingerprints)==before
                    assert sql('SELECT schema_version FROM tk_schema')=='3'
                    assert sql('SELECT count(*) FROM tk_recovery_address')=='0'
                    assert sql((DB/'postgresql-shape.sql').read_text())==(DB/'schema-v3-postgresql.shape').read_text().strip()
                    for source,destination in ((mail,'config/mail.properties'),(smtp,'secrets/smtp_password')):
                        assert command('docker','exec',container,'cat','/var/packages/timekeeper/var/server/'+destination).stdout==source.read_text(), 'Legacy backup must retain current mail settings'
                print(json.dumps({'scenario':interruption,'privateDataAndCredentialsPreserved':True,'transactionRollback':True,'retryCompleted':True,'preUpgradeBackupRetained':True,'nativeBackupAndOldBackupRestore':interruption=='before-commit'}),flush=True)
            finally:command('docker','rm','-f',container)


if __name__=='__main__':main()
