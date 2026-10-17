#!/usr/bin/env python3
"""Signed WebAuthn operations against independent JVMs sharing real PostgreSQL.

The HTTP clients model the trusted TLS proxy's canonical Origin and browser
cookies. Real HTTPS/browser interoperability is tested separately. All accounts,
authenticator private keys, databases and Docker networks are disposable.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
import hashlib
from http.cookies import SimpleCookie
import ipaddress
import json
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from configure_accounts import create_challenge

ROOT=Path(__file__).resolve().parents[2]
PASSWORD='Initial isolated passkey account password'
RECOVERED='Recovered isolated passkey account password'
CHANGED='Changed isolated passkey account password'
VPN='https://vpn.example.invalid:8443'
PORT='https://vpn.example.invalid:9443'
PUBLIC='https://public.example.invalid'


def encoded(value):return base64.urlsafe_b64encode(value).decode().rstrip('=')


def cbor(value):
    """Minimal fixture encoder; the production verifier is Yubico's library."""
    def header(major,length):
        if length<24:return bytes([major*32+length])
        if length<256:return bytes([major*32+24,length])
        return bytes([major*32+25])+length.to_bytes(2,'big')
    if isinstance(value,int):return header(0,value) if value>=0 else header(1,-1-value)
    if isinstance(value,bytes):return header(2,len(value))+value
    if isinstance(value,str):return header(3,len(value.encode()))+value.encode()
    if isinstance(value,dict):return header(5,len(value))+b''.join(cbor(key)+cbor(item) for key,item in value.items())
    raise ValueError('Unsupported authenticator fixture value')


class Authenticator:
    def __init__(self,folder):
        self.credential=secrets.token_bytes(32);self.handle=None;self.rp=None
        self.private=Path(folder)/('authenticator-'+secrets.token_hex(8)+'.pem')
        key=subprocess.run(['openssl','genpkey','-algorithm','EC','-pkeyopt','ec_paramgen_curve:P-256'],capture_output=True,check=True).stdout
        self.private.write_bytes(key);self.private.chmod(0o600)
        public=subprocess.run(['openssl','pkey','-pubout','-outform','DER'],input=key,capture_output=True,check=True).stdout[-65:]
        if len(public)!=65 or public[0]!=4:raise AssertionError('Fixture did not produce a P-256 public key')
        self.public=cbor({1:2,3:-7,-1:1,-2:public[1:33],-3:public[33:]})
    def __repr__(self):return 'Synthetic authenticator'
    def registration(self,options,origin):
        self.handle=options['publicKey']['user']['id'];self.rp=options['publicKey']['rp']['id']
        client=json.dumps({'type':'webauthn.create','challenge':options['publicKey']['challenge'],'origin':origin,'crossOrigin':False},separators=(',',':')).encode()
        data=hashlib.sha256(self.rp.encode()).digest()+bytes([0x5d])+bytes(4)+bytes(16)+len(self.credential).to_bytes(2,'big')+self.credential+self.public
        return {'ceremony':options['ceremony'],'credential':{'id':encoded(self.credential),'rawId':encoded(self.credential),'type':'public-key',
            'response':{'clientDataJSON':encoded(client),'attestationObject':encoded(cbor({'fmt':'none','authData':data,'attStmt':{}})),'transports':['internal']},
            'clientExtensionResults':{'credProps':{'rk':True}},'authenticatorAttachment':'platform'}}
    def assertion(self,options,origin,*,corrupt=False):
        client=json.dumps({'type':'webauthn.get','challenge':options['publicKey']['challenge'],'origin':origin,'crossOrigin':False},separators=(',',':')).encode()
        # Synced/discoverable fixture: UP+UV+BE+BS with a legitimate zero counter.
        data=hashlib.sha256(self.rp.encode()).digest()+bytes([0x1d])+bytes(4)
        signature=subprocess.run(['openssl','dgst','-sha256','-sign',str(self.private)],input=data+hashlib.sha256(client).digest(),capture_output=True,check=True).stdout
        if corrupt:signature=signature[:-1]+bytes([signature[-1]^1])
        return {'ceremony':options['ceremony'],'credential':{'id':encoded(self.credential),'rawId':encoded(self.credential),'type':'public-key',
            'response':{'clientDataJSON':encoded(client),'authenticatorData':encoded(data),'signature':encoded(signature),'userHandle':self.handle},
            'clientExtensionResults':{},'authenticatorAttachment':'platform'}}


def expect(reply,status,operation):
    if reply[0]!=status:raise AssertionError(f'{operation}: expected HTTP {status}, received {reply[0]} ({(reply[1] or {}).get("code","no error code")})')
    return reply[1]


@dataclass(repr=False)
class Browser:
    cookies:dict=field(default_factory=dict)
    csrf:str|None=None
    owner:str|None=None
    def __repr__(self):return 'Disposable browser context'
    def clone(self):return Browser(dict(self.cookies),self.csrf,self.owner)


class Replicas:
    def __init__(self,args,folder):
        self.args=args;self.folder=Path(folder);self.suffix=secrets.token_hex(5)
        self.network='timekeeper-passkeys-'+self.suffix;self.database=self.network+'-db'
        self.names=[];self.instances={};self.origins={};self.bases={};self.addresses={};self.generation=0;self.network_created=False
        self.password=secrets.token_urlsafe(32);self.app_password=secrets.token_urlsafe(32)
        self.config=self.folder/'config';self.config.mkdir(mode=0o755)
        (self.config/'tokens.json').write_text('[]\n');(self.config/'tokens.json').chmod(0o644)
        self.code,_=create_challenge(self.config/'setup.json');(self.config/'setup.json').chmod(0o644)
    def command(self,*arguments,input=None,output=None,check=True):
        result=subprocess.run(arguments,input=input,text=True,stdout=output if output is not None else subprocess.PIPE,stderr=subprocess.PIPE,timeout=90)
        if check and result.returncode:raise RuntimeError('Disposable passkey fixture command failed: '+arguments[0])
        return result
    def initialize(self):
        # A dedicated bridge isolates these containers; PostgreSQL has no host
        # port. --internal suppresses published loopback API ports on Docker Desktop.
        self.command('docker','network','create',self.network);self.network_created=True
        ranges=json.loads(self.command('docker','network','inspect','--format','{{json .IPAM.Config}}',self.network).stdout)
        self.subnet=ipaddress.ip_network(next(item['Subnet'] for item in ranges if ':' not in item['Subnet']))
        env=self.folder/'postgres.env';env.write_text('POSTGRES_PASSWORD='+self.password+'\n');env.chmod(0o600)
        self.command('docker','run','-d','--name',self.database,'--network',self.network,'--env-file',str(env),self.args.postgres_image);self.names.append(self.database)
        for _ in range(120):
            if self.command('docker','exec',self.database,'pg_isready','-h','127.0.0.1','-U','postgres',check=False).returncode==0:break
            time.sleep(.1)
        else:raise RuntimeError('Disposable PostgreSQL did not start')
        self.command('docker','exec','-i',self.database,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','postgres',input=
            "CREATE ROLE timekeeper LOGIN PASSWORD '"+self.app_password+"'; CREATE DATABASE timekeeper OWNER timekeeper;")
        self.start(1,VPN,1);self.start(2,VPN,1)
    def start(self,index,origin,epoch,*,stale=False):
        old=self.instances.get(index)
        if old:self.command('docker','rm','-f',old);self.names.remove(old)
        self.generation+=1;name=self.network+'-api-'+str(index)+'-'+str(self.generation)
        self.addresses[index]=str(self.subnet.network_address+10+self.generation)
        environment={'TIMEKEEPER_SERVER_DB_URL':'jdbc:postgresql://'+self.database+':5432/timekeeper',
            'TIMEKEEPER_SERVER_DB_USER':'timekeeper','TIMEKEEPER_SERVER_DB_PASSWORD':self.app_password,
            'TIMEKEEPER_SERVER_TOKENS_FILE':'/config/tokens.json','TIMEKEEPER_SERVER_SETUP_FILE':'/config/setup.json',
            'TIMEKEEPER_PUBLIC_ORIGIN':origin,'TIMEKEEPER_AUTH_ORIGIN_EPOCH':str(epoch),'QUARKUS_HTTP_PORT':'8080'}
        env=self.folder/('api-'+str(self.generation)+'.env');env.write_text(''.join(key+'='+value+'\n' for key,value in environment.items()));env.chmod(0o600)
        self.command('docker','run','-d','--name',name,'--network',self.network,'--ip',self.addresses[index],
            '-p','127.0.0.1::8080','--read-only','--tmpfs','/tmp','--env-file',str(env),'-v',str(self.config)+':/config:ro',self.args.server_image)
        self.names.append(name);self.instances[index]=name;self.origins[index]=origin
        self.bases[index]='http://127.0.0.1:'+self.command('docker','port',name,'8080/tcp').stdout.strip().rsplit(':',1)[1]
        if stale:
            if self.command('docker','wait',name).stdout.strip()=='0':raise AssertionError('A stale authentication epoch started successfully')
            return
        for _ in range(160):
            try:
                with urlopen(self.bases[index]+'/q/health/ready',timeout=1) as response:
                    if response.status==200:return
            except OSError:pass
            time.sleep(.1)
        raise RuntimeError('Disposable passkey replica did not become ready')
    def request(self,index,browser,path,data=None,*,method=None,headers=None,update=True):
        selected={'Origin':self.origins[index],**(headers or {})}
        if browser.cookies:selected['Cookie']='; '.join(key+'='+value for key,value in browser.cookies.items())
        if browser.csrf:selected['X-CSRF-Token']=browser.csrf
        if browser.owner:selected['X-Timekeeper-Owner']=encoded(browser.owner.encode())
        if data is not None:selected['Content-Type']='application/json'
        request=Request(self.bases[index]+'/api/v1'+path,data=None if data is None else json.dumps(data).encode(),headers=selected,method=method or ('GET' if data is None else 'POST'))
        try:reply=urlopen(request,timeout=20)
        except HTTPError as failure:reply=failure
        with reply:
            raw=reply.read();body=json.loads(raw) if raw else None
            if update:
                for header in reply.headers.get_all('Set-Cookie',[]):
                    parsed=SimpleCookie();parsed.load(header)
                    for key,morsel in parsed.items():
                        if morsel['max-age']=='0':browser.cookies.pop(key,None)
                        else:browser.cookies[key]=morsel.value
                if reply.status==200 and isinstance(body,dict) and 'csrf' in body and 'ownerId' in body:browser.csrf=body['csrf'];browser.owner=body['ownerId']
            return reply.status,body,reply.headers
    def browser(self,index=1):
        value=Browser();expect(self.request(index,value,'/auth/status'),200,'Browser binding status')
        if '__Host-timekeeper-browser' not in value.cookies:raise AssertionError('Server did not issue its browser binding cookie')
        return value
    def password_login(self,username,password=PASSWORD,index=1):
        browser=self.browser(index);expect(self.request(index,browser,'/auth/login',{'username':username,'password':password,'remember':False}),200,'Password sign-in');return browser
    def enroll(self,browser,key,password=PASSWORD):
        options=expect(self.request(1,browser,'/auth/passkeys/register/options',{'name':'Synthetic replicated passkey','currentPassword':password}),200,'Registration options on first replica')
        return expect(self.request(2,browser,'/auth/passkeys/register/finish',key.registration(options,self.origins[1])),201,'Registration finish on other replica')
    def login_options(self,browser,index=1,remember=False):return expect(self.request(index,browser,'/auth/passkeys/login/options',{'remember':remember}),200,'Passkey sign-in options')
    def key_login(self,key,index=2,remember=False):
        browser=self.browser();options=self.login_options(browser,remember=remember)
        expect(self.request(index,browser,'/auth/passkeys/login/finish',key.assertion(options,self.origins[1])),200,'Passkey sign-in on other replica');return browser
    def private_data(self,browser):return expect(self.request(2,browser,'/data/export'),200,'Private owner export')
    def race(self,operations):
        barrier=threading.Barrier(len(operations))
        def run(operation):barrier.wait(timeout=10);return operation()
        with ThreadPoolExecutor(max_workers=len(operations)) as pool:return list(pool.map(run,operations))
    def backup(self):
        for name in self.instances.values():self.command('docker','stop',name)
        path=self.folder/'protected.dump'
        with path.open('wb') as output:self.command('docker','exec',self.database,'pg_dump','-U','timekeeper','-d','timekeeper','--format=custom',output=output)
        path.chmod(0o600)
        for index in (1,2):self.start(index,VPN,1)
        return path
    def restore(self,path):
        for name in self.instances.values():self.command('docker','stop',name)
        with path.open('rb') as source:
            result=subprocess.run(['docker','exec','-i',self.database,'pg_restore','-U','timekeeper','-d','timekeeper','--clean','--if-exists','--no-owner','--single-transaction','--exit-on-error'],stdin=source,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=60)
        if result.returncode:raise RuntimeError('Protected passkey backup restore failed')
        for index in (1,2):self.start(index,VPN,1)
    def recover_code(self,owner):
        code,_=create_challenge(self.config/'setup.json',purpose='RECOVERY',owner=owner,replace=True);(self.config/'setup.json').chmod(0o644)
        # Challenge files are loaded at initialization on each independent JVM.
        for index in (1,2):self.start(index,VPN,1)
        return code
    def close(self):
        for name in reversed(self.names):self.command('docker','rm','-f','-v',name,check=False)
        if self.network_created:self.command('docker','network','rm',self.network,check=False)


def exercise(system):
    checks=[]
    def passed(name):checks.append(name);print('PASS: '+name,flush=True)
    alice=system.browser();expect(system.request(1,alice,'/auth/setup',{'code':system.code,'username':'alice','password':PASSWORD,'displayName':'Alice','email':'alice@example.invalid'}),200,'First administrator setup')
    versions=[expect(system.request(index,alice,'/server'),200,'Replica build identity') for index in (1,2)]
    system.server_commit=versions[0]['commit']
    if any(value['commit']!=system.server_commit or value['dirty'] for value in versions):raise AssertionError('Replica build identities are inconsistent or dirty')
    if system.args.expected_commit and system.server_commit!=system.args.expected_commit:raise AssertionError('Replica does not run the expected source commit')
    expect(system.request(2,alice,'/auth/users',{'username':'bob','password':PASSWORD,'displayName':'Bob','administrator':False,'email':'bob@example.invalid'}),201,'Independent account creation')
    bob=system.password_login('bob',index=2)
    for browser in (alice,bob):
        client=expect(system.request(1,browser,'/clients',{'name':'Owner private client','color':'#246bad'}),201,'Private client')
        expect(system.request(2,browser,'/work-sessions',{'clientId':client['id'],'title':'Private closed interval','summary':'No shared accounts','start':'2026-10-12T07:00:00.123456789Z','end':'2026-10-12T15:00:00.987654321Z'}),201,'Private work session')
    private={browser.owner:system.private_data(browser) for browser in (alice,bob)}
    first=Authenticator(system.folder);second=Authenticator(system.folder)
    first_record=system.enroll(alice,first);system.enroll(alice,second)
    if first.handle!=second.handle:raise AssertionError('Opaque account handle changed across credentials')
    signed=system.key_login(first,remember=True)
    expect(system.request(1,signed,'/auth/session'),200,'Remembered passkey session on first replica')
    if signed.owner!=alice.owner:raise AssertionError('Passkey changed the stable owner')
    expect(system.request(2,bob,'/auth/passkeys/'+first_record['id'],{'name':'Foreign rename'},method='PUT',headers={'If-Match':'"0"'}),404,'Foreign owner credential isolation')
    passed('registration and discoverable sign-in cross replicas with stable opaque ownership')

    concurrent=system.browser();options=system.login_options(concurrent);assertion=first.assertion(options,VPN)
    contexts=[concurrent.clone(),concurrent.clone()]
    results=system.race([lambda i=i:system.request(i+1,contexts[i],'/auth/passkeys/login/finish',assertion) for i in range(2)])
    if sorted(reply[0] for reply in results)!=[200,401]:raise AssertionError('Concurrent completion did not have one winner')
    winner=contexts[next(index for index,reply in enumerate(results) if reply[0]==200)]
    expect(system.request(2,winner,'/auth/session'),200,'Winning concurrent session')
    expect(system.request(1,concurrent,'/auth/passkeys/login/finish',assertion),401,'Consumed ceremony replay')
    invalid=system.browser();options=system.login_options(invalid)
    expect(system.request(2,invalid,'/auth/passkeys/login/finish',first.assertion(options,VPN,corrupt=True)),401,'Invalid signature')
    expect(system.request(1,invalid,'/auth/passkeys/login/finish',first.assertion(options,VPN)),401,'Invalid signature consumed the challenge')
    passed('one-use challenges and rejected signatures commit across independent JVMs')

    moving=system.browser();pending=system.login_options(moving);old_address=system.addresses[1]
    system.start(1,VPN,1)
    if system.addresses[1]==old_address:raise AssertionError('Network-change fixture did not move the backend IP')
    expect(system.request(1,moving,'/auth/passkeys/login/finish',second.assertion(pending,VPN)),200,'Pending sign-in after backend IP/port change')
    expect(system.request(1,signed,'/auth/session'),200,'Existing session after backend IP/port change')
    passed('backend IP and proxy target changes preserve the unchanged public RP and origin')

    backup=system.backup()
    keys=expect(system.request(2,alice,'/auth/passkeys'),200,'Credential list')['items']
    key=next(value for value in keys if value['id']==first_record['id'])
    racing=system.browser();options=system.login_options(racing);assertion=first.assertion(options,VPN)
    late=system.browser();late_options=system.login_options(late)
    results=system.race([
        lambda:system.request(1,racing,'/auth/passkeys/login/finish',assertion),
        lambda:system.request(2,alice,'/auth/passkeys/'+key['id'],{'currentPassword':PASSWORD},method='DELETE',headers={'If-Match':key['etag']})])
    expect(results[1],200,'Concurrent credential removal')
    if results[0][0] not in (200,401):raise AssertionError('Unexpected sign-in/removal race result')
    for browser in (racing,signed,winner):expect(system.request(1,browser,'/auth/session'),401,'Removed credential session revocation')
    expect(system.request(2,late,'/auth/passkeys/login/finish',first.assertion(late_options,VPN)),401,'Completion after credential removal')
    expect(system.request(2,alice,'/auth/session'),200,'Independent password session survives key removal')
    system.key_login(second)
    passed('credential removal fences concurrent sign-in and immediately revokes every key-backed session')

    system.restore(backup)
    restored=system.key_login(first)
    if restored.owner!=alice.owner:raise AssertionError('Restored credential changed owners')
    for browser in (alice,bob):
        if system.private_data(browser)!=private[browser.owner]:raise AssertionError('Protected restore changed private work records')
    passed('protected restore preserves credentials and demonstrates the documented old-revocation resurrection risk')

    code=system.recover_code(alice.owner)
    uncommitted=Authenticator(system.folder)
    registration=expect(system.request(1,alice,'/auth/passkeys/register/options',{'name':'Must not survive recovery','currentPassword':PASSWORD}),200,'Pending registration before recovery')
    registration_body=uncommitted.registration(registration,VPN)
    pending_browser=system.browser();options=system.login_options(pending_browser);assertion=second.assertion(options,VPN)
    recovery_browser=system.browser()
    results=system.race([
        lambda:system.request(1,pending_browser,'/auth/passkeys/login/finish',assertion),
        lambda:system.request(2,recovery_browser,'/auth/recover',{'code':code,'username':'alice','password':RECOVERED,'displayName':'Alice'})])
    expect(results[1],200,'Concurrent operator recovery')
    if results[0][0] not in (200,401):raise AssertionError('Unexpected recovery/sign-in race result')
    for browser in (pending_browser,restored,alice):expect(system.request(1,browser,'/auth/session'),401,'Recovery revoked prior sessions')
    expect(system.request(1,alice,'/auth/passkeys/register/finish',registration_body),401,'Recovery rejected unfinished registration')
    for authenticator in (first,second):
        browser=system.browser();options=system.login_options(browser)
        expect(system.request(1,browser,'/auth/passkeys/login/finish',authenticator.assertion(options,VPN)),401,'Recovery revoked prior passkeys')
    if expect(system.request(1,recovery_browser,'/auth/passkeys'),200,'Recovered key list')['items']:raise AssertionError('Recovery left an active passkey')
    expect(system.request(2,bob,'/auth/session'),200,'Recovery leaves the other account authorized')
    alice=recovery_browser;replacement=Authenticator(system.folder);system.enroll(alice,replacement,RECOVERED)
    passed('operator recovery wins over concurrent passkey completion and preserves the other account')

    old_browser=system.browser();old_options=system.login_options(old_browser)
    old_session=system.key_login(replacement)
    system.start(2,PORT,2)
    expect(system.request(1,old_browser,'/auth/passkeys/login/finish',replacement.assertion(old_options,VPN)),503,'Stale running replica ceremony')
    expect(system.request(2,old_session,'/auth/session'),401,'Port-cutover retired session')
    expect(system.request(2,old_browser,'/auth/passkeys/login/finish',replacement.assertion(old_options,VPN)),401,'Port-cutover retired ceremony')
    system.start(1,VPN,1,stale=True);system.start(1,PORT,2)
    alice=system.password_login('alice',RECOVERED)
    port_session=system.key_login(replacement)
    keys=expect(system.request(2,alice,'/auth/passkeys'),200,'Same-RP key list')['items']
    if not all(key['usableHere'] for key in keys):raise AssertionError('A port-only change disabled compatible RP credentials')
    passed('same-host port cutover retains compatible keys while retiring sessions, ceremonies and stale replicas')

    retired=system.browser();retired_options=system.login_options(retired)
    system.start(2,PUBLIC,3)
    expect(system.request(1,retired,'/auth/passkeys/login/finish',replacement.assertion(retired_options,PORT)),503,'Retired RP replica')
    expect(system.request(2,port_session,'/auth/session'),401,'Retired hostname session')
    system.start(1,PUBLIC,3)
    alice=system.password_login('alice',RECOVERED)
    old_keys=expect(system.request(2,alice,'/auth/passkeys'),200,'Old-RP credential management')['items']
    if not old_keys or any(key['usableHere'] for key in old_keys):raise AssertionError('Old-RP keys were deleted or presented as compatible')
    incompatible=system.browser();options=system.login_options(incompatible)
    expect(system.request(2,incompatible,'/auth/passkeys/login/finish',replacement.assertion(options,PUBLIC)),401,'Unrelated RP credential rejection')
    public_key=Authenticator(system.folder);system.enroll(alice,public_key,RECOVERED)
    if replacement.handle!=public_key.handle:raise AssertionError('Changing RP changed stable owner handle')
    public_session=system.key_login(public_key)
    if public_session.owner!=alice.owner:raise AssertionError('Hostname migration changed owner')
    bob=system.password_login('bob')
    for browser in (alice,bob):
        if system.private_data(browser)!=private[browser.owner]:raise AssertionError('Hostname migration changed private work data')
    passed('unrelated hostname migration uses password fallback and replacement enrollment without the old host')

    expect(system.request(1,alice,'/auth/password',{'currentPassword':RECOVERED,'newPassword':CHANGED}),204,'Normal password change')
    expect(system.request(2,public_session,'/auth/session'),401,'Password change session revocation')
    final=system.key_login(public_key)
    if final.owner!=alice.owner:raise AssertionError('Password change broke retained passkeys')
    passed('normal password change retains passkeys while ending existing sessions')

    rollback=system.browser();options=system.login_options(rollback)
    system.start(2,VPN,4)
    expect(system.request(2,rollback,'/auth/passkeys/login/finish',public_key.assertion(options,PUBLIC)),401,'Rollback retired public-origin ceremony')
    expect(system.request(1,final,'/auth/session'),401,'Rollback revoked the old public-origin session')
    expect(system.request(1,rollback,'/auth/passkeys/login/options',{'remember':False}),503,'Old public-origin replica cannot start another ceremony')
    system.start(1,VPN,4)
    alice=system.password_login('alice',CHANGED)
    compatible=system.key_login(replacement)
    if compatible.owner!=alice.owner:raise AssertionError('Returning to the old RP changed owner')
    for authenticator in (first,second):
        browser=system.browser();options=system.login_options(browser)
        expect(system.request(1,browser,'/auth/passkeys/login/finish',authenticator.assertion(options,VPN)),401,'Rollback must not revive a recovered credential')
    keys=expect(system.request(2,alice,'/auth/passkeys'),200,'Rollback credential management')['items']
    if not any(key['rpId']=='public.example.invalid' and not key['usableHere'] for key in keys):raise AssertionError('Rollback relabeled the newer RP credential')
    if system.private_data(alice)!=private[alice.owner]:raise AssertionError('Origin rollback changed private work data')
    passed('returning to an old hostname uses a newer epoch without reviving revoked credentials')
    return checks


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-image',required=True)
    parser.add_argument('--postgres-image',default='postgres:18.6-bookworm')
    parser.add_argument('--expected-commit',help='Assert the immutable source commit reported by both JVMs')
    parser.add_argument('--output',type=Path,default=ROOT/'server/target/passkey-replica-acceptance.json')
    args=parser.parse_args()
    # A failed rerun must not leave an older successful receipt at this path.
    args.output.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='timekeeper-passkey-replicas-') as folder:
        system=Replicas(args,folder)
        try:
            system.initialize();checks=exercise(system)
            args.output.parent.mkdir(parents=True,exist_ok=True)
            image_id=system.command('docker','image','inspect','--format','{{.Id}}',args.server_image).stdout.strip()
            args.output.write_text(json.dumps({'serverImage':args.server_image,'imageId':image_id,'serverCommit':system.server_commit,'postgresImage':args.postgres_image,'twoActualJVMs':True,
                'syntheticAuthenticators':True,'trustedProxyOriginFixture':True,'checks':checks,'passed':True},indent=2)+'\n')
        finally:system.close()


if __name__=='__main__':main()
