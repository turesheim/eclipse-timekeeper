#!/usr/bin/env python3
"""Package tagged p2 builds and reconstruct Pages from published release assets."""

import argparse
import hashlib
import html
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

ARCHIVE = "timekeeper-p2.zip"
CHECKSUM = ARCHIVE + ".sha256"
SERVER_ARCHIVE = "timekeeper-server.zip"
SERVER_CHECKSUM = SERVER_ARCHIVE + ".sha256"
PROVENANCE = "timekeeper-release.json"
FEATURE = "net.resheim.eclipse.timekeeper.feature.group"
TAG = re.compile(r"v?(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z0-9][A-Za-z0-9.-]*))?\Z")


def release_version(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError(f"Unsupported release tag: {tag!r}; use vMAJOR.MINOR.PATCH[-suffix]")
    return tuple(int(part) for part in match.groups()[:3])


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def asset_state(release):
    names = {asset["name"] for asset in release.get("assets", [])}
    found = names & {ARCHIVE, CHECKSUM}
    if found and found != {ARCHIVE, CHECKSUM}:
        raise ValueError(f"Incomplete p2 asset pair on {release['tag_name']}; restore or remove the partial upload before retrying")
    return bool(found)


def read_repository_xml(repository, name):
    plain = repository / f"{name}.xml"
    if plain.is_file():
        return ET.parse(plain).getroot()
    with zipfile.ZipFile(repository / f"{name}.jar") as jar:
        return ET.fromstring(jar.read(f"{name}.xml"))


def validate_repository(repository, tag):
    version = ".".join(map(str, release_version(tag)))
    content = read_repository_xml(repository, "content")
    units = content.findall("./units/unit")
    base = [unit for unit in units if unit.get("id") == FEATURE]
    if len(base) != 1 or not (base[0].get("version") == version or base[0].get("version", "").startswith(version + ".")):
        raise ValueError(f"Repository must contain exactly one {FEATURE} matching {version}")
    if any("timekeeper" in unit.get("id", "") and "test" in unit.get("id", "") for unit in units):
        raise ValueError("Test bundles must not be published")
    artifacts = read_repository_xml(repository, "artifacts").findall("./artifacts/artifact")
    if not artifacts:
        raise ValueError("Repository has no artifacts")
    for artifact in artifacts:
        kind = artifact.get("classifier")
        directory = {"osgi.bundle": "plugins", "org.eclipse.update.feature": "features"}.get(kind)
        if not directory:
            raise ValueError(f"Unsupported artifact classifier: {kind}")
        name = f"{artifact.get('id')}_{artifact.get('version')}.jar"
        if not (repository / directory / name).is_file():
            raise ValueError(f"Missing repository artifact: {directory}/{name}")
    return base[0].get("version")


def package(repository, tag, commit, output):
    version = validate_repository(repository, tag)
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("A full source commit SHA is required")
    output.mkdir(parents=True, exist_ok=True)
    archive = output / ARCHIVE
    # Metadata and plugins stay at the ZIP root for Eclipse Local/Archive install.
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zipped:
        for path in sorted(repository.rglob("*")):
            if path.is_symlink():
                raise ValueError(f"Repository contains a symlink: {path}")
            if path.is_file():
                zipped.write(path, path.relative_to(repository).as_posix())
        zipped.writestr(PROVENANCE, json.dumps({"tag": tag, "commit": commit, "version": version}, indent=2) + "\n")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (output / CHECKSUM).write_text(f"{digest}  {ARCHIVE}\n", encoding="utf-8")


def extract_verified(assets, destination, tag):
    archive = assets / ARCHIVE
    checksum = (assets / CHECKSUM).read_text(encoding="utf-8").split()
    if len(checksum) != 2 or checksum[1] != ARCHIVE or checksum[0] != hashlib.sha256(archive.read_bytes()).hexdigest():
        raise ValueError(f"Release checksum does not match: {tag}")
    with zipfile.ZipFile(archive) as zipped:
        provenance = json.loads(zipped.read(PROVENANCE))
        if provenance.get("tag") != tag or not re.fullmatch(r"[0-9a-f]{40}", provenance.get("commit", "")):
            raise ValueError(f"Release provenance does not match: {tag}")
        for member in zipped.infolist():
            path = PurePosixPath(member.filename)
            mode = member.external_attr >> 16
            if path.is_absolute() or ".." in path.parts or "\\" in member.filename or (mode & 0o170000) == 0o120000:
                raise ValueError(f"Unsafe ZIP member: {member.filename}")
        zipped.extractall(destination)
    if validate_repository(destination, tag) != provenance.get("version"):
        raise ValueError(f"Release version does not match provenance: {tag}")


def composite(directory, name, children):
    directory.mkdir(parents=True, exist_ok=True)
    for filename, kind, instruction in (
        ("compositeContent.xml", "metadata.repository.CompositeMetadataRepository", "compositeMetadataRepository"),
        ("compositeArtifacts.xml", "artifact.repository.CompositeArtifactRepository", "compositeArtifactRepository"),
    ):
        root = ET.Element("repository", name=name, type="org.eclipse.equinox.internal.p2." + kind, version="1.0.0")
        properties = ET.SubElement(root, "properties", size="1")
        ET.SubElement(properties, "property", name="p2.timestamp", value=str(int(time.time() * 1000)))
        locations = ET.SubElement(root, "children", size=str(len(children)))
        for child in children:
            ET.SubElement(locations, "child", location=child)
        ET.indent(root)
        text = f'<?xml version="1.0" encoding="UTF-8"?>\n<?{instruction} version="1.0.0"?>\n' + ET.tostring(root, encoding="unicode") + "\n"
        (directory / filename).write_text(text, encoding="utf-8")
    (directory / "p2.index").write_text("version = 1\nmetadata.repository.factory.order = compositeContent.xml,\\!\nartifact.repository.factory.order = compositeArtifacts.xml,\\!\n", encoding="utf-8")


def assemble_site(releases, output, download):
    stable, preview, links = [], [], []
    for release in releases:
        if release["draft"] or not asset_state(release):
            continue
        tag = release["tag_name"]
        version = release_version(tag)
        destination = output / "updates" / "releases" / tag
        with tempfile.TemporaryDirectory() as temporary:
            assets = Path(temporary)
            download(release, assets)
            extract_verified(assets, destination, tag)
        # A prerelease can never enter the stable channel, even if mislabelled.
        prerelease = release["prerelease"] or TAG.fullmatch(tag).group(4) is not None
        channel = preview if prerelease else stable
        channel.append((version, release.get("published_at", ""), tag))
        label = " (prerelease)" if prerelease else ""
        links.append(f'<li><a href="releases/{html.escape(tag, quote=True)}/">{html.escape(tag)}</a>{label}</li>')
    # Include all stable versions so existing Eclipse profiles can still resolve
    # older artifacts. Eclipse/p2 selects the highest compatible IU version.
    stable.sort(reverse=True)
    preview.sort(reverse=True)
    updates = output / "updates"
    composite(updates, "Timekeeper stable releases", [f"releases/{tag}" for _, _, tag in stable])
    composite(updates / "preview", "Timekeeper prereleases", [f"../releases/{tag}" for _, _, tag in preview])
    listing = "\n".join(links) or "<li>No release repositories have been published yet.</li>"
    (updates / "index.html").write_text(f"""<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Timekeeper update site</title>
<body><h1>Timekeeper for Eclipse — update site</h1>
<p>In Eclipse, open <strong>Help &gt; Install New Software…</strong>, choose
<strong>Add…</strong> and use this page's URL as the repository location.</p>
<p>Select <strong>Timekeeper for Eclipse</strong>. Mylyn users can also select
<strong>Timekeeper Mylyn integration</strong>. Version 2.0 requires Eclipse 2026-09 and Java 21.</p>
<p>This address contains stable releases. <a href="preview/">Prereleases</a> are available separately.</p>
<h2>Published repositories</h2><ul>{listing}</ul>
<p><a href="../">Project documentation</a></p></body></html>
""", encoding="utf-8")
    (updates / "preview" / "index.html").write_text('<!doctype html><html lang="en"><meta charset="utf-8"><title>Timekeeper prereleases</title><h1>Timekeeper prereleases</h1><p>Use this page’s URL in Eclipse to install prereleases. <a href="../">Stable releases</a>.</p></html>\n', encoding="utf-8")


def complete_asset_state(release):
    expected = {ARCHIVE, CHECKSUM, SERVER_ARCHIVE, SERVER_CHECKSUM}
    found = {asset["name"] for asset in release.get("assets", [])} & expected
    if found and found != expected:
        raise ValueError(f"Incomplete release assets on {release['tag_name']}; restore or remove the partial upload before retrying")
    return bool(found)


def verify_complete_assets(assets, tag):
    # Retain immutable uploads only after checking both distributions, including
    # their shared source identity. No server binary is executed during reuse.
    with tempfile.TemporaryDirectory() as temporary:
        extracted = Path(temporary)
        extract_verified(assets, extracted, tag)
        p2_manifest = json.loads((extracted / PROVENANCE).read_text())
    archive = assets / SERVER_ARCHIVE
    checksum = (assets / SERVER_CHECKSUM).read_text().split()
    if len(checksum) != 2 or checksum[1] != SERVER_ARCHIVE or checksum[0] != hashlib.sha256(archive.read_bytes()).hexdigest():
        raise ValueError("Server release checksum does not match")
    with zipfile.ZipFile(archive) as zipped:
        if zipped.testzip() is not None:
            raise ValueError("Invalid server release archive")
        manifest = json.loads(zipped.read("timekeeper-server/" + PROVENANCE))
        if manifest.get("kind") != "release" or manifest.get("tag") != tag or manifest.get("commit") != p2_manifest["commit"]:
            raise ValueError("Server and p2 release provenance do not match")


def prepare(repo, tag, require_server=False):
    release_version(tag)
    release = json.loads(gh("release", "view", tag, "--repo", repo, "--json", "isDraft,tagName,assets"))
    if release["isDraft"] or release["tagName"] != tag:
        raise ValueError("Only an already published release may be built")
    state = {"tag_name": tag, "assets": release["assets"]}
    reuse = complete_asset_state(state) if require_server else asset_state(state)
    if reuse and require_server:
        with tempfile.TemporaryDirectory() as temporary:
            assets = Path(temporary)
            gh("release", "download", tag, "--repo", repo, "--pattern", ARCHIVE,
               "--pattern", CHECKSUM, "--pattern", SERVER_ARCHIVE,
               "--pattern", SERVER_CHECKSUM, "--dir", str(assets))
            verify_complete_assets(assets, tag)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"reuse={str(reuse).lower()}\n")
    if reuse:
        print("Existing release assets will be retained; Pages will verify them before publishing.")


def site(repo, output):
    pages = json.loads(gh("api", f"repos/{repo}/releases", "--paginate", "--slurp"))
    releases = [release for page in pages for release in page]

    def download(release, assets):
        gh("release", "download", release["tag_name"], "--repo", repo, "--pattern", ARCHIVE, "--pattern", CHECKSUM, "--dir", str(assets))

    assemble_site(releases, output, download)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    preflight = commands.add_parser("prepare")
    preflight.add_argument("--repo", required=True)
    preflight.add_argument("--tag", required=True)
    preflight.add_argument("--require-server", action="store_true", help="Require and verify both p2 and server release assets")
    pack = commands.add_parser("package")
    pack.add_argument("--repository", required=True, type=Path)
    pack.add_argument("--tag", required=True)
    pack.add_argument("--commit", required=True)
    pack.add_argument("--output", required=True, type=Path)
    publish = commands.add_parser("site")
    publish.add_argument("--repo", required=True)
    publish.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.repo, args.tag, args.require_server)
    elif args.command == "package":
        package(args.repository, args.tag, args.commit, args.output)
    else:
        site(args.repo, args.output)


if __name__ == "__main__":
    main()
