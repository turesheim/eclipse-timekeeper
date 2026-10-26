#!/usr/bin/python3 -I
"""Operator/container startup only. Creates restricted Unix identities; never invoked by HTTP."""
import grp
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import subprocess
import sys
from urllib.parse import urlsplit

def run(*args):subprocess.run(args,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

def write(path,content,mode,uid=0,gid=0):
    descriptor=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC|os.O_NOFOLLOW,mode)
    with os.fdopen(descriptor,'w') as stream:stream.write(content)
    os.chown(path,uid,gid);os.chmod(path,mode)

def main():
    if os.geteuid()!=0:raise ValueError('Operator provisioning requires root in the dedicated gateway container.')
    os.umask(0o077);source=Path('/run/secrets/journal-gateway.json')
    if source.is_symlink() or source.stat().st_uid!=0 or source.stat().st_mode&0o077:raise ValueError('Protect the root-owned operator secret with mode 600.')
    data=json.loads(source.read_text())
    if data['format']!=1 or not data.get('encryptedStorage') or not data.get('backupKeysSeparated'):raise ValueError('Storage protection prerequisites are required.')
    endpoint=urlsplit(data['brokerUrl'])
    if endpoint.scheme!='https' and not(endpoint.scheme=='http' and endpoint.hostname in ['localhost','127.0.0.1','::1']):raise ValueError('Use an HTTPS broker endpoint or loopback.')
    if endpoint.path!='/internal/journal' or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment:raise ValueError('Invalid broker endpoint.')
    try:grp.getgrnam('journal-auth')
    except KeyError:run('groupadd','--system','journal-auth')
    try:pwd.getpwnam('journal-auth')
    except KeyError:run('useradd','--system','--gid','journal-auth','--no-create-home','--shell','/usr/sbin/nologin','journal-auth')
    accounts=Path('/etc/timekeeper-journal/accounts');accounts.mkdir(parents=True,mode=0o711,exist_ok=True);os.chmod(accounts.parent,0o711);os.chmod(accounts,0o711)
    base=Path('/var/lib/timekeeper-journal');base.mkdir(parents=True,mode=0o711,exist_ok=True);os.chmod(base,0o711)
    spool=Path('/var/lib/timekeeper-journal/spool');spool.mkdir(parents=True,mode=0o711);os.chmod(spool,0o711)
    homes=Path('/var/lib/timekeeper-journal/homes');homes.mkdir(mode=0o711,exist_ok=True);os.chmod(homes,0o711)
    names=set();uids=set();tokens=set()
    for entry in data['accounts']:
        user=entry['username'];uid=entry['uid'];token=entry['token']
        if not re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}',user) or type(uid)is not int or not 20000<=uid<=60000 or not re.fullmatch(r'[A-Za-z0-9_-]{43,128}',token) or user in names or uid in uids or token in tokens:raise ValueError('Invalid or duplicate restricted account binding.')
        names.add(user);uids.add(uid);tokens.add(token)
        home=homes/user;home.mkdir(mode=0o755,exist_ok=True);os.chmod(home,0o755)
        try:
            if grp.getgrnam(user).gr_gid!=uid:raise ValueError('Existing group has another GID.')
        except KeyError:run('groupadd','--gid',str(uid),user)
        try:
            if pwd.getpwnam(user).pw_uid!=uid:raise ValueError('Existing user has another UID.')
        except KeyError:run('useradd','--uid',str(uid),'--gid',str(uid),'--home-dir',str(home),'--no-create-home','--shell','/bin/dash',user)
        # Unusable password (not a locked account): OpenSSH public-key authentication remains possible.
        run('usermod','--password','*',user)
        folder=spool/user;folder.mkdir(mode=0o700,exist_ok=True);os.chown(folder,uid,uid);os.chmod(folder,0o700)
        # Restarted sessions cannot resume old gateway stages; admitted data is already in the app DB.
        for child in folder.iterdir():
            if child.name.startswith('transfer-') and child.is_dir() and not child.is_symlink():shutil.rmtree(child)
        record={'username':user,'token':token,'brokerUrl':data['brokerUrl'],'spool':str(folder)}
        if data.get('caFile'):record['caFile']=data['caFile']
        write(accounts/(user+'.json'),json.dumps(record),0o440,gid=uid)
    authgid=grp.getgrnam('journal-auth').gr_gid
    write(accounts.parent/'gateway.json',json.dumps(data),0o440,gid=authgid)
    Path('/run/sshd').mkdir(mode=0o755,exist_ok=True)
    host=Path('/var/lib/timekeeper-journal/host');host.mkdir(mode=0o700,exist_ok=True);os.chmod(host,0o700)
    key=host/'ssh_host_ed25519_key'
    if not key.exists():run('ssh-keygen','-q','-t','ed25519','-N','','-f',str(key))
    os.chmod(key,0o600)
    config='''Port 2222
ListenAddress 0.0.0.0
HostKey /var/lib/timekeeper-journal/host/ssh_host_ed25519_key
PidFile /run/sshd/timekeeper.pid
PasswordAuthentication no
KbdInteractiveAuthentication no
AuthenticationMethods publickey
PermitRootLogin no
PermitEmptyPasswords no
UsePAM no
PermitTTY no
DisableForwarding yes
X11Forwarding no
PermitUserRC no
PermitUserEnvironment no
AuthorizedKeysFile none
AuthorizedKeysCommand /usr/local/libexec/timekeeper-journal-gateway authorize %u %t %k
AuthorizedKeysCommandUser journal-auth
MaxAuthTries 2
MaxSessions 2
LogLevel ERROR
'''
    if not names:raise ValueError('Provision at least one restricted account.')
    config+='AllowUsers '+' '.join(sorted(names))+'\n'
    write(Path('/etc/ssh/timekeeper-journal.conf'),config,0o600)
    run('/usr/sbin/sshd','-t','-f','/etc/ssh/timekeeper-journal.conf')
    os.execv('/usr/sbin/sshd',['/usr/sbin/sshd','-D','-e','-f','/etc/ssh/timekeeper-journal.conf'])

if __name__=='__main__':
    try:main()
    except Exception:print('Journal gateway provisioning failed. Check the protected operator configuration and encrypted storage mounts.',file=sys.stderr);raise SystemExit(1)
