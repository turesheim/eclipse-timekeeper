"""Shared disposable HTTP data and browser clients for instance migration acceptance.

Canonical Origin and Secure cookies model TLS proxy forwarding to container
loopback. Real HTTPS/browser interoperability is checked separately.
"""
import base64
from email.parser import BytesHeaderParser
import hashlib
from http.cookies import SimpleCookie
import json
import subprocess
import time

ADMIN_PASSWORD='Preserved original administrator password'
COLLEAGUE_PASSWORD='Preserved original colleague password'
DESTINATION_PASSWORD='Disposable destination administrator password'
PASSPHRASE='Separate full instance migration archive passphrase'


class API:
    def __init__(self,container,port,origin):
        self.container,self.port,self.origin=container,port,origin
        self.cookies=SimpleCookie();self.csrf='';self.owner=''

    def request(self,method,path,data=None,headers=None,token=None,expected=None):
        arguments=['docker','exec','-i',self.container,'curl','--silent','--show-error','--max-time','90','--dump-header','-',
                   '--request',method,'--header','Origin: '+self.origin]
        if token:arguments+=['--header','Authorization: Bearer '+token]
        elif self.cookies:
            arguments+=['--header','Cookie: '+'; '.join(key+'='+value.value for key,value in self.cookies.items())]
            if self.csrf:arguments+=['--header','X-CSRF-Token: '+self.csrf]
            if self.owner:arguments+=['--header','X-Timekeeper-Owner: '+self.owner]
        for key,value in (headers or {}).items():arguments+=['--header',key+': '+value]
        body=None
        if data is not None:
            body=data if isinstance(data,bytes) else json.dumps(data).encode()
            arguments+=['--header','Content-Type: '+('application/octet-stream' if isinstance(data,bytes) else 'application/json'),
                        '--data-binary','@-']
        arguments+=['http://127.0.0.1:'+str(self.port)+'/api/v1'+path]
        result=subprocess.run(arguments,input=body,capture_output=True,timeout=100)
        if result.returncode:raise RuntimeError('Isolated native API transport failed')
        head,separator,payload=result.stdout.partition(b'\r\n\r\n')
        if not separator:raise RuntimeError('Missing native API response headers')
        status=int(head.split(b'\r\n',1)[0].split()[1])
        response=BytesHeaderParser().parsebytes(head.split(b'\r\n',1)[1])
        for cookie in response.get_all('Set-Cookie',[]):self.cookies.load(cookie)
        if response.get('X-Timekeeper-Owner'):self.owner=response['X-Timekeeper-Owner']
        value=json.loads(payload) if payload and response.get_content_type()=='application/json' else payload
        if isinstance(value,dict) and value.get('csrf'):self.csrf=value['csrf']
        if status not in (expected if expected is not None else range(200,300)):
            code=value.get('code','') if isinstance(value,dict) else ''
            raise RuntimeError('Native API '+method+' '+path+' returned '+str(status)+' '+str(code))
        return value,response

    def call(self,method,path,data=None,**kwargs):return self.request(method,path,data,**kwargs)[0]
    def login(self,username,password):
        self.call('GET','/auth/status')
        return self.call('POST','/auth/login',{'username':username,'password':password,'remember':False})
    def put(self,path,data):
        _,headers=self.request('GET',path)
        return self.call('PUT',path,data,headers={'If-Match':headers['ETag']})
    def until(self,path,predicate):
        deadline=time.monotonic()+120
        while True:
            value=self.call('GET',path)
            if predicate(value):return value
            if value.get('phase')=='FAILED' or value.get('state') in ('FAILED','INTERRUPTED','EXPIRED') or value.get('operation',{}).get('state') in ('FAILED','INTERRUPTED','EXPIRED'):
                raise RuntimeError('Native instance operation failed')
            if time.monotonic()>deadline:raise RuntimeError('Native instance operation did not finish')
            time.sleep(.2)


def seed(source):
    api=source.api()
    api.call('GET','/auth/status')
    owner=api.call('POST','/auth/setup',{'code':source.code,'username':'original-admin','password':ADMIN_PASSWORD,'displayName':'Original administrator','email':'original-admin@example.org'})['ownerId']
    api.call('POST','/admin/users',{'username':'original-colleague','password':COLLEAGUE_PASSWORD,'displayName':'Original colleague','email':'colleague@example.org','administrator':False})
    client=api.call('POST','/clients',{'name':'Original client æøå','color':'#176653'})
    project=api.call('POST','/projects',{'name':'Original project','clientId':client['id'],'icon':'code'})
    task=api.call('POST','/tasks',{'summary':'Original task β','projectId':project['id'],'parentTaskId':None,'url':None})
    label=api.call('POST','/labels',{'name':'Original activity label','color':'#84a644'})
    session=api.call('POST','/work-sessions',{'summary':'Original work session','clientId':client['id'],'projectId':project['id'],
                    'start':'2026-10-27T08:00:00.123456789Z','end':'2026-10-27T10:00:00.123456789Z'})
    activity=api.call('POST','/activities',{'summary':'Original activity','taskId':task['id'],'projectId':project['id'],'labelIds':[label['id']],
                     'start':'2026-10-27T08:00:00.123456789Z','end':'2026-10-27T09:00:00.123456789Z'})
    attachment='Exact attachment bytes æ\n'.encode()
    api.call('PUT','/journal-assets/migration.txt',{'data':base64.b64encode(attachment).decode()},headers={'If-None-Match':'*'})
    api.put('/day-labels/2026-10-27',{'text':'Independent original label'})
    api.put('/day-notes/2026-10-27/content',{'body':'# Original journal\n\n[Attachment](assets/migration.txt)\n'})
    api.put('/week-notes/2026-W44',{'body':'# Original weekly journal\n'})
    token=api.call('POST','/auth/tokens',{'name':'Original integration token','permissions':['read','write'],'expiresAt':None})['token']
    paths=['/clients/'+client['id'],'/projects/'+project['id'],'/tasks/'+task['id'],'/labels/'+label['id'],
           '/work-sessions/'+session['id'],'/activities/'+activity['id'],'/day-labels/2026-10-27','/day-notes/2026-10-27/content','/week-notes/2026-W44']
    expected={path:api.call('GET',path) for path in paths}
    colleague=source.api();other_owner=colleague.login('original-colleague',COLLEAGUE_PASSWORD)['ownerId']
    other=colleague.call('POST','/clients',{'name':'Other owner private client','color':'#123456'})
    return api,{'owner':owner,'otherOwner':other_owner,'otherClient':other,'expected':expected,'attachment':attachment,'token':token,
                'client':client,'project':project,'task':task,'label':label}


def export(api):
    operation=api.call('POST','/admin/instance/backups',{'currentPassword':ADMIN_PASSWORD,'passphrase':PASSPHRASE})
    path='/admin/instance/operations/'+operation['id']
    api.until(path,lambda value:value['state']=='READY')
    api.call('POST',path+'/download-grant',{'currentPassword':ADMIN_PASSWORD})
    archive=api.call('GET',path+'/content')
    if not isinstance(archive,bytes) or not archive.startswith(b'TKIBK001'):raise RuntimeError('Missing authenticated encrypted source archive')
    return archive


def verify_data(destination,expected):
    api=destination.api();account=api.login('original-admin',ADMIN_PASSWORD)
    if account['ownerId']!=expected['owner']:raise RuntimeError('Restored account ownership changed')
    for path,value in expected['expected'].items():
        if api.call('GET',path)!=value:raise RuntimeError('Restored native record differs: '+path)
    if api.call('GET','/journal-assets/migration.txt')!=expected['attachment']:raise RuntimeError('Native attachment bytes changed')
    if api.call('GET','/clients/'+expected['client']['id'],token=expected['token'])['id']!=expected['client']['id']:raise RuntimeError('Restored integration token failed')
    api.call('GET','/admin/instance',token=expected['token'],expected=(403,))
    colleague=destination.api()
    if colleague.login('original-colleague',COLLEAGUE_PASSWORD)['ownerId']!=expected['otherOwner']:raise RuntimeError('Second account access changed')
    if colleague.call('GET','/clients/'+expected['otherClient']['id'])!=expected['otherClient']:raise RuntimeError('Other owner private data changed')
    colleague.call('GET','/clients/'+expected['client']['id'],expected=(404,))
    colleague.call('GET','/admin/instance',expected=(403,))
    return api

