# Notes and vaults — implementation design

Implementation for issue [#501](https://github.com/turesheim/eclipse-timekeeper/issues/501).
The [operations guide](../operations/NOTES-WEBDAV.md) defines supported workflows
and their observed limits; [acceptance](../verification/NOTES-VAULT.md) records
the reproducible client and packaged-browser checks.

## Document identity and paths

An account-private collection has stable entry identifiers, separate from its
display paths. Directory membership uses the existing indexed `tk_relation`
parent/child graph; normalized path and canonical-resource references use
owner-scoped records in `tk_resource`. Directory reads load metadata, not note
bodies or attachments. Moves update paths and membership atomically inside the
existing owner transaction. Existing destination content is never overwritten
implicitly. Deleted entries keep their identity and cannot be restored over a
new document occupying their former path.

Names use NFC Unicode normalization. A conservative locale-independent
upper/lower-case key detects portable case collisions; a collision is an error,
not a merge. Components are limited to 255 UTF-8 bytes, full paths to 1,024
bytes, and depth to 32. Absolute paths, traversal components, separators hidden
inside URI segments, malformed UTF-8, control characters and nonportable device
names are rejected. URI segments are decoded once. Literal percent and plus
characters round-trip without changing hierarchy.

## Existing journals and events

Daily, weekly and event entries reference their existing canonical resources.
The configured defaults are `journal/`, `events/` and `assets/`. Notes, Calendar,
Today, work-session entry points and legacy DAV aliases share canonical bytes,
versions and change events. Day labels remain independent. Raw general content
and tombstone recovery have separate canonical storage; namespace hints/indexes
never become a second live copy of a typed note.

Direct date/week names in the journal folder and stable event names in the event
folder bind to canonical resources. Date-looking names elsewhere are ordinary
Markdown. Association-changing moves are refused. Reconfiguring special folders
moves their existing roots atomically and preserves IDs/content versions, including
nested assets. Existing conflicting destinations require explicit review.

The Notes UI provides lazy metadata navigation, search, resize, mobile drawer,
keyboard access and the shared editor. Exact source and unsupported syntax are
retained; rich edits can normalize body Markdown. Relative link/image handling is
account-authenticated and generation-fenced. Opaque `.obsidian` data requires
explicit opt-in; no synchronized code/configuration is executed.

File metadata and bytes have separate validators. Directory tags include the
monotonic namespace generation; pagination rejects a stale snapshot. Random
collection identity distinguishes a replaced root. Owner transactions include
usage counters, bindings, canonical writes, recovery and change events. Historical
overages are indexed without truncation; new growth and portable restores enforce
deployment quotas.

Account archive v4 exports logical namespace records and separate checked byte
files, reads v1–v3, and rebuilds owner-private indexes on a pristine target.
Encrypted full-instance backup/preflight restores every owner's raw namespace,
canonical documents, associations, retained bytes and history. Schema 10 is a
logical compatibility fence; no table or engine migration is introduced.

## Manual WebDAV interoperability

The actual unmodified Obsidian 1.14.5/MySync 3.0.7 desktop release was exercised
against nested vaults over verified HTTPS. It handles guarded Markdown/PDF edits,
first-import and concurrent conflicts, delete/recreate, selected remote roots and
offline retry. It normalizes edited text to LF, skips unknown extensions such as
`.bin`, and does not issue MOVE. These are client limits, not lossless whole-vault
or stable-identity rename claims. The packaged Python POSIX helper synchronizes
arbitrary file bytes with explicit review, guarded MOVE/deletion, private retained
copies and an interrupted-operation journal. It is manual, with no scheduler.

Released davfs2 1.6.1-1 rejected a conditional delete (428) and remains unsupported
for full synchronization. rclone/Cyberduck/Remotely Save retain their previously
observed guard limitations. Do not weaken conditional writes for compatibility.
The [operations guide](../operations/NOTES-WEBDAV.md) documents transition from flat
journals, relative attachment links, settings, quotas, account isolation and
recovery. Acceptance receipts identify exact candidate commits and client hashes.
