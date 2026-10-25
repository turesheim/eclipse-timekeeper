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


def request(config,path,data,method='POST'):
    url=config['brokerUrl'];parsed=urlsplit(url)
    if parsed.scheme!='https' and not(parsed.scheme=='http' and parsed.hostname in ['127.0.0.1','::1','localhost']):raise Failure('JOURNAL_ENCRYPTED_BROKER_REQUIRED')
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path!='/internal/journal':raise Failure('JOURNAL_GATEWAY_CONFIGURATION_INVALID')
    if not re.fullmatch(r'[A-Za-z0-9_-]{43,128}',config['token']):raise Failure('JOURNAL_GATEWAY_CONFIGURATION_INVALID')
    context=ssl.create_default_context(cafile=config.get('caFile') or None)
    opener=build_opener(NoRedirect(),HTTPSHandler(context=context))
    call=Request(url+'/'+username(config['username'])+path,json.dumps(data,separators=(',',':')).encode(),
                 {'Authorization':'JournalTransport '+config['token'],'Content-Type':'application/json','Accept':'application/json'},method=method)
    try:
        with opener.open(call,timeout=30) as response:
            raw=response.read(65537)
            if len(raw)>65536:raise Failure('JOURNAL_BROKER_UNAVAILABLE')
            return json.loads(raw)
    except HTTPError as error:
        try:code=json.loads(error.read(4096)).get('code','')
        except (ValueError,UnicodeError):code=''
        raise Failure(code if re.fullmatch(r'JOURNAL_[A-Z_]{1,80}',code) else 'JOURNAL_BROKER_ACCESS_DENIED') from None
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


def completed_files(stage):
    manifest=json.loads(read_regular(stage/'.timekeeper-batch.json',65536))
    if set(manifest)!=set(['format','files','errors']) or manifest['format']!=1 or not isinstance(manifest['files'],list) or not isinstance(manifest['errors'],list) or len(manifest['files'])+len(manifest['errors'])>100:raise Failure('JOURNAL_INVALID_MANIFEST')
    files={}
    for entry in manifest['files']:
        if not isinstance(entry,dict) or set(entry)!=set(['name','bytes','sha256']):raise Failure('JOURNAL_INVALID_MANIFEST')
        name=entry['name']
        if not NAME.fullmatch(name) or name in files or type(entry['bytes']) is not int or not 0<=entry['bytes']<=FILE_BYTES or not re.fullmatch(r'[a-f0-9]{64}',entry['sha256']):raise Failure('JOURNAL_INVALID_MANIFEST')
        content=read_regular(stage/name,FILE_BYTES)
        if len(content)!=entry['bytes'] or hashlib.sha256(content).hexdigest()!=entry['sha256']:raise Failure('JOURNAL_INCOMPLETE_TRANSFER')
        files[name]=content
    errors=set()
    for entry in manifest['errors']:
        if not isinstance(entry,dict) or set(entry)!=set(['name','code']) or not NAME.fullmatch(entry['name']) or entry['name'] in files or entry['name'] in errors or entry['code'] not in ['FILE_TOO_LARGE','UNSAFE_LOCAL_FILE','FILE_CHANGED','LOCAL_FILE_UNREADABLE']:raise Failure('JOURNAL_INVALID_MANIFEST')
        errors.add(entry['name'])
    if set(path.name for path in stage.iterdir())!=set(files)|{'.timekeeper-batch.json'}:raise Failure('JOURNAL_UNEXPECTED_TRANSFER_FILE')
    return manifest,files


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
    # rrsync performs the full current upstream option/path validation. This gate adds the protocol role.
    arguments=shlex.split(original)
    if len(arguments)<4 or arguments[:2]!=['rsync','--server'] or '--sender' in arguments or '--daemon' in arguments:raise Failure('JOURNAL_COMMAND_DENIED')
    lease=request(config,'/transfers',{'deviceId':device});identifier=str(uuid.UUID(lease['id']))
    root=Path(config['spool']);value=root.lstat()
    if not stat.S_ISDIR(value.st_mode) or value.st_uid!=os.getuid() or value.st_mode&0o077:raise Failure('JOURNAL_PRIVATE_STORAGE_UNAVAILABLE')
    with tempfile.TemporaryDirectory(prefix='transfer-',dir=root) as temporary:
        stage=Path(temporary)
        environment={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','HOME':pwd.getpwuid(os.getuid()).pw_dir,'SSH_ORIGINAL_COMMAND':original}
        def limits():
            resource.setrlimit(resource.RLIMIT_FSIZE,(FILE_BYTES,FILE_BYTES));resource.setrlimit(resource.RLIMIT_CPU,(60,60));os.umask(0o077)
        process=subprocess.Popen(['/usr/bin/python3','-I',RR,'-wo','-munge','-no-del',str(stage)],env=environment,stderr=subprocess.DEVNULL,start_new_session=True,preexec_fn=limits)
        def cancel(signum,frame):
            with contextlib.suppress(ProcessLookupError):os.killpg(process.pid,signal.SIGKILL)
            raise Failure('JOURNAL_TRANSFER_ABORTED')
        signal.signal(signal.SIGTERM,cancel);signal.signal(signal.SIGHUP,cancel);signal.signal(signal.SIGINT,cancel)
        deadline=time.monotonic()+900
        try:
            while process.poll() is None:
                if time.monotonic()>deadline:raise Failure('JOURNAL_TRANSFER_EXPIRED')
                stage_limits(stage);request(config,'/transfers/'+identifier+'/heartbeat',{});time.sleep(1)
            if process.returncode:raise Failure('JOURNAL_RSYNC_FAILED')
            manifest,files=completed_files(stage)
            for name,content in files.items():
                request(config,'/transfers/'+identifier+'/heartbeat',{})
                request(config,'/transfers/'+identifier+'/files/'+name,{'data':base64.b64encode(content).decode()},'PUT')
            manifest['completed']=True
            # Explicit seal only after rsync exit 0 and all content/manifest checks.
            request(config,'/transfers/'+identifier+'/complete',manifest)
            while request(config,'/transfers/'+identifier+'/settle',{})['pending']:
                if time.monotonic()>deadline:raise Failure('JOURNAL_IMPORT_PENDING')
                time.sleep(1)
        finally:
            if process.poll() is None:
                with contextlib.suppress(ProcessLookupError):os.killpg(process.pid,signal.SIGKILL)
            process.wait(timeout=5)


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
