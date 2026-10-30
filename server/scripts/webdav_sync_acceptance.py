#!/usr/bin/env python3
"""Exercise the actual manual WebDAV CLI against a disposable packaged HTTPS server."""
import argparse
from http.cookiejar import CookieJar
import hashlib
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
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPCookieProcessor, HTTPSHandler, urlopen
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'server/web/tests'))
from recovery_fixture import RecoveryFixture
from configure_accounts import create_challenge

class API:
    def __init__(self,base,certificate):
        self.base=base;self.csrf='';self.opener=build_opener(HTTPSHandler(context=ssl.create_default_context(cafile=str(certificate))),HTTPCookieProcessor(CookieJar()))
    def call(self,method,path,body=None,etag=None,expected=200):
        headers={'Origin':self.base}
        if self.csrf:headers['X-CSRF-Token']=self.csrf
        if etag:headers['If-Match']=etag
        if isinstance(body,bytes):headers['Content-Type']='application/octet-stream'
        elif body is not None:headers['Content-Type']='application/json';body=json.dumps(body).encode()
        try:
            with self.opener.open(Request(self.base+'/api/v1'+path,data=body,method=method,headers=headers),timeout=30) as response:
                status=response.status;value=response.read();tag=response.headers.get('ETag');kind=response.headers.get('Content-Type','')
        except HTTPError as error:
            status=error.code;error.close();value=b'';tag=None;kind=''
        if status!=expected:raise AssertionError('Disposable API '+method+' '+path.split('?')[0]+' returned '+str(status))
        result=json.loads(value) if value and 'json' in kind else value
        if isinstance(result,dict) and result.get('csrf'):self.csrf=result['csrf']
        return result,tag

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--distribution',type=Path,required=True);parser.add_argument('--java',default='java');parser.add_argument('--client',type=Path,help='Development-only helper override; final certification uses the packaged helper');parser.add_argument('--output',type=Path,default=ROOT/'server/target/acceptance/webdav-manual.json');args=parser.parse_args()
    checksum=hashlib.sha256(args.distribution.read_bytes()).hexdigest()
    if args.distribution.with_suffix('.zip.sha256').read_text().split()[0]!=checksum:raise AssertionError('Distribution checksum mismatch')
    with tempfile.TemporaryDirectory(prefix='timekeeper-manual-vault-') as directory:
        data=Path(directory);data.chmod(0o700)
        with zipfile.ZipFile(args.distribution) as archive:
            if any(not name.startswith('timekeeper-server/') or '..' in Path(name).parts or Path(name).is_absolute() for name in archive.namelist()):raise AssertionError('Unexpected packaged path')
            manifest=json.loads(archive.read('timekeeper-server/timekeeper-release.json'));archive.extractall(data)
        client=args.client or data/'timekeeper-server/webdav_sync.py'
        if not client.is_file():raise AssertionError('The packaged manual WebDAV helper is missing')
        registry=data/'tokens.json';registry.write_text('[]');registry.chmod(0o600);code,_=create_challenge(data/'setup.json')
        with socket.socket() as port_socket:port_socket.bind(('127.0.0.1',0));port=port_socket.getsockname()[1]
        fixture=RecoveryFixture(data,port,https=True)
        for server in fixture.servers:
            handler=server.RequestHandlerClass
            if hasattr(handler,'relay'):
                for verb in ['PROPFIND','MKCOL','MOVE']:setattr(handler,'do_'+verb,handler.relay)
        certificate=data/'fixture-cert.pem';base=fixture.base;process=None;log=(data/'server.log').open('w')
        environment={**os.environ,'TIMEKEEPER_SERVER_DB_URL':'jdbc:h2:'+str(data/'database'),'TIMEKEEPER_SERVER_TOKENS_FILE':str(registry),'TIMEKEEPER_SERVER_SETUP_FILE':str(data/'setup.json'),'TIMEKEEPER_PUBLIC_ORIGIN':base,**fixture.environment}
        def start():
            nonlocal process
            process=subprocess.Popen([args.java,'-Dquarkus.http.port='+str(port),'-jar',str(data/'timekeeper-server/quarkus-run.jar')],env=environment,stdout=log,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+40
            while time.monotonic()<deadline:
                if process.poll() is not None:raise RuntimeError('Disposable server exited during startup')
                try:
                    with urlopen('http://127.0.0.1:'+str(port)+'/q/health/ready',timeout=1) as response:
                        if response.status==200:return
                except (URLError,TimeoutError):pass
                time.sleep(.1)
            raise RuntimeError('Disposable server readiness timeout')
        def stop():
            nonlocal process
            if process is not None:process.terminate();process.wait(timeout=30);process=None
        try:
            start();api=API(base,certificate);api.call('GET','/auth/status');api.call('POST','/auth/setup',{'code':code,'username':'manual-vault-admin','password':secrets.token_urlsafe(32),'displayName':'Manual fixture','email':'manual@example.invalid'})
            information,_=api.call('GET','/server')
            if information['commit']!=manifest['commit'] or information['dirty']:raise AssertionError('Unexpected or dirty server source identity')
            issued,_=api.call('POST','/auth/tokens',{'name':'Manual vault acceptance','permissions':['read','write'],'expiresAt':None},expected=201);token=issued['token']
            api.call('POST','/notes/folders',{'path':'Manual'},expected=201)
            folder=data/'ordinary-markdown';folder.mkdir();control=data/'ordinary-state';(folder/'Nested').mkdir()
            source=b'---\r\nunknown: [true, "keep"]\r\n---\r\n[[Other note]]  \r\n<https://example.invalid/x>\r\n---\r\n'
            (folder/'Nested'/'A+B 100%.md').write_bytes(source);(folder/'empty.md').write_bytes(b'');binary=b'\x00\xff\r\n'+bytes(range(256));(folder/'Nested'/'asset.bin').write_bytes(binary)
            remote=base+'/dav/vault/Manual/'
            def run(extra=(),expected=0,credential=None,url=None,local_folder=None,state=None):
                command=[sys.executable,str(client),'--url',url or remote,'--folder',str(local_folder or folder),'--state-dir',str(state or control),'--ca-file',str(certificate),*extra]
                result=subprocess.run(command,env={**os.environ,'WEBDAV_TOKEN':credential or token},capture_output=True,text=True,timeout=90)
                if result.returncode!=expected:raise AssertionError('Actual manual WebDAV CLI returned an unexpected status: '+result.stderr.strip())
                return json.loads(result.stdout) if result.returncode==0 else result.stderr
            def apply(extra=(),**kwargs):
                preview=run(extra,**kwargs)
                if preview['conflicts']:raise AssertionError('Unexpected manual synchronization conflict')
                return run([*extra,'--apply',preview['review']],**kwargs)
            apply();note,_=api.call('GET','/notes/resolve?'+urlencode({'path':'Manual/Nested/A+B 100%.md'}));first_id=note['id']
            assert api.call('GET','/notes/entries/'+first_id+'/content')[0]==source
            assert run()['actions']==[]
            updated=source+b'Local edit\r\n';(folder/'Nested'/'A+B 100%.md').write_bytes(updated);apply();assert api.call('GET','/notes/entries/'+first_id+'/content')[0]==updated
            current,etag=api.call('GET','/notes/entries/'+first_id+'/content');api.call('PUT','/notes/entries/'+first_id+'/content',b'Remote edit',etag);apply();assert (folder/'Nested'/'A+B 100%.md').read_bytes()==b'Remote edit'
            (folder/'Nested'/'A+B 100%.md').rename(folder/'Nested'/'Renamed.md');preview=run();assert any(action['operation']=='move-remote' for action in preview['actions']);run(['--apply',preview['review']]);renamed,_=api.call('GET','/notes/resolve?path=Manual%2FNested%2FRenamed.md');assert renamed['id']==first_id
            (folder/'Nested'/'Renamed.md').write_bytes(b'Local concurrent draft');review=run();current,etag=api.call('GET','/notes/entries/'+first_id+'/content');api.call('PUT','/notes/entries/'+first_id+'/content',b'Remote concurrent draft',etag)
            run(['--apply',review['review']],expected=1);assert (folder/'Nested'/'Renamed.md').read_bytes()==b'Local concurrent draft';assert api.call('GET','/notes/entries/'+first_id+'/content')[0]==b'Remote concurrent draft'
            assert run()['conflicts'];apply(['--prefer-remote','Nested/Renamed.md']);assert (folder/'Nested'/'Renamed.md').read_bytes()==b'Remote concurrent draft'
            (folder/'empty.md').unlink();preview=run();assert preview['deletionApprovalRequired'];run(['--apply',preview['review']],expected=1);apply(['--allow-deletes']);api.call('GET','/notes/resolve?path=Manual%2Fempty.md',expected=404)
            (folder/'empty.md').write_bytes(b'Recreated');apply();assert (folder/'Nested'/'asset.bin').read_bytes()==binary
            # The selected local directory must not silently become a new empty
            # root after a missing mount, path replacement, or interruption.
            folder.rename(data/'retained-local');folder.mkdir();run(expected=1);folder.rmdir();(data/'retained-local').rename(folder)
            stop();start();assert run()['actions']==[]
            read_only,_=api.call('POST','/auth/tokens',{'name':'Read-only manual fixture','permissions':['read'],'expiresAt':None},expected=201)
            (folder/'Denied.md').write_bytes(b'Keep local write');preview=run(credential=read_only['token']);run(['--apply',preview['review']],expected=1,credential=read_only['token']);assert (folder/'Denied.md').read_bytes()==b'Keep local write';api.call('GET','/notes/resolve?path=Manual%2FDenied.md',expected=404);(folder/'Denied.md').unlink();apply(['--resume'])
            api.call('DELETE','/auth/tokens/'+issued['id'],expected=204);run(expected=1)
            receipt={'serverCommit':manifest['commit'],'distributionSha256':checksum,'clientVersion':'1.0','clientSha256':hashlib.sha256(client.read_bytes()).hexdigest(),'packagedClient':args.client is None,'python':sys.version.split()[0],'verifiedHttps':True,'ordinaryNestedFolder':True,'selectedRemoteRoot':True,'exactMarkdownAndBinary':True,'emptyFiles':True,'manualTwoWayEdits':True,'guardedMovePreservesDocumentId':True,'concurrentVersionsPreserved':True,'explicitConflictResolution':True,'explicitDeletionReview':True,'deleteRecreate':True,'missingLocalRootRefused':True,'restart':True,'readOnlyRejected':True,'revokedTokenRejected':True,'passed':True}
            args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
        finally:stop();fixture.close();log.close()

if __name__=='__main__':main()
