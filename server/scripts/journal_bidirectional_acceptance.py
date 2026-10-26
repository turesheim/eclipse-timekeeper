"""Additional bidirectional cases run inside the real disposable SSH acceptance fixture."""
from datetime import date,timedelta
import json
import time
from urllib.error import URLError


def run(*,api,password,python,tool,enroll,ssh,client_module,docker,app_name):
    folder="/client/bidirectional vault ' quoted/Daily";profile='profile-two-way.json'
    python('from pathlib import Path;Path('+repr(folder)+').mkdir(parents=True)')
    device,connection=enroll(1,'/client/two-way-key',profile,bidirectional=True,folder=folder)
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
    # The legacy upload-only key cannot invoke inventory or the sender role.
    denied=python(client_module()+'c.remote_json(d,i,t,"timekeeper-journal-inventory",{})',check=False);assert denied.returncode
    assert ssh('rsync --server --sender -r . outgoing/00000000-0000-0000-0000-000000000000/').returncode
    assert ssh('timekeeper-journal-test',profile=profile,username='journal_a').returncode
    status,_=api.call('GET','/journal');api.call('PUT','/journal/settings',{'enabled':True,'bidirectional':False,'currentPassword':password},match=status['settings']['etag'])
    assert tool('verify',profile,check=False).returncode;tool('verify')
    status,_=api.call('GET','/journal');api.call('PUT','/journal/settings',{'enabled':True,'bidirectional':True,'currentPassword':password},match=status['settings']['etag']);tool('verify',profile)
    # A fresh client must export more than the retained-source quota without backlogging it.
    names=[(date(2010,1,1)+timedelta(days=index)).isoformat()+'.md' for index in range(501)]
    for name in names:put(name,'Database pressure '+name)
    result=json.loads(tool('sync',profile).stdout);assert result['exchangedNotes']==504
    assert read(names[0])==('Database pressure '+names[0]).encode() and read(names[-1])==('Database pressure '+names[-1]).encode()
    status,_=api.call('GET','/journal');assert status['health']['outgoing']==0 and status['health']['pending']==0 and status['health']['lastSyncAt']
    state=json.loads(tool('recoveries',profile).stdout);assert state['recoveries'] and not state['exchanges'] and not state['operations']
    assert tool('verify',profile).returncode==0
    # Retire only this fixture key so later legacy scenarios remain independent.
    api.call('POST','/journal/devices/'+device['id']+'/revoke',{'currentPassword':password})
    print('Real bidirectional write-only/read/cross-account scope, opt-out, 501-note export pressure and durable local recovery storage: PASS',flush=True)
