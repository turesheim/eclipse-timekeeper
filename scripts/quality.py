#!/usr/bin/env python3
"""Run Dagsverk's shared local/CI checks and record results for the current source."""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import tomllib
import xml.etree.ElementTree as ET

from ci_changes import agent_tooling, documentation, validation_jobs

ROOT = Path(__file__).resolve().parents[1]


def git(root, *args):
    return subprocess.run(["git", *args], cwd=root, capture_output=True, check=True).stdout


def changed_paths(root, base="HEAD"):
    tracked = git(root, "diff", "--no-renames", "--name-only", "-z", base)
    staged = git(root, "diff", "--cached", "--no-renames", "--name-only", "-z", base)
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z")
    return sorted(set(os.fsdecode(p) for p in (tracked + staged + untracked).split(b"\0") if p))


def file_digest(root, name):
    path = root / name
    if path.is_symlink():
        return "symlink:" + os.readlink(path)
    if not path.is_file():
        return "missing"
    return hashlib.sha256(path.read_bytes()).hexdigest() + ":" + oct(path.stat().st_mode & 0o777)


def snapshot(root):
    index = {}
    for entry in git(root, "ls-files", "--stage", "-z").split(b"\0"):
        if entry:
            metadata, name = entry.split(b"\t", 1)
            index.setdefault(os.fsdecode(name), []).append(metadata.decode())
    return {"head": git(root, "rev-parse", "HEAD").decode().strip(),
            "files": {name: [file_digest(root, name), index.get(name)] for name in changed_paths(root)}}


def fingerprint(root):
    return hashlib.sha256(json.dumps(snapshot(root), sort_keys=True).encode()).hexdigest()


def session_changes(root, baseline):
    current = snapshot(root)
    if current["head"] != baseline["head"]:
        names = set(current["files"]) | set(baseline["files"])
        names.update(os.fsdecode(p) for p in git(root, "diff", "--no-renames", "--name-only", "-z",
                                              baseline["head"], "HEAD").split(b"\0") if p)
        return sorted(names)
    # A file absent from the baseline dirty set was clean at session start.
    names = set(current["files"]) | set(baseline["files"])
    return sorted(name for name in names if current["files"].get(name) != baseline["files"].get(name))


def commands(root):
    maven = "mvnw.cmd" if os.name == "nt" else "./mvnw"
    npm = "npm.cmd" if os.name == "nt" else "npm"
    return {
        "tooling": [sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-v"],
        "contract": [sys.executable, "-m", "unittest", "discover", "-s", "common/api/tests", "-v"],
        "web": [npm, "test", "--prefix", "server/web"],
        "server": [maven, "-B", "-ntp", "-f", "server/pom.xml", "verify"],
        "eclipse": [maven, "-B", "-ntp", "verify"],
    }


def select_checks(paths, mode="affected"):
    if mode == "quick":
        return ["quick"]
    if mode == "full":
        return ["quick", "tooling", "contract", "web", "server", "eclipse"]
    checks = {"quick"}
    if any(agent_tooling(p) or p.startswith(("scripts/", ".github/")) for p in paths):
        checks.add("tooling")
    product = [p for p in paths if not documentation(p) and not agent_tooling(p)]
    jobs = validation_jobs(paths)
    if jobs["code"]:
        if any(p.startswith("server/web/") for p in product):
            checks.add("web")
        if any(p.startswith("common/") or not p.startswith(("server/", "scripts/", ".github/")) for p in product):
            checks.update(("contract", "web", "server"))
        if any(p.startswith("server/") and not p.startswith("server/web/") for p in product):
            checks.update(("server", "contract"))
        if jobs["eclipse"]:
            checks.add("eclipse")
    return [c for c in ("quick", "tooling", "contract", "web", "server", "eclipse") if c in checks]


def quick_check(root, paths, base="HEAD"):
    errors = []
    diff = subprocess.run(["git", "diff", "--check", base, "--", *paths], cwd=root,
                          capture_output=True, text=True) if paths else None
    if diff and diff.returncode:
        errors.append(diff.stdout + diff.stderr)
    for name in paths:
        path = root / name
        if not path.is_file() or path.is_symlink():
            continue
        try:
            if path.suffix == ".py":
                ast.parse(path.read_bytes(), filename=name)
            elif path.suffix == ".json":
                json.loads(path.read_text())
            elif path.suffix == ".toml":
                tomllib.loads(path.read_text())
            elif path.suffix in {".xml", ".target", ".launch", ".project", ".classpath"}:
                ET.parse(path)
            elif path.suffix in {".js", ".mjs"}:
                result = subprocess.run(["node", "--check", str(path)], cwd=root,
                                        capture_output=True, text=True, timeout=10)
                if result.returncode:
                    errors.append(name + ": " + result.stderr)
            elif path.name == "SKILL.md":
                text = path.read_text()
                front = text.split("---", 2)
                if len(front) != 3 or front[0].strip():
                    raise ValueError("Missing YAML frontmatter")
                fields = dict(re.findall(r"^(name|description):\s*(.+)$", front[1], re.M))
                if fields.get("name") != path.parent.name or not fields.get("description"):
                    raise ValueError("Skill needs its folder name and a triggering description")
        except (ValueError, SyntaxError, ET.ParseError, OSError, subprocess.TimeoutExpired) as error:
            errors.append(f"{name}: {error}")
    return errors


def receipt_path(root, mode):
    return root / "target" / "agent-quality" / (mode + ".json")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as stream:
        json.dump(value, stream, indent=2)
        temporary = Path(stream.name)
    temporary.replace(path)


def verified(root, required):
    identity = fingerprint(root)
    for mode in ("affected", "full", "quick"):
        try:
            receipt = json.loads(receipt_path(root, mode).read_text())
            if receipt.get("fingerprint") == identity and receipt.get("passed") and set(required) <= set(receipt["checks"]):
                return True
        except (OSError, ValueError, KeyError):
            pass
    return False


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--quick", action="store_true", help="Only syntax and diff checks")
    modes.add_argument("--affected", action="store_true", help="Affected command groups (default)")
    modes.add_argument("--full", action="store_true", help="All repository command groups; CI also runs acceptance")
    parser.add_argument("--base", default="HEAD", help="Compare against this Git revision, including local edits")
    parser.add_argument("--plan", action="store_true", help="Print selected checks without executing")
    parser.add_argument("--paths", nargs="+", help="Explicit repository-relative task paths; omit unrelated pre-existing work")
    args = parser.parse_args(argv)
    mode = "quick" if args.quick else "full" if args.full else "affected"
    try:
        paths = changed_paths(ROOT, args.base)
        if args.paths:
            if any(Path(p).is_absolute() or ".." in Path(p).parts for p in args.paths):
                raise ValueError("--paths must stay inside the repository")
            paths = sorted(set(args.paths))
        checks = select_checks(paths, mode)
        available = commands(ROOT)
        print("Dagsverk quality: " + ", ".join(checks), flush=True)
        if args.plan:
            for check in checks:
                print(check + ": " + ("syntax and diff checks" if check == "quick" else " ".join(available[check])))
            return 0
        before = fingerprint(ROOT)
        results = []
        for check in checks:
            print("Checking " + check, flush=True)
            if check == "quick":
                errors = quick_check(ROOT, paths, args.base)
                for error in errors:
                    print(error, file=sys.stderr)
                code = int(bool(errors))
            else:
                try:
                    code = subprocess.run(available[check], cwd=ROOT, check=False, timeout=1800).returncode
                except (OSError, subprocess.TimeoutExpired) as error:
                    print(str(error), file=sys.stderr)
                    code = 1
            results.append({"check": check, "exit_code": code})
            if code:
                break
        unchanged = before == fingerprint(ROOT)
        passed = unchanged and len(results) == len(checks) and all(r["exit_code"] == 0 for r in results)
        write_json(receipt_path(ROOT, mode), {"fingerprint": before, "base": args.base,
                   "mode": mode, "checks": [r["check"] for r in results], "passed": passed,
                   "results": results, "created_at": time.time()})
        if not unchanged:
            print("Source changed during validation; rerun checks for the final source.", file=sys.stderr)
        return 0 if passed else 1
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print("Quality checks unavailable: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
