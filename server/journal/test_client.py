"""Client safety checks; SSH/rsync integration is tested with the actual gateway image."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch
import uuid

SPEC=importlib.util.spec_from_file_location('journal_client',Path(__file__).parents[1]/'web/src/journal-client.py')
client=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(client)


class ClientTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix="journal client ' space ");self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name).resolve();self.notes=self.root/'Daily notes';self.notes.mkdir();self.identity=self.root/"identity ' file";self.identity.write_text('PRIVATE_KEY_SENTINEL');self.identity.chmod(0o600)
        raw=bytes.fromhex('0000000b7373682d6564323535313900000020')+bytes(range(32));key='ssh-ed25519 '+base64.b64encode(raw).decode()
        self.values={'format':1,'mechanism':'RSYNC_SSH','direction':'CLIENT_TO_SERVER','deviceId':str(uuid.uuid4()),'minimumRsyncVersion':'3.5.1','hostname':'ssh.example.invalid','port':2222,'username':'journal_test','remotePath':'.','hostKey':key,'hostKeyFingerprint':'SHA256:'+base64.b64encode(hashlib.sha256(raw).digest()).decode().rstrip('='),'hostAlias':'timekeeper-journal-'+'a'*24}
        self.values['knownHostsEntry']=self.values['hostAlias']+' '+key;self.profile=self.root/'profile.json';self.write_profile()
        self.args=argparse.Namespace(profile=self.profile,folder=str(self.notes),identity=str(self.identity),fingerprint=self.values['hostKeyFingerprint'],replace=False)
    def write_profile(self):self.profile.write_text(json.dumps(self.values))
    def configure(self):
        client.configure(self.args);client.trust(self.args)
    def test_profile_cannot_inject_ssh_commands_or_replace_the_host_key_silently(self):
        self.configure();self.values['hostname']='-oProxyCommand=command';self.write_profile()
        with self.assertRaises(client.Failure):client.profile(self.profile)
        self.values['hostname']='ssh.example.invalid';self.values['hostKeyFingerprint']='SHA256:wrong';self.write_profile()
        with self.assertRaises(client.Failure):client.trust(self.args)
    def test_local_private_key_contents_are_never_put_in_configuration_or_schedules(self):
        self.configure();local,state=client.paths(self.profile);self.assertEqual(0o600,local.stat().st_mode&0o777);self.assertEqual(0o700,state.stat().st_mode&0o777)
        self.args.output_directory=None;self.args.interval=300;self.args.platform='macos'
        with patch.dict(os.environ,{'SSH_AUTH_SOCK':str(self.root/'agent.sock')}):client.schedule(self.args)
        plist=next((state/'schedule').glob('*.plist'));values=plistlib.loads(plist.read_bytes());self.assertEqual(str(self.profile),values['ProgramArguments'][-1]);self.assertNotIn('PRIVATE_KEY_SENTINEL',plist.read_text());self.assertEqual(os.environ.get('PATH',os.defpath),values['EnvironmentVariables']['PATH'])
        self.args.platform='linux'
        with patch.dict(os.environ,{'SSH_AUTH_SOCK':str(self.root/'agent.sock')}):client.schedule(self.args)
        self.assertNotIn('PRIVATE_KEY_SENTINEL',next((state/'schedule').glob('*.service')).read_text())
        self.assertNotIn('PRIVATE_KEY_SENTINEL',local.read_text());self.assertEqual(0o600,(state/'known_hosts').stat().st_mode&0o777)
    def test_private_file_aliases_and_broad_permissions_are_rejected(self):
        self.identity.chmod(0o644)
        with self.assertRaises(client.Failure):client.configure(self.args)
        self.identity.chmod(0o600);alias=self.root/'alias';alias.symlink_to(self.identity);self.args.identity=str(alias)
        with self.assertRaises(client.Failure):client.configure(self.args)
    def test_systemd_schedule_escapes_exec_expansion_but_keeps_the_agent_environment_literal(self):
        self.profile=self.root/'$PROFILE%name.json';self.write_profile();self.args.profile=self.profile;self.configure()
        self.args.output_directory=None;self.args.interval=300;self.args.platform='linux'
        with patch.dict(os.environ,{'SSH_AUTH_SOCK':str(self.root/'$AGENT%socket')}):client.schedule(self.args)
        service=next((client.paths(self.profile)[1]/'schedule').glob('*.service')).read_text()
        self.assertIn('$$PROFILE%%name.json',service);self.assertIn('$AGENT%%socket',service);self.assertNotIn('$$AGENT',service)
    def test_ssh_arguments_keep_paths_quoted_and_host_verification_mandatory(self):
        self.configure();data,_,identity,state=client.local_config(self.args);arguments=client.ssh_arguments(data,identity,state)
        self.assertIn('StrictHostKeyChecking=yes',arguments);self.assertIn('ClearAllForwardings=yes',arguments);self.assertIn('BatchMode=yes',arguments);self.assertIn(str(self.identity),arguments)
        self.assertIn('UserKnownHostsFile="'+str(state/'known_hosts')+'"',arguments)
    def test_snapshot_preserves_bytes_and_excludes_symlinks_oversize_and_unrelated_vault_files(self):
        self.configure();original=b'\xef\xbb\xbf---\r\nunknown: preserved\r\n---\r\n\r\n# Daily  \r\n'
        (self.notes/'2026-10-25.md').write_bytes(original);(self.notes/'unrelated.md').write_text('UNRELATED_SECRET');(self.notes/'.obsidian').mkdir();(self.notes/'.obsidian/config').write_text('VAULT_SECRET')
        (self.notes/'2026-10-26.md').symlink_to(self.identity);(self.notes/'2026-10-27.md').write_bytes(b'x'*220001)
        batches=[]
        def transfer(data,identity,state,snapshot,entries,errors):batches.append((list(entries),list(errors),{path.name:path.read_bytes() for path in snapshot.iterdir()}))
        with patch.object(client,'rsync_version'),patch.object(client,'transfer',side_effect=transfer):
            with self.assertRaises(client.Failure):client.sync(self.args)
        self.assertEqual(original,batches[0][2]['2026-10-25.md']);self.assertEqual({'2026-10-25.md'},set(batches[0][2]));self.assertEqual({'UNSAFE_LOCAL_FILE','FILE_TOO_LARGE'},{entry['code'] for entry in batches[0][1]})
        self.assertEqual(original,(self.notes/'2026-10-25.md').read_bytes());self.assertFalse(any(client.paths(self.profile)[1].glob('snapshot-*')))
    def test_batches_bound_large_initial_folders_and_missing_files_never_add_deletion_options(self):
        self.configure()
        for index in range(1,4):
            for month in range(1,13):
                for day in range(1,29):(self.notes/f'{2020+index}-{month:02}-{day:02}.md').write_text('body')
        sizes=[]
        with patch.object(client,'rsync_version'),patch.object(client,'transfer',side_effect=lambda d,i,s,p,entries,errors:sizes.append(len(entries)+len(errors))):client.sync(self.args)
        self.assertEqual(1008,sum(sizes));self.assertLessEqual(max(sizes),100)

if __name__=='__main__':unittest.main()
