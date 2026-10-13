# Release publication

The **Release p2 and server distributions** workflow builds the published release tag with
Java 21 and the Maven wrapper. Before uploading assets, it runs the API contract,
server unit and packaged HTTP/restart acceptance, browser tests, Linux/Xvfb
Eclipse/Jira tests, and installed remote Eclipse acceptance with the browser peer
and real GTK file/folder dialogs. The server ZIP used by both clients is built
from that same tag and includes the web client. The job validates the p2 metadata,
artifact inventory, both checksums and shared source identity, then attaches:

- `timekeeper-p2.zip`: an Eclipse p2 repository, with metadata at the archive root;
- `timekeeper-p2.zip.sha256`: its SHA-256 checksum;
- `timekeeper-server.zip`: the complete Java 21 server distribution, including the web client;
- `timekeeper-server.zip.sha256`: its SHA-256 checksum.

Credential-free test reports and acceptance receipts are retained as the
`release-test-results` workflow artifact, including all installed restart/account/
backend phases. See [server installation](../operations/SERVER.md) and
[web client](WEB-CLIENT.md) for configuration.

The archive contains `timekeeper-release.json` with the release tag, source
commit and qualified p2 feature version. The job builds the tag, not the current
main branch. Use a tag matching the plugin version, such as `v2.0.0` or
`v2.0.0-rc.1`. Publishing a draft triggers the job; editing or saving a draft does
not. Both stable releases and published prereleases are supported.

## GitHub Pages

In repository **Settings > Pages > Build and deployment**, select **GitHub
Actions** as the source. The `github-pages` environment must allow deployments
from the default branch. The existing project website continues to be built
with GitHub's Jekyll action; generated p2 repositories are added after Jekyll,
so binary metadata and JARs are served without transformation.

The **Website and p2 update site** workflow runs after a successful release
build, on main pushes, on release edits/deletions, and on manual dispatch. It
always checks out the default branch and reconstructs the complete site from
published release assets. Drafts and historical releases without the new asset
pair are excluded. Checksums, embedded release identity, feature version and
the artifact inventory must validate before a Pages deployment can proceed.
Failed builds or invalid assets leave the previously deployed site intact.

Install locations:

| Location | Contents |
| --- | --- |
| `https://turesheim.github.io/eclipse-timekeeper/updates/` | All stable releases; p2 selects the newest compatible version |
| `https://turesheim.github.io/eclipse-timekeeper/updates/preview/` | Published prereleases only |
| `https://turesheim.github.io/eclipse-timekeeper/updates/releases/<tag>/` | One release's original p2 repository |

The channels are p2 composite repositories pointing to retained release
directories. New prereleases cannot update the stable channel. Promoting a
prerelease without a suffix to a stable release updates the channels when the
release is edited; tags containing a prerelease suffix always stay in preview.
Keep the assets attached to older releases to retain their installation URLs
and artifact resolution. Deleting a release removes it on the next deployment.
The GitHub Pages site size limit applies to the sum of retained repositories.

## Publication and retry

1. Merge the release changes and publication workflows into main. Ensure the
   tag points to the intended tested source commit and its plugin version
   matches the release version. The draft's old target commit must be reviewed
   before publishing; updating its notes alone does not change that commit.
2. Publish the GitHub release. Wait for **Release p2 and server distributions**, then
   **Website and p2 update site** to succeed.
3. In a clean supported Eclipse installation, install from the stable or preview
   URL. Keep the Eclipse 2026-09 software site enabled for dependencies. Select
   the base feature and, if needed, the optional Mylyn integration. Check the
   installed versions, task/activity workflow and Error Log.
4. Record the release build and installed-package acceptance before announcing
   the update site. Automated publication does not replace release acceptance.

If the build fails, use **Actions > Release p2 and server distributions > Run workflow** on
main with the already published release tag. An existing complete set of all
four assets is downloaded and verified for checksums, release tag and matching
source commits before reuse; assets are never overwritten. The Pages workflow
also verifies the p2 repository before deployment. If an upload was interrupted,
restore the missing files from the original successful build or remove the
partial set before retrying. A p2 pair alone cannot count as a completed 2.0
release. Existing historical p2-only releases remain usable by the Pages builder. Rebuilding
uses new qualified p2 versions, so do not overwrite an already distributed
repository at the same release URL. Publish a new release instead.

To rebuild only the website/channels, manually run **Website and p2 update
site** on main. No personal access token or separate hosting secret is required:
the release job uses `contents: write`; Pages deployment uses `pages: write`
and `id-token: write` through the repository's `GITHUB_TOKEN`.

## Local verification

```sh
python3 -m unittest discover -s scripts/tests -v
actionlint .github/workflows/build.yml .github/workflows/release-p2.yml .github/workflows/pages.yml
python3 scripts/p2_release.py package \
  --repository eclipse/net.resheim.eclipse.timekeeper-site/target/repository \
  --tag v2.0.0 --commit "$(git rev-parse HEAD)" --output /tmp/timekeeper-release
```

The packaging command validates an existing build; run the normal clean
`./mvnw ... verify` first to create it. With authenticated `gh` available,
`python3 scripts/p2_release.py site --repo turesheim/eclipse-timekeeper --output /tmp/timekeeper-site`
reconstructs the update repositories locally without uploading or publishing.

The workflow uses GitHub's [custom Pages deployment](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)
and Eclipse's [p2 composite repository format](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/p2_composite_repositories.htm).
