"""Gateway protocol boundaries; actual SSH and rsync are covered by container acceptance."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

SPEC=importlib.util.spec_from_file_location('journal_gateway',Path(__file__).with_name('gateway.py'))
gateway=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(gateway)


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup);self.stage=Path(self.temporary.name)
        self.exchange=str(uuid.uuid4());self.device=str(uuid.uuid4());self.name='2026-10-25.md';self.content=b'---\r\nunknown: preserved\r\n---\r\n\r\nDaily note  \r\n'
    def write(self,name,data):
        path=self.stage/name;path.write_bytes(data);path.chmod(0o600)
    def manifest(self):
        return {'format':2,'exchangeId':self.exchange,'clientStateId':str(uuid.uuid4()),'files':[{'name':self.name,'baseToken':'','bytes':len(self.content),'sha256':hashlib.sha256(self.content).hexdigest()}],'absent':[{'name':'2026-10-26.md','baseToken':str(uuid.uuid4())}],'errors':[]}
    def seal(self,value):self.write('.timekeeper-batch.json',json.dumps(value).encode())
    def test_role_is_narrow_and_upload_path_cannot_select_outbound(self):
        prefix='rsync --server -re.iLsfxCIvu . '
        for sending,path in [(False,'incoming/'),(True,'outgoing/')]:
            command=prefix.replace('--server','--server --sender') if sending else prefix
            role,exchange,rewritten=gateway.command(command+path+self.exchange+'/')
            self.assertEqual((sending,self.exchange),(role,exchange));self.assertTrue(rewritten.endswith(' . .'))
        self.assertEqual((False,None,prefix+'.'),gateway.command(prefix+'.'))
        for value in ['cat /etc/passwd',prefix+'outgoing/'+self.exchange,prefix+'incoming/'+self.exchange+'/../x',prefix+'incoming/'+self.exchange+' extra',prefix+'incoming/not-a-uuid','rsync --server --sender -r . .','rsync --server --sender -r . incoming/'+self.exchange,'rsync --server --daemon -r . .']:
            with self.subTest(value=value),self.assertRaises((gateway.Failure,ValueError)):gateway.command(value)
    def test_seal_checks_every_file_and_explicit_absence_before_admission(self):
        value=self.manifest();self.seal(value);self.write(self.name,self.content)
        manifest,files=gateway.completed_files(self.stage,self.exchange)
        self.assertEqual(value,manifest);self.assertEqual({self.name:self.content},files)
        self.write(self.name,self.content+b'interrupted')
        with self.assertRaises(gateway.Failure):gateway.completed_files(self.stage,self.exchange)
    def test_duplicates_unsafe_files_invalid_dates_and_wrong_exchange_are_rejected(self):
        value=self.manifest();self.write(self.name,self.content)
        for mutation in ['duplicate','invalid-date','wrong-exchange','unexpected','symlink','permissions']:
            item=json.loads(json.dumps(value))
            with self.subTest(mutation=mutation):
                if mutation=='duplicate':item['absent'][0]['name']=self.name
                if mutation=='invalid-date':item['absent'][0]['name']='2026-02-30.md'
                if mutation=='wrong-exchange':item['exchangeId']=str(uuid.uuid4())
                if mutation=='unexpected':self.write('extra',b'private')
                if mutation=='symlink':(self.stage/self.name).unlink();(self.stage/self.name).symlink_to('/etc/passwd')
                if mutation=='permissions':(self.stage/self.name).chmod(0o644)
                self.seal(item)
                with self.assertRaises(gateway.Failure):gateway.completed_files(self.stage,self.exchange)
                if mutation=='unexpected':(self.stage/'extra').unlink()
                if mutation=='symlink':(self.stage/self.name).unlink();self.write(self.name,self.content)
                if mutation=='permissions':(self.stage/self.name).chmod(0o600)
    def test_oneway_manifest_stays_separate_and_two_way_limit_counts_all_entries(self):
        self.write(self.name,self.content);value=self.manifest();value['absent']*=50;self.seal(value)
        with self.assertRaises(gateway.Failure):gateway.completed_files(self.stage,self.exchange)
        value=self.manifest();value.pop('absent');value.pop('clientStateId');value.pop('exchangeId');value['format']=1;value['files'][0].pop('baseToken');self.seal(value)
        self.assertEqual(self.content,gateway.completed_files(self.stage)[1][self.name])
        with self.assertRaises(gateway.Failure):gateway.completed_files(self.stage,self.exchange)
    def test_export_materializes_only_owned_offered_targets_and_validates_their_hash(self):
        offer={'id':str(uuid.uuid4()),'filename':self.name,'state':'OFFERED','targetExists':True,'targetHash':hashlib.sha256(self.content).hexdigest()}
        value={'format':2,'exchangeId':self.exchange,'pending':0,'offers':[offer,{'id':str(uuid.uuid4()),'filename':'2026-10-26.md','state':'CONFLICT'}],'errors':[]}
        previous=os.umask(0o077)
        try:
            with patch.object(gateway,'request',return_value=self.content) as request:
                gateway.prepared_files(self.stage,{},self.device,self.exchange,value)
                self.assertEqual({'deviceId':self.device},request.call_args.args[2]);self.assertTrue(request.call_args.kwargs['binary'])
            self.assertEqual(self.content,(self.stage/self.name).read_bytes());self.assertEqual({self.name,'.timekeeper-results.json'},{p.name for p in self.stage.iterdir()})
            for path in self.stage.iterdir():self.assertEqual(0o600,path.stat().st_mode&0o777)
            with tempfile.TemporaryDirectory() as other,patch.object(gateway,'request',return_value=b'wrong snapshot'):
                with self.assertRaises(gateway.Failure):gateway.prepared_files(Path(other),{},self.device,self.exchange,value)
        finally:os.umask(previous)


if __name__=='__main__':unittest.main()
