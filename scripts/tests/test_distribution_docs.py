"""Verify relocated guides remain usable outside the source checkout."""
from pathlib import Path
import re
import sys
import tarfile
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'server/scripts'))
from documentation import render_document
from package_deployment import package
import package_server

COMMIT = 'a' * 40


class DistributionDocumentationTest(unittest.TestCase):
    def test_repository_links_become_local_guides_or_immutable_source_links(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('docs/operations/SERVER.md', 'docs/architecture/API.md',
                         'server/scripts/create_token.py', 'server/scripts/configure_accounts.py'):
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
                self.assertIn('[shared Tailscale access guide](TAILSCALE.md)', local)
                self.assertIn('[shared Tailscale guide](TAILSCALE.md)', archive.extractfile('README.md').read().decode())
                for name in ('README.md', 'LOCAL.md', 'SERVER.md', 'API-POLICY.md', 'TAILSCALE.md'):
                    text = archive.extractfile(name).read().decode()
                    for url in re.findall(r'\]\(([^\s)]+)\)', text):
                        parts = urlsplit(url)
                        if parts.scheme or not parts.path:
                            continue
                        self.assertIn(parts.path, names, (name, url))

    def test_server_zip_includes_shared_tailscale_guide_with_local_links(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for name in ('server/app/target/quarkus-app/quarkus-run.jar',
                         'server/app/target/quarkus-app/app/fixture.jar',
                         'server/app/target/quarkus-app/lib/fixture.jar',
                         'server/app/target/quarkus-app/quarkus/fixture.jar',
                         'common/api/openapi.json','server/scripts/create_token.py', 'server/scripts/configure_accounts.py','LICENSE'):
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('Packaging fixture')
            with zipfile.ZipFile(root/'server/app/target/quarkus-app/app/fixture.jar','w') as jar:
                jar.writestr('timekeeper-build.properties', f'commit={COMMIT}\ndirty=false\n')
            for name in ('day-notes-desktop.png','day-notes-editor.png','journal-editor-mobile.png','mermaid-editor.png','about-references-desktop.png','work-session-editor-delete-confirm.png','holiday-preferences-mobile.png',
                         'public-holidays-desktop.png','public-holidays-details-mobile.png',
                         'calendar-subscriptions-desktop.png','calendar-subscriptions-details-mobile.png',
                         'locale-norwegian-account.png','locale-norwegian-administration.png',
                         'activities-mobile.png','project-icon-picker.png','track-project-icons.png','track-project-icons-mobile.png',
                         'compact-workweek-desktop.png','compact-workweek-mobile.png','projects-grouped-mobile.png',
                         'project-report-desktop.png',
                         'bootstrap-work-session-desktop.png','bootstrap-work-session-narrow.png',
                         'passkeys-desktop.png','passkeys-mobile.png',
                         'account-data-review-desktop.png','account-data-review-mobile.png',
                         'account-menu-mobile.png','account-labels-mobile.png','week-day-note-editor-mobile.png',
                         'journal-heading-menu-mobile.png',
                         'track-month-desktop.png','track-month-narrow.png','track-month-picker.png',
                         'toggl-import-review.png','toggl-import-history-narrow.png'):
                image = root/'docs/assets/screenshots'/name
                image.parent.mkdir(parents=True,exist_ok=True)
                image.write_bytes((ROOT/'docs/assets/screenshots'/name).read_bytes())
            # Exercise ZIP assembly with synthetic JVM files and the real operational guides.
            with patch.object(package_server,'ROOT',root),patch.object(package_server.subprocess,'check_output',side_effect=[COMMIT,'']), \
                    patch.object(package_server,'render_document',side_effect=lambda _,source,destinations,commit:render_document(ROOT,source,destinations,commit)):
                package_server.package(root/'output',None,COMMIT)
            with zipfile.ZipFile(root/'output/timekeeper-server.zip') as archive:
                names=set(archive.namelist())
                guide=archive.read('timekeeper-server/TAILSCALE.md').decode()
                self.assertIn('[Synology deployment guide](DEPLOYMENT.md)',guide)
                self.assertIn('[local Docker guide](LOCAL-DOCKER.md)',guide)
                web = archive.read('timekeeper-server/WEB-CLIENT.md').decode()
                for url in re.findall(r'!\[[^\]]*\]\(([^\s)]+)\)', web):
                    parts = urlsplit(url)
                    self.assertFalse(parts.scheme or parts.netloc, url)
                    self.assertTrue(archive.read('timekeeper-server/'+parts.path).startswith(b'\x89PNG\r\n\x1a\n'), url)
                for view in ('passkeys','account-data-review'):
                    for size in ('desktop','mobile'):
                        name=f'images/{view}-{size}.png'
                        self.assertIn(']('+name+')',web)
                        self.assertTrue(archive.read('timekeeper-server/'+name).startswith(b'\x89PNG\r\n\x1a\n'))
                for view in ('work-session', 'track-month'):
                    for size in ('desktop','narrow'):
                        name = f'images/{view}-{size}.png'
                        self.assertIn(']('+name+')',web)
                        self.assertTrue(archive.read('timekeeper-server/'+name).startswith(b'\x89PNG\r\n\x1a\n'))
                for name in ('images/toggl-import-review.png','images/toggl-import-history-narrow.png'):
                    self.assertIn(']('+name+')',web)
                    self.assertTrue(archive.read('timekeeper-server/'+name).startswith(b'\x89PNG\r\n\x1a\n'))
                for name in ('DEPLOYMENT.md','LOCAL-DOCKER.md','TAILSCALE.md','WEB-CLIENT.md','TOGGL-IMPORT.md','ACCOUNT-DATA.md'):
                    text=archive.read('timekeeper-server/'+name).decode()
                    for url in re.findall(r'\]\(([^\s)]+)\)',text):
                        parts=urlsplit(url)
                        if not parts.scheme and parts.path:
                            self.assertIn('timekeeper-server/'+parts.path,names,(name,url))


if __name__ == '__main__':
    unittest.main()
