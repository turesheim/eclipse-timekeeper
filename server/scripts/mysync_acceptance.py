#!/usr/bin/env python3
"""Real released Obsidian/MySync interoperability against a private HTTPS server."""
import argparse
import base64
from email.parser import BytesHeaderParser
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time

from configure_accounts import create_challenge
from instance_acceptance_client import API

ROOT=Path(__file__).resolve().parents[2]
CLIENT=ROOT/'server/scripts/clients/mysync'


def command(*args,input=None,expected=0,timeout=150):
    result=subprocess.run(args,input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if (result.returncode==0)!=(expected==0):
        raise RuntimeError('Disposable interoperability operation failed: '+args[0])
    return result.stdout


class Fixture:
    origin='https://timekeeper.test:8443'
    def __init__(self,folder,args):
        self.folder,self.args=folder,args
        self.prefix='timekeeper-mysync-'+secrets.token_hex(5)
        self.network=self.prefix+'-net';self.database=self.prefix+'-db';self.server=self.prefix+'-server';self.client=self.prefix+'-client'
        self.containers=[];self.network_created=False

    def private(self,name,value,mode=0o600):
        path=self.folder/name;path.write_text(value);path.chmod(mode);return path

    def start(self):
        command('docker','network','create','--internal',self.network);self.network_created=True
        password=secrets.token_urlsafe(32)
        dbpassword=self.private('database-password',password,0o444)
        dbenv=self.private('database.env','POSTGRES_PASSWORD='+secrets.token_urlsafe(32)+'\n')
        code,_=create_challenge(self.folder/'setup.json',minutes=60);(self.folder/'setup.json').chmod(0o444)
        self.private('tls-password','isolated TLS fixture password\n')
        command('openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2','-subj','/CN=Timekeeper isolated test CA',
                '-keyout',str(self.folder/'ca.key'),'-out',str(self.folder/'ca.crt'))
        command('openssl','req','-newkey','rsa:2048','-nodes','-subj','/CN=timekeeper.test',
                '-keyout',str(self.folder/'server.key'),'-out',str(self.folder/'server.csr'))
        self.private('server.ext','subjectAltName=DNS:timekeeper.test,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n')
        command('openssl','x509','-req','-in',str(self.folder/'server.csr'),'-CA',str(self.folder/'ca.crt'),'-CAkey',str(self.folder/'ca.key'),
                '-CAcreateserial','-days','2','-extfile',str(self.folder/'server.ext'),'-out',str(self.folder/'server.crt'))
        command('openssl','pkcs12','-export','-inkey',str(self.folder/'server.key'),'-in',str(self.folder/'server.crt'),'-certfile',str(self.folder/'ca.crt'),
                '-out',str(self.folder/'server.p12'),'-passout','file:'+str(self.folder/'tls-password'))
        (self.folder/'server.p12').chmod(0o444)
        command('docker','run','-d','--name',self.database,'--network',self.network,'--network-alias','database','--env-file',str(dbenv),
                '-v',str(dbpassword)+':/run/secrets/timekeeper_database_password:ro',
                '-v',str(ROOT/'server/deploy/postgres/bootstrap.sql')+':/docker-entrypoint-initdb.d/10-timekeeper.sql:ro','postgres:18.6-bookworm')
        self.containers.append(self.database)
        for _ in range(100):
            result=subprocess.run(['docker','exec',self.database,'pg_isready','-h','127.0.0.1','-U','postgres'],capture_output=True)
            if result.returncode==0:break
            time.sleep(.2)
        else:raise RuntimeError('Isolated database was not ready')
        env=self.private('server.env','\n'.join([
            'TIMEKEEPER_SERVER_DB_URL=jdbc:postgresql://database:5432/timekeeper','TIMEKEEPER_SERVER_DB_USER=timekeeper',
            'TIMEKEEPER_SERVER_DB_PASSWORD_FILE=/run/secrets/database-password','TIMEKEEPER_SERVER_SETUP_FILE=/run/secrets/setup.json',
            'TIMEKEEPER_PUBLIC_ORIGIN='+self.origin,'TIMEKEEPER_CALENDAR_AUTOMATIC=false',
            'QUARKUS_HTTP_SSL_PORT=8443','QUARKUS_HTTP_SSL_CERTIFICATE_KEY_STORE_FILE=/run/secrets/server.p12',
            'QUARKUS_HTTP_SSL_CERTIFICATE_KEY_STORE_PASSWORD=isolated TLS fixture password'])+'\n')
        command('docker','run','-d','--init','--name',self.server,'--network',self.network,'--network-alias','timekeeper.test','--env-file',str(env),
                '-v',str(dbpassword)+':/run/secrets/database-password:ro','-v',str(self.folder/'setup.json')+':/run/secrets/setup.json:ro',
                '-v',str(self.folder/'server.p12')+':/run/secrets/server.p12:ro',self.args.server_image)
        self.containers.append(self.server)
        for _ in range(150):
            result=subprocess.run(['docker','exec',self.server,'curl','-fsS','http://127.0.0.1:8080/q/health/ready'],capture_output=True)
            if result.returncode==0:break
            if subprocess.check_output(['docker','inspect','--format','{{.State.Running}}',self.server],text=True).strip()!='true':
                raise RuntimeError('Isolated server exited before readiness')
            time.sleep(.3)
        else:raise RuntimeError('Isolated server was not ready')
        self.api=API(self.server,8080,self.origin)
        self.api.call('GET','/auth/status')
        account=self.api.call('POST','/auth/setup',{'code':code,'username':'journal-admin','password':'Disposable interoperability administrator password',
            'displayName':'Interoperability administrator','email':'journal@example.org'})
        identity=self.api.call('GET','/server')
        if identity['dirty'] is not False or not identity['commit'] or (self.args.expected_commit and identity['commit']!=self.args.expected_commit):
            raise AssertionError('Acceptance server source identity is dirty or unexpected')
        self.server_commit=identity['commit']
        issued=self.api.call('POST','/auth/tokens',{'name':'MySync interoperability','permissions':['read','write'],'expiresAt':None})
        self.token,self.token_id=issued['token'],issued['id'];self.owner=account['ownerId']
        self.api.put('/day-labels/2026-10-20',{'text':'Independent label'})
        self.api.put('/day-notes/2026-10-20/content',{'body':'# Calendar source æ\n'})
        self.api.put('/week-notes/2026-W44',{'body':'# Weekly fixture\n'})
        self.dav('PUT','2026-10-22.md',b'',none=True)
        self.api.put('/day-notes/2026-10-26/content',{'body':'# Legacy fixture\n'})
        # Represent a valid pre-canonical record, preserving its public version.
        command('docker','exec','-i',self.database,'sh','-ec',
            'export PGPASSWORD="$(cat /run/secrets/timekeeper_database_password)"; exec psql -h 127.0.0.1 -U timekeeper -d timekeeper -X -q -v ON_ERROR_STOP=1 -v owner="$1"',
            'legacy-fixture',self.owner,input=b"UPDATE tk_resource SET payload=(payload::jsonb-'markdown')::text WHERE owner_id=:'owner' AND entity_type='DAY_NOTE' AND payload::jsonb->>'date'='2026-10-26';")
        self.legacy_version=self.api.call('GET','/day-notes/2026-10-26/content')['version']
        self.asset=b'%PDF-1.7\n'+bytes(range(256))+b'\x00\xff\r\n'
        self.dav('PUT','assets/fixture.pdf',self.asset,none=True)
        self.make_client(self.client,self.token)

    def make_client(self,name,token):
        command('docker','run','-d','--init','--name',name,'--network',self.network,'--shm-size','256m',
                '-e','NODE_EXTRA_CA_CERTS=/certs/ca.crt','-v',str(self.folder/'ca.crt')+':/certs/ca.crt:ro',
                '--entrypoint','sh',self.args.client_image,'-ec',
                'mkdir -p /root/.pki/nssdb; certutil -N -d sql:/root/.pki/nssdb --empty-password; '
                'certutil -A -d sql:/root/.pki/nssdb -n Timekeeper -t "C,," -i /certs/ca.crt; '
                'exec xvfb-run -a /opt/obsidian/obsidian --no-sandbox --disable-dev-shm-usage --disable-gpu --remote-debugging-port=9222 --user-data-dir=/profile')
        self.containers.append(name)
        command('docker','cp',str(CLIENT/'cdp.mjs'),name+':/cdp.mjs')
        config={'remoteBackend':'nextcloud','nextcloudUrl':self.origin,'nextcloudUsername':'journal-user','nextcloudPassword':token,
                'nextcloudRemotePath':'/vault' if getattr(self.args,'vault',False) else '/','syncFolderMode':'vault-root','syncObsidianConfig':False,'logLevel':'off'}
        self.private(name+'-settings.json',json.dumps(config))
        self.evaluate('(async()=>{await app.plugins.setEnable(true);await app.plugins.enablePluginAndSave("mysync");'
            'const p=app.plugins.plugins.mysync;p.updateLogLevel("off");Object.assign(p.settings,'+json.dumps(config)+');await p.saveSettings();'
            'return p.manifest.version;})()',expected='3.0.7',client=name)

    def evaluate(self,expression,expected=None,client=None):
        raw=command('docker','exec','-i',client or self.client,'node','/cdp.mjs',input=json.dumps({'expression':expression}).encode())
        result=json.loads(raw)
        if expected is not None and result!=expected:raise AssertionError('Unexpected desktop operation result')
        return result

    def dav(self,method,path,body=None,match=None,none=False,expected=None,token=None,root="/dav/journal/"):
        auth=base64.b64encode(('journal-user:'+(token or self.token)).encode()).decode()
        args=['docker','exec','-i',self.server,'curl','--silent','--show-error','--max-time','30','--dump-header','-',
              '--request',method,'--header','Authorization: Basic '+auth]
        if match is not None:args+=['--header','If-Match: '+match]
        if none:args+=['--header','If-None-Match: *']
        if body is not None:args+=['--header','Content-Type: application/octet-stream','--data-binary','@-']
        raw=command(*args,'http://127.0.0.1:8080'+root+path,input=body)
        head,_,data=raw.partition(b'\r\n\r\n');status=int(head.split(b'\r\n',1)[0].split()[1])
        headers=BytesHeaderParser().parsebytes(head.split(b'\r\n',1)[1])
        if status not in (expected if expected is not None else range(200,300)):
            raise AssertionError('Unexpected guarded DAV status: '+str(status))
        return data,headers

    def action(self,method,client=None):
        # Invoke the real plugin's published command service, never a test sync
        # implementation. Wait for its existing operation before starting another.
        self.evaluate('(async()=>{const s=app.plugins.plugins.mysync.syncService;'
            'for(let i=0;s.isRunning()&&i<400;i++)await new Promise(r=>setTimeout(r,50));'
            'if(s.isRunning())throw new Error();await s.'+method+'();return true;})()',expected=True,client=client)

    def read_file(self,path,expected,client=None):
        actual=self.evaluate('(async()=>{const f=app.vault.getAbstractFileByPath('+json.dumps(path)+');'
            'return f?await app.vault.read(f):null;})()',client=client)
        if actual!=expected:raise AssertionError('Desktop file did not match expected state: '+path)

    def write_file(self,path,body,client=None):
        self.evaluate('(async()=>{const path='+json.dumps(path)+',body='+json.dumps(body)+';'
            'const f=app.vault.getAbstractFileByPath(path);if(f)await app.vault.modify(f,body);else await app.vault.create(path,body);'
            'return true;})()',expected=True,client=client)

    def read_binary(self,path,expected):
        actual=self.evaluate('(async()=>{const f=app.vault.getAbstractFileByPath('+json.dumps(path)+');'
            'return f?Array.from(new Uint8Array(await app.vault.readBinary(f))):null;})()')
        if actual!=list(expected):raise AssertionError('Desktop attachment did not match expected bytes')

    def push(self,client=None):
        self.action('syncNow',client);self.action('pushToRemote',client)

    def run(self):
        self.action('pullFromRemote')
        self.read_file('2026-10-20.md','# Calendar source æ\n')
        self.read_file('2026-10-22.md','')
        self.read_file('2026-W44.md','# Weekly fixture\n')
        self.read_file('2026-10-26.md','# Legacy fixture\n')
        if self.api.call('GET','/day-notes/2026-10-26/content')['version']!=self.legacy_version:raise AssertionError('Reading legacy Markdown changed its version')
        self.read_binary('assets/fixture.pdf',self.asset)
        changed=self.asset+b'\x00updated\xff'
        self.evaluate('(async()=>{await app.vault.modifyBinary(app.vault.getAbstractFileByPath("assets/fixture.pdf"),Uint8Array.from('+json.dumps(list(changed))+').buffer);return true;})()',expected=True)
        self.push()
        if self.dav('GET','assets/fixture.pdf')[0]!=changed:raise AssertionError('Binary attachment upload changed bytes')
        self.evaluate('(async()=>{await app.vault.delete(app.vault.getAbstractFileByPath("assets/fixture.pdf"));return true;})()',expected=True)
        self.push();self.dav('GET','assets/fixture.pdf',expected=(404,))
        print('Real HTTPS client pulled Calendar, weekly, empty and legacy notes; attachment round trip passed',flush=True)
        self.write_file('2026-10-21.md','# Client source β\n');self.push()
        if self.api.call('GET','/day-notes/2026-10-21/content')['body']!='# Client source β\n':raise AssertionError('Client creation did not reach the canonical note')
        self.api.put('/day-labels/2026-10-21',{'text':'Keep this independent label'})
        self.write_file('2026-10-21.md','# Client update γ\r\n');self.push()
        if self.dav('GET','2026-10-21.md')[0]!='# Client update γ\n'.encode():raise AssertionError('Server did not retain the released client’s LF upload bytes')
        print('Real HTTPS client created and conditionally updated notes',flush=True)
        metadata='---\r\ncustom: preserved\r\ntimekeeper-label: inert metadata\r\n---\r\n# Remote metadata β\r\n  spaces  \r\n'
        _,headers=self.dav('GET','2026-10-20.md')
        self.dav('PUT','2026-10-20.md',metadata.encode(),match=headers['ETag'])
        self.action('pullFromRemote');self.read_file('2026-10-20.md',metadata.replace('\r\n','\n'))
        unchanged,original=self.dav('GET','2026-10-20.md');self.push()
        retained,current=self.dav('GET','2026-10-20.md')
        if unchanged!=metadata.encode() or retained!=unchanged or current['ETag']!=original['ETag']:
            raise AssertionError('Unchanged server Markdown bytes or version changed during client scan')
        if self.api.call('GET','/day-labels/2026-10-20')['text']!='Independent label':raise AssertionError('Markdown metadata changed the independent label')
        _,before=self.dav('GET','2026-10-21.md')
        self.evaluate('(async()=>{await app.vault.delete(app.vault.getAbstractFileByPath("2026-10-21.md"));return true;})()',expected=True)
        self.push();self.dav('GET','2026-10-21.md',expected=(404,))
        if self.api.call('GET','/day-labels/2026-10-21')['text']!='Keep this independent label':raise AssertionError('Client deletion removed the label')
        self.write_file('2026-10-21.md','# Recreated δ\n');self.push()
        data,after=self.dav('GET','2026-10-21.md')
        if data!='# Recreated δ\n'.encode() or before['ETag']==after['ETag']:raise AssertionError('Client recreation reused content identity')
        _,remote=self.dav('GET','2026-10-22.md');self.dav('DELETE','2026-10-22.md',match=remote['ETag'])
        self.action('pullFromRemote');self.read_file('2026-10-22.md',None)
        self.write_file('2026-10-21.md','');self.push()
        if self.dav('GET','2026-10-21.md')[0]!=b'':raise AssertionError('Client clearing did not retain a present empty file')
        if self.api.call('GET','/day-labels/2026-10-21')['text']!='Keep this independent label':raise AssertionError('Clearing removed the independent label')
        self.write_file('2026-10-21.md','# Recreated δ\n');self.push()
        _,old=self.dav('GET','2026-10-20.md')
        metadata_only=metadata.replace('custom: preserved','custom: updated').replace('\r\n','\n')
        self.write_file('2026-10-20.md',metadata_only);self.push()
        actual,new=self.dav('GET','2026-10-20.md')
        if actual!=metadata_only.encode() or old['ETag']==new['ETag']:raise AssertionError('Metadata-only edit was lost or unversioned')
        metadata=metadata_only
        print('Unchanged CRLF bytes, metadata-only edits, independent labels, clearing, deletion and recreation passed',flush=True)
        second=self.prefix+'-second';self.make_client(second,self.token)
        self.action('pullFromRemote',second);self.read_file('2026-10-21.md','# Recreated δ\n',second)
        self.write_file('2026-10-23.md','# Device two\n',second);self.push(second)
        self.action('pullFromRemote');self.read_file('2026-10-23.md','# Device two\n')
        self.write_file('2026-10-23.md','# Pending device one\n');self.action('syncNow')
        self.write_file('2026-10-23.md','# Published device two\n',second);self.push(second)
        self.action('pushToRemote')
        self.read_file('2026-10-23.md','# Pending device one\n')
        if self.dav('GET','2026-10-23.md')[0]!=b'# Published device two\n':raise AssertionError('Conflict silently overwrote the remote note')
        found=self.evaluate('(async()=>{const s=app.plugins.plugins.mysync.syncService;const c=(await s.listActiveConflicts()).find(c=>c.path==="2026-10-23.md");'
            'if(!c)return false;await s.resolveConflict(c._id,"keep-remote");return true;})()')
        if not found:raise AssertionError('The real client did not expose the expected conflict')
        self.read_file('2026-10-23.md','# Published device two\n')
        print('Two real devices preserved and resolved a concurrent-edit conflict',flush=True)
        command('docker','network','disconnect',self.network,self.client)
        self.write_file('2026-10-24.md','# Offline pending file\n');self.push()
        self.read_file('2026-10-24.md','# Offline pending file\n');self.dav('GET','2026-10-24.md',expected=(404,))
        command('docker','network','connect',self.network,self.client)
        self.push()
        if self.dav('GET','2026-10-24.md')[0]!=b'# Offline pending file\n':raise AssertionError('Client retry lost an interrupted local edit')
        print('Real client retained offline work and retried after reconnection',flush=True)
        self.api.call('POST','/admin/users',{'username':'other-journal','password':'Disposable other journal password','displayName':'Other journal account','email':'other@example.org','administrator':False})
        other=API(self.server,8080,self.origin);other.login('other-journal','Disposable other journal password')
        other.put('/day-notes/2026-10-25/content',{'body':'# Other account only\n'})
        other_token=other.call('POST','/auth/tokens',{'name':'Other client','permissions':['read','write'],'expiresAt':None})['token']
        third=self.prefix+'-other';self.make_client(third,other_token);self.action('pullFromRemote',third)
        self.read_file('2026-10-25.md','# Other account only\n',third);self.read_file('2026-10-20.md',None,third)
        self.dav('GET','2026-10-25.md',expected=(404,))
        self.api.call('DELETE','/auth/tokens/'+self.token_id)
        self.action('pullFromRemote');self.read_file('2026-10-20.md',metadata.replace('\r\n','\n'))
        self.write_file('2026-10-21.md','# Rejected revoked-token edit\n');self.push()
        if self.api.call('GET','/day-notes/2026-10-21/content')['body']!='# Recreated δ\n':raise AssertionError('Revoked client changed the server note')
        self.read_file('2026-10-21.md','# Rejected revoked-token edit\n')
        print('Actual client account isolation and token revocation passed',flush=True)
        # This records exact observed boundaries; it does not claim lossless
        # client-side CRLF copies or platform coverage that was not exercised.
        receipt={'serverCommit':self.server_commit,'serverImage':self.args.server_image,'clientImage':self.args.client_image,'obsidianVersion':'1.14.5','mySyncVersion':'3.0.7',
            'realDesktopAndUnmodifiedRelease':True,'verifiedHttps':True,'dailyWeeklyEmptyAndLegacyPull':True,'canonicalCreateAndGuardedUpdate':True,
            'clientTextUploadLineEndings':'LF','unchangedServerMarkdownBytesAndEtag':True,'independentLabelsPreserved':True,
            'binaryAttachmentRoundTripAndDelete':True,'metadataOnlyChangeVersioned':True,'clearingPreservesFileAndLabel':True,'localDeleteAndRecreate':True,'remoteDeletePropagation':True,'twoDeviceConflictPreservedAndResolved':True,
            'offlineWorkAndRetry':True,'accountIsolation':True,'tokenRevocationPreservesLocalWork':True}
        self.args.output.parent.mkdir(parents=True,exist_ok=True);self.args.output.write_text(json.dumps(receipt,indent=2)+'\n')

    def run_vault(self):
        def dav(method,path,body=None,**kwargs):return self.dav(method,path,body,root='/dav/vault/',**kwargs)
        self.api.call('GET','/notes');dav('MKCOL','Research/');dav('MKCOL','Research/Nested/')
        exact='---\r\nunknown: [true, "keep"]\r\n---\r\n[[Other note]]  \r\n![Attachment](asset.bin)\r\n'
        dav('PUT','Research/Nested/Unicode-%C3%A6%2B100%25.md',exact.encode(),none=True)
        dav('PUT','Research/Nested/asset.bin',self.asset,none=True);dav('PUT','Research/empty.md',b'',none=True)
        self.action('pullFromRemote');self.read_file('Research/Nested/Unicode-æ+100%.md',exact.replace('\r\n','\n'));self.read_binary('Research/Nested/asset.bin',self.asset);self.read_file('Research/empty.md','')
        self.read_file('journal/2026-10-20.md','# Calendar source æ\n');self.read_file('journal/2026-W44.md','# Weekly fixture\n')
        before,tag=dav('GET','Research/Nested/Unicode-%C3%A6%2B100%25.md');self.push();after,current=dav('GET','Research/Nested/Unicode-%C3%A6%2B100%25.md')
        if before!=after or tag['ETag']!=current['ETag']:raise AssertionError('A no-op vault scan changed canonical bytes/version')
        self.write_file('Research/Nested/Local.md','# Local nested note\n');self.push()
        if dav('GET','Research/Nested/Local.md')[0]!=b'# Local nested note\n':raise AssertionError('Nested client upload lost')
        _,tag=dav('GET','Research/Nested/Local.md');dav('PUT','Research/Nested/Local.md',b'# Remote nested edit\n',match=tag['ETag']);self.action('pullFromRemote');self.read_file('Research/Nested/Local.md','# Remote nested edit\n')
        self.evaluate('(async()=>{await app.vault.rename(app.vault.getAbstractFileByPath("Research/Nested/Local.md"),"Research/Nested/Renamed.md");return true;})()',expected=True);self.push()
        if dav('GET','Research/Nested/Renamed.md')[0]!=b'# Remote nested edit\n':raise AssertionError('Released plugin rename lost bytes')
        dav('GET','Research/Nested/Local.md',expected=(404,))
        self.evaluate('(async()=>{await app.vault.delete(app.vault.getAbstractFileByPath("Research/empty.md"));return true;})()',expected=True);self.push();dav('GET','Research/empty.md',expected=(404,))
        self.write_file('Research/empty.md','');self.push()
        if dav('GET','Research/empty.md')[0]!=b'':raise AssertionError('Nested empty recreate failed')
        self.write_file('journal/2026-10-20.md','# Journal through vault\n');self.push()
        if self.api.call('GET','/day-notes/2026-10-20/content')['body']!='# Journal through vault\n' or self.api.call('GET','/day-labels/2026-10-20')['text']!='Independent label':raise AssertionError('Canonical journal/label association changed')
        command('docker','network','disconnect',self.network,self.client);self.write_file('Research/Offline.md','Offline local bytes\n');self.push();self.read_file('Research/Offline.md','Offline local bytes\n');dav('GET','Research/Offline.md',expected=(404,));command('docker','network','connect',self.network,self.client);self.push()
        if dav('GET','Research/Offline.md')[0]!=b'Offline local bytes\n':raise AssertionError('Offline nested edit lost')
        print('Released Obsidian plugin passed nested vault, attachments, exact no-op, rename, empty/delete/recreate and offline retry',flush=True)
        second=self.prefix+'-vault-second';self.make_client(second,self.token);self.action('pullFromRemote',second);self.read_file('Research/Nested/Renamed.md','# Remote nested edit\n',second)
        self.write_file('Research/Nested/Renamed.md','Pending device one\n');self.action('syncNow');self.write_file('Research/Nested/Renamed.md','Published device two\n',second);self.push(second);self.action('pushToRemote')
        self.read_file('Research/Nested/Renamed.md','Pending device one\n')
        if dav('GET','Research/Nested/Renamed.md')[0]!=b'Published device two\n':raise AssertionError('Concurrent vault edit silently overwritten')
        found=self.evaluate('(async()=>{const s=app.plugins.plugins.mysync.syncService;const c=(await s.listActiveConflicts()).find(c=>c.path==="Research/Nested/Renamed.md");if(!c)return false;await s.resolveConflict(c._id,"keep-remote");return true;})()')
        if not found:raise AssertionError('Nested conflict not exposed')
        self.read_file('Research/Nested/Renamed.md','Published device two\n')
        self.api.call('DELETE','/auth/tokens/'+self.token_id);self.write_file('Research/Nested/Renamed.md','Retained revoked-token draft\n');self.push();self.read_file('Research/Nested/Renamed.md','Retained revoked-token draft\n')
        if self.api.call('GET','/notes/resolve?path=Research%2FNested%2FRenamed.md')['path']!='Research/Nested/Renamed.md':raise AssertionError('Revocation changed namespace')
        receipt={'serverCommit':self.server_commit,'serverImage':self.args.server_image,'clientImage':self.args.client_image,'obsidianVersion':'1.14.5','mySyncVersion':'3.0.7','realDesktopAndUnmodifiedRelease':True,'verifiedHttps':True,'hierarchicalVault':True,'unicodePlusPercent':True,'nestedBinaryRoundTrip':True,'unchangedRawMarkdownAndEtag':True,'clientTextUploadLineEndings':'LF','nestedManualTwoWay':True,'pluginRenameWorkflow':True,'emptyDeleteRecreate':True,'canonicalJournalAndIndependentLabel':True,'offlineWorkAndRetry':True,'twoDeviceConflictPreservedAndResolved':True,'revocationRetainsLocalDraft':True}
        self.args.output.parent.mkdir(parents=True,exist_ok=True);self.args.output.write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt),flush=True)

    def close(self):
        for name in reversed(self.containers):subprocess.run(['docker','rm','-f','-v',name],capture_output=True)
        if self.network_created:subprocess.run(['docker','network','rm',self.network],capture_output=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-image',required=True)
    parser.add_argument('--expected-commit',help='Require the packaged server to report this clean source commit')
    parser.add_argument('--client-image',default='timekeeper-mysync-client:obsidian-1.14.5-mysync-3.0.7')
    parser.add_argument('--output',type=Path,default=ROOT/'server/target/acceptance/mysync.json')
    parser.add_argument('--vault',action='store_true',help='Verify the hierarchical Notes namespace with the released plugin')
    parser.add_argument('--keep-fixture',action='store_true',help='Keep only these disposable fixtures for local diagnosis')
    args=parser.parse_args();args.output.unlink(missing_ok=True)
    folder=Path(tempfile.mkdtemp(prefix='timekeeper-mysync-421-'));folder.chmod(0o700)
    fixture=Fixture(folder,args)
    descriptor=folder/'fixture.json';descriptor.write_text(json.dumps({'folder':str(folder),'network':fixture.network,'server':fixture.server,'database':fixture.database,'client':fixture.client}));descriptor.chmod(0o600)
    print('Disposable fixture: '+str(folder),flush=True)
    try:
        fixture.start()
        if args.vault:fixture.run_vault()
        else:fixture.run()
    finally:
        if not args.keep_fixture:
            fixture.close();shutil.rmtree(folder)


if __name__=='__main__':main()
