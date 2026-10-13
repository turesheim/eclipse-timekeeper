"""Build selection must fail open to validation, including code-to-doc renames."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ci_changes", Path(__file__).parents[1] / "ci_changes.py")
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class ChangesTest(unittest.TestCase):
    def check_paths(self, paths, expected):
        diff = subprocess.CompletedProcess([], 0, "\0".join(paths).encode() + b"\0", b"")
        with patch.object(ci.subprocess, "run", return_value=diff):
            self.assertEqual(expected, ci.requires_build({"before": "a" * 40}, "push", "refs/heads/main", "b" * 40))

    def test_documentation_only_and_mixed_changes(self):
        self.check_paths(["README.md", "server/README.md", "docs/assets/screenshots/workweek.png"], False)
        for path in ("server/web/src/app.js", "pom.xml", ".github/workflows/build.yml",
                     "scripts/ci_changes.py", "eclipse/default.target", "common/api/openapi.yaml"):
            with self.subTest(path=path):
                self.check_paths(["README.md", path], True)

    def test_removed_or_renamed_code_and_empty_diff_are_built(self):
        self.check_paths(["common/Example.java", "docs/Example.java"], True)
        self.check_paths([], True)

    def test_pr_uses_merge_base_and_push_uses_previous_head(self):
        diff = subprocess.CompletedProcess([], 0, b"README.md\0", b"")
        with patch.object(ci.subprocess, "run", return_value=diff) as git:
            ci.requires_build({"pull_request": {"base": {"sha": "abc"}}}, "pull_request", "refs/pull/1/merge", "def")
            self.assertEqual("abc...def", git.call_args.args[0][-1])
            ci.requires_build({"before": "abc"}, "push", "refs/heads/main", "def")
            self.assertEqual("abc..def", git.call_args.args[0][-1])
            self.assertIn("--no-renames", git.call_args.args[0])

    def test_tags_manual_new_branch_and_missing_history_always_build(self):
        for name, ref, event in (("push", "refs/tags/v2.0.0", {}),
                                 ("workflow_dispatch", "refs/heads/main", {}),
                                 ("push", "refs/heads/main", {"before": "0" * 40})):
            with patch.object(ci.subprocess, "run") as git:
                self.assertTrue(ci.requires_build(event, name, ref, "b" * 40))
                git.assert_not_called()
        with patch.object(ci.subprocess, "run", return_value=subprocess.CompletedProcess([], 128, b"", b"")):
            self.assertTrue(ci.requires_build({"before": "abc"}, "push", "refs/heads/main", "def"))

    def test_real_diff_for_readme_code_and_code_moved_to_docs(self):
        original = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                def git(*args):
                    return subprocess.check_output(["git", *args], text=True).strip()
                git("init", "-q")
                git("config", "user.name", "CI fixture")
                git("config", "user.email", "fixture@example.invalid")
                Path("README.md").write_text("Original guide")
                Path("Example.java").write_text("class Example {}")
                git("add", ".");git("commit", "-qm", "Baseline")
                base = git("rev-parse", "HEAD")
                Path("README.md").write_text("Corrected guide")
                git("add", ".");git("commit", "-qm", "README only")
                self.assertFalse(ci.requires_build({"before": base}, "push", "refs/heads/main", git("rev-parse", "HEAD")))
                Path("docs").mkdir();Path("Example.java").rename("docs/Example.java")
                git("add", ".");git("commit", "-qm", "Move code")
                self.assertTrue(ci.requires_build({"before": base}, "push", "refs/heads/main", git("rev-parse", "HEAD")))
            finally:
                os.chdir(original)
