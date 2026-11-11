"""CI package-source changes must retain Ubuntu's existing trust configuration."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("ci_ubuntu_mirror", Path(__file__).resolve().parents[1] / "ci_ubuntu_mirror.py")
mirror = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mirror)


class UbuntuMirrorTest(unittest.TestCase):
    def test_both_mirror_lists_change_without_replacing_sources_or_keyrings(self):
        with tempfile.TemporaryDirectory() as temporary:
            apt = Path(temporary)
            for name in mirror.MIRROR_LISTS:
                (apt / name).write_text("https://archive.ubuntu.com/ubuntu/ priority:1\nhttps://security.ubuntu.com/ubuntu/ priority:2\n")
            sources = apt / "sources.list.d"
            sources.mkdir()
            ubuntu = sources / "ubuntu.sources"
            trusted = "Types: deb\nURIs: mirror+file:/etc/apt/apt-mirrors.txt\nSuites: resolute resolute-updates resolute-security\nComponents: main universe\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n"
            ubuntu.write_text(trusted)
            other = sources / "microsoft-prod.list"
            unrelated = "deb [signed-by=/usr/share/keyrings/microsoft-prod.gpg] https://packages.microsoft.com/ubuntu/26.04/prod resolute main\n"
            other.write_text(unrelated)
            self.assertEqual(2, len(mirror.configure(apt)))
            for name in mirror.MIRROR_LISTS:
                self.assertEqual(mirror.MIRROR + "\n", (apt / name).read_text())
            self.assertEqual(trusted, ubuntu.read_text())
            self.assertEqual(unrelated, other.read_text())

    def test_runner_with_only_the_main_mirror_list_is_supported_and_reconfiguration_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temporary:
            apt = Path(temporary)
            (apt / "apt-mirrors.txt").write_text("http://azure.archive.ubuntu.com/ubuntu/\n")
            self.assertEqual([apt / "apt-mirrors.txt"], mirror.configure(apt))
            before = (apt / "apt-mirrors.txt").read_bytes()
            mirror.configure(apt)
            self.assertEqual(before, (apt / "apt-mirrors.txt").read_bytes())
            self.assertFalse((apt / "apt-mirrors-security.txt").exists())

    def test_unexpected_runner_layout_fails_without_changing_other_package_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            apt = Path(temporary)
            original = apt / "sources.list"
            original.write_text("deb https://archive.ubuntu.com/ubuntu/ resolute main\n")
            with self.assertRaisesRegex(FileNotFoundError, "GitHub-hosted Ubuntu"):
                mirror.configure(apt)
            self.assertEqual("deb https://archive.ubuntu.com/ubuntu/ resolute main\n", original.read_text())
            self.assertEqual([original], list(apt.iterdir()))
