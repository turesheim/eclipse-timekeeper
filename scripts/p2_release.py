#!/usr/bin/env python3
"""Package tagged p2 builds and reconstruct Pages from published release assets."""

import argparse
import hashlib
import html
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
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
        extract_repository_zip(zipped, destination)
    if validate_repository(destination, tag) != provenance.get("version"):
        raise ValueError(f"Release version does not match provenance: {tag}")


def extract_repository_zip(zipped, destination):
    for member in zipped.infolist():
        path = PurePosixPath(member.filename)
        mode = member.external_attr >> 16
        if path.is_absolute() or ".." in path.parts or "\\" in member.filename or (mode & 0o170000) == 0o120000:
            raise ValueError(f"Unsafe ZIP member: {member.filename}")
    if zipped.testzip() is not None:
        raise ValueError("Invalid p2 repository archive")
    zipped.extractall(destination)


def download_testing_artifact(repo, artifact_id, destination):
    with destination.open("wb") as output:
        subprocess.run(["gh", "api", f"repos/{repo}/actions/artifacts/{artifact_id}/zip"],
                       stdout=output, check=True)


def testing_repository_version(repository):
    units=read_repository_xml(repository,"content").findall("./units/unit")
    base=[unit for unit in units if unit.get("id")==FEATURE]
    if len(base)!=1:
        raise ValueError("Testing repository has no unique Timekeeper feature")
    return validate_repository(repository,"v"+".".join(base[0].get("version","").split(".")[:3]))


def assemble_testing_repository(repo,repository,output,provenance,build_path):
    version=testing_repository_version(repository)
    commit=provenance["commit"]
    if not re.fullmatch(r"[0-9a-f]{40}",commit) or provenance["version"]!=version:
        raise ValueError("Testing repository source/version identity is invalid")
    if any(path.is_symlink() for path in repository.rglob("*")):
        raise ValueError("Testing repository contains a symlink")
    testing=output/"updates/testing"
    destination=testing/build_path
    shutil.copytree(repository,destination,dirs_exist_ok=True)
    (destination/"timekeeper-build.json").write_text(json.dumps(provenance,indent=2)+"\n")
    composite(testing,"Timekeeper tested main builds",[build_path])
    (testing/"index.html").write_text(f'''<!doctype html><html lang="en">
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Timekeeper testing update site</title><h1>Timekeeper testing update site</h1>
<p>In Eclipse, use this page's URL in <strong>Help &gt; Install New Software… &gt; Add…</strong>.</p>
<p>Select <strong>Timekeeper for Eclipse</strong> and optionally <strong>Timekeeper Mylyn integration</strong>.
Version 2.0 requires Eclipse 2026-09 and Java 21. Keep the Eclipse 2026-09 software site enabled.</p>
<p>This is an unreleased build that passed the platform matrix, server, web and installed-client tests.
It is separate from <a href="../">stable releases</a> and <a href="../preview/">release candidates</a>.</p>
<p>Version: <code>{html.escape(version)}</code>. Source: <code>{commit}</code>.
<a href="https://github.com/{repo}/actions/runs/{provenance['run']}">Verified build</a>.
<a href="{build_path}/timekeeper-build.json">Build identity</a>.</p>
<p>Restart Eclipse after installation, then open <strong>Window &gt; Show View &gt; Other… &gt; Timekeeper &gt; Workweek</strong>.</p>
</html>\n''',encoding="utf-8")


def check_site(repo,repository,output,commit,run):
    # This verifies an artifact downloaded from the same CI run after its jobs pass.
    # Pages separately selects a completed trusted main run before deploying.
    version=testing_repository_version(repository)
    provenance={"kind":"verification","commit":commit,"version":version,"run":run}
    assemble_site([],output,lambda *_:None)
    assemble_testing_repository(repo,repository,output,provenance,f"builds/{commit}")
    return provenance


def extract_run_artifact(repo,run,name,destination):
    artifacts=json.loads(gh("api",f"repos/{repo}/actions/runs/{run['id']}/artifacts?per_page=100"))["artifacts"]
    available=[item for item in artifacts if item["name"]==name and not item["expired"]]
    if len(available)!=1:
        raise ValueError(f"Expected one available {name} artifact")
    artifact=available[0];source=artifact.get("workflow_run",{})
    if (source.get("id")!=run["id"] or source.get("head_sha")!=run["head_sha"]
            or source.get("head_branch")!=run["head_branch"]):
        raise ValueError("Artifact source does not match its workflow run")
    digest=artifact.get("digest","")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}",digest):
        raise ValueError("Artifact has no SHA-256 digest")
    with tempfile.TemporaryDirectory() as temporary:
        archive=Path(temporary)/"artifact.zip"
        download_testing_artifact(repo,artifact["id"],archive)
        if "sha256:"+hashlib.sha256(archive.read_bytes()).hexdigest()!=digest:
            raise ValueError("Artifact checksum does not match")
        with zipfile.ZipFile(archive) as zipped:
            extract_repository_zip(zipped,destination)
    return artifact


def build_site(repo,output,commit,run_id):
    run=json.loads(gh("api",f"repos/{repo}/actions/runs/{run_id}"))
    if run["id"]!=run_id or (run["event"]=="push" and run["head_sha"]!=commit):
        raise ValueError("Current build source does not match its workflow run")
    with tempfile.TemporaryDirectory() as temporary:
        repository=Path(temporary)/"repository"
        artifact=extract_run_artifact(repo,run,"p2-repository",repository)
        version=testing_repository_version(repository)
        provenance={"kind":"testing","commit":commit,"version":version,"run":run_id,
                    "artifactSha256":artifact["digest"].removeprefix("sha256:")}
        site(repo,output)  # Released channels; no lookup of an older testing build.
        assemble_testing_repository(repo,repository,output,provenance,f"builds/{run_id}-{artifact['id']}")
        if not (output/"index.html").is_file():
            raise ValueError("Build the project website before adding update repositories")
        (output/"timekeeper-site.json").write_text(json.dumps(provenance,indent=2)+"\n")
    return provenance


def fetch_site(repo,output,run_id):
    branch=gh("api",f"repos/{repo}","--jq",".default_branch").strip()
    run=json.loads(gh("api",f"repos/{repo}/actions/runs/{run_id}"))
    if (run.get("id")!=run_id or run.get("status")!="completed" or run.get("conclusion")!="success"
            or run.get("event")!="push" or run.get("head_branch")!=branch
            or run.get("path")!=".github/workflows/build.yml"
            or run.get("head_repository",{}).get("full_name")!=repo):
        raise ValueError("Site deployment requires a successful trusted main Build run")
    with tempfile.TemporaryDirectory() as temporary:
        prepared=Path(temporary)/"site"
        extract_run_artifact(repo,run,"website-update-site",prepared)
        identity=json.loads((prepared/"timekeeper-site.json").read_text())
        if identity.get("commit")!=run["head_sha"] or identity.get("run")!=run_id:
            raise ValueError("Prepared site source does not match the successful build")
        children=ET.parse(prepared/"updates/testing/compositeContent.xml").findall("./children/child")
        if len(children)!=1:
            raise ValueError("Prepared site has no unique testing repository")
        repository=(prepared/"updates/testing"/children[0].get("location","")).resolve()
        repository.relative_to(prepared.resolve())
        if testing_repository_version(repository)!=identity.get("version") or not (prepared/"index.html").is_file():
            raise ValueError("Prepared website or testing version is invalid")
        if json.loads((repository/"timekeeper-build.json").read_text())!=identity:
            raise ValueError("Prepared site and p2 source identities differ")
        shutil.copytree(prepared,output,dirs_exist_ok=True)
    return identity


def add_testing_channel(repo, output):
    branch=gh("api",f"repos/{repo}","--jq",".default_branch").strip()
    runs=json.loads(gh("api",f"repos/{repo}/actions/workflows/build.yml/runs",
                      "--method","GET","-f",f"branch={branch}","-f","event=push",
                      "-f","status=success","-f","per_page=10"))["workflow_runs"]
    required={"API-contract","Server","Web","Build (ubuntu-latest)",
              "Build (macos-latest)","Build (windows-latest)"}
    for run in runs:
        if (run.get("status")!="completed" or run.get("conclusion")!="success"
                or run.get("event")!="push" or run.get("head_branch")!=branch
                or run.get("path")!=".github/workflows/build.yml"
                or run.get("head_repository",{}).get("full_name")!=repo):
            continue
        commit=run["head_sha"]
        if not re.fullmatch(r"[0-9a-f]{40}",commit):
            raise ValueError("Invalid testing source commit")
        jobs=json.loads(gh("api",f"repos/{repo}/actions/runs/{run['id']}/jobs?per_page=100"))["jobs"]
        passed={job["name"] for job in jobs if job.get("status")=="completed" and job.get("conclusion")=="success"}
        if (not required.issubset(passed)
                or not passed.intersection({"Remote-Eclipse","Installed client tests"})
                or not passed.intersection({"Publication","Update site checks","Build website and update site"})
                or (passed.intersection({"Update site checks","Build website and update site"})
                    and not passed.intersection({"Publication tooling tests","Packaging and channel tests"}))):
            continue
        artifacts=json.loads(gh("api",f"repos/{repo}/actions/runs/{run['id']}/artifacts?per_page=100"))["artifacts"]
        available=[artifact for artifact in artifacts if artifact["name"]=="p2-repository" and not artifact["expired"]]
        if not available:
            continue
        if len(available)!=1:
            raise ValueError("Ambiguous testing p2 artifact")
        artifact=available[0]
        source=artifact.get("workflow_run",{})
        if source.get("id")!=run["id"] or source.get("head_sha")!=commit or source.get("head_branch")!=branch:
            raise ValueError("Testing artifact source does not match the verified build")
        digest=artifact.get("digest","")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}",digest):
            raise ValueError("Testing artifact has no SHA-256 digest")
        with tempfile.TemporaryDirectory() as temporary:
            archive=Path(temporary)/"p2-artifact.zip"
            download_testing_artifact(repo,artifact["id"],archive)
            if "sha256:"+hashlib.sha256(archive.read_bytes()).hexdigest()!=digest:
                raise ValueError("Testing artifact checksum does not match")
            repository=Path(temporary)/"repository"
            with zipfile.ZipFile(archive) as zipped:
                extract_repository_zip(zipped,repository)
            version=testing_repository_version(repository)
            provenance={"kind":"testing","commit":commit,"version":version,
                        "run":run["id"],"artifactSha256":digest.removeprefix("sha256:")}
            assemble_testing_repository(repo,repository,output,provenance,
                                        f"builds/{run['id']}-{artifact['id']}")
        print(json.dumps(provenance))
        return provenance
    print("No complete verified main p2 artifact is available for the testing channel.")
    return None


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
    composite(updates / "testing", "Timekeeper tested main builds", [])
    (updates / "testing/index.html").write_text('<!doctype html><html lang="en"><meta charset="utf-8"><title>Timekeeper testing builds</title><h1>Timekeeper testing builds</h1><p>No complete verified main p2 artifact is available yet. <a href="../">Stable releases</a>.</p></html>\n')
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
<p>For unreleased 2.0 development builds, use the <a href="testing/">tested main update site</a>.</p>
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


def site(repo, output, include_testing=False):
    pages = json.loads(gh("api", f"repos/{repo}/releases", "--paginate", "--slurp"))
    releases = [release for page in pages for release in page]

    def download(release, assets):
        gh("release", "download", release["tag_name"], "--repo", repo, "--pattern", ARCHIVE, "--pattern", CHECKSUM, "--dir", str(assets))

    assemble_site(releases, output, download)
    if include_testing:
        add_testing_channel(repo, output)


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
    publish.add_argument("--include-testing", action="store_true", help="Add a separate channel from a fully verified main Build artifact")
    check = commands.add_parser("check-site", help="Verify site assembly using this CI run's matrix artifact")
    check.add_argument("--repo", required=True)
    check.add_argument("--repository", required=True, type=Path)
    check.add_argument("--output", required=True, type=Path)
    check.add_argument("--commit", required=True)
    check.add_argument("--run", required=True, type=int)
    build = commands.add_parser("build-site", help="Build the deployable site from this run's matrix artifact")
    build.add_argument("--repo", required=True)
    build.add_argument("--output", required=True, type=Path)
    build.add_argument("--commit", required=True)
    build.add_argument("--run", required=True, type=int)
    fetch = commands.add_parser("fetch-site", help="Fetch the site prepared by a successful main Build")
    fetch.add_argument("--repo", required=True)
    fetch.add_argument("--output", required=True, type=Path)
    fetch.add_argument("--run", required=True, type=int)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.repo, args.tag, args.require_server)
    elif args.command == "package":
        package(args.repository, args.tag, args.commit, args.output)
    elif args.command == "check-site":
        check_site(args.repo, args.repository, args.output, args.commit, args.run)
    elif args.command == "build-site":
        build_site(args.repo,args.output,args.commit,args.run)
    elif args.command == "fetch-site":
        fetch_site(args.repo,args.output,args.run)
    else:
        site(args.repo, args.output, args.include_testing)


if __name__ == "__main__":
    main()
