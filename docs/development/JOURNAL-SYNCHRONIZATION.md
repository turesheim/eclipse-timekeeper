# Journal synchronization design and acceptance record

Issue [#396](https://github.com/turesheim/eclipse-timekeeper/issues/396) is the
one-way import delivery. This document records the implementation plan and its
acceptance gates. A parser alone does not complete the issue. Reconciliation in
both directions belongs to #397.

The bidirectional delivery keeps the same restricted SSH transport and adds
explicit per-account/per-device read permission. Format-2 exchanges bind an
immutable client snapshot to its device, local state identity and common tokens.
Only an acknowledged, durable local installation advances common state. Prepared
downloads contain only that exchange's owned note files and metadata; they do not
expose the server filesystem. Upload-only format-1 devices retain their original
permissions and missing-file behavior.

## Transport decision

Use rsync over OpenSSH, with a separate gateway and restricted identity for each
account. The web service never provisions Unix users or runs as root. A trusted
operator maps an SSH username to an existing Timekeeper account; request bodies
and Markdown cannot supply that mapping. The deployment supplies the actual SSH
endpoint and public host key, independently of the HTTPS endpoint.

The gateway requires rsync and its matching rrsync at least 3.5.1. The
[upstream release page](https://rsync.samba.org/) lists 3.5.1, released on
2026-09-21, following the 3.5.0 security release. Do not use the old rsync shipped
with macOS. The [rrsync manual](https://www.samba.org/ftp/rsync/generated-files/rrsync.1.html)
documents write-only operation, path validation, symlink munging and deletion
restrictions. Use `-wo -munge -no-del`; do not enable protected arguments, which
would hide server options from rrsync's validation.

Use OpenSSH's `restrict` authorized-key option together with a forced receiver
helper, disabled password/interactive login, no user-controlled startup files,
and a protected minimal login shell. See [sshd](https://man.openbsd.org/sshd)
and [ssh-keygen](https://man.openbsd.org/ssh-keygen). Merely choosing a destination
directory or adding client filters is insufficient confinement.

Unison is not offered for this delivery. Its [manual](https://github.com/bcpierce00/unison/blob/master/doc/unison-manual.tex)
describes two-replica synchronization and separate archive files. That would
introduce a second reconciliation authority beside
the database importer. Forcing one direction would still require the same
restricted server process, completed-batch protocol, account mapping, key
enrollment and database conflict checks. It offers no benefit for this one-way
snapshot protocol. This is a design evaluation, not a claim of transport testing;
only the rsync implementation will be advertised as supported.

## Boundaries and durable state

The local client snapshots only regular, exactly named daily Markdown files from
the selected directory into a private temporary directory. It leaves the vault,
unknown frontmatter, unrelated files and `.obsidian` untouched. SSH uses a
dedicated pinned known-hosts file and the locally generated private key. Private
keys and passphrases never go to Timekeeper or into downloaded profiles.

The receiver writes into a fresh private stage. Only a successful receiver exit,
a validated manifest and an explicit complete handoff make a batch importable.
The internal gateway credential authorizes journal transport only, and is bound
to an operator-defined account. It is never a normal API credential. Disable,
revocation and account-state checks are repeated at handoff and inside the
owner-locked import transaction. In-flight transfers must stop and cannot commit
after their authorization is withdrawn.

Persistent per-device/date state records source hash, accepted database version
and monotonically ordered batches. Distinct device baselines prevent a stale
device from silently overwriting another device. An identical note adopts a
baseline without emitting a change. A different first import or a changed
database version preserves the incoming candidate as a conflict. Resolution
requires a fresh database version and an explicit account action. Missing files
never mean deletion. An explicitly empty completed file clears the note through
the existing conditional-write path.

Accepted input, pending/conflict candidates and state remain private across
restart. The database is authoritative for calendar reads and account archives.
The importer never writes database content into an incoming file or client
vault. Only actual label/body changes generate calendar invalidations.

## Acceptance matrix

All rows are release requirements. The foundation in PR #402 supplies parsing,
private durable state, conflict resolution, device operations and trusted
configuration validation. The transport delivery adds account/internal routes,
the worker, Journal settings, the local client and the dedicated gateway image.
Account routes enforce direct sign-in, fresh password proof and CSRF for
credential/settings mutations. The worker revalidates account, device and
configuration epochs inside each owner-locked import transaction.

| Area | Required implementation and evidence |
| --- | --- |
| Markdown | Exact names/real dates, strict UTF-8, bounded safe YAML, label/body/empty notes, unknown metadata ignored, exact body whitespace, generic content-free failures. |
| Storage | Private account-mapped incoming/staging/conflict/state paths; reject traversal, symlinks, unsafe permissions and oversized content; no public assets, caches or body logging. |
| Import | Owner-locked conditional writes, per-device accepted hash/version, no-op imports, first collision and calendar-edit conflicts, explicit resolution, independent per-file failures, missing-file no-delete. |
| Durability | Sealed completed batches only; restart-safe pending work, accepted state and conflict copies; stale/out-of-order batch prevention, bounded retries and safe rescan. |
| Credentials | Local Ed25519 key generation, public-only enrollment/fingerprint/device label, reauthentication and CSRF, separate devices, atomic rotation, revocation and disable including in-flight work; metadata-only security audit. |
| SSH | Real supported rsync/rrsync and sshd; forced receiver, validated command/path, write-only confinement, no shell/forwarding/admin access, strict host pinning and mismatch denial. |
| Account UI | Dedicated Journal settings, enable/disable, actual endpoint/path/key material, copyable/downloadable account/device configuration, prerequisites, setup steps, health/counts/errors, verify/test/retry and deliberate conflict recovery. |
| Operations | Narrow operator gateway setup; account mapping; encrypted database/incoming/conflict/temp/backups, key custody and restore; server-readable trust boundary; Synology guidance. |
| Client | Safe path handling, supported tool installation, folder selection, private-key permissions/passphrase/agent, first transfer/calendar verification, macOS/Linux/WSL scheduling, disconnect and host-key replacement instructions. |
| End to end | Clean supported client: enroll, pin host, transfer/import, status, rotate/revoke; cross-account, symlink/traversal, shell/forwarding, mismatch, disabled sync/account and interrupted transfer denial. |
| Release | Full Java/API/HTTP/browser/operator-helper tests, packaged documentation, actual transport acceptance, all hosted build/container jobs green. |

Run the actual transport gate with:

```sh
python3 -m unittest discover -s server/journal -v
python3 server/scripts/journal_gateway_acceptance.py --distribution server/target/distribution/timekeeper-server.zip
python3 server/scripts/journal_gateway_acceptance.py --distribution server/target/distribution/timekeeper-server.zip --bidirectional
```

It uses disposable Docker volumes, a clean Linux client with generated local
keys, actual OpenSSH and rsync/rrsync 3.5.1, and the checksum-verified packaged
JVM over certificate-verified HTTPS. It tests exact bytes, quoted paths, no-op
and account isolation, shell/forwarding/pull/path/symlink/mismatch denial,
501-file admission pressure, independent errors, empty/missing files, conflict
recovery after forced restart, and rotation/revocation/disable while a throttled
receiver has an open private temporary file. Files and directories are checked
as 600/700 during that transfer, even with an over-permissive sender. Known test
secrets and note sentinels are forbidden in logs; diagnostic artifacts are
private. The receipt binds the test to its exact distribution hash and commit.

The HTTPS browser gate exercises all six themes, narrow layouts, public-only
enrollment/rotation/profiles/revocation, read-only and missing prerequisites,
dirty navigation, original downloads and stale conflict decisions. These tests
use a broker fixture for UI state; only the Docker gate proves actual SSH.

Both the standalone and deployment archives include the
[operator/client guide](../operations/JOURNAL-SYNC.md) and storage policy.

## Protection at rest

The deployment must meet [Journal storage protection](../operations/JOURNAL-STORAGE.md)
for the database, gateway stage, incoming/conflict copies, temporary files and
backups. Encryption keys are held separately from encrypted backups with an
operator-controlled recovery policy. The server processes readable Markdown;
SSH encryption does not provide end-to-end encryption. Missing administrator
prerequisites produce actionable Journal setup status and do not silently enable
a partially configured transport.
