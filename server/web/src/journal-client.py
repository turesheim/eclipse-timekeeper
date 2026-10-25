#!/usr/bin/env python3
"""Timekeeper one-way daily Markdown sync. Private keys are read only by local SSH."""
import argparse
import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shlex
import shutil
import stat
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


def profile(path):
    data=read_json(path)
    try:
        require(data['format']==1 and data['mechanism']=='RSYNC_SSH' and data['direction']=='CLIENT_TO_SERVER')
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
        reply=json.loads(result.stdout);require(reply['status']=='READY' and reply['direction']=='CLIENT_TO_SERVER')
    except (ValueError,KeyError,AssertionError):fail('The endpoint did not return the restricted journal verification response.')
    print('Restricted SSH identity verified. Direction: client to Timekeeper.')


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
    for name,action in [('configure',configure),('trust',trust),('verify',verify),('sync',sync),('schedule',schedule)]:
        command=commands.add_parser(name);command.add_argument('--profile',type=lambda value:Path(value).expanduser().absolute(),required=True);command.set_defaults(action=action)
        if name=='configure':command.add_argument('--folder');command.add_argument('--identity')
        if name=='trust':command.add_argument('--fingerprint',required=True);command.add_argument('--replace',action='store_true')
        if name=='schedule':
            command.add_argument('--platform',choices=['auto','macos','linux'],default='auto');command.add_argument('--interval',type=int,default=300);command.add_argument('--output-directory',type=Path)
    args=parser.parse_args()
    try:
        if os.name!='posix' or sys.version_info<(3,11):fail('Use Python 3.11 or later on macOS, Linux or Windows through WSL2.')
        if args.command=='schedule' and not 60<=args.interval<=86400:fail('Use a schedule interval between 60 and 86400 seconds.')
        args.action(args);return 0
    except (Failure,OSError,subprocess.SubprocessError):
        exception=sys.exception();print(str(exception) if isinstance(exception,Failure) else 'Local operation failed. Check tool installation, private file permissions and the configured paths.',file=sys.stderr);return 1


if __name__=='__main__':raise SystemExit(main())
