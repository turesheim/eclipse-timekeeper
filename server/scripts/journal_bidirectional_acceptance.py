"""Additional bidirectional cases run inside the real disposable SSH acceptance fixture."""
from datetime import date,timedelta
import base64
import json
import subprocess
import time
from urllib.error import URLError


def run(*,api,account_index,read_accounts,admin,relogin,password,python,tool,enroll,ssh,client_module,docker,app_name,gateway_name,client_name,background):
    folder="/client/bidirectional vault ' quoted/Daily";profile='profile-two-way.json'
    python('from pathlib import Path;Path('+repr(folder)+').mkdir(parents=True)')
    device,connection=enroll(account_index,'/client/two-way-key',profile,bidirectional=True,folder=folder)
    assert connection['format']==2 and connection['direction']=='BIDIRECTIONAL' and connection['maxExchangeFiles']==50
    tool('verify',profile)
    def write(name,content):python('from pathlib import Path;Path('+repr(folder+'/'+name)+').write_bytes('+repr(content)+')')
    def read(name):return python('from pathlib import Path;import sys;sys.stdout.buffer.write(Path('+repr(folder+'/'+name)+').read_bytes())').stdout
    def exists(name):return not python('from pathlib import Path;import sys;sys.exit(0 if Path('+repr(folder+'/'+name)+').exists() else 1)',check=False).returncode
    def put(name,body,text=None):
        _,headers=api.call('GET','/day-notes/'+name[:-3]);value={'body':body}
        if text is not None:value['text']=text
        return api.call('PUT','/day-notes/'+name[:-3],value,match=headers['ETag'])[0]
    first='2040-01-01.md';second='2040-01-02.md';third='2040-01-03.md'
    put(first,'Database-only first note','First');tool('sync',profile)
    assert b'Database-only first note' in read(first)
    original=b'\xef\xbb\xbf---\r\ntimekeeper-label: Local\r\nunknown: [keep, exact]\r\n---\r\n\r\n<details>Raw Markdown</details>  \r\n'
    write(second,original);tool('sync',profile);note,_=api.call('GET','/day-notes/'+second[:-3]);assert note['body']=='\r\n<details>Raw Markdown</details>  \r\n' and read(second)==original
    changed=original.replace(b'[keep, exact]',b'[keep, metadata-only]');version=note['version'];write(second,changed);tool('sync',profile)
    assert api.call('GET','/day-notes/'+second[:-3])[0]['version']==version and read(second)==changed
    put(second,'Calendar edit with **Markdown**  \r\n','Changed');tool('sync',profile);exported=read(second)
    assert b'metadata-only' in exported and b'Calendar edit with **Markdown**  \r\n' in exported
    print('Real bidirectional initial discovery, exact CRLF/BOM import, metadata-only acknowledgement and Calendar export: PASS',flush=True)
    # A simultaneous edit blocks just this date; an independent date still exports.
    write(first,b'Local simultaneous variant');put(first,'Calendar simultaneous variant');put(third,'Independent Calendar note')
    result=tool('sync',profile,check=False);assert result.returncode and json.loads(result.stdout)['pendingConflictExchanges']==1
    assert read(first)==b'Local simultaneous variant' and b'Independent Calendar note' in read(third)
    status,_=api.call('GET','/journal');conflict=next(item for item in status['items'] if item['filename']==first and item['state']=='CONFLICT' and item['deviceId']==device['id'])
    assert api.call('GET','/journal/items/'+conflict['id']+'/source',binary=True)[0]==b'Local simultaneous variant'
    assert b'Calendar simultaneous variant' in api.call('GET','/journal/items/'+conflict['id']+'/calendar-source',binary=True)[0]
    docker('kill','--signal','KILL',app_name);docker('start',app_name);deadline=time.monotonic()+30
    while True:
        try:api.call('GET','/journal');break
        except (URLError,ConnectionError):
            if time.monotonic()>deadline:raise RuntimeError('Bidirectional conflict restart failed')
            time.sleep(.25)
    candidate,_=api.call('GET','/journal/items/'+conflict['id'])
    api.call('POST','/journal/items/'+conflict['id']+'/resolve',{'action':'CALENDAR','currentEtag':candidate['currentEtag']},match=candidate['candidateEtag'])
    tool('sync',profile);assert b'Calendar simultaneous variant' in read(first)
    # File absence after an acknowledged base is deletion, while an empty file is present.
    python('from pathlib import Path;Path('+repr(folder+'/'+second)+').unlink()');tool('sync',profile)
    deleted,_=api.call('GET','/day-notes/'+second[:-3]);assert deleted['deleted'] and not exists(second)
    version=deleted['version'];tool('sync',profile);assert api.call('GET','/day-notes/'+second[:-3])[0]['version']==version
    write(second,b'');tool('sync',profile);note,_=api.call('GET','/day-notes/'+second[:-3]);assert not note['deleted'] and note['body']=='' and read(second)==b''
    _,headers=api.call('GET','/day-notes/'+first[:-3]);api.call('DELETE','/day-notes/'+first[:-3],match=headers['ETag'],status=204);tool('sync',profile);assert not exists(first)
    write(first,b'Recreated after acknowledged deletion');tool('sync',profile);assert api.call('GET','/day-notes/'+first[:-3])[0]['body']=='Recreated after acknowledged deletion'
    print('Real bidirectional independent conflict, forced restart, Calendar resolution, tracked deletion, empty file and recreation: PASS',flush=True)
    # Inject reply loss only after the real SSH command has completed. Both the
    # server state and all file mutations still use the packaged implementation.
    put(first,'Calendar edit before lost acknowledgement')
    program=client_module(profile)+'original=c.remote_json\ndef lost(data,identity,state,command,value=None):\n reply=original(data,identity,state,command,value)\n if command=="timekeeper-journal-ack":raise c.Failure("Simulated lost SSH acknowledgement")\n return reply\nc.remote_json=lost\ntry:c.sync_bidirectional(d,f,i,t)\nexcept c.Failure:sys.exit(73)'
    assert python(program,check=False).returncode==73
    assert json.loads(tool('recoveries',profile).stdout)['operations'];tool('sync',profile)
    assert not json.loads(tool('recoveries',profile).stdout)['operations'] and b'Calendar edit before lost acknowledgement' in read(first)
    write(first,b'External upload before lost reply')
    program=client_module(profile)+'original=c.exchange_rsync\ndef lost(data,identity,state,directory,exchange,download=False):\n original(data,identity,state,directory,exchange,download)\n if not download:raise c.Failure("Simulated lost completed-upload reply")\nc.exchange_rsync=lost\ntry:c.sync_bidirectional(d,f,i,t)\nexcept c.Failure:sys.exit(74)'
    assert python(program,check=False).returncode==74
    write(first,b'Newer editor save after interrupted upload');tool('sync',profile)
    assert read(first)==b'Newer editor save after interrupted upload' and api.call('GET','/day-notes/'+first[:-3])[0]['body']=='External upload before lost reply'
    tool('sync',profile);assert api.call('GET','/day-notes/'+first[:-3])[0]['body']=='Newer editor save after interrupted upload'
    print('Real SSH lost acknowledgement and completed-upload retry preserve durable common state and a newer editor save: PASS',flush=True)
    # The legacy upload-only key cannot invoke inventory or the sender role.
    denied=python(client_module()+'c.remote_json(d,i,t,"timekeeper-journal-inventory",{})',check=False);assert denied.returncode
    assert ssh('rsync --server --sender -r . outgoing/00000000-0000-0000-0000-000000000000/').returncode
    assert ssh('timekeeper-journal-test',profile=profile,username='journal_a').returncode
    status,_=api.call('GET','/journal');api.call('PUT','/journal/settings',{'enabled':True,'bidirectional':False,'currentPassword':password},match=status['settings']['etag'])
    assert tool('verify',profile,check=False).returncode;tool('verify')
    status,_=api.call('GET','/journal');api.call('PUT','/journal/settings',{'enabled':True,'bidirectional':True,'currentPassword':password},match=status['settings']['etag']);tool('verify',profile)
    # A fresh client must export more than the retained-source quota without backlogging it.
    names=[(date(2010,1,1)+timedelta(days=index)).isoformat()+'.md' for index in range(501)]
    for index,name in enumerate(names):
        put(name,'Database pressure '+name)
        if (index+1)%100==0:print('Prepared bidirectional pressure notes: '+str(index+1),flush=True)
    print('Exchanging the 501-note database-only folder over real SSH…',flush=True)
    result=json.loads(tool('sync',profile).stdout);assert result['exchangedNotes']==504
    assert read(names[0])==('Database pressure '+names[0]).encode() and read(names[-1])==('Database pressure '+names[-1]).encode()
    status,_=api.call('GET','/journal');assert status['health']['outgoing']==0 and status['health']['pending']==0 and status['health']['lastSyncAt']
    state=json.loads(tool('recoveries',profile).stdout);assert state['recoveries'] and not state['exchanges'] and not state['operations']
    assert tool('verify',profile).returncode==0
    print('Real bidirectional write-only/read/cross-account scope, opt-out, 501-note export pressure and durable local recovery storage: PASS',flush=True)
    api.call('POST','/journal/devices/'+device['id']+'/revoke',{'currentPassword':password})
    # Observe the actual outgoing sender and private receiving temporary file,
    # then withdraw the key/global grant while rsync is still streaming.
    for index,cause in enumerate(['rotation','revocation','bidirectional','synchronization','account']):
        # Successful fresh-password proofs count toward the production throttle.
        # Independent fixture accounts exercise each lifecycle without weakening
        # that limit or sleeping through its five-minute window.
        account_index,owner_id,api=read_accounts[index];user='journal_'+chr(ord('a')+account_index);uid=20001+account_index
        folder='/client/outgoing-case-'+str(index)+'/Daily';profile='profile-outgoing-case-'+str(index)+'.json'
        python('from pathlib import Path;Path('+repr(folder)+').mkdir(parents=True)')
        device,connection=enroll(account_index,'/client/outgoing-case-key-'+str(index),profile,bidirectional=True,folder=folder)
        name='2100-01-0'+str(index+1)+'.md';put(name,'Private outgoing acceptance note\n'+'z'*180000)
        program=client_module(profile)+'import tempfile,uuid,json;exchange=str(uuid.uuid4());\nwith c.Replica(t,f,d["deviceId"]) as replica:\n with tempfile.TemporaryDirectory(dir=t) as temporary:\n  snapshot=pathlib.Path(temporary);c.atomic(snapshot/".timekeeper-batch.json",json.dumps({"format":2,"clientStateId":replica.client_id,"exchangeId":exchange,"files":[],"absent":[{"name":'+repr(name)+',"baseToken":""}],"errors":[]}).encode());c.exchange_rsync(d,i,t,snapshot,exchange)\nprint(exchange)'
        exchange=python(program).stdout.decode().strip();destination='/client/private-slow-download-'+str(index)
        python('from pathlib import Path;p=Path('+repr(destination)+');p.mkdir(mode=0o700)')
        program=client_module(profile)+'r=subprocess.run(["rsync","-r","--perms","--chmod=Du=rwx,Dgo=,Fu=rw,Fgo=","--bwlimit=8","--rsh",shlex.join(c.ssh_arguments(d,i,t)),d["username"]+"@"+d["hostname"]+":outgoing/"+'+repr(exchange)+'+"/",'+repr(destination+'/')+']);sys.exit(r.returncode)'
        process=subprocess.Popen(['docker','exec','--user','30000:30000',client_name,'python3','-c',program],stdout=subprocess.PIPE,stderr=subprocess.PIPE);background.append(process);deadline=time.monotonic()+20
        while True:
            observed=json.loads(python('from pathlib import Path;import json;p=Path('+repr(destination)+');print(json.dumps([[q.name,q.stat().st_mode&511] for q in p.iterdir()]))').stdout)
            rows=json.loads(docker('exec',gateway_name,'python3','-c',"from pathlib import Path;import json;p=Path("+repr('/var/lib/timekeeper-journal/spool/'+user)+");print(json.dumps([[str(q.relative_to(p)),q.stat().st_mode&511,q.stat().st_uid,q.is_dir()] for q in p.rglob('*') if not q.is_symlink()]))").stdout)
            assert all(mode==(0o700 if directory else 0o600) and owner==uid for path,mode,owner,directory in rows),'Unsafe outgoing-stage permissions'
            assert all(mode==0o600 for path,mode in observed),'Unsafe private local download permissions'
            if any(name in path for path,mode in observed) and process.poll() is None:break
            if time.monotonic()>deadline or process.poll() is not None:raise RuntimeError('Real outgoing stream did not reach its private local receiver')
            time.sleep(.2)
        previous_device=device;previous_profile=profile
        if cause=='rotation':device,connection=enroll(account_index,'/client/outgoing-rotated-key','profile-outgoing-rotated.json',rotate=device['id'],bidirectional=True,folder=folder);profile='profile-outgoing-rotated.json'
        elif cause=='revocation':api.call('POST','/journal/devices/'+device['id']+'/revoke',{'currentPassword':password})
        elif cause=='account':
            owner_key=base64.urlsafe_b64encode(owner_id.encode()).decode().rstrip('=');record,record_headers=admin.call('GET','/admin/users/'+owner_key);record=record['user'];user_body={key:record[key] for key in ['username','displayName','administrator']};user_body['enabled']=False
            _,disabled_headers=admin.call('PUT','/admin/users/'+owner_key,user_body,match=record_headers['ETag'])
        else:
            status,_=api.call('GET','/journal');api.call('PUT','/journal/settings',{'enabled':cause!='synchronization','bidirectional':cause!='bidirectional','currentPassword':password},match=status['settings']['etag'])
        process.communicate(timeout=15);assert process.returncode,'Withdrawn outgoing stream was acknowledged';assert not exists(name)
        if cause=='revocation':device,connection=enroll(account_index,'/client/outgoing-new-key','profile-outgoing-new.json',bidirectional=True,folder=folder);profile='profile-outgoing-new.json'
        if cause in ['bidirectional','synchronization']:
            status,_=api.call('GET','/journal');api.call('PUT','/journal/settings',{'enabled':True,'bidirectional':True,'currentPassword':password},match=status['settings']['etag'])
        if cause=='account':
            user_body['enabled']=True;admin.call('PUT','/admin/users/'+owner_key,user_body,match=disabled_headers['ETag']);api=relogin(account_index)
        if cause in ['rotation','revocation']:assert tool('verify',previous_profile,check=False).returncode
        tool('verify',profile)
        status,_=api.call('GET','/journal');offer=next(item for item in status['items'] if item['filename']==name and item['deviceId']==previous_device['id'] and item.get('canDiscard'))
        api.call('POST','/journal/items/'+offer['id']+'/discard',match=offer['candidateEtag'])
        print('Real in-flight outgoing '+cause+' withdrawal and private permissions: PASS',flush=True)
        api.call('POST','/journal/devices/'+device['id']+'/revoke',{'currentPassword':password})
