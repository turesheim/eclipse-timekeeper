#!/usr/bin/env python3
"""Build a self-contained DSM candidate from a verified immutable server image."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

from documentation import render_document
from package_server import verify_build_identity
from validate_synology import validate
from synology_identity import PACKAGES, require_identity, application_id, package_guide

ROOT = Path(__file__).resolve().parents[2]
VERSION = {'preview': '1.99.0', 'stable': '2.0.0'}
GUIDES = {'docs/operations/SYNOLOGY-PACKAGE.md': 'README.md',
          'docs/operations/ACCOUNTS.md': 'ACCOUNTS.md',
          'docs/operations/ACCOUNT-DATA.md': 'ACCOUNT-DATA.md',
          'docs/operations/INSTANCE-BACKUP.md': 'INSTANCE-BACKUP.md',
          'docs/operations/API-DIAGNOSTICS.md': 'API-DIAGNOSTICS.md',
          'docs/operations/TOGGL-IMPORT.md': 'TOGGL-IMPORT.md',
          'docs/operations/TAILSCALE.md': 'TAILSCALE.md'}


def check_source(root, commit):
    if not re.fullmatch(r'[a-f0-9]{40}', commit):
        raise ValueError('Use the full source commit')
    head = subprocess.check_output(['git','rev-parse','HEAD'], cwd=root, text=True).strip()
    dirty = subprocess.check_output(['git','status','--porcelain'], cwd=root, text=True).strip()
    if head != commit or dirty:
        raise ValueError('Build the DSM package from the exact clean committed source')


def archive_tree(source, output, epoch, compressed=False):
    mode = 'w:gz' if compressed else 'w'
    with tarfile.open(output, mode) as archive:
        for path in sorted(source.rglob('*')):
            if path.is_symlink():
                raise ValueError('Unexpected package symlink')
            entry = archive.gettarinfo(path, arcname=path.relative_to(source).as_posix())
            entry.uid = entry.gid = 0
            entry.uname = entry.gname = 'root'
            entry.mtime = epoch
            if path.is_file():
                with path.open('rb') as stream:
                    archive.addfile(entry, stream)
            else:
                archive.addfile(entry)


def verify_image(image, commit, directory):
    expected = 'ghcr.io/turesheim/eclipse-timekeeper-server:sha-' + commit
    if image != expected:
        raise ValueError('Use the full immutable source-commit image tag')
    inspected = json.loads(subprocess.check_output(['docker','image','inspect',image], text=True))[0]
    if inspected['Architecture'] != 'amd64' or inspected['Os'] != 'linux':
        raise ValueError('The initial DS923+ package requires a linux/amd64 image')
    created = subprocess.check_output(['docker','create','--platform','linux/amd64','--network','none',image], text=True).strip()
    app = directory / 'app'
    app.mkdir()
    try:
        subprocess.run(['docker','cp',created+':/app/app',str(app)], check=True, capture_output=True)
        verify_build_identity(app, commit)
    finally:
        subprocess.run(['docker','rm',created], check=True, capture_output=True)
    return inspected['Id']


def build(output, commit, image, revision, channel, package_id="timekeeper"):
    require_identity(package_id)
    check_source(ROOT, commit)
    if channel not in VERSION or not 1 <= revision <= 2147483647:
        raise ValueError('Choose preview/stable and a positive package revision')
    if channel == 'stable':
        raise ValueError('Use promote_synology.py with real NAS acceptance; stable payloads are never rebuilt')
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    epoch = int(subprocess.check_output(['git','show','-s','--format=%ct',commit], cwd=ROOT, text=True))
    with tempfile.TemporaryDirectory(prefix='timekeeper-spk-') as temporary:
        directory = Path(temporary)
        verify_image(image, commit, directory)
        payload, outer = directory/'payload', directory/'spk'
        payload.mkdir();outer.mkdir()
        package = ROOT/'server/synology'
        shutil.copytree(package/'ui', payload/'ui')
        (payload/'project').mkdir()
        project=json.loads((package/'project/compose.json').read_text().replace('@TIMEKEEPER_IMAGE@',image))
        project['services']['server']['container_name']=package_id+'-server'
        for mount in project['services']['server']['volumes']:
            mount['source']=mount['source'].replace('/var/packages/timekeeper/','/var/packages/'+package_id+'/')
        (payload/'project/compose.yaml').write_text(json.dumps(project,indent=2)+'\n')
        launch=json.loads((payload/'ui/config').read_text())['.url']['net.resheim.timekeeper']
        launch['title']=PACKAGES[package_id]
        (payload/'ui/config').write_text(json.dumps({'.url':{application_id(package_id):launch}},indent=2)+'\n')
        (payload/'private').mkdir(mode=0o700)
        for name in ('config','secrets'):(payload/'private'/name).mkdir(mode=0o755)
        (outer/'scripts').mkdir()
        shutil.copytree(package/'conf', outer/'conf')
        privilege=json.loads((outer/'conf/privilege').read_text())
        privilege.update(username=package_id,groupname=package_id)
        (outer/'conf/privilege').write_text(json.dumps(privilege,indent=2)+'\n')
        resource=json.loads((outer/'conf/resource').read_text())
        resource['docker-project']['projects'][0]['name']=package_id+'-server'
        (outer/'conf/resource').write_text(json.dumps(resource,indent=2)+'\n')
        shutil.copytree(package/'WIZARD_UIFILES', outer/'WIZARD_UIFILES')
        for source in (package/'scripts').iterdir():
            if source.is_file():
                shutil.copyfile(source, outer/'scripts'/source.name)
                (outer/'scripts'/source.name).chmod(0o755)
        (outer/'scripts/migration').mkdir()
        for name in ('postgresql-shape.sql','schema-v2-postgresql.shape','schema-v3-postgresql.shape','schema-v4-postgresql.shape','schema-v5-postgresql.shape','schema-v6-postgresql.shape','schema-v7-postgresql.shape','schema-v8-postgresql.shape','schema-v9-postgresql.shape','recovery-v3.sql','passkeys-v4.sql','settings-v5.sql','locale-v6.sql','day-labels-v7.sql','day-note-history-v8.sql','instance-v9.sql'):
            shutil.copyfile(ROOT/'server/app/src/main/resources/db'/name,outer/'scripts/migration'/name)
        release = {'commit':commit,'image':image,'serverSchema':9}
        release_json = json.dumps(release, indent=2)+'\n'
        (outer/'scripts/release.json').write_text(release_json)
        # SYNOPKG_PKGINST_TEMP_DIR contains the extracted package.tgz payload,
        # not the SPK control scripts. Preinst/preupgrade read this incoming
        # identity before the installed package is replaced.
        (payload/'scripts').mkdir()
        (payload/'scripts/release.json').write_text(release_json)
        # The installed controller validates a schema upgrade before DSM moves
        # the new control scripts into place. Its incoming release and SQL must
        # therefore be available together in the staged package.tgz payload.
        shutil.copytree(outer/'scripts/migration', payload/'scripts/migration')
        environment = {**os.environ, 'GOOS':'linux', 'GOARCH':'amd64', 'GOAMD64':'v1', 'CGO_ENABLED':'0',
                       'GOCACHE':str(ROOT/'server/target/synology-go-cache')}
        subprocess.run(['go','build','-trimpath','-ldflags=-s -w -X main.packageID='+package_id,'-o',str(outer/'scripts/timekeeper-dsm'),'.'],
                       cwd=package/'installer', env=environment, check=True)
        (outer/'scripts/timekeeper-dsm').chmod(0o755)
        # Preloading the tested image makes the candidate installable without an
        # unpublished PR tag, Docker registry credentials, or a moving image tag.
        with (payload/'image.tar.gz').open('wb') as target:
            process = subprocess.Popen(['docker','image','save',image], stdout=subprocess.PIPE)
            try:
                with gzip.GzipFile(fileobj=target, mode='wb', mtime=0) as compressor:
                    shutil.copyfileobj(process.stdout, compressor)
            finally:
                process.stdout.close()
            if process.wait():
                raise ValueError('Unable to save the verified application image')
        for source,destination in GUIDES.items():
            (payload/destination).write_text(package_guide(render_document(ROOT, source, GUIDES, commit),package_id))
        shutil.copyfile(ROOT/'LICENSE', outer/'LICENSE')
        shutil.copyfile(package/'ui/images/timekeeper_64.png', outer/'PACKAGE_ICON.PNG')
        shutil.copyfile(package/'ui/images/timekeeper_256.png', outer/'PACKAGE_ICON_256.PNG')
        archive_tree(payload, outer/'package.tgz', epoch, compressed=True)
        checksum = hashlib.md5((outer/'package.tgz').read_bytes(), usedforsecurity=False).hexdigest()
        version = VERSION[channel]+'-'+str(revision)
        info = {'package':package_id, 'displayname':PACKAGES[package_id], 'version':version,
                'os_min_ver':'7.2.2-72806', 'arch':'r1000', 'maintainer':'Timekeeper contributors',
                'maintainer_url':'https://github.com/turesheim/eclipse-timekeeper',
                'description':'Private time tracking with a bundled web client and SynoCommunity PostgreSQL.',
                'thirdparty':'yes', 'startable':'yes', 'precheckstartstop':'yes', 'silent_install':'no', 'silent_upgrade':'no',
                'dsmuidir':'ui', 'dsmappname':application_id(package_id),
                'install_dep_packages':'ContainerManager:postgresql>=17.11-6',
                'checksum':checksum}
        (outer/'INFO').write_text(''.join(key+'='+json.dumps(value)+'\n' for key,value in info.items()))
        name=package_id+'-'+version+'-r1000.spk'
        destination=output/name
        archive_tree(outer,destination,epoch)
        # Docker's containerd store reports a manifest ID from image inspect;
        # the saved image's configuration digest is stable across image stores.
        checked=validate(destination)
        digest=checked['sha256']
        destination.with_suffix('.spk.sha256').write_text(digest+'  '+name+'\n')
        manifest={'format':1,'channel':channel,'package':package_id,'version':version,'commit':commit,
                  'architecture':'r1000','image':image,'imageId':checked['imageId'],'sha256':digest,
                  'serverSchema':9,'nativePostgreSQLMajor':17,'realDsmAcceptance':'required'}
        destination.with_suffix('.json').write_text(json.dumps(manifest,indent=2)+'\n')
        return destination


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--commit',required=True)
    parser.add_argument('--image',required=True)
    parser.add_argument('--revision',type=int,required=True)
    parser.add_argument('--channel',choices=VERSION,default='preview')
    parser.add_argument('--package-id',choices=PACKAGES,default='timekeeper',help='Build a separately installable destination package')
    args=parser.parse_args()
    print(build(args.output,args.commit,args.image,args.revision,args.channel,args.package_id))


if __name__=='__main__':
    main()
