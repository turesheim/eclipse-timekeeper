#!/usr/bin/env python3
"""Export a stopped, supported PostgreSQL 18 deployment for the DSM PostgreSQL 17 importer."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
from datetime import datetime, timezone
from setup import HERE, configuration

BASE_TABLES=('tk_schema','tk_owner','tk_resource','tk_relation','tk_external_reference',
        'tk_active','tk_receipt','tk_change','tk_account','tk_auth_control',
        'tk_auth_challenge','tk_api_token','tk_auth_session','tk_auth_throttle')
RECOVERY_TABLES=BASE_TABLES+('tk_recovery_control','tk_recovery_address','tk_recovery_link')
PASSKEY_TABLES=RECOVERY_TABLES+('tk_passkey_user','tk_passkey_credential','tk_passkey_ceremony')
SETTINGS_TABLES=PASSKEY_TABLES+('tk_service_settings',)
TABLES=SETTINGS_TABLES+('tk_locale',)


def tables(schema):
    if schema not in (2,3,4,5,6,7):raise ValueError('Unsupported server schema')
    return BASE_TABLES if schema==2 else RECOVERY_TABLES if schema==3 else PASSKEY_TABLES if schema==4 else SETTINGS_TABLES if schema==5 else TABLES


def fingerprint_sql(table):
    return ("SELECT '"+table+"|'||count(*)||'|'||COALESCE(md5(string_agg(md5(row_to_json(t)::text),'' "
            "ORDER BY md5(row_to_json(t)::text) COLLATE \"C\")),'empty') FROM public."+table+' t;')


def resources():
    packaged=HERE/'migration'
    return packaged if packaged.is_dir() else HERE.parent/'app/src/main/resources/db'


def compose_client(program, arguments, *, sql=None, output=None):
    result=subprocess.run(['docker','compose','run','--rm','--no-deps','-T','--entrypoint','sh','maintenance','-ec',
        'export PGPASSWORD="$(cat /run/secrets/timekeeper_database_password)"; exec "$@"',
        'timekeeper-migration',program,*arguments],cwd=HERE,input=sql.encode() if sql else None,
        stdout=output if output else subprocess.PIPE,stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('Migration export failed; keep the source stopped and check its database/schema configuration')
    return result.stdout.decode().strip() if output is None else None


def export(output, client=compose_client, state=None, db_resources=None):
    output=Path(output).resolve()
    if output.exists():raise ValueError('Choose a new migration archive path')
    db_resources=Path(db_resources or resources())
    state=Path(state or configuration().get('TIMEKEEPER_STATE_DIR',HERE/'state')).resolve()
    def query(sql):return client('psql',['-X','-q','-A','-t','-w','-v','ON_ERROR_STOP=1','-h','postgres','-U','timekeeper','-d','timekeeper'],sql=sql)
    def guard():
        value=query("SELECT current_setting('server_version_num')::int/10000; SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid(); SELECT schema_version||'|'||state FROM public.tk_schema WHERE id=1; SELECT bootstrap_completed FROM public.tk_auth_control WHERE id=1;")
        if value not in tuple(f'18\n0\n{version}|READY\nt' for version in (2,3,4,5,6,7)):
            raise ValueError('Stop ALL source API replicas, use supported schema 2/3/4/5/6/7 and complete first-administrator setup before export')
        return int(value.splitlines()[2].split('|')[0])
    schema=guard();exported_tables=tables(schema)
    expected=(db_resources/f'schema-v{schema}-postgresql.shape').read_text().strip()
    if query((db_resources/'postgresql-shape.sql').read_text())!=expected:
        raise ValueError('Migration supports only the exact canonical Timekeeper schema')
    fingerprints=query('\n'.join(fingerprint_sql(table) for table in exported_tables))
    with tempfile.TemporaryDirectory(prefix='timekeeper-migration-') as temp:
        directory=Path(temp)
        data=directory/'data.sql'
        with data.open('xb') as stream:
            client('pg_dump',['-h','postgres','-U','timekeeper','-d','timekeeper','--no-password',
                '--data-only','--no-owner','--no-privileges','--no-large-objects','--schema=public'],output=stream)
        data.chmod(0o600)
        if query('\n'.join(fingerprint_sql(table) for table in exported_tables))!=fingerprints:
            raise ValueError('Source changed during export; keep all replicas stopped and export again')
        if guard()!=schema:raise ValueError('Source schema changed during export')
        tokens=directory/'tokens.json';tokens.write_bytes((state/'config/tokens.json').read_bytes());tokens.chmod(0o600)
        table_fingerprints={name:count+'|'+digest for name,count,digest in (line.split('|') for line in fingerprints.splitlines())}
        if set(table_fingerprints)!=set(exported_tables):raise ValueError('Incomplete source fingerprint')
        def digest(path):
            with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
        manifest={'format':1,'kind':'timekeeper-postgresql18-to17','serverSchema':schema,
                  'sourcePostgreSQLMajor':18,'targetPostgreSQLMajor':17,
                  'createdAt':datetime.now(timezone.utc).isoformat(),
                  'schemaShapeSha256':hashlib.sha256((expected+'\n').encode()).hexdigest(),
                  'tableFingerprints':table_fingerprints,'files':{name:digest(directory/name) for name in ('data.sql','tokens.json')}}
        (directory/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
        output.parent.mkdir(parents=True,exist_ok=True)
        created=False
        try:
            with output.open('xb') as target:
                created=True
                os.chmod(output,0o600)
                with tarfile.open(fileobj=target,mode='w') as archive:
                    for name in ('manifest.json','data.sql','tokens.json'):
                        archive.add(directory/name,arcname=name,recursive=False)
        except BaseException:
            if created:output.unlink(missing_ok=True)
            raise
    return output


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    print(export(parser.parse_args().output))
