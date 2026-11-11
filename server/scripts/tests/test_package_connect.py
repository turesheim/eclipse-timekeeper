import sys
from pathlib import Path
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from package_connect import verify_identity


class ConnectPackagingTest(unittest.TestCase):
    def test_stale_dirty_or_missing_service_identity_cannot_be_packaged(self):
        with tempfile.TemporaryDirectory() as folder:
            app = Path(folder)
            (app / 'app').mkdir()
            for properties, valid in ((None, False), ('commit=' + 'a' * 40 + '\ndirty=true\n', False),
                                      ('commit=' + 'b' * 40 + '\ndirty=false\n', False),
                                      ('commit=' + 'a' * 40 + '\ndirty=false\n', True)):
                with zipfile.ZipFile(app / 'app/service.jar', 'w') as jar:
                    if properties:
                        jar.writestr('connect-build.properties', properties)
                if valid:
                    verify_identity(app, 'a' * 40)
                else:
                    with self.assertRaises(ValueError):
                        verify_identity(app, 'a' * 40)
