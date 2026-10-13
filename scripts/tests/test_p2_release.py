import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

spec = importlib.util.spec_from_file_location("p2_release", Path(__file__).parents[1] / "p2_release.py")
p2 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p2)


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        self.repository.mkdir()
        self.version = "2.0.0.202610111300"
        for directory, identity in (("features", "net.resheim.eclipse.timekeeper"), ("plugins", "net.resheim.eclipse.timekeeper.ui")):
            (self.repository / directory).mkdir()
            with zipfile.ZipFile(self.repository / directory / f"{identity}_{self.version}.jar", "w") as jar:
                jar.writestr("fixture.txt", "Test artifact")
        with zipfile.ZipFile(self.repository / "content.jar", "w") as jar:
            jar.writestr("content.xml", f'<repository><units><unit id="{p2.FEATURE}" version="{self.version}"/></units></repository>')
        with zipfile.ZipFile(self.repository / "artifacts.jar", "w") as jar:
            jar.writestr("artifacts.xml", f'''<repository><artifacts>
                <artifact classifier="org.eclipse.update.feature" id="net.resheim.eclipse.timekeeper" version="{self.version}"/>
                <artifact classifier="osgi.bundle" id="net.resheim.eclipse.timekeeper.ui" version="{self.version}"/>
                </artifacts></repository>''')
        (self.repository / "p2.index").write_text("version = 1\n")

    def assets(self, tag="v2.0.0"):
        assets = self.root / tag
        p2.package(self.repository, tag, "a" * 40, assets)
        return assets

    def release(self, tag, draft=False, prerelease=False, date="2026-10-11T13:00:00Z"):
        return {"tag_name": tag, "draft": draft, "prerelease": prerelease, "published_at": date,
                "assets": [{"name": p2.ARCHIVE}, {"name": p2.CHECKSUM}]}

    def test_packaged_archive_is_installable_at_root_and_has_provenance(self):
        assets = self.assets()
        destination = self.root / "unpacked"
        p2.extract_verified(assets, destination, "v2.0.0")
        self.assertTrue((destination / "content.jar").is_file())
        self.assertEqual(self.version, p2.validate_repository(destination, "v2.0.0"))
        self.assertEqual("a" * 40, json.loads((destination / p2.PROVENANCE).read_text())["commit"])

    def test_wrong_release_version_and_missing_artifact_are_refused(self):
        with self.assertRaisesRegex(ValueError, "matching 3.0.0"):
            p2.package(self.repository, "v3.0.0", "a" * 40, self.root / "wrong")
        next((self.repository / "plugins").glob("*.jar")).unlink()
        with self.assertRaisesRegex(ValueError, "Missing repository artifact"):
            self.assets()

    def test_damaged_archive_is_refused_before_extraction(self):
        assets = self.assets()
        with (assets / p2.ARCHIVE).open("ab") as archive:
            archive.write(b"corruption")
        with self.assertRaisesRegex(ValueError, "checksum"):
            p2.extract_verified(assets, self.root / "unpacked", "v2.0.0")
        self.assertFalse((self.root / "unpacked").exists())

    def test_zip_cannot_write_outside_the_repository(self):
        assets = self.assets()
        with zipfile.ZipFile(assets / p2.ARCHIVE, "a") as archive:
            archive.writestr("../outside.txt", "bad")
        digest = hashlib.sha256((assets / p2.ARCHIVE).read_bytes()).hexdigest()
        (assets / p2.CHECKSUM).write_text(f"{digest}  {p2.ARCHIVE}\n")
        with self.assertRaisesRegex(ValueError, "Unsafe ZIP member"):
            p2.extract_verified(assets, self.root / "unpacked", "v2.0.0")
        self.assertFalse((self.root / "outside.txt").exists())

    def test_assets_cannot_be_relabelled_as_a_different_release(self):
        with self.assertRaisesRegex(ValueError, "provenance"):
            p2.extract_verified(self.assets(), self.root / "unpacked", "v2.0.1")

    def test_channels_preserve_old_versions_and_exclude_prereleases(self):
        releases = [self.release("v2.0.0"), self.release("v2.0.0-rc.1", prerelease=True),
                    self.release("v2.0.0-beta.1"), self.release("v2.0.0-rc.2", draft=True)]
        old = self.release("v1.1.0")
        old["assets"] = []  # Historical release ZIPs use an older storage format.
        releases.append(old)
        assets = {release["tag_name"]: self.assets(release["tag_name"]) for release in releases[:3]}

        def download(release, destination):
            for path in assets[release["tag_name"]].iterdir():
                shutil.copy2(path, destination / path.name)

        output = self.root / "site"
        output.mkdir()
        (output / "index.html").write_text("Existing project website")
        p2.assemble_site(releases, output, download)
        stable = ET.parse(output / "updates/compositeContent.xml")
        preview = ET.parse(output / "updates/preview/compositeContent.xml")
        self.assertEqual(["releases/v2.0.0"], [child.get("location") for child in stable.findall("./children/child")])
        self.assertEqual({"../releases/v2.0.0-rc.1", "../releases/v2.0.0-beta.1"}, {child.get("location") for child in preview.findall("./children/child")})
        self.assertEqual("Existing project website", (output / "index.html").read_text())
        self.assertTrue((output / "updates/releases/v2.0.0/plugins").is_dir())
        self.assertFalse((output / "updates/releases/v2.0.0-rc.2").exists())

    def test_multiple_stable_releases_remain_available(self):
        releases = [self.release("v2.0.0"), self.release("v2.0.1")]
        assets = self.assets()
        newer = self.root / "v2.0.1"
        self.version = "2.0.1.202610111400"
        # Build an independent second repository by updating the fixture version.
        for metadata in ("content", "artifacts"):
            with zipfile.ZipFile(self.repository / f"{metadata}.jar") as jar:
                xml = jar.read(f"{metadata}.xml").decode().replace("2.0.0.202610111300", self.version)
            with zipfile.ZipFile(self.repository / f"{metadata}.jar", "w") as jar:
                jar.writestr(f"{metadata}.xml", xml)
        for artifact in self.repository.glob("*/*.jar"):
            artifact.rename(artifact.with_name(artifact.name.replace("2.0.0.202610111300", self.version)))
        p2.package(self.repository, "v2.0.1", "b" * 40, newer)

        def download(release, destination):
            for path in (assets if release["tag_name"] == "v2.0.0" else newer).iterdir():
                shutil.copy2(path, destination / path.name)

        p2.assemble_site(releases, self.root / "site", download)
        root = ET.parse(self.root / "site/updates/compositeContent.xml")
        self.assertEqual(["releases/v2.0.1", "releases/v2.0.0"], [child.get("location") for child in root.findall("./children/child")])

    def test_partial_asset_upload_does_not_produce_a_new_site(self):
        release = self.release("v2.0.0")
        release["assets"].pop()
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            p2.assemble_site([release], self.root / "site", lambda *_: None)
        self.assertFalse((self.root / "site/updates/compositeContent.xml").exists())

    def test_empty_site_has_valid_channels(self):
        p2.assemble_site([], self.root / "site", lambda *_: self.fail("No download expected"))
        root = ET.parse(self.root / "site/updates/compositeArtifacts.xml")
        self.assertEqual("0", root.find("children").get("size"))
        self.assertIn("No release repositories", (self.root / "site/updates/index.html").read_text())

    def _testing_fixture(self):
        archive=self.root/"testing-artifact.zip"
        with zipfile.ZipFile(archive,"w") as zipped:
            for path in self.repository.rglob("*"):
                if path.is_file():zipped.write(path,path.relative_to(self.repository).as_posix())
        run={"id":123,"status":"completed","conclusion":"success","event":"push",
             "head_branch":"main","path":".github/workflows/build.yml","head_sha":"a"*40,
             "head_repository":{"full_name":"owner/repo"}}
        jobs=[{"name":name,"status":"completed","conclusion":"success"} for name in (
            "Publication","API-contract","Server","Web","Build (ubuntu-latest)",
            "Build (macos-latest)","Build (windows-latest)","Installed client tests")]
        artifact={"id":456,"name":"p2-repository","expired":False,
                  "digest":"sha256:"+hashlib.sha256(archive.read_bytes()).hexdigest(),
                  "workflow_run":{"id":123,"head_sha":"a"*40,"head_branch":"main"}}
        def github(*args):
            path=args[1]
            if path=="repos/owner/repo":return "main\n"
            if "workflows/build.yml/runs" in path:return json.dumps({"workflow_runs":[run]})
            if "/jobs" in path:return json.dumps({"jobs":jobs})
            if "/artifacts" in path:return json.dumps({"artifacts":[artifact]})
            self.fail("Unexpected API request: "+path)
        def download(repo,artifact_id,destination):
            self.assertEqual(("owner/repo",456),(repo,artifact_id));shutil.copyfile(archive,destination)
        return run,jobs,artifact,archive,github,download

    def test_verified_main_matrix_artifact_populates_only_testing_channel(self):
        run,jobs,artifact,archive,github,download=self._testing_fixture()
        output=self.root/"site"
        p2.assemble_site([],output,lambda *_:self.fail("No release download expected"))
        with patch.object(p2,"gh",side_effect=github),patch.object(p2,"download_testing_artifact",side_effect=download):
            result=p2.add_testing_channel("owner/repo",output)
        self.assertEqual("a"*40,result["commit"])
        self.assertEqual(self.version,result["version"])
        children=ET.parse(output/"updates/testing/compositeContent.xml").findall("./children/child")
        self.assertEqual(["builds/123-456"],[child.get("location") for child in children])
        repository=output/"updates/testing"/children[0].get("location")
        self.assertEqual(self.version,p2.validate_repository(repository,"v2.0.0"))
        self.assertEqual(result,json.loads((repository/"timekeeper-build.json").read_text()))
        self.assertEqual("0",ET.parse(output/"updates/compositeContent.xml").find("children").get("size"))
        self.assertIn("unreleased main build",(output/"updates/testing/index.html").read_text())

    def test_fork_pr_and_incomplete_matrix_cannot_supply_testing_site(self):
        for change in ("fork","pr","failed","skipped-client"):
            with self.subTest(change=change):
                run,jobs,artifact,archive,github,download=self._testing_fixture()
                if change=="fork":run["head_repository"]["full_name"]="fork/repo"
                if change=="pr":run["event"]="pull_request"
                if change=="failed":run["conclusion"]="failure"
                if change=="skipped-client":jobs[-1]["conclusion"]="skipped"
                with patch.object(p2,"gh",side_effect=github),patch.object(p2,"download_testing_artifact") as fetch:
                    self.assertIsNone(p2.add_testing_channel("owner/repo",self.root/"site"))
                    fetch.assert_not_called()

    def test_testing_artifact_must_match_source_and_download_checksum(self):
        for change in ("commit","checksum"):
            with self.subTest(change=change):
                run,jobs,artifact,archive,github,download=self._testing_fixture()
                if change=="commit":artifact["workflow_run"]["head_sha"]="b"*40
                else:
                    with archive.open("ab") as stream:stream.write(b"corrupted")
                with patch.object(p2,"gh",side_effect=github),patch.object(p2,"download_testing_artifact",side_effect=download):
                    with self.assertRaisesRegex(ValueError,"does not match"):
                        p2.add_testing_channel("owner/repo",self.root/"site")

    def test_expired_main_artifact_keeps_testing_unavailable(self):
        run,jobs,artifact,archive,github,download=self._testing_fixture();artifact["expired"]=True
        with patch.object(p2,"gh",side_effect=github),patch.object(p2,"download_testing_artifact") as fetch:
            self.assertIsNone(p2.add_testing_channel("owner/repo",self.root/"site"));fetch.assert_not_called()

    def complete_assets(self, commit="a" * 40, tag="v2.0.0"):
        assets = self.assets(tag)
        with zipfile.ZipFile(assets / p2.SERVER_ARCHIVE, "w") as archive:
            archive.writestr("timekeeper-server/" + p2.PROVENANCE,
                            json.dumps({"kind": "release", "tag": tag, "commit": commit}))
        digest = hashlib.sha256((assets / p2.SERVER_ARCHIVE).read_bytes()).hexdigest()
        (assets / p2.SERVER_CHECKSUM).write_text(f"{digest}  {p2.SERVER_ARCHIVE}\n")
        return assets

    def test_complete_release_rejects_every_partial_upload(self):
        from itertools import combinations
        names = [p2.ARCHIVE, p2.CHECKSUM, p2.SERVER_ARCHIVE, p2.SERVER_CHECKSUM]
        release = {"tag_name": "v2.0.0", "assets": []}
        self.assertFalse(p2.complete_asset_state(release))
        for size in range(1, 4):
            for subset in combinations(names, size):
                with self.subTest(subset=subset), self.assertRaisesRegex(ValueError, "Incomplete"):
                    p2.complete_asset_state({**release, "assets": [{"name": name} for name in subset]})
        self.assertTrue(p2.complete_asset_state({**release, "assets": [{"name": name} for name in names]}))

    def test_complete_assets_require_shared_provenance_and_intact_server(self):
        assets = self.complete_assets()
        p2.verify_complete_assets(assets, "v2.0.0")
        with (assets / p2.SERVER_ARCHIVE).open("ab") as archive:
            archive.write(b"corruption")
        with self.assertRaisesRegex(ValueError, "checksum"):
            p2.verify_complete_assets(assets, "v2.0.0")
        assets = self.complete_assets(commit="b" * 40)
        with self.assertRaisesRegex(ValueError, "provenance"):
            p2.verify_complete_assets(assets, "v2.0.0")

    def test_full_preflight_verifies_downloads_before_emitting_reuse(self):
        assets = self.complete_assets()
        release = {"tagName": "v2.0.0", "isDraft": False,
                   "assets": [{"name": path.name} for path in assets.iterdir()]}
        def github(*args):
            if args[1] == "view":
                return json.dumps(release)
            destination = Path(args[args.index("--dir") + 1])
            for source in assets.iterdir():
                shutil.copy2(source, destination / source.name)
            return ""
        output = self.root / "output"
        with patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}), patch.object(p2, "gh", side_effect=github):
            p2.prepare("owner/repo", "v2.0.0", require_server=True)
            self.assertEqual("reuse=true\n", output.read_text())
            output.unlink()
            with (assets / p2.SERVER_ARCHIVE).open("ab") as archive:
                archive.write(b"corruption")
            with self.assertRaisesRegex(ValueError, "checksum"):
                p2.prepare("owner/repo", "v2.0.0", require_server=True)
            self.assertFalse(output.exists())

    def test_preflight_reuses_assets_and_rejects_drafts(self):
        release = {"tagName": "v2.0.0", "isDraft": False, "assets": self.release("v2.0.0")["assets"]}
        with patch.dict(os.environ, {"GITHUB_OUTPUT": str(self.root / "output")}), patch.object(p2, "gh", return_value=json.dumps(release)):
            p2.prepare("owner/repo", "v2.0.0")
            self.assertEqual("reuse=true\n", (self.root / "output").read_text())
            release["isDraft"] = True
            with patch.object(p2, "gh", return_value=json.dumps(release)), self.assertRaisesRegex(ValueError, "already published"):
                p2.prepare("owner/repo", "v2.0.0")


if __name__ == "__main__":
    unittest.main()
