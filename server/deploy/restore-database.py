#!/usr/bin/env python3
"""Restore a protected schema-2/3/4/5/6/7 dump and upgrade it in one offline transaction."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

HERE=Path(__file__).resolve().parent
CURRENT=7
SUPPORTED=(2,3,4,5,6,7)


def resources():
    packaged=HERE/'migration'
    return packaged if packaged.is_dir() else HERE.parent/'app/src/main/resources/db'


def compose_client(program,arguments,*,input=None,output=None):
    command=['docker','compose','run','--rm','--no-deps','-T','--entrypoint','sh','maintenance','-ec',
        'export PGPASSWORD="$(cat /run/secrets/timekeeper_database_password)"; exec "$@"',
        'timekeeper-restore',program,*arguments]
    options={'stdin':input} if hasattr(input,'read') else {'input':input.encode() if input else None}
    result=subprocess.run(command,cwd=HERE,stdout=output if output else subprocess.PIPE,stderr=subprocess.PIPE,**options)
    if result.returncode:
        raise RuntimeError('Database restore failed; keep all API replicas stopped and check the backup and database configuration')
    return result.stdout.decode().strip() if output is None else None


def schema_guard(directory,version):
    query=(directory/'postgresql-shape.sql').read_text().strip().removesuffix(';')
    expected=(directory/f'schema-v{version}-postgresql.shape').read_text().strip().replace("'","''")
    return ("\nSET search_path=public;\nDO $timekeeper_schema$ BEGIN IF (SELECT COALESCE(string_agg(definition,E'\\n' ORDER BY definition COLLATE \"C\"),'') FROM ("+
        query+") canonical) <> '"+expected+"' THEN RAISE EXCEPTION 'Noncanonical Timekeeper schema'; END IF; "+
        f"IF (SELECT count(*) FROM public.tk_schema WHERE id=1 AND schema_version={version} AND state='READY')<>1 OR (SELECT count(*) FROM public.tk_schema)<>1 THEN RAISE EXCEPTION 'Incomplete Timekeeper schema'; END IF; END $timekeeper_schema$;\n")


def restore(dump,client=compose_client,db_resources=None):
    directory=Path(db_resources or resources())
    connection=['-h','postgres','-U','timekeeper','-d','timekeeper','--no-password']
    # Decode only the tiny schema record first. No SQL from the archive executes
    # until its declared version is supported and the complete transaction exists.
    with Path(dump).open('rb') as source:
        metadata=client('pg_restore',['--data-only','--table=tk_schema','--file=-'],input=source)
    match=re.search(r'COPY public\.tk_schema \(id, schema_version, state\) FROM stdin;\n1\t([234567])\tREADY\n\\\.\n',metadata)
    if not match:raise ValueError('Backup does not contain a supported ready Timekeeper schema')
    incoming=int(match[1])
    def query(sql):return client('psql',[*connection,'-X','-q','-A','-t','-v','ON_ERROR_STOP=1'],input=sql)
    exists=query("SELECT COALESCE(to_regclass('public.tk_schema')::text,'');")
    current=None
    if exists:
        value=query("SELECT schema_version||'|'||state FROM public.tk_schema WHERE id=1;")
        if value not in tuple(f'{version}|READY' for version in SUPPORTED):
            raise ValueError('Target does not contain a supported ready Timekeeper schema')
        current=int(value.split('|')[0])
    elif query("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S','f');")!='0':
        raise ValueError('Refusing a nonempty target without Timekeeper schema metadata')
    with tempfile.TemporaryFile() as sql:
        before=("SET standard_conforming_strings=on; SELECT pg_advisory_xact_lock(92751638246);\n"
            "DO $timekeeper_offline$ BEGIN IF EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()) THEN RAISE EXCEPTION 'Stop all other database clients before maintenance'; END IF; END $timekeeper_offline$;\n")
        if current is not None:
            before+=schema_guard(directory,current)
            if current>=6 and incoming<6:
                before+='DROP TABLE public.tk_locale;\n'
            if current>=5 and incoming<5:
                before+='DROP TABLE public.tk_service_settings;\n'
            if current>=4 and incoming<4:
                before+='ALTER TABLE public.tk_auth_session DROP CONSTRAINT tk_auth_session_passkey;\nDROP TABLE public.tk_passkey_ceremony,public.tk_passkey_credential,public.tk_passkey_user;\n'
            if current>=3 and incoming<3:
                before+='DROP TABLE public.tk_recovery_link,public.tk_recovery_address,public.tk_recovery_control;\n'
        sql.write(before.encode())
        sql.flush()
        with Path(dump).open('rb') as source:
            client('pg_restore',['--clean','--if-exists','--no-owner','--no-privileges','--file=-'],input=source,output=sql)
        sql.seek(0,2)
        after=schema_guard(directory,incoming)
        for version in range(incoming+1,CURRENT+1):
            after+=(directory/{3:'recovery-v3.sql',4:'passkeys-v4.sql',5:'settings-v5.sql',6:'locale-v6.sql',7:'day-labels-v7.sql'}[version]).read_text()
            after+=f'\nUPDATE public.tk_schema SET schema_version={version} WHERE id=1;\n'+schema_guard(directory,version)
        sql.write(after.encode());sql.seek(0)
        client('psql',[*connection,'-X','-q','--single-transaction','--file=-','-v','ON_ERROR_STOP=1'],input=sql,output=subprocess.DEVNULL)


if __name__=='__main__':
    try:
        if len(sys.argv)!=2:raise ValueError('Usage: restore-database.py /absolute/path/to/database.dump')
        restore(sys.argv[1])
    except (ValueError,RuntimeError,OSError) as failure:
        print(str(failure),file=sys.stderr);raise SystemExit(1)
