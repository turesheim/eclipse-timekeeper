# Synology Package Center distribution

The initial NAS acceptance target is **DS923+ / r1000, DSM 7.4.1-90080**.
The package uses DSM **7.2.2 or later** resource interfaces,
Container Manager with the Docker Project worker (**build 1432 or later**), and
SynoCommunity PostgreSQL **17.11-6** on its normal port **5433**. Real DSM acceptance
is required before this candidate can be described as supported or published in
a stable feed. The [generic Compose deployment](DEPLOYMENT.md) remains available.

## Installation flow

Install SynoCommunity PostgreSQL and Synology Container Manager first. Native
PostgreSQL must already have the administrator credentials chosen in its own
wizard. Use **Package Center → Manual Install** for the candidate `.spk`.

The Timekeeper wizard asks for those database administrator credentials, unused
dedicated database/role names, a local HTTP port and the public HTTPS origin from
DSM Reverse Proxy or [Tailscale Serve](TAILSCALE.md). Configure HTTPS forwarding
to that loopback port. It also displays a random one-time setup code: copy it
before finishing. No database administrator password is retained by Timekeeper.

Use **Open**, choose **First administrator setup**, and enter that code to create
your username and password. Codes expire 15 minutes after installation. New
identities use a random stable owner ID; claiming existing data requires its
explicit existing owner ID. [Account operations](ACCOUNTS.md) describes privacy,
sessions, named Eclipse tokens and recovery.

The low-privilege installer creates a dedicated non-superuser role and database
from PostgreSQL's `template0`, avoiding unrelated extensions in SynoCommunity's
`template1`. It refuses DSM's built-in PostgreSQL port 5432. Container Manager's
resource worker loads the bundled immutable Java 21 application image and owns
its lifecycle; there is no Timekeeper PostgreSQL container or host Java dependency.

The application uses host networking to reach native PostgreSQL on loopback and
binds its HTTP listener to **127.0.0.1**. HTTPS and the exact public origin are
required for browser sign-in. The selected HTTP port is also used by the container
health check. The application runs as UID 10001 with a read-only filesystem.

The complete Compose project and empty bind directories ship in `package.tgz`:
DSM acquires the project **before** `postinst`. The installer then copies retained
configuration into those existing directories under a mode-0700 package-private
parent. Quarkus reads runtime properties after container start, so project creation
needs neither database credentials nor install-time environment substitution.
`prestart` synchronizes retained files and checks maintenance state before startup;
`start` waits for application readiness after the worker starts the container.
Stopped status uses DSM exit code 3. See Synology's [resource timing](https://help.synology.com/developer-guide/resource_acquisition/timing.html)
and [script lifecycle](https://help.synology.com/developer-guide/synology_package/scripts.html).

## Data and lifecycle

The private `/var/packages/timekeeper/var/server` directory contains settings,
the application database credential, hashed setup/token configuration and
backups. DSM links `var` to permanent `@appdata/timekeeper` on the selected
package volume. This directory exists during installation and survives package
uninstallation. It uses mode 0700 under the package account; it is not an SMB
shared folder. PostgreSQL data remains with the separate PostgreSQL package.
Stopping or uninstalling Timekeeper never drops databases, roles or other packages.

This uses DSM's [permanent appdata directory](https://help.synology.com/developer-guide/integrate_dsm/fhs.html),
not a Data Share resource: the latter is acquired at enable/startup, after
`postinst` needs to provision and save configuration. Creating directories under
its future share link during installation would write into the wrong location.

Reinstallation and upgrades reuse retained credentials and owner mappings.
The retained-install wizard does not request the database administrator password.
Reinstallation renews an unfinished first-administrator setup code and invalidates
earlier pending codes; it never reopens completed account setup.
Provisioning records an installation ID on its dedicated role/database so an
interrupted operation can resume without taking over an unrelated object.
Same-schema upgrades replace the pinned application image and record deployment
time. Schema changes require explicit maintenance and are refused automatically.

Back up before upgrades. A failed application upgrade leaves native data and
retained configuration intact; retain the previous `.spk` for recovery. Real DSM
checks must verify package repair, reinstallation permissions and failure recovery.
Do not remove the native PostgreSQL package or the retained appdata as part
of an application rollback.

## Native backup and restore

Stop Timekeeper in Package Center before backing up or restoring. The maintenance
helper refuses an active HTTP listener, other database clients, an incompatible
schema or a PostgreSQL major version other than 17. PostgreSQL itself stays running.

A DSM administrator can use **Control Panel → Task Scheduler → Create → Scheduled
Task → User-defined script**, run as root, to invoke the package account without
SSH. For an immediate backup, save and run this task:

```sh
sudo -u timekeeper env SYNOPKG_PKGNAME=timekeeper /var/packages/timekeeper/scripts/timekeeper-dsm backup
```

The task output gives the backup path in the retained private `server/backups`
directory. The mode-0600 archive contains a consistent native `pg_dump`, setup/token
configuration, retained settings and the database service credential. Keep the
archive private and copy it to your normal backup destination. The manifest records
SHA-256 for every member. No PostgreSQL administrator credential is included.

To restore, keep Timekeeper stopped and run a task with the absolute backup path:

```sh
sudo -u timekeeper env SYNOPKG_PKGNAME=timekeeper /var/packages/timekeeper/scripts/timekeeper-dsm restore /var/packages/timekeeper/var/server/backups/timekeeper-EXAMPLE.tar
```

Restore validates the complete archive before touching data, then uses PostgreSQL
17 `pg_restore` in one transaction. It restores business/account data and setup/token
configuration into the current dedicated database, retaining the current service
role/password, HTTPS address and port. Start Timekeeper after success. If a restore
fails, leave the package stopped and resolve the error before retrying. Restoring
an older backup also restores its older password, token and session state; follow
[account recovery guidance](ACCOUNTS.md) for subsequent credential changes.

For disaster recovery, install/provision a fresh Timekeeper database and role,
allow the application to create its canonical schema, then stop it and restore the
native archive using the same command. The new service credential remains in use;
business owners and their credentials come from the backup. This recovery is tested
with a different role/password/database, leaving the original database untouched.

If retained settings point at a lost database, keep the package stopped and move
its private `server` directory aside using an administrator Task Scheduler task.
Keep that directory and the original archive. Reinstall through Package Center,
choose unused database/role names, and restore a copy of the archive into the
freshly provisioned installation. Give the new package account ownership of the
copied archive. Do not overwrite the retained original or remove unrelated roles,
databases or the shared PostgreSQL package.

## Migrating from containerized PostgreSQL 18

The supported logical transfer is **PostgreSQL 18.6 → 17.11, canonical Timekeeper
schema 2**. Upgrade the source server using the explicit schema-upgrade procedure in
[account operations](ACCOUNTS.md) and complete its first-administrator setup before
exporting. Existing owners must be claimed by their explicit IDs. The exporter
refuses an older/custom schema, unfinished account setup or connected API clients.

1. Use the updated deployment bundle with the source's existing `.env` and state.
   Make a normal backup, pause clients, and stop **all** source API replicas:

   ```sh
   sh backup.sh
   docker compose stop api-1 api-2
   python3 export-synology.py --output /absolute/private/path/timekeeper-pg18-to17.tar
   ```

   The exporter checks the schema and fingerprints every table before and after
   generating a data-only PostgreSQL 18 dump. It leaves the source stopped. The
   mode-0600 archive includes the hashed legacy token registry and preserves all
   owners, UUIDs, relationships, exact timestamp strings, revisions, cursor secrets,
   command receipts and account/password/token/session state.

2. Install the DSM candidate into an **unused** dedicated database. Leave the
   wizard's existing-owner field blank and choose an unused HTTP port (for example
   8081 while the source gateway still uses 8080). Allow the server to initialize its empty
   schema, then stop Timekeeper in Package Center. **Do not create a target account**:
   source accounts will be transferred. The importer refuses a used target.

3. Transfer the archive through File Station to a private location on the NAS.
   In an administrator Task Scheduler script, copy it to the retained backup
   directory, give the package account ownership, then invoke the importer:

   ```sh
   cp /volume1/PRIVATE-TRANSFER/timekeeper-pg18-to17.tar /var/packages/timekeeper/var/server/backups/migration.tar
   chown timekeeper:timekeeper /var/packages/timekeeper/var/server/backups/migration.tar
   chmod 600 /var/packages/timekeeper/var/server/backups/migration.tar
   sudo -u timekeeper env SYNOPKG_PKGNAME=timekeeper /var/packages/timekeeper/scripts/timekeeper-dsm import-postgresql18 /var/packages/timekeeper/var/server/backups/migration.tar
   ```

   The current dedicated database service role/password and DSM address/port are
   retained. The importer applies only source data to the canonical PostgreSQL 17
   schema, then compares fingerprints of **all 14 tables before committing** the
   single transaction. No UUID remapping or timestamp conversion is performed.

4. Start Timekeeper and sign in with an existing source account. Point clients at
   the target address and check their own data. Existing API tokens remain bound
   to the same owners. Command IDs and stored responses survive, so pending client
   operations can replay their original receipts. A new HTTPS hostname requires
   browser sign-in because cookies belong to the original hostname.

Leave the source stopped after cutover. Keep its backup and state until the target
has been verified. Restarting the source is a rollback to the export point; after
the target has accepted new writes, those writes require a separate recovery plan.
Never mount PostgreSQL 18 data files into PostgreSQL 17.

An interrupted import/restore leaves a durable maintenance marker. The container
entrypoint refuses to start the server while this marker exists. Retry the same
migration archive to finish; a failed import whose transaction left the target
empty can instead be cancelled with:

```sh
sudo -u timekeeper env SYNOPKG_PKGNAME=timekeeper /var/packages/timekeeper/scripts/timekeeper-dsm cancel-empty-import
```

Cancellation refuses a database containing imported owners. A native restore can
be retried with a verified native backup while the package remains stopped.

## Building a candidate

Build from a clean commit. The image must be `linux/amd64`, carry the same embedded
clean source identity, and have the immutable name shown below. The packager
checks those properties, compiles the small static installer, bundles the image,
and writes the `.spk`, SHA-256 checksum and provenance JSON:

```sh
docker build --platform linux/amd64 --build-arg TIMEKEEPER_BUILD_COMMIT="$COMMIT" --build-arg TIMEKEEPER_BUILD_DIRTY=false -t "ghcr.io/turesheim/eclipse-timekeeper-server:sha-$COMMIT" -f server/Dockerfile .
python3 server/scripts/package_synology.py --commit "$COMMIT" --image "ghcr.io/turesheim/eclipse-timekeeper-server:sha-$COMMIT" --revision 1 --output server/target/synology
```

Preview SPKs use `1.99.0-<revision>` so the eventual stable `2.0.0` package sorts
higher in DSM. Both identify the actual server build in the web footer. Preloading
the tested image avoids unpublished PR tags and moving registry aliases.

## Candidate downloads and repository discovery

The **Server containers → Synology candidate and PostgreSQL 17** CI job uploads
`synology-preview-candidate` after both PostgreSQL major versions pass. Download
that artifact from the successful PR/main workflow and verify the adjacent SHA-256
before a manual NAS installation. It includes the SPK, source/image provenance,
preview `catalog.json`, icons and automated acceptance evidence.
An **incomplete** `dsm-acceptance-template.json` binds the real-NAS test record to
that exact candidate. Automated CI does not fill in the manual test results.

The catalog uses a separate `/synology/preview/r1000/` HTTPS directory and links to
the immutable versioned SPK beside it. Its install/upgrade flags keep the wizard
interactive. Configure SynoCommunity separately for the PostgreSQL dependency.
The generator can be run against a verified candidate directory:

```sh
python3 server/scripts/synology_catalog.py --package server/target/synology/timekeeper-1.99.0-1-r1000.spk --base-url https://turesheim.github.io/eclipse-timekeeper/synology/preview/r1000/
```

This generates files locally. **No Timekeeper package source is live yet.** First
validate manual install on the actual NAS, then test catalog discovery over HTTPS,
including the request method DSM uses, dependency resolution and version updates.
A static Pages catalog is suitable only if the tested client accepts GET requests;
a POST-requiring client needs a repository service. Stable publication remains
separate until the required NAS acceptance and hosting are resolved.
SynoCommunity inclusion remains subject to upstream acceptance.

The catalog fields follow the [SynoCommunity NAS catalog implementation](https://github.com/SynoCommunity/spkrepo/blob/main/spkrepo/domain/catalog.py)
and its [repository setup guide](https://docs.synocommunity.com/developer-guide/publishing/package-server/).

## Promoting a NAS-tested package

Keep the downloaded candidate SPK and its adjacent provenance JSON together. To
create an acceptance template locally (CI also supplies one):

```sh
python3 server/scripts/promote_synology.py --package /private/candidate/timekeeper-1.99.0-42-r1000.spk --write-template /private/candidate/dsm-acceptance.json
```

Record the full DSM, Container Manager and PostgreSQL package versions from the
tested NAS and the UTC completion time in `testedAt`. Set a check to `true` only
after that scenario passes on the actual NAS. The scenarios include both client
connections, package/reboot lifecycle and failed-upgrade recovery, migration,
backup/restore, uninstall retention and repository discovery/update. Local Docker
tests do not satisfy these checks. Do not put passwords, setup codes, tokens,
hostnames or user data in the record; additional fields are refused.

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

After this tooling is merged, the manually started **Release Synology
distribution** workflow publishes the stable SPK on an existing, published
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

## Verification status

Automated PostgreSQL 17 schema/migration tests and an unprivileged installer test
against real PostgreSQL verify role privileges, retained credentials, interrupted
provisioning, explicit owner mapping and absence of plaintext credentials in SQL
statement logs. The static project is created before runtime files are supplied,
then the real server and its selected-port healthcheck are started against native
loopback PostgreSQL; migrated passwords must authenticate as their original owners. Native PostgreSQL 17 backup/restore and corrupt-archive refusal are
also tested in the isolated package layout. A real PostgreSQL 18.6 → 17.11 transfer
compares all 14 tables with two owners, shared resource UUIDs, nanosecond timestamps,
revisions above JavaScript's exact integer range and authentication state. A
fingerprint mismatch rolls back the entire import; used targets are refused.
These do not emulate DSM Package Center
or its resource workers.

Still required before stable distribution: real DS923+ installation/start/stop/
restart/upgrade/reinstall testing, verification of PostgreSQL 18 migration and native backup/restore on the actual
NAS, and package repository discovery. SynoCommunity catalog inclusion
requires upstream acceptance. An independent Timekeeper feed will require users
to configure SynoCommunity separately; one configured source is not promised.

The packaging follows Synology's [Docker Project worker](https://help.synology.com/developer-guide/resource_acquisition/docker-project.html),
[permanent appdata layout](https://help.synology.com/developer-guide/integrate_dsm/fhs.html)
and [package privilege model](https://help.synology.com/developer-guide/privilege/privilege_config.html).
PostgreSQL connectivity follows [SynoCommunity's package documentation](https://docs.synocommunity.com/packages/postgresql/).
