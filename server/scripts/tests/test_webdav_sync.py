import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('webdav_sync',Path(__file__).resolve().parents[1]/'webdav_sync.py')
sync=importlib.util.module_from_spec(spec);spec.loader.exec_module(sync)

class ManualSyncTests(unittest.TestCase):
    def test_missing_root_and_incomplete_xml_are_not_valid_empty_remote_scans(self):
        remote=sync.Remote('https://example.invalid/dav/vault/','client','synthetic-token')
        for xml in [b'<D:multistatus xmlns:D="DAV:"></D:multistatus>',b'<D:multistatus xmlns:D="DAV:"><D:response>']:
            with patch.object(remote,'request',return_value=(207,{},xml)):
                with self.assertRaises(sync.SyncError):remote.list()
    def test_recreated_remote_folder_cannot_be_deleted_as_the_old_folder(self):
        baseline={'Folder':{'kind':'directory','etag':'"old"','resourceId':'urn:uuid:00000000-0000-0000-0000-000000000001'}}
        remote={'Folder':{'kind':'directory','etag':'"new"','resourceId':'urn:uuid:00000000-0000-0000-0000-000000000002'}}
        actions,conflicts=sync.plan({},remote,baseline)
        self.assertEqual([],actions);self.assertEqual(1,len(conflicts))
    def test_recreated_remote_root_aborts_before_reading_any_file(self):
        remote=sync.Remote('https://example.invalid/dav/vault/','client','synthetic-token')
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
