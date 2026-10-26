#!/usr/bin/env python3
"""Timekeeper daily Markdown synchronization. Private keys stay with local SSH."""
import argparse
import base64
import contextlib
from datetime import date
import fcntl
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shlex
import shutil
import stat
import sqlite3
import subprocess
import sys
import tempfile
import uuid

FILE_BYTES=220000
BATCH_FILES=100
DATE_NAME=re.compile(r'[0-9]{4}-[0-9]{2}-[0-9]{2}\.md\Z')


class Failure(Exception):
    pass


def fail(message):
    raise Failure(message)


def require(condition):
    # Profile validation must also run when Python optimization is enabled.
    if not condition:raise ValueError('Invalid configuration')


def read_json(path,private=False):
    if private:private_file(path)
    with path.open('rb') as source:
        data=source.read(65537)
    if len(data)>65536:fail('Configuration is too large.')
    try:return json.loads(data)
    except (ValueError,UnicodeError):fail('Invalid JSON configuration.')


def private_file(path):
    value=path.lstat()
    if not stat.S_ISREG(value.st_mode) or value.st_uid!=os.getuid() or value.st_mode&0o077:
        fail('Use a regular, user-owned file readable only by your user (chmod 600).')


def private_directory(path):
    try:path.mkdir(mode=0o700)
    except FileExistsError:pass
    value=path.lstat()
    if not stat.S_ISDIR(value.st_mode) or value.st_uid!=os.getuid() or value.st_mode&0o077:
        fail('The local sync state directory must be private and user-owned (chmod 700).')


def atomic(path,data):
    if path.is_symlink():fail('Refusing a symbolic link for local sync configuration.')
    descriptor,name=tempfile.mkstemp(prefix='.timekeeper-',dir=path.parent)
    try:
        with os.fdopen(descriptor,'wb') as output:
            output.write(data);output.flush();os.fsync(output.fileno())
        os.replace(name,path)
    finally:
        with contextlib.suppress(FileNotFoundError):os.unlink(name)


def sync_directory(path):
    descriptor=os.open(path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:os.fsync(descriptor)
    finally:os.close(descriptor)


def file_name(name):
    if not isinstance(name,str) or not DATE_NAME.fullmatch(name):fail('The exchange contains an invalid daily-note filename.')
    return name


def note_name(name):
    try:file_name(name);date.fromisoformat(name[:-3])
    except (ValueError,TypeError):fail('The exchange contains an invalid daily-note filename.')
    return name


class Replica:
    """Private durable local operations; never overwrite an editor's replacement."""
    ABSENT='ABSENT'
    def __init__(self,state,folder,device):
        self.root=state.resolve()/'replica';self.folder=folder.resolve(strict=True);self.device=device
        self.database=None;self.lock=None;self.folder_fd=None
        if self.root.is_relative_to(self.folder):fail('Keep the profile and private sync state outside the daily-note folder.')
        private_directory(self.root)
        if self.root.stat().st_dev!=self.folder.stat().st_dev:fail('Bidirectional profiles and their private state must share the daily folder’s filesystem. Select a private folder outside the vault on that filesystem.')
        try:
            lock=self.root/'sync.lock'
            if lock.exists():private_file(lock)
            self.lock=os.open(lock,os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
            try:fcntl.flock(self.lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            except BlockingIOError:fail('Another sync is already using this device profile.')
            self.folder_fd=os.open(self.folder,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            path=self.root/'state.sqlite3'
            if path.exists():private_file(path)
            else:
                descriptor=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW,0o600);os.close(descriptor)
            self.database=sqlite3.connect(path);self.database.row_factory=sqlite3.Row
            self.database.execute('PRAGMA journal_mode=WAL');self.database.execute('PRAGMA synchronous=FULL');self.database.execute('PRAGMA temp_store=MEMORY')
            self.database.executescript('CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL); CREATE TABLE IF NOT EXISTS base(name TEXT PRIMARY KEY,token TEXT NOT NULL,hash TEXT NOT NULL,present INTEGER NOT NULL,sequence INTEGER NOT NULL); CREATE TABLE IF NOT EXISTS operation(id TEXT PRIMARY KEY,name TEXT NOT NULL,descriptor TEXT NOT NULL,phase TEXT NOT NULL,prepared TEXT NOT NULL,backup TEXT NOT NULL); CREATE TABLE IF NOT EXISTS recovery(id TEXT PRIMARY KEY,name TEXT NOT NULL,backup TEXT NOT NULL,expected_hash TEXT NOT NULL,reason TEXT NOT NULL); CREATE TABLE IF NOT EXISTS exchange(id TEXT PRIMARY KEY,manifest TEXT NOT NULL);')
            identity=os.fstat(self.folder_fd);binding=str(identity.st_dev)+':'+str(identity.st_ino)
            with self.database:
                for key,value in [('device',device),('folder',binding),('client',str(uuid.uuid4()))]:
                    self.database.execute('INSERT OR IGNORE INTO meta(key,value) VALUES(?,?)',(key,value))
            if self.meta('device')!=device or self.meta('folder')!=binding:fail('The configured folder or device identity changed. Keep the originals and register a new device profile; missing files must not become accidental deletions.')
            sync_directory(self.root)
        except BaseException:self.close();raise
    def __enter__(self):return self
    def __exit__(self,*args):self.close()
    def close(self):
        if self.database is not None:self.database.close();self.database=None
        if self.folder_fd is not None:os.close(self.folder_fd);self.folder_fd=None
        if self.lock is not None:os.close(self.lock);self.lock=None
    def meta(self,key):return self.database.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0]
    @property
    def client_id(self):return self.meta('client')
    def bases(self):return {row['name']:dict(row) for row in self.database.execute('SELECT * FROM base')}
    def pending(self):return [dict(row) for row in self.database.execute('SELECT * FROM operation ORDER BY rowid')]
    def recoveries(self):return [dict(row) for row in self.database.execute('SELECT * FROM recovery ORDER BY rowid')]
    def exchanges(self):return [(row['id'],json.loads(row['manifest'])) for row in self.database.execute('SELECT * FROM exchange ORDER BY rowid')]
    def save_exchange(self,identifier,manifest):
        sync_directory(self.root/identifier);sync_directory(self.root)
        with self.database:self.database.execute('INSERT INTO exchange VALUES(?,?)',(identifier,json.dumps(manifest,separators=(',',':'))))
    def finish_exchange(self,identifier):
        with self.database:self.database.execute('DELETE FROM exchange WHERE id=?',(identifier,))
        shutil.rmtree(self.root/identifier);sync_directory(self.root)
    def folder_unchanged(self):
        current=self.folder.stat(follow_symlinks=False);opened=os.fstat(self.folder_fd)
        if not stat.S_ISDIR(current.st_mode) or (current.st_dev,current.st_ino)!=(opened.st_dev,opened.st_ino):fail('The daily-note folder changed during sync. Originals and private recovery copies are kept.')
    def read_current(self,name):
        note_name(name);self.folder_unchanged()
        try:descriptor=os.open(name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=self.folder_fd)
        except FileNotFoundError:return None
        with os.fdopen(descriptor,'rb') as source:
            before=os.fstat(source.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_nlink!=1 or before.st_size>FILE_BYTES:fail('A daily note is unsafe or too large. Keep it and resolve the local file error before syncing.')
            content=source.read(FILE_BYTES+1);after=os.fstat(source.fileno())
        if len(content)>FILE_BYTES or len(content)!=after.st_size or (before.st_size,before.st_mtime_ns,before.st_ctime_ns)!=(after.st_size,after.st_mtime_ns,after.st_ctime_ns):fail('A daily note changed while being read. Retry after the editor has finished saving.')
        return content
    @staticmethod
    def content_hash(content):return Replica.ABSENT if content is None else hashlib.sha256(content).hexdigest()
    def descriptor(self,offer):
        try:
            for key in ['id','token']:require(str(uuid.UUID(offer[key]))==offer[key])
            note_name(offer['filename']);require(type(offer['sequence']) is int and 0<offer['sequence']<9007199254740991)
            for prefix in ['expected','target']:
                require(type(offer[prefix+'Exists']) is bool)
                require(bool(re.fullmatch('[a-f0-9]{64}',offer[prefix+'Hash'])) if offer[prefix+'Exists'] else offer[prefix+'Hash']==self.ABSENT)
            require(offer['state']=='OFFERED')
            require(offer.get('parentToken','')=='' or str(uuid.UUID(offer['parentToken']))==offer['parentToken'])
        except (KeyError,TypeError,ValueError):fail('Invalid prepared journal exchange. No local note was replaced.')
        return offer
    def parent(self,offer):
        current=self.database.execute('SELECT token FROM base WHERE name=?',(offer['filename'],)).fetchone()
        token='' if current is None else current['token']
        if token not in [offer.get('parentToken',''),offer['token']]:fail('A stale export cannot replace newer local synchronization state. Refresh the exchange; originals are kept.')
    def _phase(self,identifier,phase):
        with self.database:self.database.execute('UPDATE operation SET phase=? WHERE id=?',(phase,identifier))
    def _backup(self,path):
        private_file(path);before=path.lstat()
        if before.st_nlink!=1 or before.st_size>FILE_BYTES:fail('Unsafe private recovery copy; automatic sync has stopped.')
        descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        with os.fdopen(descriptor,'rb') as source:
            content=source.read(FILE_BYTES+1);after=os.fstat(source.fileno())
        if len(content)>FILE_BYTES or (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns,before.st_ctime_ns)!=(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns,after.st_ctime_ns):fail('A recovery copy is still being edited; automatic sync has stopped.')
        return content
    def _conflict(self,operation,reason):
        backup=self.root/operation['backup'];offer=json.loads(operation['descriptor'])
        if os.path.lexists(backup):
            # Exclusive restore preserves an editor's independently created file.
            with contextlib.suppress(FileExistsError,OSError):os.link(backup,operation['name'],dst_dir_fd=self.folder_fd,follow_symlinks=False)
            with self.database:self.database.execute('INSERT OR REPLACE INTO recovery VALUES(?,?,?,?,?)',(operation['id'],operation['name'],operation['backup'],offer['expectedHash'],reason))
        elif (self.root/operation['prepared']).exists():
            with self.database:self.database.execute('INSERT OR REPLACE INTO recovery VALUES(?,?,?,?,?)',(operation['id'],operation['name'],operation['prepared'],offer['targetHash'],reason))
        self._phase(operation['id'],'CONFLICT');sync_directory(self.root);os.fsync(self.folder_fd)
        fail('A local edit interrupted an export. Both variants are kept; run recoveries and resolve this operation before syncing again. Recovery ID: '+operation['id'])
    def check_recoveries(self):
        for row in self.recoveries():
            path=self.root/row['backup']
            if row['reason']!='ACCEPTED_EXPORT' or self.content_hash(self._backup(path))!=row['expected_hash']:
                fail('A private recovery copy contains an unresolved or later editor change. Run recoveries before syncing again. Recovery ID: '+row['id'])
    def apply(self,offer,content):
        offer=self.descriptor(offer);self.parent(offer);identifier=offer['id'];name=offer['filename']
        if (content is not None)!=offer['targetExists'] or self.content_hash(content)!=offer['targetHash']:fail('Export content does not match its authenticated exchange. No local note was replaced.')
        if content is not None:
            if len(content)>FILE_BYTES:fail('Exported note exceeds the supported file limit.')
            try:content.decode('utf-8')
            except UnicodeError:fail('Exported note is not valid UTF-8.')
        old=self.database.execute('SELECT * FROM operation WHERE id=?',(identifier,)).fetchone()
        if old is not None:
            if json.loads(old['descriptor'])!=offer:fail('An exchange identifier was reused. Stop and inspect private recovery state.')
            return self.resume(dict(old))
        if self.database.execute('SELECT count(*) FROM recovery').fetchone()[0]>=500:fail('Private recovery storage is full. Review and explicitly discard old recovery copies before more exports.')
        prepared=identifier+'.new';backup=identifier+'.old'
        if content is not None:atomic(self.root/prepared,content)
        sync_directory(self.root)
        with self.database:self.database.execute('INSERT INTO operation VALUES(?,?,?,?,?,?)',(identifier,name,json.dumps(offer,separators=(',',':')),'PLANNED',prepared,backup))
        return self.resume(dict(self.database.execute('SELECT * FROM operation WHERE id=?',(identifier,)).fetchone()))
    def resume(self,operation):
        offer=self.descriptor(json.loads(operation['descriptor']));self.parent(offer);name=operation['name'];self.folder_unchanged()
        if operation['phase']=='CONFLICT':fail('Resolve the pending private recovery before syncing. Recovery ID: '+operation['id'])
        if operation['phase']=='APPLIED':return offer
        backup=self.root/operation['backup'];prepared=self.root/operation['prepared']
        # A source/target no-op records the earlier common point and never
        # touches a newer editor save that happened during the network exchange.
        if offer['expectedExists']==offer['targetExists'] and offer['expectedHash']==offer['targetHash']:
            self._phase(operation['id'],'APPLIED');return offer
        if prepared.exists():
            try:
                target=os.stat(name,dir_fd=self.folder_fd,follow_symlinks=False);source=prepared.lstat()
                same_inode=(target.st_dev,target.st_ino)==(source.st_dev,source.st_ino)
            except FileNotFoundError:same_inode=False
            if same_inode:
                descriptor=os.open(prepared,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
                with os.fdopen(descriptor,'rb') as stream:content=stream.read(FILE_BYTES+1)
                if self.content_hash(content)!=offer['targetHash']:return self._conflict(operation,'LOCAL_EDIT_DURING_EXPORT')
                prepared.unlink();sync_directory(self.root)
        if not os.path.lexists(backup):
            current=self.read_current(name)
            if self.content_hash(current)==offer['targetHash']:
                self._phase(operation['id'],'APPLIED');return offer
            if self.content_hash(current)!=offer['expectedHash']:return self._conflict(operation,'LOCAL_EDIT_DURING_EXPORT')
            if offer['expectedExists']:
                os.rename(name,backup,src_dir_fd=self.folder_fd);os.fsync(self.folder_fd);sync_directory(self.root)
                value=backup.lstat()
                if not stat.S_ISREG(value.st_mode) or value.st_nlink!=1:return self._conflict(operation,'UNSAFE_LOCAL_FILE')
                backup.chmod(0o600)
        if os.path.lexists(backup):
            value=backup.lstat()
            if not stat.S_ISREG(value.st_mode) or value.st_nlink!=1:return self._conflict(operation,'UNSAFE_LOCAL_FILE')
            backup.chmod(0o600)
            if self.content_hash(self._backup(backup))!=offer['expectedHash']:return self._conflict(operation,'LOCAL_EDIT_DURING_EXPORT')
        if offer['targetExists']:
            # Resume a crash after link() and before unlinking its private name.
            if prepared.exists():
                try:
                    target=os.stat(name,dir_fd=self.folder_fd,follow_symlinks=False);source=prepared.lstat()
                    installed=(target.st_dev,target.st_ino)==(source.st_dev,source.st_ino)
                except FileNotFoundError:installed=False
                if not installed:
                    try:os.link(prepared,name,dst_dir_fd=self.folder_fd,follow_symlinks=False)
                    except FileExistsError:return self._conflict(operation,'LOCAL_EDIT_DURING_EXPORT')
                prepared.unlink()
            elif self.content_hash(self.read_current(name))!=offer['targetHash']:return self._conflict(operation,'LOCAL_EDIT_DURING_EXPORT')
        elif self.read_current(name) is not None:return self._conflict(operation,'LOCAL_EDIT_DURING_EXPORT')
        os.fsync(self.folder_fd);sync_directory(self.root);self.folder_unchanged()
        if os.path.lexists(backup):
            if self.content_hash(self._backup(backup))!=offer['expectedHash']:return self._conflict(operation,'LOCAL_EDIT_DURING_EXPORT')
            with self.database:self.database.execute('INSERT OR IGNORE INTO recovery VALUES(?,?,?,?,?)',(operation['id'],name,operation['backup'],offer['expectedHash'],'ACCEPTED_EXPORT'))
        self._phase(operation['id'],'APPLIED');return offer
    def acknowledged(self,offer):
        self.descriptor(offer)
        operation=self.database.execute('SELECT * FROM operation WHERE id=?',(offer['id'],)).fetchone()
        if operation is None or operation['phase']!='APPLIED':fail('The local operation has not been applied; its common base cannot advance.')
        current=self.database.execute('SELECT * FROM base WHERE name=?',(offer['filename'],)).fetchone()
        if current is not None and current['sequence']>=offer['sequence']:
            if current['token']!=offer['token']:fail('A stale acknowledgement cannot replace newer local synchronization state.')
        else:
            with self.database:self.database.execute('INSERT OR REPLACE INTO base VALUES(?,?,?,?,?)',(offer['filename'],offer['token'],offer['targetHash'],offer['targetExists'],offer['sequence']))
        with self.database:self.database.execute('DELETE FROM operation WHERE id=?',(offer['id'],))
        with contextlib.suppress(FileNotFoundError):(self.root/operation['prepared']).unlink()
        sync_directory(self.root)


def profile(path):
    data=read_json(path)
    try:
        require(type(data['format']) is int and data['mechanism']=='RSYNC_SSH')
        if data['format']==2:
            require(data['direction']=='BIDIRECTIONAL' and type(data['exchangeProtocol']) is int and data['exchangeProtocol']==1 and data['uploadPrefix']=='incoming/' and data['downloadPrefix']=='outgoing/' and type(data['maxExchangeFiles']) is int and data['maxExchangeFiles']==50)
        else:require(data['format']==1 and data['direction']=='CLIENT_TO_SERVER')
        require(data['minimumRsyncVersion']=='3.5.1')
        require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]{0,252}',data['hostname']) and '..' not in data['hostname'])
        require(type(data['port']) is int and 1<=data['port']<=65535)
        require(re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}',data['username']) and data['remotePath']=='.')
        require(str(uuid.UUID(data['deviceId']))==data['deviceId'])
        require(re.fullmatch(r'timekeeper-journal-[a-f0-9]{24}',data['hostAlias']))
        kind,encoded=data['hostKey'].split(' ');raw=base64.b64decode(encoded,validate=True)
        require(kind=='ssh-ed25519' and raw[:19]==bytes.fromhex('0000000b7373682d6564323535313900000020') and len(raw)==51)
        fingerprint='SHA256:'+base64.b64encode(hashlib.sha256(raw).digest()).decode().rstrip('=')
        require(data['hostKeyFingerprint']==fingerprint)
        require(data['knownHostsEntry']==data['hostAlias']+' '+data['hostKey'])
    except (KeyError,ValueError,TypeError,AssertionError):fail('Invalid public journal profile. Download it again from My account over HTTPS.')
    return data


def paths(profile_path):
    return profile_path.with_suffix('.local.json'),profile_path.parent/('.timekeeper-journal-'+profile(profile_path)['deviceId'])


def configure(args):
    data=profile(args.profile);local,state=paths(args.profile);private_directory(args.profile.parent)
    folder=Path(args.folder or input('Local daily-note folder: ')).expanduser().resolve(strict=True)
    identity=Path(args.identity or input('Local private-key filename: ')).expanduser().absolute()
    if not folder.is_dir():fail('Select an existing daily-note directory.')
    private_file(identity);private_directory(state)
    if data['format']==2:
        with Replica(state,folder,data['deviceId']):pass
    atomic(local,(json.dumps({'format':1,'folder':str(folder),'identity':str(identity)},indent=2)+'\n').encode())
    print('Local paths saved privately. Compare the HTTPS fingerprint, then run trust, verify and sync.')


def trust(args):
    data=profile(args.profile);_,state=paths(args.profile);private_directory(args.profile.parent);private_directory(state)
    if args.fingerprint!=data['hostKeyFingerprint']:fail('Fingerprint does not match the downloaded HTTPS profile. Stop and verify it with the operator.')
    known=state/'known_hosts';entry=(data['knownHostsEntry']+'\n').encode()
    if known.exists():
        private_file(known)
        if known.read_bytes()!=entry and not args.replace:fail('The pinned host key changed. Independently verify the replacement, then use trust --replace --fingerprint.')
    atomic(known,entry);print('Host key pinned. SSH will refuse a different host key.')


def local_config(args):
    data=profile(args.profile);local,state=paths(args.profile);private_directory(args.profile.parent);private_directory(state)
    values=read_json(local,private=True)
    try:
        folder=Path(values['folder']);identity=Path(values['identity'])
        require(values['format']==1 and folder.is_absolute() and identity.is_absolute())
        require(not any(ord(char)<32 for char in str(identity)+str(state)))
    except (KeyError,ValueError,TypeError,AssertionError):fail('Run configure again to select local paths.')
    private_file(identity);known=state/'known_hosts';private_file(known)
    if known.read_bytes()!=(data['knownHostsEntry']+'\n').encode():fail('The profile differs from the pinned key. Verify its HTTPS fingerprint before replacing trust.')
    return data,folder,identity,state


def ssh_arguments(data,identity,state):
    # -o values use SSH config syntax inside one argv entry; quote literal paths too.
    known=str(state/'known_hosts').replace('%','%%').replace('\\','\\\\').replace('"','\\"')
    return ['ssh','-F','/dev/null','-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes',
            '-o','UserKnownHostsFile="'+known+'"','-o','GlobalKnownHostsFile=/dev/null','-o','HostKeyAlias='+data['hostAlias'],
            '-o','ClearAllForwardings=yes','-o','ForwardAgent=no','-o','RequestTTY=no','-o','ControlMaster=no','-o','ControlPath=none',
            '-o','ConnectTimeout=15','-i',str(identity).replace('%','%%'),'-p',str(data['port'])]


def verify(args):
    data,_,identity,state=local_config(args)
    result=subprocess.run(ssh_arguments(data,identity,state)+['-l',data['username'],data['hostname'],'timekeeper-journal-test'],capture_output=True,timeout=30)
    if result.returncode:fail('SSH verification failed. Check the pinned host key, unlocked local agent, network endpoint and device status in My account.')
    try:
        reply=json.loads(result.stdout);require(reply['status']=='READY' and reply['direction']==data['direction'])
    except (ValueError,KeyError,AssertionError):fail('The endpoint did not return the restricted journal verification response.')
    print('Restricted SSH identity verified. Direction: '+data['direction']+'.')


def rsync_version():
    result=subprocess.run(['rsync','--version'],capture_output=True,timeout=10)
    match=re.match(rb'rsync\s+version\s+(\d+)\.(\d+)\.(\d+)',result.stdout)
    if result.returncode or not match or tuple(map(int,match.groups()))<(3,5,1):fail('Install rsync 3.5.1 or later; the old macOS system rsync is not supported.')


def snapshot_file(entry):
    """Never follow a link or read a changing/oversized file into the transfer snapshot."""
    before=entry.stat(follow_symlinks=False)
    if not stat.S_ISREG(before.st_mode) or before.st_nlink!=1:return None,'UNSAFE_LOCAL_FILE'
    if before.st_size>FILE_BYTES:return None,'FILE_TOO_LARGE'
    try:
        descriptor=os.open(entry.path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        with os.fdopen(descriptor,'rb') as source:
            opened=os.fstat(source.fileno())
            if not stat.S_ISREG(opened.st_mode) or opened.st_nlink!=1 or (before.st_dev,before.st_ino)!=(opened.st_dev,opened.st_ino):return None,'FILE_CHANGED'
            content=source.read(FILE_BYTES+1);after=os.fstat(source.fileno())
        if len(content)>FILE_BYTES:return None,'FILE_TOO_LARGE'
        if (opened.st_size,opened.st_mtime_ns,opened.st_ctime_ns)!=(after.st_size,after.st_mtime_ns,after.st_ctime_ns) or len(content)!=after.st_size:return None,'FILE_CHANGED'
        return content,None
    except OSError:return None,'LOCAL_FILE_UNREADABLE'


def transfer(data,identity,state,snapshot,entries,errors):
    manifest={'format':1,'files':entries,'errors':errors}
    atomic(snapshot/'.timekeeper-batch.json',json.dumps(manifest,separators=(',',':')).encode())
    command=['rsync','--recursive','--no-links','--no-perms','--no-owner','--no-group','--no-devices','--no-specials','--omit-dir-times',
             '--rsh',shlex.join(ssh_arguments(data,identity,state)),str(snapshot)+os.sep,data['username']+'@'+data['hostname']+':.']
    result=subprocess.run(command,capture_output=True,timeout=1000)
    if result.returncode:fail('Transfer was not acknowledged. Check device access, host trust and pending/conflicting copies in My account, then retry. Original files are unchanged.')


def sync(args):
    rsync_version();data,folder,identity,state=local_config(args)
    if not folder.is_dir():fail('The configured daily-note folder is unavailable.')
    if data['format']==2:return sync_bidirectional(data,folder,identity,state)
    sent=failed=0
    with tempfile.TemporaryDirectory(prefix='snapshot-',dir=state) as temporary:
        snapshot=Path(temporary);entries=[];errors=[]
        with os.scandir(folder) as directory:
            for entry in sorted(directory,key=lambda value:value.name):
                if not DATE_NAME.fullmatch(entry.name):continue
                try:content,error=snapshot_file(entry)
                except OSError:content,error=None,'LOCAL_FILE_UNREADABLE'
                if error:errors.append({'name':entry.name,'code':error});failed+=1
                else:
                    atomic(snapshot/entry.name,content);entries.append({'name':entry.name,'bytes':len(content),'sha256':hashlib.sha256(content).hexdigest()});sent+=1
                if len(entries)+len(errors)==BATCH_FILES:
                    transfer(data,identity,state,snapshot,entries,errors)
                    for child in snapshot.iterdir():child.unlink()
                    entries=[];errors=[]
        if entries or errors or not sent and not failed:transfer(data,identity,state,snapshot,entries,errors)
    print(json.dumps({'transferredFiles':sent,'localFileErrors':failed,'direction':'CLIENT_TO_SERVER'}))
    if failed:fail('Some local files were skipped. Review per-file errors in My account, correct the originals and sync again.')


def remote_json(data,identity,state,command,value=None):
    with tempfile.TemporaryFile() as output:
        result=subprocess.run(ssh_arguments(data,identity,state)+['-l',data['username'],data['hostname'],command],input=None if value is None else json.dumps(value,separators=(',',':')).encode(),stdout=output,stderr=subprocess.DEVNULL,timeout=60)
        if result.returncode:fail('The restricted journal exchange was not acknowledged. Keep the private state and retry after checking device access and host trust.')
        output.seek(0);raw=output.read(65537)
    try:
        require(len(raw)<=65536);reply=json.loads(raw);require(isinstance(reply,dict));return reply
    except (ValueError,TypeError):fail('The journal endpoint returned an invalid exchange response. Private state and local notes are kept.')


def exchange_rsync(data,identity,state,directory,exchange,download=False):
    remote=data['username']+'@'+data['hostname']+':'+('outgoing/' if download else 'incoming/')+exchange+'/'
    options=['rsync','--recursive','--no-links','--no-owner','--no-group','--no-devices','--no-specials','--omit-dir-times','--perms','--chmod=Du=rwx,Dgo=,Fu=rw,Fgo=','--rsh',shlex.join(ssh_arguments(data,identity,state))]
    operands=[remote,str(directory)+os.sep] if download else [str(directory)+os.sep,remote]
    result=subprocess.run(options+operands,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=1000)
    if result.returncode:fail('Journal transfer was interrupted. Keep the private state and retry; the same exchange will resume without replacing a newer local edit.')


def acknowledge(data,identity,state,replica,offer):
    replica.check_recoveries()
    reply=remote_json(data,identity,state,'timekeeper-journal-ack',{'offerId':offer['id'],'token':offer['token'],'exists':offer['targetExists'],'sha256':offer['targetHash']})
    if any(reply.get(key)!=offer[key] for key in ['id','token','filename','sequence','targetExists','targetHash']) or reply.get('state')!='ACKNOWLEDGED':fail('The acknowledgement does not match the applied snapshot. Private recovery state is kept.')
    replica.acknowledged(offer)


def exchange_names(manifest):return {entry['name'] for group in ['files','absent','errors'] for entry in manifest[group]}


def exchange_results(value,exchange,names):
    try:
        require(value['format']==2 and value['exchangeId']==exchange and type(value['pending']) is int and value['pending']==0)
        require(isinstance(value['offers'],list) and isinstance(value['errors'],list) and len(value['offers'])+len(value['errors'])<=50)
        found=set()
        for entry in value['offers']:
            name=note_name(entry['filename']);require(name not in found and name in names);found.add(name)
            require(str(uuid.UUID(entry['id']))==entry['id'] and entry['state'] in ['OFFERED','CONFLICT','ACKNOWLEDGED','DISCARDED'])
        for entry in value['errors']:
            name=file_name(entry['name']);require(name not in found and name in names);found.add(name);require(isinstance(entry['code'],str))
        require(found==names)
    except (KeyError,TypeError,ValueError):fail('Incomplete or invalid exchange results. Private snapshots and local notes are kept.')
    return value


def process_exchange(data,identity,state,replica,exchange,manifest):
    names=exchange_names(manifest);snapshot=replica.root/exchange
    exchange_rsync(data,identity,state,snapshot,exchange)
    value=exchange_results(remote_json(data,identity,state,'timekeeper-journal-results '+exchange),exchange,names)
    incoming=None
    with tempfile.TemporaryDirectory(prefix='outgoing-',dir=replica.root) as temporary:
        incoming=Path(temporary)
        if any(offer['state']=='OFFERED' for offer in value['offers']):
            exchange_rsync(data,identity,state,incoming,exchange,download=True)
            value=exchange_results(read_json(incoming/'.timekeeper-results.json',private=True),exchange,names)
            expected={offer['filename'] for offer in value['offers'] if offer['state']=='OFFERED' and offer['targetExists']}|{'.timekeeper-results.json'}
            if {path.name for path in incoming.iterdir()}!=expected:fail('Unexpected files in the private export. Local notes are unchanged.')
        pending=False
        for offer in value['offers']:
            if offer['state']=='CONFLICT':pending=True;continue
            if offer['state']=='DISCARDED':continue
            if offer['state']=='ACKNOWLEDGED':
                common=replica.bases().get(offer['filename'])
                if common is None or common['token']!=offer['token']:fail('A server acknowledgement has no matching local common state. Preserve this profile and register a new device instead of resetting its state.')
                continue
            content=None
            if offer['targetExists']:content=replica._backup(incoming/offer['filename'])
            replica.apply(offer,content);acknowledge(data,identity,state,replica,offer)
        if not pending:replica.finish_exchange(exchange)
    return pending,bool(value['errors'])


def sync_bidirectional(data,folder,identity,state):
    with Replica(state,folder,data['deviceId']) as replica:
        replica.check_recoveries()
        for operation in replica.pending():
            offer=replica.resume(operation);acknowledge(data,identity,state,replica,offer)
        blocked=set();conflicts=failed=0
        for exchange,manifest in replica.exchanges():
            blocked|=exchange_names(manifest)
            pending,errors=process_exchange(data,identity,state,replica,exchange,manifest);conflicts+=pending;failed+=errors
        names=set(replica.bases())
        with os.scandir(folder) as directory:
            for entry in directory:
                if DATE_NAME.fullmatch(entry.name):names.add(entry.name)
        after=None
        while True:
            page=remote_json(data,identity,state,'timekeeper-journal-inventory',{} if after is None else {'after':after})
            if not isinstance(page.get('names'),list) or len(page['names'])>50:fail('Invalid journal inventory; no new exchange was started.')
            previous=after
            for name in page['names']:
                note_name(name)
                if previous is not None and name<=previous:fail('Invalid journal inventory ordering; no new exchange was started.')
                previous=name;names.add(name)
            after=page.get('next')
            if after is None:break
            if not page['names'] or after!=page['names'][-1]:fail('Invalid journal inventory cursor; no new exchange was started.')
        available=sorted(names-blocked);transferred=0
        for offset in range(0,len(available),50):
            batch=available[offset:offset+50];exchange=str(uuid.uuid4());snapshot=replica.root/exchange;private_directory(snapshot)
            manifest={'format':2,'clientStateId':replica.client_id,'exchangeId':exchange,'files':[],'absent':[],'errors':[]};bases=replica.bases()
            for name in batch:
                token=bases.get(name,{}).get('token','')
                try:content=replica.read_current(name)
                except (Failure,OSError):manifest['errors'].append({'name':name,'code':'LOCAL_FILE_UNREADABLE'});failed+=1;continue
                if content is None:manifest['absent'].append({'name':name,'baseToken':token})
                else:
                    atomic(snapshot/name,content);manifest['files'].append({'name':name,'baseToken':token,'bytes':len(content),'sha256':replica.content_hash(content)})
            atomic(snapshot/'.timekeeper-batch.json',json.dumps(manifest,separators=(',',':')).encode());replica.save_exchange(exchange,manifest)
            pending,errors=process_exchange(data,identity,state,replica,exchange,manifest);conflicts+=pending;failed+=errors;transferred+=len(batch)
        print(json.dumps({'exchangedNotes':transferred,'pendingConflictExchanges':conflicts,'fileErrors':failed,'direction':'BIDIRECTIONAL'}))
        if failed or conflicts:fail('Some notes need review. Other notes were synchronized. Review My account conflicts or file errors and retry with the same profile.')


def recovery(args):
    data,folder,_,state=local_config(args)
    if data['format']!=2:fail('Local exchange recovery applies to bidirectional profiles.')
    with Replica(state,folder,data['deviceId']) as replica:
        if args.command=='recoveries':
            active={identifier for identifier,_ in replica.exchanges()}
            snapshots=[path.name for path in replica.root.iterdir() if path.is_dir() and re.fullmatch('[a-f0-9-]{36}',path.name) and path.name not in active]
            print(json.dumps({'recoveries':replica.recoveries(),'operations':[{'id':row['id'],'name':row['name'],'phase':row['phase']} for row in replica.pending()],'exchanges':[{'id':identifier,'notes':sorted(exchange_names(manifest))} for identifier,manifest in replica.exchanges()],'retainedSnapshotDirectories':snapshots,'privateDirectory':str(replica.root)},indent=2));return
        try:require(str(uuid.UUID(args.id))==args.id)
        except (TypeError,ValueError):fail('Use a recovery or exchange ID printed by recoveries.')
        if args.command=='abandon-exchange':
            exchange=next((value for identifier,value in replica.exchanges() if identifier==args.id),None)
            if exchange is None:fail('The exchange is not pending.')
            names=exchange_names(exchange)
            if any(row['name'] in names for row in replica.pending()):fail('Copy and review pending local recovery variants, then discard those recovery operations before abandoning their exchange.')
            with replica.database:replica.database.execute('DELETE FROM exchange WHERE id=?',(args.id,))
            print('Exchange abandoned without advancing common state. The original snapshot is retained privately at '+str(replica.root/args.id)+'. Discard the corresponding retained server copies in My account after reviewing them.');return
        row=next((value for value in replica.recoveries() if value['id']==args.id),None)
        operation=next((value for value in replica.pending() if value['id']==args.id),None)
        if row is None and operation is None:fail('No local recovery or operation has that ID.')
        if args.command=='recover':
            name=(operation['prepared'] if operation else args.id+'.new') if args.variant=='offered' else (row['backup'] if row else operation['backup'])
            source=replica.root/name
            private_file(source);before=source.lstat()
            if before.st_size>FILE_BYTES:fail('The recovery copy exceeds the supported file size. Review it directly in the private directory.')
            descriptor=os.open(source,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
            with os.fdopen(descriptor,'rb') as stream:content=stream.read(FILE_BYTES+1);after=os.fstat(stream.fileno())
            if len(content)>FILE_BYTES or (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns,before.st_ctime_ns)!=(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns,after.st_ctime_ns):fail('The recovery copy changed while being copied. Retry after the editor has finished saving.')
            output=args.output.expanduser().absolute()
            if output.parent.resolve().is_relative_to(folder.resolve()) or output.parent.resolve().is_relative_to(replica.root):fail('Choose a new recovery filename outside the daily-note folder and private sync state; review it before editing a canonical note.')
            descriptor=os.open(output,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
            with os.fdopen(descriptor,'wb') as target:target.write(content);target.flush();os.fsync(target.fileno())
            sync_directory(output.parent);print('Recovery copy saved privately. Original variants and synchronization state are kept.');return
        # Explicit disposal never acknowledges an offer or changes the common base.
        # Unlinking a restored hard link also makes the retained canonical copy regular again.
        names={args.id+'.old',args.id+'.new'}
        if row:names.add(row['backup'])
        for name in names:
            with contextlib.suppress(FileNotFoundError):(replica.root/name).unlink()
        with replica.database:
            replica.database.execute('DELETE FROM operation WHERE id=?',(args.id,));replica.database.execute('DELETE FROM recovery WHERE id=?',(args.id,))
        sync_directory(replica.root);print('Private recovery variants discarded. The current local note and common state were kept. If the interrupted exchange cannot resume, review and abandon it explicitly.')


def schedule(args):
    data,_,_,state=local_config(args);label='net.resheim.timekeeper.journal.'+data['deviceId']
    destination=(args.output_directory or state/'schedule').expanduser().absolute();private_directory(destination)
    command=[sys.executable,str(Path(__file__).resolve()),'sync','--profile',str(args.profile)]
    agent=os.environ.get('SSH_AUTH_SOCK')
    if not agent:fail('Load the key into a local SSH agent and export SSH_AUTH_SOCK before creating the schedule.')
    if args.platform=='macos' or args.platform=='auto' and sys.platform=='darwin':
        values={'Label':label,'ProgramArguments':command,'StartInterval':args.interval,'RunAtLoad':False,'EnvironmentVariables':{'SSH_AUTH_SOCK':agent,'PATH':os.environ.get('PATH',os.defpath)}}
        path=destination/(label+'.plist');atomic(path,plistlib.dumps(values))
        print('Install this private launchd plist with launchctl bootstrap gui/$(id -u). It refers only to local paths and the existing agent.')
    else:
        # systemd performs its own token expansion even inside quoted ExecStart arguments.
        quote=lambda text:'"'+text.replace('%','%%').replace('\\','\\\\').replace('"','\\"').replace('\n','\\n')+'"'
        unit='[Unit]\nDescription=Timekeeper daily journal synchronization\n[Service]\nType=oneshot\nExecStart='+ ' '.join(quote(value.replace('$','$$')) for value in command)+'\nEnvironment='+quote('SSH_AUTH_SOCK='+agent)+' '+quote('PATH='+os.environ.get('PATH',os.defpath))+'\nUMask=0077\n'
        atomic(destination/(label+'.service'),unit.encode())
        atomic(destination/(label+'.timer'),('[Unit]\nDescription=Timekeeper journal timer\n[Timer]\nOnBootSec=2min\nOnUnitActiveSec='+str(args.interval)+'s\n[Install]\nWantedBy=timers.target\n').encode())
        print('Install the private .service and .timer in ~/.config/systemd/user, then run systemctl --user daemon-reload and systemctl --user enable --now '+label+'.timer.')
    print('Schedule generated in the local state directory. Keep its SSH agent available and unlocked; no key or passphrase is included.')


def main():
    os.umask(0o077)
    parser=argparse.ArgumentParser(description=__doc__);commands=parser.add_subparsers(dest='command',required=True)
    for name,action in [('configure',configure),('trust',trust),('verify',verify),('sync',sync),('schedule',schedule),('recoveries',recovery),('recover',recovery),('discard-recovery',recovery),('abandon-exchange',recovery)]:
        command=commands.add_parser(name);command.add_argument('--profile',type=lambda value:Path(value).expanduser().absolute(),required=True);command.set_defaults(action=action)
        if name=='configure':command.add_argument('--folder');command.add_argument('--identity')
        if name=='trust':command.add_argument('--fingerprint',required=True);command.add_argument('--replace',action='store_true')
        if name in ['recover','discard-recovery','abandon-exchange']:command.add_argument('--id',required=True)
        if name=='recover':
            command.add_argument('--variant',choices=['original','offered'],default='original');command.add_argument('--output',type=Path,required=True,help='New private copy outside Daily; existing files are never replaced.')
        if name=='schedule':
            command.add_argument('--platform',choices=['auto','macos','linux'],default='auto');command.add_argument('--interval',type=int,default=300);command.add_argument('--output-directory',type=Path)
    args=parser.parse_args()
    try:
        if os.name!='posix' or sys.version_info<(3,11):fail('Use Python 3.11 or later on macOS, Linux or Windows through WSL2.')
        if args.command=='schedule' and not 60<=args.interval<=86400:fail('Use a schedule interval between 60 and 86400 seconds.')
        args.action(args);return 0
    except (Failure,OSError,ValueError,sqlite3.Error,subprocess.SubprocessError):
        exception=sys.exception();print(str(exception) if isinstance(exception,Failure) else 'Local operation failed. Check tool installation, private file permissions and the configured paths.',file=sys.stderr);return 1


if __name__=='__main__':raise SystemExit(main())
