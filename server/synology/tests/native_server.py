"""Exercise the DSM project creation/postinst/start order with the actual server.

A shared Linux network namespace stands in for DSM host networking. It does not
emulate Package Center or the Container Manager resource worker.
"""
import json
from pathlib import Path
import shutil
import time


def verify(command,database_container,folder,image):
    folder=Path(folder)
    project=folder/'rendered-project.json'
    command('docker','cp',database_container+':/var/packages/timekeeper/target/project/compose.yaml',str(project))
    definition=json.loads(project.read_text());service=definition['services']['server']
    prepared=folder/'prepared-config';password=folder/'native-password'
    command('docker','cp',database_container+':/var/packages/timekeeper/target/private/config',str(prepared))
    command('docker','cp',database_container+':/var/packages/timekeeper/target/private/secrets/database_password',str(password))
    config=folder/'native-config';secrets=folder/'native-secrets'
    config.mkdir(mode=0o755);secrets.mkdir(mode=0o755)
    name=database_container+'-server'
    # Only adapt the fixture's image, host namespace and bind sources. The static
    # service environment, health check and all container restrictions stay intact.
    service['image']=image;service['network_mode']='container:'+database_container;service['container_name']=name
    for volume in service['volumes']:
        volume['source']=str(config if volume['target']=='/config' else secrets)
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
        for username,owner in (('account0','owner-a'),('account1','owner-β')):
            response=command('docker','exec','-i',name,'curl','-fsS','-H','Content-Type: application/json',
                '--data-binary','@-',endpoint+'/api/v1/auth/login',
                input=json.dumps({'username':username,'password':'migration password fixture','remember':False}))
            if json.loads(response.stdout)['ownerId']!=owner:raise AssertionError('Migrated login changed data ownership')
        status=json.loads(command('docker','exec',name,'curl','-fsS',endpoint+'/api/v1/auth/status').stdout)
        if status['setupRequired']:raise AssertionError('Restored server reopened administrator setup')
        return {'projectCreatedBeforeRuntimeConfiguration':True,'renderedNativeServerReady':True,'selectedRuntimePortHealthcheck':True,
                'migratedPasswordsAuthenticateAsOriginalOwners':True,'actualContainerRefusesPendingMaintenance':True}
    finally:
        command(*compose,'down',check=False)
