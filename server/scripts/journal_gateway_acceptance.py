#!/usr/bin/env python3
"""Disposable real OpenSSH/rrsync/HTTPS journal acceptance; never uses host SSH identities."""
import argparse
import base64
import contextlib
from datetime import date,timedelta
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
    result=subprocess.run(args,input=input,capture_output=True,timeout=1200)
    if check and result.returncode:raise RuntimeError('Disposable journal command failed: '+args[0])
    return result


def build_gateway_image():
    # The image is built before any private fixture data is created. Let Docker
    # write its diagnostics to CI; the generic command helper deliberately hides
    # output because later invocations can contain journal contents and tokens.
    result=subprocess.run(('docker','build','--progress=plain','-t',GATEWAY_IMAGE,
                           '-f',str(ROOT/'server/journal/Dockerfile'),str(ROOT)),timeout=1200)
    if result.returncode:raise RuntimeError('Disposable journal gateway image build failed (exit '+str(result.returncode)+')')


class Api:
    def __init__(self,base,certificate,origin=None):
        self.base=base;self.origin=origin or base;self.csrf=None;self.opener=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()),HTTPSHandler(context=ssl.create_default_context(cafile=certificate)))
    def call(self,method,path,body=None,match=None,status=200,binary=False):
        headers={'Origin':self.origin,'Accept':'application/octet-stream' if binary else 'application/json'}
        if body is not None:headers['Content-Type']='application/json'
        if self.csrf:headers['X-CSRF-Token']=self.csrf
        if match:headers['If-Match']=match
        if path=='/journal/devices':headers['Idempotency-Key']=str(uuid.uuid4())
        request=Request(self.base+'/api/v1'+path,None if body is None else json.dumps(body).encode(),headers,method=method)
        try:response=self.opener.open(request,timeout=60)
        except HTTPError as error:response=error
        raw=response.read();value=raw if binary else json.loads(raw) if raw else None
        if response.status!=status:raise AssertionError('Journal API '+method+' '+path+' returned '+str(response.status)+' instead of '+str(status)+' ('+str(value.get('code','') if isinstance(value,dict) else '')+')')
        if isinstance(value,dict) and value.get('csrf'):self.csrf=value['csrf']
        return value,response.headers


def main():
    global GATEWAY_IMAGE
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--distribution',type=Path,required=True);parser.add_argument('--skip-image-build',action='store_true');parser.add_argument('--gateway-image',default=GATEWAY_IMAGE);parser.add_argument('--bidirectional',action='store_true');args=parser.parse_args();GATEWAY_IMAGE=args.gateway_image
    archive=args.distribution.resolve();digest=hashlib.sha256(archive.read_bytes()).hexdigest()
    if archive.with_suffix('.zip.sha256').read_text().split()[0]!=digest:raise ValueError('Distribution checksum mismatch')
    if not args.skip_image_build:build_gateway_image()
    suffix=secrets.token_hex(5);network='journal-'+suffix;containers=[];volumes=[];background=[]
    sensitive=[b'Private daily Markdown',b'Private pressure note',b'INDEPENDENT_VALID_NOTE_SENTINEL',b'PRIVATE_CALENDAR_EDIT_SENTINEL',b'PRIVATE_CONFLICT_SOURCE_SENTINEL',b'INFLIGHT_PRIVATE_SENTINEL',b'UNRELATED_VAULT',b'UNACKNOWLEDGED_NOTE_SENTINEL',b'Private outgoing acceptance note',b'Local simultaneous variant',b'Calendar simultaneous variant',b'Database pressure']
    output=ROOT/'server/target/journal-gateway-acceptance';output.mkdir(parents=True,exist_ok=True);output.chmod(0o700)
    with contextlib.suppress(FileNotFoundError):(output/'acceptance.json').unlink()
    for previous in output.glob('*.log'):previous.unlink()
    with tempfile.TemporaryDirectory(prefix='timekeeper-journal-ssh-') as temporary:
        directory=Path(temporary).resolve();directory.chmod(0o700)
        def docker(*arguments,**kwargs):return command('docker',*arguments,**kwargs)
        def populate(volume,program,input=None):
            return docker('run','--rm','-i','-v',volume+':/target','-v',str(directory)+':/fixture:ro','--entrypoint','/usr/bin/python3',GATEWAY_IMAGE,'-c',program,input=input)
        def client(*arguments,check=True):
            return docker('exec','--user','30000:30000',client_name,*arguments,check=check)
        def python(program,input=None,check=True):return docker('exec','--user','30000:30000','-i',client_name,'python3','-c',program,input=input,check=check)
        def tool(action,profile='profile-a.json',*options,check=True):
            return client('python3','/tools/journal-client.py',action,'--profile','/client/'+profile,*options,check=check)
        def copy_client(name,value):
            python('import sys,os;from pathlib import Path;p=Path('+repr('/client/'+name)+');p.write_bytes(sys.stdin.buffer.read());p.chmod(0o600)',input=(json.dumps(value)+'\n').encode())
        try:
            # Docker Desktop does not publish container ports on internal networks.
            # The unique bridge still isolates fixtures; HTTPS is exposed on loopback only.
            docker('network','create',network)
            for label in ['app','gateway','secret','client']:
                volume='journal-'+label+'-'+suffix;docker('volume','create',volume);volumes.append(volume)
            app_volume,gateway_volume,secret_volume,client_volume=volumes
            with zipfile.ZipFile(archive) as package:
                if any(not name.startswith('timekeeper-server/') or '..' in Path(name).parts or Path(name).is_absolute() for name in package.namelist()):raise ValueError('Invalid distribution paths')
                package.extractall(directory/'unpacked');manifest=json.loads(package.read('timekeeper-server/timekeeper-release.json'))
            command('openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(directory/'tls.key'),'-out',str(directory/'tls.crt'),'-days','1','-subj','/CN=web.test','-addext','subjectAltName=DNS:web.test,IP:127.0.0.1')
            code,_=create_challenge(directory/'setup.json',owner='journal-admin-owner')
            admin_token=secrets.token_urlsafe(32);(directory/'tokens.json').write_text(json.dumps([{'ownerId':'journal-admin-owner','sha256':hashlib.sha256(admin_token.encode()).hexdigest(),'permissions':['read','write']}]))
            populate(app_volume,"import os,shutil;from pathlib import Path;root=Path('/target');root.chmod(0o700);os.chown(root,10001,10001);p=root/'incoming';p.mkdir(mode=0o700);os.chown(p,10001,10001)\nfor name in ['tls.key','tls.crt','setup.json','tokens.json']:\n p=root/name;shutil.copyfile('/fixture/'+name,p);p.chmod(0o600);os.chown(p,10001,10001)")
            host=populate(gateway_volume,"import subprocess;from pathlib import Path;p=Path('/target/host');p.mkdir(mode=0o700);subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-f',str(p/'ssh_host_ed25519_key')],check=True);print((p/'ssh_host_ed25519_key.pub').read_text().split(' ')[0]+' '+(p/'ssh_host_ed25519_key.pub').read_text().split(' ')[1])").stdout.decode().strip()
            with socket.socket() as reserved:reserved.bind(('127.0.0.1',0));port=reserved.getsockname()[1]
            base='https://127.0.0.1:'+str(port);origin='https://web.test:8443';app_name='journal-app-'+suffix;containers.append(app_name)
            docker('run','-d','--name',app_name,'--network',network,'--network-alias','web.test','--user','10001:10001','-p','127.0.0.1:'+str(port)+':8443',
                   '-v',app_volume+':/data','-v',str(directory/'unpacked/timekeeper-server')+':/app:ro','-w','/app',
                   '-e','TIMEKEEPER_SERVER_DB_URL=jdbc:h2:/data/database','-e','TIMEKEEPER_SERVER_TOKENS_FILE=/data/tokens.json','-e','TIMEKEEPER_SERVER_SETUP_FILE=/data/setup.json',
                   '-e','TIMEKEEPER_JOURNAL_CONFIGURATION_FILE=/data/journal.json','-e','TIMEKEEPER_PUBLIC_ORIGIN='+origin,'-e','TIMEKEEPER_PASSKEYS_ENABLED=false',
                   '-e','QUARKUS_HTTP_HOST=0.0.0.0','-e','QUARKUS_HTTP_INSECURE_REQUESTS=disabled','-e','QUARKUS_HTTP_SSL_PORT=8443',
                   '-e','QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/data/tls.crt','-e','QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/data/tls.key','eclipse-temurin:21-jre-jammy','java','-jar','quarkus-run.jar')
            admin=Api(base,str(directory/'tls.crt'),origin);deadline=time.monotonic()+60
            while True:
                try:admin.call('GET','/auth/status');break
                except (URLError,ConnectionError):
                    if time.monotonic()>deadline:raise RuntimeError('Packaged HTTPS journal server did not start')
                    time.sleep(.25)
            # Run the client actually served by this immutable distribution,
            # never a newer script from the checkout running the test harness.
            with admin.opener.open(Request(base+'/web/journal-client.py'),timeout=10) as response:client_script=response.read(65537)
            if len(client_script)>65536 or not client_script.startswith(b'#!/usr/bin/env python3'):raise RuntimeError('Packaged journal client is unavailable')
            (directory/'journal-client.py').write_bytes(client_script)
            password='Journal acceptance account passphrase '+suffix
            sensitive.append(password.encode())
            admin.call('POST','/auth/setup',{'code':code,'username':'journal-admin','password':password,'displayName':'Journal administrator','email':'journal-admin@example.invalid'})
            actors=[];account_names=list('abcdefgh') if args.bidirectional else ['a','b']
            for name in account_names:
                value,_=admin.call('POST','/admin/users',{'username':'journal-'+name,'password':password,'displayName':'Journal '+name,'email':'journal-'+name+'@example.invalid','administrator':False},status=201)
                api=Api(base,str(directory/'tls.crt'),origin);api.call('GET','/auth/status');api.call('POST','/auth/login',{'username':'journal-'+name,'password':password,'remember':False});actors.append((value['user']['ownerId'],api))
            tokens=[secrets.token_urlsafe(32) for _ in account_names]
            sensitive.extend(token.encode() for token in tokens)
            config={'format':1,'hostname':'gateway.test','port':2222,'hostKey':host,'incomingRoot':'/data/incoming','encryptedStorage':True,'backupKeysSeparated':True,
                    'accounts':[{'ownerId':owner,'username':'journal_'+name,'brokerTokenSha256':hashlib.sha256(token.encode()).hexdigest()} for (owner,api),name,token in zip(actors,account_names,tokens)]}
            populate(app_volume,"import sys,os;from pathlib import Path;p=Path('/target/journal.json');p.write_bytes(sys.stdin.buffer.read());p.chmod(0o600);os.chown(p,10001,10001)",input=json.dumps(config).encode())
            gateway_config={'format':1,'brokerUrl':'https://web.test:8443/internal/journal','caFile':'/run/secrets/ca.pem','encryptedStorage':True,'backupKeysSeparated':True,
                            'accounts':[{'username':'journal_'+name,'uid':20001+index,'token':token} for index,(name,token) in enumerate(zip(account_names,tokens))]}
            populate(secret_volume,"import sys,shutil;from pathlib import Path;p=Path('/target/journal-gateway.json');p.write_bytes(sys.stdin.buffer.read());p.chmod(0o600);shutil.copyfile('/fixture/tls.crt','/target/ca.pem')",input=json.dumps(gateway_config).encode())
            gateway_name='journal-gateway-'+suffix;containers.append(gateway_name)
            docker('run','-d','--name',gateway_name,'--network',network,'--network-alias','gateway.test','-v',gateway_volume+':/var/lib/timekeeper-journal','-v',secret_volume+':/run/secrets:ro',GATEWAY_IMAGE)
            populate(client_volume,"import os;from pathlib import Path;p=Path('/target');p.chmod(0o700);os.chown(p,30000,30000)")
            client_name='journal-client-'+suffix;containers.append(client_name)
            docker('run','-d','--name',client_name,'--network',network,'-v',client_volume+':/client','-v',str(directory/'journal-client.py')+':/tools/journal-client.py:ro','--entrypoint','/bin/sh',GATEWAY_IMAGE,'-c',"groupadd -g 30000 client && useradd -u 30000 -g 30000 -d /client -M client && exec sleep infinity")
            deadline=time.monotonic()+20
            while client('id',check=False).returncode:
                if time.monotonic()>deadline:raise RuntimeError('Clean journal client did not start')
                time.sleep(.2)
            def enroll(index,identity,profile_name,rotate=None,bidirectional=False,folder="/client/vault ' with spaces/Daily"):
                client('ssh-keygen','-q','-t','ed25519','-N','','-f',identity)
                public=client('cat',identity+'.pub').stdout.decode().strip();api=actors[index][1]
                status,_=api.call('GET','/journal')
                if not status['settings']['enabled'] or bidirectional and not status['settings']['bidirectional']:api.call('PUT','/journal/settings',{'enabled':True,'bidirectional':bidirectional or status['settings']['bidirectional'],'currentPassword':password},match=status['settings']['etag'])
                body={'label':'Clean client '+str(index),'publicKey':public,'bidirectional':bidirectional,'currentPassword':password}
                if rotate:body['rotateDeviceId']=rotate
                device,_=api.call('POST','/journal/devices',body,status=201);public_profile,_=api.call('GET','/journal/devices/'+device['id']+'/profile');copy_client(profile_name,public_profile)
                tool('configure',profile_name,'--folder',folder,'--identity',identity);tool('trust',profile_name,'--fingerprint',public_profile['hostKeyFingerprint'])
                return device,public_profile
            python("from pathlib import Path;p=Path(\"/client/vault ' with spaces/Daily\");p.mkdir(parents=True);(p/'.obsidian').mkdir();(p/'.obsidian/private-config').write_text('UNRELATED_VAULT_CONFIGURATION');(p/'unrelated.md').write_text('UNRELATED_VAULT_FILE')")
            device,public_profile=enroll(0,"/client/identity ' with spaces",'profile-a.json')
            deadline=time.monotonic()+20
            while tool('verify',check=False).returncode:
                if time.monotonic()>deadline:
                    # Diagnostic contains only a public key and generic broker codes.
                    public=client('cat',"/client/identity ' with spaces.pub").stdout.decode().split()
                    diagnostic=docker('exec','--user','journal-auth',gateway_name,'python3','-c',
                        'import runpy,sys,http.client,ssl,json;g=runpy.run_path("/usr/local/libexec/timekeeper-journal-gateway");c=g["private_json"](g["CONFIG"]);a=c["accounts"][0];h=http.client.HTTPSConnection("web.test",8443,context=ssl.create_default_context(cafile=c["caFile"]));h.request("POST","/internal/journal/"+a["username"]+"/keys",json.dumps({"publicKey":" ".join(sys.argv[2:])}),{"Content-Type":"application/json","Authorization":"JournalTransport "+a["token"]});r=h.getresponse();print(r.status,r.read(4096).decode());g["authorize"](*sys.argv[1:])','journal_a',*public[:2],check=False)
                    path=output/'authorization.log';path.write_bytes(diagnostic.stdout+diagnostic.stderr);path.chmod(0o600)
                    raise RuntimeError('Real restricted SSH verification failed')
                time.sleep(.25)
            source='---\r\ntimekeeper-label: Planning\r\nunknown: retained\r\n---\r\n\r\n# Private daily Markdown  \r\n'
            python("from pathlib import Path;Path(\"/client/vault ' with spaces/Daily/2026-10-25.md\").write_bytes("+repr(source.encode())+")")
            tool('sync');note,headers=actors[0][1].call('GET','/day-notes/2026-10-25');assert note['text']=='Planning' and note['body']=='\r\n# Private daily Markdown  \r\n'
            version=note['version'];tool('sync');assert actors[0][1].call('GET','/day-notes/2026-10-25')[0]['version']==version
            assert actors[1][1].call('GET','/day-notes/2026-10-25')[0]['version']==0
            assert python("from pathlib import Path;import sys;sys.stdout.buffer.write(Path(\"/client/vault ' with spaces/Daily/2026-10-25.md\").read_bytes())").stdout==source.encode()
            status,_=actors[0][1].call('GET','/journal');assert status['health']['lastVerifiedAt'] and status['health']['lastImportAt'] and status['health']['pending']==0
            assert 'UNRELATED_VAULT' not in json.dumps(status)
            print('Real clean-client SSH enrollment, HTTPS host pin, quoted paths, transfer/import, no-op and account isolation: PASS',flush=True)
            def client_module(profile='profile-a.json'):
                return 'import importlib.util,pathlib,argparse,subprocess,sys,shlex;s=importlib.util.spec_from_file_location("c","/tools/journal-client.py");c=importlib.util.module_from_spec(s);s.loader.exec_module(c);d,f,i,t=c.local_config(argparse.Namespace(profile=pathlib.Path('+repr('/client/'+profile)+')));'
            def ssh(remote,profile='profile-a.json',username=None,options=(),raw_pin=False):
                prefix=client_module(profile)
                if raw_pin:prefix=prefix.split('d,f,i,t=c.local_config')[0]+'p=pathlib.Path('+repr('/client/'+profile)+');d=c.profile(p);local,t=c.paths(p);i=pathlib.Path(c.read_json(local)["identity"]);'
                # An adversarial client deliberately requests forwarding instead
                # of using the normal client's ClearAllForwardings safeguard.
                return python(prefix+'a=c.ssh_arguments(d,i,t);'+('n=a.index("ClearAllForwardings=yes");del a[n-1:n+1];' if options else '')+'r=subprocess.run(a+'+repr(list(options))+'+["-l",'+repr(username)+' or d["username"],d["hostname"],'+repr(remote)+']);sys.exit(r.returncode)',check=False)
            def raw_sync(folder,options=(),destination='.',profile='profile-a.json'):
                return python(client_module(profile)+'r=subprocess.run(["rsync","-r","--rsh",shlex.join(c.ssh_arguments(d,i,t))]+'+repr(list(options))+'+['+repr(folder+'/')+',d["username"]+"@"+d["hostname"]+":"+'+repr(destination)+']);sys.exit(r.returncode)',check=False)
            def relogin(index):
                api=Api(base,str(directory/'tls.crt'),origin);api.call('GET','/auth/status');api.call('POST','/auth/login',{'username':'journal-'+account_names[index],'password':password,'remember':False});actors[index]=(actors[index][0],api);return api
            for remote in ['id','sh','rsync --server --sender -r . .','rsync --daemon','rsync --server -r . ../../escape']:
                assert ssh(remote).returncode,'Restricted SSH command was accepted'
            assert ssh('timekeeper-journal-test',username='journal_b').returncode,'Cross-account key was accepted'
            assert ssh('timekeeper-journal-test',options=['-o','ExitOnForwardFailure=yes','-R','0:127.0.0.1:8443']).returncode,'SSH forwarding was accepted'
            # Bypass the normal client filters: a completed rsync with an invalid
            # manifest must still fail its SSH acknowledgement and import nothing.
            python("from pathlib import Path;import json;p=Path('/client/attack');p.mkdir();(p/'.timekeeper-batch.json').write_text(json.dumps({'format':1,'files':[],'errors':[]}));(p/'2026-10-24.md').write_text('UNACKNOWLEDGED_NOTE_SENTINEL')")
            assert raw_sync('/client/attack').returncode,'Post-rsync validation failure was not propagated'
            assert actors[0][1].call('GET','/day-notes/2026-10-24')[0]['version']==0
            python("from pathlib import Path;p=Path('/client/attack');(p/'2026-10-24.md').unlink();(p/'2026-10-24.md').symlink_to('/etc/passwd')")
            assert raw_sync('/client/attack',options=['--links']).returncode,'Remote symlink upload was accepted'
            assert raw_sync('/client/attack',destination='../escape').returncode,'Rsync traversal was accepted'
            python("from pathlib import Path;import subprocess;p=Path('/client/other-host');subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-f',str(p)],check=True)")
            changed=client('cat','/client/other-host.pub').stdout.decode().split()[:2]
            # Replace only the local pin with an unrelated real key to exercise
            # OpenSSH mismatch handling, then restore the authenticated pin.
            python(client_module()+"p=t/'known_hosts';old=p.read_bytes();(t/'original-pin').write_bytes(old);p.write_text(d['hostAlias']+' '+"+repr(' '.join(changed))+"+'\\n')")
            mismatch=ssh('timekeeper-journal-test',raw_pin=True)
            assert mismatch.returncode and b'REMOTE HOST IDENTIFICATION HAS CHANGED' in mismatch.stderr,'Host mismatch was accepted'
            python(client_module().split('d,f,i,t=c.local_config')[0]+"p=pathlib.Path('/client/profile-a.json');_,t=c.paths(p);(t/'known_hosts').write_bytes((t/'original-pin').read_bytes());(t/'original-pin').unlink()")
            assert not tool('verify').returncode
            print('Real SSH command/forwarding/cross-account, malformed handoff, symlink, traversal and host mismatch denial: PASS',flush=True)
            if args.bidirectional:
                from journal_bidirectional_acceptance import run
                run(api=actors[2][1],account_index=2,read_accounts=[(index,*actors[index]) for index in range(3,8)],admin=admin,relogin=relogin,password=password,python=python,tool=tool,enroll=enroll,ssh=ssh,client_module=client_module,docker=docker,app_name=app_name,gateway_name=gateway_name,client_name=client_name,background=background)
            # More than the pending-copy quota must work as bounded, settled
            # batches rather than failing because a sender outruns the worker.
            names=[(date(1990,1,1)+timedelta(days=index)).isoformat()+'.md' for index in range(501)]
            python("from pathlib import Path;p=Path(\"/client/vault ' with spaces/Daily\")\nfor name in "+repr(names)+":(p/name).write_text('Private pressure note '+name)")
            assert json.loads(tool('sync').stdout)['transferredFiles']==502
            for name in [names[0],names[-1]]:assert actors[0][1].call('GET','/day-notes/'+name[:-3])[0]['body']=='Private pressure note '+name
            python("from pathlib import Path;p=Path(\"/client/vault ' with spaces/Daily\")\nfor name in "+repr(names)+":(p/name).unlink()\n(p/'2026-10-26.md').write_text('INDEPENDENT_VALID_NOTE_SENTINEL');(p/'2026-10-27.md').symlink_to('/etc/passwd');(p/'2026-10-28.md').write_bytes(b'x'*220001)")
            result=tool('sync',check=False);assert result.returncode and json.loads(result.stdout)['localFileErrors']==2
            assert actors[0][1].call('GET','/day-notes/2026-10-26')[0]['body']=='INDEPENDENT_VALID_NOTE_SENTINEL'
            status,_=actors[0][1].call('GET','/journal');assert {'UNSAFE_LOCAL_FILE','FILE_TOO_LARGE'}<={item.get('error') for item in status['items']}
            assert actors[0][1].call('GET','/day-notes/'+names[0][:-3])[0]['version']>0,'Missing source deleted a note'
            python("from pathlib import Path;p=Path(\"/client/vault ' with spaces/Daily\");(p/'2026-10-27.md').unlink();(p/'2026-10-28.md').unlink();(p/'2026-10-26.md').write_bytes(b'')")
            tool('sync');assert actors[0][1].call('GET','/day-notes/2026-10-26')[0]['body']==''
            print('Real 501-note pressure, independent local errors, missing-file preservation and explicit empty clearing: PASS',flush=True)
            current,headers=actors[0][1].call('GET','/day-notes/2026-10-25')
            actors[0][1].call('PUT','/day-notes/2026-10-25',{'body':'PRIVATE_CALENDAR_EDIT_SENTINEL'},match=headers['ETag'])
            incoming=b'---\r\ntimekeeper-label: Incoming\r\nunknown: retained\r\n---\r\nPRIVATE_CONFLICT_SOURCE_SENTINEL  \r\n'
            python("from pathlib import Path;Path(\"/client/vault ' with spaces/Daily/2026-10-25.md\").write_bytes("+repr(incoming)+")")
            tool('sync');status,_=actors[0][1].call('GET','/journal');conflict=next(item for item in status['items'] if item['filename']=='2026-10-25.md' and item['state']=='CONFLICT')
            assert actors[0][1].call('GET','/journal/items/'+conflict['id']+'/source',binary=True)[0]==incoming
            docker('kill','--signal','KILL',app_name);docker('start',app_name);deadline=time.monotonic()+30
            while True:
                try:actors[0][1].call('GET','/journal');break
                except (URLError,ConnectionError):
                    if time.monotonic()>deadline:raise RuntimeError('Journal crash restart failed')
                    time.sleep(.25)
            candidate,_=actors[0][1].call('GET','/journal/items/'+conflict['id'])
            assert candidate['current']['body']=='PRIVATE_CALENDAR_EDIT_SENTINEL'
            actors[0][1].call('POST','/journal/items/'+conflict['id']+'/resolve',{'action':'INCOMING','currentEtag':candidate['currentEtag']},match=candidate['candidateEtag'])
            accepted,_=actors[0][1].call('GET','/day-notes/2026-10-25');tool('sync');assert actors[0][1].call('GET','/day-notes/2026-10-25')[0]['version']==accepted['version']
            print('Real SSH Calendar conflict, byte-exact recovery, forced server restart and durable accepted no-op baseline: PASS',flush=True)
            # Observe actual private receiver files while a deliberately slow,
            # over-permissive sender is still writing, then withdraw authority.
            def slow(profile,user):
                folder='/client/slow-'+user
                python("from pathlib import Path;import json,hashlib;p=Path("+repr(folder)+");p.mkdir(exist_ok=True);b=b'INFLIGHT_PRIVATE_SENTINEL\\n'+b'z'*180000;(p/'2026-11-01.md').write_bytes(b);(p/'.timekeeper-batch.json').write_text(json.dumps({'format':1,'files':[{'name':'2026-11-01.md','bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()}],'errors':[]}))")
                program=client_module(profile)+'r=subprocess.run(["rsync","-r","--perms","--chmod=ugo=rwx","--bwlimit=8","--rsh",shlex.join(c.ssh_arguments(d,i,t)),'+repr(folder+'/')+',d["username"]+"@"+d["hostname"]+":."]);sys.exit(r.returncode)'
                process=subprocess.Popen(['docker','exec','--user','30000:30000',client_name,'python3','-c',program],stdout=subprocess.PIPE,stderr=subprocess.PIPE);background.append(process)
                deadline=time.monotonic()+15
                while True:
                    program="import os,json;from pathlib import Path;p=Path('/var/lib/timekeeper-journal/spool/"+user+"');print(json.dumps([[str(q.relative_to(p)),q.stat().st_mode&511,q.stat().st_uid,q.stat().st_size,q.is_dir()] for q in p.rglob('*') if not q.is_symlink()]))"
                    rows=json.loads(docker('exec',gateway_name,'python3','-c',program).stdout)
                    assert all(mode==(0o700 if directory else 0o600) and uid==(20001 if user=='journal_a' else 20002) for path,mode,uid,size,directory in rows),'Unsafe in-flight receiver permissions'
                    # rsync can buffer this bounded file until its final write;
                    # the receiver's open private temporary file proves that
                    # the throttled data stream is active before its handoff.
                    if any(not directory and '2026-11-01.md' in path for path,mode,uid,size,directory in rows):break
                    if process.poll() is not None or time.monotonic()>deadline:
                        if process.poll() is None:process.kill()
                        out,err=process.communicate(timeout=5);path=output/'slow-receiver.log';path.write_bytes(out+err+json.dumps(rows).encode());path.chmod(0o600)
                        raise RuntimeError('Slow receiver did not enter private file transfer')
                    time.sleep(.2)
                return process
            def stopped(process,index):
                process.communicate(timeout=15);assert process.returncode,'Withdrawn transfer was acknowledged'
                assert actors[index][1].call('GET','/day-notes/2026-11-01')[0]['version']==0,'Withdrawn transfer changed Calendar'
            process=slow('profile-a.json','journal_a')
            replacement,replacement_profile=enroll(0,'/client/replacement-key','profile-replacement.json',rotate=device['id'])
            stopped(process,0);assert tool('verify',check=False).returncode
            tool('verify','profile-replacement.json');tool('sync','profile-replacement.json')
            process=slow('profile-replacement.json','journal_a')
            actors[0][1].call('POST','/journal/devices/'+replacement['id']+'/revoke',{'currentPassword':password})
            stopped(process,0);assert tool('verify','profile-replacement.json',check=False).returncode
            device_b,profile_b=enroll(1,'/client/b-key','profile-b.json')
            assert docker('exec','--user','20002:20002',gateway_name,'ls','/var/lib/timekeeper-journal/spool/journal_a',check=False).returncode
            assert docker('exec','--user','20002:20002',gateway_name,'cat','/etc/timekeeper-journal/accounts/journal_a.json',check=False).returncode
            process=slow('profile-b.json','journal_b');status,_=actors[1][1].call('GET','/journal')
            status,_=actors[1][1].call('PUT','/journal/settings',{'enabled':False,'currentPassword':password},match=status['settings']['etag'])
            stopped(process,1);assert tool('verify','profile-b.json',check=False).returncode
            actors[1][1].call('PUT','/journal/settings',{'enabled':True,'currentPassword':password},match=status['settings']['etag']);tool('verify','profile-b.json')
            process=slow('profile-b.json','journal_b')
            owner_key=base64.urlsafe_b64encode(actors[1][0].encode()).decode().rstrip('=');user,user_headers=admin.call('GET','/admin/users/'+owner_key);user=user['user']
            user_body={key:user[key] for key in ['username','displayName','administrator']};user_body['enabled']=False
            _,disabled_headers=admin.call('PUT','/admin/users/'+owner_key,user_body,match=user_headers['ETag'])
            process.communicate(timeout=15);assert process.returncode;assert tool('verify','profile-b.json',check=False).returncode
            user_body['enabled']=True;admin.call('PUT','/admin/users/'+owner_key,user_body,match=disabled_headers['ETag'])
            api=Api(base,str(directory/'tls.crt'),origin);api.call('GET','/auth/status');api.call('POST','/auth/login',{'username':'journal-b','password':password,'remember':False});actors[1]=(actors[1][0],api)
            assert api.call('GET','/day-notes/2026-11-01')[0]['version']==0
            assert api.call('GET','/day-notes/2026-10-25')[0]['version']==0
            tool('verify','profile-b.json')
            print('Real in-flight private permissions, rotation/revocation, disabled sync/account and Unix account isolation: PASS',flush=True)
            image_id=docker('image','inspect',GATEWAY_IMAGE,'--format','{{.Id}}').stdout.decode().strip()
            receipt=output/'acceptance.json';receipt.write_text(json.dumps({'passed':True,'distributionSha256':digest,'sourceCommit':manifest['commit'],'clientSha256':hashlib.sha256(client_script).hexdigest(),'gatewayImageId':image_id,'rsync':'3.5.1','realSsh':True,'realHttps':True,'pressureFiles':501,'inflightWithdrawal':True,'crashRestart':True,'bidirectional':args.bidirectional})+'\n');receipt.chmod(0o600)
        finally:
            for process in background:
                if process.poll() is None:process.kill();process.communicate(timeout=5)
            leaked=False
            for container in containers:
                logs=docker('logs',container,check=False);log=logs.stdout+logs.stderr
                for secret in sensitive:
                    if secret in log:leaked=True;log=log.replace(secret,b'[REDACTED]')
                path=output/(container.split('-'+suffix)[0]+'.log');path.write_bytes(log);path.chmod(0o600)
            for container in reversed(containers):docker('rm','-f',container,check=False)
            for volume in reversed(volumes):docker('volume','rm',volume,check=False)
            docker('network','rm',network,check=False)
            if leaked:
                with contextlib.suppress(FileNotFoundError):(output/'acceptance.json').unlink()
                raise RuntimeError('Fixture logs contained private journal material; saved diagnostics were redacted')
    return 0

if __name__=='__main__':raise SystemExit(main())
