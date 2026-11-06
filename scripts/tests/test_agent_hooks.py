"""Test hooks with real source changes and no external Codex session."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tomllib
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_hooks
import quality
from test_quality import RepositoryTest


class HookTest(RepositoryTest):
    def test_environment_reports_versions_without_echoing_runtime_option_values(self):
        results = [subprocess.CompletedProcess([], 0, "v24.2.0\n", ""),
                   subprocess.CompletedProcess([], 0, "", 'Picked up JAVA_TOOL_OPTIONS: -Dpassword=private-value\nopenjdk version "21.0.13"\n')]
        with patch.object(agent_hooks.subprocess, "run", side_effect=results):
            message = agent_hooks.environment()
        self.assertIn("java 21.0.13", message)
        self.assertNotIn("private-value", message)

    def event(self, name, **extra):
        return {"session_id": "fixture-session", "turn_id": "turn-1", "hook_event_name": name, **extra}

    def start(self):
        with patch.object(agent_hooks, "environment", return_value="Fixture tools"):
            return agent_hooks.handle(self.event("SessionStart"), self.root)

    def test_existing_changes_and_unchanged_advice_do_not_block(self):
        self.write("existing.py", "user_work = True\n")
        self.start()
        self.assertEqual(agent_hooks.handle(self.event("Stop"), self.root), {})

    def test_missing_baseline_is_visible_and_does_not_invent_pass(self):
        result = agent_hooks.handle(self.event("Stop"), self.root)
        self.assertIn("baseline missing", result["systemMessage"])
        self.assertNotIn("decision", result)

    def test_stop_continues_once_and_accepts_current_covering_receipt(self):
        self.start()
        self.write("server/web/src/example.js", "const value = 1;\n")
        result = agent_hooks.handle(self.event("Stop"), self.root)
        self.assertEqual(result["decision"], "block")
        self.assertIn("--base " + quality.snapshot(self.root)["head"], result["reason"])
        again = agent_hooks.handle(self.event("Stop"), self.root)
        self.assertNotIn("decision", again)
        self.assertIn("incomplete", again["systemMessage"])
        quality.write_json(quality.receipt_path(self.root, "affected"), {
            "fingerprint": quality.fingerprint(self.root), "passed": True, "checks": ["quick", "web"]})
        self.assertEqual(agent_hooks.handle(self.event("Stop"), self.root), {})
        self.write("server/web/src/example.js", "const value = 2;\n")
        self.assertEqual(agent_hooks.handle(self.event("Stop", turn_id="turn-2"), self.root)["decision"], "block")

    def test_active_stop_guard_prevents_recursive_continuation(self):
        self.start()
        self.write("new.py", "x = 1\n")
        result = agent_hooks.handle(self.event("Stop", stop_hook_active=True), self.root)
        self.assertNotIn("decision", result)
        self.assertIn("incomplete", result["systemMessage"])

    def test_later_advice_turn_does_not_reopen_unchanged_unverified_work(self):
        self.start()
        self.write("new.py", "x = 1\n")
        self.assertEqual(agent_hooks.handle(self.event("Stop"), self.root)["decision"], "block")
        self.assertEqual(agent_hooks.handle(self.event("Stop", turn_id="advice-turn"), self.root), {})

    def test_post_tool_checks_are_deduplicated_and_debounced(self):
        self.start()
        self.write("new.py", "def broken(\n")
        with patch.object(agent_hooks.time, "time", return_value=100):
            result = agent_hooks.handle(self.event("PostToolUse"), self.root)
        self.assertIn("quick checks failed", result["hookSpecificOutput"]["additionalContext"])
        with patch.object(quality, "quick_check", side_effect=AssertionError("Unexpected repeated check")):
            self.assertEqual(agent_hooks.handle(self.event("PostToolUse"), self.root), {})
            self.write("new.py", "x = 1\n")
            with patch.object(agent_hooks.time, "time", return_value=102):
                self.assertEqual(agent_hooks.handle(self.event("PostToolUse"), self.root), {})
        with patch.object(agent_hooks.time, "time", return_value=106):
            self.assertEqual(agent_hooks.handle(self.event("PostToolUse"), self.root), {})

    def test_resume_retains_original_baseline(self):
        self.start()
        self.write("new.py", "x = 1\n")
        self.start()
        self.assertEqual(agent_hooks.handle(self.event("Stop"), self.root)["decision"], "block")

    def test_sessions_do_not_share_baselines(self):
        self.start()
        self.write("new.py", "x = 1\n")
        with patch.object(agent_hooks, "environment", return_value="Fixture tools"):
            agent_hooks.handle(self.event("SessionStart", session_id="second"), self.root)
        self.assertEqual(agent_hooks.handle(self.event("Stop", session_id="second"), self.root), {})
        self.assertEqual(agent_hooks.handle(self.event("Stop"), self.root)["decision"], "block")


class ConfigurationTest(RepositoryTest):
    @unittest.skipIf(os.name == "nt", "The configured shell command uses POSIX Git-root expansion")
    def test_registered_hooks_execute_valid_handler_from_repository_root(self):
        root = Path(__file__).resolve().parents[2]
        config = json.loads((root / ".codex/hooks.json").read_text())
        (self.root / "scripts").mkdir()
        (self.root / "nested").mkdir()
        for name in ("agent_hooks.py", "quality.py", "ci_changes.py"):
            shutil.copyfile(root / "scripts" / name, self.root / "scripts" / name)
        self.git("add", ".")
        self.git("commit", "-qm", "Hook fixture")
        for name, groups in config["hooks"].items():
            for group in groups:
                for handler in group["hooks"]:
                    event = {"hook_event_name": name, "session_id": "integration", "turn_id": "turn-1"}
                    process = subprocess.run(handler["command"], shell=True, cwd=self.root / "nested",
                                             input=json.dumps(event), capture_output=True, text=True,
                                             timeout=handler["timeout"])
                    self.assertEqual(process.returncode, 0, process.stderr)
                    result = json.loads(process.stdout)
                    self.assertNotIn("systemMessage", result)
                    self.assertNotIn("decision", result)
                    if name == "SessionStart":
                        self.assertIn("Dagsverk workspace", result["hookSpecificOutput"]["additionalContext"])
        config = tomllib.loads((root / ".codex/config.toml").read_text())
        self.assertTrue(all(not entry["enabled"] for entry in config["skills"]["config"]))


if __name__ == "__main__":
    unittest.main()
