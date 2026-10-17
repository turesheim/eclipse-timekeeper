#!/usr/bin/env python3
"""Verify the actual PostgreSQL 18 data-only export and unprivileged native-17 importer."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
import subprocess
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


def fixture_sql(schema=4):
    if schema not in (2,3,4):raise ValueError('Unsupported fixture schema')
    sql=(DB/'schema-v1-postgresql.sql').read_text()+(DB/'auth-v2.sql').read_text()
    sql+="UPDATE tk_schema SET schema_version=2,state='READY'; UPDATE tk_auth_control SET bootstrap_completed=TRUE;\n"
    now='2026-10-14T12:34:56.123456789Z'
    # Two owners share UUIDs deliberately: migration must never collapse identities.
    ids={name:str(uuid.uuid4()) for name in ('CLIENT','PROJECT','TASK','ACTIVITY','WORK_SESSION','LABEL')}
    for index,owner in enumerate(('owner-a','owner-β')):
        salt=secrets.token_bytes(16)
        encoded=lambda value:base64.urlsafe_b64encode(value).decode().rstrip('=')
        password='pbkdf2-sha256$600000$'+encoded(salt)+'$'+encoded(hashlib.pbkdf2_hmac('sha256',b'migration password fixture',salt,600000))
        # Keep the beyond-JavaScript sequence on a closed interval. The active
        # owner's sequence needs room for legitimate day-boundary writes when
        # this historical fixture is started by the real server on later dates.
        sequence=42 if index==0 else 9007199254740993
        sql+=f"INSERT INTO tk_owner VALUES({quote(owner)},'Europe/Oslo',7,{sequence},{quote(secrets.token_hex(32))});\n"
        sql+=f"INSERT INTO tk_account VALUES({quote(owner)},'account{index}',{quote(password)},'Private account','ENABLED',{str(index==0)},TRUE,8);\n"
        for kind,identifier in ids.items():
            payload={'ownerId':owner,'id':identifier,'version':9,'summary':'Norwegian æøå and β; tab\tnewline\nquote \' slash\\', 'start':now,'end':None}
            if kind in ('CLIENT','PROJECT','LABEL'):payload['name']='Private '+kind.lower()
            if kind in ('CLIENT','LABEL'):payload['color']='#2563eb'
            if kind in ('PROJECT','WORK_SESSION'):payload['clientId']=ids['CLIENT']
            if kind=='TASK':payload.update(projectId=ids['PROJECT'],parentTaskId=None,url=None,externalReferences=[{'providerId':'mylyn','repositoryId':'https://example.invalid/repository','externalId':'issue-123','externalUrl':None}])
            if kind=='ACTIVITY':
                payload.update(taskId=ids['TASK'],projectId=ids['PROJECT'],workSessionId=ids['WORK_SESSION'],labelIds=[ids['LABEL']],manual=True)
                if index!=0:payload['end']='2026-10-14T13:34:56.123456789Z'
            if kind=='WORK_SESSION':payload['title']='Private work session'
            sql+=f"INSERT INTO tk_resource VALUES({quote(owner)},'{kind}','{identifier}',9,{quote(json.dumps(payload,ensure_ascii=False))});\n"
        for from_type,kind,to_type in (('TASK','project','PROJECT'),('PROJECT','client','CLIENT'),('WORK_SESSION','client','CLIENT'),('ACTIVITY','task','TASK'),('ACTIVITY','workSession','WORK_SESSION'),('ACTIVITY','label','LABEL')):
            sql+=f"INSERT INTO tk_relation VALUES({quote(owner)},'{from_type}','{ids[from_type]}','{kind}','{to_type}','{ids[to_type]}');\n"
        sql+=f"INSERT INTO tk_external_reference VALUES({quote(owner)},'mylyn','https://example.invalid/repository','issue-123','TASK','{ids['TASK']}',{quote(hashlib.sha256(owner.encode()).hexdigest())});\n"
        if index==0:sql+=f"INSERT INTO tk_active VALUES({quote(owner)},'ACTIVITY','{ids['ACTIVITY']}');\n"
        sql+=f"INSERT INTO tk_receipt VALUES({quote(owner)},'{uuid.uuid4()}',{'%s'},201,{quote(json.dumps({'id':ids['ACTIVITY'],'start':now}))},'\"9\"','/api/v1/activities/{ids['ACTIVITY']}',{quote(now)});\n" % quote('f'*64)
        sql+=f"INSERT INTO tk_change VALUES({quote(owner)},{sequence},'ACTIVITY','{ids['ACTIVITY']}','CREATED',{quote(now)});\n"
        token=secrets.token_hex(32)
        sql+=f"INSERT INTO tk_api_token VALUES('{token}','{uuid.uuid4()}',{quote(owner)},'Eclipse','READ_WRITE',NULL,FALSE,'MANAGED',{quote(now)},NULL,3,NULL);\n"
        sql+=f"INSERT INTO tk_auth_session VALUES('{secrets.token_hex(32)}',{quote(owner)},'{secrets.token_hex(32)}','READ_WRITE',{quote(now)},'2030-01-01T00:00:00Z',{quote(now)},TRUE,'{token}');\n"
        sql+=f"INSERT INTO tk_auth_challenge VALUES('{secrets.token_hex(32)}','RECOVERY',{quote(owner)},'2030-01-01T00:00:00Z',TRUE);\n"
    sql+=f"INSERT INTO tk_auth_throttle VALUES('{secrets.token_hex(32)}',4,{quote(now)});\n"
    if schema>=3:
        sql+=(DB/'recovery-v3.sql').read_text()
        sql+="UPDATE tk_schema SET schema_version=3; UPDATE tk_recovery_control SET origin='https://timekeeper.example.invalid',epoch=7;\n"
        for index,owner in enumerate(('owner-a','owner-β')):
            sql+=f"INSERT INTO tk_recovery_address VALUES({quote(owner)},'PRIMARY','account{index}@example.invalid','replacement{index}@example.invalid',5);\n"
            sql+=f"INSERT INTO tk_recovery_address VALUES({quote(owner)},'BACKUP','backup{index}@example.invalid',NULL,2);\n"
            sql+=f"INSERT INTO tk_recovery_link VALUES('{secrets.token_hex(32)}',{quote(owner)},'PRIMARY','replacement{index}@example.invalid','VERIFY',7,{quote(now)},'2030-01-01T00:00:00Z',FALSE);\n"
    if schema>=4:
        sql+=(DB/'passkeys-v4.sql').read_text()
        sql+="UPDATE tk_schema SET schema_version=4;\n"
        for index,owner in enumerate(('owner-a','owner-β')):
            # Only public COSE material enters the SQL/archive. Private fixture
            # keys stay in this process and are never printed or committed.
            private=subprocess.run(['openssl','genpkey','-algorithm','EC','-pkeyopt','ec_paramgen_curve:P-256'],check=True,capture_output=True).stdout
            public=subprocess.run(['openssl','pkey','-pubout','-outform','DER'],input=private,check=True,capture_output=True).stdout
            point=public[-65:];assert len(point)==65 and point[0]==4
            cose=bytes.fromhex('a5010203262001215820')+point[1:33]+bytes.fromhex('225820')+point[33:]
            credential=secrets.token_urlsafe(32);identifier=str(uuid.uuid4());handle=secrets.token_urlsafe(32)
            sql+=f"INSERT INTO tk_passkey_user VALUES({quote(owner)},{quote(handle)});\n"
            sql+=f"INSERT INTO tk_passkey_credential VALUES('{identifier}',{quote(owner)},'{hashlib.sha256(credential.encode()).hexdigest()}',{quote(credential)},'timekeeper.example.invalid',{quote(base64.urlsafe_b64encode(cose).decode().rstrip('='))},0,TRUE,TRUE,'[\"internal\"]','Migration passkey',{quote(now)},NULL,{str(index==1)},5);\n"
            if index==0:
                sql+=f"INSERT INTO tk_auth_session(digest,owner_id,csrf,permissions,created_at,expires_at,last_seen_at,persistent,origin_epoch,browser_digest,passkey_id) VALUES('{secrets.token_hex(32)}',{quote(owner)},'{secrets.token_urlsafe(32)}','READ_WRITE',{quote(now)},'2030-01-01T00:00:00Z',{quote(now)},TRUE,7,'{secrets.token_hex(32)}','{identifier}');\n"
        payload=json.dumps({'origin':'https://timekeeper.example.invalid','rpId':'timekeeper.example.invalid','epoch':7,'expectedSession':'','data':{'method':'PASSKEY','request':'fixture options'}})
        sql+=f"INSERT INTO tk_passkey_ceremony VALUES('{secrets.token_hex(32)}','{secrets.token_hex(32)}','LOGIN',NULL,NULL,{quote(payload)},'2030-01-01T00:00:00Z',FALSE);\n"
    return sql


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-image')
    parser.add_argument('--source-schema',type=int,choices=(2,3,4),default=4)
    args=parser.parse_args()
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
                    if command('docker','exec',name,'pg_isready','-h','127.0.0.1','-U','postgres','-p',port,check=False).returncode==0:break
                    time.sleep(.1)
                else:raise RuntimeError('Migration fixture did not start')
            command('docker','exec','-i',source,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','postgres',input=fixture_sql(args.source_schema))
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
                'mkdir -p /var/packages/timekeeper/scripts/migration /var/packages/timekeeper/target /var/packages/timekeeper/var /var/packages/postgresql/target/bin; '
                'for tool in psql pg_dump pg_restore; do ln -s /usr/lib/postgresql/17/bin/$tool /var/packages/postgresql/target/bin/$tool; done; '
                'chown -R 10001:10001 /var/packages/timekeeper')
            command('docker','cp',str(binary),target+':/var/packages/timekeeper/scripts/timekeeper-dsm')
            release=folder/'release.json';release.write_text(json.dumps({'commit':'a'*40,'image':'ghcr.io/turesheim/eclipse-timekeeper-server:sha-'+'a'*40,'serverSchema':4}))
            command('docker','cp',str(release),target+':/var/packages/timekeeper/scripts/release.json')
            blueprint=folder/'compose.yaml'
            blueprint.write_text((ROOT/'server/synology/project/compose.json').read_text().replace('@TIMEKEEPER_IMAGE@',json.loads(release.read_text())['image']))
            command('docker','exec',target,'mkdir','-p','/var/packages/timekeeper/target/project')
            command('docker','cp',str(blueprint),target+':/var/packages/timekeeper/target/project/compose.yaml')
            for file in ('postgresql-shape.sql','schema-v2-postgresql.shape','schema-v3-postgresql.shape','schema-v4-postgresql.shape','recovery-v3.sql','passkeys-v4.sql'):
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
            ddl=''.join((DB/name).read_text() for name in ('schema-v1-postgresql.sql','auth-v2.sql','recovery-v3.sql','passkeys-v4.sql'))+"UPDATE tk_schema SET schema_version=4,state='READY';"
            canonical="SET ROLE timekeeper_fixture_app; "+ddl
            command('docker','exec','-i',target,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','postgres','-p','5433','-d','timekeeper_fixture',input=canonical)
            archive.chmod(0o644);command('docker','cp',str(archive),target+':/var/packages/timekeeper/var/server/backups/migration.tar')
            native_archive='/var/packages/timekeeper/var/server/backups/migration.tar'
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
            broken.chmod(0o644);command('docker','cp',str(broken),target+':/var/packages/timekeeper/var/server/backups/mismatched.tar')
            helper('import-postgresql18','/var/packages/timekeeper/var/server/backups/mismatched.tar',expected=1)
            empty=command('docker','exec',target,'psql','-X','-At','-U','postgres','-p','5433','-d','timekeeper_fixture','-c','SELECT count(*) FROM tk_owner').stdout.strip()
            if empty!='0':raise AssertionError('Failed import committed partial data')
            command('docker','exec',target,'test','-f','/var/packages/timekeeper/var/server/config/maintenance-pending.json')
            helper('cancel-empty-import')
            helper('import-postgresql18',native_archive)
            compared=[table for table in exporter.tables(args.source_schema) if (table!='tk_schema' or args.source_schema==4) and (table!='tk_auth_session' or args.source_schema>=4)]
            fingerprints=client('psql',['-X','-q','-A','-t'],sql='\n'.join(exporter.fingerprint_sql(table) for table in compared))
            actual=command('docker','exec','-i',target,'psql','-X','-q','-A','-t','-U','postgres','-p','5433','-d','timekeeper_fixture',input='\n'.join(exporter.fingerprint_sql(table) for table in compared)).stdout.strip()
            if actual!=fingerprints:raise AssertionError('Migration changed source data')
            metadata=command('docker','exec',target,'psql','-X','-At','-U','postgres','-p','5433','-d','timekeeper_fixture','-c',"SELECT schema_version||'|'||state FROM tk_schema WHERE id=1").stdout.strip()
            if metadata!='4|READY':raise AssertionError('Migration did not produce the current ready schema')
            if args.source_schema<4:
                sessions=command('docker','exec',target,'psql','-X','-At','-U','postgres','-p','5433','-d','timekeeper_fixture','-c','SELECT count(*) FROM tk_auth_session').stdout.strip()
                if sessions!='0':raise AssertionError('Migration retained sessions without origin/browser provenance')
            if args.source_schema==2:
                recovery=command('docker','exec',target,'psql','-X','-At','-U','postgres','-p','5433','-d','timekeeper_fixture','-c',"SELECT epoch FROM tk_recovery_control WHERE id=1 AND origin IS NULL; SELECT count(*) FROM tk_recovery_address; SELECT count(*) FROM tk_recovery_link").stdout.strip()
                if recovery!='0\n0\n0':raise AssertionError('Legacy migration invented recovery data')
            # Recovery must preserve the complete upgraded target, including the
            # schema metadata that legitimately changes for a schema-2 import.
            fingerprints=command('docker','exec','-i',target,'psql','-X','-q','-A','-t','-U','postgres','-p','5433','-d','timekeeper_fixture',input='\n'.join(exporter.fingerprint_sql(table) for table in exporter.TABLES)).stdout.strip()
            copied=folder/'target-tokens.json';command('docker','cp',target+':/var/packages/timekeeper/var/server/config/tokens.json',str(copied))
            if copied.read_text()!=tokens:raise AssertionError('Migration changed legacy token ownership')
            helper('import-postgresql18',native_archive,expected=1)
            helper('cancel-empty-import',expected=1)
            # Disaster recovery uses a freshly provisioned dedicated role/database;
            # business ownership must not depend on the old PostgreSQL service role.
            native_backup=helper('backup').stdout.strip()
            command('docker','exec','--user','10001:10001',target,'cp',native_backup,'/var/packages/timekeeper/native-recovery.tar')
            previous_password=folder/'previous-password'
            command('docker','cp',target+':/var/packages/timekeeper/var/server/secrets/database_password',str(previous_password))
            command('docker','exec','--user','10001:10001',target,'mv','/var/packages/timekeeper/var/server','/var/packages/timekeeper/var/retained-before-recovery')
            env['wizard_database']='timekeeper_recovered';env['wizard_database_role']='timekeeper_recovered_app'
            env_file.write_text(''.join(key+'='+value+'\n' for key,value in env.items()))
            helper('install')
            recovered_schema="SET ROLE timekeeper_recovered_app; "+ddl
            command('docker','exec','-i',target,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','postgres','-p','5433','-d','timekeeper_recovered',input=recovered_schema)
            helper('restore','/var/packages/timekeeper/native-recovery.tar')
            restored=command('docker','exec','-i',target,'psql','-X','-q','-A','-t','-U','postgres','-p','5433','-d','timekeeper_recovered',input='\n'.join(exporter.fingerprint_sql(table) for table in exporter.TABLES)).stdout.strip()
            if restored!=fingerprints:raise AssertionError('Fresh-installation recovery changed owner data')
            recovered_password=folder/'recovered-password'
            command('docker','cp',target+':/var/packages/timekeeper/var/server/secrets/database_password',str(recovered_password))
            if recovered_password.read_bytes()==previous_password.read_bytes():raise AssertionError('Fresh recovery reused the source service credential')
            previous=command('docker','exec','-i',target,'psql','-X','-q','-A','-t','-U','postgres','-p','5433','-d','timekeeper_fixture',input='\n'.join(exporter.fingerprint_sql(table) for table in exporter.TABLES)).stdout.strip()
            if previous!=fingerprints:raise AssertionError('Recovery changed a database outside its dedicated target')
            native={}
            if args.server_image:
                # Operators must carry forward the source origin epoch too.
                # Importing data must never silently lower this shared fence.
                if args.source_schema>=3:
                    command('docker','exec','--user','10001:10001',target,'sh','-ec',
                        "sed -i 's/timekeeper.auth.origin-epoch=1/timekeeper.auth.origin-epoch=7/' /var/packages/timekeeper/var/server/config/mail.properties")
                    helper('prestart')
                from native_server import verify
                native=verify(command,target,folder,args.server_image)
            print(json.dumps({'source':'postgres:18.6-bookworm' ,'target':'postgres:17.11-bookworm','sourceSchema':args.source_schema,'targetSchema':4,'sourceTablesCompared':len(compared),'backupTablesCompared':len(exporter.TABLES),
                'allTableFingerprintsMatch':True,'twoOwnersWithSharedResourceIds':True,'exactNanosecondTimestampsAndLargeRevisions':True,
                'schemaMetadataUpgraded':args.source_schema<4,'verifiedAndPendingRecoveryAddressesPreserved':args.source_schema>=3,
                'passkeyCredentialsAndRevocationTombstonesPreserved':args.source_schema>=4,'legacyBrowserSessionsRevoked':args.source_schema<4,
                'accountAndTokenHashesPreserved':True,'legacyRegistryPreserved':True,'usedTargetRefused':True,'fingerprintMismatchRollsBackAtomically':True,'emptyFailedImportCanBeCancelled':True,'freshInstallationRestoreWithNewServiceCredential':True,'otherDatabaseUntouched':True,**native}))
        finally:
            for name in names:command('docker','rm','-f','-v',name,check=False)


if __name__=='__main__':main()
