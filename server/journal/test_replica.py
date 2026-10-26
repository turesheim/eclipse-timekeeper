"""Real filesystem races and crash recovery for the bidirectional local installer."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

SPEC=importlib.util.spec_from_file_location('journal_client_replica',Path(__file__).parents[1]/'web/src/journal-client.py')
client=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(client)


class ReplicaTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix="journal replica ' ");self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name).resolve();self.state=self.root/'private';self.state.mkdir(mode=0o700);self.notes=self.root/'Daily';self.notes.mkdir();self.device=str(uuid.uuid4());self.name='2026-10-25.md'
    def replica(self):return client.Replica(self.state,self.notes,self.device)
    def offer(self,old,new,sequence=1,parent=''):
        return {'id':str(uuid.uuid4()),'token':str(uuid.uuid4()),'filename':self.name,'sequence':sequence,'state':'OFFERED','parentToken':parent,'expectedExists':old is not None,'expectedHash':client.Replica.content_hash(old),'targetExists':new is not None,'targetHash':client.Replica.content_hash(new)}
    def test_export_and_tracked_delete_preserve_originals_and_acknowledged_bases_across_restart(self):
        old=b'Original local note  \r\n';new=b'Calendar edit\r\n';path=self.notes/self.name;path.write_bytes(old)
        with self.replica() as replica:
            identity=replica.client_id;offer=self.offer(old,new);replica.apply(offer,new);self.assertEqual(new,path.read_bytes());self.assertEqual({},replica.bases())
            self.assertEqual(old,(replica.root/(offer['id']+'.old')).read_bytes());replica.acknowledged(offer);self.assertFalse(replica.pending())
        with self.replica() as replica:
            self.assertEqual(identity,replica.client_id);self.assertEqual(offer['token'],replica.bases()[self.name]['token']);replica.check_recoveries()
            removed=self.offer(new,None,2,offer['token']);replica.apply(removed,None);self.assertFalse(path.exists());replica.acknowledged(removed)
            self.assertFalse(replica.bases()[self.name]['present']);self.assertEqual(new,(replica.root/(removed['id']+'.old')).read_bytes())
            for item in replica.root.iterdir():
                if item.is_file():self.assertEqual(0o600,item.stat().st_mode&0o777)
    def test_noop_acknowledges_its_snapshot_without_replacing_a_newer_editor_save(self):
        path=self.notes/self.name;path.write_bytes(b'Newer editor save');offer=self.offer(b'Common earlier point',b'Common earlier point')
        with self.replica() as replica:
            replica.apply(offer,b'Common earlier point');replica.acknowledged(offer)
            self.assertEqual(b'Newer editor save',path.read_bytes());self.assertEqual(offer['targetHash'],replica.bases()[self.name]['hash']);self.assertFalse(replica.recoveries())
    def test_exclusive_install_does_not_overwrite_an_atomic_editor_replacement(self):
        path=self.notes/self.name;path.write_bytes(b'Original');link=os.link
        def editor_saves(source,destination,**options):
            if destination==self.name:path.write_bytes(b'Concurrent editor replacement')
            return link(source,destination,**options)
        with self.replica() as replica:
            offer=self.offer(b'Original',b'Server variant')
            with patch.object(client.os,'link',side_effect=editor_saves):
                with self.assertRaises(client.Failure):replica.apply(offer,b'Server variant')
            self.assertEqual(b'Concurrent editor replacement',path.read_bytes());self.assertEqual(b'Original',(replica.root/(offer['id']+'.old')).read_bytes());self.assertEqual(b'Server variant',(replica.root/(offer['id']+'.new')).read_bytes());self.assertEqual({},replica.bases())
    def test_editor_with_an_old_open_descriptor_keeps_its_variant_private(self):
        path=self.notes/self.name;path.write_bytes(b'Original');link=os.link
        with path.open('ab',buffering=0) as editor:
            def editor_saves(source,destination,**options):
                result=link(source,destination,**options)
                if destination==self.name:editor.write(b' plus an unsaved old-descriptor edit');os.fsync(editor.fileno())
                return result
            with self.replica() as replica:
                offer=self.offer(b'Original',b'Server variant')
                with patch.object(client.os,'link',side_effect=editor_saves):
                    with self.assertRaises(client.Failure):replica.apply(offer,b'Server variant')
                self.assertEqual(b'Server variant',path.read_bytes());self.assertIn(b'old-descriptor edit',(replica.root/(offer['id']+'.old')).read_bytes());self.assertEqual({},replica.bases())
    def test_late_old_descriptor_writes_are_detected_after_acknowledgement(self):
        path=self.notes/self.name;path.write_bytes(b'Original')
        with path.open('ab',buffering=0) as editor:
            with self.replica() as replica:
                offer=self.offer(b'Original',b'Server variant');replica.apply(offer,b'Server variant');replica.acknowledged(offer);editor.write(b' late change');os.fsync(editor.fileno())
            with self.replica() as replica:
                with self.assertRaises(client.Failure):replica.check_recoveries()
                self.assertEqual(b'Server variant',path.read_bytes());self.assertEqual(b'Original late change',(replica.root/(offer['id']+'.old')).read_bytes())
    def test_crash_after_move_before_install_resumes_without_losing_either_copy(self):
        path=self.notes/self.name;path.write_bytes(b'Original');link=os.link;offer=self.offer(b'Original',b'Server variant')
        with self.replica() as replica:
            with patch.object(client.os,'link',side_effect=OSError('simulated interruption')):
                with self.assertRaises(OSError):replica.apply(offer,b'Server variant')
            self.assertFalse(path.exists());self.assertTrue(replica.pending())
        with self.replica() as replica:
            replica.resume(replica.pending()[0]);self.assertEqual(b'Server variant',path.read_bytes());replica.acknowledged(offer)
            self.assertEqual(b'Original',(replica.root/(offer['id']+'.old')).read_bytes())
    def test_crash_after_exclusive_link_resumes_for_a_new_file(self):
        offer=self.offer(None,b'Initial export');original_unlink=Path.unlink
        def interruption(path,*args,**kwargs):
            if path.name==offer['id']+'.new':raise OSError('simulated interruption after link')
            return original_unlink(path,*args,**kwargs)
        with self.replica() as replica:
            with patch.object(Path,'unlink',interruption):
                with self.assertRaises(OSError):replica.apply(offer,b'Initial export')
        with self.replica() as replica:
            replica.resume(replica.pending()[0]);replica.acknowledged(offer);self.assertEqual(b'Initial export',(self.notes/self.name).read_bytes());self.assertEqual(1,(self.notes/self.name).stat().st_nlink)
    def test_changed_folder_or_stale_parent_and_invalid_paths_never_modify_notes(self):
        with self.replica() as replica:
            initial=self.offer(None,b'Initial');replica.apply(initial,b'Initial');replica.acknowledged(initial)
            with self.assertRaises(client.Failure):replica.apply(self.offer(b'Initial',b'Stale',2,''),b'Stale')
            invalid=self.offer(None,b'Invalid');invalid['filename']='../2026-10-25.md'
            with self.assertRaises(client.Failure):replica.apply(invalid,b'Invalid')
            self.assertEqual(b'Initial',(self.notes/self.name).read_bytes())
        self.notes.rename(self.root/'Old Daily');self.notes.mkdir()
        with self.assertRaises(client.Failure):self.replica()
        self.assertEqual(b'Initial',(self.root/'Old Daily'/self.name).read_bytes());self.assertEqual([],list(self.notes.iterdir()))
    def test_private_state_cannot_live_inside_the_canonical_daily_folder(self):
        state=self.notes/'state';state.mkdir(mode=0o700)
        with self.assertRaises(client.Failure):client.Replica(state,self.notes,self.device)


if __name__=='__main__':unittest.main()
