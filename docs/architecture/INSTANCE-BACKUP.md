# Full-instance backup implementation

This feature is being implemented for [#473](https://github.com/turesheim/eclipse-timekeeper/issues/473).
The internal encryption and database-image primitives exist. There is no
administrator backup endpoint or restore UI yet. The acceptance criteria in the
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

## Complete database image

`InstanceDatabaseImage` writes `timekeeper-instance-database-v1`. Its header
names the exact supported logical server schema. Every table has an explicit
column list and rows of nullable strings; numeric values therefore retain their
full precision independently of JSON consumers. All 22 current tables are
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
by table and row and has no record-count limit.

`replace` is an internal offline primitive. Its caller must establish exclusive
maintenance and verify target readiness. It preflights the complete image,
replaces rows in dependency order without disabling constraints, and leaves
commit authority with the caller. A savepoint rolls back all replacements if
staging is damaged after preflight or an insertion fails.

## Verification and remaining integration

The current local server reactor passes 305 application tests and the shared
module suites. The new checks include randomized multi-segment encryption,
failure cleanup, all-table credential fidelity, missing-state detection,
concurrent snapshot consistency and damage after validation with destination
rollback. A real PostgreSQL 17 fixture verifies H2 → PostgreSQL → H2 using
byte-identical images, including Unicode and nanosecond timestamps. Both
PostgreSQL CI jobs include these tests.

Delivery still requires the complete archive and configuration inventory,
checksummed manifest, administrator session and fresh-proof checks, protected
operation storage, upload/download and validation APIs, Administration UI,
exclusive restart-safe activation, deployment and hostname remapping,
Synology/Docker/standalone integration, full restored-access tests and operator
documentation. In particular, a database image is not a complete installation
backup: external configuration and applicable security material must also be
covered. No privileged host credentials may be granted to the web process to
make that integration work.
