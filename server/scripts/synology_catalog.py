#!/usr/bin/env python3
"""Generate DSM discovery metadata beside a verified, self-contained SPK.

The per-architecture catalog is a candidate for real Package Center discovery
acceptance. Generating it does not publish a feed or attest NAS compatibility.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile
from urllib.parse import urlsplit
from synology_acceptance import RECORD
from validate_synology import validate
from synology_identity import PACKAGES, require_identity


def entry(version,base_url,size,md5,package_url=None,package_id="timekeeper"):
    require_identity(package_id)
    if not re.fullmatch(r'(1\.99\.0|2\.0\.0)-[1-9][0-9]*',version):
        raise ValueError('Use a preview or stable DSM package version')
    channel='preview' if version.startswith('1.99.0-') else 'stable'
    url=urlsplit(base_url)
    if url.scheme!='https' or not url.netloc or url.username or url.password or url.query or url.fragment or not url.path.endswith('/'+channel+'/r1000/'):
        raise ValueError('Use a public HTTPS '+channel+'/r1000/ catalog directory without credentials, query or fragment')
    filename=package_id+'-'+version+'-r1000.spk'
    if package_url is not None:
        download=urlsplit(package_url)
        if (channel!='stable' or download.scheme!='https' or not download.netloc or download.username or
            download.password or download.query or download.fragment or not download.path.endswith('/'+filename)):
            raise ValueError('Only stable catalogs may link to an immutable HTTPS release SPK')
    return {'package':package_id,'version':version,'dname':PACKAGES[package_id]+(' (preview)' if channel=='preview' else ''),
            'desc':PACKAGES[package_id]+' for DS923+ / r1000. Requires Container Manager and SynoCommunity PostgreSQL 17.',
            'link':package_url or base_url+filename,
            'thumbnail':[base_url+package_id+'-64.png'],
            'thumbnail_retina':[base_url+package_id+'-256.png']*2,
            'qinst':False,'qupgrade':False,'qstart':False,'startable':'yes',
            'deppkgs':'ContainerManager:postgresql>=17.11-6','conflictpkgs':None,
            'snapshot':[],'maintainer':'Timekeeper contributors',
            'maintainer_url':'https://github.com/turesheim/eclipse-timekeeper',
            'md5':md5,'size':size}


def build(package,base_url,package_url=None):
    package=Path(package).resolve()
    checked=validate(package)
    provenance=json.loads(package.with_suffix('.json').read_text())
    package_id=require_identity(checked['package'])
    expected_name=package_id+'-'+checked['version']+'-r1000.spk'
    channel='preview' if checked['version'].startswith('1.99.0-') else 'stable'
    if package.name!=expected_name or provenance.get('package')!=package_id or provenance.get('channel')!=channel or provenance.get('commit')!=checked['sourceCommit'] or provenance.get('sha256')!=checked['sha256'] or provenance.get('imageId')!=checked['imageId']:
        raise ValueError('Catalog must match the verified package provenance')
    with package.open('rb') as source:md5=hashlib.file_digest(source,'md5').hexdigest()
    result={'packages':[entry(checked['version'],base_url,package.stat().st_size,md5,package_url,package_id)]}
    with tarfile.open(package) as source:
        if channel=='stable':
            receipt=source.extractfile(RECORD).read()
            if (provenance.get('realDsmAcceptance')!='recorded' or
                provenance.get('contentSha256')!=checked['contentSha256'] or
                provenance.get('acceptanceSha256')!=hashlib.sha256(receipt).hexdigest() or
                provenance.get('testedPreviewSha256')!=json.loads(receipt)['previewSha256']):
                raise ValueError('Stable catalog requires matching NAS acceptance provenance')
        for archive_name,destination in (('PACKAGE_ICON.PNG',package_id+'-64.png'),('PACKAGE_ICON_256.PNG',package_id+'-256.png')):
            (package.parent/destination).write_bytes(source.extractfile(archive_name).read())
    destination=package.parent/'catalog.json'
    destination.write_text(json.dumps(result,indent=2)+'\n')
    return destination


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package',required=True,type=Path)
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--package-url',help='Immutable public release SPK URL (stable only)')
    args=parser.parse_args()
    print(build(args.package,args.base_url,args.package_url))
