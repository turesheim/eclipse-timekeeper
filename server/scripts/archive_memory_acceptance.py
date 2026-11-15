#!/usr/bin/env python3
"""Exercise private exports on real PostgreSQL with synthetic files larger than a constrained heap.

The default 384 MiB test budget deliberately reproduces the old deployment limit;
supported production servers require at least 2 GiB. Docker resources and records
are disposable. No live account, credentials or production server are contacted.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,urlopen
import zipfile

ROOT=Path(__file__).resolve().parents[2]


def command(*arguments,input=None,check=True):
    return subprocess.run(arguments,input=input,capture_output=True,check=check,timeout=180)


def zero_digest(size):
    digest=hashlib.sha256();chunk=bytes(1024*1024)
    while size:piece=chunk[:min(size,len(chunk))];digest.update(piece);size-=len(piece)
    return digest.hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution',type=Path,required=True,help='Fresh PostgreSQL-profile server ZIP')
    parser.add_argument('--postgres-image',default='postgres:17.11-bookworm')
    parser.add_argument('--heap',default='384m')
    parser.add_argument('--files',type=int,default=80)
    parser.add_argument('--file-bytes',type=int,default=6*1024*1024)
    parser.add_argument('--large-file-bytes',type=int,default=150*1024*1024)
    parser.add_argument('--expect-oom',action='store_true',help='Diagnose an old build; require the known heap failure')
    parser.add_argument('--output',type=Path,default=ROOT/'server/target/archive-memory-acceptance.json')
    args=parser.parse_args()
    if args.files<1 or min(args.file_bytes,args.large_file_bytes)<1 or any(size%3 for size in [args.file_bytes,args.large_file_bytes]):parser.error('Use positive byte sizes divisible by three')
    digest=hashlib.sha256(args.distribution.read_bytes()).hexdigest()
    if digest!=args.distribution.with_suffix('.zip.sha256').read_text().split()[0]:raise ValueError('Distribution checksum mismatch')
    suffix=secrets.token_hex(6);database='dagsverk-archive-memory-'+suffix;process=None
    args.output.parent.mkdir(parents=True,exist_ok=True);log_path=args.output.with_suffix('.server.log')
    report={'distributionSha256':digest,'heap':args.heap,'postgresImage':args.postgres_image,'files':args.files+1,'uncompressedBytes':args.files*args.file_bytes+args.large_file_bytes,'passed':False}
    with tempfile.TemporaryDirectory(prefix='dagsverk-archive-memory-') as folder:
        folder=Path(folder);token=secrets.token_urlsafe(32);owner='archive-memory-owner'
        registry=folder/'tokens.json';registry.write_text(json.dumps([{'ownerId':owner,'sha256':hashlib.sha256(token.encode()).hexdigest(),'permissions':['read','write']}]));registry.chmod(0o600)
        with zipfile.ZipFile(args.distribution) as archive:
            for name in archive.namelist():
                if not name.startswith('timekeeper-server/') or '..' in Path(name).parts or Path(name).is_absolute():raise ValueError('Unexpected distribution path')
            archive.extractall(folder);report['sourceCommit']=json.loads(archive.read('timekeeper-server/timekeeper-release.json'))['commit']
        try:
            command('docker','run','--rm','-d','--name',database,'-e','POSTGRES_PASSWORD='+suffix,'-p','127.0.0.1::5432',args.postgres_image)
            for _ in range(100):
                if command('docker','exec',database,'pg_isready','-U','postgres',check=False).returncode==0:break
                time.sleep(.1)
            else:raise RuntimeError('Disposable PostgreSQL did not become ready')
            pg_port=command('docker','port',database,'5432/tcp').stdout.decode().strip().rsplit(':',1)[1]
            with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
            base='http://127.0.0.1:'+str(port)
            environment={key:value for key,value in os.environ.items() if not key.startswith(('TIMEKEEPER_','DAGSVERK_','QUARKUS_')) and key not in {'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'}}
            environment.update({'QUARKUS_PROFILE':'postgresql','TIMEKEEPER_SERVER_DB_URL':'jdbc:postgresql://127.0.0.1:'+pg_port+'/postgres','TIMEKEEPER_SERVER_DB_USER':'postgres','TIMEKEEPER_SERVER_DB_PASSWORD':suffix,'TIMEKEEPER_SERVER_TOKENS_FILE':str(registry),'TIMEKEEPER_SERVER_SETUP_FILE':'NONE','TIMEKEEPER_PUBLIC_ORIGIN':'NONE','TIMEKEEPER_CONNECT_SHARED':'false','TIMEKEEPER_CALENDAR_AUTOMATIC':'false','TIMEKEEPER_TASK_AUTOMATIC':'false','TIMEKEEPER_NOTE_TASKS_AUTOMATIC':'false'})
            with log_path.open('w') as log:
                log_path.chmod(0o600)
                process=subprocess.Popen(['java','-Xms64m','-Xmx'+args.heap,'-XX:+ExitOnOutOfMemoryError','-Dquarkus.http.host=127.0.0.1','-Dquarkus.http.port='+str(port),'-jar',str(folder/'timekeeper-server/quarkus-run.jar')],env=environment,stdout=log,stderr=subprocess.STDOUT)
                def request(path,method='GET',body=None,raw=None):
                    headers={'Authorization':'Bearer '+token};data=None
                    if body is not None:headers['Content-Type']='application/json';data=json.dumps(body).encode()
                    if raw is not None:headers.update({'Content-Type':'application/octet-stream','If-None-Match':'*'});data=raw
                    with urlopen(Request(base+path,data=data,method=method,headers=headers),timeout=5) as reply:return json.load(reply)
                for _ in range(200):
                    if process.poll() is not None:raise RuntimeError('Disposable server stopped during startup; see private server log')
                    try:
                        with urlopen(base+'/q/health/ready',timeout=1) as reply:
                            if reply.status==200:break
                    except OSError:pass
                    time.sleep(.1)
                else:raise RuntimeError('Disposable server did not become ready')
                request('/api/v1/me');request('/api/v1/notes');request('/api/v1/notes/folders','POST',{'path':'Bulk'})
                expected={};updates=[]
                for index,size in enumerate([args.file_bytes]*args.files+[args.large_file_bytes]):
                    path='Bulk/file-'+str(index)+'.bin';entry=request('/api/v1/notes/files?'+urlencode({'path':path}),'PUT',raw=b'x');source=entry['sourceId'];sha=zero_digest(size);expected[path]=(size,sha,source)
                    # Expand only disposable canonical bytes after their ordinary
                    # API-created namespace entry exists. Large uploads are a
                    # separate behavior; this fixture isolates export memory.
                    updates.append("UPDATE tk_resource SET payload=((payload::jsonb)||jsonb_build_object('data',repeat('A',%d),'byteLength',%d,'sha256','%s'))::text WHERE owner_id='%s' AND entity_type='VAULT_CONTENT' AND id='%s';"%(size//3*4,size,sha,owner,source))
                command('docker','exec','-i',database,'psql','-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1',input=('\n'.join(updates)).encode())
                report['checks']=[]
                def download(endpoint,target):
                    with urlopen(Request(base+endpoint,headers={'Authorization':'Bearer '+token,'Accept':'application/zip'}),timeout=300) as reply,target.open('wb') as output:shutil.copyfileobj(reply,output,1024*1024)
                try:
                    for kind,endpoint in [('account','/api/v1/data/account/export'),('notes','/api/v1/notes/export')]:
                        target=folder/(kind+'.zip');latencies=[];started=time.monotonic()
                        with ThreadPoolExecutor(max_workers=1) as worker:
                            pending=worker.submit(download,endpoint,target)
                            while not pending.done():
                                tick=time.monotonic();request('/q/health/ready');request('/api/v1/me');latencies.append(time.monotonic()-tick);time.sleep(.1)
                            pending.result()
                        with zipfile.ZipFile(target) as archive:
                            for path,(size,sha,source) in expected.items():
                                name='vault/content/'+source+'.bin' if kind=='account' else path
                                if archive.getinfo(name).file_size!=size:raise AssertionError('Exported byte length changed')
                                checksum=hashlib.sha256()
                                with archive.open(name) as content:
                                    while chunk:=content.read(1024*1024):checksum.update(chunk)
                                if checksum.hexdigest()!=sha:raise AssertionError('Exported file bytes changed')
                        if not latencies:raise AssertionError('Export completed without a concurrent responsiveness check')
                        report['checks'].append({'kind':kind,'seconds':round(time.monotonic()-started,3),'concurrentHealthAndAccountReads':len(latencies),'maximumCombinedReadSeconds':round(max(latencies),3)})
                except Exception:
                    if not args.expect_oom:raise
                    time.sleep(.3);log.flush()
                    if 'OutOfMemoryError' not in log_path.read_text() and 'Java heap space' not in log_path.read_text():raise
                    report['expectedOutOfMemoryObserved']=True
                else:
                    if args.expect_oom:raise AssertionError('Expected the old heap failure but exports succeeded')
                report['passed']=True
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:process.wait(timeout=15)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
            command('docker','rm','-f',database,check=False)
            args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report));return 0


if __name__=='__main__':raise SystemExit(main())
