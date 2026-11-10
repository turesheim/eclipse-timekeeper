#!/usr/bin/env python3
"""Verify private task-provider state across two real Quarkus server JVMs."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def run(*command, **kwargs):
    return subprocess.run(command, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expected-commit', help='Require this exact source checkout before starting the fixture.')
    parser.add_argument('--existing-database', action='store_true',
                        help='Use the caller-provisioned TIMEKEEPER_TEST_TASK_DB_URL prefix and PostgreSQL credentials.')
    parser.add_argument('--postgres-image', default='postgres:17.11-bookworm')
    args = parser.parse_args()
    commit = run('git', 'rev-parse', 'HEAD', cwd=ROOT, capture_output=True).stdout.strip()
    if args.expected_commit and args.expected_commit != commit:
        parser.error('Expected source commit does not match this checkout')
    dirty = bool(run('git', 'status', '--porcelain', cwd=ROOT, capture_output=True).stdout.strip())
    if args.expected_commit and dirty:
        parser.error('Expected source commit requires a clean checkout')
    env = os.environ.copy()
    name = 'dagsverk-task-restart-' + uuid.uuid4().hex[:12]
    owned = False
    try:
        if args.existing_database:
            for key in ('TIMEKEEPER_TEST_TASK_DB_URL', 'TIMEKEEPER_TEST_PG_USER', 'TIMEKEEPER_TEST_PG_PASSWORD'):
                if not env.get(key):
                    parser.error('Missing isolated fixture variable: ' + key)
        else:
            password = 'isolated-task-restart-fixture'
            run('docker', 'run', '-d', '--rm', '--name', name, '-p', '127.0.0.1::5432',
                '-e', 'POSTGRES_PASSWORD=' + password, args.postgres_image, stdout=subprocess.DEVNULL)
            owned = True
            deadline = time.monotonic() + 30
            while subprocess.run(['docker', 'exec', name, 'pg_isready', '-U', 'postgres'],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Owned PostgreSQL restart fixture did not become ready')
                time.sleep(.25)
            run('docker', 'exec', name, 'psql', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1',
                '-c', 'CREATE DATABASE timekeeper_tasks_task_provider_restart', stdout=subprocess.DEVNULL)
            port = run('docker', 'port', name, '5432/tcp', capture_output=True).stdout.strip().rsplit(':', 1)[1]
            env.update(TIMEKEEPER_TEST_TASK_DB_URL='jdbc:postgresql://127.0.0.1:' + port + '/timekeeper_tasks',
                       TIMEKEEPER_TEST_PG_USER='postgres', TIMEKEEPER_TEST_PG_PASSWORD=password)
        env['QUARKUS_HTTP_TEST_PORT'] = '0'
        with tempfile.TemporaryDirectory(prefix='dagsverk-task-restart-proof-') as temporary:
            env['TIMEKEEPER_TEST_TASK_RESTART_PROOF'] = str(Path(temporary) / 'proof.json')
            for phase in ('prepare', 'verify'):
                print('Task-provider restart phase: ' + phase, flush=True)
                env['TIMEKEEPER_TEST_TASK_RESTART_PHASE'] = phase
                run(str(ROOT / 'mvnw'), '-B', '-ntp', '-f', 'server/pom.xml', '-pl', 'app', '-am',
                    '-Dtest=TaskProviderRestartTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test',
                    cwd=ROOT, env=env)
            proof = json.loads(Path(env['TIMEKEEPER_TEST_TASK_RESTART_PROOF']).read_text())
            if not proof.get('passed') or proof['preparePid'] == proof['verifyPid']:
                raise RuntimeError('Two distinct successful server JVMs were not observed')
            result = {'passed': True, 'commit': commit, 'sourceDirty': dirty,
                      'preparePid': proof['preparePid'], 'verifyPid': proof['verifyPid'],
                      'checks': ['acknowledged-page-resume', 'repeated-sync-without-duplicates',
                                 'expired-sending-to-unknown-without-resend', 'durable-ack-without-provider-request']}
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, indent=2) + '\n')
            print(json.dumps(result), flush=True)
        return 0
    finally:
        if owned:
            subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    raise SystemExit(main())
