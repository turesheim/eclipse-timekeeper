#!/usr/bin/env python3
"""Verify the actual PostgreSQL 18 data-only export and unprivileged native-17 importer."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import sys
import tempfile
import tarfile
import io
import time
import uuid
from acceptance import command, ROOT
sys.path.insert(0,str(ROOT/'server/deploy'))
spec=importlib.util.spec_from_file_location('synology_export',ROOT/'server/deploy/export-synology.py')
exporter=importlib.util.module_from_spec(spec);spec.loader.exec_module(exporter)
DB=ROOT/'server/app/src/main/resources/db'


def quote(value):return "'"+value.replace("'","''")+"'"


def fixture_sql():
    sql=(DB/'schema-v1-postgresql.sql').read_text()+(DB/'auth-v2.sql').read_text()
    sql+="UPDATE tk_schema SET schema_version=2,state='READY'; UPDATE tk_auth_control SET bootstrap_completed=TRUE;\n"
    now='2026-10-14T12:34:56.123456789Z'
    # Two owners share UUIDs deliberately: migration must never collapse identities.
    ids={name:str(uuid.uuid4()) for name in ('CLIENT','PROJECT','TASK','ACTIVITY','WORK_SESSION','LABEL')}
    for index,owner in enumerate(('owner-a','owner-β')):
        salt=secrets.token_bytes(16)
        encoded=lambda value:base64.urlsafe_b64encode(value).decode().rstrip('=')
        password='pbkdf2-sha256$600000$'+encoded(salt)+'$'+encoded(hashlib.pbkdf2_hmac('sha256',b'migration password fixture',salt,600000))
        sql+=f"INSERT INTO tk_owner VALUES({quote(owner)},'Europe/Oslo',7,9007199254740993,{quote(secrets.token_hex(32))});\n"
        sql+=f"INSERT INTO tk_account VALUES({quote(owner)},'account{index}',{quote(password)},'Private account','ENABLED',{str(index==0)},TRUE,8);\n"
        for kind,identifier in ids.items():
            payload={'ownerId':owner,'id':identifier,'version':9,'summary':'Norwegian æøå and β; tab\tnewline\nquote \' slash\\', 'start':now,'end':None}
            if kind in ('PROJECT','WORK_SESSION'):payload['clientId']=ids['CLIENT']
            if kind=='WORK_SESSION':payload['title']='Private work session'
            sql+=f"INSERT INTO tk_resource VALUES({quote(owner)},'{kind}','{identifier}',9,{quote(json.dumps(payload,ensure_ascii=False))});\n"
        for from_type,kind,to_type in (('TASK','project','PROJECT'),('PROJECT','client','CLIENT'),('WORK_SESSION','client','CLIENT'),('ACTIVITY','task','TASK'),('ACTIVITY','workSession','WORK_SESSION'),('ACTIVITY','label','LABEL')):
            sql+=f"INSERT INTO tk_relation VALUES({quote(owner)},'{from_type}','{ids[from_type]}','{kind}','{to_type}','{ids[to_type]}');\n"
        sql+=f"INSERT INTO tk_external_reference VALUES({quote(owner)},'mylyn','https://example.invalid/repository','issue-123','TASK','{ids['TASK']}',{quote(hashlib.sha256(owner.encode()).hexdigest())});\n"
        sql+=f"INSERT INTO tk_active VALUES({quote(owner)},'ACTIVITY','{ids['ACTIVITY']}');\n"
        sql+=f"INSERT INTO tk_receipt VALUES({quote(owner)},'{uuid.uuid4()}',{'%s'},201,{quote(json.dumps({'id':ids['ACTIVITY'],'start':now}))},'\"9\"','/api/v1/activities/{ids['ACTIVITY']}',{quote(now)});\n" % quote('f'*64)
        sql+=f"INSERT INTO tk_change VALUES({quote(owner)},9007199254740993,'ACTIVITY','{ids['ACTIVITY']}','CREATED',{quote(now)});\n"
        token=secrets.token_hex(32)
        sql+=f"INSERT INTO tk_api_token VALUES('{token}','{uuid.uuid4()}',{quote(owner)},'Eclipse','READ_WRITE',NULL,FALSE,'MANAGED',{quote(now)},NULL,3,NULL);\n"
        sql+=f"INSERT INTO tk_auth_session VALUES('{secrets.token_hex(32)}',{quote(owner)},'{secrets.token_hex(32)}','READ_WRITE',{quote(now)},'2030-01-01T00:00:00Z',{quote(now)},TRUE,'{token}');\n"
        sql+=f"INSERT INTO tk_auth_challenge VALUES('{secrets.token_hex(32)}','RECOVERY',{quote(owner)},'2030-01-01T00:00:00Z',TRUE);\n"
    sql+=f"INSERT INTO tk_auth_throttle VALUES('{secrets.token_hex(32)}',4,{quote(now)});\n"
    return sql


def main():
    suffix=secrets.token_hex(4);source='timekeeper-migrate18-'+suffix;target='timekeeper-migrate17-'+suffix
    names=[]
    with tempfile.TemporaryDirectory(prefix='timekeeper-native-migration-') as temporary:
        folder=Path(temporary);state=folder/'source-state';(state/'config').mkdir(parents=True)
        tokens=json.dumps([{'ownerId':'owner-β','sha256':secrets.token_hex(32),'permissions':['read','write']}])+'\n'
        (state/'config/tokens.json').write_text(tokens)
        try:
            for name,image,port in ((source,'postgres:18.6-bookworm','5432'),(target,'postgres:17.11-bookworm','5433')):
                command('docker','run','-d','--name',name,'--network','none','-e','POSTGRES_HOST_AUTH_METHOD=trust',image,'postgres','-p',port)
                names.append(name)
                for _ in range(100):
                    if command('docker','exec',name,'pg_isready','-U','postgres','-p',port,check=False).returncode==0:break
                    time.sleep(.1)
                else:raise RuntimeError('Migration fixture did not start')
            command('docker','exec','-i',source,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','postgres',input=fixture_sql())
            def client(program,arguments,*,sql=None,output=None):
                filtered=[];skip=False
                for arg in arguments:
                    if skip:skip=False;continue
                    if arg in ('-h','-U','-d'):skip=True;continue
                    filtered.append(arg)
                import subprocess
                result=subprocess.run(['docker','exec','-i',source,program,*filtered,'-U','postgres','-d','postgres'],
                    input=sql.encode() if sql else None,stdout=output if output else subprocess.PIPE,stderr=subprocess.PIPE)
                if result.returncode:raise RuntimeError('Source PostgreSQL migration operation failed')
                return result.stdout.decode().strip() if output is None else None
            archive=exporter.export(folder/'postgres18-to17.tar',client=client,state=state,db_resources=DB)
            arch=command('docker','image','inspect','--format','{{.Architecture}}','postgres:17.11-bookworm').stdout.strip()
            binary=folder/'timekeeper-dsm'
            command('go','-C',str(ROOT/'server/synology/installer'),'build','-o',str(binary),'.',
                environment={**os.environ,'GOOS':'linux','GOARCH':arch,'CGO_ENABLED':'0','GOCACHE':str(folder/'go-cache')})
            command('docker','exec',target,'sh','-ec',
                'mkdir -p /var/packages/timekeeper/scripts/migration /var/packages/timekeeper/target /var/packages/timekeeper/shares/timekeeper-data /var/packages/postgresql/target/bin; '
                'for tool in psql pg_dump pg_restore; do ln -s /usr/lib/postgresql/17/bin/$tool /var/packages/postgresql/target/bin/$tool; done; '
                'chown -R 10001:10001 /var/packages/timekeeper')
            command('docker','cp',str(binary),target+':/var/packages/timekeeper/scripts/timekeeper-dsm')
            release=folder/'release.json';release.write_text(json.dumps({'commit':'a'*40,'image':'ghcr.io/turesheim/eclipse-timekeeper-server:sha-'+'a'*40,'serverSchema':2}))
            command('docker','cp',str(release),target+':/var/packages/timekeeper/scripts/release.json')
            for file in ('postgresql-shape.sql','schema-v2-postgresql.shape'):
                command('docker','cp',str(DB/file),target+':/var/packages/timekeeper/scripts/migration/'+file)
            env={'SYNOPKG_PKGNAME':'timekeeper','SYNOPKG_PKGDEST':'/var/packages/timekeeper/target','wizard_database':'timekeeper_fixture','wizard_database_role':'timekeeper_fixture_app',
                 'wizard_database_port':'5433','wizard_http_port':'8098','wizard_public_origin':'https://timekeeper.example.invalid','wizard_owner':'',
                 'wizard_database_admin':'postgres','wizard_database_admin_password':'synthetic-trust-only-fixture','wizard_setup_code':secrets.token_urlsafe(32)}
            env_file=folder/'wizard.env';env_file.write_text(''.join(key+'='+value+'\n' for key,value in env.items()));env_file.chmod(0o600)
            def helper(action,*arguments,expected=0):
                result=command('docker','exec','--user','10001:10001','--env-file',str(env_file),target,'/var/packages/timekeeper/scripts/timekeeper-dsm',action,*arguments,check=False)
                if (result.returncode==0)!=(expected==0):raise AssertionError('Native migration action failed: '+action+' '+result.stderr)
                return result
            helper('install')
            canonical="SET ROLE timekeeper_fixture_app; "+(DB/'schema-v1-postgresql.sql').read_text()+(DB/'auth-v2.sql').read_text()+"UPDATE tk_schema SET schema_version=2,state='READY';"
            command('docker','exec','-i',target,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','postgres','-p','5433','-d','timekeeper_fixture',input=canonical)
            archive.chmod(0o644);command('docker','cp',str(archive),target+':/var/packages/timekeeper/shares/timekeeper-data/server/backups/migration.tar')
            native_archive='/var/packages/timekeeper/shares/timekeeper-data/server/backups/migration.tar'
            # A mismatched post-import fingerprint must roll back the entire data
            # transaction. It leaves a durable marker that blocks container startup.
            broken=folder/'mismatched.tar'
            with tarfile.open(archive) as source_tar,tarfile.open(broken,'w') as destination:
                for member in source_tar.getmembers():
                    value=source_tar.extractfile(member).read()
                    if member.name=='manifest.json':
                        manifest=json.loads(value);manifest['tableFingerprints']['tk_resource']='0|empty'
                        value=json.dumps(manifest).encode();member.size=len(value)
                    destination.addfile(member,io.BytesIO(value))
            broken.chmod(0o644);command('docker','cp',str(broken),target+':/var/packages/timekeeper/shares/timekeeper-data/server/backups/mismatched.tar')
            helper('import-postgresql18','/var/packages/timekeeper/shares/timekeeper-data/server/backups/mismatched.tar',expected=1)
            empty=command('docker','exec',target,'psql','-X','-At','-U','postgres','-p','5433','-d','timekeeper_fixture','-c','SELECT count(*) FROM tk_owner').stdout.strip()
            if empty!='0':raise AssertionError('Failed import committed partial data')
            command('docker','exec',target,'test','-f','/var/packages/timekeeper/shares/timekeeper-data/server/config/maintenance-pending.json')
            helper('cancel-empty-import')
            helper('import-postgresql18',native_archive)
            fingerprints=client('psql',['-X','-q','-A','-t'],sql='\n'.join(exporter.fingerprint_sql(table) for table in exporter.TABLES))
            actual=command('docker','exec','-i',target,'psql','-X','-q','-A','-t','-U','postgres','-p','5433','-d','timekeeper_fixture',input='\n'.join(exporter.fingerprint_sql(table) for table in exporter.TABLES)).stdout.strip()
            if actual!=fingerprints:raise AssertionError('Migration changed source data')
            copied=folder/'target-tokens.json';command('docker','cp',target+':/var/packages/timekeeper/shares/timekeeper-data/server/config/tokens.json',str(copied))
            if copied.read_text()!=tokens:raise AssertionError('Migration changed legacy token ownership')
            helper('import-postgresql18',native_archive,expected=1)
            helper('cancel-empty-import',expected=1)
            # Disaster recovery uses a freshly provisioned dedicated role/database;
            # business ownership must not depend on the old PostgreSQL service role.
            native_backup=helper('backup').stdout.strip()
            command('docker','exec','--user','10001:10001',target,'cp',native_backup,'/var/packages/timekeeper/native-recovery.tar')
            previous_password=folder/'previous-password'
            command('docker','cp',target+':/var/packages/timekeeper/shares/timekeeper-data/server/secrets/database_password',str(previous_password))
            command('docker','exec','--user','10001:10001',target,'mv','/var/packages/timekeeper/shares/timekeeper-data/server','/var/packages/timekeeper/shares/timekeeper-data/retained-before-recovery')
            env['wizard_database']='timekeeper_recovered';env['wizard_database_role']='timekeeper_recovered_app'
            env_file.write_text(''.join(key+'='+value+'\n' for key,value in env.items()))
            helper('install')
            recovered_schema="SET ROLE timekeeper_recovered_app; "+(DB/'schema-v1-postgresql.sql').read_text()+(DB/'auth-v2.sql').read_text()+"UPDATE tk_schema SET schema_version=2,state='READY';"
            command('docker','exec','-i',target,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','postgres','-p','5433','-d','timekeeper_recovered',input=recovered_schema)
            helper('restore','/var/packages/timekeeper/native-recovery.tar')
            restored=command('docker','exec','-i',target,'psql','-X','-q','-A','-t','-U','postgres','-p','5433','-d','timekeeper_recovered',input='\n'.join(exporter.fingerprint_sql(table) for table in exporter.TABLES)).stdout.strip()
            if restored!=fingerprints:raise AssertionError('Fresh-installation recovery changed owner data')
            recovered_password=folder/'recovered-password'
            command('docker','cp',target+':/var/packages/timekeeper/shares/timekeeper-data/server/secrets/database_password',str(recovered_password))
            if recovered_password.read_bytes()==previous_password.read_bytes():raise AssertionError('Fresh recovery reused the source service credential')
            previous=command('docker','exec','-i',target,'psql','-X','-q','-A','-t','-U','postgres','-p','5433','-d','timekeeper_fixture',input='\n'.join(exporter.fingerprint_sql(table) for table in exporter.TABLES)).stdout.strip()
            if previous!=fingerprints:raise AssertionError('Recovery changed a database outside its dedicated target')
            print(json.dumps({'source':'postgres:18.6-bookworm' ,'target':'postgres:17.11-bookworm','tablesCompared':len(exporter.TABLES),
                'allTableFingerprintsMatch':True,'twoOwnersWithSharedResourceIds':True,'exactNanosecondTimestampsAndLargeRevisions':True,
                'accountAndTokenSessionHashesPreserved':True,'legacyRegistryPreserved':True,'usedTargetRefused':True,'fingerprintMismatchRollsBackAtomically':True,'emptyFailedImportCanBeCancelled':True,'freshInstallationRestoreWithNewServiceCredential':True,'otherDatabaseUntouched':True}))
        finally:
            for name in names:command('docker','rm','-f','-v',name,check=False)


if __name__=='__main__':main()
