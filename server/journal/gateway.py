#!/usr/bin/python3 -I
"""Restricted rsync gateway. Installed root-owned; the web application never runs it."""
import argparse
import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import resource
import shlex
import shutil
import signal
import ssl
import stat
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError,URLError
from urllib.parse import urlsplit
from urllib.request import Request,build_opener,HTTPSHandler,HTTPRedirectHandler
import uuid

CONFIG=Path('/etc/timekeeper-journal/gateway.json')
ACCOUNT_ROOT=Path('/etc/timekeeper-journal/accounts')
PROGRAM='/usr/local/libexec/timekeeper-journal-gateway'
RR='/usr/local/libexec/rrsync'
RR_READ='/usr/local/libexec/rrsync-read'
NAME=re.compile(r'[0-9]{4}-[0-9]{2}-[0-9]{2}\.md\Z')
FILE_BYTES=220000


class Failure(Exception):pass


def private_json(path,central=False):
    value=path.lstat()
    if not stat.S_ISREG(value.st_mode) or value.st_uid!=0 or value.st_mode&0o027:raise Failure('JOURNAL_GATEWAY_CONFIGURATION_INVALID')
    with path.open('rb') as source:data=source.read(65537)
    if len(data)>65536:raise Failure('JOURNAL_GATEWAY_CONFIGURATION_INVALID')
    return json.loads(data)


def username(value):
    if not re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}',value):raise Failure('JOURNAL_GATEWAY_CONFIGURATION_INVALID')
    return value


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None


def request(config,path,data,method='POST',binary=False):
    url=config['brokerUrl'];parsed=urlsplit(url)
    if parsed.scheme!='https' and not(parsed.scheme=='http' and parsed.hostname in ['127.0.0.1','::1','localhost']):raise Failure('JOURNAL_ENCRYPTED_BROKER_REQUIRED')
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path!='/internal/journal':raise Failure('JOURNAL_GATEWAY_CONFIGURATION_INVALID')
    if not re.fullmatch(r'[A-Za-z0-9_-]{43,128}',config['token']):raise Failure('JOURNAL_GATEWAY_CONFIGURATION_INVALID')
    context=ssl.create_default_context(cafile=config.get('caFile') or None)
    opener=build_opener(NoRedirect(),HTTPSHandler(context=context))
    call=Request(url+'/'+username(config['username'])+path,json.dumps(data,separators=(',',':')).encode(),
                 {'Authorization':'JournalTransport '+config['token'],'Content-Type':'application/json','Accept':'application/octet-stream' if binary else 'application/json'},method=method)
    try:
        with opener.open(call,timeout=30) as response:
            limit=FILE_BYTES if binary else 65536
            raw=response.read(limit+1)
            if len(raw)>limit or response.headers.get_content_type()!=('application/octet-stream' if binary else 'application/json'):raise Failure('JOURNAL_BROKER_UNAVAILABLE')
            return raw if binary else json.loads(raw)
    except HTTPError as error:
        try:code=json.loads(error.read(4096)).get('code','')
        except (ValueError,UnicodeError):code=''
        raise Failure(code if re.fullmatch(r'JOURNAL_[A-Z_]{1,80}',code) else 'JOURNAL_BROKER_HTTP_'+str(error.code)) from None
    except (URLError,TimeoutError,ValueError):raise Failure('JOURNAL_BROKER_UNAVAILABLE') from None


def authorize(user,kind,encoded):
    user=username(user)
    if kind!='ssh-ed25519' or not re.fullmatch(r'[A-Za-z0-9+/]{68}',encoded):return
    config=private_json(CONFIG)
    account=next((item for item in config['accounts'] if item['username']==user),None)
    if not account:return
    record={**account,'brokerUrl':config['brokerUrl'],'caFile':config.get('caFile')}
    found=request(record,'/keys',{'publicKey':kind+' '+encoded});device=str(uuid.UUID(found['deviceId']))
    if found['publicKey']!=kind+' '+encoded:raise Failure('JOURNAL_KEY_NOT_AUTHORIZED')
    # Every key carries its own immutable device authority; client arguments cannot select it.
    print('restrict,command="'+PROGRAM+' receive '+user+' '+device+'" '+kind+' '+encoded)


def read_regular(path,limit):
    before=path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_nlink!=1 or before.st_mode&0o077 or before.st_size>limit:raise Failure('JOURNAL_UNSAFE_TRANSFER_FILE')
    descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    with os.fdopen(descriptor,'rb') as source:
        opened=os.fstat(source.fileno())
        if (before.st_dev,before.st_ino)!=(opened.st_dev,opened.st_ino):raise Failure('JOURNAL_UNSAFE_TRANSFER_FILE')
        content=source.read(limit+1);after=os.fstat(source.fileno())
    if len(content)>limit or (opened.st_size,opened.st_mtime_ns,opened.st_ctime_ns)!=(after.st_size,after.st_mtime_ns,after.st_ctime_ns):raise Failure('JOURNAL_UNSAFE_TRANSFER_FILE')
    return content


def identifier(value):
    if not isinstance(value,str) or str(uuid.UUID(value))!=value:raise Failure('JOURNAL_INVALID_MANIFEST')
    return value


def note_name(value):
    if not isinstance(value,str) or not NAME.fullmatch(value):raise Failure('JOURNAL_INVALID_MANIFEST')
    # The importer reports an impossible date per file. Do not reject its
    # independent valid neighbours before the completed-batch handoff.
    return value


def base_token(value):
    if value!='':identifier(value)


def completed_files(stage,exchange=None):
    manifest=json.loads(read_regular(stage/'.timekeeper-batch.json',65536))
    fields={'format','files','errors'};limit=100
    if exchange is not None:fields|={'clientStateId','exchangeId','absent'};limit=50
    if not isinstance(manifest,dict) or set(manifest)!=fields or type(manifest['format']) is not int or manifest['format']!=(2 if exchange else 1) or not isinstance(manifest['files'],list) or not isinstance(manifest['errors'],list):raise Failure('JOURNAL_INVALID_MANIFEST')
    absent=manifest.get('absent',[])
    if not isinstance(absent,list) or len(manifest['files'])+len(manifest['errors'])+len(absent)>limit:raise Failure('JOURNAL_INVALID_MANIFEST')
    if exchange:
        identifier(manifest['clientStateId'])
        if manifest['exchangeId']!=exchange:raise Failure('JOURNAL_INVALID_MANIFEST')
    files={}
    for entry in manifest['files']:
        if not isinstance(entry,dict) or set(entry)!=({'name','bytes','sha256','baseToken'} if exchange else {'name','bytes','sha256'}):raise Failure('JOURNAL_INVALID_MANIFEST')
        name=note_name(entry['name'])
        if name in files or type(entry['bytes']) is not int or not 0<=entry['bytes']<=FILE_BYTES or not isinstance(entry['sha256'],str) or not re.fullmatch(r'[a-f0-9]{64}',entry['sha256']):raise Failure('JOURNAL_INVALID_MANIFEST')
        if exchange:base_token(entry['baseToken'])
        content=read_regular(stage/name,FILE_BYTES)
        if len(content)!=entry['bytes'] or hashlib.sha256(content).hexdigest()!=entry['sha256']:raise Failure('JOURNAL_INCOMPLETE_TRANSFER')
        files[name]=content
    names=set(files)
    for entry in absent:
        if not isinstance(entry,dict) or set(entry)!={'name','baseToken'}:raise Failure('JOURNAL_INVALID_MANIFEST')
        name=note_name(entry['name']);base_token(entry['baseToken'])
        if name in names:raise Failure('JOURNAL_INVALID_MANIFEST')
        names.add(name)
    for entry in manifest['errors']:
        if not isinstance(entry,dict) or set(entry)!={'name','code'}:raise Failure('JOURNAL_INVALID_MANIFEST')
        name=note_name(entry['name'])
        if name in names or entry['code'] not in ['FILE_TOO_LARGE','UNSAFE_LOCAL_FILE','FILE_CHANGED','LOCAL_FILE_UNREADABLE']:raise Failure('JOURNAL_INVALID_MANIFEST')
        names.add(name)
    if set(path.name for path in stage.iterdir())!=set(files)|{'.timekeeper-batch.json'}:raise Failure('JOURNAL_UNEXPECTED_TRANSFER_FILE')
    return manifest,files


def command(original):
    arguments=shlex.split(original)
    if len(arguments)<5 or arguments[:2]!=['rsync','--server'] or '--daemon' in arguments or arguments[-2]!='.':raise Failure('JOURNAL_COMMAND_DENIED')
    sending='--sender' in arguments;path=arguments[-1]
    if path=='.' and not sending:return False,None,original
    prefix='outgoing/' if sending else 'incoming/'
    if not path.startswith(prefix):raise Failure('JOURNAL_COMMAND_DENIED')
    exchange=identifier(path[len(prefix):].removesuffix('/'))
    arguments[-1]='.'
    return sending,exchange,shlex.join(arguments)


def input_object(fields,required):
    raw=sys.stdin.buffer.read(65537)
    if len(raw)>65536:raise Failure('JOURNAL_INVALID_MANIFEST')
    value=json.loads(raw)
    if not isinstance(value,dict) or not required<=set(value) or not set(value)<=fields:raise Failure('JOURNAL_INVALID_MANIFEST')
    return value


def results(config,device,exchange):
    return request(config,'/exchanges/'+exchange+'/results',{'deviceId':device})


def prepared_files(stage,config,device,exchange,value):
    if value.get('format')!=2 or value.get('exchangeId')!=exchange or type(value.get('pending')) is not int or value['pending']!=0 or not isinstance(value.get('offers'),list) or not isinstance(value.get('errors'),list) or len(value['offers'])+len(value['errors'])>50:raise Failure('JOURNAL_EXCHANGE_PENDING')
    names=set()
    for offer in value['offers']:
        name=note_name(offer['filename']);identifier(offer['id'])
        if name in names:raise Failure('JOURNAL_INVALID_MANIFEST')
        names.add(name)
        if offer['state']!='OFFERED':continue
        if type(offer['targetExists']) is not bool:raise Failure('JOURNAL_INVALID_MANIFEST')
        if not offer['targetExists']:continue
        data=request(config,'/exchanges/'+exchange+'/files/'+offer['id'],{'deviceId':device},binary=True)
        if hashlib.sha256(data).hexdigest()!=offer['targetHash']:raise Failure('JOURNAL_INCOMPLETE_TRANSFER')
        with (stage/name).open('xb') as target:target.write(data)
    raw=json.dumps(value,separators=(',',':')).encode()
    if len(raw)>65536:raise Failure('JOURNAL_INVALID_MANIFEST')
    with (stage/'.timekeeper-results.json').open('xb') as target:target.write(raw)


def run_rsync(stage,original,sending,heartbeat,finish):
    environment={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','HOME':pwd.getpwuid(os.getuid()).pw_dir,'SSH_ORIGINAL_COMMAND':original}
    def limits():
        resource.setrlimit(resource.RLIMIT_FSIZE,(FILE_BYTES,FILE_BYTES));resource.setrlimit(resource.RLIMIT_CPU,(60,60));os.umask(0o077)
    options=[RR_READ,'-ro'] if sending else [RR,'-wo','-munge','-no-del']
    process=subprocess.Popen(['/usr/bin/python3','-I',*options,str(stage)],env=environment,stderr=subprocess.DEVNULL,start_new_session=True,preexec_fn=limits)
    def cancel(signum,frame):
        with contextlib.suppress(ProcessLookupError):os.killpg(process.pid,signal.SIGKILL)
        raise Failure('JOURNAL_TRANSFER_ABORTED')
    signals=(signal.SIGTERM,signal.SIGHUP,signal.SIGINT);previous={item:signal.signal(item,cancel) for item in signals}
    deadline=time.monotonic()+900
    try:
        while process.poll() is None:
            if time.monotonic()>deadline:raise Failure('JOURNAL_TRANSFER_EXPIRED')
            stage_limits(stage);heartbeat();time.sleep(1)
        if process.returncode:raise Failure('JOURNAL_RSYNC_FAILED')
        heartbeat();finish(deadline)
    finally:
        if process.poll() is None:
            with contextlib.suppress(ProcessLookupError):os.killpg(process.pid,signal.SIGKILL)
        process.wait(timeout=5)
        for item,handler in previous.items():signal.signal(item,handler)


def stage_limits(stage):
    count=total=0
    for directory,folders,files in os.walk(stage,followlinks=False):
        count+=len(folders)+len(files)
        if count>210:raise Failure('JOURNAL_BATCH_TOO_LARGE')
        for name in files:
            value=os.lstat(Path(directory)/name);total+=value.st_size
            if value.st_size>FILE_BYTES or total>32*1024*1024:raise Failure('JOURNAL_BATCH_TOO_LARGE')


def receive(user,device):
    user=username(user);device=str(uuid.UUID(device))
    if pwd.getpwnam(user).pw_uid!=os.geteuid():raise Failure('JOURNAL_GATEWAY_IDENTITY_MISMATCH')
    config=private_json(ACCOUNT_ROOT/(user+'.json'))
    if config['username']!=user:raise Failure('JOURNAL_GATEWAY_IDENTITY_MISMATCH')
    original=os.environ.get('SSH_ORIGINAL_COMMAND','')
    if original=='timekeeper-journal-test':
        print(json.dumps(request(config,'/verify',{'deviceId':device})));return
    if original=='timekeeper-journal-inventory':
        value=input_object({'after'},set())
        if 'after' in value:note_name(value['after'])
        print(json.dumps(request(config,'/exchanges/inventory',{'deviceId':device,**value})));return
    if original.startswith('timekeeper-journal-results '):
        exchange=identifier(original.removeprefix('timekeeper-journal-results '))
        print(json.dumps(results(config,device,exchange)));return
    if original=='timekeeper-journal-ack':
        value=input_object({'offerId','token','exists','sha256'},{'offerId','token','exists','sha256'})
        offer=identifier(value.pop('offerId'));identifier(value['token'])
        if type(value['exists']) is not bool or not isinstance(value['sha256'],str) or not (re.fullmatch('[a-f0-9]{64}',value['sha256']) if value['exists'] else value['sha256']=='ABSENT'):raise Failure('JOURNAL_INVALID_MANIFEST')
        print(json.dumps(request(config,'/offers/'+offer+'/acknowledge',{'deviceId':device,**value})));return
    # rrsync performs the full current upstream option/path validation. This gate adds the protocol role.
    sending,exchange,original=command(original)
    root=Path(config['spool']);value=root.lstat()
    if not stat.S_ISDIR(value.st_mode) or value.st_uid!=os.getuid() or value.st_mode&0o077:raise Failure('JOURNAL_PRIVATE_STORAGE_UNAVAILABLE')
    with tempfile.TemporaryDirectory(prefix='transfer-',dir=root) as temporary:
        stage=Path(temporary)
        if sending:
            prepared_files(stage,config,device,exchange,results(config,device,exchange))
            run_rsync(stage,original,True,lambda:results(config,device,exchange),lambda deadline:None)
            return
        lease=request(config,'/transfers',{'deviceId':device,**({'exchangeId':exchange} if exchange else {})});transfer=identifier(lease['id']);completed=False
        def heartbeat():
            if lease['status']=='COMPLETED':results(config,device,exchange)
            else:request(config,'/transfers/'+transfer+'/heartbeat',{})
        def finish(deadline):
            nonlocal completed
            manifest,files=completed_files(stage,exchange)
            if lease['status']!='COMPLETED':
                for name,content in files.items():
                    heartbeat();request(config,'/transfers/'+transfer+'/files/'+name,{'data':base64.b64encode(content).decode()},'PUT')
            manifest['completed']=True
            # Explicit seal only after rsync exit 0 and all content/manifest checks.
            request(config,'/transfers/'+transfer+'/complete',manifest);completed=True
            while request(config,'/transfers/'+transfer+'/settle',{})['pending']:
                if time.monotonic()>deadline:raise Failure('JOURNAL_IMPORT_PENDING')
                time.sleep(1)
        try:run_rsync(stage,original,False,heartbeat,finish)
        finally:
            if not completed:
                with contextlib.suppress(Failure):request(config,'/transfers/'+transfer+'/abort',{})


def main():
    os.umask(0o077)
    try:
        if len(sys.argv)==5 and sys.argv[1]=='authorize':authorize(*sys.argv[2:]);return 0
        if len(sys.argv)==4 and sys.argv[1]=='receive':receive(*sys.argv[2:]);return 0
        raise Failure('JOURNAL_COMMAND_DENIED')
    except (Failure,OSError,ValueError,KeyError,TypeError,subprocess.SubprocessError):
        if len(sys.argv)>1 and sys.argv[1]=='authorize':return 0
        error=sys.exception();print(str(error) if isinstance(error,Failure) else 'JOURNAL_GATEWAY_FAILED',file=sys.stderr);return 1


if __name__=='__main__':raise SystemExit(main())
