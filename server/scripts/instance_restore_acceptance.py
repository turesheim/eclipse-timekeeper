#!/usr/bin/env python3
"""Verify the shipped offline recovery entry point with a disposable protocol fixture.

The normal administrator proof boundary is covered by application tests. This
check ensures the helper and its dependencies actually work from the distribution.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', type=Path, required=True)
    args = parser.parse_args()
    digest = hashlib.sha256(args.distribution.read_bytes()).hexdigest()
    if args.distribution.with_suffix('.zip.sha256').read_text().split()[0] != digest:
        raise ValueError('Distribution checksum mismatch')
    report = ROOT / 'server/app/target/surefire-reports/TEST-net.resheim.timekeeper.server.InstanceRestoreRecoveryTest.xml'
    properties = {entry.attrib['name']: entry.attrib['value'] for entry in ET.parse(report).getroot().find('properties')}
    fixture_classpath = properties.get('surefire.test.class.path', properties.get('java.class.path'))
    if not fixture_classpath:
        raise ValueError('Run the server tests before packaged recovery acceptance')
    with tempfile.TemporaryDirectory(prefix='timekeeper-instance-restore-') as temporary:
        stage = Path(temporary)
        fixture = stage / 'fixture'
        fixture.mkdir(mode=0o700)
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(('TIMEKEEPER_', 'QUARKUS_', 'SMALLRYE_'))
                       and key not in {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'}}
        prepared = subprocess.run(['java', '-cp', fixture_classpath,
                                   'net.resheim.timekeeper.server.InstanceRestorePackagedFixture', str(fixture)],
                                  cwd=ROOT, env=environment, capture_output=True, timeout=40)
        if prepared.returncode:
            raise ValueError('Disposable restore fixture failed')
        with zipfile.ZipFile(args.distribution) as archive:
            for entry in archive.infolist():
                path = Path(entry.filename)
                if not entry.filename.startswith('timekeeper-server/') or path.is_absolute() or '..' in path.parts or stat.S_ISLNK(entry.external_attr >> 16):
                    raise ValueError('Unexpected distribution entry')
            manifest = json.loads(archive.read('timekeeper-server/timekeeper-release.json'))
            archive.extractall(stage / 'distribution')
        app = stage / 'distribution/timekeeper-server'
        # Boot libraries contain logging and SmallRye classes that are not in lib/main.
        runtime_classpath = os.pathsep.join(str(app / pattern) for pattern in ('app/*', 'lib/main/*', 'lib/boot/*'))
        environment['TIMEKEEPER_SERVER_DB_PASSWORD_FILE'] = str(fixture / 'database-secret')
        command = ['java', '-Dquarkus.config.locations=' + (fixture / 'destination.properties').as_uri(),
                   '-cp', runtime_classpath, 'net.resheim.timekeeper.server.InstanceRestoreMain', str(fixture / 'intents, café')]
        for _ in range(2):
            restored = subprocess.run(command, cwd=ROOT, env=environment, capture_output=True, timeout=40)
            if restored.returncode or not restored.stdout.startswith(b'INSTANCE_RESTORE_COMPLETE\n'):
                raise ValueError('Packaged offline recovery failed')
        if (fixture / 'intents, café/prepared.json').exists():
            raise ValueError('Completed recovery retained its plaintext handoff')
        evidence = {'sourceCommit': manifest['commit'], 'distributionSha256': digest,
                    'packagedRuntimeOnly': True, 'fileBackedCredentials': True, 'completedRetry': True, 'plaintextStagingRemoved': True}
        output = ROOT / 'server/target/acceptance'
        output.mkdir(parents=True, exist_ok=True)
        (output / 'instance-restore.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print('Packaged offline instance recovery and completed retry passed.')


if __name__ == '__main__':
    main()
