#!/usr/bin/env python3
"""Verify schema-6/7 -> schema-9 label separation and prospective history across actual packaged H2 JVMs.

Both distributions and all credentials/data are local disposable fixtures.
No request is made to a user's server. Supply a checksum-verified older package.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import zipfile


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--old-distribution',type=Path,required=True)
    parser.add_argument('--distribution',type=Path,required=True)
    args=parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='timekeeper-label-upgrade-') as folder:
        root=Path(folder);packages={};identities={}
        for key,path in [('old',args.old_distribution),('new',args.distribution)]:
            checksum=path.with_suffix('.zip.sha256').read_text().split()[0]
            if hashlib.sha256(path.read_bytes()).hexdigest()!=checksum:raise AssertionError('Distribution checksum mismatch')
            target=root/key
            with zipfile.ZipFile(path) as archive:
                if any(not name.startswith('timekeeper-server/') or '..' in Path(name).parts for name in archive.namelist()):raise AssertionError('Unsafe distribution path')
                identities[key]=json.loads(archive.read('timekeeper-server/timekeeper-release.json'))
                archive.extractall(target)
            packages[key]=target/'timekeeper-server/quarkus-run.jar'
        if identities['old']['serverSchema'] not in (6,7) or identities['new']['serverSchema']!=10:raise AssertionError('Expected schema-6/7 and schema-10 packages')
        token=secrets.token_urlsafe(32);registry=root/'tokens.json'
        registry.write_text(json.dumps([{'ownerId':'label-upgrade','sha256':hashlib.sha256(token.encode()).hexdigest(),'permissions':['read','write']}]))
        registry.chmod(0o600)
        environment={**os.environ,'TIMEKEEPER_SERVER_DB_URL':'jdbc:h2:'+str(root/'database'),'TIMEKEEPER_SERVER_TOKENS_FILE':str(registry),'TIMEKEEPER_PUBLIC_ORIGIN':'NONE','TIMEKEEPER_SERVER_SETUP_FILE':'NONE'}
        process=None;log=None;counter=0;base=None
        def stop():
            nonlocal process,log
            if process:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
                process=None
            if log:log.close();log=None
        def start(key,upgrade=False,blocked=False):
            nonlocal process,log,counter,base
            stop();counter+=1
            with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
            base='http://127.0.0.1:'+str(port);log=(root/('server-'+str(counter)+'.log')).open('w')
            process=subprocess.Popen(['java','-Dquarkus.http.port='+str(port),'-Dtimekeeper.schema.allow-upgrade='+str(upgrade).lower(),'-jar',str(packages[key])],env=environment,stdout=log,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+35
            while time.monotonic()<deadline:
                if process.poll() is not None:
                    if blocked and process.returncode!=0:return
                    raise AssertionError('Unexpected packaged server exit')
                try:
                    with urlopen(base+'/q/health/ready',timeout=.5) as response:
                        if response.status==200:
                            if blocked:raise AssertionError('Incompatible server unexpectedly accepted storage')
                            return
                except (HTTPError,URLError,TimeoutError):pass
                time.sleep(.1)
            raise AssertionError('Packaged server did not reach expected startup state')
        def request(path,method='GET',body=None,etag=None,status=200,dav=False):
            auth='Basic '+base64.b64encode(('fixture:'+token).encode()).decode() if dav else 'Bearer '+token
            headers={'Authorization':auth}
            if body is not None:headers['Content-Type']='application/json'
            if etag:headers['If-Match']=etag
            data=None if body is None else json.dumps(body).encode()
            try:response=urlopen(Request(base+path,data=data,headers=headers,method=method),timeout=10)
            except HTTPError as error:response=error
            with response:
                if response.status!=status:raise AssertionError('Unexpected fixture API status: '+str(response.status))
                raw=response.read();value=json.loads(raw) if raw and response.headers.get('Content-Type','').startswith('application/json') else raw
                return value,response.headers.get('ETag')
        try:
            day='2026-10-27';legacy='/api/v1/day-notes/'+day;label='/api/v1/day-labels/'+day;content=legacy+'/content';dav='/dav/journal/'+day+'.md'
            start('old');_,tag=request(legacy)
            original,_=request(legacy,'PUT',{'text':'Migration label','body':'# Existing note\r\n  exact body  \n'},tag)
            document,_=request(dav,dav=True);stop()
            start('new',blocked=True)
            start('old');retained,_=request(legacy)
            if retained['text']!='Migration label' or retained['version']!=original['version']:raise AssertionError('Rejected upgrade changed old labels')
            start('new',upgrade=True);migrated,label_tag=request(label);note,note_tag=request(content)
            history_path=legacy+'/history';initial_history,_=request(history_path)
            if initial_history['items'] or not initial_history['legacyGap']:raise AssertionError('Upgrade fabricated history for existing notes')
            if migrated['text']!='Migration label' or note['body']!=original['body'] or note['version']!=original['version']:raise AssertionError('Authorized upgrade changed a label, note or note version')
            if request(dav,dav=True)[0]!=document:raise AssertionError('Migration changed canonical Markdown bytes')
            changed,new_label_tag=request(label,'PUT',{'text':'Independent label'},label_tag)
            if request(content)[1]!=note_tag or request(dav,dav=True)[0]!=document:raise AssertionError('Label edit changed the note or its ETag')
            _,cleared_tag=request(content,'PUT',{'body':''},note_tag)
            if request(label)[1]!=new_label_tag:raise AssertionError('Clearing a body changed the label')
            request(content,'DELETE',etag=cleared_tag,status=204)
            if request(label)[0]['text']!='Independent label':raise AssertionError('Deleting a note removed the label')
            request(dav,dav=True,status=404)
            history_before,_=request(history_path)
            if len(history_before['items'])!=3:raise AssertionError('Actual label, body-clear and delete changes were not recorded')
            start('new');after,_=request(label)
            if request(history_path)[0]['items']!=history_before['items']:raise AssertionError('Restart changed diagnostic events')
            if after!=changed or not request(content)[0]['deleted']:raise AssertionError('Restart changed independent state')
            start('old',blocked=True)
            print(json.dumps({'oldCommit':identities['old']['commit'],'newCommit':identities['new']['commit'],'sourceSchema':identities['old']['serverSchema'],'targetSchema':10,'upgradeRequiresAuthorization':True,'oldBinaryRejected':True,'historyDoesNotFabricateLegacyEvents':True,'historySurvivesRestart':True,'labelAndNotePreservedDuringMigration':True,'canonicalMarkdownPreserved':True,'independentEtags':True,'bodyClearAndNoteDeleteRetainLabel':True,'restartPreservesState':True,'passed':True}))
        finally:stop()


if __name__=='__main__':main()
