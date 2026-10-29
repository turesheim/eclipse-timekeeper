"""Private Compose maintenance transport for committed instance restore material.

Only immutable active files are retained. Database replacement is authorized by
restore-database.py's SQL transaction; this module never authorizes a web restore.
"""
from io import BytesIO
import hashlib
import json
import os
import selectors
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile

HERE = Path(__file__).resolve().parent
FORMAT = 'timekeeper-instance-material-v1'
UUID = re.compile(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\Z')
SHA = re.compile(r'[a-f0-9]{64}\Z')
INPUT = re.compile(r'source/configuration/[0-9]{4}\.(?:bin|p12|pfx|jks|pem|crt|key|json|properties|der)\Z')
ARCHIVE = 'instance-material.tar'
ERROR = 'Instance material is missing or inconsistent; keep API replicas stopped and retry maintenance with a complete backup'


def invalid():
    return ValueError(ERROR)


def strict_json(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise invalid()
            result[key] = value
        return result
    try:
        value = json.loads(data, object_pairs_hook=unique)
        if not isinstance(value, dict):
            raise invalid()
        return value
    except (ValueError, UnicodeError):
        raise invalid() from None


def pointer(data):
    value = strict_json(data)
    if (set(value) != {'format', 'generation', 'sha256'} or value['format'] != FORMAT
            or not isinstance(value['generation'], str) or not UUID.fullmatch(value['generation'])
            or not isinstance(value['sha256'], str) or not SHA.fullmatch(value['sha256'])):
        raise invalid()
    return value


def metadata(data, active):
    if hashlib.sha256(data).hexdigest() != active['sha256']:
        raise invalid()
    value = strict_json(data)
    if (set(value) != {'format', 'generation', 'operation', 'intentDigest', 'entries'}
            or value['format'] != FORMAT or value['generation'] != active['generation']
            or not isinstance(value['operation'], str) or not UUID.fullmatch(value['operation'])
            or not isinstance(value['intentDigest'], str) or not SHA.fullmatch(value['intentDigest'])
            or not isinstance(value['entries'], dict) or not 2 <= len(value['entries']) <= 66
            or not {'application.properties', 'source-configuration.json'} <= value['entries'].keys()):
        raise invalid()
    total = 0
    for name, entry in value['entries'].items():
        limit = {'application.properties': 1_000_000, 'source-configuration.json': 4_000_000}.get(name, 20_000_000)
        if (name not in {'application.properties', 'source-configuration.json'} and not INPUT.fullmatch(name)
                or not isinstance(entry, dict) or set(entry) != {'bytes', 'sha256'}
                or type(entry['bytes']) is not int or not 0 <= entry['bytes'] <= limit
                or not isinstance(entry['sha256'], str) or not SHA.fullmatch(entry['sha256'])):
            raise invalid()
        total += entry['bytes']
    if total > 70_000_000:
        raise invalid()
    return value


def read_archive(path):
    """Validate before extracting anything; links, duplicates and extras fail."""
    if not path.exists():
        return None
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 71_000_000:
        raise invalid()
    files = {}
    total = 0
    try:
        with tarfile.open(path, 'r:') as archive:
            for member in archive:
                if (not member.isfile() or member.name in files or len(files) >= 68
                        or not 0 <= member.size <= 20_000_000 or member.mode != 0o600):
                    raise invalid()
                total += member.size
                if total > 70_200_000:
                    raise invalid()
                files[member.name] = archive.extractfile(member).read()
        active = pointer(files.get('active.json', b''))
        prefix = 'material/' + active['generation'] + '/'
        receipt = metadata(files.get(prefix + 'material.json', b''), active)
        expected = {'active.json', prefix + 'material.json'} | {prefix + name for name in receipt['entries']}
        if files.keys() != expected:
            raise invalid()
        for name, entry in receipt['entries'].items():
            body = files[prefix + name]
            if len(body) != entry['bytes'] or hashlib.sha256(body).hexdigest() != entry['sha256']:
                raise invalid()
        return {'pointer': active, 'metadata': receipt, 'files': files}
    except (OSError, tarfile.TarError, KeyError, TypeError):
        raise invalid() from None


def guard_sql(material):
    """All interpolated values come from validated UUIDs and SHA-256 digests."""
    condition = "COALESCE(payload::jsonb->>'restorePending','false')='false' AND COALESCE(payload::jsonb#>>'{restore,phase}','')<>'COMPLETE'"
    if material is not None:
        active, receipt = material['pointer'], material['metadata']
        # Revalidate even for programmatic callers, rather than accepting SQL text.
        active = pointer(json.dumps(active).encode())
        receipt = metadata(material['files']['material/' + active['generation'] + '/material.json'], active)
        condition = ("COALESCE(payload::jsonb->>'restorePending','false')='false'"
            + " AND payload::jsonb->>'restoreGeneration'='" + active['generation'] + "'"
            + " AND payload::jsonb#>>'{restore,phase}'='COMPLETE'"
            + " AND payload::jsonb#>>'{restore,operationId}'='" + receipt['operation'] + "'"
            + " AND payload::jsonb#>>'{restore,materialSha256}'='" + active['sha256'] + "'"
            + " AND payload::jsonb#>>'{restoreIntent,sha256}'='" + receipt['intentDigest'] + "'")
    return ("\nDO $instance_material$ BEGIN IF (SELECT count(*) FROM tk_instance WHERE kind='CONTROL')<>1 OR EXISTS "
            "(SELECT 1 FROM tk_instance WHERE kind='CONTROL' AND NOT COALESCE((" + condition
            + "),false)) THEN RAISE EXCEPTION 'Instance material is missing or inconsistent'; END IF; END $instance_material$;\n")


def copy_value(value):
    # PostgreSQL COPY text escapes backslashes independently from JSON escaping.
    def decode(match):
        escaped = match[1]
        if escaped[0] in '01234567':
            return chr(int(escaped, 8))
        return {'b': '\b', 'f': '\f', 'n': '\n', 'r': '\r', 't': '\t', 'v': '\v', '\\': '\\'}.get(escaped, escaped)
    return re.sub(r'\\([0-7]{1,3}|.)', decode, value)


def dump_control(dump, client):
    with dump.open('rb') as source:
        schema = client('pg_restore', ['--data-only', '--table=tk_schema', '--file=-'], input=source)
    match = re.search(r'COPY public\.tk_schema \(id, schema_version, state\) FROM stdin;\n1\t([2-9])\tREADY\n\\\.\n', schema)
    if not match:
        raise invalid()
    if int(match[1]) < 9:
        return {}
    with tempfile.TemporaryFile() as text:
        with dump.open('rb') as source:
            client('pg_restore', ['--data-only', '--table=tk_instance', '--file=-'], input=source, output=text)
        text.seek(0)
        copying = False
        control = None
        for raw in iter(lambda: text.readline(4_000_001), b''):
            if len(raw) > 4_000_000:
                raise invalid()
            line = raw.decode().rstrip('\n')
            if line == 'COPY public.tk_instance (id, kind, version, operation_id, actor_owner, occurred_at, payload) FROM stdin;':
                if copying:
                    raise invalid()
                copying = True
            elif copying and line == '\\.':
                copying = False
            elif copying:
                parts = line.split('\t')
                if len(parts) != 7:
                    raise invalid()
                if parts[1] == 'CONTROL':
                    if control is not None or parts[0] != '00000000-0000-0000-0000-000000000000':
                        raise invalid()
                    control = strict_json(copy_value(parts[6]))
        if copying or control is None:
            raise invalid()
        return control


def matches_control(control, material):
    if control.get('restorePending', False) is not False:
        raise invalid()
    restored = control.get('restore', {})
    if not isinstance(restored, dict):
        raise invalid()
    if restored.get('phase') == 'COMPLETE':
        if material is None:
            raise invalid()
        active, receipt = material['pointer'], material['metadata']
        if (control.get('restoreGeneration') != active['generation']
                or restored.get('operationId') != receipt['operation']
                or restored.get('materialSha256') != active['sha256']
                or control.get('restoreIntent', {}).get('sha256') != receipt['intentDigest']):
            raise invalid()
    elif material is not None:
        raise invalid()


# Paths are generated from validated inventory names. Inspect every component;
# tar never follows a pre-existing symlink or retains a hard link as material.
PRIVATE = '''
check() {
  path=/instance/restore/$1
  test ! -L "$path"
  test -f "$path"
  test "$(stat -c '%a:%h' "$path")" = '600:1'
  path=$(dirname "$path")
  while :; do
    test ! -L "$path"
    test -d "$path"
    test "$(stat -c '%a' "$path")" = 700
    test "$path" != /instance || break
    path=$(dirname "$path")
  done
}
'''


def volume(script, arguments=(), *, input=None, output=None, missing=False):
    result = subprocess.run(['docker', 'compose', 'run', '--rm', '--no-deps', '-T', '--user', '10001:10001',
        '--entrypoint', 'sh', 'maintenance', '-ec', script, 'instance-material', *arguments],
        cwd=HERE, input=input, stdout=output if output is not None else subprocess.PIPE, stderr=subprocess.PIPE)
    if missing and result.returncode == 3:
        return None
    if result.returncode:
        raise RuntimeError(ERROR)
    return result.stdout


def backup(directory, client):
    control = dump_control(directory / 'database.dump', client)
    raw = volume(PRIVATE + '''
test ! -e /instance/maintenance.pending
test ! -L /instance/maintenance.pending
if test ! -e /instance/restore/active.json && test ! -L /instance/restore/active.json; then exit 3; fi
check active.json
test "$(stat -c '%s' /instance/restore/active.json)" -le 1024
exec cat /instance/restore/active.json
''', missing=True)
    if raw is None:
        matches_control(control, None)
        return
    active = pointer(raw)
    name = 'material/' + active['generation'] + '/material.json'
    raw = volume(PRIVATE + '''check "$1"; test "$(stat -c '%s' /instance/restore/"$1")" -le 131072; exec cat /instance/restore/"$1"''', [name])
    receipt = metadata(raw, active)
    names = ['active.json', name, *('material/' + active['generation'] + '/' + item for item in sorted(receipt['entries']))]
    temporary = directory / (ARCHIVE + '.tmp')
    try:
        with temporary.open('xb') as output:
            temporary.chmod(0o600)
            volume(PRIVATE + 'for name do check "$name"; done; exec tar -C /instance/restore -cf - "$@"', names, output=output)
            output.flush()
            os.fsync(output.fileno())
        material = read_archive(temporary)
        matches_control(control, material)
        temporary.replace(directory / ARCHIVE)
    finally:
        temporary.unlink(missing_ok=True)


def prepare(directory):
    material = read_archive(directory / ARCHIVE)
    # Validate before modifying files. The identical binding is rechecked within
    # the SQL replacement transaction, so a stripped archive cannot be committed.
    from importlib.util import spec_from_file_location, module_from_spec
    spec = spec_from_file_location('compose_database_restore', HERE / 'restore-database.py')
    restorer = module_from_spec(spec); spec.loader.exec_module(restorer)
    matches_control(dump_control(directory / 'database.dump', restorer.compose_client), material)
    volume('''
umask 077
test ! -L /instance
test "$(stat -c '%a' /instance)" = 700
test ! -L /instance/maintenance.pending
tmp=$(mktemp /instance/.maintenance.XXXXXXXX)
printf '%s\n' 'Compose database maintenance is incomplete' > "$tmp"
sync -f /instance
mv -f "$tmp" /instance/maintenance.pending
sync -f /instance
''')
    if material is None:
        return
    active = material['pointer']
    prefix = 'material/' + active['generation'] + '/'
    with tempfile.TemporaryFile() as stream:
        with tarfile.open(fileobj=stream, mode='w:') as archive:
            for name, body in sorted(material['files'].items()):
                if not name.startswith(prefix):
                    continue
                entry = tarfile.TarInfo(name[len(prefix):]); entry.size = len(body); entry.mode = 0o600
                archive.addfile(entry, BytesIO(body))
        stream.seek(0)
        # subprocess input expects bytes; total expanded state is bounded above.
        volume('''
umask 077
for path in /instance/restore /instance/restore/material; do
  test ! -L "$path"
  if test ! -e "$path"; then mkdir "$path"; fi
  test -d "$path"
  test "$(stat -c '%a' "$path")" = 700
done
stage=$(mktemp -d /instance/restore/material/.maintenance.XXXXXXXX)
trap 'rm -rf "$stage"' EXIT
tar --no-same-owner --same-permissions -xf - -C "$stage"
sync -f /instance
path=/instance/restore/material/$1
test ! -L "$path"
if test -e "$path"; then
  test -d "$path"
  test "$(stat -c '%a' "$path")" = 700
  diff -r "$stage" "$path" > /dev/null
else
  mv -T "$stage" "$path"
fi
sync -f /instance
''', [active['generation']], input=stream.read())
    names = [name for name in material['files'] if name != 'active.json']
    volume(PRIVATE + 'for name do check "$name"; done', names)


def finish(directory):
    material = read_archive(directory / ARCHIVE)
    raw = b'' if material is None else json.dumps(material['pointer'], separators=(',', ':')).encode()
    volume('''
umask 077
test -f /instance/maintenance.pending
test ! -L /instance/maintenance.pending
for path in /instance/restore /instance/restore/active.json /instance/restore/prepared.json; do test ! -L "$path"; done
if test "$1" = active; then
  tmp=$(mktemp /instance/restore/.active.XXXXXXXX)
  cat > "$tmp"
  chmod 600 "$tmp"
  sync -f /instance
  mv -f "$tmp" /instance/restore/active.json
else
  rm -f /instance/restore/active.json
fi
rm -f /instance/restore/prepared.json
sync -f /instance
rm /instance/maintenance.pending
sync -f /instance
''', ['active' if material is not None else 'none'], input=raw)


def locked(command):
    """Hold one kernel lock on the shared volume across all host-side steps."""
    script = '''umask 077
test ! -L /instance/maintenance.lock
if test -e /instance/maintenance.lock; then
  test -f /instance/maintenance.lock
  test "$(stat -c '%a:%h' /instance/maintenance.lock)" = '600:1'
fi
exec 9>>/instance/maintenance.lock
flock -n 9
printf 'LOCKED\n'
cat > /dev/null
'''
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(['docker', 'compose', 'run', '--rm', '--no-deps', '-T', '--user', '10001:10001',
            '--entrypoint', 'sh', 'maintenance', '-ec', script], cwd=HERE,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors)
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                if not selector.select(60) or process.stdout.readline(16) != b'LOCKED\n':
                    raise RuntimeError('Another maintenance operation is active or its private lock is unavailable')
            return subprocess.run(['sh', *command], cwd=HERE,
                env={**os.environ, 'TIMEKEEPER_COMPOSE_LOCK_HELD': '1'}).returncode
        finally:
            process.stdin.close()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.terminate(); process.wait(timeout=10)
            process.stdout.close()


if __name__ == '__main__':
    try:
        if len(sys.argv) >= 3 and sys.argv[1] == 'locked':
            raise SystemExit(locked(sys.argv[2:]))
        if len(sys.argv) != 3 or sys.argv[1] not in {'backup', 'prepare', 'finish'}:
            raise invalid()
        directory = Path(sys.argv[2])
        if sys.argv[1] == 'backup':
            from importlib.util import spec_from_file_location, module_from_spec
            spec = spec_from_file_location('compose_database_restore', HERE / 'restore-database.py')
            restorer = module_from_spec(spec); spec.loader.exec_module(restorer)
            backup(directory, restorer.compose_client)
        else:
            {'prepare': prepare, 'finish': finish}[sys.argv[1]](directory)
    except (ValueError, RuntimeError, OSError, KeyError, TypeError):
        print(ERROR, file=sys.stderr)
        raise SystemExit(1)
