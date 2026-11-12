#!/usr/bin/env python3
"""Verify real pre-prefix image upgrade and rollback without replacing settings or secrets."""
import argparse
import json
from pathlib import Path
import re
import tempfile

from compose_instance_restore_acceptance import Installation
from instance_acceptance_client import ADMIN_PASSWORD


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--old-image',required=True)
    parser.add_argument('--new-image',required=True)
    parser.add_argument('--old-commit',required=True)
    parser.add_argument('--new-commit',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if not all(re.fullmatch('[0-9a-f]{40}',value) for value in (args.old_commit,args.new_commit)):
        parser.error('Use full source commit identities')
    args.output.mkdir(parents=True,exist_ok=True)
    result=args.output/'acceptance.json';result.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='dagsverk-environment-upgrade-') as temporary:
        installed=Installation(Path(temporary)/'installation',args.old_image,'timekeeper')
        try:
            registration=installed.state/'secrets/task-providers/application.properties'
            registration.chmod(0o600)
            registration.write_text('timekeeper.connect.shared=false\ntimekeeper.tasks.google.client-id=upgrade-fixture\ntimekeeper.tasks.google.client-secret=private-upgrade-fixture\n')
            registration.chmod(0o444)
            installed.start();api=installed.api()
            api.call('GET','/auth/status')
            owner=api.call('POST','/auth/setup',{'code':installed.code,'username':'upgrade-admin','password':ADMIN_PASSWORD,'displayName':'Upgrade fixture','email':'upgrade@example.invalid'})['ownerId']
            client=api.call('POST','/clients',{'name':'Retained upgrade client','color':'#176653'})
            stages=[];expected_id="upgrade-fixture";edited=False
            for image,commit in [(args.old_image,args.old_commit),(args.new_image,args.new_commit),(args.old_image,args.old_commit),(args.new_image,args.new_commit)]:
                env=installed.folder/'.env'
                lines=[line for line in env.read_text().splitlines() if not line.startswith('DAGSVERK_SERVER_IMAGE=')]
                # An existing legacy value deliberately remains: the new name must win.
                env.write_text('\n'.join(lines)+'\nDAGSVERK_SERVER_IMAGE='+image+'\n')
                installed.start();api=installed.api();profile=api.login('upgrade-admin',ADMIN_PASSWORD)
                if profile['ownerId']!=owner or api.call('GET','/clients/'+client['id'])!=client:
                    raise RuntimeError('Upgrade or rollback changed owned data')
                if api.call('GET','/server')['commit']!=commit:
                    raise RuntimeError('Upgrade or rollback used the wrong server image')
                setup=api.call('GET','/admin/internet-accounts')
                for field in ('saved','effective'):
                    if setup[field]['mode']!='DIRECT' or setup[field]['googleClientId']!=expected_id or not setup[field]['googleClientSecretConfigured']:
                        raise RuntimeError('Upgrade or rollback lost provider registration')
                if 'private-upgrade-fixture' in json.dumps(setup):
                    raise RuntimeError('Provider secret exposed by setup response')
                stages.append(commit)
                if image==args.new_image and not edited:
                    setup,headers=api.request('GET','/admin/internet-accounts')
                    api.call('PUT','/admin/internet-accounts',{'mode':'DIRECT','serviceUrl':None,'googleClientId':'upgrade-fixture-updated','googleSecretAction':'PRESERVE',
                        'microsoftClientId':setup['saved'].get('microsoftClientId',''),'microsoftSecretAction':'PRESERVE','currentPassword':ADMIN_PASSWORD},headers={'If-Match':headers['ETag']})
                    expected_id='upgrade-fixture-updated';edited=True
            receipt={'passed':True,'oldCommit':args.old_commit,'newCommit':args.new_commit,'stages':stages,
                     'newNamesOverrideLegacy':True,'oldImageRollback':True,'ownerAndDataPreserved':True,'providerSettingsAndSecretPreserved':True,'legacyGoogleSettingsChangesSurviveRollback':True}
            result.write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
        finally:
            installed.close()


if __name__=='__main__':
    main()
