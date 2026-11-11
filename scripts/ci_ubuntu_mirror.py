#!/usr/bin/env python3
"""Select an independent Ubuntu archive for GitHub-hosted CI runners."""
from pathlib import Path

MIRROR = "https://mirrors.edge.kernel.org/ubuntu/"
MIRROR_LISTS = ("apt-mirrors.txt", "apt-mirrors-security.txt")


def configure(directory=Path("/etc/apt")):
    paths = [directory / name for name in MIRROR_LISTS if (directory / name).is_file()]
    if not paths:
        raise FileNotFoundError("Expected GitHub-hosted Ubuntu APT mirror lists")
    # Keep Ubuntu's existing suites, components, keyrings and signature checks.
    # Only the runner's mirror lists change; no application image is modified.
    for path in paths:
        path.write_text(MIRROR + "\n")
    return paths


if __name__ == "__main__":
    for path in configure():
        print(f"Ubuntu archive mirror: {path} -> {MIRROR}")
