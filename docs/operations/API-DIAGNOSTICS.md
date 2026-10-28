# Read-only API diagnostics

Use a separate personal token under **My Account → API tokens**, with
**Read-only** access and a short expiry. Administrator access is unnecessary for
inspecting your own labels and notes. Store its secret outside the repository
in an owner-readable curl configuration file, for example:

```text
header = "Authorization: Bearer <personal-token>"
```

Set the file's permissions to `0600`. Keep the configuration private and use a
certificate-validated HTTPS connection to the same Timekeeper server as the web
client. The operating system's curl may use its system certificate store when a
separate Python runtime does not trust the operator's local certificate issuer.

With `TIMEKEEPER_DIAGNOSTICS_CONFIG` naming that file and
`TIMEKEEPER_DIAGNOSTICS_URL` naming the HTTPS server, the following are read-only:

```sh
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/auth/session"
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/server"
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/day-labels?fromDate=2026-10-01&toDate=2026-10-31"
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/day-labels/2026-10-27"
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/day-notes/2026-10-27/content"
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/day-entries/2026-10-27"
```

Check `/auth/session` for the actual permissions; a token granted write access
must still be used only for reads during a read-only investigation. `/server`
identifies the deployed commit and startup/deployment times. `/day-labels`
reports the independent label version; `/day-notes/{date}/content` reports only
the note's body, presence/deletion state and version. `/day-entries` supplies the
editor projection plus the independent resource ETags. A cleared label retains
its version. A deleted note may coexist with a nonempty label. Body-only edits
must not change the label version or its ETag, and label-only edits must not
change the note version, ETag or WebDAV bytes.

On an older server without these independent routes, `/day-notes/{date}` and its
range listing expose the legacy combined projection. WebDAV `HEAD`, using a
separately configured Basic-auth client, exposes the latest note modification
time. The generic `/changes` feed contains content-free PROFILE invalidations;
it cannot identify which old label changed or attribute that change to a client.
Current-state reads and version numbers cannot reconstruct unrecorded history.

For an unexplained historical loss, compare a pre-update backup in an isolated
restored instance. Keep the restored instance separate from live clients and
compare label values, note versions and deployment identity. Do not restore it
over manually corrected live data as part of diagnosis. Record only synthetic
values in public issues; keep authorization headers and private account content
out of logs, screenshots and shared reports.

## Prospective change history

Read `/api/v1/day-notes/{date}/history?limit=50` with the same separate,
expiring read-only token. Responses use `Cache-Control: private, no-store`.
The endpoint covers both independent resources for that date; a history
sequence is diagnostic ordering, not a shared label/note write version.

```sh
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/day-notes/2026-10-27/history?limit=50"
```

`current` contains the label, independent note/label versions, body-presence and
note-deletion flags, and each available latest modification time. It does not
contain the Markdown body. Each history item identifies the actual resource,
operation, source interface, versions before/after, previous/new label,
changed-field names, time and server build identity. `hadBody` and `hasBody`
are booleans; neither body bytes nor attachments are copied into history.
An available `previousModifiedAt` is the changed resource's previously stored
modification time, not a reconstructed revision.

A body clear, note delete, WebDAV replacement or recreation leaves the label
unchanged. Such note events therefore have equal previous/new labels and an
unchanged label version. Label clears have resource `LABEL`; they preserve the
note version and body-presence state. No-op saves produce no event. Validation,
stale-version failures, failed imports and archive previews roll back their
history with the data. A combined editor save commits its resource changes and
history atomically; its separate label/note events can share a timestamp.

Sources distinguish legacy REST, independent REST labels/notes, the shared
editor command, WebDAV, archive restoration and internal operations. Client
metadata is deliberately limited to authentication kind and an available public
API-token ID. Browser password/passkey sessions do not expose a session ID or
claim a durable device identity. Token values, digests, parent/session hashes,
CSRF secrets, request headers and arbitrary user-agent strings are excluded.
The build identity records the writing server, not whichever server reads the
history later. Keep previous labels and client IDs private when sharing evidence.

## Pagination and gaps

The default page size is 50; the maximum is 100. Follow `nextCursor` using the
same date and owner. Cursors are signed and bound to the owner, date and account
revision. Any intervening account change expires pagination with
`409 CURSOR_EXPIRED`: start again from the first page. A cursor cannot be moved
to another account/date or edited to read foreign history. Reads require no
write permission and do not create history.

The latest 1,000 events per account/date are retained across note deletion and
recreation. `historyVersion` is the monotonically increasing event sequence;
`retentionTruncated` means an earlier prefix was discarded. `recordingStartedAt`
identifies when local recording began, even if its earliest events have since
aged out of the count limit. `legacyGap` identifies state predating recording,
including restored state whose source evidence is kept separately from the local trail.
An untouched date has no events and a null recording start. Existing dates
without history expose their actual current state and available modification
time without inventing an earlier author, operation or revision.

These are prospective diagnostics. They cannot establish the cause of a label
loss that happened before recording, or recover values already absent from all
retained history and backups. Compare a pre-update backup in an isolated server
as described above; preserve manually corrected live data.

## Backup and account portability

Database backups preserve the local history records, original event identities
and sequences. Personal account ZIPs include diagnostic entries in the existing
`importHistory` collection, using `ARCHIVE_AUDIT` with `data.kind` equal to
`DAY_NOTE_HISTORY` and the original record in `data.source`. Import preserves those entries as
inert `ARCHIVE_AUDIT` copies with source-archive provenance. The receiving server
does not accept supplied sequences, client claims or timestamps as locally
authenticated changes. Its history endpoint reports new local `RESTORE` events
for the actual imported labels/notes, including the authenticated importer.
Source evidence remains available in subsequent account ZIPs. This preserves
portability without allowing a supplied archive to forge the local audit trail.

The schema-8 compatibility gate requires an explicitly authorized, coordinated
upgrade with older server replicas stopped. It prevents an older server from
performing unrecorded writes after recording is enabled. It does not rewrite
existing labels, note bodies, canonical Markdown or resource versions and does
not manufacture historical events.
