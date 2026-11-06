"""Exercise routing, source identity and real Git change detection in isolation."""
from contextlib import redirect_stdout, redirect_stderr
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import quality


class RepositoryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "user.name", "Quality fixture")
        self.write(".gitignore", "target/\n")
        self.write("README.md", "Fixture\n")
        self.git("add", ".")
        self.git("commit", "-qm", "Fixture")

    def git(self, *args):
        return quality.git(self.root, *args)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)


class RoutingTest(unittest.TestCase):
    def test_minimum_groups_and_conservative_unknown_paths(self):
        for paths, expected in [
            (["docs/example.md"], ["quick"]),
            (["server/web/src/theme.js"], ["quick", "web"]),
            ([".agents/skills/dagsverk-change/SKILL.md"], ["quick", "tooling"]),
            ([".codex/hooks.json"], ["quick", "tooling"]),
            (["server/app/pom.xml"], ["quick", "contract", "server"]),
            (["common/timekeeper-domain/Record.java"], ["quick", "contract", "web", "server", "eclipse"]),
            (["new-product/source"], ["quick", "contract", "web", "server", "eclipse"]),
        ]:
            with self.subTest(paths=paths):
                self.assertEqual(quality.select_checks(paths), expected)
        self.assertEqual(quality.select_checks(["common/Record.java"], "quick"), ["quick"])


class GitStateTest(RepositoryTest):
    def test_staged_unstaged_untracked_and_deleted_paths(self):
        self.write("staged.py", "x = 1\n")
        self.git("add", "staged.py")
        self.write("README.md", "Changed\n")
        self.write("untracked file.json", "{}\n")
        self.write("target/ignored.json", "{}")
        self.assertEqual(quality.changed_paths(self.root), ["README.md", "staged.py", "untracked file.json"])
        (self.root / "README.md").unlink()
        self.assertIn("README.md", quality.changed_paths(self.root))

    def test_baseline_preserves_existing_changes_but_detects_later_edits(self):
        self.write("README.md", "Existing user edit\n")
        self.write("existing.json", "{}\n")
        baseline = quality.snapshot(self.root)
        self.assertEqual(quality.session_changes(self.root, baseline), [])
        self.write("existing.json", '{"edited":true}\n')
        self.write("new.py", "x = 1\n")
        self.assertEqual(quality.session_changes(self.root, baseline), ["existing.json", "new.py"])

    def test_staged_content_is_detected_when_worktree_has_been_restored(self):
        self.write("README.md", "Staged change\n")
        self.git("add", "README.md")
        self.write("README.md", "Fixture\n")
        self.assertEqual(quality.changed_paths(self.root), ["README.md"])
        before = quality.fingerprint(self.root)
        self.git("reset", "-q", "HEAD", "README.md")
        self.assertNotEqual(quality.fingerprint(self.root), before)

    def test_explicit_path_scope_cannot_escape_repository(self):
        with patch.object(quality, "ROOT", self.root), redirect_stderr(io.StringIO()):
            self.assertEqual(quality.main(["--paths", "../outside.py", "--plan"]), 1)

    def test_explicit_scope_selects_task_checks_without_unrelated_components(self):
        self.write("unrelated/source.java", "class Example {}\n")
        self.write("server/web/src/example.js", "const value = 1;\n")
        output = io.StringIO()
        with patch.object(quality, "ROOT", self.root), redirect_stdout(output):
            self.assertEqual(quality.main(["--paths", "server/web/src/example.js", "--plan"]), 0)
        self.assertIn("quick, web", output.getvalue())
        self.assertNotIn("Checking eclipse", output.getvalue())

    def test_committed_changes_are_detected_after_session_start(self):
        baseline = quality.snapshot(self.root)
        self.write("README.md", "New commit\n")
        self.git("add", ".")
        self.git("commit", "-qm", "During session")
        self.assertEqual(quality.session_changes(self.root, baseline), ["README.md"])
        self.assertEqual(quality.changed_paths(self.root, baseline["head"]), ["README.md"])

    def test_receipt_expires_after_edit_and_cannot_cover_unexecuted_groups(self):
        receipt = {"fingerprint": quality.fingerprint(self.root), "passed": True, "checks": ["quick", "web"]}
        quality.write_json(quality.receipt_path(self.root, "affected"), receipt)
        self.assertTrue(quality.verified(self.root, ["web"]))
        self.assertFalse(quality.verified(self.root, ["server"]))
        self.write("new.py", "x = 1\n")
        self.assertFalse(quality.verified(self.root, ["web"]))

    def test_quick_checks_invalid_source_and_limits_diff_to_selected_files(self):
        self.write("README.md", "Pre-existing trailing whitespace  \n")
        self.write("invalid.py", "def broken(\n")
        self.write("invalid.json", "{")
        errors = quality.quick_check(self.root, ["invalid.py", "invalid.json"])
        self.assertEqual(len(errors), 2)
        self.assertTrue(any("invalid.py" in error for error in errors))
        self.assertTrue(any("invalid.json" in error for error in errors))
        self.write("valid.py", "x = 1\n")
        self.assertEqual(quality.quick_check(self.root, ["valid.py"]), [])

    def test_cli_records_success_and_failure_and_rejects_edits_during_validation(self):
        self.write("server/web/src/example.js", "const value = 1;\n")
        with patch.object(quality, "ROOT", self.root), redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            for body, expected in [("pass", 0), ("raise SystemExit(7)", 1),
                                   ("from pathlib import Path; Path('README.md').write_text('Changed during check')", 1)]:
                with self.subTest(body=body), patch.object(quality, "commands", return_value={"web": [sys.executable, "-c", body]}):
                    self.assertEqual(quality.main(["--affected"]), expected)
                    receipt = json.loads(quality.receipt_path(self.root, "affected").read_text())
                    self.assertEqual(receipt["passed"], expected == 0)


if __name__ == "__main__":
    unittest.main()
