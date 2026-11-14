# Synology installation and recovery

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

For common tasks, go to [installation](#installation-flow),
[data and lifecycle](#data-and-lifecycle), [Hyper Backup](#hyper-backup-application-backup),
[native host recovery](#native-backup-and-restore) or
[encrypted instance archives](INSTANCE-BACKUP.md).

## Independent migration package

The SPK builder accepts `--package-id timekeeper` (the default) or
`--package-id dagsverk`. The latter produces a separately installable Dagsverk
package, with its own package account, appdata, container, launcher, database/role
defaults and runtime identity. Both use the same verified application image.
Use separate output directories and keep the original installation intact.
A Dagsverk package is a migration destination; installing it never renames or
reuses the original Timekeeper package's state.

Choose a different dedicated database/role and a distinct local port and public
HTTPS origin/proxy route for the destination. Do not overwrite the source's
reverse-proxy or Tailscale forwarding route. Complete the destination's disposable
administrator setup, then use the encrypted
[full-instance workflow](INSTANCE-BACKUP.md) to migrate. The confirmation must show
`dagsverk` as the actual destination identity. The original package can remain
installed and running; only stop/restart the destination during activation.

These builds are preview packages until the required real-NAS acceptance is
recorded. Changing identity does not bypass image provenance, native dependency,
archive compatibility or stable-promotion checks. The bundled guides identify the
package they address and use that package's operational paths.

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
headers or disabling Host validation. The application runs as the dedicated DSM package account's numeric UID/GID
with a read-only image filesystem. During incoming-payload validation, the
unprivileged installer records that identity and the permanent appdata path in
the project before Container Manager creates it. No Docker socket or host
administrator credentials are exposed to the application.

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
needs no database credentials. The incoming project's `/state` bind uses
permanent appdata, independent of temporary package links and replacement of the
installed payload. The server creates private instance backup and restore roots
under `/state/server`; these survive package upgrades.
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
time. Supported schema-2–9 → schema-10 upgrades run while the package is stopped:
the incoming installer validates the canonical schema, saves a native pre-upgrade
backup, and adds the required recovery/passkey/settings/locale/instance-operation tables in one transaction. Pre-schema-4 browser sessions are ended; account passwords, API tokens and private data remain.
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

For Internet accounts, choose a managed OAuth service or register Google and
Microsoft applications for this NAS. See [OAuth setup](SHARED-SIGN-IN.md). Existing
`secrets/task_providers.properties` files stay outside generated runtime settings
and are included in native and Hyper Backup application archives; see
[retained settings](TASK-PROVIDERS.md#retain-operator-settings).

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

## Hyper Backup application backup

The preview SPK includes Hyper Backup application callbacks. In Hyper Backup,
create or edit a **Folders and Packages** task and select **Dagsverk** on the
**Applications** page (or **Timekeeper** for the compatibility package). Select a
destination, schedule, version retention and encryption in Hyper Backup. No
shared folder selection, Dagsverk browser session or stored administrator password
is needed. Real DSM application discovery and backup/restore acceptance remain
required before this integration is described as supported.

Each callback creates a fresh, complete native recovery archive, including all
accounts, business data, attachments, settings and the active recovered security
material. It uses the same archive and validation as the native commands below.
This is separate from the encrypted `.tkbackup` download in Administration.
**Enable Hyper Backup client-side encryption** when protecting the destination:
the native archive contains plaintext credentials, and its confidentiality and
retention are managed by Hyper Backup. Keep Hyper Backup's recovery key separately.

The archive covers the whole selected application instance, including every
account; it is not the current user's account export. The native engine's
inventory is:

| State | Archive coverage and recovery behavior |
| --- | --- |
| Accounts and work | `database.dump` contains the dedicated database, including owners, account credentials, browser sessions, API-token state, clients, projects, tasks, activities and work sessions. |
| Notes and files | The same dump includes canonical note/file bytes, day/week/event notes, journal assets and task attachments, deleted content in the recycle bin, note revisions, and account preferences. Assets and history are database records, not an additional shared-folder tree. |
| Authentication and settings | The dump includes central settings, recovery/passkey records and instance control. `config/tokens.json`, `config/account-setup.json`, `config/mail.properties` and `secrets/smtp_password` preserve the retained bootstrap/token/mail configuration. |
| Recovered security material | Format 2 includes `instance-restore/active.json` and every file in its committed immutable generation, with inventory checksums and database bindings. This includes applicable recovered keys, certificates and source configuration. Format 1 remains readable when there is no active generation. |
| Destination connection | `settings.json` and `secrets/database_password` describe the source for native recovery, but restore keeps the installed destination's database, role, generated service password, local HTTP port and public origin. PostgreSQL roles, the NAS configuration and administrator passwords are not dumped. |

Temporary uploads, uncommitted restore stages, decrypted workspaces, locks,
generated runtime configuration, reproducible search indexes, container images,
logs and earlier native backup files are excluded. Install the compatible SPK
and dependencies separately; this application archive is not an operating-system
or PostgreSQL-package backup. Do not select the PostgreSQL data directory to make
this logical archive complete.

Backup briefly stops the selected package and restarts it if it was running.
An already stopped package stays stopped. PostgreSQL remains running and all
other clients of the selected application database must be stopped. Only the
selected package is controlled; other packages/databases are not stopped. A
concurrent callback is refused. Failed exports are reported to Hyper Backup and
do not publish a partial archive.

Allow downtime for the complete dump or restore, not just for uploading the
result. Duration and free-space requirements depend on database size. Backup
needs room in private appdata for its temporary dump and native archive, plus
Hyper Backup's staged copy. Restore additionally needs the private input copy,
unpacked dump and rendered SQL, as well as PostgreSQL transaction space. Check
both the appdata and Hyper Backup staging volumes before a large operation.

Restore through Hyper Backup's **Data** restore workflow, choosing the backup
version and application. The destination package must already be installed and
provisioned with a compatible PostgreSQL 17 database. Restore replaces its
application/account data using the existing offline transaction and checksums,
while retaining the destination service role/password, address and port. It
restores the matching active security material as part of the same maintenance
workflow. If the package was running, successful restore starts it again. Failed
restore leaves it stopped; follow native recovery below and retry the same backup.
An interrupted restore retains a private `.hyper-backup-import-*.tar` under
`server/backups` while its maintenance marker remains. Keep that archive and
marker until recovery completes.

### Recovery onto a clean destination

1. Keep the original installation and repository intact. Obtain the Hyper Backup
   recovery key from its separate storage. Install a compatible DSM, Container
   Manager and SynoCommunity PostgreSQL 17, meeting the minimums above, plus
   Hyper Backup. Retain the exact tested SPK and its version information.
2. Install the destination application package. Choose unused dedicated
   database/role names and the destination's own HTTPS origin and local port.
   Supply the PostgreSQL administrator credential only to the installation
   wizard. The installer generates a new service password; recovery must keep it.
   A newer backup schema requires a compatible application package before restore.
3. Start the fresh application once so it initializes its canonical schema, then
   stop it in Package Center. Finish or discard any unrelated pending maintenance
   using its documented recovery procedure before importing a backup. The
   destination must be installed and provisioned; Hyper Backup's application
   import is not an installation wizard.
4. Relink/open the existing repository in Hyper Backup and choose **Restore →
   Data**, the desired version, and the matching application identity. Dagsverk
   and Timekeeper are separate installed packages: use the identity recorded by
   that repository. No PostgreSQL directory or additional application-data share
   is required. Restore replaces the destination's disposable application data.
5. On successful restore, start the package if it was previously stopped.
   Authenticate using accounts from the backup, open representative notes and
   assets, inspect note history and work/tasks for more than one account, and
   create/edit a record. Verify the destination still uses its new database and
   service credential. Check origin-dependent passkeys, HTTPS/proxy routing and
   outbound mail using the [account recovery guidance](ACCOUNTS.md).
6. Keep the source and recovery archive until verification succeeds. A failed
   restore stays stopped. Retain the maintenance marker and staged native
   archive, resolve the reported prerequisite or validation problem, and retry
   the chosen backup. Do not clear the marker simply to bypass startup checks.

This procedure must be demonstrated from a real Hyper Backup repository on the
supported NAS. A successful direct native restore or Linux fixture alone does
not establish clean-destination Hyper Backup recovery.

### Failures and acceptance evidence

An unavailable database, unsupported database/schema, another connected client,
invalid archive or failed staging write makes the callback fail. A failed backup
attempts to restore a previously running source's availability; a failed restore
leaves the destination stopped. If restart fails, inspect Package Center and
resolve that failure before starting the package. Hyper Backup remains
responsible for reporting task success and retaining repository versions.
After interruption, inspect both Package Center and the native maintenance
marker before retrying; do not assume that cancelling the UI restored the prior
state. The installed Hyper Backup cancellation/lifecycle contract remains part
of the required real-NAS verification.

The root callback handles only package lifecycle and Hyper Backup staging file
descriptors. Archive creation and parsing run under the package's own numeric
UID/GID, with a controlled environment. Dagsverk gains no Docker socket or host
administrator access. Hyper Backup staging receives only the completed native
archive; uploads, decrypted instance workspaces and uncommitted handoffs are
excluded.

Before stable publication, verify on the supported NAS that both package
identities appear independently in Applications, callbacks receive DSM's actual
request/privilege environment, successful backup preserves the original package
state, and restore recovers data and active material. Also exercise a failed
export, a corrupt import, interruption/retry, and destination recovery with a new
service credential. Linux acceptance simulates DSM package lifecycle; it does not
prove Hyper Backup UI integration.

## Native backup and restore

Stop Timekeeper in Package Center before backing up or restoring. The maintenance
helper refuses an active HTTP listener, other database clients, an incompatible
schema or a PostgreSQL major version other than 17. PostgreSQL itself stays running.
The database check allows a bounded eight-second drain for closing connections
and transient background maintenance before refusing. It never disconnects a
client or ignores an unclassified backend.

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

After a full-instance restore, native archive format 2 additionally preserves the
active pointer and its complete immutable security-material generation. Every
file is checked against the material inventory and the database's generation,
operation and digest bindings. Uncommitted stages, temporary plaintext and upload
files are excluded. Backups without active recovered material retain format 1
compatibility. Native restore verifies material before replacement and checks its
binding inside the same SQL transaction; a missing or mismatched inventory rolls
the transaction back. Immutable files are installed before commit and the active
pointer is published afterwards, while the maintenance marker blocks startup.
Keep the package stopped until recovery succeeds. If existing material is damaged,
retain that directory separately before restoring a verified native archive;
recovery refuses to overwrite a conflicting immutable generation.

For encrypted portable archives and migration to another installation/package,
use [Full-instance backup and restore](INSTANCE-BACKUP.md). The native maintenance
archive above is a private host recovery artifact and is not a web download.

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
schema 2–10** into a schema-10 package. Upgrade an older source using the explicit schema-upgrade procedure in
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
   (14 for schema 2, 17 for schema 3, 20 for schema 4, 21 for schema 5,
   22 for schemas 6–8 and 23 for schemas 9–10). Older imports add empty
   passkey state, retire browser sessions without origin/browser provenance, and
   update the schema marker to the package's schema 10 in that transaction. Schema-2 imports also add
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

See [Synology packaging](../development/SYNOLOGY-PACKAGING.md#building-a-candidate)
for source/image provenance, candidate builds and release promotion.

## Manual installer downloads

The **Server containers → Synology candidate and PostgreSQL 17** CI job uploads
`synology-installer` after both PostgreSQL major versions pass. Download
that artifact from the successful PR/main workflow and verify the adjacent SHA-256
before a manual NAS installation. It includes the SPK, source/image provenance,
preview `catalog.json` and icons. Automated acceptance reports and database test
results are uploaded separately as `synology-test-results`, including on failures.
The Dagsverk packages used by migration acceptance are uploaded separately as
`synology-migration-test-packages`; they are excluded from installer downloads.
An **incomplete** `dsm-acceptance-template.json` binds the real-NAS test record to
that exact candidate. Automated CI does not fill in the manual test results.

Routine main builds use **Synology web preview** and upload
`synology-installer` too. This packages the exact image that passed
deployment startup and bundled-web checks, without repeating the database and
native backup matrix. When preview publication is enabled, the preview channel
also requires the matching commit's selected Build checks, every Web shard and
**PR validation**. Channel metadata identifies these packages as
`validation: web-smoke`; fully certified candidates use `validation: full`.
Web previews cannot be promoted to stable: stable publication still requires
the full candidate and real-NAS acceptance.

Every main build produces the installer, including documentation-only changes.
The newest verified main SPK is also available at the rolling
[Synology latest prerelease](https://github.com/turesheim/eclipse-timekeeper/releases/tag/synology-latest).
Its old assets and older temporary SPK artifacts are removed after replacement.
Versioned release assets are retained. The SPK bundles its image; installation
does not require the retired GitHub container package.

The installer artifact name describes its contents. Preview status remains in
the SPK version (`1.99.0-<revision>`), provenance and repository channel. Release
and Pages tooling also accept the former `synology-preview-candidate` name for
already-tested builds, without rebuilding their packages.

## Package sources and publication

Manual installer downloads do not require a Package Center source. Source
availability and release promotion are separate operator/maintainer decisions;
see [repository discovery and publication](../development/SYNOLOGY-PACKAGING.md#repository-discovery).

## Verification status

Acceptance is specific to a candidate and platform. Automated PostgreSQL and
installer fixtures do not emulate DSM Package Center or Hyper Backup. Keep the
exact package checksum, versions and real-NAS acceptance record when upgrading.
[Earlier package evidence](../verification/SYNOLOGY-PACKAGE.md) is retained
separately; it is not a test result for the currently installed candidate.
