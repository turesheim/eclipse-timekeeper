"""Reject unusable or mismatched DSM archives before asking for a NAS install."""
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import tarfile
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'server/scripts'))
from validate_synology import REQUIRED, validate

ROOT=Path(__file__).resolve().parents[2]
COMMIT='a'*40
IMAGE='ghcr.io/turesheim/eclipse-timekeeper-server:sha-'+COMMIT


def archive(files, compressed=False, extra=None):
    output=io.BytesIO()
    with tarfile.open(fileobj=output,mode='w:gz' if compressed else 'w') as tar:
        for name,value in files.items():
            item=tarfile.TarInfo(name);item.size=len(value)
            item.mode=0o755 if name.startswith('scripts/') and not name.endswith('.json') else 0o644
            tar.addfile(item,io.BytesIO(value))
        if extra is not None: tar.addfile(extra)
    return output.getvalue()


class SynologyPackageTest(unittest.TestCase):
    def candidate(self, *, image_tag=IMAGE, architecture='amd64', missing_launcher=False, elevated=False, outer_extra=None, checksum=None):
        image=archive({'manifest.json':json.dumps([{'RepoTags':[image_tag],'Config':'config.json','Layers':[]}]).encode(),
                       'config.json':json.dumps({'architecture':architecture,'os':'linux'}).encode()},True)
        payload={'image.tar.gz':image,'project/compose.yaml':b'{"services":{}}',
                 **{name:b'guide' for name in ('README.md','ACCOUNTS.md','TAILSCALE.md')}}
        if not missing_launcher:payload['ui/config']=(ROOT/'server/synology/ui/config').read_bytes()
        payload=archive(payload,True)
        info={'package':'timekeeper','arch':'r1000','os_min_ver':'7.2.2-72806','version':'1.99.0-1',
              'dsmuidir':'ui','dsmappname':'net.resheim.timekeeper','install_dep_packages':'ContainerManager:postgresql>=17.11-6',
              'checksum':checksum or hashlib.md5(payload,usedforsecurity=False).hexdigest()}
        files={name:b'#!/bin/sh\nexit 0\n' for name in REQUIRED}
        helper=bytearray(64);helper[:5]=b'\x7fELF\x02';struct.pack_into('<H',helper,18,62)
        files.update({'INFO':''.join(key+'='+json.dumps(value)+'\n' for key,value in info.items()).encode(),
                      'package.tgz':payload,'scripts/timekeeper-dsm':bytes(helper),
                      'scripts/release.json':json.dumps({'commit':COMMIT,'image':IMAGE,'serverSchema':2}).encode(),
                      'conf/privilege':json.dumps({'defaults':{'run-as':'root' if elevated else 'package'},'username':'timekeeper','groupname':'timekeeper'}).encode(),
                      'conf/resource':(ROOT/'server/synology/conf/resource').read_bytes(),
                      'PACKAGE_ICON.PNG':(ROOT/'server/synology/ui/images/timekeeper_64.png').read_bytes(),
                      'PACKAGE_ICON_256.PNG':(ROOT/'server/synology/ui/images/timekeeper_256.png').read_bytes()})
        return archive(files,extra=outer_extra)

    def check(self, content):
        with tempfile.TemporaryDirectory() as temp:
            package=Path(temp)/'candidate.spk';package.write_bytes(content)
            return validate(package)

    def test_self_contained_candidate_reports_source_and_requires_real_dsm_acceptance(self):
        receipt=self.check(self.candidate())
        self.assertEqual(COMMIT,receipt['sourceCommit'])
        self.assertEqual('required',receipt['realDsmAcceptance'])

    def test_wrong_image_tag_or_cpu_is_rejected(self):
        for overrides in ({'image_tag':'timekeeper:latest'},{'architecture':'arm64'}):
            with self.subTest(overrides=overrides),self.assertRaises(ValueError):self.check(self.candidate(**overrides))

    def test_missing_launcher_privileged_controller_or_corrupted_payload_is_rejected(self):
        for overrides in ({'missing_launcher':True},{'elevated':True},{'checksum':'0'*32}):
            with self.subTest(overrides=overrides),self.assertRaises(ValueError):self.check(self.candidate(**overrides))

    def test_parent_path_and_symlink_escape_is_rejected(self):
        for name,kind in (('../outside',tarfile.REGTYPE),('escape',tarfile.SYMTYPE)):
            member=tarfile.TarInfo(name);member.type=kind;member.linkname='/etc/passwd'
            with self.subTest(name=name),self.assertRaises(ValueError):self.check(self.candidate(outer_extra=member))
