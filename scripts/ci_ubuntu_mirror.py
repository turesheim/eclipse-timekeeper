#!/usr/bin/env python3
"""Configure official Ubuntu archives with APT-native CI failover."""
from pathlib import Path

MIRRORS = ("https://archive.ubuntu.com/ubuntu/", "https://security.ubuntu.com/ubuntu/")
MIRROR_LISTS = ("apt-mirrors.txt", "apt-mirrors-security.txt")


def configure(directory=Path("/etc/apt")):
    paths = [directory / name for name in MIRROR_LISTS if (directory / name).is_file()]
    if not paths:
        raise FileNotFoundError("Expected GitHub-hosted Ubuntu APT mirror lists")
    # Keep Ubuntu's existing suites, components, keyrings and signature checks.
    # Only the runner's mirror lists change; no application image is modified.
    for path in paths:
        mirrors = reversed(MIRRORS) if path.name == "apt-mirrors-security.txt" else MIRRORS
        # Lower priorities are tried first; APT falls back on download failures.
        # https://manpages.ubuntu.com/manpages/jammy/man1/apt-transport-mirror.1.html
        path.write_text("".join(f"{url}\tpriority:{priority}\n" for priority, url in enumerate(mirrors, 1)))
    return paths


if __name__ == "__main__":
    for path in configure():
        print(f"Ubuntu archive mirrors: {path}\n{path.read_text().rstrip()}")
