# Full-instance backup implementation

This feature is being implemented for [#473](https://github.com/turesheim/eclipse-timekeeper/issues/473).
The internal encrypted archive, configuration inventory, database-image and
administrator-authority components exist. There is no administrator backup
endpoint or restore UI yet. The acceptance criteria in the
issue remain the delivery requirements; these foundations alone do not satisfy them.

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
still rejected. Source paths are metadata; opening an archive never reads or
writes those paths on the destination.

`InstanceConfiguration` captures resolved `timekeeper.*`, `quarkus.*` and
`smallrye.config.*` values, including applicable DB/SMTP credentials and
configuration encryption keys. It does not collect unrelated host environment
variables. Its file inventory covers token/setup files, external configuration
files and [Quarkus 3.40 TLS inputs](https://quarkus.io/version/3.40/guides/tls-registry-reference/),
including named PEM pairs, keystores, certificate lists and flat trust directories.
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
installation, creates one encrypted archive from a repeatable-read transaction,
then rechecks the original session and administrator role before publishing the
result. Logout, password revocation or demotion prevents publication. Its
passphrase array is cleared on success and failure. HTTP authorization, CSRF,
operation receipts, download grants and audit handling still need integration;
these service checks do not substitute for testing those routes.

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

## Complete database image

`InstanceDatabaseImage` writes `timekeeper-instance-database-v1`. Its header
names the exact supported logical server schema. Every table has an explicit
column list and rows of nullable strings; numeric values therefore retain their
full precision independently of JSON consumers. All 23 current tables are
included, without owner or resource-type filtering. This includes password
hashes, tokens, passkeys, sessions, recovery state, both stored SMTP passwords,
private resource payloads, relationships, receipts and change history.

Backup requires one repeatable-read or serializable transaction across all
tables. Metadata checks compare every physical table and column against the
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

## Verification and remaining integration

The current local server reactor passes 335 application tests and the shared
module suites. The new checks include real-runtime configuration capture, complete archive integrity,
unsafe/duplicate ZIP and JSON rejection, decompression budgets, scoped credential
proof, revocation/configuration-change publication guards, randomized multi-segment encryption,
failure cleanup, all-table credential fidelity, missing-state detection,
concurrent snapshot consistency, operation leases and audit rollback, interrupted
schema-9 upgrade recovery, and damage after validation with destination
rollback. A real PostgreSQL 17 fixture verifies H2 → PostgreSQL → H2 using
byte-identical images, including Unicode and nanosecond timestamps. Both
PostgreSQL CI jobs include these tests.

Delivery still requires protected durable artifact storage and audit integration,
HTTP upload/download and validation APIs, Administration UI,
exclusive restart-safe activation, semantic authentication/configuration validation,
deployment and hostname remapping,
Synology/Docker/standalone integration, full restored-access tests and operator
documentation. Deployment integration must still audit all recoverable inputs and applicable
security material, including native maintenance backup coverage after a restore. No privileged host credentials may be granted to the web process to
make that integration work.
