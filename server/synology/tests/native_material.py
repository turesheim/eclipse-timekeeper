"""Native archive component checks using a representative immutable material inventory.

This does not stand in for the public full-restore or installed-server acceptance.
"""
import hashlib
import io
import json
from pathlib import Path
import tarfile


def verify(command,container,folder,helper,read,sql,identity):
    folder=Path(folder)
    generation='11111111-1111-4111-8111-111111111111'
    operation='22222222-2222-4222-8222-222222222222'
    root=folder/'instance-restore'
    destination=root/'material'/generation
    destination.mkdir(parents=True,mode=0o700)
    for path in (root,root/'material'):path.chmod(0o700)
    entries={}
    for name,body in {'application.properties':b'timekeeper.instance-restore.storage=/state/server/instance-restore\n',
                      'source-configuration.json':b'{"format":"timekeeper-instance-configuration-v1"}',
                      'source/configuration/0000.json':b'[]'}.items():
        path=destination/name;path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
        path.write_bytes(body);path.chmod(0o600)
        entries[name]={'bytes':len(body),'sha256':hashlib.sha256(body).hexdigest()}
    (destination/'source').chmod(0o700)
    metadata={'format':'timekeeper-instance-material-v1','generation':generation,'operation':operation,'intentDigest':'a'*64,'entries':entries}
    receipt=json.dumps(metadata).encode()
    (destination/'material.json').write_bytes(receipt);(destination/'material.json').chmod(0o600)
    digest=hashlib.sha256(receipt).hexdigest()
    (root/'active.json').write_text(json.dumps({'format':metadata['format'],'generation':generation,'sha256':digest}));(root/'active.json').chmod(0o600)
    base='/volume1/@appdata/timekeeper/server'
    command('docker','cp',str(root),container+':'+base+'/instance-restore')
    command('docker','exec',container,'chown','-R',identity,base+'/instance-restore')
    binding={'restoreGeneration':generation,'restorePending':False,'restoreIntent':{'generation':generation,'sha256':'a'*64},
             'restore':{'phase':'COMPLETE','operationId':operation,'materialSha256':digest}}
    def literal(value):return "'"+value.replace("'","''")+"'"
    sql("UPDATE tk_instance SET payload=(payload::jsonb || "+literal(json.dumps(binding))+"::jsonb)::text WHERE kind='CONTROL';")
    backup=helper('backup').stdout.strip()
    archive=folder/'active-material-native.tar';command('docker','cp',container+':'+backup,str(archive))
    with tarfile.open(archive) as source:
        manifest=json.load(source.extractfile('manifest.json'))
        assert manifest['format']==2
        assert 'instance-restore/active.json' in manifest['files']
        assert len([name for name in manifest['files'] if name.startswith('instance-restore/')])==5
        assert all('prepared' not in name and '/work/' not in name for name in manifest['files'])
    # Reject missing live material before producing a misleading native backup.
    command('docker','exec',container,'mv',base+'/instance-restore',base+'/instance-restore-lost')
    helper('backup',expected=1)
    helper('restore',backup)
    assert read('instance-restore/active.json')==(root/'active.json').read_bytes()
    assert read('instance-restore/material/'+generation+'/material.json')==receipt
    helper('backup')
    # Strip every material member from an otherwise checksummed archive. Its SQL
    # still requires that material, so rejection must roll the database back.
    missing=folder/'missing-native-material.tar'
    with tarfile.open(archive) as source,tarfile.open(missing,'w') as target:
        for member in source:
            if member.name.startswith('instance-restore/'):continue
            body=source.extractfile(member).read()
            if member.name=='manifest.json':
                value=json.loads(body);value['files']={name:sha for name,sha in value['files'].items() if not name.startswith('instance-restore/')};body=json.dumps(value).encode()
            member.size=len(body);target.addfile(member,io.BytesIO(body))
    invalid=base+'/backups/missing-native-material.tar'
    command('docker','cp',str(missing),container+':'+invalid)
    command('docker','exec',container,'chown',identity,invalid)
    sql("UPDATE tk_resource SET payload=jsonb_set(payload::jsonb,'{name}','\"rollback sentinel\"')::text WHERE owner_id='existing-owner' AND entity_type='LABEL';")
    helper('restore',invalid,expected=1)
    assert sql("SELECT payload::jsonb->>'name' FROM tk_resource WHERE owner_id='existing-owner' AND entity_type='LABEL'")=='rollback sentinel'
    assert read('instance-restore/active.json')==(root/'active.json').read_bytes()
    helper('prestart',expected=1)
    helper('restore',backup)
    assert sql("SELECT payload::jsonb->>'name' FROM tk_resource WHERE owner_id='existing-owner' AND entity_type='LABEL'")=='owned private data'
    return {'nativeActiveMaterialRoundTrip':True,'nativeMissingActiveMaterialBackupRefused':True,
            'nativeMissingMaterialRestoreRollsBack':True,'nativeMaterialRecoveryRetry':True}
