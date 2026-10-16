"""Bind an operator's DSM acceptance record to the installed package contents."""
from datetime import datetime, timezone
import hashlib
import json
import re


CHECKS = (
    'manualInstall', 'dependencyResolution', 'webClient', 'eclipseClient',
    'startStop', 'nasRestart', 'upgrade', 'failedUpgradeRecovery',
    'migration18to17', 'backupRestore', 'reinstall', 'uninstallRetention',
    'unrelatedDataUntouched', 'repositoryDiscovery', 'repositoryUpgrade',
)
RECORD = 'DSM_ACCEPTANCE.json'


def content_digest(archive):
    """Ignore only tar serialization, the channel's version and the added record.

    All installed bytes, paths, permissions and ownership must match the tested
    preview, including scripts outside package.tgz. Do not extract archive paths.
    """
    entries = []
    for member in sorted(archive.getmembers(), key=lambda item: item.name):
        if member.name == RECORD:
            continue
        value = archive.extractfile(member).read() if member.isfile() else b''
        if member.name == 'INFO':
            info = {key: json.loads(raw) for key, raw in
                    (line.split('=', 1) for line in value.decode().splitlines())}
            info.pop('version')
            value = json.dumps(info, sort_keys=True, separators=(',', ':')).encode()
        entries.append([member.name, member.type.decode('ascii'), member.mode,
                        member.uid, member.gid, hashlib.sha256(value).hexdigest()])
    return hashlib.sha256(json.dumps(entries, separators=(',', ':')).encode()).hexdigest()


def template(checked):
    return {
        'format': 1, 'sourceCommit': checked['sourceCommit'],
        'previewSha256': checked['sha256'], 'imageId': checked['imageId'],
        'contentSha256': checked['contentSha256'], 'testedAt': None,
        'target': {'model': 'DS923+', 'architecture': 'r1000', 'dsmVersion': '',
                   'containerManagerVersion': '', 'postgresqlVersion': ''},
        'checks': dict.fromkeys(CHECKS, False),
    }


def validate_record(record, checked, preview_sha=None):
    expected = set(template(checked))
    if not isinstance(record, dict) or set(record) != expected or type(record['format']) is not int or record['format'] != 1:
        raise ValueError('Use the DSM acceptance template without additional fields')
    for key in ('sourceCommit', 'imageId', 'contentSha256'):
        if record[key] != checked[key]:
            raise ValueError('DSM acceptance does not describe these package contents: ' + key)
    if not isinstance(record['previewSha256'], str) or not re.fullmatch('[a-f0-9]{64}', record['previewSha256']):
        raise ValueError('Missing tested preview SHA-256')
    if preview_sha is not None and record['previewSha256'] != preview_sha:
        raise ValueError('DSM acceptance belongs to another preview archive')
    if not isinstance(record['checks'], dict) or set(record['checks']) != set(CHECKS) or any(record['checks'][key] is not True for key in CHECKS):
        raise ValueError('Every required real-NAS scenario must have passed')
    target = record['target']
    if not isinstance(target, dict) or set(target) != set(template(checked)['target']) or target['model'] != 'DS923+' or target['architecture'] != 'r1000':
        raise ValueError('Record the supported DS923+ / r1000 NAS target')
    for key in ('dsmVersion', 'containerManagerVersion', 'postgresqlVersion'):
        if not isinstance(target[key], str) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*-[0-9]+', target[key]):
            raise ValueError('Record the full installed package/DSM version: ' + key)
    if not target['postgresqlVersion'].startswith('17.'):
        raise ValueError('The native PostgreSQL package must be version 17')
    def version_parts(value):
        return tuple(int(part) for part in re.split(r'[.-]', value))
    if (version_parts(target['dsmVersion']) < (7, 2, 2, 72806) or
        version_parts(target['postgresqlVersion']) < (17, 11, 6) or
        int(target['containerManagerVersion'].rsplit('-', 1)[1]) < 1432):
        raise ValueError('Recorded DSM/native dependencies are below the supported minimum')
    stamp = record['testedAt']
    if not isinstance(stamp, str) or not re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ', stamp):
        raise ValueError('Record testedAt as a UTC timestamp, YYYY-MM-DDTHH:MM:SSZ')
    tested_at = datetime.strptime(stamp, '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)
    if tested_at > datetime.now(timezone.utc):
        raise ValueError('DSM acceptance cannot be dated in the future')
