import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('webdav_sync',Path(__file__).resolve().parents[1]/'webdav_sync.py')
sync=importlib.util.module_from_spec(spec);spec.loader.exec_module(sync)

class ManualSyncTests(unittest.TestCase):
    def test_entry_bound_can_be_adjusted_without_allowing_partial_scans(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'one.md').write_text('One');(root/'two.md').write_text('Two')
            local=sync.Local(root,max_entries=1)
            try:
                with self.assertRaises(sync.SyncError):local.scan()
            finally:local.close()
            with self.assertRaises(sync.SyncError):sync.prepare_import_metadata(root,max_entries=1)
            self.assertFalse((root/sync.IMPORT_METADATA).exists())
            local=sync.Local(root,max_entries=2)
            try:self.assertEqual(2,len(local.scan()))
            finally:local.close()
            self.assertEqual(2,sync.prepare_import_metadata(root,max_entries=2)['entries'])
    def test_import_metadata_keeps_precise_modification_dates_without_inventing_posix_creation_dates(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'Notes').mkdir();source=root/'Notes'/'Exact.md';source.write_bytes(b'Original\r\n');(root/'Empty').mkdir();(root/'.git').mkdir();(root/'.git'/'private').write_text('ignored')
            os.utime(source,ns=(1_612_325_106_987_654_321,1_612_325_106_987_654_321))
            real_fstat=os.fstat
            def posix_stat(fd):
                value=real_fstat(fd);return SimpleNamespace(**{name:getattr(value,name) for name in dir(value) if name.startswith('st_') and name not in ('st_birthtime','st_birthtime_ns')})
            with patch.object(sync.os,'fstat',side_effect=posix_stat):result=sync.prepare_import_metadata(root)
            data=json.loads((root/sync.IMPORT_METADATA).read_text());rows={row['path']:row for row in data['entries']}
            self.assertEqual(3,result['entries']);self.assertEqual('2021-02-03T04:05:06.987654321Z',rows['Notes/Exact.md']['modifiedAt']);self.assertNotIn('createdAt',rows['Notes/Exact.md']);self.assertEqual('FOLDER',rows['Empty']['kind']);self.assertNotIn('.git/private',rows);self.assertEqual(b'Original\r\n',source.read_bytes())
            sync.prepare_import_metadata(root);self.assertNotIn(sync.IMPORT_METADATA,{row['path'] for row in json.loads((root/sync.IMPORT_METADATA).read_text())['entries']})
    def test_import_metadata_preserves_available_birthtime_and_refuses_unsafe_source_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'vault';root.mkdir();source=root/'Exact.md';source.write_text('Text');real_fstat=os.fstat
            def birth_stat(fd):
                value=real_fstat(fd);fields={name:getattr(value,name) for name in dir(value) if name.startswith('st_')};fields['st_birthtime_ns']=1_577_934_245_123_456_789;return SimpleNamespace(**fields)
            with patch.object(sync.os,'fstat',side_effect=birth_stat):sync.prepare_import_metadata(root)
            self.assertEqual('2020-01-02T03:04:05.123456789Z',json.loads((root/sync.IMPORT_METADATA).read_text())['entries'][0]['createdAt'])
            original=(root/sync.IMPORT_METADATA).read_bytes();outside=Path(directory)/'outside';outside.write_text('Private');(root/'link').symlink_to(outside)
            with self.assertRaises(sync.SyncError):sync.prepare_import_metadata(root)
            self.assertEqual(original,(root/sync.IMPORT_METADATA).read_bytes());self.assertEqual('Private',outside.read_text())
    def test_import_metadata_never_overwrites_an_unrelated_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);target=root/sync.IMPORT_METADATA;target.write_text('User file')
            with self.assertRaises(sync.SyncError):sync.prepare_import_metadata(root)
            self.assertEqual('User file',target.read_text())
    def test_missing_root_and_incomplete_xml_are_not_valid_empty_remote_scans(self):
        remote=sync.Remote('https://example.invalid/dav/notes/','client','synthetic-token')
        for xml in [b'<D:multistatus xmlns:D="DAV:"></D:multistatus>',b'<D:multistatus xmlns:D="DAV:"><D:response>']:
            with patch.object(remote,'request',return_value=(207,{},xml)):
                with self.assertRaises(sync.SyncError):remote.list()
    def test_recreated_remote_folder_cannot_be_deleted_as_the_old_folder(self):
        baseline={'Folder':{'kind':'directory','etag':'"old"','resourceId':'urn:uuid:00000000-0000-0000-0000-000000000001'}}
        remote={'Folder':{'kind':'directory','etag':'"new"','resourceId':'urn:uuid:00000000-0000-0000-0000-000000000002'}}
        actions,conflicts=sync.plan({},remote,baseline)
        self.assertEqual([],actions);self.assertEqual(1,len(conflicts))
    def test_recreated_remote_root_aborts_before_reading_any_file(self):
        remote=sync.Remote('https://example.invalid/dav/notes/','client','synthetic-token')
        root={'kind':'directory','etag':'"new"','resourceId':'urn:uuid:00000000-0000-0000-0000-000000000002'}
        with patch.object(remote,'list',return_value=(root,{})),patch.object(remote,'read') as read:
            with self.assertRaises(sync.SyncError):remote.scan({},'urn:uuid:00000000-0000-0000-0000-000000000001')
            read.assert_not_called()
    def test_concurrent_syncs_cannot_interleave_writes_to_the_same_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)/'notes';folder.mkdir();local=sync.Local(folder);control=sync.Control(Path(directory)/'control',local)
            try:
                with self.assertRaises(sync.SyncError):sync.Control(Path(directory)/'control',local)
            finally:control.close();local.close()
    def test_first_import_collision_requires_explicit_review_and_keeps_both_versions(self):
        local={'Note.md':{'kind':'file','sha256':'local'}};remote={'Note.md':{'kind':'file','sha256':'remote','etag':'"1"'}}
        actions,conflicts=sync.plan(local,remote,{})
        self.assertEqual([],actions);self.assertEqual(1,len(conflicts))
        actions,conflicts=sync.plan(local,remote,{},prefer_local=['Note.md'])
        self.assertEqual([],conflicts);self.assertEqual('"1"',actions[0]['remoteEtag']);self.assertEqual('push',actions[0]['operation'])
    def test_unique_rename_uses_a_guarded_move_instead_of_deleting_the_document(self):
        local={'New.md':{'kind':'file','sha256':'same'}};remote={'Old.md':{'kind':'file','sha256':'same','etag':'"3"'}}
        baseline={'Old.md':{'kind':'file','sha256':'same','etag':'"3"'}}
        actions,conflicts=sync.plan(local,remote,baseline)
        self.assertEqual([],conflicts);self.assertEqual([{'operation':'move-remote','path':'Old.md','destination':'New.md','remoteEtag':'"3"','remoteSha256':'same'}],actions)
    def test_remote_deletion_cannot_erase_new_local_edits(self):
        local={'Note.md':{'kind':'file','sha256':'changed'}};baseline={'Note.md':{'kind':'file','sha256':'old','etag':'"1"'}}
        actions,conflicts=sync.plan(local,{},baseline)
        self.assertEqual([],actions);self.assertEqual(1,len(conflicts))
    def test_local_replacement_never_overwrites_an_editor_that_recreates_the_path(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)/'notes';folder.mkdir();(folder/'Note.md').write_bytes(b'original')
            local=sync.Local(folder);control=sync.Control(Path(directory)/'control',local);control.open();control.state['pending']=[{'operation':'pull','path':'Note.md'}];control.save()
            real_link=os.link
            def race(source,destination,**kwargs):
                (folder/'Note.md').write_bytes(b'concurrent editor');return real_link(source,destination,**kwargs)
            try:
                with patch.object(sync.os,'link',side_effect=race):
                    with self.assertRaises(FileExistsError):local.install('Note.md',b'remote',sync.digest(b'original'),control)
                self.assertEqual(b'concurrent editor',(folder/'Note.md').read_bytes())
                backups=list((control.path/'backups').glob('*.bin'));self.assertEqual(1,len(backups));self.assertEqual(b'original',backups[0].read_bytes())
                self.assertTrue(json.loads(control.file.read_text())['pending'])
            finally:control.close();local.close()
    def test_swapping_a_file_for_a_symlink_during_capture_never_follows_it(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)/'notes';folder.mkdir();outside=Path(directory)/'private';outside.write_bytes(b'outside secret');(folder/'Note.md').write_bytes(b'original')
            local=sync.Local(folder);control=sync.Control(Path(directory)/'control',local);control.open()
            real_rename=os.rename
            def race(source,destination,**kwargs):
                (folder/'Note.md').unlink();(folder/'Note.md').symlink_to(outside);return real_rename(source,destination,**kwargs)
            try:
                with patch.object(sync.os,'rename',side_effect=race):
                    with self.assertRaises(OSError):local.capture('Note.md',sync.digest(b'original'),control)
                self.assertEqual(b'outside secret',outside.read_bytes())
            finally:control.close();local.close()
    def test_missing_or_replaced_local_root_is_not_an_empty_collection(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)/'notes';folder.mkdir();local=sync.Local(folder)
            try:
                folder.rename(Path(directory)/'retained');folder.mkdir()
                with self.assertRaises(sync.SyncError):local.scan()
            finally:local.close()
    def test_symlink_and_case_collision_abort_before_any_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)/'notes';folder.mkdir();(folder/'Alias').symlink_to(Path(directory))
            local=sync.Local(folder)
            try:
                with self.assertRaises(sync.SyncError):local.scan()
            finally:local.close()
            (folder/'Alias').unlink();(folder/'One.md').write_bytes(b'one');(folder/'one.md').write_bytes(b'two')
            # APFS may be case insensitive; the collision itself is exercised
            # on Linux, while the production portable-key rule is tested here.
            self.assertEqual(sync.key('Straße.md'),sync.key('STRASSE.md'))
            if len(list(folder.iterdir()))==2:
                local=sync.Local(folder)
                try:
                    with self.assertRaises(sync.SyncError):local.scan()
                finally:local.close()

if __name__=='__main__':unittest.main()
