#!/usr/bin/env python3
"""Private account archive restore across actual JVMs, restart and protected PostgreSQL backup.

All users, credentials, files and Docker resources are disposable. HTTP models the
trusted TLS proxy; real HTTPS browser behavior is exercised by web acceptance.
"""
import argparse
import base64
import csv
import hashlib
import io
import json
from pathlib import Path
import tempfile
import time
import uuid
from urllib.request import Request,urlopen
import zipfile

from passkey_replica_acceptance import Replicas,Browser,Authenticator,expect,encoded,PASSWORD,VPN,ROOT


class ArchiveReplicas(Replicas):
    def __init__(self,args,folder):
        super().__init__(args,folder)
        self.network='timekeeper-archives-'+self.suffix;self.database=self.network+'-db'
    def restart(self,index):
        before=self.bases[index]
        self.command('docker','restart',self.instances[index])
        # Docker Desktop can allocate a different ephemeral published port when
        # restarting the same container. Production deployments use fixed ports.
        self.bases[index]='http://127.0.0.1:'+self.command('docker','port',self.instances[index],'8080/tcp').stdout.strip().rsplit(':',1)[1]
        self.restart_port_changed=before!=self.bases[index]
        for _ in range(160):
            try:
                with urlopen(self.bases[index]+'/q/health/ready',timeout=1) as reply:
                    if reply.status==200:return
            except OSError:pass
            time.sleep(.1)
        logs=self.command('docker','logs',self.instances[index],check=False)
        path=self.args.output.with_suffix('.failure.log');path.parent.mkdir(parents=True,exist_ok=True);path.write_text(logs.stdout[-20000:]+logs.stderr[-20000:]);path.chmod(0o600)
        raise RuntimeError('Disposable archive replica did not restart; see its private failure log')


def download(system,index,browser,*,token=None,owner=None,with_zip=False):
    selected={'Origin':system.origins[index],'Accept':'application/zip','X-Timekeeper-Owner':encoded((owner or browser.owner).encode())}
    if token:selected['Authorization']='Bearer '+token
    else:selected['Cookie']='; '.join(key+'='+value for key,value in browser.cookies.items())
    with urlopen(Request(system.bases[index]+'/api/v1/data/account/export',headers=selected),timeout=60) as reply:
        if reply.status!=200 or reply.headers.get_content_type()!='application/zip' or reply.headers.get('Cache-Control')!='no-store':raise AssertionError('Private archive response boundary failed')
        if reply.headers.get('X-Timekeeper-Owner')!=selected['X-Timekeeper-Owner']:raise AssertionError('Archive response changed its authenticated owner')
        binary=reply.read()
    with zipfile.ZipFile(io.BytesIO(binary)) as archive:
        source=archive.read('archive.json');value=json.loads(source);manifest=json.loads(archive.read('manifest.json'))
        for name in ('clients','projects','tasks','labels','activities','workSessions'):
            if manifest['counts'][name]!=len(value['work'][name]):raise AssertionError('Archive manifest and snapshot disagree')
            with io.TextIOWrapper(archive.open(name+'.csv'),encoding='utf-8',newline='') as stream:
                if sum(1 for _ in csv.reader(stream))-1!=manifest['counts'][name]:raise AssertionError('CSV omitted an owned work record')
        if value['account']['ownerId']!=(owner or browser.owner):raise AssertionError('Archive exposed another owner')
    return (value,source,binary) if with_zip else (value,source)


def exercise(system):
    checks=[]
    def passed(name):checks.append(name);print('PASS: '+name,flush=True)
    def post(index,browser,path,data):return expect(system.request(index,browser,path,data,headers={'Idempotency-Key':str(uuid.uuid4())}),201,'Create owned fixture record')
    def seed(browser,name,zone):
        profile=system.request(1,browser,'/me');expect(profile,200,'Current account preference')
        expect(system.request(2,browser,'/me',{'zoneId':zone},method='PUT',headers={'If-Match':profile[2]['ETag']}),200,'Owned time zone')
        client=post(1,browser,'/clients',{'name':name+' client','color':'#246bad'})
        project=post(2,browser,'/projects',{'name':name+' project','clientId':client['id']})
        task=post(1,browser,'/tasks',{'projectId':project['id'],'parentTaskId':None,'summary':name+' task','url':'https://tracker.example.invalid/'+name,
            'externalReferences':[{'providerId':'archive-fixture','repositoryId':'https://tracker.example.invalid','externalId':name+'-1','externalUrl':None}]})
        label=post(2,browser,'/labels',{'name':name+' label','color':'#114455'})
        interval={'start':'2026-10-12T07:00:00.123456789Z','end':'2026-10-12T08:00:00.987654321Z','summary':name+' notes'}
        post(1,browser,'/activities',{**interval,'taskId':task['id'],'projectId':project['id'],'labelIds':[label['id']]})
        post(2,browser,'/work-sessions',{**interval,'clientId':client['id'],'projectId':project['id'],'summary':name+' work\n\n'+name+' notes'})

    alice=system.browser();expect(system.request(1,alice,'/auth/setup',{'code':system.code,'username':'alice','password':PASSWORD,'displayName':'Alice','email':'alice@example.invalid'}),200,'First administrator setup')
    information=[expect(system.request(index,alice,'/server'),200,'Actual replica identity') for index in (1,2)]
    system.server_commit=information[0]['commit']
    if any(value['commit']!=system.server_commit or value['dirty'] for value in information):raise AssertionError('Archive replicas have inconsistent provenance')
    if system.args.expected_commit and system.server_commit!=system.args.expected_commit:raise AssertionError('Archive image does not contain the expected source')
    expect(system.request(2,alice,'/admin/users',{'username':'bob','password':PASSWORD,'displayName':'Bob','administrator':False,'email':'bob@example.invalid'}),201,'Independent target account')
    bob=system.password_login('bob',index=2);seed(alice,'alice','Europe/Oslo');seed(bob,'bob','UTC')
    source_key=Authenticator(system.folder);target_key=Authenticator(system.folder);system.enroll(alice,source_key);target_record=system.enroll(bob,target_key)
    source_token=expect(system.request(1,alice,'/auth/tokens',{'name':'Source Eclipse','permissions':['read','write'],'expiresAt':None}),201,'Source personal token')['token']
    target_token=expect(system.request(2,bob,'/auth/tokens',{'name':'Target Eclipse','permissions':['read','write'],'expiresAt':None}),201,'Target personal token')['token']
    source,raw=download(system,1,alice)
    forbidden=[source_token.encode(),hashlib.sha256(source_token.encode()).hexdigest().encode(),encoded(source_key.credential).encode(),source_key.handle.encode(),encoded(source_key.public).encode()]
    if any(secret in raw for secret in forbidden):raise AssertionError('Account download included credential material')
    if len(source['account']['passkeyMetadata'])!=1 or not source['account']['administrator']:raise AssertionError('Owner credential-management metadata was omitted')
    passed('complete JSON and CSV snapshots remain owner-scoped and exclude actual token and passkey material')

    # Two large inert audit rows make the upload cross its normal request chunk
    # boundary. Forged source import authority must stay an audit copy.
    source['archiveId']=str(uuid.uuid4());forged_job=str(uuid.uuid4())
    source['importHistory']=[{'entityType':'IMPORT_JOB','data':{'id':forged_job,'ownerId':alice.owner,'state':'IMPORTED','note':'a'*600000}},
        {'entityType':'IMPORT_USE','data':{'jobId':forged_job,'note':'b'*600000}}]
    binary=json.dumps(source,separators=(',',':')).encode()
    def stage(value,index=1):
        data=json.dumps(value,separators=(',',':')).encode();upload=post(index,bob,'/data/account/uploads',{'fileName':'archive.json','mediaType':'application/json','bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
        for offset in range(0,len(data),upload['chunkBytes']):
            chunk=offset//upload['chunkBytes'];expect(system.request(3-index,bob,'/data/account/uploads/'+upload['id']+'/chunks/'+str(chunk),{'data':base64.b64encode(data[offset:offset+upload['chunkBytes']]).decode()},method='PUT'),200,'Archive upload chunk')
        return upload
    upload=post(1,bob,'/data/account/uploads',{'fileName':'archive.json','mediaType':'application/json','bytes':len(binary),'sha256':hashlib.sha256(binary).hexdigest()})
    path='/data/account/uploads/'+upload['id'];first={'data':base64.b64encode(binary[:upload['chunkBytes']]).decode()}
    first_reply=system.request(1,bob,path+'/chunks/0',first,method='PUT');expect(first_reply,200,'First upload chunk')
    system.restart(1)
    progress=expect(system.request(2,bob,path),200,'Upload progress after process restart')
    if progress['nextChunk']!=1:raise AssertionError('Restart lost committed upload progress')
    if expect(system.request(2,bob,path+'/chunks/0',first,method='PUT'),200,'Exact first-chunk replay')!=progress:raise AssertionError('Chunk replay changed progress')
    for offset in range(upload['chunkBytes'],len(binary),upload['chunkBytes']):
        expect(system.request(2,bob,path+'/chunks/'+str(offset//upload['chunkBytes']),{'data':base64.b64encode(binary[offset:offset+upload['chunkBytes']]).decode()},method='PUT'),200,'Resumed chunk on other JVM')
    expect(system.request(1,alice,path),404,'Administrator cannot read another owner upload')
    passed('private immutable upload chunks resume across actual restart and the other JVM without granting administrator access')

    before=system.private_data(bob);identity=expect(system.request(2,bob,'/auth/account'),200,'Target identity before restore')
    address=expect(system.request(1,bob,'/auth/email/addresses/PRIMARY'),200,'Target recovery address before restore')
    reviewed=system.request(1,bob,path+'/preview',{'restoreTimeZone':True});review=expect(reviewed,200,'Complete archive review')
    if not review['canImport'] or system.private_data(bob)!=before:raise AssertionError('Preview changed work or refused the valid graph')
    headers={'If-Match':reviewed[2]['ETag']}
    outcomes=system.race([lambda index=index:system.request(index,bob.clone(),path+'/import',{'restoreTimeZone':True},headers=headers) for index in (1,2)])
    for result in outcomes:expect(result,201,'Concurrent reviewed restore')
    if outcomes[0][1]!=outcomes[1][1]:raise AssertionError('Concurrent restore did not return one durable result')
    outcome=outcomes[0][1];after=system.private_data(bob)
    for name in ('clients','projects','tasks','labels','activities','workSessions'):
        old={value['id'] for value in before[name]};source_ids={value['id'] for value in source['work'][name]};actual={value['id'] for value in after[name]}
        if not old.issubset(actual) or len(actual)!=len(old)+len(source_ids) or (actual-old)&source_ids:raise AssertionError('Restore lost, duplicated or failed to remap owned work')
    copied=next(value for value in after['workSessions'] if value['summary']=='alice work\n\nalice notes');project=next(value for value in after['projects'] if value['id']==copied['projectId'])
    if copied['clientId']!=project['clientId'] or copied['start']!=source['work']['workSessions'][0]['start'] or copied['end']!=source['work']['workSessions'][0]['end']:raise AssertionError('Restore changed project/client links or exact timestamps')
    if expect(system.request(1,bob,'/auth/account'),200,'Preserved identity')!=identity or expect(system.request(2,bob,'/auth/email/addresses/PRIMARY'),200,'Preserved recovery address')!=address:raise AssertionError('Archive changed account identity or recovery authority')
    if system.password_login('bob',index=1).owner!=bob.owner or system.key_login(target_key,index=2).owner!=bob.owner:raise AssertionError('Archive invalidated target sign-in credentials')
    keys=expect(system.request(1,bob,'/auth/passkeys'),200,'Preserved target passkey')['items']
    if len(keys)!=1 or keys[0]['id']!=target_record['id']:raise AssertionError('Source credentials were installed in the target')
    if expect(system.request(2,Browser(),'/me',headers={'Authorization':'Bearer '+target_token}),200,'Preserved Eclipse token')['ownerId']!=bob.owner:raise AssertionError('Archive changed token ownership')
    expect(system.request(1,alice,'/work-sessions/'+copied['id']),404,'Source administrator cannot read restored target work')
    if expect(system.request(2,bob,'/imports'),200,'Imported audit is not live import authority')['items']:raise AssertionError('Audit restored an executable import job')
    expect(system.request(1,bob,'/imports/jobs/'+forged_job+'/undo',method='POST',headers={'Idempotency-Key':str(uuid.uuid4()),'If-Match':'"0"'}),404,'Forged source Undo authority')
    passed('concurrent cross-JVM restores have one remapped graph and preserve target passwords, passkeys, tokens and independent import authority')

    backup=system.backup();post(1,bob,'/projects',{'name':'Must disappear after protected restore'})
    system.restore(backup)
    if system.private_data(bob)!=after:raise AssertionError('Protected restore changed the completed archive result')
    replay=expect(system.request(2,bob,path+'/import',{'restoreTimeZone':False},headers=headers),201,'Archive replay after protected restore')
    if replay!=outcome or system.private_data(bob)!=after:raise AssertionError('Restored receipt duplicated work or preferences')
    if system.key_login(target_key,index=1).owner!=bob.owner:raise AssertionError('Protected restore lost the target key')
    passed('protected PostgreSQL backup restores work, staging outcome and receipts together, retaining exact retry behavior')

    duplicate=stage(source,2);duplicate_review=expect(system.request(1,bob,'/data/account/uploads/'+duplicate['id']+'/preview',{'restoreTimeZone':False}),200,'Reselected archive review')
    if not duplicate_review['alreadyImported'] or duplicate_review['canImport'] or system.private_data(bob)!=after:raise AssertionError('Reselecting an archive duplicated work')
    invalid=json.loads(json.dumps(source));invalid['archiveId']=str(uuid.uuid4());invalid['work']['workSessions'][0]['projectId']=str(uuid.uuid4());bad=stage(invalid)
    expect(system.request(2,bob,'/data/account/uploads/'+bad['id']+'/preview',{'restoreTimeZone':False}),422,'Dangling project is rejected atomically')
    if system.private_data(bob)!=after:raise AssertionError('Rejected archive partly changed target work')
    read_token=expect(system.request(1,bob,'/auth/tokens',{'name':'Read-only archive','permissions':['read'],'expiresAt':None}),201,'Read-only personal token')['token']
    expect(system.request(2,Browser(),'/data/account/uploads',{'fileName':'archive.json','mediaType':'application/json','bytes':len(binary),'sha256':hashlib.sha256(binary).hexdigest()},headers={'Authorization':'Bearer '+read_token,'Idempotency-Key':str(uuid.uuid4())}),403,'Read-only staging rejected')
    downloaded,_=download(system,2,Browser(),token=read_token,owner=bob.owner)
    if downloaded['account']['ownerId']!=bob.owner:raise AssertionError('Read-only download changed owner')
    passed('reselection, malformed graphs and read-only access preserve one private, atomic restore outcome')

    day='2026-10-27';week='2026-W44';day_body='# Private replica day\r\n[Report](assets/replica.bin)\r\n';week_body='# Private replica week\r\n'
    attachment=b'private replica attachment\x00bytes'
    independent_labels={day:'Daily label','2026-10-28':'Label without note','2026-10-29':'Keep after note deletion','2026-10-30':''}
    for date,label in independent_labels.items():
        route='/day-labels/'+date;current=system.request(1,alice,route);expect(current,200,'Read independent label')
        expect(system.request(2,alice,route,{'text':label},method='PUT',headers={'If-Match':current[2]['ETag']}),200,'Save independent label on other JVM')
    deleted_route='/day-notes/2026-10-29/content';current=system.request(1,alice,deleted_route)
    saved=system.request(2,alice,deleted_route,{'body':'Remove this note only'},method='PUT',headers={'If-Match':current[2]['ETag']});expect(saved,200,'Create removable note')
    expect(system.request(1,alice,deleted_route,method='DELETE',headers={'If-Match':saved[2]['ETag']}),204,'Delete note while retaining label')
    for route,body in (('/day-notes/'+day,day_body),('/week-notes/'+week,week_body)):
        current=system.request(1,alice,route);expect(current,200,'Read empty note before ZIP fixture')
        expect(system.request(2,alice,route,{'body':body},method='PUT',headers={'If-Match':current[2]['ETag']}),200,'Save exact note for ZIP fixture')
    expect(system.request(1,alice,'/journal-assets/replica.bin',{'data':base64.b64encode(attachment).decode()},method='PUT',headers={'If-None-Match':'*'}),201,'Save binary journal ZIP fixture')
    task=post(2,alice,'/tasks',{'projectId':None,'parentTaskId':None,'summary':'Separate binary task attachment','url':None})
    post(1,alice,'/tasks/'+task['id']+'/attachments',{'fileName':'original.bin','contentType':'application/octet-stream','bytes':len(attachment),'sha256':hashlib.sha256(attachment).hexdigest(),'data':base64.b64encode(attachment).decode()})
    snapshot,metadata,packed=download(system,2,alice,with_zip=True)
    if snapshot['format']!='timekeeper-account-v3' or day_body.encode() in metadata or base64.b64encode(attachment) in metadata:raise AssertionError('New archive still embeds file bodies in JSON')
    expect(system.request(1,alice,'/admin/users',{'username':'carol','password':PASSWORD,'displayName':'Carol','administrator':False,'email':'carol@example.invalid'}),201,'Fresh target for complete ZIP restore')
    carol=system.password_login('carol',index=2)
    upload=post(1,carol,'/data/account/uploads',{'fileName':'account.zip','mediaType':'application/zip','bytes':len(packed),'sha256':hashlib.sha256(packed).hexdigest()})
    route='/data/account/uploads/'+upload['id']
    for offset in range(0,len(packed),upload['chunkBytes']):
        expect(system.request(2,carol,route+'/chunks/'+str(offset//upload['chunkBytes']),{'data':base64.b64encode(packed[offset:offset+upload['chunkBytes']]).decode()},method='PUT'),200,'Upload separate-file ZIP on other JVM')
    review=system.request(1,carol,route+'/preview',{'restoreTimeZone':False})
    if not expect(review,200,'Review separate-file ZIP')['canImport']:raise AssertionError('Separate files failed valid ZIP review')
    expect(system.request(2,carol,route+'/import',{'restoreTimeZone':False},headers={'If-Match':review[2]['ETag']}),201,'Restore separate-file ZIP on other JVM')
    if expect(system.request(1,carol,'/day-notes/'+day),200,'Restored daily note')['body']!=day_body or expect(system.request(2,carol,'/week-notes/'+week),200,'Restored weekly note')['body']!=week_body:raise AssertionError('ZIP restore changed exact note bodies')
    for date,label in independent_labels.items():
        value=expect(system.request(2,carol,'/day-labels/'+date),200,'Restored independent day label')
        if value['text']!=label or value['version']<1:raise AssertionError('ZIP restore changed independent label or lost explicit clear')
    if any(value['path']=='2026-10-29.md' for value in snapshot['journalFiles']):raise AssertionError('Deleted note was recreated by its surviving label')
    restored,_,restored_zip=download(system,1,carol,with_zip=True)
    task_file=next(value['archiveFile'] for value in restored['work']['taskAttachments'] if value['fileName']=='original.bin')
    with zipfile.ZipFile(io.BytesIO(restored_zip)) as archive:
        if archive.read(day+'.md')!=day_body.encode() or archive.read(week+'.md')!=week_body.encode() or archive.read('assets/replica.bin')!=attachment or archive.read(task_file)!=attachment:raise AssertionError('Cross-JVM ZIP restore changed separate Markdown or binary files')
    system.restart(2)
    for date,label in independent_labels.items():
        if expect(system.request(1,carol,'/day-labels/'+date),200,'Label after peer restart')['text']!=label:raise AssertionError('Independent label changed across restart')
    route='/day-notes/'+day+'/content';current=system.request(1,carol,route)
    expect(system.request(2,carol,route,{'body':''},method='PUT',headers={'If-Match':current[2]['ETag']}),200,'Clear only restored body')
    if expect(system.request(1,carol,'/day-labels/'+day),200,'Label after body clearing')['text']!=independent_labels[day]:raise AssertionError('Clearing body removed label')
    passed('independent labels and clears survive note deletion, ZIP restore, body clearing and actual JVM restart')
    passed('separate daily/weekly Markdown and binary journal/task attachments restore exactly across PostgreSQL JVMs')
    return checks


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--server-image',required=True);parser.add_argument('--postgres-image',default='postgres:18.6-bookworm')
    parser.add_argument('--expected-commit');parser.add_argument('--output',type=Path,default=ROOT/'server/target/archive-replica-acceptance.json');args=parser.parse_args();args.output.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='timekeeper-archive-replicas-') as folder:
        system=ArchiveReplicas(args,folder)
        try:
            system.initialize();checks=exercise(system);args.output.parent.mkdir(parents=True,exist_ok=True)
            image=system.command('docker','image','inspect','--format','{{.Id}}',args.server_image).stdout.strip()
            args.output.write_text(json.dumps({'serverImage':args.server_image,'imageId':image,'serverCommit':system.server_commit,'postgresImage':args.postgres_image,'twoActualJVMs':True,'trustedProxyOriginFixture':True,'restartPublishedPortChanged':system.restart_port_changed,'checks':checks,'passed':True},indent=2)+'\n')
        finally:system.close()


if __name__=='__main__':main()
