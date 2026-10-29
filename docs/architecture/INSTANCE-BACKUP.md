# Full-instance backup implementation

This document describes the implementation of
[#473](https://github.com/turesheim/eclipse-timekeeper/issues/473). Administration
provides encrypted installation backups, session-bound upload and validation,
private restore preparation, destination review and explicit confirmation.
Exclusive preboot recovery applies committed consent before the framework starts.
Native DSM and Compose maintenance retain the recovered security material needed
for later restarts and disaster recovery. See the
[operator guide](../operations/INSTANCE-BACKUP.md) for deployment procedures.

## Administration workflow

An operator configures `timekeeper.instance-backup.storage` to a private storage
root whose parent already exists. The same root must be mounted on every replica;
its durable identity is bound to the installation database. The defaults reserve
at most 8 GiB per archive, 16 GiB in aggregate, and three staged jobs. Filesystems
must support the required private POSIX modes and durable writes.

Sign in with an administrator account password or passkey, then open
**Administration → Backup & Restore**. Token-derived sessions cannot authorize
instance operations. Creation asks for the current administrator password and a
separate encryption passphrase, entered twice. Store that passphrase separately;
it cannot be recovered. Passwords and phrases are cleared from the form before
waiting for a request, and are never stored in browser storage or worker messages.

The page polls at most 50 recent operation receipts, which also lets a reloaded
page discover ongoing work after a lost creation response. A ready backup has a
password-authorized native download. Check the browser download manager for
completion; handing off the request does not prove that the file was saved.
The link sends only the page origin as its referrer, as required by authenticated
API reads, while the grant remains in its private session-bound cookie.

Choose an encrypted file to upload. A local SHA-256 worker reads it in 1 MiB
chunks so the browser never needs a full archive buffer. Uploads use bounded raw
binary requests with the normal account binding and CSRF checks. To resume after
a lost reply or reload, select the same file and use the operation's resume
button. The size and digest must match its receipt before sending further bytes;
the server's durable offset decides where transfer continues. Upload access is
restricted to its initiating session. A different administrator session can
freshly authorize discarding its artifact, but cannot take over the upload.

After transfer, verify the archive using the current password and encryption
passphrase. An incorrect phrase retains encrypted bytes for retry. A successful
review shows source metadata and table counts, with an explicit warning that
target readiness, authentication checks, deployment mapping and offline
activation are still required. When persistent restore storage is configured, the interface can prepare a private handoff and explicitly commit maintenance consent for exclusive startup recovery.
**Check target database** requires fresh password proof and the upload's initiating
session. It inspects a serializable database snapshot only after archive review.
Only the sole bootstrap administrator, unchanged built-in labels and initial
profile/label change events are disposable bootstrap data. Other owners/accounts,
business resources, command receipts, API tokens (including revoked records),
modified/incomplete default labels and business history are blockers. Queries
bound label payload reads and do not load attachment contents into browser or
server memory. Unknown persisted tables or columns fail coverage.

The target-check result is advisory, carries the review's operation version, and keeps
`restoreReady` false. It does not reserve the database or authorize replacement. The separate staging and confirmation endpoints bind their own reviewed version and generation.
Activation must repeat the database check under an exclusive write fence and
complete authentication/configuration, deployment mapping and offline checks.
Existing business records remain intact when a target is rejected.

Discard removes the staged archive and work files while retaining its audit.

The checksum library is pinned, bundled locally and included in the About license
inventory. Chromium and WebKit acceptance exercises the packaged server over
HTTPS, including native download, incorrect-passphrase retry, reload recovery,
mobile layouts and accessibility.

## Encryption envelope

`InstanceBackupEncryption` uses pinned Tink Java 1.23.0 and its
[`AES256_GCM_HKDF_1MB` streaming AEAD](https://developers.google.com/tink/encrypt-large-files-or-data-streams).
It encrypts the entire supplied plaintext, including its metadata. The outer
envelope contains only eight ASCII bytes `TKIBK001`, a random 32-byte salt, and
the Tink ciphertext. The complete 40-byte outer header is authenticated as
associated data. PBKDF2-HMAC-SHA256 with 600,000 iterations derives 32 bytes of
key material from the administrator's passphrase. No keyset or passphrase is
written into the envelope. Parameters are fixed by the envelope version; inputs
cannot choose a weaker algorithm or unbounded derivation work.

Decryption consumes and authenticates the whole stream into a private staging
file before returning a path. A parser or restore operation must never consume
partially decrypted segments. The caller supplies a maximum plaintext length.
Wrong passphrases, altered headers or segments, truncation, reordering and
trailing ciphertext are rejected. Failed operations remove their staging files
and return errors without payloads, paths or underlying exception causes.
Staging currently uses POSIX directories with mode 0700 and files with mode
0600. ACL-backed staging needs verification before claiming support for a
non-POSIX filesystem. The caller owns completed files and passphrase arrays and
must delete the files and clear the arrays at the end of their lifetime.

## Complete archive and configuration inventory

`InstanceBackupArchive` creates `timekeeper-instance-v1`. The encrypted ZIP
contains `database.json`, `configuration.json`, numbered configuration inputs
and `manifest.json`. The manifest records a canonical archive UUID, UTC creation
time, logical schema, source build/deployment identity, and every file's byte
count and SHA-256. All metadata is inside the authenticated encryption envelope.

Opening an archive authenticates the complete ciphertext before parsing the
ZIP. It rejects duplicate names, directories, unsafe/unexpected paths, missing
or extra files, duplicate JSON fields, unsupported versions, integer overflow,
length/hash mismatches and configuration references that do not match the file
index. A caller-supplied budget limits both the decrypted ZIP and the total
uncompressed contents, including the manifest. Every indexed byte is verified
before disposable-database preflight. Checksummed but incomplete databases are
still rejected. The disposable database also runs source authentication preflight:
claimed accounts must have password hashes supported by the same parser as normal
sign-in, an enabled administrator and completed bootstrap must exist, token
records must have supported digests and timestamps, and passkeys must contain
parseable public COSE keys with matching credential indexes, nonempty handles,
valid transport lists and canonical RP IDs. Saved/effective service settings are
checked by the startup validator, and the effective authentication origin/epoch
must match the recovery control. A malformed credential inside an otherwise
correctly encrypted and checksummed archive is rejected; no partial review is
published. A fixed `INVALID_INSTANCE_AUTHENTICATION` code identifies this case
without exposing an account, credential or underlying exception. The interface
asks the operator to repair the source and upload a replacement backup, rather
than repeatedly changing the encryption passphrase. These checks never require a plaintext account password or an
authenticator's private key.

Old-hostname passkeys remain stored unchanged. Source validation does not make
those passkeys usable at a different target hostname, authorize an origin cutover,
or validate destination TLS/key material. Deployment review and exclusive
activation checks remain required. Source paths are metadata; opening an archive
never reads or writes those paths on the destination.

`InstanceConfiguration` captures resolved `timekeeper.*`, `quarkus.*` and
`smallrye.config.*` values, including applicable DB/SMTP credentials and
configuration encryption keys. Environment aliases referenced by canonical
configuration expressions are captured through those resolved properties, rather
than reinterpreted as additional file inputs. Explicitly declared unknown file
inputs still fail coverage. It does not collect unrelated host environment
variables. Its file inventory covers token/setup files, external configuration
files and [Quarkus 3.40 TLS inputs](https://quarkus.io/version/3.40/guides/tls-registry-reference/),
including named PEM pairs, keystores, certificate lists and flat trust directories.
Resolved `javax.net.ssl.*` settings and their explicit key/trust stores are also
captured. PostgreSQL JDBC additional SSL properties inventory `sslcert`, `sslkey`
and `sslrootcert`, including named datasources. The inventory retains conventional
JVM trust material (`jssecacerts`, otherwise `cacerts`) and existing pgJDBC
service-home certificate/key/root files as source material. These derived entries
do not override destination database or TLS settings. Secret values and original
file bytes remain inside the encrypted archive.
Classpath inputs are supported; input paths are never silently omitted when
missing, unreadable, oversized or unsupported. Unknown application file inputs
fail coverage, and a source-level test checks newly declared application
configuration properties. Files changing while being read are rejected. The archive service captures the
inventory again before publication and rejects a changed result.

There are at most 64 input files, 20,000,000 bytes per file and 64,000,000 bytes
in aggregate; the configuration JSON is limited to 4,000,000 bytes. These limits
reject the operation instead of producing a partial archive. Known output
locations, such as logs, remain metadata rather than being recursively copied.
Binary configuration buffers are cleared when their internal snapshot closes.
These secret-bearing snapshots must never become JSON API replies.

## Administrator authority

`InstanceAdministrator` accepts only current administrator browser-account
sessions with write permission. It rejects managed/legacy bearer tokens,
read-only users and sessions exchanged from API tokens. Password proof is
required, expires after five minutes before starting an operation, and is
rate-limited after five failed attempts in five minutes. A successful proof
resets its own failure counter. Proof objects are internal and cannot be
constructed from request data.

`InstanceBackupService` checks fresh proof before accessing the complete
installation, creates one encrypted archive from a backend-appropriate snapshot transaction,
then rechecks the original session and administrator role before publishing the
result. Logout, password revocation or demotion prevents publication. Its
passphrase array is cleared on success and failure. Administrator start/status/discard HTTP routes enforce browser-only authority,
CSRF and current password proof, with typed contract inputs and metadata-only
replies. Native download grants and public restore staging/confirmation routes are integrated.

## Connected backup jobs and HTTP entry points

`InstanceBackupJobs` owns asynchronous work, private working areas, publication,
authorization rechecks and cleanup. The existing parent of the configured
storage root must be provisioned first. The root entry is forced to disk before
its identity is published. Capacity is reserved before a snapshot creates any
plaintext ZIP. Each job stages under its UUID directory, so interrupted plaintext
can be identified and removed without scanning unrelated temporary directories.
Publication accepts only a private encrypted file from that working area, forces
it before recording completion, and removes the working directory. Only then
can a current, authorized worker transition its durable receipt to `READY`.

A single bounded worker rejects overlapping starts rather than queuing another
passphrase. The caller's array is cleared before returning its initial receipt;
the worker owns and clears its copy. A separate renewal task checks the original
account session and renews the lease every minute. Loss of authority or lease
prevents publication. Failed work is marked `FAILED` when its lease still belongs
to that worker, and all its files are removed. An expired lease remains available
for interruption recovery. A subsequent authorized start records abandonment and
cleans failed/interrupted/expired jobs before reserving more capacity. Audit
records remain in the database.

The administrator API starts a backup, returns status and discards a completed
artifact. HTTP tests exercise real browser cookies and CSRF, reject wrong proof,
unknown input fields, foreign origins, ordinary users, bearer tokens and
token-exchanged sessions, and validate the status reply against OpenAPI. Internal
archive reads also require fresh proof and recheck authorization and `READY`
after their integrity check. Download authorization re-verifies the current
password and issues a two-minute, one-use, session-bound HttpOnly cookie. The
separate native GET consumes it atomically, records a safe started audit event,
and streams encrypted bytes directly to the browser. No secret enters the URL or
JSON response, and the client need not accumulate a Blob. Simultaneous requests
can consume the grant only once. Replacement, expiry, wrong job/session and
logout invalidate access. Uploaded archive validation/review, public preparation/confirmation and exclusive
preboot activation enforce their separate authority and integrity boundaries.
See [API policy](API-V1.md).

## Durable operation records and schema 9

`tk_instance` contains the installation control row, operation receipts and
append-only audit records. Actor references intentionally have no owner foreign
key: replacing a destination's bootstrap account must not erase the evidence of
who authorized its restore. These rows are installation-private and must never
appear in personal exports or owner-scoped resource lists. Full-instance images
include them without filtering. The new physical table requires the explicitly
authorized schema-9 upgrade with older replicas stopped. PostgreSQL upgrades in
one transaction. H2 can resume an interrupted table creation/seed only with
explicit authorization, the exact canonical shape, and a `MIGRATING` marker.
Normal startup requires the unique control row.

`InstanceOperations` serializes work starts through that control row. Ten-minute
worker leases support renewal. An expired worker cannot renew or publish a
result; recovery records `INTERRUPTED` and permits a new operation. A restore
review has no worker lease and requires explicit discard before another job can
start. State transitions and their audit records commit or roll back together.
Audit payloads contain only the operation type and state, not exception strings,
credentials, archive contents or paths. This remains an internal transaction
component: artifact cleanup, administrator routing and offline restore activation
are still being integrated. Its interrupted status never claims to have repaired
application data or completed a restore.

## Protected artifact staging

`InstanceArtifacts` provides bounded resumable input staging on a private POSIX
filesystem. Each operation uses a UUID directory and 0600 files under a 0700
root. Metadata is small, strictly parsed JSON. The configured retained-byte
budget reserves each input's full declared length before accepting chunks;
job count and per-archive limits apply independently. It rejects symlinks,
hard-linked files, broad permissions and unknown root entries instead of
following or silently removing them. Errors contain stable codes without paths
or underlying causes.

Chunks are at most 1 MiB. Data is forced to disk before an atomic, forced
receipt update acknowledges its offset. Retrying acknowledged bytes is
idempotent only when their content matches; an unacknowledged tail is truncated
before a retry. Completion requires the declared length and SHA-256. A crash
between the final rename and metadata commit can be completed idempotently.
Reads recheck the hash before invoking the streaming consumer. A per-job
filesystem lock prevents discard during streaming, including through another
store instance. Cleanup validates all entries before removing an operation and
forces the parent directory update.

A persistent private root UUID can be bound to the database control row using
`InstanceOperations.bindStorage`. A replica with a different root identity is rejected.
Existing store handles also reject a removed/replaced identity marker. A full
restore must explicitly rebind this deployment-specific identity to the target
root, interrupt source jobs, and retain audit records. Input files and existing
backup downloads are transient operation artifacts, not recursively included
in subsequent backups. The storage component itself does not authorize requests, make
uploaded bytes trusted, validate archive semantics, or activate a restore. Its backup job service now supplies those authorization and recovery-cleanup
boundaries; native download and session-bound upload HTTP are integrated.

## Session-bound uploads and archive review

`InstanceRestoreUploads` uses the same private root, limits and durable receipts
as backup creation. A current password proof reserves the declared ciphertext
length and SHA-256. The originating account session is stored privately and is
required for append, progress and validation. Binary chunks are bounded to 1 MiB;
an acknowledged range can only be retried with identical content. Lease renewal
follows accepted writes. Cancellation removes the worker capability and lease so
an expired receipt cannot later be mistaken for abandoned active work. The audit
attributes explicit discard to the administrator who requested it.

Validation returns its durable `VALIDATING` receipt before reading the whole
file. A renewable background worker verifies transfer checksum, complete AEAD,
manifest, supported format and the relational image. It creates no active-domain
records. Decrypted ZIP/database/configuration staging stays under the operation's
working directory and is removed before `REVIEW`. The worker clears its owned
passphrase on success, rejection and shutdown. Status polling during validation
reads durable metadata without competing for the long-held archive file lock.

Invalid input returns to `UPLOADING` with an opaque validation code, retaining the
encrypted bytes for retry after another fresh password proof. No plaintext is
retained between reviews. The configurable expanded-data budget defaults to
16 GiB. Tests exercise partial transfer/resume, changed retry rejection,
incomplete input, incorrect checksum, wrong-passphrase retry, expansion limits,
session/token isolation and explicit cancellation. A real target client created
after the source backup remains unchanged by validation.

The safe review records source build/deployment metadata, archive UUID/time,
logical schema and table counts. It explicitly returns `restoreReady: false`;
archive verification alone does not prove empty-target readiness, usable restored
authentication/configuration, correct deployment mapping or exclusive activation.
The staging, confirmation and exclusive preboot phases check these additional boundaries. The deployment acceptance tests below exercise migration and later material recovery.

## Complete database image

`InstanceDatabaseImage` writes `timekeeper-instance-database-v1`. Its header
names the exact supported logical server schema. Every table has an explicit
column list and rows of nullable strings; numeric values therefore retain their
full precision independently of JSON consumers. All 23 current tables are
included, without owner or resource-type filtering. This includes password
hashes, tokens, passkeys, sessions, recovery state, both stored SMTP passwords,
private resource payloads, relationships, receipts and change history.

Backup requires one PostgreSQL repeatable-read (or serializable) transaction,
or an H2 serializable transaction, across all tables. H2 repeatable-read can see
later committed data when an independent table is read for the first time; it is
therefore rejected by the image writer. This distinction follows H2's
[isolation semantics](https://h2database.com/html/advanced.html#isolation) and is
verified against pinned H2 2.4.240: a concurrent atomic owner/settings update
between their first reads must not enter the in-progress image. A new transaction
must see the complete committed update. Metadata checks compare every physical table and column against the
portable definition. A newly persisted table or field fails coverage until its
backup semantics are implemented. PostgreSQL's `reference_key` is the sole
derived column: export verifies it against the complete external identity and
restore recomputes it. H2 timestamp values preserve nanoseconds when converted
to their portable UTC representation. PostgreSQL stores these timestamps as
strings, preserving the same precision.

The reader rejects unsupported versions, duplicate fields, unexpected tables,
wrong column lists, incomplete rows, trailing documents and missing singleton
controls. Preflight inserts the complete image into a disposable, private H2
database with foreign keys, unique constraints and checks enabled. This tests
the data before modifying a destination. Decoded strings are limited to 134,217,728 characters; the image is streamed
by table and row and has no record-count limit. PostgreSQL fetches one row at a
time to avoid retaining batches of large attachment payloads. A child JVM with a
64 MiB heap exports more than 75 MB of real PostgreSQL resource payloads in the
cross-backend regression test.

`replace` is an internal offline primitive. Its caller must establish exclusive
maintenance and verify target readiness. It preflights the complete image,
replaces rows in dependency order without disabling constraints, and leaves
commit authority with the caller. A savepoint rolls back all replacements if
staging is damaged after preflight or an insertion fails.

## Verification

The server reactor and shared module suites, API contracts, pinned web assets and
packaging checks cover the current implementation. The checks include real-runtime configuration capture, complete archive integrity,
unsafe/duplicate ZIP and JSON rejection, decompression budgets, scoped credential
proof, HTTP browser-only/CSRF boundaries, asynchronous backup lifecycle,
revocation/configuration-change publication guards, randomized multi-segment encryption,
failure cleanup, all-table credential fidelity, missing-state detection,
concurrent snapshot consistency, operation leases and audit rollback, interrupted
schema-9 upgrade recovery, and damage after validation with destination
rollback. A real PostgreSQL 17 fixture verifies H2 → PostgreSQL → H2 using
byte-identical images, including Unicode and nanosecond timestamps. Both
PostgreSQL CI jobs include these tests.

Administration preparation/confirmation/status controls, automatic JVM launch
recovery and deployment-specific maintenance share the same database/material
binding. The offline coordinator installs mapped material before replacing rows,
commits with the pending gate, publishes the active pointer and completes
activation. The native and Compose acceptance tests exercise subsequent
maintenance backup and recovery after material loss. The web process receives no
Docker socket, privileged database role or host restart credentials.

## Runtime maintenance and restore fence

Account and owner transactions, archive snapshots, calendar refresh reads and
readiness checks now check the installation control record before doing work.
A pending restore returns `503 INSTANCE_RESTORE_PENDING`; a changed restore
generation returns `503 INSTANCE_RESTART_REQUIRED`. A running process cannot
refresh its captured generation. Invalid control metadata fails closed with
`INSTANCE_FENCE_UNAVAILABLE`. These internal fields are not an activation API.

On PostgreSQL, requests hold a shared session advisory lock across their complete
transaction, acquired before opening a repeatable-read snapshot. Ready-schema
startup validation also uses a shared lock; schema upgrades retain the exclusive
transaction lock. A five-second request lock timeout returns
`INSTANCE_MAINTENANCE_ACTIVE` and discards the connection to prevent pooled locks.

The offline exclusive helper requires a fresh connection and refuses any other
connected database client. PostgreSQL uses the matching exclusive advisory lock;
H2 uses non-forcing `SET EXCLUSIVE 1`. It never terminates another client or
rolls back its work. All target replicas must be stopped before activation.
Closing the helper rolls back unfinished work before releasing exclusivity,
including on H2 where changing exclusive mode otherwise commits a transaction.
The helper enables H2 synchronous writes or PostgreSQL synchronous commits before
returning its exclusive guard.
See [H2 exclusive mode](https://h2database.com/html/commands.html#set_exclusive)
and [PostgreSQL advisory locks](https://www.postgresql.org/docs/17/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS).

The completed-generation startup check also requires the matching recovered
configuration and verified material digest. Missing material returns
`INSTANCE_RESTORE_MATERIAL_REQUIRED` before account initialization. The
Administration page continues to report `restoreReady: false` until confirmation
and launch integration are connected.

## Offline database application phase

`InstanceRestoreDatabase` now applies a fully verified portable image inside the
caller's serializable transaction and active exclusive restore guard. It repeats
the empty-target check, requires the exact reviewed operation version and actor,
and verifies the destination artifact-root identity. Database and configuration
checksums are rechecked before application and before this phase returns. Source
authentication, operation compatibility and destination/source audit identifier
collisions are checked before replacing any rows. An error rolls back the entire
phase to its savepoint; neither this component nor guard release commits it.

Business records, account password hashes, API token records, passkey credentials
and handles retain their IDs, versions and timestamps. Source audit rows remain
unchanged. Destination operation/audit evidence survives replacement of the
bootstrap account. Imported artifact operations are expired and their worker,
upload and download capabilities removed because the corresponding local
artifact files were not recursively included in the archive.

The confirmed destination package identity and authentication origin/RP policy
are recorded. Saved and effective service settings retain SMTP passwords and
settings while receiving the destination origin/RP policy and a new origin epoch
above both installations' epochs. Both settings versions advance once so a
pre-restore settings ETag cannot authorize changing the remapped configuration;
a pending saved/effective distinction is retained. Existing session rows receive epoch zero;
setup/recovery challenges, recovery links and passkey ceremonies are consumed.
Thus imported browser sessions cannot become valid at the new destination, even
if an archived row contains a future epoch. Stored passkey RP associations are
preserved: changing the hostname requires enrollment for the new RP. API tokens
and passwords remain preserved. With no HTTPS origin, email recovery is disabled.

The database control record remains `restorePending: true` with a
`DATABASE_APPLIED` phase and fresh generation. This phase does **not** clear the
gate or claim restore completion by itself. The recovery coordinator installs
material, commits this database phase, publishes the active pointer and completes
activation. The packaged launcher and explicit Administration confirmation connect these phases without replacing a live database. H2 and PostgreSQL tests exercise
actual image application, credential/data/audit preservation, checksum rejection,
refusal of populated or stale targets, and full rollback without a commit.

Separate child-JVM crash probes halt without cleanup before and after the
database commit. H2 recovery preserves the complete old database before commit
and the complete pending restored database after commit. PostgreSQL retains the
committed pending database after the client process halts. These are database
phase guarantees. The coordinator additionally tests resumption after this
commit and after interrupted completed-stage cleanup.

## Durable restore handoff and consent

`InstanceRestoreIntent` now prepares a private, versioned handoff for the offline
process. It copies the verified logical image, source configuration/material and
destination configuration/material into an independent generation directory.
The original decryption job can then close. The encryption passphrase is never
persisted. Every file has a length and SHA-256 receipt; creation uses private
permissions, file and directory fsyncs, and an atomic prepared pointer. Quotas
are checked before writes. Loading rejects malformed metadata, changed bytes,
symlinks, hard links and insecure permissions. A process lock prevents concurrent
preparation or activation; a separate-JVM halt test checks that another process
can recover the same durable handoff after lock-holder termination.

The files are not authorization. Inside a serializable account transaction,
`Prepared.authorize` requires fresh browser-administrator proof, a still-valid
session, the original upload session, exact review version and actor, unchanged
installation generation and artifact root, and an eligible empty target. The
captured destination database URL, backend and username must match the actual
connection. Consent commits the handoff generation and metadata digest into both
the operation and installation control, records `RESTORE_PREPARED`, and sets the
pending runtime gate. Rolling back that transaction leaves the files unauthorized.
Rewriting both the metadata and filesystem pointer cannot reuse committed consent
because the database retains the original digest.

The offline reader checks those database bindings before recognizing `PREPARED`
or `DATABASE_APPLIED`. The database application phase retains them through
replacement, allowing recovery to distinguish an authorized prepared handoff from
one whose database phase has committed. Tests use actual administrator proof and
PostgreSQL as well as H2; offline application uses an isolated synthetic clone,
since a live application pool correctly prevents exclusive activation.

The public staging and confirmation endpoints connect these components to the Administration workflow.
The packaged launch-time caller resumes an already-authorized handoff.
`InstanceRestoreMain` also provides a manual offline entry point; that mode does not start the HTTP server.
Unreferenced or interrupted private staging must remain private until the recovery
workflow can safely discard it; files must never be interpreted as consent by a
launcher. Launcher integration must resolve the current runtime configuration
before opening the destination and install mapped material before Quarkus starts.


## Material installation and offline completion

`InstanceRestoreMaterial` copies source configuration and every archived input
into an immutable private generation, with a checksummed index. Original source
paths remain metadata. The source token registry is validated using the runtime
parser and mapped to its copied file. Setup material is retained without being
reactivated. The source calendar trust/refresh policy is validated and retained.
Database credentials, listener settings and active TLS bindings remain with the
destination. Recovered source configuration, keys and other inactive material
are explicitly included by `timekeeper.instance-restored.files`, so another full
backup retains their bytes. Tests cover commas, Unicode and literal expression
markers in paths, changed installed files and malformed token registries.

The generated overlay has configuration ordinal 450. Before changing rows, the
offline entry point uses the real Quarkus configuration loader with the overlay
and checks required bindings and normal backup inventory limits. Conflicting
higher-priority settings fail this preflight. Later deliberate operator recovery
may use a private override properties file with an explicitly higher ordinal;
the operator guide documents restart, cancellation and material-retention procedures.

`InstanceRestoreRecovery` installs material, applies the verified image inside
the exclusive serializable transaction, commits with the pending gate, then
publishes the material pointer. Final completion verifies that pointer, records
`RESTORE_COMPLETED`/`RESTORED`, and clears the pending gate transactionally. A
repeat completion does not emit a second audit event. Startup checks the
completed generation and material digest before initializing accounts.

Completed cleanup deletes the prepared pointer before removing its temporary
plaintext image. If deletion is interrupted, recovery verifies the completed
database and active material instead of trying to reopen a partly deleted image.
Active material survives separately. Completed upload artifacts are eligible for
normal job cleanup; the completed operation receipt remains available.

The manual offline entry point is `net.resheim.timekeeper.server.InstanceRestoreMain`.
It resolves normal profiles, environment variables and external configuration,
uses only destination application JDBC credentials, refuses in-memory targets,
and opens H2 with `IFEXISTS`. It suppresses private configuration/JDBC diagnostics
and returns a fixed completion marker plus the recovered configuration location.
It requires an authorized handoff and all other target database clients stopped.
Its manual mode is an operator recovery interface; public confirmation uses automatic preboot. Separate-JVM tests cover
H2 and a non-superuser PostgreSQL application role, repeated completion, recovery
after database commit, partial cleanup, and refusal of populated targets.

Packaged acceptance verifies the actual public stage/confirm/restart protocol,
restored password and API-token access, original IDs/versions, separate day labels,
daily/weekly journals and exact attachment bytes. Signed assertions prove that a
restored passkey works at its original RP. With a changed RP, the old credential
remains stored but unusable there; password access permits reenrollment, and a new
signed assertion verifies the destination credential. The disposable private key
stays outside the archive, as an authenticator's private key would in production.

The entry point also resolves the deployment's database/SMTP password-file
variables using the same trailing-newline handling as the shell entrypoint.
Their file bytes and canonical resolved values are included in the inventory.
Credentials stay in the child environment/configuration and are not command
arguments. The shipped-helper classpath includes `app/*`, `lib/main/*` **and**
`lib/boot/*`; the boot libraries supply logging and SmallRye classes.
`server/scripts/instance_restore_acceptance.py` runs the helper and its completed
retry against a password-protected target using its credential file, from the
actual distribution without test libraries in its runtime
classpath. The normal Server CI job runs this check after packaging and retains
checksum/commit-bound evidence alongside the other acceptance reports.


## Packaged startup recovery

The fast-jar `Main-Class` is `InstancePreboot`, a JDK-only wrapper installed by
Maven after Quarkus packaging. Before loading framework classes, it launches a
short-lived helper using the shipped application, main and boot libraries. JVM
configuration properties travel through a private stdin pipe; recovered
configuration locations and file-backed credential aliases return through stdout.
Private provider diagnostics are suppressed and failures produce a fixed error.
The helper has no HTTP listener. Normal startup never creates a missing H2
database and leaves schema initialization/upgrades to the existing server path.

A committed pending handoff is recovered exclusively before framework startup.
For completed restores, startup verifies the database-bound active material and
adds its configuration overlay before initializing TLS, HTTP or scheduled jobs.
Missing or damaged required material stops startup. The original framework
entry point runs only after successful verification; a launcher shutdown also
stops its recovery child. Ordinary ready replicas do not acquire the exclusive
restore lock.

Distribution verification rejects a missing or bypassed wrapper and class bytes
that differ from the application build. Packaged acceptance starts with pending
consent, verifies restored password login and the original client ID/version,
restarts and repeats the authenticated read, then checks that missing required
material prevents framework startup. This loopback protocol check explicitly
forwards the Secure session cookie; it does not replace HTTPS browser acceptance
or the separate deployment and browser checks.


## Cancellation before database replacement

The existing fresh-password Discard action also cancels a matching uncommitted
handoff in the configured restore root. This requires its original account
session, checks only the small private handoff header, records `EXPIRED` with
`restorePhase=CANCELLED`, and commits before deleting plaintext. CSRF, administrator
and fresh-proof checks remain in effect. Other upload artifacts retain their
existing discard rules. A rolled-back cancellation cannot authorize cleanup.

After restore consent has committed and the maintenance gate is active, stop all
target server processes before cancellation. The shipped offline entry point
accepts `--cancel <restore-root> <operation-UUID>` after the main class name.
It resolves the destination configuration and password files exactly as recovery
does. Supply one JSON object on standard input with `username` and
`currentPassword` for the original destination administrator. Use a private input
pipe or a mode-0600 file; credentials must not be placed in command arguments or
shell history. A successful cancellation prints only `INSTANCE_RESTORE_CANCELLED`.
The normal server can then be started again with its original configuration.
This interface is also used for operator recovery after public confirmation. See [the operational guide](../operations/INSTANCE-BACKUP.md).

The offline connection requires exclusive access and the normal application DB
role. It checks the original account's current password, enabled administrator
state and durable rate limit. A pending cancellation trusts the exact database
consent binding, so it does not need a readable decrypted image or staging header.
The cancellation receipt remains in the destination database and audit; original
business and account data are unchanged. The source installation is never used.

Cancellation is permitted only before the replacement database transaction
commits. `DATABASE_APPLIED` and completed generations must resume recovery.
Cancelling consent clears the pending gate transactionally and preserves any
previous active generation/material binding. Cleanup verifies the receipt, keeps
active material, and removes only that cancelled generation's temporary plaintext
and unpublished material. Deleting the handoff pointer first makes partial
cleanup repeatable from the database receipt, even after its header is gone.
No general authenticated API bypass is added for the pending state.

Tests cover rollback before commit, original-session isolation and CSRF, wrong
and rate-limited credentials, missing/corrupt staged images and headers, repeated
cancellation, interrupted deletion, symlink refusal, post-application rejection,
and H2/PostgreSQL application-role execution. Exact-distribution acceptance also
cancels a pending handoff twice and verifies the original bootstrap account can
log in after normal startup and restart with no restored source client data.

Storage identities are now published atomically. Opening an established artifact
store reads its immutable identity without taking its global mutation lock;
identity validation remains mandatory for each protected operation. A regression
test holds that lock while opening another store handle, preventing upload-status
polling from intermittently failing during unrelated storage work.


## Public preparation and confirmation

`POST /api/v1/admin/instance/uploads/{id}/staging` requires the initiating browser
session, fresh password proof, the archive passphrase, current operation version
and desired public-origin/passkey mapping. A single bounded worker claims a durable
lease, rechecks bootstrap-only eligibility and copies the verified image and
configuration into the private restore root. It installs and validates unpublished
source security material before exposing READY. The destination package identity
comes from the running destination configuration; DB credentials, listener and TLS
settings stay with that destination. No live business data is replaced.

`GET .../{id}/staging` reports NONE, PREPARING, READY or FAILED with bounded,
non-secret destination metadata and the version/generation to review. Worker
renewals retain the version reserved for the published stage. Passphrases exist
only in the bounded worker and are cleared when work finishes. Lightweight staging
avoids loading a second copy of all source/target configuration bytes.

`POST .../{id}/confirm` requires fresh proof, the reviewed version/generation and
literal `RESTORE <destination-package-id>`. It validates the exact current
configuration and file digests against the staged target, rechecks the initiating
session and bootstrap-only target in a serializable transaction, and commits the
PREPARED intent and maintenance gate. Its response says PENDING_RESTART. The web
process never restarts privileged host services or replaces its live database.
All target processes must stop; starting one server invokes exclusive preboot
recovery. No general authenticated bypass is added while the gate is active.

The Administration page provides preparation fields, a destination review,
consequences, explicit confirmation and restart/recovery guidance in English and
Norwegian. The stage survives navigation/reload. HTTPS browser acceptance checks
its narrow layouts and accessibility before committing confirmation.

Before claiming a new staging worker, fresh administrator authority can reclaim
unreferenced private generation directories and interrupted pointer/install files.
The root lock excludes active writers, current database/material generations and
published viable handoffs are protected, and cleanup is audited against the
triggering operation. Obsolete failed stages can have only their matching
unpublished material removed. Active or historical material is not swept blindly.

The packaged public protocol fixture starts an empty dagsverk destination,
creates its bootstrap account through the real API, uploads/validates a separate
source archive, stages/confirms, verifies maintenance fencing, stops/restarts,
and checks restored password/API-token access, client IDs/versions, daily and
weekly journal bodies, independent labels and exact attachment bytes. Its source
archive checksum must remain unchanged. This complements the database fidelity
suite and the separate native/Compose deployment acceptance described below.

Restore preparation and confirmation retry only bounded SQL serialization/deadlock aborts (40001/40P01). Garbage collection and file work stay outside the retried transaction. Each attempt reacquires the runtime fence and current account authority; uncertain connection/commit failures are not replayed. A worker may clean its private workspace only after acquiring its lease, and cleanup finishes before a failed stage becomes retryable. Real PostgreSQL conflict and rejected-worker workspace tests cover these boundaries.


## Native package integration

`package_synology.py --package-id timekeeper|dagsverk` creates independent package
identities from the same verified application image. Package names, accounts,
appdata, containers, launchers and database defaults are distinct. The compiled
controller checks its package identity before performing lifecycle operations.
Preinstall prepares the incoming project for its dedicated UID/GID and permanent
appdata mount; it does not rewrite the source installation's installed payload.
The unprivileged application creates private instance roots under that mount.

Native maintenance format 2 retains only the immutable active generation and its
pointer. Inventory validation rejects missing/extra entries, unsafe paths,
symlinks/hard links, duplicate JSON fields, invalid private modes and mismatched
lengths/checksums. Its generation, operation, material digest and intent digest
must match the database. The restore transaction checks that binding before
commit; immutable files are installed first and the active pointer is published
afterwards, while maintenance prevents startup. Legacy archives without active
material retain format 1 support.

`instance_migration_acceptance.py` loads real Timekeeper and Dagsverk SPKs, runs
their compiled controllers and application images with separate package accounts
and native PostgreSQL, and exercises administrator export and public restoration.
It checks passwords, owner isolation, API-token access, original business
identities/versions/timestamps/relations, labels, journals and attachment bytes.
Bootstrap and copied source sessions must be rejected. New destination writes
must survive later native backup, active-material loss and recovery. The source
database and installed file bytes, permissions, owners and timestamps are
fingerprinted before and after destination operations.

The fixture represents DSM resource creation and lifecycle calls using an
isolated layout. It is not a real Package Center/NAS hardware acceptance receipt;
that remains a separate stable-publication gate.

## Compose maintenance integration

The image initializes `/instance` with mode 0700 for UID/GID 10001. Both API
replicas mount the same project-scoped `instance-state` volume and use its backup
and restore roots. Ordinary container replacement retains that volume. The
operator maintenance service mounts it for backup and restore; the web application
continues to run without Docker or host privileges.

`backup.sh` and `restore.sh` hold a kernel lock on that volume across host-side
SQL and configuration steps. `instance_material.py` reads the exact dump's schema
and instance control through data-only `pg_restore` output without executing its
SQL. A pending instance restore or incomplete maintenance marker refuses backup.
A completed database must have its matching private active material. Only the
immutable active generation enters `instance-material.tar`; transient uploads,
plaintext workspaces and prepared handoffs are excluded.

Maintenance restore validates files before extracting them, creates a durable
startup marker and installs the immutable generation. `restore-database.py` verifies
the generation/operation/material/intent binding inside the same transaction as
SQL replacement and schema validation/upgrade. A stripped or inconsistent
material archive rolls back replacement. After commit and configuration-file
recovery, the helper publishes the active pointer and removes the marker. The
entrypoint refuses startup while maintenance is incomplete. Retrying the same
complete backup is idempotent; schema 2–9 backups without active material remain
supported.

`compose_instance_restore_acceptance.py` uses two packaged Compose installations
and the actual application role. It verifies public encrypted migration, two
accounts and owner isolation, business data, journals and exact attachments;
then it tests active-material loss/recovery, concurrent-maintenance refusal,
incomplete-maintenance backup refusal, interrupted-start refusal, stripped-material
SQL rollback and complete-backup retry. These backend protocol clients explicitly
forward canonical Origin and Secure cookies as a TLS proxy would. Separate HTTPS
browser tests verify the user-facing protocol.
