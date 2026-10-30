#!/usr/bin/env python3
"""Exercise the distributed Compose restore helper on real PostgreSQL, without NAS access."""
import argparse
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'server/synology/tests'))
from migration_acceptance import fixture_sql, exporter

spec=importlib.util.spec_from_file_location('restore_database',ROOT/'server/deploy/restore-database.py')
restorer=importlib.util.module_from_spec(spec);spec.loader.exec_module(restorer)


def failure_reason(stderr):
    # Database diagnostics can contain restored rows and credentials. Return
    # only fixed categories for known guards; never forward stderr or SQL.
    text=stderr.decode('utf-8',errors='replace') if isinstance(stderr,bytes) else ''
    for message,reason in (
        ('Stop all other database clients before maintenance','other-database-clients'),
        ('Noncanonical Timekeeper schema','noncanonical-schema'),
        ('Incomplete Timekeeper schema','incomplete-schema'),
        ('Missing instance-operation control','missing-instance-control')):
        if message in text:return reason
    return 'command-rejected'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres-image',default='postgres:18.6-bookworm')
    args=parser.parse_args()
    name='timekeeper-schema-restore-'+secrets.token_hex(5)
    def command(*arguments,input=None,output=None):
        options={'stdin':input} if hasattr(input,'read') else {'input':input.encode() if input else None}
        result=subprocess.run(arguments,stdout=output if output is not None else subprocess.PIPE,stderr=subprocess.PIPE,**options)
        if result.returncode:raise RuntimeError('Isolated restore operation failed: '+arguments[0]+' ['+failure_reason(result.stderr)+']')
        return result.stdout.decode().strip() if output is None else None
    def client(program,arguments,*,input=None,output=None):
        adjusted=list(arguments)
        if '-h' in adjusted:adjusted[adjusted.index('-h')+1]='127.0.0.1'
        return command('docker','exec','-i',name,program,*adjusted,input=input,output=output)
    def query(sql):return client('psql',['-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-U','timekeeper','-d','timekeeper'],input=sql)
    def snapshot():return query('\n'.join(exporter.fingerprint_sql(table) for table in exporter.TABLES))
    try:
        command('docker','run','-d','--name',name,'--network','none','-e','POSTGRES_HOST_AUTH_METHOD=trust',args.postgres_image)
        for attempt in range(100):
            if subprocess.run(['docker','exec',name,'pg_isready','-h','127.0.0.1','-U','postgres'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
            time.sleep(.1)
        else:raise RuntimeError('Disposable restore database did not start')
        command('docker','exec','-i',name,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','postgres',input='CREATE ROLE timekeeper LOGIN; CREATE DATABASE timekeeper OWNER timekeeper;')
        with tempfile.TemporaryDirectory(prefix='timekeeper-compose-restore-') as temporary:
            dump=Path(temporary)/'database.dump'
            for version in (2,3,4,5,6,7,8,9,10):
                query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;'+fixture_sql(version))
                source_tables=[table for table in exporter.tables(version) if (table!='tk_schema' or version==9) and (table!='tk_auth_session' or version>=4)]
                source=query('\n'.join(exporter.fingerprint_sql(table) for table in source_tables))
                with dump.open('wb') as output:client('pg_dump',['--format=custom','-U','timekeeper','-d','timekeeper'],output=output)
                dump.chmod(0o600)
                query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;'+fixture_sql(9))
                restorer.restore(dump,client=client)
                actual=query('\n'.join(exporter.fingerprint_sql(table) for table in source_tables))
                assert actual==source,'Restoring an older schema changed its private records'
                assert query("SELECT schema_version||'|'||state FROM tk_schema WHERE id=1")=='9|READY'
                assert query('SELECT count(*) FROM tk_auth_session')==('1' if version>=4 else '0')
                # Repeat against the current schema to check newer FK cleanup.
                stable=snapshot();restorer.restore(dump,client=client);assert snapshot()==stable
                query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;')
                restorer.restore(dump,client=client);assert snapshot()==stable,'Fresh-database restore changed private records'
                # Damaged source DDL must roll back cleanup and partial COPY data.
                query('ALTER TABLE tk_resource ADD COLUMN unexpected TEXT')
                with dump.open('wb') as output:client('pg_dump',['--format=custom','-U','timekeeper','-d','timekeeper'],output=output)
                query('ALTER TABLE tk_resource DROP COLUMN unexpected');stable=snapshot()
                try:restorer.restore(dump,client=client)
                except RuntimeError:pass
                else:raise AssertionError('Restore accepted a noncanonical source')
                assert snapshot()==stable,'A rejected restore changed the existing database'
                print(json.dumps({'postgres':args.postgres_image,'sourceSchema':version,'targetSchema':10,'sourceTablesCompared':len(source_tables),'privateFingerprintsPreserved':True,
                    'passkeySessionProvenancePreserved':version>=4,'legacyBrowserSessionsRevoked':version<4,'repeatedRestore':True,'freshDatabaseRestore':True,'noncanonicalRestoreRolledBack':True}),flush=True)
    finally:
        subprocess.run(['docker','rm','-f','-v',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)


if __name__=='__main__':main()
