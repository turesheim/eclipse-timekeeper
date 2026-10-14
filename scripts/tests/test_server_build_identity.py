"""Do not publish stale or dirty JVM artifacts under a clean source manifest."""
import sys
import tempfile
import unittest
from pathlib import Path
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'server/scripts'))
from package_server import verify_build_identity

class ServerBuildIdentityTest(unittest.TestCase):
    def test_embedded_identity_must_match_the_clean_source(self):
        with tempfile.TemporaryDirectory() as directory:
            app=Path(directory);(app/'app').mkdir()
            for commit,dirty,valid in [('a'*40,'false',True),('b'*40,'false',False),('a'*40,'true',False),('unknown','unknown',False)]:
                with zipfile.ZipFile(app/'app/server.jar','w') as jar:
                    jar.writestr('timekeeper-build.properties',f'commit={commit}\ndirty={dirty}\n')
                if valid: verify_build_identity(app,'a'*40)
                else:
                    with self.assertRaises(ValueError): verify_build_identity(app,'a'*40)
    def test_missing_or_ambiguous_runtime_identity_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            app=Path(directory);(app/'app').mkdir()
            with self.assertRaises(ValueError): verify_build_identity(app,'a'*40)
            for name in ['one','two']:
                with zipfile.ZipFile(app/'app'/f'{name}.jar','w') as jar:
                    jar.writestr('timekeeper-build.properties','commit='+ 'a'*40 +'\ndirty=false\n')
            with self.assertRaises(ValueError): verify_build_identity(app,'a'*40)
