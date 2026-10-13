#!/usr/bin/env python3
"""Skip product builds only when every changed path is documentation."""
import json
import os
from pathlib import Path, PurePosixPath
import subprocess


def documentation(path):
    name = PurePosixPath(path)
    return name.suffix.lower() == ".md" or path.startswith("docs/")


def requires_build(event, event_name, ref, head):
    if event_name not in ("push", "pull_request") or ref.startswith("refs/tags/"):
        return True
    if event_name == "pull_request":
        base = event["pull_request"]["base"]["sha"]
        comparison = f"{base}...{head}"
    else:
        base = event.get("before", "")
        if not base or set(base) == {"0"}:
            return True
        comparison = f"{base}..{head}"
    # With renames disabled, moving code into docs still includes the old code path.
    result = subprocess.run(["git", "diff", "--no-renames", "--name-only", "-z", comparison],
                            capture_output=True, check=False)
    if result.returncode:
        return True  # Missing/rewritten history must not suppress validation.
    paths = result.stdout.decode().rstrip("\0").split("\0")
    return not paths[0] or any(not documentation(path) for path in paths)


if __name__ == "__main__":
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    build = requires_build(event, os.environ["GITHUB_EVENT_NAME"],
                           os.environ["GITHUB_REF"], os.environ["GITHUB_SHA"])
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"code={'true' if build else 'false'}\n")
    print("Full product validation" if build else "Documentation only: reuse tested binaries")
