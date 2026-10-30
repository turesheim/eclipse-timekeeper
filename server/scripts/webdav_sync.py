#!/usr/bin/env python3
"""Preview and manually synchronize a POSIX folder using guarded standard WebDAV."""
import argparse
import base64
import fcntl
import getpass
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import stat
import sys
import unicodedata
from urllib.error import HTTPError, URLError
from urllib.parse import quote, unquote, urljoin, urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, HTTPSHandler
import xml.etree.ElementTree as ET

VERSION='1.0'
DAV='{DAV:}'

class SyncError(Exception):pass
def digest(data):return hashlib.sha256(data).hexdigest()
def encoded(value):return '/'.join(quote(part,safe='') for part in value.split('/'))
def key(value):return unicodedata.normalize('NFC',value.upper().lower())
def beneath(value,parent):return value==parent or value.startswith(parent+'/')
def portable(value):
    value=unicodedata.normalize('NFC',value)
    if not value or len(value.encode('utf-8'))>1024 or len(value.split('/'))>32:raise SyncError('Invalid or excessive collection path')
    for part in value.split('/'):
        stem=part.split('.')[0].rstrip(' ').upper()
        if not part or part in ('.','..') or part[-1] in ' .' or len(part.encode('utf-8'))>255 or re.search(r'[<>:"\\|?*\x00-\x1f\x7f]',part) or stem in ('CON','PRN','AUX','NUL','CONIN$','CONOUT$') or re.fullmatch(r'(COM|LPT)[1-9¹²³]',stem):raise SyncError('Nonportable collection path; rename it before synchronization')
    return value
def ignored(path,include_config):return not include_config and any(key(part)=='.obsidian' for part in path.split('/'))
def inside(path,parent):return path==parent or parent in path.parents

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):raise SyncError('WebDAV redirects are refused; use the final trusted HTTPS collection URL')

class Remote:
    def __init__(self,url,username,token,ca=None,allow_http=False,maximum=16_000_000,include_config=False):
        value=urlsplit(url)
        if value.username or value.password or value.query or value.fragment or not value.hostname or not value.path.startswith('/'):raise SyncError('Use a collection URL without credentials, query or fragment')
        if value.scheme!='https' and not (allow_http and value.scheme=='http' and value.hostname in ('127.0.0.1','::1')):raise SyncError('Verified HTTPS is required; plaintext loopback requires an explicit test-only option')
        self.url=url.rstrip('/')+'/';self.authority=(value.scheme,value.netloc);self.root=self.parts(value.path);self.maximum=maximum;self.include_config=include_config
        self.owner=None;self.owner_checked=False
        self.opener=build_opener(NoRedirect(),HTTPSHandler(context=ssl.create_default_context(cafile=ca)))
        self.authorization='Basic '+base64.b64encode((username+':'+token).encode()).decode()
    @staticmethod
    def parts(path):
        result=[]
        for part in path.split('/'):
            if not part:continue
            try:value=unquote(part,encoding='utf-8',errors='strict')
            except UnicodeError:raise SyncError('Invalid UTF-8 WebDAV path')
            if '/' in value or '\\' in value or value in ('.','..'):raise SyncError('Unsafe WebDAV response path')
            result.append(unicodedata.normalize('NFC',value))
        return result
    def request(self,method,path='',data=None,headers=None,maximum=None):
        request=Request(self.url+encoded(path),data=data,method=method,headers={'Authorization':self.authorization,'User-Agent':'webdav-manual-sync/'+VERSION,**({'Content-Type':'application/octet-stream'} if data is not None else {}),**(headers or {})})
        try:
            with self.opener.open(request,timeout=60) as response:
                owner=response.headers.get('X-Timekeeper-Owner')
                if self.owner_checked and owner!=self.owner:raise SyncError('The authenticated account changed; synchronization stopped')
                self.owner,self.owner_checked=owner,True
                limit=self.maximum if maximum is None else maximum
                body=response.read(limit+1)
                if len(body)>limit:raise SyncError('WebDAV response exceeds the configured client limit')
                declared=response.headers.get('Content-Length')
                if declared is not None and (not declared.isdecimal() or int(declared)!=len(body)):raise SyncError('Incomplete WebDAV response; synchronization stopped')
                return response.status,{name.lower():value for name,value in response.headers.items()},body
        except HTTPError as failure:
            code=failure.code;failure.close();raise SyncError('WebDAV '+method+' was rejected (HTTP '+str(code)+'); no unguarded retry is performed') from None
        except (URLError,TimeoutError,OSError,http.client.HTTPException):raise SyncError('WebDAV transport failed; no deletion or empty-list inference is made') from None
    def list(self,path='',depth='infinity'):
        requested=b'<D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/><D:getetag/><D:getcontentlength/><D:resource-id/></D:prop></D:propfind>'
        status,headers,body=self.request('PROPFIND',path,data=requested,headers={'Depth':depth,'Content-Type':'application/xml'},maximum=32_000_000)
        if status!=207:raise SyncError('A complete WebDAV multistatus is required')
        if b'<!DOCTYPE' in body.upper() or b'<!ENTITY' in body.upper():raise SyncError('WebDAV listings must not contain XML entity declarations')
        try:document=ET.fromstring(body)
        except ET.ParseError:raise SyncError('Incomplete or invalid WebDAV listing; synchronization stopped') from None
        if document.tag!=DAV+'multistatus':raise SyncError('Invalid WebDAV multistatus document')
        rows={};root=None;keys=set()
        for response in document.findall(DAV+'response'):
            href=response.findtext(DAV+'href');value=urlsplit(urljoin(self.url,href or ''))
            if not href or (value.scheme,value.netloc)!=self.authority or value.query or value.fragment:raise SyncError('Foreign or malformed WebDAV response path')
            parts=self.parts(value.path)
            if parts[:len(self.root)]!=self.root:raise SyncError('WebDAV response escaped the selected collection')
            relative='/'.join(parts[len(self.root):]);props=None
            for propstat in response.findall(DAV+'propstat'):
                if propstat.findtext(DAV+'status')=='HTTP/1.1 200 OK':props=propstat.find(DAV+'prop');break
            if props is None:raise SyncError('A WebDAV entry could not be read; the scan is incomplete')
            folder=props.find(DAV+'resourcetype/'+DAV+'collection') is not None;tag=props.findtext(DAV+'getetag')
            if not tag or not re.fullmatch(r'"[^"\r\n]+"',tag):raise SyncError('Strong ETags are required for every WebDAV entry')
            record={'kind':'directory' if folder else 'file','etag':tag}
            identity=props.findtext(DAV+'resource-id/'+DAV+'href')
            if identity is not None:
                if not re.fullmatch(r'urn:uuid:[a-fA-F0-9-]{36}',identity):raise SyncError('Unsupported DAV resource identity format')
                record['resourceId']=identity
            if folder and identity is None:raise SyncError('Stable DAV:resource-id values are required for folder synchronization')
            if relative==path:
                if root is not None or not folder:raise SyncError('Invalid selected WebDAV root')
                root=record
            elif relative:
                relative=portable(relative)
                if path and not beneath(relative,path):raise SyncError('WebDAV listing escaped the requested folder')
                if ignored(relative,self.include_config):continue
                if relative in rows or key(relative) in keys:raise SyncError('Review a case or Unicode collision before synchronizing')
                rows[relative]=record;keys.add(key(relative))
                if len(rows)>10000:raise SyncError('Collection exceeds this manual helper’s 10,000-entry limit')
        if root is None:raise SyncError('The selected WebDAV root is missing; refusing an empty scan')
        if depth=='infinity':
            for relative in rows:
                parent=relative.rpartition('/')[0]
                while parent and parent!=path:
                    if rows.get(parent,{}).get('kind')!='directory':raise SyncError('The WebDAV folder hierarchy is incomplete')
                    parent=parent.rpartition('/')[0]
        return root,rows
    def read(self,path,tag):
        status,headers,body=self.request('GET',path,headers={'If-Match':tag})
        if status!=200 or headers.get('etag')!=tag:raise SyncError('Remote content changed during the scan')
        return body
    def scan(self,baseline,expected_root=None):
        root,rows=self.list()
        if expected_root is not None and root['resourceId']!=expected_root:raise SyncError('The remote collection was replaced; preserve this baseline and review a fresh synchronization profile')
        for path,row in rows.items():
            if row['kind']=='file':
                previous=baseline.get(path,{})
                row['sha256']=previous['sha256'] if previous.get('etag')==row['etag'] and previous.get('sha256') else digest(self.read(path,row['etag']))
        return root,rows
    def delete_directory(self,path,expected_identity):
        root,rows=self.list(path,'1')
        if root['resourceId']!=expected_identity:raise SyncError('A remote directory was recreated; refusing deletion of its replacement')
        if rows:raise SyncError('A remote directory gained content; refusing recursive deletion')
        self.request('DELETE',path,headers={'If-Match':root['etag']})

class Local:
    def __init__(self,folder,maximum=16_000_000,include_config=False):
        path=Path(folder).absolute()
        if path.is_symlink() or not path.is_dir():raise SyncError('The selected local folder is unavailable or is a symlink')
        self.path=path.resolve();self.maximum=maximum;self.include_config=include_config
        self.fd=os.open(self.path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW);value=os.fstat(self.fd);self.identity=[value.st_dev,value.st_ino]
    def close(self):os.close(self.fd)
    def check(self):
        try:value=self.path.stat(follow_symlinks=False)
        except OSError:raise SyncError('The local root disappeared; refusing deletion inference') from None
        if [value.st_dev,value.st_ino]!=self.identity or not stat.S_ISDIR(value.st_mode):raise SyncError('The local root was replaced or unmounted; synchronization stopped')
    def parent(self,path):
        self.check();parts=path.split('/');fd=os.dup(self.fd)
        try:
            for part in parts[:-1]:
                next_fd=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd);os.close(fd);fd=next_fd
            return fd,parts[-1]
        except BaseException:os.close(fd);raise
    def read(self,path):
        parent,name=self.parent(path)
        try:
            fd=os.open(name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=parent)
            with os.fdopen(fd,'rb') as source:
                if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):raise SyncError('Local special files are not supported')
                data=source.read(self.maximum+1)
                if len(data)>self.maximum:raise SyncError('A local file exceeds the configured client size limit')
                return data
        finally:os.close(parent)
    def scan(self):
        self.check();rows={};keys=set()
        def visit(fd,prefix):
            with os.scandir(fd) as entries:
                for entry in entries:
                    disk=(prefix+'/' if prefix else '')+entry.name;path=portable(disk)
                    if ignored(path,self.include_config):continue
                    if key(path) in keys:raise SyncError('Review a local case or Unicode collision before synchronizing')
                    keys.add(key(path));value=entry.stat(follow_symlinks=False)
                    if stat.S_ISLNK(value.st_mode):raise SyncError('Symlinks are refused; no paths outside the selected folder are followed')
                    if stat.S_ISDIR(value.st_mode):
                        rows[path]={'kind':'directory','disk':disk};child=os.open(entry.name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
                        try:visit(child,disk)
                        finally:os.close(child)
                    elif stat.S_ISREG(value.st_mode):rows[path]={'kind':'file','disk':disk,'sha256':digest(self.read(disk))}
                    else:raise SyncError('Local special files are not supported')
                    if len(rows)>10000:raise SyncError('Collection exceeds this manual helper’s 10,000-entry limit')
        visit(self.fd,'');self.check();return rows
    def mkdir(self,path):
        parent,name=self.parent(path)
        try:os.mkdir(name,mode=0o700,dir_fd=parent)
        finally:os.close(parent)
    def capture(self,path,expected,control):
        parent,name=self.parent(path);backup=secrets.token_hex(16)+'.bin'
        try:
            control.record_backup(backup,path,expected)
            os.rename(name,backup,src_dir_fd=parent,dst_dir_fd=control.backup_fd)
            fd=os.open(backup,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=control.backup_fd)
            with os.fdopen(fd,'rb') as original:
                if not stat.S_ISREG(os.fstat(original.fileno()).st_mode) or digest(original.read(self.maximum+1))!=expected:raise SyncError('A local file changed during replacement; both the current file and its backup are retained for review')
                os.fsync(original.fileno())
            os.fsync(parent);os.fsync(control.backup_fd)
            return backup
        finally:os.close(parent)
    def install(self,path,data,expected,control):
        if expected is not None:self.capture(path,expected,control)
        parent,name=self.parent(path);temporary='.webdav-sync-'+secrets.token_hex(16)
        try:
            fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=parent)
            with os.fdopen(fd,'wb') as target:target.write(data);target.flush();os.fsync(target.fileno())
            # link() fails if an editor recreated the destination after capture.
            # os.replace() here could silently overwrite that concurrent edit.
            os.link(temporary,name,src_dir_fd=parent,dst_dir_fd=parent,follow_symlinks=False)
            os.fsync(parent)
        finally:
            try:os.unlink(temporary,dir_fd=parent)
            except FileNotFoundError:pass
            os.close(parent)
    def rmdir(self,path):
        parent,name=self.parent(path)
        try:os.rmdir(name,dir_fd=parent)
        finally:os.close(parent)

class Control:
    def __init__(self,path,local):
        self.path=Path(path).absolute().resolve();self.local=local;self.backup_fd=None;self.lock_fd=None
        if inside(self.path,local.path):raise SyncError('Keep the private state directory outside the synchronized folder, on the same filesystem')
        self.file=self.path/'state.json'
        try:
            self.open()
            if self.file.is_symlink():raise SyncError('The sync state must not be a symlink')
            if self.file.exists() and self.file.stat().st_size>32_000_000:raise SyncError('The synchronization baseline is too large; preserve it for review')
            try:self.state=json.loads(self.file.read_text()) if self.file.exists() else {'format':1,'entries':{}}
            except (OSError,ValueError):raise SyncError('The sync baseline is unreadable; preserve it and review before proceeding') from None
            if self.state.get('format')!=1 or not isinstance(self.state.get('entries'),dict):raise SyncError('Unsupported synchronization baseline')
        except BaseException:self.close();raise
    def open(self):
        if self.lock_fd is not None:return
        if not self.path.exists():self.path.mkdir(mode=0o700,parents=True)
        value=self.path.stat()
        if value.st_dev!=self.local.identity[0] or value.st_uid!=os.getuid() or value.st_mode&0o077:raise SyncError('The state directory must be private (0700), owned by you, and on the local vault filesystem')
        marker=self.path/'format'
        if marker.is_symlink():raise SyncError('Invalid private synchronization state directory')
        if not marker.exists():
            if any(self.path.iterdir()):raise SyncError('The chosen state directory is not an empty private directory')
            descriptor=os.open(marker,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
            with os.fdopen(descriptor,'w') as output:output.write('webdav-manual-sync-state-v1\n');output.flush();os.fsync(output.fileno())
        if marker.read_text()!='webdav-manual-sync-state-v1\n':raise SyncError('Invalid private synchronization state directory')
        self.lock_fd=os.open(self.path/'lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
        try:fcntl.flock(self.lock_fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:os.close(self.lock_fd);self.lock_fd=None;raise SyncError('Another manual sync is using this baseline; wait for it to finish') from None
        backup=self.path/'backups'
        if backup.is_symlink():raise SyncError('The backup directory must not be a symlink')
        backup.mkdir(mode=0o700,exist_ok=True);self.backup_fd=os.open(backup,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    def close(self):
        if self.backup_fd is not None:os.close(self.backup_fd);self.backup_fd=None
        if self.lock_fd is not None:os.close(self.lock_fd);self.lock_fd=None
    def save(self):
        temporary=self.path/('.state-'+secrets.token_hex(16))
        fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        try:
            with os.fdopen(fd,'w') as target:json.dump(self.state,target,ensure_ascii=False,sort_keys=True);target.flush();os.fsync(target.fileno())
            os.replace(temporary,self.file)
            descriptor=os.open(self.path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:os.fsync(descriptor)
            finally:os.close(descriptor)
        finally:temporary.unlink(missing_ok=True)
    def record_backup(self,name,path,sha):
        self.state.setdefault('backups',[]).append({'file':name,'path':path,'sha256':sha});self.save()

def plan(local,remote,baseline,prefer_local=(),prefer_remote=()):
    actions=[];conflicts=[]
    for path in sorted(set(local)|set(remote)|set(baseline)):
        left=local.get(path);right=remote.get(path);previous=baseline.get(path)
        if left and right and left['kind']!=right['kind']:conflicts.append({'path':path,'reason':'file/directory collision'});continue
        kind=(left or right or previous)['kind']
        if kind=='directory':
            if bool(left)==bool(right):continue
            if previous and right and previous.get('resourceId') and previous['resourceId']!=right.get('resourceId'):conflicts.append({'path':path,'reason':'the remote folder was recreated; review its identity before deleting it'});continue
            present=local if left else remote
            changed=previous and any(not baseline.get(child) or row.get('sha256')!=baseline[child].get('sha256') for child,row in present.items() if child!=path and beneath(child,path))
            if changed:conflicts.append({'path':path,'reason':'folder deleted on one side and edited on the other'});continue
            operation=('delete-local-directory' if left else 'delete-remote-directory') if previous else ('mkdir-remote' if left else 'mkdir-local')
        else:
            lh=left.get('sha256') if left else None;rh=right.get('sha256') if right else None;old=previous.get('sha256') if previous else None
            if lh==rh:continue
            if path in prefer_local:operation='push' if left else 'delete-remote'
            elif path in prefer_remote:operation='pull' if right else 'delete-local'
            elif left and right:
                if old==rh:operation='push'
                elif old==lh:operation='pull'
                else:conflicts.append({'path':path,'reason':'both versions differ; review and explicitly choose a version'});continue
            elif left:operation='delete-local' if previous and lh==old else 'push' if not previous else None
            else:operation='delete-remote' if previous and rh==old else 'pull' if not previous else None
            if operation is None:conflicts.append({'path':path,'reason':'deletion conflicts with an edit'});continue
        action={'operation':operation,'path':path}
        if operation=='delete-remote-directory':action['resourceId']=right['resourceId']
        if left and kind=='file':action['localSha256']=left['sha256']
        if right and kind=='file':action['remoteEtag']=right['etag'];action['remoteSha256']=right['sha256']
        actions.append(action)
    # A unique unchanged-content local rename can use real guarded MOVE, retaining
    # the remote document ID. Ambiguous equal-content files remain explicit ops.
    removed=[a for a in actions if a['operation']=='delete-remote'];created=[a for a in actions if a['operation']=='push' and 'remoteEtag' not in a]
    for old in removed:
        matches=[new for new in created if new['localSha256']==old['remoteSha256']]
        if len(matches)==1 and sum(item['remoteSha256']==old['remoteSha256'] for item in removed)==1:
            new=matches[0];actions.remove(old);actions.remove(new);created.remove(new);actions.append({'operation':'move-remote','path':old['path'],'destination':new['path'],'remoteEtag':old['remoteEtag'],'remoteSha256':old['remoteSha256']})
    return actions,conflicts

def execute(action,local,remote,rows,control):
    operation=action['operation'];path=action['path'];disk=rows.get(path,{}).get('disk',path)
    if path not in rows:
        parts=path.split('/')
        for length in range(len(parts)-1,0,-1):
            parent=rows.get('/'.join(parts[:length]))
            if parent is not None:disk=parent['disk']+'/'+('/'.join(parts[length:]));break
    if operation=='mkdir-local':local.mkdir(disk)
    elif operation=='mkdir-remote':remote.request('MKCOL',path)
    elif operation=='push':
        data=local.read(disk)
        if digest(data)!=action['localSha256']:raise SyncError('Local content changed after review')
        remote.request('PUT',path,data,{'If-Match':action['remoteEtag']} if action.get('remoteEtag') else {'If-None-Match':'*'})
    elif operation=='pull':local.install(disk,remote.read(path,action['remoteEtag']),action.get('localSha256'),control)
    elif operation=='delete-local':local.capture(disk,action['localSha256'],control)
    elif operation=='delete-remote':remote.request('DELETE',path,headers={'If-Match':action['remoteEtag']})
    elif operation=='delete-local-directory':local.rmdir(disk)
    elif operation=='delete-remote-directory':remote.delete_directory(path,action['resourceId'])
    elif operation=='move-remote':remote.request('MOVE',path,headers={'If-Match':action['remoteEtag'],'Overwrite':'F','Destination':remote.url+encoded(action['destination'])})
    else:raise SyncError('Unknown synchronization operation')

def synchronize(args,token):
    local=Local(args.folder,args.max_file_bytes,args.include_obsidian);control=None
    try:
        control=Control(args.state_dir,local)
        remote=Remote(args.url,args.username,token,args.ca_file,args.allow_loopback_http,args.max_file_bytes,args.include_obsidian)
        if 'binding' in control.state:remote.owner=control.state['binding']['owner'];remote.owner_checked=True
        baseline=control.state['entries'];local_rows=local.scan();root,remote_rows=remote.scan(baseline,control.state.get('rootResourceId'));scan_config={'includeObsidian':args.include_obsidian}
        if baseline and not control.state.get('rootResourceId'):raise SyncError('This baseline predates stable collection identity; preserve it and review a fresh synchronization profile')
        if control.state.get('scanConfig')==scan_config and control.state.get('rootEtag')==root['etag'] and set(baseline)!=set(remote_rows):raise SyncError('WebDAV membership changed without a new namespace version; refusing deletion inference')
        binding={'url':remote.url,'owner':remote.owner,'username':args.username,'localRoot':str(local.path),'localIdentity':local.identity}
        if control.state.get('binding',binding)!=binding:raise SyncError('The account, collection URL or local folder identity changed; use a separately reviewed fresh state directory')
        names={}
        for path in set(local_rows)|set(remote_rows):
            if key(path) in names and names[key(path)]!=path:raise SyncError('Review a cross-client case or Unicode collision before synchronizing')
            names[key(path)]=path
        actions,conflicts=plan(local_rows,remote_rows,baseline,args.prefer_local,args.prefer_remote)
        previous_deletes=[a for a in control.state.get('pending',[]) if a['operation'].startswith('delete-')]
        if control.state.get('pending'):
            for action in actions:
                if action['operation'].startswith('delete-') and action not in previous_deletes:conflicts.append({'path':action['path'],'reason':'an interrupted operation requires backup review before inferring a new deletion'})
        review={'binding':binding,'scanConfig':scan_config,'local':local_rows,'remote':remote_rows,'root':root,'actions':actions,'baseline':baseline,'allowDeletes':args.allow_deletes,'pending':control.state.get('pending')}
        code=digest(json.dumps(review,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode())
        result={'clientVersion':VERSION,'review':code,'actions':actions,'conflicts':conflicts,'deletionApprovalRequired':any(a['operation'].startswith('delete-') for a in actions)}
        if not args.apply:return result
        if args.apply!=code:raise SyncError('The reviewed local or remote snapshot changed; preview again')
        if conflicts:raise SyncError('Resolve the reported conflicts before applying any operation')
        if result['deletionApprovalRequired'] and not args.allow_deletes:raise SyncError('Deletions need an explicit --allow-deletes preview and matching review code')
        if control.state.get('pending') and not args.resume:raise SyncError('A previous operation was interrupted; review retained backups and use --resume with a fresh preview')
        control.open();control.state.update(binding=binding,pending=actions,rootResourceId=root['resourceId']);control.save()
        order={'mkdir-local':0,'mkdir-remote':0,'move-remote':1,'push':2,'pull':2,'delete-local':3,'delete-remote':3,'delete-local-directory':4,'delete-remote-directory':4}
        for action in sorted(actions,key=lambda a:(order[a['operation']],-a['path'].count('/') if a['operation'].endswith('directory') else a['path'].count('/'),a['path'])):execute(action,local,remote,local_rows,control)
        after_local=local.scan();after_root,after_remote=remote.scan({},root['resourceId'])
        if set(after_local)!=set(after_remote) or any(after_local[path]['kind']!=row['kind'] or row['kind']=='file' and after_local[path]['sha256']!=row['sha256'] for path,row in after_remote.items()):raise SyncError('Content changed during synchronization; retain the interrupted baseline and review again')
        control.state.update(entries=after_remote,pending=[],rootEtag=after_root['etag'],scanConfig=scan_config);control.save();result['applied']=True;return result
    finally:
        if control is not None:control.close()
        local.close()

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--version',action='version',version=VERSION)
    parser.add_argument('--url',required=True);parser.add_argument('--folder',required=True);parser.add_argument('--state-dir',required=True);parser.add_argument('--username',default='webdav');parser.add_argument('--ca-file')
    parser.add_argument('--apply',metavar='REVIEW_SHA256');parser.add_argument('--allow-deletes',action='store_true');parser.add_argument('--resume',action='store_true');parser.add_argument('--include-obsidian',action='store_true');parser.add_argument('--allow-loopback-http',action='store_true')
    parser.add_argument('--max-file-bytes',type=int,default=16_000_000);parser.add_argument('--prefer-local',action='append',default=[]);parser.add_argument('--prefer-remote',action='append',default=[])
    args=parser.parse_args()
    if args.max_file_bytes<1 or args.max_file_bytes>1_000_000_000:parser.error('Choose a positive bounded file-size limit')
    try:
        for path in args.prefer_local+args.prefer_remote:portable(path)
        if set(args.prefer_local)&set(args.prefer_remote):raise SyncError('Choose only one version of each conflicting path')
        token=os.environ.get('WEBDAV_TOKEN') or getpass.getpass('WebDAV API token: ')
        if not token or ':' in args.username or any(ord(c)<32 for c in args.username):raise SyncError('Invalid WebDAV credentials')
        print(json.dumps(synchronize(args,token),ensure_ascii=False,indent=2))
    except KeyboardInterrupt:
        print('Interrupted; preserve the baseline and retained backups, then preview again',file=sys.stderr);return 130
    except Exception as error:
        # Filesystem/transport exceptions can contain headers, private URLs or
        # local paths. Only fixed client-generated messages leave this boundary.
        print(str(error) if isinstance(error,SyncError) else 'Synchronization stopped; preserve the baseline and retained backups for review',file=sys.stderr);return 1
    return 0

if __name__=='__main__':raise SystemExit(main())
