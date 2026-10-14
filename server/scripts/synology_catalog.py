#!/usr/bin/env python3
"""Generate DSM discovery metadata beside a verified, self-contained preview SPK.

The per-architecture catalog is a candidate for real Package Center discovery
acceptance. Generating it does not publish a feed or attest NAS compatibility.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
from urllib.parse import urlsplit
from validate_synology import validate


def entry(version,base_url,size,md5):
    url=urlsplit(base_url)
    if url.scheme!='https' or not url.netloc or url.username or url.password or url.query or url.fragment or not url.path.endswith('/preview/r1000/'):
        raise ValueError('Use a public HTTPS preview/r1000/ catalog directory without credentials, query or fragment')
    return {'package':'timekeeper','version':version,'dname':'Timekeeper (preview)',
            'desc':'Timekeeper preview for DS923+ / r1000. Requires Container Manager and SynoCommunity PostgreSQL 17.',
            'link':base_url+'timekeeper-'+version+'-r1000.spk',
            'thumbnail':[base_url+'timekeeper-64.png'],
            'thumbnail_retina':[base_url+'timekeeper-256.png']*2,
            'qinst':False,'qupgrade':False,'qstart':False,'startable':'yes',
            'deppkgs':'ContainerManager:postgresql>=17.11-6','conflictpkgs':None,
            'snapshot':[],'maintainer':'Timekeeper contributors',
            'maintainer_url':'https://github.com/turesheim/eclipse-timekeeper',
            'md5':md5,'size':size}


def build(package,base_url):
    package=Path(package).resolve()
    checked=validate(package)
    provenance=json.loads(package.with_suffix('.json').read_text())
    expected_name='timekeeper-'+checked['version']+'-r1000.spk'
    if package.name!=expected_name or provenance.get('channel')!='preview' or not checked['version'].startswith('1.99.0-') or provenance.get('commit')!=checked['sourceCommit'] or provenance.get('sha256')!=checked['sha256']:
        raise ValueError('Preview catalog must match the verified package provenance')
    with package.open('rb') as source:md5=hashlib.file_digest(source,'md5').hexdigest()
    result={'packages':[entry(checked['version'],base_url,package.stat().st_size,md5)]}
    with tarfile.open(package) as source:
        for archive_name,destination in (('PACKAGE_ICON.PNG','timekeeper-64.png'),('PACKAGE_ICON_256.PNG','timekeeper-256.png')):
            (package.parent/destination).write_bytes(source.extractfile(archive_name).read())
    destination=package.parent/'catalog.json'
    destination.write_text(json.dumps(result,indent=2)+'\n')
    return destination


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package',required=True,type=Path)
    parser.add_argument('--base-url',required=True)
    args=parser.parse_args()
    print(build(args.package,args.base_url))
