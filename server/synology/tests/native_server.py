"""Exercise the DSM project creation/postinst/start order with the actual server.

A shared Linux network namespace stands in for DSM host networking. It does not
emulate Package Center or the Container Manager resource worker.
"""
import json
from http.cookies import SimpleCookie
import re
from pathlib import Path
import shutil
import time
from urllib.parse import urlsplit


def verify(command,database_container,folder,image):
    folder=Path(folder)
    project=folder/'rendered-project.json'
    command('docker','cp',database_container+':/var/packages/timekeeper/target/project/compose.yaml',str(project))
    definition=json.loads(project.read_text());service=definition['services']['server']
    prepared=folder/'prepared-config';password=folder/'native-password';smtp_password=folder/'native-smtp-password'
    command('docker','cp',database_container+':/var/packages/timekeeper/target/private/config',str(prepared))
    command('docker','cp',database_container+':/var/packages/timekeeper/target/private/secrets/database_password',str(password))
    command('docker','cp',database_container+':/var/packages/timekeeper/target/private/secrets/smtp_password',str(smtp_password))
    config=folder/'native-config';secrets=folder/'native-secrets'
    config.mkdir(mode=0o755);secrets.mkdir(mode=0o755)
    name=database_container+'-server'
    # Only adapt the fixture's image, host namespace and bind sources. The static
    # service environment, health check and all container restrictions stay intact.
    service['image']=image;service['network_mode']='container:'+database_container;service['container_name']=name
    state_volume=database_container+'-instance-state'
    identity=service.get('user','10001:10001')
    if not re.fullmatch(r'[1-9][0-9]*:[1-9][0-9]*',identity):raise AssertionError('Native container needs an explicit non-root package identity')
    command('docker','volume','create',state_volume)
    command('docker','run','--rm','--network','none','--user','0:0','--entrypoint','sh','-v',state_volume+':/state',image,'-ec','mkdir -p /state/server; chown -R '+identity+' /state; chmod 700 /state/server')
    for volume in service['volumes']:
        if volume['target']=='/state':
            volume['type']='volume';volume['source']=state_volume
        else:volume['source']=str(config if volume['target']=='/config' else secrets)
    definition['volumes']={state_volume:{'external':True}}
    project.write_text(json.dumps(definition))
    compose=['docker','compose','-p',database_container+'-app','-f',str(project)]
    def create():command(*compose,'create','--no-build')
    pending=config/'maintenance-pending.json'
    try:
        # WHEN_POSTINST creates the container before postinst writes runtime files.
        create()
        if list(config.iterdir()) or list(secrets.iterdir()):raise AssertionError('Fixture did not create the project with empty runtime directories')
        for path in prepared.iterdir():shutil.copyfile(path,config/path.name);(config/path.name).chmod(0o644)
        shutil.copyfile(password,secrets/'database_password');(secrets/'database_password').chmod(0o444)
        shutil.copyfile(smtp_password,secrets/'smtp_password');(secrets/'smtp_password').chmod(0o444)
        pending.write_text('{}');pending.chmod(0o644)
        command(*compose,'start')
        if command('docker','wait',name).stdout.strip()!='1':raise AssertionError('Pending maintenance allowed server startup')
        logs=command('docker','logs',name)
        if 'maintenance is incomplete' not in logs.stdout+logs.stderr:raise AssertionError('Missing interrupted-maintenance diagnostic')
        command('docker','rm','-f',name);pending.unlink()
        create();command(*compose,'start')
        endpoint='http://127.0.0.1:'+(config/'http-port').read_text().strip()
        for _ in range(120):
            if command('docker','exec',name,'curl','-fsS',endpoint+'/q/health/ready',check=False).returncode==0:break
            time.sleep(.5)
        else:
            logs=command('docker','logs',name)
            diagnostic=(logs.stdout+logs.stderr).replace(password.read_text().strip(),'[redacted]')
            raise AssertionError('Rendered native-PostgreSQL server did not become ready:\n'+diagnostic[-12000:])
        for _ in range(40):
            if command('docker','inspect','--format','{{.State.Health.Status}}',name).stdout.strip()=='healthy':break
            time.sleep(.5)
        else:raise AssertionError('Static Compose healthcheck did not use the selected runtime port')
        properties=dict(line.split('=',1) for line in (config/'runtime.properties').read_text().splitlines() if '=' in line)
        public_host=urlsplit(properties['timekeeper.auth.public-origin']).netloc
        web=command('docker','exec',name,'curl','-fsS','-H','Host: '+public_host,endpoint+'/web/')
        if '<title>Timekeeper</title>' not in web.stdout:raise AssertionError('Public reverse-proxy Host did not serve the web client')
        for host in (public_host,public_host.upper(),'localhost:8443','127.0.0.1:8080','[::1]:8080'):
            response=command('docker','exec',name,'curl','-fsS','-H','Host: '+host,endpoint+'/api/v1/auth/status')
            if 'setupRequired' not in json.loads(response.stdout):raise AssertionError('Allowed Host did not reach account status')
        for host in ('unrelated.example','timekeeper.example.invalid.attacker.example'):
            response=command('docker','exec',name,'curl','-sS','-o','/dev/null','-w','%{http_code}',
                '-H','Host: '+host,endpoint+'/web/')
            if response.stdout!='400':raise AssertionError('Unconfigured Host bypassed the allowlist')
        for username,owner in (('account0','owner-a'),('account1','owner-β')):
            origin=properties['timekeeper.auth.public-origin']
            status=command('docker','exec',name,'curl','-fsS','-D','-','-o','/dev/null','-H','Origin: '+origin,endpoint+'/api/v1/auth/status')
            binding=re.search(r'(?im)^set-cookie:\s*(__Host-timekeeper-browser=[^;\r\n]+)',status.stdout)
            if not binding:raise AssertionError('Configured native server did not establish the browser binding')
            response=command('docker','exec','-i',name,'curl','-fsS','-D','-','-H','Content-Type: application/json','-H','Origin: '+origin,'-H','Cookie: '+binding.group(1),
                '--data-binary','@-',endpoint+'/api/v1/auth/login',
                input=json.dumps({'username':username,'password':'migration password fixture','remember':False}))
            headers,separator,body=response.stdout.partition('\n\n')
            if not separator:raise AssertionError('Missing native login response headers')
            account=json.loads(body)
            if account['ownerId']!=owner:raise AssertionError('Migrated login changed data ownership')
            if username=='account0':
                jar=SimpleCookie();jar.load(binding.group(1))
                for line in headers.splitlines():
                    if line.lower().startswith('set-cookie:'):jar.load(line.split(':',1)[1].strip())
                cookie='; '.join(key+'='+value.value for key,value in jar.items())
                owner_binding=re.search(r'(?im)^x-timekeeper-owner:\s*([^\r\n]+)',headers)
                if not owner_binding:raise AssertionError('Missing native owner binding')
                def api(method,path,data=None):
                    arguments=['docker','exec','-i',name,'curl','-fsS','-X',method,'-H','Origin: '+origin,'-H','Cookie: '+cookie,
                               '-H','X-CSRF-Token: '+account['csrf'],'-H','X-Timekeeper-Owner: '+owner_binding.group(1).strip()]
                    if data is not None:arguments+=['-H','Content-Type: application/json','--data-binary','@-']
                    return json.loads(command(*arguments,endpoint+'/api/v1'+path,input=json.dumps(data) if data is not None else None).stdout)
                summary=api('GET','/admin/instance')
                if not summary['storageConfigured'] or not summary['restoreStorageConfigured']:raise AssertionError('Native persistent instance roots are not configured')
                operation=api('POST','/admin/instance/backups',{'currentPassword':'migration password fixture','passphrase':'Separate native integration backup phrase'})
                for _ in range(120):
                    operation=api('GET','/admin/instance/operations/'+operation['id'])
                    if operation['state']!='PREPARING':break
                    time.sleep(.25)
                if operation['state']!='READY':raise AssertionError('Native encrypted backup did not become ready: '+operation['state'])
                actual=command('docker','exec',name,'stat','-c','%a:%u','/state/server/instance-backup').stdout.strip()
                if actual!='700:'+identity.split(':')[0]:raise AssertionError('Native backup storage has the wrong owner or mode')
        status=json.loads(command('docker','exec',name,'curl','-fsS',endpoint+'/api/v1/auth/status').stdout)
        if status['setupRequired']:raise AssertionError('Restored server reopened administrator setup')
        return {'projectCreatedBeforeRuntimeConfiguration':True,'renderedNativeServerReady':True,'packageAccountContainerIdentity':identity,'nativeEncryptedBackupReady':True,'selectedRuntimePortHealthcheck':True,
                'migratedPasswordsAuthenticateAsOriginalOwners':True,'actualContainerRefusesPendingMaintenance':True,
                'publicReverseProxyHostServesWebClient':True,'unconfiguredHostsRejected':True}
    finally:
        command(*compose,'down',check=False)
        command('docker','volume','rm',state_volume,check=False)
