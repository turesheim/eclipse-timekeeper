"""Verify relocated guides remain usable outside the source checkout."""
from pathlib import Path
import re
import sys
import tarfile
import tempfile
import unittest
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'server/scripts'))
from documentation import render_document
from package_deployment import package

COMMIT = 'a' * 40


class DistributionDocumentationTest(unittest.TestCase):
    def test_repository_links_become_local_guides_or_immutable_source_links(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('docs/operations/SERVER.md', 'docs/architecture/API.md',
                         'server/scripts/create_token.py'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            (root / 'docs/operations/SERVER.md').write_text(
                '[API](../architecture/API.md#ownership) '
                '[Tool](../../server/scripts/create_token.py) '
                '[External](https://example.invalid/help) [Section](#tracking)')
            result = render_document(root, 'docs/operations/SERVER.md',
                                     {'docs/architecture/API.md': 'API-POLICY.md'}, COMMIT)
            self.assertIn('[API](API-POLICY.md#ownership)', result)
            self.assertIn(f'/blob/{COMMIT}/server/scripts/create_token.py', result)
            self.assertIn('[External](https://example.invalid/help)', result)
            self.assertIn('[Section](#tracking)', result)

    def test_missing_repository_documentation_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'README.md').write_text('[Guide](missing.md)')
            with self.assertRaisesRegex(ValueError, 'Missing documentation link'):
                render_document(root, 'README.md', {}, COMMIT)

    def test_compose_bundle_contains_complete_guides_and_no_private_state(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'deployment.tar.gz'
            package(output, COMMIT)
            with tarfile.open(output) as archive:
                names = set(archive.getnames())
                self.assertNotIn('.env', names)
                self.assertFalse(any(name.startswith(('state/', 'secrets/')) for name in names))
                local = archive.extractfile('LOCAL.md').read().decode()
                self.assertIn('python3 setup.py --owner alice', local)
                self.assertIn('http://127.0.0.1:8080/web/', local)
                self.assertIn('[deployment guide](README.md)', local)
                for name in ('README.md', 'LOCAL.md', 'SERVER.md', 'API-POLICY.md'):
                    text = archive.extractfile(name).read().decode()
                    for url in re.findall(r'\]\(([^\s)]+)\)', text):
                        parts = urlsplit(url)
                        if parts.scheme or not parts.path:
                            continue
                        self.assertIn(parts.path, names, (name, url))


if __name__ == '__main__':
    unittest.main()
