#!/usr/bin/env python3
"""Validate DSM package anatomy, architecture and metadata before NAS testing."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import struct
import tarfile

from synology_acceptance import RECORD, content_digest, validate_record

REQUIRED = {'INFO','LICENSE','PACKAGE_ICON.PNG','PACKAGE_ICON_256.PNG','package.tgz',
            'conf/privilege','conf/resource','WIZARD_UIFILES/install_uifile.sh',
            'scripts/timekeeper-dsm','scripts/release.json',
            'scripts/migration/postgresql-shape.sql','scripts/migration/schema-v2-postgresql.shape',
            'scripts/migration/schema-v3-postgresql.shape','scripts/migration/schema-v4-postgresql.shape','scripts/migration/schema-v5-postgresql.shape','scripts/migration/schema-v6-postgresql.shape','scripts/migration/schema-v7-postgresql.shape','scripts/migration/schema-v8-postgresql.shape','scripts/migration/schema-v9-postgresql.shape',
            'scripts/migration/recovery-v3.sql','scripts/migration/passkeys-v4.sql','scripts/migration/settings-v5.sql','scripts/migration/locale-v6.sql','scripts/migration/day-labels-v7.sql','scripts/migration/day-note-history-v8.sql','scripts/migration/instance-v9.sql',
            *('scripts/'+name for name in ('preinst','postinst','preupgrade','postupgrade','preuninst','postuninst','start-stop-status'))}
MIGRATION_FILES = {name for name in REQUIRED if name.startswith('scripts/migration/')}


def safe_members(archive):
    names={}
    for member in archive.getmembers():
        path=PurePosixPath(member.name)
        if path.is_absolute() or '..' in path.parts or member.issym() or member.islnk() or not (member.isfile() or member.isdir()):
            raise ValueError('Unsafe DSM archive member')
        if member.name in names:
            raise ValueError('Duplicate DSM archive member')
        if member.mode & 0o6000:
            raise ValueError('Unexpected privileged package executable')
        names[member.name]=member
    return names


def png_size(value):
    if value[:8]!=b'\x89PNG\r\n\x1a\n' or value[12:16]!=b'IHDR':
        raise ValueError('Expected a PNG icon')
    return struct.unpack('>II',value[16:24])


def validate(path):
    path=Path(path)
    with tarfile.open(path,'r:*') as outer:
        members=safe_members(outer)
        if not REQUIRED.issubset(members):
            raise ValueError('Missing required DSM package files')
        def read(name):
            value=outer.extractfile(name)
            if value is None:raise ValueError('Expected regular package file')
            return value.read()
        info={}
        for line in read('INFO').decode().splitlines():
            key,raw=line.split('=',1)
            if key in info:raise ValueError('Duplicate INFO key')
            info[key]=json.loads(raw)
        if info.get('package')!='timekeeper' or info.get('arch')!='r1000' or info.get('os_min_ver')!='7.2.2-72806':
            raise ValueError('Incorrect target DSM identity')
        if info.get('install_type','')!='':
            raise ValueError('Use DSM default volume installation and permanent appdata')
        if not re.fullmatch(r'(1\.99\.0|2\.0\.0)-[1-9][0-9]*',info.get('version','')):
            raise ValueError('Invalid monotonic package version')
        if info.get('precheckstartstop')!='yes':
            raise ValueError('Package start must synchronize retained configuration before the worker starts')
        if info.get('dsmuidir')!='ui' or info.get('dsmappname')!='net.resheim.timekeeper':
            raise ValueError('Missing Package Center Open integration')
        if info.get('install_dep_packages')!='ContainerManager:postgresql>=17.11-6':
            raise ValueError('Incorrect native package dependencies')
        if png_size(read('PACKAGE_ICON.PNG'))!=(64,64) or png_size(read('PACKAGE_ICON_256.PNG'))!=(256,256):
            raise ValueError('Incorrect DSM icon dimensions')
        helper=read('scripts/timekeeper-dsm')
        if helper[:5]!=b'\x7fELF\x02' or struct.unpack('<H',helper[18:20])[0]!=62:
            raise ValueError('Expected a static Linux amd64 installer')
        for name in REQUIRED:
            if name.startswith('scripts/') and '/' not in name[len('scripts/'):] and name!='scripts/release.json' and not members[name].mode & 0o111:
                raise ValueError('Package controller is not executable')
        privilege=json.loads(read('conf/privilege'))
        if privilege!={'defaults':{'run-as':'package'},'username':'timekeeper','groupname':'timekeeper'}:
            raise ValueError('Installer must use the unprivileged package account')
        resource=json.loads(read('conf/resource'))
        if set(resource)!={'docker-project'}:
            raise ValueError('Retained state must use install-time FHS appdata, not a startup-time data-share')
        projects=resource['docker-project']
        if projects['preload-image']!='image.tar.gz' or projects['projects'][0]['path']!='project':
            raise ValueError('Incorrect Container Manager resource')
        release=json.loads(read('scripts/release.json'))
        if release['serverSchema']!=9 or not re.fullmatch('[a-f0-9]{40}',release['commit']) or release['image']!='ghcr.io/turesheim/eclipse-timekeeper-server:sha-'+release['commit']:
            raise ValueError('Unpinned package image identity')
        payload=read('package.tgz')
        if hashlib.md5(payload,usedforsecurity=False).hexdigest()!=info.get('checksum'):
            raise ValueError('DSM payload checksum mismatch')
        with tarfile.open(fileobj=io.BytesIO(payload),mode='r:gz') as inner:
            contents=safe_members(inner)
            if not {'ui/config','project/compose.yaml','image.tar.gz','README.md','ACCOUNTS.md','TAILSCALE.md','private','private/config','private/secrets','scripts/release.json'}.issubset(contents):
                raise ValueError('Incomplete payload/Open configuration')
            if json.load(inner.extractfile('scripts/release.json')) != release:
                raise ValueError('Incoming DSM payload release identity differs from the control scripts')
            if not MIGRATION_FILES.issubset(contents):
                raise ValueError('Missing staged DSM schema validation/migration files')
            for name in MIGRATION_FILES:
                staged=inner.extractfile(name)
                if staged is None or not (value:=staged.read()).strip() or value!=read(name):
                    raise ValueError('Staged DSM schema validation/migration files differ from the control scripts')
            if not contents['private'].isdir() or contents['private'].mode & 0o777 != 0o700:
                raise ValueError('Package runtime configuration needs a private parent directory')
            project=json.load(inner.extractfile('project/compose.yaml'))
            if set(project.get('services',{}))!={'server'} or project['services']['server'].get('image')!=release['image']:
                raise ValueError('Container Manager needs the complete pinned project before postinst')
            launch=json.load(inner.extractfile('ui/config'))
            if launch['.url']['net.resheim.timekeeper']['type']!='url':
                raise ValueError('Invalid DSM launcher')
            with tarfile.open(fileobj=io.BytesIO(inner.extractfile('image.tar.gz').read()),mode='r:gz') as image:
                image_members=safe_members(image)
                manifest=json.load(image.extractfile('manifest.json'))
                if len(manifest)!=1 or release['image'] not in manifest[0]['RepoTags']:
                    raise ValueError('Preloaded image differs from the pinned project image')
                config_bytes=image.extractfile(manifest[0]['Config']).read()
                image_id='sha256:'+hashlib.sha256(config_bytes).hexdigest()
                config=json.loads(config_bytes)
                if config['architecture']!='amd64' or config['os']!='linux':
                    raise ValueError('Incorrect image platform')
        checked={'package':info['package'],'version':info['version'],'sourceCommit':release['commit'],
                 'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'imageId':image_id,
                 'contentSha256':content_digest(outer),'realDsmAcceptance':'required'}
        if info['version'].startswith('2.0.0-'):
            if RECORD not in members:
                raise ValueError('Stable packages require recorded real DSM acceptance')
            validate_record(json.loads(read(RECORD)),checked)
            checked['realDsmAcceptance']='recorded'
        elif RECORD in members:
            raise ValueError('Keep the incomplete acceptance record outside the preview package')
    return checked


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package',type=Path)
    print(json.dumps(validate(parser.parse_args().package)))
