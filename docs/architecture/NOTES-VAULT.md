# Notes and vaults — implementation design

Issue [#501](https://github.com/turesheim/eclipse-timekeeper/issues/501) is in
progress. This document records implementation decisions; it is not a claim
that full-vault synchronization is already available or certified.

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

Daily, weekly and event entries will reference their existing canonical
resources. Their defaults are `journal/` and `events/`; Notes, Calendar, Today,
work-session entry points and legacy WebDAV aliases must share that content.
Day labels stay independent. Namespace metadata must not become a second live
copy of a journal body. General documents and binary files need canonical
content storage, versioning and recovery, while indexes remain derived metadata.

Special-folder configuration, association-changing moves, initial indexing,
legacy replica/schema guards, archive conversion and recovery are outstanding
integration work. Their acceptance must preserve existing content and refuse
ambiguous collisions rather than guessing which document is authoritative.

The current implementation adds canonical journal bindings, owner-atomic usage
and directory generations, guarded collection operations, hierarchical WebDAV
and the Notes API. A first Notes interface is available for packaged-browser
validation. Account archive v4 stores logical entries plus separate checksummed
content/recovery files and still reads v1–v3. Schema 10 is a logical compatibility
gate; no database engine or physical resource-table migration is introduced.
Full-client, packaged-browser and recovery acceptance remain delivery gates.

## Manual WebDAV interoperability

The cached unmodified MySync 3.0.7 release contains nested-directory `MKCOL`
support and guarded file updates. Source inspection finds no WebDAV `MOVE`
request; file renames therefore need to be tested as the client's actual
upload/delete workflow, separately from server-side moves that preserve IDs.
The existing journal-only acceptance receipt does not certify vault behavior.

Test Obsidian 1.14.5/MySync 3.0.7 with populated nested folders and the expanded
namespace before declaring support. Verify folder listing, selected local and
remote roots, relative attachments, edits, empty files, rename/delete/recreate,
conflicts, offline work and token revocation over verified HTTPS.

Also evaluate a practical non-Obsidian manual folder workflow. `davfs2` is a
candidate because its documented configuration supports conditional requests;
the early result below prevents claiming full compatibility. Plain curl
is available for explicit conditional protocol operations, but those operations
alone do not certify a practical folder synchronization client. Existing
rclone/Cyberduck limitations remain until actual contrary evidence is obtained.

The first isolated probe used released Debian davfs2 1.6.1-1 against the clean
Timekeeper journal build `48d706bef02d83c2a71aa050079e31c6b67e4890` over
verified HTTPS. Conditional overwrite succeeded; an actual stale upload was
rejected with 412 and both local and remote content survived. Its actual delete
received 428 because it did not provide a sufficient precondition. Thus this
release is **not certified for full vault synchronization**. This is an early
client capability result, not a test of the unfinished vault namespace.

No Timekeeper-specific daemon, scheduler, CouchDB service or database-engine
migration is required by this issue. No guards may be weakened for compatibility.

## Remaining delivery gates

- Canonical content, reference hooks, settings, quotas, pagination and recovery.
- Hierarchical authenticated WebDAV and documented manual real-client workflows.
- Notes UI, safe relative links/embeds and exact Markdown preservation.
- Account archive and full-instance recovery coverage, including associations.
- H2/PostgreSQL, browser, real-client, authorization and concurrency acceptance.
- OpenAPI, user, deployment and operations documentation.
