# Synology packaging and publication

This guide is for maintainers. For installation, upgrades, backups and recovery,
use the [operator guide](../operations/SYNOLOGY-PACKAGE.md).

## Building a candidate

Build from a clean commit. The image must be `linux/amd64`, carry the same embedded
clean source identity, and have the immutable name shown below. The packager
checks those properties, compiles the small static installer, bundles the image,
and writes the `.spk`, SHA-256 checksum and provenance JSON:

```sh
COMMIT=$(git rev-parse HEAD)
docker build --platform linux/amd64 --build-arg DAGSVERK_BUILD_COMMIT="$COMMIT" --build-arg DAGSVERK_BUILD_DIRTY=false -t "ghcr.io/turesheim/eclipse-timekeeper-server:sha-$COMMIT" -f server/Dockerfile .
python3 server/scripts/package_synology.py --commit "$COMMIT" --image "ghcr.io/turesheim/eclipse-timekeeper-server:sha-$COMMIT" --revision 1 --output server/target/synology
```

Preview SPKs use `1.99.0-<revision>` so the eventual stable `2.0.0` package sorts
higher in DSM. CI takes the revision from the matching **Build** workflow run,
including when packaging is executed by the independent **Server containers**
workflow. Both identify the actual server build under **About** in the web client's account menu. Preloading
the tested image avoids unpublished PR tags and moving registry aliases.

## Repository discovery

The catalog uses a separate `/synology/preview/r1000/` HTTPS directory and links to
the immutable versioned SPK beside it. Its install/upgrade flags keep the wizard
interactive. Configure SynoCommunity separately for the PostgreSQL dependency.
The generator can be run against a verified candidate directory:

```sh
python3 server/scripts/synology_catalog.py --package server/target/synology/timekeeper-1.99.0-1-r1000.spk --base-url https://turesheim.github.io/eclipse-timekeeper/synology/preview/r1000/
```

This generates files locally; it does not activate a package source. Before
publishing a channel, validate installation and catalog discovery on the actual NAS,
including the request method DSM uses, dependency resolution and version updates.
A static Pages catalog is suitable only if the tested client accepts GET requests;
a POST-requiring client needs a repository service. Stable publication remains
separate until the required NAS acceptance and hosting are resolved.
SynoCommunity inclusion remains subject to upstream acceptance.

The catalog fields follow the [SynoCommunity NAS catalog implementation](https://github.com/SynoCommunity/spkrepo/blob/main/spkrepo/domain/catalog.py)
and its [repository setup guide](https://docs.synocommunity.com/developer-guide/publishing/package-server/).

## Pages package sources

The Pages workflow assembles separate DSM channels alongside the documentation
and Eclipse update sites. It reacts to both the main build matrix and Server
containers, and to release publication workflows. Availability depends on the deployed Pages configuration and repository
variables. Check the channel before adding one of these sources:

- Stable: `https://turesheim.github.io/eclipse-timekeeper/synology/stable/r1000/catalog.json`
- Preview: `https://turesheim.github.io/eclipse-timekeeper/synology/preview/r1000/catalog.json`

Use the complete JSON URL in **Package Center → Settings → Package Sources**.
The landing page at `/synology/` and `channels.json` describe which channels have
a package. An unavailable channel has an empty package list; it does not advertise
an untested package as a stable release. Configure the separate SynoCommunity
source for the PostgreSQL dependency.

Stable metadata is reconstructed from the complete published distribution and
its operator acceptance report. The catalog links to the immutable GitHub release
SPK; icons, checksums and provenance are published in Pages. A partial upload,
missing report or mismatched source/checksum cannot create a stable entry.

Preview publication defaults to **off**. After the first successful manual NAS
installation, an operator may set the repository Actions variable
`SYNOLOGY_PREVIEW_ENABLED=true` and run the Website and p2 update site workflow.
The preview channel is for subsequent NAS acceptance testing; a green CI run
alone does not establish real-NAS compatibility for each new candidate.
Only successful main Server containers artifacts with a complete matching build
matrix are eligible. PR/fork artifacts are excluded. The binary commit must be
an ancestor of the current website source, so a documentation-only change can
reuse a tested candidate. Expired or absent artifacts cannot supply a package;
downloaded artifact and package hashes are checked before copying files.

Turning the variable off and deploying Pages withdraws the preview entry. Site
assembly clears the previous generated channels before reconstructing them, so
a missing artifact or removed release cannot leave an obsolete catalog behind.
This tooling does not itself enable the repository variable or configure DSM.
Actual source discovery and upgrade must still pass on the NAS. Pages is a
candidate host for GET clients; if this DSM requires POST, use a repository
service and keep the static source disabled.

## Promoting a package for public distribution

Keep the downloaded candidate SPK and its adjacent provenance JSON together. To
create an acceptance template locally (CI also supplies one):

```sh
python3 server/scripts/promote_synology.py --package /private/candidate/timekeeper-1.99.0-42-r1000.spk --write-template /private/candidate/dsm-acceptance.json
```

Record the full DSM, Container Manager, PostgreSQL and Hyper Backup package
versions from the tested NAS and the UTC completion time in `testedAt`. The
format-2 template names its `package` (`timekeeper` or `dagsverk`); complete a
separate record for each exact package candidate. A Timekeeper record cannot
certify a Dagsverk package. Older format-1 records lack Hyper Backup acceptance
and must be replaced by a fresh template and the additional real-NAS checks.
Set a check to `true` only
after that scenario passes on the actual NAS. The scenarios include both client
connections, package/reboot lifecycle and failed-upgrade recovery, migration,
backup/restore, uninstall retention and repository discovery/update. Local Docker
tests do not satisfy these checks. Do not put passwords, setup codes, tokens,
hostnames or user data in the record; additional fields are refused.

The dedicated Hyper Backup checks require these observations from the installed
version, beyond the generic `backupRestore` scenario:

| Check | Required observation on the actual NAS |
| --- | --- |
| `hyperBackupDiscoveryAndSummary` | The selected identity appears in Applications, and its summary explicitly includes database, application data, settings and authentication/security material. |
| `hyperBackupCallbackContract` | Record the actual request/response protocol, execution UID/GID, staging ownership/modes, lifecycle ownership and cancellation behavior in private test evidence. |
| `hyperBackupApplicationOnly` | Select only the application, with no data share/PostgreSQL directory, then complete a real repository backup. |
| `hyperBackupRunningAndStoppedState` | Run manual and scheduled backups with the package initially running and stopped; both preserve its prior state. |
| `hyperBackupAllDataRecovery` | Change/delete representative data, restore the earlier repository version, and verify all accounts, credentials, work/tasks, notes/history, attachments/assets, settings and active material. Restored users can authenticate and read/write. |
| `hyperBackupCleanDestination` | Follow the [clean-destination procedure](../operations/SYNOLOGY-PACKAGE.md#recovery-onto-a-clean-destination) from the same repository, with a newly generated destination service password; source and unrelated databases remain unchanged. |
| `hyperBackupFailedExport` | Unavailable database, unsupported versions, full staging volume and failed export report failure and publish no partial archive; a running source recovers availability. |
| `hyperBackupFailedRestore` | Corrupt/incompatible archives are rejected without data replacement; the destination remains stopped and retains any recovery marker/archive. |
| `hyperBackupCancellationAndRetry` | Interrupt/cancel backup and restore through Hyper Backup, check process/package/maintenance state, then retry successfully without partial publication or unsafe startup. |
| `hyperBackupFailedRestart` | A failed restart is visible as failure and has a verified Package Center recovery path. |
| `hyperBackupClientEncryption` | The repository uses client-side encryption and can be recovered with the separately stored key. |

Keep screenshots and redacted task logs in the private acceptance evidence,
identified by the exact preview SHA-256 and content digest. The public record
contains only the strict fields above. Neither the record nor a green automated
build substitutes for recovery demonstrated from that repository.

After all scenarios pass, promote the tested archive:

```sh
python3 server/scripts/promote_synology.py --package /private/candidate/timekeeper-1.99.0-42-r1000.spk --acceptance /private/candidate/dsm-acceptance.json --output /private/stable
python3 server/scripts/synology_catalog.py --package /private/stable/timekeeper-2.0.0-42-r1000.spk --base-url https://turesheim.github.io/eclipse-timekeeper/synology/stable/r1000/
```

Promotion changes only the DSM version in `INFO` and adds the acceptance record;
it copies the server image, payload, installer and lifecycle scripts unchanged.
It verifies the tested archive SHA-256, source commit, actual Docker image ID and
a digest covering all installed bytes, permissions and ownership. Stable
validation and catalog generation also verify this record. Existing stable
files are never overwritten. This preserves the exact tested contents without
rebuilding the installer or server. The record is an operator's test report;
it does not perform or replace those tests.

The output contains the stable SPK, checksum and provenance. It does **not** upload
a release or activate a package source. Keep preview and stable catalogs in
separate directories and publish only after the NAS discovery checks establish
which hosting method works.

## Attaching the distribution to a release

The manually started **Release Synology distribution** workflow publishes the stable SPK on an existing, published
`v2.0.0` GitHub release. It does not build a new server or installer. Before
starting it:

1. Wait for both **Build** and **Server containers** to pass on `main` at the
   release tag's exact commit. Download the candidate from that Server containers
   run and retain its SPK, checksum and provenance. A PR candidate is useful for
   development testing; release publication requires the candidate from `main`.
2. Complete real NAS acceptance for that exact candidate. Attach the completed
   report to the published release as `timekeeper-dsm-acceptance.json`. The
   workflow will verify that it describes the downloaded CI artifact, source,
   image and installed contents. The incomplete CI template cannot be used as a
   passing report.
3. Start the workflow from `main`, giving the release tag and **Server containers
   workflow run ID**, not a job ID or the Build run ID. For example, after setting
   `CANDIDATE_RUN` to the tested run ID:

   ```sh
   gh workflow run release-synology.yml --repo turesheim/eclipse-timekeeper --ref main -f tag=v2.0.0 -f candidate_run="$CANDIDATE_RUN"
   ```

The workflow verifies the complete build matrix, the PostgreSQL 17 and 18
container jobs, artifact origin and SHA-256, tag identity and operator report
before attaching four files: the stable SPK, its checksum, provenance JSON and
`timekeeper-dsm-release.json`. The last file records the source commit, CI runs,
artifact identity and report/package checksums. Existing files are compared
before uploads; a retry can finish a partial upload, while different bytes under
an existing filename cause a failure. No `--clobber` is used.

For a local preparation without uploading, use the same tool without `--publish`:

```sh
python3 scripts/synology_release.py --repo turesheim/eclipse-timekeeper --tag v2.0.0 --candidate-run "$CANDIDATE_RUN" --output /private/prepared-synology-release
```

Keep the candidate artifact available until release preparation has completed.
An expired/missing artifact is refused; rebuilding it requires testing the new
candidate and a new matching report. Attaching a GitHub release distribution does
not activate a Package Center source. Repository hosting and discovery must
still be verified on DSM before publishing the feed.
