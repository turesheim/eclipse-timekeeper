#!/usr/bin/env python3
"""Promote the exact NAS-tested preview to stable without rebuilding its payload."""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile

from synology_acceptance import RECORD, template, validate_record
from validate_synology import validate


def preview(package):
    package = Path(package)
    checked = validate(package)
    provenance = json.loads(package.with_suffix('.json').read_text())
    if not checked['version'].startswith('1.99.0-') or provenance.get('channel') != 'preview':
        raise ValueError('Only a verified preview can be promoted')
    for key, actual in (('commit', checked['sourceCommit']), ('sha256', checked['sha256']),
                        ('imageId', checked['imageId']), ('version', checked['version']),
                        ('architecture', 'r1000'), ('package', checked['package'])):
        if provenance.get(key) != actual:
            raise ValueError('Preview provenance mismatch: ' + key)
    if package.name != checked['package'] + '-' + checked['version'] + '-r1000.spk':
        raise ValueError('Use the original versioned preview filename')
    return checked, provenance


def promote(package, acceptance, output):
    package, acceptance, output = Path(package), Path(acceptance), Path(output)
    checked, provenance = preview(package)
    record = json.loads(acceptance.read_text())
    validate_record(record, checked, checked['sha256'])
    version = checked['version'].replace('1.99.0-', '2.0.0-', 1)
    name = checked['package'] + '-' + version + '-r1000.spk'
    output.mkdir(parents=True, exist_ok=True)
    paths = [output / name, output / (name + '.sha256'), output / (name[:-4] + '.json')]
    if any(path.exists() for path in paths):
        raise ValueError('Refusing to replace an existing stable package or provenance')
    with tempfile.TemporaryDirectory(prefix='timekeeper-promote-') as temporary:
        destination = Path(temporary) / name
        receipt_bytes = (json.dumps(record, indent=2) + '\n').encode()
        with tarfile.open(package) as source, tarfile.open(destination, 'w') as target:
            for member in source.getmembers():
                value = source.extractfile(member).read() if member.isfile() else None
                member = copy.copy(member)
                if member.name == 'INFO':
                    lines = value.decode().splitlines(keepends=True)
                    value = ''.join('version=' + json.dumps(version) + '\n' if line.startswith('version=') else line for line in lines).encode()
                    member.size = len(value)
                target.addfile(member, io.BytesIO(value) if value is not None else None)
            member = tarfile.TarInfo(RECORD)
            member.size, member.mode = len(receipt_bytes), 0o644
            target.addfile(member, io.BytesIO(receipt_bytes))
        stable = validate(destination)
        if stable['contentSha256'] != checked['contentSha256']:
            raise ValueError('Promotion changed installed package contents')
        provenance.update(channel='stable', version=version, sha256=stable['sha256'],
                          realDsmAcceptance='recorded', testedPreviewSha256=checked['sha256'],
                          contentSha256=checked['contentSha256'],
                          acceptanceSha256=hashlib.sha256(receipt_bytes).hexdigest())
        # Exclusive writes also protect against another publisher racing us.
        created = []
        try:
            for path, value in zip(paths, (destination.read_bytes(),
                    (stable['sha256'] + '  ' + name + '\n').encode(),
                    (json.dumps(provenance, indent=2) + '\n').encode())):
                with path.open('xb') as stream:
                    created.append(path)
                    stream.write(value)
        except BaseException:
            for path in created:
                path.unlink()
            raise
    return paths[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', required=True, type=Path)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--write-template', type=Path)
    action.add_argument('--acceptance', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.write_template:
        checked, _ = preview(args.package)
        with args.write_template.open('x') as stream:
            stream.write(json.dumps(template(checked), indent=2) + '\n')
        print(args.write_template)
    else:
        if not args.output:
            parser.error('--output is required with --acceptance')
        print(promote(args.package, args.acceptance, args.output))


if __name__ == '__main__':
    main()
