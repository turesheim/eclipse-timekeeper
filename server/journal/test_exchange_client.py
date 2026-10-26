"""Exercise durable client orchestration across lost replies and per-note conflicts."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

SPEC=importlib.util.spec_from_file_location('exchange_client',Path(__file__).parents[1]/'web/src/journal-client.py')
client=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(client)


class ExchangeClientTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup);self.root=Path(self.temporary.name);self.state=self.root/'state';self.state.mkdir(mode=0o700);self.folder=self.root/'Daily';self.folder.mkdir();self.identity=self.root/'identity';self.device=str(uuid.uuid4());self.data={'format':2,'deviceId':self.device};self.remote={};self.received={};self.tokens={};self.conflicts=set();self.fail_upload=False;self.lose_ack=False;self.sequence=0
        self.patches=[patch.object(client,'remote_json',side_effect=self.request),patch.object(client,'exchange_rsync',side_effect=self.transfer)]
        for item in self.patches:item.start();self.addCleanup(item.stop)
    def sync(self):return client.sync_bidirectional(self.data,self.folder,self.identity,self.state)
    def request(self,data,identity,state,command,value=None):
        if command=='timekeeper-journal-inventory':return {'names':sorted(self.remote)}
        if command.startswith('timekeeper-journal-results '):return self.received[command.split()[-1]]['results']
        if command=='timekeeper-journal-ack':
            for exchange in self.received.values():
                for offer in exchange['results']['offers']:
                    if offer['id']==value['offerId']:
                        self.assertEqual(offer['targetHash'],value['sha256']);self.assertEqual(offer['token'],value['token']);offer['state']='ACKNOWLEDGED';self.tokens[offer['filename']]=offer['token']
                        if self.lose_ack:self.lose_ack=False;raise client.Failure('simulated lost acknowledgement')
                        return dict(offer)
        self.fail('Unexpected remote command')
    def transfer(self,data,identity,state,directory,exchange,download=False):
        if download:
            received=self.received[exchange];client.atomic(directory/'.timekeeper-results.json',json.dumps(received['results']).encode())
            for offer in received['results']['offers']:
                if offer['state']=='OFFERED' and offer['targetExists']:client.atomic(directory/offer['filename'],received['files'][offer['filename']])
            return
        manifest=json.loads((directory/'.timekeeper-batch.json').read_text())
        if exchange not in self.received:
            self.sequence+=1;offers=[];files={}
            for entry in manifest['files']+manifest['absent']:
                name=entry['name'];old=(directory/name).read_bytes() if entry in manifest['files'] else None
                target=self.remote.get(name,old);files[name]=target
                offers.append({'id':str(uuid.uuid4()),'token':str(uuid.uuid4()),'sequence':self.sequence,'filename':name,'parentToken':entry['baseToken'],'state':'CONFLICT' if name in self.conflicts else 'OFFERED','expectedExists':old is not None,'expectedHash':client.Replica.content_hash(old),'targetExists':target is not None,'targetHash':client.Replica.content_hash(target)})
            self.received[exchange]={'manifest':manifest,'files':files,'results':{'format':2,'exchangeId':exchange,'pending':0,'offers':offers,'errors':manifest['errors']}}
        else:self.assertEqual(manifest,self.received[exchange]['manifest'])
        if self.fail_upload:self.fail_upload=False;raise client.Failure('simulated transfer response loss')
    def test_database_only_export_and_lost_ack_resume_before_a_new_exchange(self):
        name='2026-10-25.md';self.remote[name]=b'First calendar copy';self.lose_ack=True
        with self.assertRaises(client.Failure):self.sync()
        self.assertEqual(self.remote[name],(self.folder/name).read_bytes())
        with client.Replica(self.state,self.folder,self.device) as replica:self.assertTrue(replica.pending());self.assertFalse(replica.bases())
        self.sync()
        with client.Replica(self.state,self.folder,self.device) as replica:self.assertFalse(replica.pending());self.assertFalse(replica.exchanges());self.assertEqual(self.tokens[name],replica.bases()[name]['token'])
        self.remote[name]=b'Later calendar edit';self.sync();self.assertEqual(self.remote[name],(self.folder/name).read_bytes())
        with client.Replica(self.state,self.folder,self.device) as replica:self.assertEqual(1,len(replica.recoveries()));self.assertEqual(b'First calendar copy',(replica.root/replica.recoveries()[0]['backup']).read_bytes())
    def test_retry_reuses_original_snapshot_and_preserves_a_later_editor_save(self):
        name='2026-10-25.md';path=self.folder/name;path.write_bytes(b'Original upload');self.fail_upload=True
        with self.assertRaises(client.Failure):self.sync()
        path.write_bytes(b'Newer editor save');self.sync()
        self.assertEqual(b'Newer editor save',path.read_bytes());self.assertEqual(1,len(self.received))
        with client.Replica(self.state,self.folder,self.device) as replica:self.assertEqual(hashlib.sha256(b'Original upload').hexdigest(),replica.bases()[name]['hash'])
        self.sync();self.assertEqual(2,len(self.received))
    def test_conflicted_date_is_retained_while_other_exports_finish(self):
        name='2026-10-25.md';self.conflicts.add(name);(self.folder/name).write_bytes(b'Local conflict');self.remote[name]=b'Calendar conflict';self.remote['2026-10-26.md']=b'Independent export'
        with self.assertRaises(client.Failure):self.sync()
        self.assertEqual(b'Local conflict',(self.folder/name).read_bytes());self.assertEqual(b'Independent export',(self.folder/'2026-10-26.md').read_bytes())
        with client.Replica(self.state,self.folder,self.device) as replica:self.assertEqual(1,len(replica.exchanges()));self.assertNotIn(name,replica.bases());self.assertIn('2026-10-26.md',replica.bases())
    def test_unsafe_local_file_is_reported_as_error_and_never_as_a_deletion(self):
        name='2026-10-25.md';secret=self.root/'private-secret';secret.write_bytes(b'private');(self.folder/name).symlink_to(secret);self.remote['2026-10-26.md']=b'Independent export'
        with self.assertRaises(client.Failure):self.sync()
        manifest=next(iter(self.received.values()))['manifest'];self.assertEqual([{'name':name,'code':'LOCAL_FILE_UNREADABLE'}],manifest['errors']);self.assertNotIn(name,[entry['name'] for entry in manifest['absent']]);self.assertEqual(b'private',secret.read_bytes());self.assertEqual(b'Independent export',(self.folder/'2026-10-26.md').read_bytes())


if __name__=='__main__':unittest.main()
