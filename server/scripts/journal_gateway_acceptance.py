#!/usr/bin/env python3
"""Disposable real OpenSSH/rrsync/HTTPS journal acceptance; never uses host SSH identities."""
import argparse
import base64
import contextlib
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError,URLError
from urllib.request import Request,build_opener,HTTPCookieProcessor,HTTPSHandler
import uuid
import zipfile
from configure_accounts import create_challenge

ROOT=Path(__file__).resolve().parents[2]
GATEWAY_IMAGE='timekeeper-journal-gateway:acceptance'


def command(*args,input=None,check=True):
    result=subprocess.run(args,input=input,capture_output=True)
    if check and result.returncode:raise RuntimeError('Disposable journal command failed: '+args[0])
    return result


class Api:
    def __init__(self,base,certificate):
        self.base=base;self.csrf=None;self.opener=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()),HTTPSHandler(context=ssl.create_default_context(cafile=certificate)))
    def call(self,method,path,body=None,match=None,status=200):
        headers={'Origin':self.base,'Accept':'application/json'}
        if body is not None:headers['Content-Type']='application/json'
        if self.csrf:headers['X-CSRF-Token']=self.csrf
        if match:headers['If-Match']=match
        if path=='/journal/devices':headers['Idempotency-Key']=str(uuid.uuid4())
        request=Request(self.base+'/api/v1'+path,None if body is None else json.dumps(body).encode(),headers,method=method)
        try:response=self.opener.open(request,timeout=60)
        except HTTPError as error:response=error
        raw=response.read();value=json.loads(raw) if raw else None
        if response.status!=status:raise AssertionError('Journal API '+method+' '+path+' returned '+str(response.status)+' instead of '+str(status)+' ('+str(value.get('code','') if isinstance(value,dict) else '')+')')
        if isinstance(value,dict) and value.get('csrf'):self.csrf=value['csrf']
        return value,response.headers


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--distribution',type=Path,required=True);parser.add_argument('--skip-image-build',action='store_true');args=parser.parse_args()
    archive=args.distribution.resolve();digest=hashlib.sha256(archive.read_bytes()).hexdigest()
    if archive.with_suffix('.zip.sha256').read_text().split()[0]!=digest:raise ValueError('Distribution checksum mismatch')
    if not args.skip_image_build:command('docker','build','-t',GATEWAY_IMAGE,'-f',str(ROOT/'server/journal/Dockerfile'),str(ROOT))
    suffix=secrets.token_hex(5);network='journal-'+suffix;containers=[];volumes=[]
    with tempfile.TemporaryDirectory(prefix='timekeeper-journal-ssh-') as temporary:
        directory=Path(temporary).resolve();directory.chmod(0o700)
        def docker(*arguments,**kwargs):return command('docker',*arguments,**kwargs)
        def populate(volume,program,input=None):
            return docker('run','--rm','-i','-v',volume+':/target','-v',str(directory)+':/fixture:ro','--entrypoint','/usr/bin/python3',GATEWAY_IMAGE,'-c',program,input=input)
        def client(*arguments,check=True):
            return docker('exec','--user','30000:30000',client_name,*arguments,check=check)
        def python(program,input=None):return docker('exec','--user','30000:30000','-i',client_name,'python3','-c',program,input=input)
        def tool(action,profile='profile-a.json',*options,check=True):
            return client('python3','/tools/journal-client.py',action,'--profile','/client/'+profile,*options,check=check)
        def copy_client(name,value):
            python('import sys,os;from pathlib import Path;p=Path('+repr('/client/'+name)+');p.write_bytes(sys.stdin.buffer.read());p.chmod(0o600)',input=(json.dumps(value)+'\n').encode())
        try:
            docker('network','create','--internal',network)
            for label in ['app','gateway','secret','client']:
                volume='journal-'+label+'-'+suffix;docker('volume','create',volume);volumes.append(volume)
            app_volume,gateway_volume,secret_volume,client_volume=volumes
            with zipfile.ZipFile(archive) as package:package.extractall(directory/'unpacked')
            command('openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(directory/'tls.key'),'-out',str(directory/'tls.crt'),'-days','1','-subj','/CN=web.test','-addext','subjectAltName=DNS:web.test,IP:127.0.0.1')
            code,_=create_challenge(directory/'setup.json',owner='journal-admin-owner')
            admin_token=secrets.token_urlsafe(32);(directory/'tokens.json').write_text(json.dumps([{'ownerId':'journal-admin-owner','sha256':hashlib.sha256(admin_token.encode()).hexdigest(),'permissions':['read','write']}]))
            populate(app_volume,"import os,shutil;from pathlib import Path;root=Path('/target');root.chmod(0o700);os.chown(root,10001,10001);p=root/'incoming';p.mkdir(mode=0o700);os.chown(p,10001,10001)\nfor name in ['tls.key','tls.crt','setup.json','tokens.json']:\n p=root/name;shutil.copyfile('/fixture/'+name,p);p.chmod(0o600);os.chown(p,10001,10001)")
            host=populate(gateway_volume,"import subprocess;from pathlib import Path;p=Path('/target/host');p.mkdir(mode=0o700);subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-f',str(p/'ssh_host_ed25519_key')],check=True);print((p/'ssh_host_ed25519_key.pub').read_text().split(' ')[0]+' '+(p/'ssh_host_ed25519_key.pub').read_text().split(' ')[1])").stdout.decode().strip()
            with socket.socket() as reserved:reserved.bind(('127.0.0.1',0));port=reserved.getsockname()[1]
            base='https://127.0.0.1:'+str(port);app_name='journal-app-'+suffix;containers.append(app_name)
            docker('run','-d','--name',app_name,'--network',network,'--network-alias','web.test','--user','10001:10001','-p','127.0.0.1:'+str(port)+':8443',
                   '-v',app_volume+':/data','-v',str(directory/'unpacked/timekeeper-server')+':/app:ro','-w','/app',
                   '-e','TIMEKEEPER_SERVER_DB_URL=jdbc:h2:/data/database','-e','TIMEKEEPER_SERVER_TOKENS_FILE=/data/tokens.json','-e','TIMEKEEPER_SERVER_SETUP_FILE=/data/setup.json',
                   '-e','TIMEKEEPER_JOURNAL_CONFIGURATION_FILE=/data/journal.json','-e','TIMEKEEPER_PUBLIC_ORIGIN='+base,'-e','TIMEKEEPER_PASSKEYS_ENABLED=false',
                   '-e','QUARKUS_HTTP_HOST=0.0.0.0','-e','QUARKUS_HTTP_INSECURE_REQUESTS=disabled','-e','QUARKUS_HTTP_SSL_PORT=8443',
                   '-e','QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/data/tls.crt','-e','QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/data/tls.key','eclipse-temurin:21-jre-jammy','java','-jar','quarkus-run.jar')
            admin=Api(base,str(directory/'tls.crt'));deadline=time.monotonic()+60
            while True:
                try:admin.call('GET','/auth/status');break
                except (URLError,ConnectionError):
                    if time.monotonic()>deadline:raise RuntimeError('Packaged HTTPS journal server did not start')
                    time.sleep(.25)
            password='Journal acceptance account passphrase '+suffix
            admin.call('POST','/auth/setup',{'code':code,'username':'journal-admin','password':password,'displayName':'Journal administrator','email':'journal-admin@example.invalid'})
            actors=[]
            for name in ['a','b']:
                value,_=admin.call('POST','/admin/users',{'username':'journal-'+name,'password':password,'displayName':'Journal '+name,'email':'journal-'+name+'@example.invalid','administrator':False})
                api=Api(base,str(directory/'tls.crt'));api.call('GET','/auth/status');api.call('POST','/auth/login',{'username':'journal-'+name,'password':password,'remember':False});actors.append((value['user']['ownerId'],api))
            tokens=[secrets.token_urlsafe(32),secrets.token_urlsafe(32)]
            config={'format':1,'hostname':'gateway.test','port':2222,'hostKey':host,'incomingRoot':'/data/incoming','encryptedStorage':True,'backupKeysSeparated':True,
                    'accounts':[{'ownerId':owner,'username':'journal_'+name,'brokerTokenSha256':hashlib.sha256(token.encode()).hexdigest()} for (owner,api),name,token in zip(actors,['a','b'],tokens)]}
            populate(app_volume,"import sys,os;from pathlib import Path;p=Path('/target/journal.json');p.write_bytes(sys.stdin.buffer.read());p.chmod(0o600);os.chown(p,10001,10001)",input=json.dumps(config).encode())
            gateway_config={'format':1,'brokerUrl':'https://web.test:8443/internal/journal','caFile':'/run/secrets/ca.pem','encryptedStorage':True,'backupKeysSeparated':True,
                            'accounts':[{'username':'journal_'+name,'uid':20001+index,'token':token} for index,(name,token) in enumerate(zip(['a','b'],tokens))]}
            populate(secret_volume,"import sys,shutil;from pathlib import Path;p=Path('/target/journal-gateway.json');p.write_bytes(sys.stdin.buffer.read());p.chmod(0o600);shutil.copyfile('/fixture/tls.crt','/target/ca.pem')",input=json.dumps(gateway_config).encode())
            gateway_name='journal-gateway-'+suffix;containers.append(gateway_name)
            docker('run','-d','--name',gateway_name,'--network',network,'--network-alias','gateway.test','-v',gateway_volume+':/var/lib/timekeeper-journal','-v',secret_volume+':/run/secrets:ro',GATEWAY_IMAGE)
            populate(client_volume,"import os;from pathlib import Path;p=Path('/target');p.chmod(0o700);os.chown(p,30000,30000)")
            client_name='journal-client-'+suffix;containers.append(client_name)
            docker('run','-d','--name',client_name,'--network',network,'-v',client_volume+':/client','-v',str(ROOT/'server/web/src/journal-client.py')+':/tools/journal-client.py:ro','--entrypoint','/bin/sh',GATEWAY_IMAGE,'-c',"groupadd -g 30000 client && useradd -u 30000 -g 30000 -d /client -M client && exec sleep infinity")
            deadline=time.monotonic()+20
            while client('id',check=False).returncode:
                if time.monotonic()>deadline:raise RuntimeError('Clean journal client did not start')
                time.sleep(.2)
            def enroll(index,identity,profile_name,rotate=None):
                client('ssh-keygen','-q','-t','ed25519','-N','','-f',identity)
                public=client('cat',identity+'.pub').stdout.decode().strip();api=actors[index][1]
                status,_=api.call('GET','/journal')
                if not status['settings']['enabled']:api.call('PUT','/journal/settings',{'enabled':True,'currentPassword':password},match=status['settings']['etag'])
                body={'label':'Clean client '+str(index),'publicKey':public,'currentPassword':password}
                if rotate:body['rotateDeviceId']=rotate
                device,_=api.call('POST','/journal/devices',body,status=201);public_profile,_=api.call('GET','/journal/devices/'+device['id']+'/profile');copy_client(profile_name,public_profile)
                tool('configure',profile_name,'--folder',"/client/vault ' with spaces/Daily",'--identity',identity);tool('trust',profile_name,'--fingerprint',public_profile['hostKeyFingerprint'])
                return device,public_profile
            python("from pathlib import Path;p=Path(\"/client/vault ' with spaces/Daily\");p.mkdir(parents=True);(p/'.obsidian').mkdir();(p/'.obsidian/private-config').write_text('UNRELATED_VAULT_CONFIGURATION');(p/'unrelated.md').write_text('UNRELATED_VAULT_FILE')")
            device,public_profile=enroll(0,"/client/identity ' with spaces",'profile-a.json')
            deadline=time.monotonic()+20
            while tool('verify',check=False).returncode:
                if time.monotonic()>deadline:raise RuntimeError('Real restricted SSH verification failed')
                time.sleep(.25)
            source='---\r\ntimekeeper-label: Planning\r\nunknown: retained\r\n---\r\n\r\n# Private daily Markdown  \r\n'
            python("from pathlib import Path;Path(\"/client/vault ' with spaces/Daily/2026-10-25.md\").write_bytes("+repr(source.encode())+")")
            tool('sync');note,headers=actors[0][1].call('GET','/day-notes/2026-10-25');assert note['text']=='Planning' and note['body']=='\r\n# Private daily Markdown  \r\n'
            version=note['version'];tool('sync');assert actors[0][1].call('GET','/day-notes/2026-10-25')[0]['version']==version
            assert actors[1][1].call('GET','/day-notes/2026-10-25')[0]['version']==0
            assert python("from pathlib import Path;import sys;sys.stdout.buffer.write(Path(\"/client/vault ' with spaces/Daily/2026-10-25.md\").read_bytes())").stdout==source.encode()
            status,_=actors[0][1].call('GET','/journal');assert status['health']['lastVerifiedAt'] and status['health']['lastImportAt'] and status['health']['pending']==0
            assert 'UNRELATED_VAULT' not in json.dumps(status)
            print('Real clean-client SSH enrollment, HTTPS host pin, quoted paths, transfer/import, no-op and account isolation: PASS')
            # Additional adversarial and recovery checks are appended below as the transport evolves.
        finally:
            for container in reversed(containers):docker('rm','-f',container,check=False)
            for volume in reversed(volumes):docker('volume','rm',volume,check=False)
            docker('network','rm',network,check=False)
    return 0

if __name__=='__main__':raise SystemExit(main())
