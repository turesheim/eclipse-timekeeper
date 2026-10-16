"""DSM previews must retain interactive setup and a separate architecture/channel URL."""
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'server/scripts'))
from synology_catalog import entry

class SynologyCatalogTest(unittest.TestCase):
    def test_preview_discovery_requires_wizards_and_native_database_dependency(self):
        value=entry('1.99.0-42','https://example.invalid/timekeeper/synology/preview/r1000/',12345,'a'*32)
        self.assertEqual('https://example.invalid/timekeeper/synology/preview/r1000/timekeeper-1.99.0-42-r1000.spk',value['link'])
        self.assertFalse(value['qinst']);self.assertFalse(value['qupgrade']);self.assertFalse(value['qstart'])
        self.assertEqual('ContainerManager:postgresql>=17.11-6',value['deppkgs'])
        self.assertEqual('a'*32,value['md5']);self.assertEqual(12345,value['size'])

    def test_static_preview_catalog_cannot_be_written_as_a_stable_or_wrong_architecture_feed(self):
        for url in ('https://example.invalid/synology/stable/r1000/','https://example.invalid/synology/preview/arm64/',
                    'http://example.invalid/synology/preview/r1000/','https://secret:password@example.invalid/preview/r1000/',
                    'https://example.invalid/preview/r1000/?token=secret'):
            with self.subTest(url=url),self.assertRaises(ValueError):entry('1.99.0-42',url,12345,'a'*32)

    def test_only_stable_catalog_can_link_to_an_immutable_https_release_package(self):
        url='https://github.com/example/timekeeper/releases/download/v2.0.0/timekeeper-2.0.0-42-r1000.spk'
        base='https://example.invalid/synology/stable/r1000/'
        self.assertEqual(url,entry('2.0.0-42',base,12345,'a'*32,url)['link'])
        for invalid in (url.replace('https:', 'http:'),url+'?token=secret',url.replace('2.0.0-42-r1000.spk','2.0.0-43-r1000.spk')):
            with self.subTest(url=invalid),self.assertRaises(ValueError):entry('2.0.0-42',base,12345,'a'*32,invalid)
        with self.assertRaises(ValueError):entry('1.99.0-42','https://example.invalid/synology/preview/r1000/',12345,'a'*32,url)
