#!/usr/bin/env python3
"""Small Codex lifecycle hooks; shared checks live in quality.py."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import shlex
import sys
import time

import quality


def context(event, message):
    return {"hookSpecificOutput": {"hookEventName": event, "additionalContext": message}}


def environment():
    versions = [f"Python {sys.version_info.major}.{sys.version_info.minor}"]
    warnings = []
    if sys.version_info[:2] != (3, 13):
        warnings.append("CI uses Python 3.13")
    for command, pattern, expected in (
            (["node", "--version"], r"(?m)^v(\d+\.\d+\.\d+)\s*$", "24."),
            (["java", "-version"], r'(?:openjdk|java) version "([^"\s]+)"', "21.")):
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=3)
            match = re.search(pattern, result.stdout + result.stderr)
            version = match[1] if match else "unavailable"
            # JVM option notices can contain environment values; emit only the version.
            versions.append(command[0] + " " + version)
            if result.returncode or not version.startswith(expected):
                warnings.append(command[0] + " differs from CI")
        except (OSError, subprocess.TimeoutExpired):
            warnings.append(command[0] + " unavailable")
    return "; ".join(versions + warnings)


def handle(event, root=quality.ROOT):
    name = event["hook_event_name"]
    session = event.get("session_id")
    if not session:
        return {"systemMessage": "Dagsverk hooks: no session ID; automatic validation unavailable."}
    key = hashlib.sha256(session.encode()).hexdigest()
    state_path = root / "target" / "agent-hooks" / (key + ".json")
    try:
        state = json.loads(state_path.read_text())
    except (OSError, ValueError):
        state = None
    if name == "SessionStart":
        if state is None:
            state = {"baseline": quality.snapshot(root)}
            quality.write_json(state_path, state)
        branch = quality.git(root, "branch", "--show-current").decode().strip() or "detached HEAD"
        return context(name, f"Dagsverk workspace: {branch}; {len(state['baseline']['files'])} changed files at session start. "
                       "Preserve local changes. Read AGENTS.md and the relevant dagsverk-* skill. " + environment())
    if state is None:
        return {"systemMessage": "Dagsverk hooks: session baseline missing. Run quality.py --affected manually; "
                "start a new session to enable change-aware hooks."}
    paths = quality.session_changes(root, state["baseline"])
    if not paths:
        return {}
    identity = quality.fingerprint(root)
    if name == "PostToolUse":
        # Supported tool names vary. Inspect the actual source instead of guessing
        # from a shell command, and skip identical source or closely spaced edits.
        now = time.time()
        if state.get("quick_fingerprint") == identity or now - state.get("quick_at", 0) < 5:
            return {}
        errors = quality.quick_check(root, paths, state["baseline"]["head"])
        state.update(quick_fingerprint=identity, quick_at=now)
        quality.write_json(state_path, state)
        if errors:
            return context(name, "Dagsverk quick checks failed:\n" + "\n".join(errors)[:3000])
        return {}
    if name == "Stop":
        required = quality.select_checks(paths)
        if quality.verified(root, required):
            return {}
        turn = event.get("turn_id", "unknown")
        if state.get("last_stop_fingerprint") == identity and state.get("last_stop_turn") != turn:
            return {}  # A later advice/status turn must not reopen unchanged work.
        state.update(last_stop_fingerprint=identity, last_stop_turn=turn)
        command = shlex.join(["python3", str(root / "scripts/quality.py"), "--affected", "--base",
                              state["baseline"]["head"], "--paths", *paths])
        reason = ("Dagsverk validation is missing or failed for the current source. Run " + command +
                  ". Address failures within the task, then verify acceptance criteria. "
                  "If a required tool/environment is unavailable, report that limitation accurately. "
                  "Do not deploy, expand scope or repeat an unavailable check.")
        if event.get("stop_hook_active") or state.get("continued_turn") == turn:
            quality.write_json(state_path, state)
            return {"systemMessage": "Dagsverk: validation remains incomplete (" + ", ".join(required) +
                    "). State the actual failed/unavailable checks in the final response. "
                    "Automatic continuation is limited to one attempt per turn."}
        state["continued_turn"] = turn
        quality.write_json(state_path, state)
        return {"decision": "block", "reason": reason}
    return {}


def main():
    try:
        result = handle(json.load(sys.stdin))
    except Exception as error:
        # Hook failures are visible, never represented as successful validation.
        result = {"systemMessage": "Dagsverk hook unavailable: " + str(error)[:500] +
                  ". Run python3 scripts/quality.py --affected manually."}
    print(json.dumps(result))


if __name__ == "__main__":
    main()
