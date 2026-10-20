#!/usr/bin/env python3
"""Select server/web and Eclipse validation from the changed product paths."""
import json
import os
from pathlib import Path, PurePosixPath
import subprocess


def documentation(path):
    name = PurePosixPath(path)
    return name.suffix.lower() == ".md" or path.startswith("docs/")


def changed_paths(event, event_name, ref, head):
    if event_name not in ("push", "pull_request") or ref.startswith("refs/tags/"):
        return None
    if event_name == "pull_request":
        base = event["pull_request"]["base"]["sha"]
        comparison = f"{base}...{head}"
    else:
        base = event.get("before", "")
        if not base or set(base) == {"0"}:
            return None
        comparison = f"{base}..{head}"
    # With renames disabled, moving code into docs still includes the old code path.
    result = subprocess.run(["git", "diff", "--no-renames", "--name-only", "-z", comparison],
                            capture_output=True, check=False)
    if result.returncode:
        return None  # Missing/rewritten history must not suppress validation.
    paths = result.stdout.decode().rstrip("\0").split("\0")
    return paths if paths[0] else None


def select_jobs(event, event_name, ref, head):
    paths = changed_paths(event, event_name, ref, head)
    if paths is None:
        return {"code": True, "eclipse": True}
    product = [path for path in paths if not documentation(path)]
    # These server release/selection tools are verified by the always-on packaging tests.
    server_tools = {"scripts/ci_changes.py", "scripts/tests/test_ci_changes.py", ".github/workflows/build.yml",
                    "scripts/synology_release.py", "scripts/tests/test_synology_release.py",
                    "scripts/tests/test_synology_site.py"}
    eclipse = any(not path.startswith("server/") and path not in server_tools for path in product)
    return {"code": bool(product), "eclipse": eclipse}


def requires_build(event, event_name, ref, head):
    return select_jobs(event, event_name, ref, head)["code"]


if __name__ == "__main__":
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    jobs = select_jobs(event, os.environ["GITHUB_EVENT_NAME"],
                           os.environ["GITHUB_REF"], os.environ["GITHUB_SHA"])
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        for name, enabled in jobs.items():
            output.write(f"{name}={'true' if enabled else 'false'}\n")
    print("Full product validation" if jobs["eclipse"] else
          "Server/web validation: reuse tested Eclipse binaries" if jobs["code"] else
          "Documentation only: reuse tested binaries")
