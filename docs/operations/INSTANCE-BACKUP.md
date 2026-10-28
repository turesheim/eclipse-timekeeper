# Full-instance backup and restore

Full-instance archives contain all accounts, credentials, business data, journals,
attachments and captured server configuration. They are encrypted with a separate
administrator-supplied passphrase. Keep that passphrase separately from the archive;
the server cannot recover it. Personal account export is a separate feature.

This workflow is being delivered in [#473](https://github.com/turesheim/eclipse-timekeeper/issues/473).
The public JVM workflow is implemented. Native package migration acceptance and
the final security-material coverage audit remain release requirements.

## Prepare storage and the destination

Configure persistent private directories with existing parent directories:

| Setting | Purpose |
| --- | --- |
| `TIMEKEEPER_INSTANCE_BACKUP_STORAGE` | Encrypted backups/uploads and private worker files |
| `TIMEKEEPER_INSTANCE_RESTORE_STORAGE` | Prepared restore image, handoff and recovered configuration/material |

Their canonical properties are `timekeeper.instance-backup.storage` and
`timekeeper.instance-restore.storage`. Both default to `NONE`. Restore preparation
requires the second directory. On replicated deployments, every process must use
the same persistent roots and database; mismatched storage identities fail closed.

The shipped Docker Compose deployment configures both roots in its project-scoped
`instance-state` named volume, mounted at `/instance` on both API replicas. The
server image initializes that directory with mode 0700 for UID/GID 10001. Normal
container replacement and `docker compose down` retain the volume. Do not use
`docker compose down --volumes` on a live installation: it deletes this state.
Keep the same Compose project identity when replacing containers so that the
replicas retain their existing volume.

Use a private POSIX filesystem: directories have mode 0700 and files mode 0600.
Symlinks and hard links are rejected for protected inputs. ACL-backed storage is
not yet verified. Reserve space for the encrypted upload, decrypted working
archive/image, prepared image and recovered files, allowing several times the
expanded archive size. Keep these roots outside publicly served directories.

Default limits are an 8 GiB encrypted archive, 16 GiB expanded input, 16 GiB retained
encrypted artifacts and three retained jobs. See the canonical
`timekeeper.instance-backup.maximum-*` properties for deployment overrides.
These limits do not replace filesystem capacity planning.

The private configuration inventory includes explicit JVM key/trust stores and
their passwords, PostgreSQL `sslcert`, `sslkey` and `sslrootcert` additional JDBC
properties, and conventional runtime trust/certificate/key files that exist for
the service account. Empty/disabled settings are preserved. Missing configured
files, unknown file-bearing inputs or files exceeding the inventory limits fail
backup instead of producing an incomplete archive. JVM store settings follow the
[JSSE property names](https://docs.oracle.com/en/java/javase/21/security/java-secure-socket-extension-jsse-reference-guide.html);
PostgreSQL inputs use the [driver's SSL settings](https://jdbc.postgresql.org/documentation/ssl/).

These bytes are retained in the encrypted archive and immutable recovered source
inventory. They do not replace the destination's database, listener or TLS
configuration; configure deployment bindings for the destination before preparing
the restore. Source paths are metadata and are never extraction destinations.

Use a new destination installation and complete its first administrator setup.
Do not create business records, API tokens or other accounts, or change built-in
label defaults. Only disposable bootstrap data is eligible for replacement.
The destination must run a compatible current binary/schema. Preserve the source
installation and keep its original archive until the restored system is verified.

For a package migration, configure the destination's actual package identity,
database, ports, TLS/proxy and persistent directories first. The restore uses the
running destination identity; it does not rename a source package in place.
Source filesystem paths are never extraction destinations. Synology
native maintenance backups preserve the active restore material and validate its
database binding. See [Native backup and restore](SYNOLOGY-PACKAGE.md#native-backup-and-restore).
The DSM project uses permanent appdata at `/state/server` with the package account's
numeric UID/GID. It configures private `instance-backup` and `instance-restore`
roots there without granting the application Docker or privileged host access.
End-to-end migration acceptance remains a release requirement.

## Timekeeper to Dagsverk on the same NAS

Install the separately built `dagsverk` SPK alongside `timekeeper`. Select a new
application database/role and public HTTPS route; keep the original package's
settings and route. Finish only the destination's initial administrator setup.
Export/download from the original Timekeeper administrator, then upload and
prepare that archive in Dagsverk. Confirm only when the review names `dagsverk`
and the intended destination origin, RP policy, database and listener.

Stop and start the **destination** package after confirmation; the source package
and database are independent. Sign in using the preserved source accounts and
verify clients, projects, tasks, activities, sessions, labels, journals and
attachments before retiring anything. A changed RP requires the documented
passkey reenrollment; password access remains available. Preserve the encrypted
source archive and passphrase until verification and a subsequent destination
backup/recovery check have passed.

A native maintenance backup made after restoration includes Dagsverk's active
restore material. Keep that material with its database and use the destination's
own controller/account for maintenance. The full-instance archive provides
portable migration; a native maintenance archive recovers the installation and
material already bound to that package.

## Create and download an archive

Sign in with an administrator account password or passkey, then open
**Administration → Backup & Restore**. API-token sessions, including sessions
created by exchanging a token, cannot authorize instance operations.

Enter the current administrator password and a separate encryption passphrase
(at least 12 characters), confirm the passphrase and create the backup. The worker
takes a consistent database snapshot; ordinary application writes can continue.
The page can be reopened to check progress. A completed job needs fresh password
verification to authorize its one-use native download.

Check the browser download manager for completion. Save the archive and passphrase
securely. Discarding the server artifact removes its staged files while retaining
the operation receipt/audit. Backup creation and download do not change the source
installation's business records or credentials.

## Upload, prepare and confirm

1. On the empty destination, select the encrypted archive, wait for its local
   checksum and enter the destination administrator password to upload it.
   Re-selecting the same file can resume an interrupted upload from the server's
   acknowledged offset. The initiating browser session owns the upload.
2. Verify the uploaded archive using the destination password and archive
   passphrase. Incorrect passphrases and incomplete/unsupported archives are
   rejected. A wrong passphrase can be retried without uploading again.
3. Review the source build/schema and table counts. **Check target database** is
   advisory; preparation, confirmation and offline activation recheck eligibility.
4. Choose **Prepare restore**. Enter the destination public origin, or `NONE` for
   local access, and the desired passkey RP configuration. Database credentials,
   listener ports and TLS stay with the destination. Enter fresh password proof
   and the archive passphrase again. Preparation copies and verifies private files
   asynchronously without replacing live data.
5. Review the prepared destination package identity, origin, passkey policy,
   database backend and listener ports. Configure any new hostname/proxy route
   before activation. Changing target configuration after preparation invalidates
   confirmation; discard the stage and prepare it again.
6. Enter the displayed `RESTORE <package-id>` text and the current destination
   administrator password. Confirmation binds the exact reviewed generation and
   version, commits maintenance consent and returns **restart required**.

The prepared stage survives page navigation and reload. Discard cancels an
uncommitted stage and removes its private copies only after a durable cancellation
receipt. Failed preparation can be retried; abandoned, unreferenced staging is
reclaimed under the private root lock.

Confirmation does not restart a host service or replace a database in a running
web process. After confirmation, normal API calls are unavailable until recovery
finishes. If the response is lost, do not assume the operation failed or create
another upload: follow the restart/recovery procedure and inspect the outcome.

## Activate and verify

Stop **every** process connected to the destination database, including other
replicas and external database clients. Then start **one** destination server
using its normal configuration and persistent restore root:

- For standalone Java, stop all target JVMs/services and start one normally.
- For Docker/Compose, stop all target replicas and start one using the same
  database configuration and persistent mounts.
- For Synology, stop the destination package and all other target DB clients,
  then run the destination package. Keep the separate source package unchanged.

The packaged preboot helper verifies committed consent and configuration before
starting the framework, TLS, scheduled jobs or HTTP. It acquires exclusive database
access, applies the verified image transactionally, publishes recovered material
and completes the restore. Other connected clients cause refusal rather than
forced disconnection. Do not bypass this refusal or manually clear the gate.

Once healthy, use a **source account's** password to sign in at the reviewed
destination origin. The disposable destination bootstrap account is replaced.
Verify clients/projects, sessions, journals, labels, attachments and account access
before starting additional replicas or retiring the source installation.

Source account IDs, password hashes, API-token records and passkey records are
preserved. Existing browser sessions and recovery links/ceremonies are invalidated.
API tokens retain their original permissions, expiry and revocation state.
Passkeys tied to another hostname/RP need reenrollment; their authenticator private
keys were never held by the server. Keep password access available during migration.

**Keep the restore root after success.** Its active configuration and recovered
security material are required for normal startup and subsequent full backups.
A completed database without its matching material will refuse startup.

## Compose maintenance after an instance restore

Use the shipped `backup.sh` and `restore.sh` together with their
`instance_material.py` helper. Maintenance operations use a kernel lock on the
shared private volume, so a second operator cannot interleave backup and restore.
The normal web application has no Docker access; these are operator commands.

`backup.sh` checks the exact dump's instance control record and retains only its
committed active material generation in `instance-material.tar`. It verifies all
paths, private modes, sizes and checksums. Uploaded archives, plaintext workspaces,
prepared handoffs and process locks are excluded. A pending instance restore or a
completed database without matching material causes backup to fail.

Keep the complete backup directory, including `instance-material.tar`, database,
token registry and mail files. This maintenance directory contains credentials in
plaintext and requires private storage. Use Administration's encrypted full
instance archive for portable migration and encrypted off-machine storage.

`restore.sh` stops both replicas, validates the material against the dump, sets a
durable maintenance marker and installs immutable files before replacing SQL data.
The database transaction checks its material binding before committing. Only then
does the helper publish the active pointer and remove the marker. Configuration
files are restored before startup is allowed. If interrupted, keep replicas stopped
and rerun `restore.sh` with the same complete backup; do not remove the marker.
Missing material or a mismatched database rolls back SQL replacement. Older backups
without an active restored generation remain supported.

The named volume must be backed up and retained; a database dump alone is
insufficient after a full instance restore. If the volume is lost, the complete
maintenance backup can reconstruct its committed material before normal startup.

## Interrupted recovery and cancellation

If startup is interrupted, start one destination server again with the same
configuration/root. Recovery recognizes committed phases and resumes; completed
retries do not reapply data or duplicate the completion audit.

Before the replacement database transaction commits, an operator can instead
cancel offline using the original destination administrator's current username
and password. Stop all target clients first. Run the shipped helper from the
server distribution with the destination's normal external configuration:

```sh
python3 -c 'import getpass,json,sys; sys.stderr.write("Administrator username: "); name=sys.stdin.readline().strip(); print(json.dumps({"username":name,"currentPassword":getpass.getpass("Current password: ")}))' |
  java -Dquarkus.config.locations=file:///absolute/path/destination.properties \
    -cp 'app/*:lib/main/*:lib/boot/*' \
    net.resheim.timekeeper.server.InstanceRestoreMain \
    --cancel /absolute/path/restore-root OPERATION-UUID
```

Keep credentials out of command arguments, logs and shell history. The JSON input
can also come from a mode-0600 file. File-backed database/SMTP credential variables
are resolved by the helper. It uses the ordinary destination application DB role,
requires current administrator credentials and applies a durable rate limit.
Successful cancellation prints only `INSTANCE_RESTORE_CANCELLED`; start the
original destination normally afterward.

Cancellation can use the database consent record even when staged images or
headers are damaged. Its receipt permits repeated cleanup after interruption.
After the database phase commits, cancellation is refused: resume recovery instead.
Do not delete active material, edit database control rows or treat private files
alone as authorization. Preserve the encrypted archive and consult the fixed
startup failure guidance if required material or configuration is missing.

## API boundary

The [OpenAPI contract](../../common/api/openapi.json) defines the browser-session
routes under `/api/v1/admin/instance`. Uploads use bounded binary chunks;
`uploads/{id}/staging` prepares/reports a private stage and `uploads/{id}/confirm`
commits explicit consent. Browser CSRF and owner binding, administrator write
access, original-session ownership and fresh password proof are enforced at the
appropriate boundaries. Passwords/passphrases are write-only and never appear in
operation receipts. Status exposes bounded deployment metadata rather than file
paths, credentials or authorization capabilities.
