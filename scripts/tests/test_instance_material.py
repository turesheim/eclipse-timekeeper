"""Maintenance restoration must never publish a database without bound material."""
import hashlib
from io import BytesIO
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'server/deploy'))
import instance_material as material

GENERATION = '11111111-1111-4111-8111-111111111111'
OPERATION = '22222222-2222-4222-8222-222222222222'
PREFIX = 'material/' + GENERATION + '/'


def fixture():
    files = {'application.properties': b'timekeeper.instance-restore.storage=/instance/restore\n',
             'source-configuration.json': b'{}', 'source/configuration/0000.key': b'disposable security material'}
    receipt = {'format': material.FORMAT, 'generation': GENERATION, 'operation': OPERATION, 'intentDigest': 'd' * 64,
               'entries': {name: {'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest()} for name, body in files.items()}}
    raw = json.dumps(receipt).encode()
    active = {'format': material.FORMAT, 'generation': GENERATION, 'sha256': hashlib.sha256(raw).hexdigest()}
    files = {PREFIX + name: body for name, body in files.items()}
    files[PREFIX + 'material.json'] = raw
    files['active.json'] = json.dumps(active).encode()
    control = {'restoreGeneration': GENERATION, 'restoreIntent': {'sha256': receipt['intentDigest']},
               'restore': {'phase': 'COMPLETE', 'operationId': OPERATION, 'materialSha256': active['sha256']}}
    return files, control


def archive(path, files, *, extra=None):
    with tarfile.open(path, 'w:') as output:
        for name, body in files.items():
            entry = tarfile.TarInfo(name); entry.size = len(body); entry.mode = 0o600
            output.addfile(entry, BytesIO(body))
        if extra is not None:
            output.addfile(extra, BytesIO(b'') if extra.isfile() else None)
    return path


class InstanceMaterialTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(); self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / material.ARCHIVE

    def test_complete_inventory_and_dump_binding_are_required(self):
        files, control = fixture()
        restored = material.read_archive(archive(self.path, files))
        material.matches_control(control, restored)
        self.assertEqual(files, restored['files'])
        for incomplete in [None, {**restored, 'pointer': {**restored['pointer'], 'generation': OPERATION}}]:
            with self.assertRaisesRegex(ValueError, 'material is missing'):
                material.matches_control(control, incomplete)
        for invalid in [{'restorePending': True}, {**control, 'restoreGeneration': OPERATION}, {}]:
            with self.assertRaises(ValueError):
                material.matches_control(invalid, restored)
        material.matches_control({}, None)

    def test_missing_extra_modified_linked_or_duplicate_files_are_rejected(self):
        files, _ = fixture()
        cases = [{name: body for name, body in files.items() if not name.endswith('.key')},
                 {**files, PREFIX + 'source/configuration/0000.key': b'changed'},
                 {**files, '../outside': b''}]
        for candidate in cases:
            with self.assertRaises(ValueError):
                material.read_archive(archive(self.path, candidate))
        for kind in [tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.DIRTYPE, tarfile.REGTYPE]:
            extra = tarfile.TarInfo('active.json'); extra.type = kind; extra.linkname = '/outside'; extra.mode = 0o600
            with self.assertRaises(ValueError):
                material.read_archive(archive(self.path, files, extra=extra))

    def test_duplicate_json_fields_and_sql_injection_never_pass_validation(self):
        with self.assertRaises(ValueError):
            material.pointer(b'{"format":1,"format":2}')
        files, _ = fixture()
        restored = material.read_archive(archive(self.path, files))
        sql = material.guard_sql(restored)
        self.assertIn("restorePending", sql)
        self.assertIn("materialSha256", sql)
        restored['pointer']['generation'] = "'; DROP TABLE tk_account; --"
        with self.assertRaises(ValueError):
            material.guard_sql(restored)
        self.assertIn("<>'COMPLETE'", material.guard_sql(None))

    def test_control_is_read_from_copy_text_without_executing_dump_sql(self):
        _, control = fixture()
        control['note'] = 'Backslash \\ and newline\næ'
        escaped = json.dumps(control).replace('\\', '\\\\')
        calls = []
        def client(program, arguments, *, input=None, output=None):
            calls.append((program, arguments))
            if '--table=tk_schema' in arguments:
                return 'COPY public.tk_schema (id, schema_version, state) FROM stdin;\n1\t9\tREADY\n\\.\n'
            self.assertEqual('pg_restore', program)
            output.write(('COPY public.tk_instance (id, kind, version, operation_id, actor_owner, occurred_at, payload) FROM stdin;\n'
                '00000000-0000-0000-0000-000000000000\tCONTROL\t1\t\\N\t\\N\t1970-01-01T00:00:00Z\t' + escaped + '\n\\.\n').encode())
        self.path.touch()
        self.assertEqual(control, material.dump_control(self.path, client))
        self.assertEqual(2, len(calls))
