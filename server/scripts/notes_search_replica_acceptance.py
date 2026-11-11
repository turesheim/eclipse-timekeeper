#!/usr/bin/env python3
"""Notes search across independent JVMs, private persistent caches and PostgreSQL.

All accounts, credentials, containers, volumes and database mutations are synthetic.
HTTP models the trusted proxy; real HTTPS/browser behavior is checked separately.
"""
import argparse
import json
from pathlib import Path
import re
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,urlopen

from passkey_replica_acceptance import Replicas,Browser,expect,encoded,PASSWORD,VPN,ROOT


class SearchReplicas(Replicas):
    def __init__(self,args,folder):
        super().__init__(args,folder)
        self.network='timekeeper-notes-search-'+self.suffix;self.database=self.network+'-db';self.volumes=set()
    def command(self,*arguments,**options):
        if arguments[:2]==('docker','run') and arguments[-1]==self.args.server_image and '--env-file' in arguments:
            name=arguments[arguments.index('--name')+1];match=re.search(r'-api-([12])-\d+$',name)
            if not match:raise RuntimeError('Unknown disposable search replica')
            volume=self.network+'-index-'+match[1];self.volumes.add(volume)
            environment=Path(arguments[arguments.index('--env-file')+1])
            with environment.open('a') as output:output.write('TIMEKEEPER_NOTES_SEARCH_CACHE_DIRECTORY=/instance/notes-search\n')
            arguments=(*arguments[:-1],'--mount','type=volume,source='+volume+',target=/instance',arguments[-1])
        return super().command(*arguments,**options)
    def close(self):
        super().close()
        for volume in self.volumes:self.command('docker','volume','rm',volume,check=False)
    def raw(self,index,browser,path,data=None,*,method='GET',headers=None):
        selected={'Origin':self.origins[index],**(headers or {})}
        if browser.cookies:selected['Cookie']='; '.join(key+'='+value for key,value in browser.cookies.items())
        if browser.csrf:selected['X-CSRF-Token']=browser.csrf
        if browser.owner:selected['X-Timekeeper-Owner']=encoded(browser.owner.encode())
        if data is not None:selected['Content-Type']='application/octet-stream'
        request=Request(self.bases[index]+'/api/v1'+path,data=data,headers=selected,method=method)
        try:reply=urlopen(request,timeout=30)
        except HTTPError as failure:reply=failure
        with reply:
            body=reply.read();return reply.status,json.loads(body) if body and reply.headers.get_content_type() in {'application/json','application/problem+json'} else body,reply.headers
    def sql(self,sql):return self.command('docker','exec','-i',self.database,'psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-U','timekeeper','-d','timekeeper',input=sql).stdout.strip()


def exercise(system):
    checks=[]
    def passed(name):checks.append(name);print('PASS: '+name,flush=True)
    alice=system.browser();expect(system.request(1,alice,'/auth/setup',{'code':system.code,'username':'alice','password':PASSWORD,'displayName':'Alice','email':'alice@example.invalid'}),200,'Administrator setup')
    versions=[expect(system.request(index,alice,'/server'),200,'Search replica identity') for index in (1,2)];system.server_commit=versions[0]['commit']
    if any(value['dirty'] or value['commit']!=system.server_commit for value in versions):raise AssertionError('Search replicas have inconsistent source identity')
    if system.args.expected_commit and system.server_commit!=system.args.expected_commit:raise AssertionError('Search image source does not match')
    expect(system.request(2,alice,'/admin/users',{'username':'bob','password':PASSWORD,'displayName':'Bob','email':'bob@example.invalid','administrator':False}),201,'Independent account')
    bob=system.password_login('bob',index=2)
    for browser in (alice,bob):expect(system.request(1,browser,'/notes'),200,'Initialize private Notes');expect(system.request(1,browser,'/notes/folders',{'path':'Corpus'}),201,'Private folder')
    def put(index,browser,path,body):return expect(system.raw(index,browser,'/notes/files?'+urlencode({'path':path}),body.encode(),method='PUT',headers={'If-None-Match':'*'}),201,'Create canonical note')
    def page(index,browser,query,*,cursor=None,limit=37):
        parameters={'q':query,'limit':limit}
        if cursor is not None:parameters['cursor']=cursor
        return system.request(index,browser,'/notes/search?'+urlencode(parameters))
    def ready(index,browser,query,limit=37,*,cursor=None):
        deadline=time.monotonic()+90
        while True:
            reply=page(index,browser,query,limit=limit,cursor=cursor)
            if reply[0]==200:
                if reply[2].get('Cache-Control')!='private, no-store':raise AssertionError('Search response is cacheable')
                return reply[1]
            expect(reply,503,'Explicit indexing response')
            if time.monotonic()>deadline:raise AssertionError('Replica did not catch up to committed Notes')
            time.sleep(.1)
    entries=[]
    for index in range(215):
        body=('# Replicaneedle priority' if index==214 else '# Record '+str(index))+'\n\nreplicaneedle Café blåbær service.cluster.local.\n\ncommitted replicas preserve canonical bytes.\n'
        entries.append(put(1,alice,'Corpus/Record-'+str(index).zfill(3)+'.md',body))
        if index==4:ready(2,alice,'replicaneedle')  # Different physical indexing history.
    foreign=put(2,bob,'Corpus/Foreign.md','replicaneedle foreignsecret')
    first=ready(1,alice,'replicaneedle');assert first['items'][0]['entry']['id']==entries[-1]['id'];seen=set();result=first;replica=1
    while True:
        for hit in result['items']:
            identifier=hit['entry']['id'];assert identifier not in seen and identifier!=foreign['id'];seen.add(identifier)
            excerpt=hit['excerpt'];assert len(excerpt['text'].encode('utf-16-le'))//2<=320
            assert all(0<=span['start']<span['end']<=len(excerpt['text'].encode('utf-16-le'))//2 for span in excerpt['highlights'])
        if 'nextCursor' not in result:break
        replica=3-replica;result=ready(replica,alice,'replicaneedle',cursor=result['nextCursor'])
    assert seen=={entry['id'] for entry in entries};assert len(ready(2,bob,'replicaneedle')['items'])==1
    expect(page(2,bob,'replicaneedle',cursor=first['nextCursor']),412,'Foreign cursor');expect(system.request(2,bob,'/notes/entries/'+entries[0]['id']),404,'Foreign metadata')
    for query in ['Cafe\u0301','BLÅBÆR','service.cluster.local','"committed replicas"']:assert ready(2,alice,query)['items']
    passed('ranked body/Unicode/phrase results page across independent caches without duplicates or account leakage')
    before=system.raw(1,alice,'/notes/entries/'+entries[0]['id']+'/content');expect(before,200,'Canonical source before indexing');assert before[2]['ETag']==entries[0]['etag']
    changed=expect(system.raw(2,alice,'/notes/entries/'+entries[0]['id']+'/content',b'cachenewword',method='PUT',headers={'If-Match':entries[0]['etag']}),200,'Committed edit')
    for replica in (1,2):assert ready(replica,alice,'cachenewword')['items'][0]['entry']['id']==changed['id'];expect(page(replica,alice,'replicaneedle',cursor=first['nextCursor']),412,'Changed snapshot')
    folder=expect(system.request(1,alice,'/notes/resolve?path=Corpus'),200,'Current folder validator')
    expect(system.request(1,alice,'/notes/entries/'+folder['id']+'/path',{'path':'Moved'},method='PUT',headers={'If-Match':folder['entryEtag']}),200,'Folder subtree move')
    for replica in (1,2):assert all(hit['entry']['path'].startswith('Moved/') for hit in ready(replica,alice,'replicaneedle')['items'])
    current=expect(system.request(2,alice,'/notes/entries/'+entries[1]['id']),200,'Current delete validator');expect(system.request(2,alice,'/notes/entries/'+current['id'],method='DELETE',headers={'If-Match':current['entryEtag']}),204,'Delete indexed note')
    for replica in (1,2):assert not ready(replica,alice,'"Record 1"',limit=200)['items']
    tombstone=expect(system.request(1,alice,'/notes/entries/'+current['id']),200,'Private tombstone');expect(system.request(1,alice,'/notes/entries/'+current['id']+'/restore',{},headers={'If-Match':tombstone['entryEtag']}),200,'Recover indexed note')
    for replica in (1,2):assert ready(replica,alice,'"Record 1"',limit=200)['items']
    passed('committed edits, subtree moves and delete/recovery invalidate stale pages on both replicas')
    maximum='# Maximum note\n\n~~~text\n'+('bounded code content\n'*500_000)
    maximum=maximum.encode()[:9_999_900]+b'\nfarendpipelineword \xf0\x9f\x98\x80\n~~~\n'
    largest=expect(system.raw(1,alice,'/notes/files?path=Maximum.md',maximum,method='PUT',headers={'If-None-Match':'*'}),201,'Maximum supported Markdown fixture')
    for replica in (1,2):
        hit=ready(replica,alice,'farendpipelineword')['items'][0];assert hit['entry']['id']==largest['id'];assert 'farendpipelineword' in hit['excerpt']['text']
    passed('a body match near the end of a nearly 10 MB supported note is indexed and highlighted on both replicas')

    # Stop one real JVM, expire its retained replay interval, then reuse its on-disk cache.
    ready(2,alice,'cachenewword');system.command('docker','stop',system.instances[2])
    current=expect(system.request(1,alice,'/notes/entries/'+changed['id']),200,'Offline replica source validator');expect(system.raw(1,alice,'/notes/entries/'+current['id']+'/content',b'historysurvivor',method='PUT',headers={'If-Match':current['etag']}),200,'Save with replica offline')
    owner=alice.owner.replace("'","''")
    system.sql("DELETE FROM tk_resource WHERE owner_id='"+owner+"' AND entity_type='NOTES_SEARCH_LOG'; UPDATE tk_resource SET payload=jsonb_set(payload::jsonb,'{historyFloor}',to_jsonb((payload::jsonb->>'generation')::bigint))::text WHERE owner_id='"+owner+"' AND entity_type='NOTES_SEARCH';")
    system.start(2,VPN,1);assert ready(2,alice,'historysurvivor')['items'][0]['entry']['id']==changed['id'];assert not ready(2,alice,'cachenewword')['items']
    token=expect(system.request(1,alice,'/auth/tokens',{'name':'Read-only search fixture','permissions':['read'],'expiresAt':None}),201,'Read token')['token']
    expect(system.raw(2,Browser(),'/notes/search/rebuild',b'',method='POST',headers={'Authorization':'Bearer '+token}),403,'Read-only rebuild boundary')
    passed('a restarted JVM rebuilds an expired persistent cache; read-only credentials cannot request rebuilds')
    return checks


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--server-image',required=True);parser.add_argument('--postgres-image',default='postgres:18.6-bookworm');parser.add_argument('--expected-commit');parser.add_argument('--output',type=Path,default=ROOT/'server/target/notes-search-replicas.json');args=parser.parse_args();args.output.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='dagsverk-notes-search-') as folder:
        system=SearchReplicas(args,folder)
        try:
            system.initialize();checks=exercise(system);args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps({'serverCommit':system.server_commit,'postgresImage':args.postgres_image,'twoActualJVMs':True,'privatePersistentCaches':True,'trustedProxyOriginFixture':True,'checks':checks,'passed':True},indent=2)+'\n')
        finally:system.close()


if __name__=='__main__':main()
