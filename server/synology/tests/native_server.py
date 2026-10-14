"""Use the rendered DSM environment with the real server and loopback native PostgreSQL.

A shared Linux network namespace stands in for DSM host networking. It does not
emulate Package Center, Data Share or the Container Manager resource worker.
"""
import json
from pathlib import Path
import time


def verify(command,database_container,folder,image):
    folder=Path(folder)
    project=folder/'rendered-project.json'
    command('docker','cp',database_container+':/var/packages/timekeeper/target/project/compose.yaml',str(project))
    service=json.loads(project.read_text())['services']['server']
    config=folder/'native-config'
    password=folder/'native-password'
    command('docker','cp',database_container+':/var/packages/timekeeper/shares/timekeeper-data/server/config',str(config))
    command('docker','cp',database_container+':/var/packages/timekeeper/shares/timekeeper-data/server/secrets/database_password',str(password))
    config.chmod(0o755);password.chmod(0o444)
    environment=folder/'native-server.env'
    environment.write_text(''.join(key+'='+value+'\n' for key,value in service['environment'].items()));environment.chmod(0o600)
    name=database_container+'-server'
    def launch():
        command('docker','run','-d','--name',name,'--network','container:'+database_container,
                '--env-file',str(environment),'--read-only','--tmpfs','/tmp','--cap-drop','ALL',
                '--security-opt','no-new-privileges:true','-v',str(config)+':/config:ro',
                '-v',str(password)+':/run/secrets/database_password:ro',image)
    pending=config/'maintenance-pending.json'
    pending.write_text('{}');pending.chmod(0o644)
    try:
        launch()
        if command('docker','wait',name).stdout.strip()!='1':raise AssertionError('Pending maintenance allowed server startup')
        logs=command('docker','logs',name)
        if 'maintenance is incomplete' not in logs.stdout+logs.stderr:raise AssertionError('Missing interrupted-maintenance diagnostic')
        command('docker','rm',name);pending.unlink()
        launch()
        endpoint='http://127.0.0.1:'+service['environment']['QUARKUS_HTTP_PORT']
        for _ in range(120):
            if command('docker','exec',name,'curl','-fsS',endpoint+'/q/health/ready',check=False).returncode==0:break
            time.sleep(.5)
        else:
            logs=command('docker','logs',name)
            diagnostic=(logs.stdout+logs.stderr).replace(password.read_text().strip(),'[redacted]')
            raise AssertionError('Rendered native-PostgreSQL server did not become ready:\n'+diagnostic[-12000:])
        for username,owner in (('account0','owner-a'),('account1','owner-β')):
            response=command('docker','exec','-i',name,'curl','-fsS','-H','Content-Type: application/json',
                '--data-binary','@-',endpoint+'/api/v1/auth/login',
                input=json.dumps({'username':username,'password':'migration password fixture','remember':False}))
            if json.loads(response.stdout)['ownerId']!=owner:raise AssertionError('Migrated login changed data ownership')
        status=json.loads(command('docker','exec',name,'curl','-fsS',endpoint+'/api/v1/auth/status').stdout)
        if status['setupRequired']:raise AssertionError('Restored server reopened administrator setup')
        return {'renderedNativeServerReady':True,'migratedPasswordsAuthenticateAsOriginalOwners':True,
                'actualContainerRefusesPendingMaintenance':True}
    finally:
        command('docker','rm','-f',name,check=False)
