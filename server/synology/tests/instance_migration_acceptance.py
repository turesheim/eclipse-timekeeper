#!/usr/bin/env python3
"""Migrate between two separately packaged DSM identities in an isolated layout.

Runs the actual SPK controllers and images with native PostgreSQL. DSM's resource
worker is represented by Compose creation before postinst and explicit lifecycle
calls; this is not a real Package Center/NAS acceptance receipt. Cookies are
forwarded explicitly across the loopback backend as a TLS proxy would do.
"""
import argparse
import base64
from email.parser import BytesHeaderParser
import hashlib
from http.cookies import SimpleCookie
import io
import json
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time

from acceptance import command, ROOT
sys.path.insert(0,str(ROOT/'server/scripts'))
from validate_synology import validate

from instance_acceptance_client import API, seed, export, verify_data, ADMIN_PASSWORD, COLLEAGUE_PASSWORD, DESTINATION_PASSWORD, PASSPHRASE




def unpack(package,folder):
    checked=validate(package)
    with tarfile.open(package) as outer:
        outer.extractall(folder,filter='data')
        with tarfile.open(fileobj=io.BytesIO(outer.extractfile('package.tgz').read()),mode='r:gz') as payload:
            payload.extractall(folder/'payload',filter='data')
    release=json.loads((folder/'scripts/release.json').read_text())
    return checked,release


class Installation:
    def __init__(self,database,identity,uid,folder,package,volume):
        self.database,self.identity,self.uid=database,identity,uid
        self.folder,self.package,self.volume=folder,package,volume
        self.prefix='/var/packages/'+identity;self.state='/volume1/@appdata/'+identity
        self.name=database+'-'+identity
        self.port=18080 if identity=='timekeeper' else 18081
        self.origin='https://localhost:'+('18443' if identity=='timekeeper' else '19443')
        self.code=secrets.token_urlsafe(32)
        self.environment={'SYNOPKG_PKGNAME':identity,'SYNOPKG_PKGDEST':self.prefix+'/target','SYNOPKG_PKGDEST_VOL':'/volume1',
                          'SYNOPKG_PKGVAR':self.prefix+'/var','SYNOPKG_PKGINST_TEMP_DIR':'/tmp/instance-incoming/'+identity,
                          'wizard_database':identity+'_instance','wizard_database_role':identity+'_instance_app','wizard_database_port':'5433',
                          'wizard_http_port':str(self.port),'wizard_public_origin':self.origin,'wizard_owner':'',
                          'wizard_database_admin':'postgres','wizard_setup_code':self.code}
        self.env_file=folder/'wizard.env'
        self.config=folder/'runtime-config';self.config.mkdir(mode=0o755)
        self.secrets=folder/'runtime-secrets';self.secrets.mkdir(mode=0o755)
        self.project=folder/'compose.json';self.compose=['docker','compose','-p',self.name,'-f',str(self.project)]

    def helper(self,action,*arguments,expected=0):
        result=command('docker','exec','--user',f'{self.uid}:{self.uid}','--env-file',str(self.env_file),self.database,
                       self.prefix+'/scripts/timekeeper-dsm',action,*arguments,check=False)
        if (result.returncode==0)!=(expected==0):raise RuntimeError('Native '+self.identity+' controller failed: '+action)
        return result

    def install(self,admin_password):
        self.environment['wizard_database_admin_password']=admin_password
        self.env_file.write_text(''.join(key+'='+value+'\n' for key,value in self.environment.items()));self.env_file.chmod(0o600)
        incoming=self.environment['SYNOPKG_PKGINST_TEMP_DIR']
        command('docker','exec',self.database,'mkdir','-p',self.prefix+'/scripts',incoming,self.state)
        command('docker','cp',str(self.package/'scripts')+'/.',self.database+':'+self.prefix+'/scripts')
        command('docker','cp',str(self.package/'payload')+'/.',self.database+':'+incoming)
        command('docker','cp',str(self.package/'INFO'),self.database+':'+self.prefix+'/INFO')
        command('docker','exec',self.database,'ln','-s',self.state,self.prefix+'/var')
        command('docker','exec',self.database,'chown','-R',f'{self.uid}:{self.uid}',self.prefix,incoming,self.state)
        self.helper('validate')
        command('docker','exec',self.database,'mv',incoming,self.prefix+'/target')
        command('docker','cp',self.database+':'+self.prefix+'/target/project/compose.yaml',str(self.project))
        definition=json.loads(self.project.read_text());service=definition['services']['server']
        if service['user']!=f'{self.uid}:{self.uid}':raise RuntimeError('Project does not use its package account')
        service['container_name']=self.name;service['network_mode']='container:'+self.database
        for mount in service['volumes']:
            if mount['target']=='/state':mount.update(type='volume',source=self.volume)
            else:mount['source']=str(self.config if mount['target']=='/config' else self.secrets)
        definition['volumes']={self.volume:{'external':True}}
        self.project.write_text(json.dumps(definition))
        # DSM creates the project before postinst writes runtime properties.
        command(*self.compose,'create','--no-build')
        self.helper('install');self.sync()
        del self.environment['wizard_database_admin_password']
        self.env_file.write_text(''.join(key+'='+value+'\n' for key,value in self.environment.items()))
        self.start()

    def sync(self):
        with tempfile.TemporaryDirectory(dir=self.folder) as temporary:
            prepared=Path(temporary)
            for kind,destination in (('config',self.config),('secrets',self.secrets)):
                command('docker','cp',self.database+':'+self.prefix+'/target/private/'+kind,str(prepared/kind))
                names={path.name for path in (prepared/kind).iterdir() if path.is_file()}
                for path in destination.iterdir():
                    if path.name not in names:path.unlink()
                for name in names:
                    with tempfile.NamedTemporaryFile(dir=destination,delete=False) as output:
                        replacement=Path(output.name)
                    try:
                        shutil.copyfile(prepared/kind/name,replacement)
                        replacement.chmod(0o444 if kind=='secrets' else 0o644)
                        replacement.replace(destination/name)
                    finally:
                        replacement.unlink(missing_ok=True)

    def start(self):
        command(*self.compose,'start')
        deadline=time.monotonic()+120
        while command('docker','exec',self.name,'curl','-fsS','http://127.0.0.1:'+str(self.port)+'/q/health/ready',check=False).returncode:
            if time.monotonic()>deadline:raise RuntimeError('Native '+self.identity+' server did not become ready')
            time.sleep(.5)
    def stop(self):command(*self.compose,'stop','--timeout','30')
    def api(self):return API(self.name,self.port,self.origin)
    def sql(self,query):
        return command('docker','exec','-i',self.database,'psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-U','postgres','-p','5433','-d',self.identity+'_instance',input=query).stdout.strip()
    def fingerprint(self):
        tables=self.sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").splitlines()
        quote=lambda name: '"'+name.replace('"','""')+'"'
        selections=[]
        for name in tables:
            selections.append("SELECT '"+name.replace("'","''")+"|'||count(*)||'|'||COALESCE(md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY md5(row_to_json(t)::text) COLLATE "+quote('C')+")),'empty') FROM "+quote(name)+' t;')
        return self.sql('\n'.join(selections))
    def files(self):
        result=command('docker','exec',self.database,'sh','-ec',
                       'find "$1" "$2" "$3" -printf \'%p|%y|%m|%U|%G|%s|%T@\\n\' -type f -exec sha256sum {} + | LC_ALL=C sort','snapshot',self.state,self.prefix+'/target',self.prefix+'/scripts')
        return hashlib.sha256(result.stdout.encode()).hexdigest()
    def close(self):command(*self.compose,'down',check=False)






def restore(destination,archive):
    api=destination.api()
    api.call('GET','/auth/status')
    api.call('POST','/auth/setup',{'code':destination.code,'username':'destination-admin','password':DESTINATION_PASSWORD,'displayName':'Disposable destination administrator','email':'destination@example.org'})
    operation=api.call('POST','/admin/instance/uploads',{'currentPassword':DESTINATION_PASSWORD,'bytes':len(archive),'sha256':hashlib.sha256(archive).hexdigest()})['operation']['id']
    path='/admin/instance/uploads/'+operation
    for offset in range(0,len(archive),1048576):api.call('PUT',path+'/content?offset='+str(offset),archive[offset:offset+1048576])
    api.call('POST',path+'/validate',{'currentPassword':DESTINATION_PASSWORD,'passphrase':PASSPHRASE})
    api.until(path,lambda value:value['operation']['state']=='REVIEW')
    stage=api.call('GET',path+'/staging')
    api.call('POST',path+'/staging',{'currentPassword':DESTINATION_PASSWORD,'passphrase':PASSPHRASE,'operationVersion':stage['operationVersion'],
             'publicOrigin':destination.origin,'passkeyRpId':'AUTO','passkeysEnabled':True})
    stage=api.until(path+'/staging',lambda value:value['phase']=='READY')
    if stage['target']['packageId']!='dagsverk':raise RuntimeError('Actual destination package identity was not reviewed')
    api.call('POST',path+'/confirm',{'currentPassword':DESTINATION_PASSWORD,'operationVersion':stage['operationVersion'],
             'generation':stage['generation'],'confirmation':'RESTORE dagsverk'})
    api.call('GET','/clients',expected=(503,))
    destination.stop();destination.start()
    api.call('GET','/clients',expected=(401,))




def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timekeeper-package',required=True,type=Path)
    parser.add_argument('--dagsverk-package',required=True,type=Path)
    parser.add_argument('--postgres-image',default='postgres:17.11-bookworm')
    parser.add_argument('--expected-commit',help='Require packages from this exact source build')
    parser.add_argument('--output',type=Path,default=ROOT/'server/target/acceptance/instance-packages.json')
    args=parser.parse_args()
    args.output.unlink(missing_ok=True)
    suffix=secrets.token_hex(4);database='timekeeper-instance-packages-'+suffix
    volumes=[database+'-timekeeper',database+'-dagsverk'];installations=[]
    with tempfile.TemporaryDirectory(prefix='timekeeper-instance-packages-') as temporary:
        folder=Path(temporary);packages=[]
        for identity,path in (('timekeeper',args.timekeeper_package),('dagsverk',args.dagsverk_package)):
            directory=folder/identity;directory.mkdir(mode=0o700)
            checked,release=unpack(path,directory)
            if checked['package']!=identity:raise ValueError('Provide the two independent package identities')
            packages.append((directory,checked,release))
        if packages[0][1]['sourceCommit']!=packages[1][1]['sourceCommit'] or packages[0][2]!=packages[1][2] or packages[0][1]['imageId']!=packages[1][1]['imageId']:raise ValueError('Use compatible packages from the same tested build')
        if args.expected_commit and packages[0][1]['sourceCommit']!=args.expected_commit:raise ValueError('Packages do not match the expected source build')
        command('docker','image','load','-i',str(packages[0][0]/'payload/image.tar.gz'))
        image=packages[0][2]['image']
        if command('docker','image','inspect','--format','{{.Architecture}}',image).stdout.strip()!='amd64':raise ValueError('Use actual r1000 packages')
        for volume in volumes:command('docker','volume','create',volume)
        password=secrets.token_urlsafe(32);secret=folder/'database-admin-password';secret.write_text(password);secret.chmod(0o444)
        command('docker','run','-d','--platform','linux/amd64','--name',database,'--network','none',
                '-e','POSTGRES_PASSWORD_FILE=/run/secrets/instance-admin','-e','POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256',
                '-v',str(secret)+':/run/secrets/instance-admin:ro',
                '-v',volumes[0]+':/volume1/@appdata/timekeeper','-v',volumes[1]+':/volume1/@appdata/dagsverk',args.postgres_image,'postgres','-p','5433')
        try:
            for _ in range(120):
                if command('docker','exec',database,'pg_isready','-h','127.0.0.1','-p','5433',check=False).returncode==0:break
                time.sleep(.25)
            else:raise RuntimeError('Native package PostgreSQL did not start')
            command('docker','exec',database,'sh','-ec','mkdir -p /var/packages/postgresql/target/bin /var/packages/ContainerManager; '
                    'for tool in psql pg_dump pg_restore; do ln -s /usr/lib/postgresql/17/bin/$tool /var/packages/postgresql/target/bin/$tool; done; '
                    'printf \'version="24.0.2-1432"\\n\' > /var/packages/ContainerManager/INFO')
            source=Installation(database,'timekeeper',37017,folder/'timekeeper',packages[0][0],volumes[0]);installations.append(source)
            source.install(password)
            original,data=seed(source);archive=export(original)
            before_database,before_files=source.fingerprint(),source.files()
            destination=Installation(database,'dagsverk',38017,folder/'dagsverk',packages[1][0],volumes[1]);installations.append(destination)
            destination.install(password);restore(destination,archive)
            replay=destination.api()
            for key,value in original.cookies.items():replay.cookies[key]=value.value
            replay.csrf,replay.owner=original.csrf,original.owner
            replay.call('GET','/clients',expected=(401,))
            restored=verify_data(destination,data)
            activity=restored.call('POST','/activities',{'summary':'New work after migration','taskId':data['task']['id'],'projectId':data['project']['id'],
                                  'labelIds':[data['label']['id']],'start':'2026-10-27T11:00:00Z','end':'2026-10-27T11:15:00Z'})
            destination.stop();native=destination.helper('backup').stdout.strip()
            command('docker','exec','--user','38017:38017',database,'mv',destination.state+'/server/instance-restore',destination.state+'/server/instance-restore-retained')
            destination.helper('backup',expected=1)
            destination.helper('restore',native);destination.sync();destination.start()
            restored=verify_data(destination,data)
            if restored.call('GET','/activities/'+activity['id'])!=activity:raise RuntimeError('Subsequent native recovery lost new application data')
            if source.fingerprint()!=before_database or source.files()!=before_files:raise RuntimeError('Destination migration changed the original installation')
            if original.call('GET','/clients/'+data['client']['id'])!=data['client']:raise RuntimeError('Original installation is no longer usable')
            evidence={'commit':packages[0][1]['sourceCommit'],'sourcePackageSha256':packages[0][1]['sha256'],'destinationPackageSha256':packages[1][1]['sha256'],'imageId':packages[0][1]['imageId'],'sourcePackage':'timekeeper','destinationPackage':'dagsverk',
                              'independentPackageAccounts':True,'sourceDatabaseAndInstalledFilesUnchanged':True,'encryptedNativeExportPublicRestore':True,
                              'bootstrapAndCopiedSourceSessionsInvalidated':True,
                              'bothPasswordsAndOwnerBindingsPreserved':True,'integrationTokenPreserved':True,'userDataIsolationPreserved':True,
                              'businessIdsVersionsRelationshipsAndTimestampsPreserved':True,'independentDayLabelAndJournalsPreserved':True,
                              'exactAttachmentBytesPreserved':True,'restoredApplicationWritesWork':True,'subsequentNativeMaterialRecovery':True}
            args.output.parent.mkdir(parents=True,exist_ok=True)
            args.output.write_text(json.dumps(evidence,indent=2)+'\n')
            print(json.dumps(evidence))
        finally:
            for installation in reversed(installations):installation.close()
            command('docker','rm','-f','-v',database,check=False)
            for volume in volumes:command('docker','volume','rm',volume,check=False)


if __name__=='__main__':main()
