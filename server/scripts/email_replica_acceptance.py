#!/usr/bin/env python3
"""Verify email recovery across two actual JVM replicas and a retired hostname.

All data, SMTP messages and credentials are disposable. Only API fixture ports
are published, on loopback. SMTP and PostgreSQL share a private network namespace.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'server/scripts'))
from configure_accounts import create_challenge


def command(*args,check=True):
    result=subprocess.run(args,capture_output=True,text=True,timeout=60)
    if check and result.returncode:raise RuntimeError('Isolated container operation failed: '+args[1])
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-image',required=True)
    args=parser.parse_args()
    suffix=secrets.token_hex(5);database='timekeeper-email-db-'+suffix;smtp='timekeeper-email-smtp-'+suffix
    names=[];password=secrets.token_urlsafe(32);account_password='Initial independent account password';new_password='Replacement independent account password'
    vpn='https://unreachable-vpn.example.invalid:8443';public='https://public.example.invalid'
    with tempfile.TemporaryDirectory(prefix='timekeeper-email-replicas-') as temporary:
        folder=Path(temporary);config=folder/'config';config.mkdir(mode=0o755)
        # The parent must be traversable by container UID 10001; mounted files
        # contain only hashed setup configuration. Operator secrets stay outside it.
        registry=config/'tokens.json';registry.write_text('[]\n');registry.chmod(0o644)
        code,_=create_challenge(config/'setup.json');(config/'setup.json').chmod(0o644)
        try:
            db_env=folder/'database.env';db_env.write_text('POSTGRES_PASSWORD='+password+'\n');db_env.chmod(0o600)
            command('docker','run','-d','--name',database,'--env-file',str(db_env),
                '-p','127.0.0.1::8081','-p','127.0.0.1::8082','postgres:18.6-bookworm');names.append(database)
            for _ in range(120):
                if command('docker','exec',database,'pg_isready','-h','127.0.0.1','-U','postgres',check=False).returncode==0:break
                time.sleep(.1)
            else:raise RuntimeError('Isolated recovery database did not start')
            smtp_script=("import json,threading;from pathlib import Path;from recovery_fixture import RecoveryFixture;"
                "fixture=RecoveryFixture(Path('/tmp'),0,mail=True);Path('/tmp/fixture.json').write_text(json.dumps(fixture.environment));threading.Event().wait()")
            command('docker','run','-d','--name',smtp,'--network','container:'+database,'--read-only','--tmpfs','/tmp',
                '-v',str(ROOT/'server/web/tests/recovery_fixture.py')+':/fixture/recovery_fixture.py:ro','-w','/fixture','python:3.13-alpine','python','-c',smtp_script);names.append(smtp)
            mail_environment=None
            for _ in range(100):
                result=command('docker','exec',smtp,'cat','/tmp/fixture.json',check=False)
                if result.returncode==0:mail_environment=json.loads(result.stdout);break
                time.sleep(.1)
            if mail_environment is None:raise RuntimeError('Isolated SMTP fixture did not start')
            mail_environment.pop('TIMEKEEPER_WEB_MAIL_FIXTURE')
            bases={index:'http://127.0.0.1:'+command('docker','port',database,str(8080+index)+'/tcp').stdout.strip().rsplit(':',1)[1] for index in (1,2)}
            def api(index,path,data=None,session=None,*,method=None,headers=None):
                selected={**(headers or {})}
                if session:
                    selected.update({'Cookie':session['cookie'],'X-CSRF-Token':session['csrf']})
                if data is not None:selected['Content-Type']='application/json'
                request=Request(bases[index]+'/api/v1'+path,data=None if data is None else json.dumps(data).encode(),headers=selected,method=method or ('GET' if data is None else 'POST'))
                try:response=urlopen(request,timeout=20)
                except HTTPError as failure:response=failure
                with response:
                    raw=response.read();body=json.loads(raw) if raw else None
                    return response.status,body,response.headers
            def expect_status(reply,status,operation):
                if reply[0]!=status:raise AssertionError(operation+' returned '+str(reply[0])+' instead of '+str(status))
                return reply
            def session(reply):
                expect_status(reply,200,'Account sign-in');value=reply[1]
                return {'cookie':reply[2]['Set-Cookie'].split(';',1)[0],'csrf':value['csrf'],'ownerId':value['ownerId']}
            def start(index,origin,epoch,ready=True):
                name='timekeeper-email-api-'+str(index)+'-'+suffix
                if name in names:command('docker','rm','-f',name);names.remove(name)
                environment={**mail_environment,'TIMEKEEPER_SERVER_DB_URL':'jdbc:postgresql://127.0.0.1:5432/postgres',
                    'TIMEKEEPER_SERVER_DB_USER':'postgres','TIMEKEEPER_SERVER_DB_PASSWORD':password,
                    'TIMEKEEPER_SERVER_TOKENS_FILE':'/config/tokens.json','TIMEKEEPER_SERVER_SETUP_FILE':'/config/setup.json',
                    'TIMEKEEPER_PUBLIC_ORIGIN':origin,'TIMEKEEPER_AUTH_ORIGIN_EPOCH':str(epoch),'QUARKUS_HTTP_PORT':str(8080+index)}
                env_file=folder/('api-'+str(index)+'.env');env_file.write_text(''.join(key+'='+value+'\n' for key,value in environment.items()));env_file.chmod(0o600)
                command('docker','run','-d','--name',name,'--network','container:'+database,'--read-only','--tmpfs','/tmp',
                    '--env-file',str(env_file),'-v',str(config)+':/config:ro',args.server_image);names.append(name)
                if not ready:
                    result=command('docker','wait',name)
                    if result.stdout.strip()=='0':raise AssertionError('Stale origin replica did not fail startup')
                    return
                for _ in range(160):
                    try:
                        with urlopen(bases[index]+'/q/health/ready',timeout=1) as response:
                            if response.status==200:return
                    except (OSError,HTTPError):pass
                    time.sleep(.1)
                raise RuntimeError('Isolated recovery replica did not become ready')
            def messages():
                script="import json;from urllib.request import urlopen;env=json.load(open('/tmp/fixture.json'));print(urlopen(env['TIMEKEEPER_WEB_MAIL_FIXTURE']).read().decode())"
                return json.loads(command('docker','exec',smtp,'python','-c',script).stdout)
            def links(address,purpose,origin):
                for _ in range(100):
                    found=[link for message in messages() if address in message['recipients'] for link in message['links'] if link.startswith(origin+'/web/#'+purpose+'/')]
                    if found:return found[-1].split('/')[-1]
                    time.sleep(.1)
                raise AssertionError('Expected email was not delivered by the private SMTP fixture')
            def verify(address,index=2):
                secret=links(address,'verify-email',vpn)
                expect_status(api(index,'/auth/email/preview',{'token':secret,'purpose':'VERIFY'}),204,'Verification preview')
                expect_status(api(index,'/auth/email/verify',{'token':secret}),204,'Email verification')
            start(1,vpn,1);start(2,vpn,1)
            alice=session(api(1,'/auth/setup',{'code':code,'username':'alice','password':account_password,'displayName':'Alice','email':'alice@example.invalid'}))
            verify('alice@example.invalid')
            expect_status(api(1,'/auth/users',{'username':'bob','password':account_password,'displayName':'Bob','administrator':False,'email':'bob@example.invalid'},alice),201,'Independent user creation')
            bob=session(api(2,'/auth/login',{'username':'bob','password':account_password,'remember':False}))
            alice_other=session(api(2,'/auth/login',{'username':'alice','password':account_password,'remember':False}))
            for index,owner in ((1,alice),(2,bob)):
                expect_status(api(index,'/projects',{'name':'Private project'},owner),201,'Private project creation')
            before={owner['ownerId']:expect_status(api(1,'/data/export',session=owner),200,'Owner export')[1] for owner in (alice,bob)}
            address=expect_status(api(1,'/auth/email/addresses/BACKUP',session=alice),200,'Backup state')
            expect_status(api(1,'/auth/email/addresses/BACKUP',{'email':'backup@example.invalid','currentPassword':account_password},alice,method='PUT',headers={'If-Match':address[2]['ETag']}),200,'Backup enrollment')
            verify('backup@example.invalid')
            token=expect_status(api(2,'/auth/tokens',{'name':'Eclipse','permissions':['read','write'],'expiresAt':None},alice_other),201,'API token creation')[1]['token']
            expect_status(api(1,'/auth/email/forgot',{'username':'alice'}),202,'Initial reset request')
            old=links('alice@example.invalid','reset-password',vpn)
            command('docker','restart','timekeeper-email-api-1-'+suffix)
            for _ in range(100):
                try:
                    if api(1,'/auth/email/preview',{'token':old,'purpose':'RESET'})[0]==204:break
                except OSError:pass
                time.sleep(.1)
            else:raise AssertionError('Restart lost an unconsumed email link')
            # Cut over one actual replica. The old hostname is never contacted.
            start(1,public,2)
            expect_status(api(1,'/auth/email/preview',{'token':old,'purpose':'RESET'}),401,'Retired link on new origin')
            expect_status(api(2,'/auth/email/preview',{'token':old,'purpose':'RESET'}),503,'Stale running replica')
            expect_status(api(2,'/auth/email/forgot',{'username':'alice'}),202,'Stale generic reset response')
            # A configuration rollback cannot revive the retired epoch.
            start(2,vpn,1,ready=False);start(2,public,2)
            expect_status(api(2,'/auth/email/addresses/PRIMARY',session=alice),200,'Preserved primary address')
            expect_status(api(2,'/auth/email/forgot',{'username':'alice'}),202,'New-origin reset request')
            primary=links('alice@example.invalid','reset-password',public);backup=links('backup@example.invalid','reset-password',public)
            for index,secret in ((1,primary),(2,backup)):
                expect_status(api(index,'/auth/email/preview',{'token':secret,'purpose':'RESET'}),204,'Scanner-safe preview')
            with ThreadPoolExecutor(max_workers=2) as pool:
                outcomes=list(pool.map(lambda item:api(item[0],'/auth/email/reset',{'token':item[1],'password':new_password})[0],((1,primary),(2,backup))))
            if sorted(outcomes)!=[204,401]:raise AssertionError('Concurrent replicas did not consume recovery exactly once')
            for index,owner in ((1,alice),(2,alice_other)):
                expect_status(api(index,'/auth/session',session=owner),401,'Old owner session revocation')
            expect_status(api(2,'/me',headers={'Authorization':'Bearer '+token}),401,'Owner API token revocation')
            resumed=session(api(1,'/auth/login',{'username':'alice','password':new_password,'remember':False}))
            if resumed['ownerId']!=alice['ownerId']:raise AssertionError('Recovery changed the stable account owner')
            for owner in (resumed,bob):
                if expect_status(api(2,'/data/export',session=owner),200,'Unchanged owner data')[1]!=before[owner['ownerId']]:raise AssertionError('Recovery changed private work data')
            print(json.dumps({'image':args.server_image,'twoActualReplicas':True,'sharedPostgreSQL18':True,'actualSmtpDelivery':True,
                'independentAccountDataPreserved':True,'linksSurviveRestart':True,'retiredHostnameNotRequired':True,
                'staleRunningAndRestartedReplicasFenced':True,'newOriginLinksUseVerifiedMailboxes':True,
                'concurrentPrimaryBackupResetHasOneWinner':True,'ownerSessionsAndApiTokensRevoked':True}),flush=True)
        finally:
            for name in reversed(names):command('docker','rm','-f','-v',name,check=False)


if __name__=='__main__':main()
