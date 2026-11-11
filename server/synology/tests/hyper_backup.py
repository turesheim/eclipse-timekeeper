"""Exercise Hyper Backup callbacks against PostgreSQL with a simulated DSM lifecycle.

The real package-account privilege drop and native archives are used. Package
discovery, jsoncmd and the Hyper Backup UI still require real DSM acceptance.
"""
import json
from pathlib import Path
import tarfile


def verify(command, container, folder, env_file, read, sql):
    folder = Path(folder)
    lifecycle = folder/'synopkg'
    lifecycle.write_text('''#!/bin/sh
set -eu
test "$2" = timekeeper
case "$1" in
  status)
    if test -e /run/dagsverk-fixture-running; then state=running; else state=stop; fi
    printf '{"status":"%s","status_code":0}\\n' "$state"
    ;;
  stop) rm -f /run/dagsverk-fixture-running ;;
  start) touch /run/dagsverk-fixture-running ;;
  *) exit 1 ;;
esac
''')
    lifecycle.chmod(0o755)
    command('docker', 'exec', container, 'groupadd', '-g', '37017', 'timekeeper')
    command('docker', 'exec', container, 'useradd', '-u', '37017', '-g', '37017', '-M', 'timekeeper')
    command('docker', 'exec', container, 'mkdir', '-p', '/usr/syno/bin', '/tmp/dagsverk-hyper-backup')
    command('docker', 'exec', container, 'chmod', '700', '/tmp/dagsverk-hyper-backup')
    command('docker', 'cp', str(lifecycle), container+':/usr/syno/bin/synopkg')
    request = json.dumps({'temp_path': '/tmp/dagsverk-hyper-backup', 'app_data_version': '1.0'})

    def callback(action, expected=0):
        result = command('docker', 'exec', '--env-file', str(env_file), '-e', 'SYNOPKG_BKP_INPUT='+request,
                         container, '/var/packages/timekeeper/scripts/timekeeper-dsm', 'hyper-backup-'+action, check=False)
        if result.returncode != expected or result.stdout:
            raise AssertionError('Unexpected application callback result: '+action)
        return result

    query = "SELECT payload::jsonb->>'name' FROM tk_resource WHERE owner_id='existing-owner' AND entity_type='LABEL'"
    source = sql(query)
    material = read('instance-restore/active.json')
    credentials = read('secrets/database_password')
    registration = folder/'task_providers.properties'
    registration.write_text('timekeeper.tasks.google.client-id=fixture-id\ntimekeeper.tasks.google.client-secret=synthetic-backup-registration\n')
    registration_path = '/var/packages/timekeeper/var/server/secrets/task_providers.properties'
    command('docker', 'cp', str(registration), container+':'+registration_path)
    command('docker', 'exec', container, 'chown', '37017:37017', registration_path)
    command('docker', 'exec', container, 'chmod', '444', registration_path)
    command('docker', 'exec', container, 'touch', '/run/dagsverk-fixture-running')
    callback('export')
    command('docker', 'exec', container, 'test', '-e', '/run/dagsverk-fixture-running')
    archive = '/tmp/dagsverk-hyper-backup/application-backup.tar'
    mode = command('docker', 'exec', container, 'stat', '-c', '%a:%u', archive).stdout.strip()
    if mode != '600:0':
        raise AssertionError('Application archive is not private root-owned Hyper Backup staging')
    exported = folder/'hyper-backup-application.tar'
    command('docker', 'cp', container+':'+archive, str(exported))
    with tarfile.open(exported) as native:
        manifest = json.load(native.extractfile('manifest.json'))
        if manifest['format'] != 2 or 'instance-restore/active.json' not in manifest['files']:
            raise AssertionError('Application backup omitted active restore security material')
        if native.extractfile('secrets/task_providers.properties').read().decode() != registration.read_text():
            raise AssertionError('Application backup omitted task-provider app registrations')
    changed = folder/'changed-task-providers.properties'
    changed.write_text('# Changed after backup.\n')
    command('docker', 'cp', str(changed), container+':'+registration_path)
    sql("UPDATE tk_resource SET payload=jsonb_set(payload::jsonb,'{name}','\"Hyper Backup restore sentinel\"')::text WHERE owner_id='existing-owner' AND entity_type='LABEL'")
    callback('import')
    command('docker', 'exec', container, 'test', '-e', '/run/dagsverk-fixture-running')
    if sql(query) != source or read('instance-restore/active.json') != material or read('secrets/database_password') != credentials:
        raise AssertionError('Application restore did not preserve data, material and destination database credential')
    mounted = command('docker', 'exec', container, 'cat', '/var/packages/timekeeper/target/private/secrets/task_providers.properties').stdout
    if read('secrets/task_providers.properties').decode() != registration.read_text() or mounted != registration.read_text():
        raise AssertionError('Application restore did not restore matching private task-provider registrations')
    command('docker', 'exec', container, 'sh', '-ec', 'printf corrupt > /tmp/dagsverk-hyper-backup/application-backup.tar')
    callback('import', expected=1)
    command('docker', 'exec', container, 'test', '!', '-e', '/run/dagsverk-fixture-running')
    if sql(query) != source:
        raise AssertionError('Corrupt application restore changed native data')
    return {'hyperBackupNativeRoundTrip': True, 'hyperBackupPackageAccountTransfer': True,
            'hyperBackupActiveMaterialRetained': True, 'hyperBackupCorruptRestoreLeavesPackageStopped': True,
            'hyperBackupTaskProviderRegistrationsRetained': True,
            'hyperBackupRealDsmAcceptance': 'required'}
