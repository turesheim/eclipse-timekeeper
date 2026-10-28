# Synology Package Center distribution

The initial NAS acceptance target is **DS923+ / r1000, DSM 7.4.1-90080**.
The package uses DSM **7.2.2 or later** resource interfaces,
Container Manager with the Docker Project worker (**build 1432 or later**), and
SynoCommunity PostgreSQL **17.11-6** on its normal port **5433**. Real DSM acceptance
is required before this candidate can be described as supported or published in
a stable feed. The [generic Compose deployment](DEPLOYMENT.md) remains available.

The current scope of #297 is a downloadable SPK installed through **Package
Center → Manual Install**, with NAS lifecycle tests and migration/recovery
instructions. Public repository discovery and release publication are deferred;
they are not acceptance gates for this implementation. Publication tooling
described below is prepared for future use. Preview publication remains off by
default.

## Installation flow

Install SynoCommunity PostgreSQL and Synology Container Manager first. Native
PostgreSQL must already have the administrator credentials chosen in its own
wizard. Use **Package Center → Manual Install** for the candidate `.spk`.

The Timekeeper wizard asks for those database administrator credentials, unused
dedicated database/role names and either **Tailscale integration** or a manually
configured public HTTPS origin from DSM Reverse Proxy or
[Tailscale Serve](TAILSCALE.md). The local HTTP port is selected automatically:
8080 when available, otherwise a free port assigned by the operating system.
An optional port override is available under **Advanced settings**. The selected
port is retained across restarts, repairs and upgrades. With integration enabled,
Timekeeper detects Tailscale, derives the HTTPS address and supplies the manual
root command for forwarding to that port. Configure forwarding yourself as
described below or in the Tailscale guide. The wizard also displays
a random one-time setup code: copy it before finishing. No database administrator
password is retained by Timekeeper.

Use **Open**, choose **First administrator setup**, and enter that code to create
your username, password and primary recovery email. Codes expire 15 minutes after installation. New
identities use a random stable owner ID; claiming existing data requires its
explicit existing owner ID. [Account operations](ACCOUNTS.md) describes privacy,
sessions, named Eclipse tokens and recovery.

The low-privilege installer creates a dedicated non-superuser role and database
from PostgreSQL's `template0`, avoiding unrelated extensions in SynoCommunity's
`template1`. It refuses DSM's built-in PostgreSQL port 5432. Container Manager's
resource worker loads the bundled immutable Java 21 application image and owns
its lifecycle; there is no Timekeeper PostgreSQL container or host Java dependency.

The DSM container is named `timekeeper-server`. The application uses host
networking to reach native PostgreSQL on loopback and binds its HTTP listener to **127.0.0.1**. HTTPS and the exact public origin are
required for browser sign-in. The selected HTTP port is also used by the container
health check. The installer allows the public origin's hostname in Quarkus's HTTP
Host validation, alongside localhost for health checks. Other hostnames remain
blocked; forwarding through Tailscale Serve does not require trusting forwarded
headers or disabling Host validation. The application runs as UID 10001 with a
read-only filesystem.

When moving from an earlier preview that generated the container name
`timekeeper-server-server-1`, uninstall the old Timekeeper package before manually
installing the new preview. Keep its permanent appdata and PostgreSQL database.
On the recorded Container Manager version, an in-place 131 → 133 rename created
the new container but failed resource acquisition with `container
[timekeeper-server]'s profile should exist!`. Normal uninstall removed the
project/container, and a retained-data installation created the new name.
The failed upgrade preserved all 14 table fingerprints, service credentials and
retained identity/settings. This one-time preview name transition must not be
counted as successful in-place upgrade acceptance.

The complete Compose project and empty bind directories ship in `package.tgz`:
DSM acquires the project **before** `postinst`. The installer then copies retained
configuration into those existing directories under a mode-0700 package-private
parent. Quarkus reads runtime properties after container start, so project creation
needs neither database credentials nor install-time environment substitution.
`prestart` synchronizes retained files and checks maintenance state before startup;
`start` waits for application readiness after the worker starts the container.
Stopped status uses DSM exit code 3. See Synology's [resource timing](https://help.synology.com/developer-guide/resource_acquisition/timing.html)
and [script lifecycle](https://help.synology.com/developer-guide/synology_package/scripts.html).

### Synchronization diagnostics

Server diagnostics appear in **Container Manager → timekeeper-server → Log**, or
through SSH with `sudo docker logs --since 30m timekeeper-server`.
API calls taking at least one second produce `API request completed` warnings
with a generated `request_id`, route template, HTTP status and `elapsed_ms`.
A request still running after five seconds produces an earlier warning. Normal
`/changes` long polling receives its requested wait allowance before either
threshold applies. HTTP 5xx responses are logged even when fast.

Slow database transactions report `pool_ms`, `owner_lock_ms`, `work_ms` and
`commit_ms`, separating connection acquisition, account locking, application work
and commit time. Database errors also report a SQL-state code, without the
underlying statement or exception message. These diagnostics exclude
credentials, query strings, record IDs, account IDs, SQL and request contents.
The `X-Timekeeper-Request-Id` response header links a browser Network entry to its
server log line. Server timings measure REST handling; network latency and proxy
or HTTP-worker queuing before REST handling can add further time.

The defaults are controlled by `timekeeper.diagnostics.slow-request-ms` and
`timekeeper.diagnostics.slow-transaction-ms` (both `1000`). Setting a threshold to
`0` temporarily logs every completed request or transaction; restore the default
after diagnosis. The web browser console additionally reports refreshes taking
five seconds or more as `Timekeeper synchronization slow`, with duration and
attempt count. Each browser snapshot has a 30-second deadline, including
pagination and consistency retries, and discards unfinished reads on failure.

### Optional Tailscale integration

Install Tailscale separately and sign the NAS into your tailnet. Enable MagicDNS
and HTTPS certificates in the Tailscale admin console. The installation and
upgrade wizards detect Tailscale and explain missing prerequisites. Select
**Tailscale integration (manual forwarding as root)** and an unused HTTPS port
(default **8443**). Timekeeper derives the NAS hostname and configures its own
public HTTPS origin. No separate Tailscale instance is started.

Timekeeper runs as its unprivileged DSM package user. It reads Tailscale status
and checks for conflicting forwarding; it never modifies Serve, requests sudo
permission or installs a root helper. An existing identical private Timekeeper
rule is accepted. A conflicting service, foreground session or public Funnel
configuration is refused so it cannot be overwritten by the suggested command.

After installation, create a **Task Scheduler → Triggered Task → User-defined
script** with user **root** and run it manually once. First inspect existing
forwarding using `/var/packages/Tailscale/target/bin/tailscale serve status`.
If the chosen HTTPS port is free or already forwards this Timekeeper instance,
run the command shown in the wizard:

```sh
/var/packages/Tailscale/target/bin/tailscale serve --bg --https="$(cat /var/packages/timekeeper/target/private/config/tailscale-https-port)" "http://127.0.0.1:$(cat /var/packages/timekeeper/target/private/config/http-port)"
```

The files contain the actual selected ports, including an automatically assigned
HTTP port. The same command works from a root SSH session. From a normal
administrator SSH session, use `sudo -i` first. Delete the one-time Task Scheduler
task afterwards. The installer also saves instructions with the concrete ports in
`/var/packages/timekeeper/var/server/TAILSCALE.txt`.

The `--bg` rule persists across NAS and Tailscale restarts. Upgrades preserve the
selected address and ports. If you change the HTTPS address or disable integration,
update or remove forwarding manually; changing Timekeeper's selection does not
modify Tailscale configuration. HTTPS certificates, tailnet access rules and
Tailscale sign-in remain administrator responsibilities.

Before uninstalling, inspect Serve and remove the Timekeeper root handler
manually as root, using the command shown in the uninstall wizard. For port 8443:

```sh
/var/packages/Tailscale/target/bin/tailscale serve --bg --https=8443 --set-path=/ off
```

Use the configured HTTPS port if different. Remove the handler only if it still
forwards Timekeeper; another application may have replaced or shared the
listener. Do not use `serve reset`, which removes other applications' forwarding.
Uninstall removes Timekeeper's container/project and retains its database and
appdata; it does not change Tailscale rules.

See the [Tailscale Serve reference](https://tailscale.com/docs/reference/tailscale-cli/serve)
for persistence and HTTPS prerequisites. Leaving integration unchecked retains
the manual DSM reverse-proxy or Tailscale configuration flow.

## Data and lifecycle

The private `/var/packages/timekeeper/var/server` directory contains settings,
the application database credential, hashed setup/token configuration and
backups. DSM links `var` to permanent `@appdata/timekeeper` on the selected
package volume. This directory exists during installation and survives package
uninstallation. It uses mode 0700 under the package account; it is not an SMB
shared folder. PostgreSQL data remains with the separate PostgreSQL package.
Stopping or uninstalling Timekeeper never drops databases, roles or other packages.

DSM removes the old package's `var` link before the incoming `preinst` during
upgrade. The helper resolves retained data using the SDK's permanent appdata
location or `${SYNOPKG_PKGDEST_VOL}/@appdata/timekeeper` while that link is absent.
It never scans other volumes or treats missing upgrade data as a fresh install.

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
time. Supported schema-2/3/4/5/6/7 → schema-8 upgrades run while the package is stopped:
the incoming installer validates the canonical schema, saves a native pre-upgrade
backup, and adds the required recovery/passkey/settings/locale tables in one transaction. Pre-schema-4 browser sessions are ended; account passwords, API tokens and private data remain.
An interruption keeps
the backup and a maintenance marker; startup stays blocked until retry completes.
After database commit, retry publishes the new configuration without rerunning
the migration or replacing the original backup. Other schema changes/downgrades
are refused. The pre-upgrade check itself does not modify data.

SMTP is optional and initially disabled. Retained `config/mail.properties` and
`secrets/smtp_password` seed it independently of regenerated runtime files.
After schema-5 startup, edit service settings in Administration and restart the
package. Protected backups include central settings and their private SMTP
secrets. Normal upgrades preserve them. If an old hostname is unreachable, follow
the explicit [operator recovery](ACCOUNTS.md#change-the-public-hostname) workflow.
See [email recovery and hostname changes](ACCOUNTS.md#recovery-email-addresses)
before enabling delivery or changing a VPN/public hostname.

Back up before upgrades. A failed application upgrade leaves native data and
retained configuration intact; retain the previous `.spk` for recovery. Real DSM
checks must verify package repair, reinstallation permissions and failure recovery.
Do not remove the native PostgreSQL package or the retained appdata as part
of an application rollback.

The failed early-preview upgrade from 128 to 129 left the previous package-owned
Container Manager project behind. Later installs reported `name 'timekeeper-server'
duplicated`; removing those broken replacement installs did not remove the old
project. Recovery identified the orphan by `is_package: true`, project name
`timekeeper-server` and working directory `/var/packages/timekeeper/target/project`
before removing its registration and stopped container as an administrator.
Native PostgreSQL and retained appdata were preserved.

On the recorded DS923+ target, recovery was followed by a clean 131
install/start/stop/backup/uninstall cycle. Normal uninstall removed both project
and container; retained files and all 14 database table fingerprints were
unchanged. This does not replace the remaining NAS acceptance scenarios required
for stable publication.

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
17 `pg_restore` to render SQL, then applies that SQL and any supported schema
upgrade in one database transaction. It restores business/account data and setup/token
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
schema 2, 3, 4, 5, 6, 7 or 8** into a schema-8 package. Upgrade an older source using the explicit schema-upgrade procedure in
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
   schema, then compares fingerprints of **all source tables before committing**
   (14 for schema 2, 17 for schema 3, 20 for schema 4, 21 for schema 5, 22 for schema 6, 7 or 8). Older imports add empty
   passkey state, retire browser sessions without origin/browser provenance, and
   update the schema marker to 8 in that transaction. Schema-2 imports also add
   empty recovery state. Schema-3/4 imports retain verified/pending email
   addresses and recovery state; schema 4/5 retains public credentials, opaque
   handles and revocation records. No UUID remapping
   or timestamp conversion is performed. Schema 5 also retains central service
   settings, including protected SMTP credentials and pending changes. Schema 6
also retains account language preferences; older sources start with browser defaults. Older
   sources seed settings from the target deployment. Keep the canonical source
   address, or use explicit operator recovery before starting at a different
   address. For older sources, configure SMTP separately for the target;
   carry forward the source origin epoch for the same address, or configure a
   strictly newer epoch when changing the address before starting the target,
   including when mail is disabled. See [passkey migration](ACCOUNTS.md#configure-the-origin-and-rp).

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
higher in DSM. Both identify the actual server build under **About** in the web client's account menu. Preloading
the tested image avoids unpublished PR tags and moving registry aliases.

## Manual installer downloads

The **Server containers → Synology candidate and PostgreSQL 17** CI job uploads
`synology-installer` after both PostgreSQL major versions pass. Download
that artifact from the successful PR/main workflow and verify the adjacent SHA-256
before a manual NAS installation. It includes the SPK, source/image provenance,
preview `catalog.json` and icons. Automated acceptance reports and database test
results are uploaded separately as `synology-test-results`, including on failures.
An **incomplete** `dsm-acceptance-template.json` binds the real-NAS test record to
that exact candidate. Automated CI does not fill in the manual test results.

The installer artifact name describes its contents. Preview status remains in
the SPK version (`1.99.0-<revision>`), provenance and repository channel. Release
and Pages tooling also accept the former `synology-preview-candidate` name for
already-tested builds, without rebuilding their packages.

### Deferred repository discovery

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

## Deferred: Pages package sources

The Pages workflow assembles separate DSM channels alongside the documentation
and Eclipse update sites. It reacts to both the main build matrix and Server
containers, and to release publication workflows. These source URLs become
available **after the Pages changes are merged and deployed**:

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

## Deferred: promoting a package for public distribution

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

Candidate **1.99.0-140** passed installation, 133 → 140 upgrade, start/stop,
retained-data uninstall/reinstall and automatic startup after a NAS reboot on the
recorded target. Native migration and restore matched all 14 table fingerprints;
web and shared Eclipse HTTP client access and user isolation were checked over
Tailscale HTTPS. Installed Eclipse UI tests passed in CI. The optional managed
Tailscale integration was added afterwards: its read-only detection, conflict checks and manual setup/cleanup
instructions have automated tests, with wizard validation tracked in PR #300. These checks do not constitute public-feed acceptance.
Repository discovery and public release publication are separate future work. Before a public package feed is
enabled, its discovery/update behavior must be tested; SynoCommunity catalog
inclusion also requires upstream acceptance. An independent Timekeeper feed
would require users to configure SynoCommunity separately.

The packaging follows Synology's [Docker Project worker](https://help.synology.com/developer-guide/resource_acquisition/docker-project.html),
[permanent appdata layout](https://help.synology.com/developer-guide/integrate_dsm/fhs.html)
and [package privilege model](https://help.synology.com/developer-guide/privilege/privilege_config.html).
PostgreSQL connectivity follows [SynoCommunity's package documentation](https://docs.synocommunity.com/packages/postgresql/).
