# Bidirectional journal reconciliation

Issue #397 extends the completed one-way delivery in #396. This work is optional
per account and device. Existing devices remain write-only. A fresh, explicitly
authorized device credential is required for outbound journal reads; disabling
the capability, sync, device or account invalidates in-flight exchange work.
The default client-to-server mechanism and its restrictions remain available.

## Meaning and common state

The database remains authoritative for Calendar. A private per-device/date
common base records the last acknowledged raw document hash, database version,
deletion state and unpredictable base token. The client stores its base tokens
and a persistent client-state identity outside the daily-note folder. Lost or
different client state cannot turn an initial scan into mass deletion: missing
files are deletions only when explicitly tracked against an acknowledged base.

Clearing a note and deleting it are distinct. The public day-note resource
retains a versioned deletion marker; a normal save undeletes deliberately.
An explicitly empty Markdown file is a present empty note. A never-seen missing
file has no deletion meaning. Tombstones persist until the relevant devices
acknowledge them; initial collisions never silently delete or resurrect data.
Existing empty database records remain cleared notes; deletion markers are
created only by explicit deletion, never inferred from older empty content.
Markers are private records bound to the exact note version. An older replica
can still save the ordinary note payload during rolling updates; its later
version cannot accidentally retain an earlier deletion marker.

Only one side changed since the common base: import the external change or
export the database change. Identical results adopt a base without duplicate
Calendar writes. Both changed: retain both variants and require an explicit
decision, including delete/edit conflicts. Database writes and decisions use
the existing owner lock and conditional version checks.

## Exchange and acknowledgement

1. The client snapshots regular daily files privately. Its manifest includes
   hashes, acknowledged base tokens and explicit tracked deletions.
2. The restricted upload completes into a fresh private stage. A verified
   completed manifest is admitted transactionally; partial uploads have no
   meaning. Reconciliation produces durable immutable offers or conflicts.
3. A read-capable device may fetch only its own prepared outbound exchange.
   rsync reads a fresh private snapshot through a forced read-only rrsync
   helper, never a live vault or arbitrary server path. Shells, forwarding,
   unrelated reads and cross-account access remain denied. Authorization is
   rechecked while receiving and sending.
4. The client receives into private staging, validates names, hashes, sizes and
   regular-file permissions, then checks the canonical local file against the
   exact input snapshot before applying an export. It never runs opposing
   rsync commands directly over the canonical folder.
5. A durable local operation record precedes file mutation. Existing files move
   into private recovery storage; exclusive installation cannot overwrite an
   editor's concurrently created replacement. Conflicting variants remain
   recoverable outside the canonical daily-note folder. The private state must
   share its filesystem with the selected folder for atomic/exclusive moves.
6. Acknowledgement binds the offer token and installed hash/deletion state.
   Only then does the server advance the common base. A newer browser save
   remains a change after that common snapshot, rather than being overwritten.
   Duplicate acknowledgements are idempotent; stale acknowledgements cannot
   roll back a newer base. Restart resumes a recorded operation or preserves
   all variants for recovery.

Unknown YAML properties survive exports through the safe YAML parser. When
content is unchanged, retain original bytes. For Calendar changes, replace only
the supported label/body meaning while preserving unknown properties and exact
unsupported Markdown. State, credentials and version metadata never enter the
portable Markdown document.

## Delivery gates

The internal storage/protocol foundation may be reviewed separately from the
client, gateway and account UI. A foundation does not close #397. Completion
requires the complete opt-in setup/profile flow and real SSH acceptance for
both directions, permissions, cross-account denial, host mismatch, key
rotation/revocation/disable, initial synchronization, either-side changes,
simultaneous changes, empty versus deleted notes, delete/edit conflicts,
unknown YAML, unsupported Markdown, duplicate/partial exchanges, restart and
local editor races. All builds must pass before the issue is complete.

The user guide must explain first synchronization, private recovery copies,
lost-state recovery, tracked deletion and interactions with other vault sync
tools. A second tool must not bypass this protocol's local compare/exclusive
installation boundary or treat its private recovery/state as daily notes.
