# Timekeeper HTTP API v1

This contract implements the design part of #217 and is the authority for the
Quarkus server (#218), remote Eclipse adapter (#220), web frontend (#221) and
change synchronization (#219). All five are required before Timekeeper 2.0.
The embedded implementation remains available and its database is not implicitly
copied, merged or opened by the server.

[openapi.json](../../common/api/openapi.json) describes `/api/v1` using
[OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.1.html). API version 1 is
independent of the Timekeeper product version. The server must serve this
contract at `/api/v1/openapi.json`; publishing the contract does not certify an
implemented server. HTTP behavior follows
[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html), bearer authentication
[RFC 6750](https://www.rfc-editor.org/rfc/rfc6750.html), and error bodies
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html).

## Identity and authorization

A bearer token maps to one immutable server owner and read/write permissions.
Multiple tokens/devices may belong to the same owner; rotating a token does not
change resource ownership. A valid read-only token can query its owner's data
but mutations return 403. Missing, invalid, expired or revoked tokens return
401 with `WWW-Authenticate: Bearer`. No owner is selected from a query, body,
external provider identity, Eclipse workspace or token text supplied as data.
The `/auth` endpoints provide account management, web sign-in and personal API
tokens; see [account operations](../operations/ACCOUNTS.md). There is no cross-owner
sharing endpoint. Administrator roles never grant another owner’s business data. TLS is required outside loopback development.
New personal API tokens are returned once by their creation endpoint; token
secrets never appear in resource DTOs, URLs, logs, errors or change notifications.
Web requests may instead use the HttpOnly Secure cookie; unsafe cookie requests
require the memory-only CSRF value returned by `/auth/session`.

Stateful clients bind their session to the first authenticated owner. Authorized
responses include `X-Timekeeper-Owner`, the canonical unpadded base64url encoding
of that owner's UTF-8 ID, including empty pages and 204 responses. Send that
value on subsequent requests. The header is an expected-owner restriction and
cannot grant access to a different owner. Malformed, duplicate or noncanonical
values return 400. A valid token mapped to a different owner returns 401
`OWNER_CHANGED` before reading resources or executing a mutation.

Omitting the header retains stateless token-owner behavior for existing API
consumers. Do not reassign issued tokens to other owners. The binding protects
existing sessions if registry configuration nevertheless changes. An existing
client retains its original binding through failures; restoring that owner can
recover the session, while an intentional account switch creates a new session.
The Java HTTP client learns the binding from the response header or resource/
profile `ownerId`, including a first empty page, and never silently replaces it.
Long polling also rechecks ownership while a request is waiting.

Every project, task, label, activity, external reference, profile, cursor,
command receipt and change event belongs to exactly one authenticated owner.
Ownership is immutable. All repository methods (including relation and external
reference lookup, existence checks, lists, reports and deletes) must operate
within that owner scope. Scope is established by the authenticated server
adapter before invoking `DefaultTimekeeperService`, using owner-bound repository
ports. The adapter supplies the authenticated owner to activity commands and
queries; the domain/application services remain independent of HTTP, Quarkus,
Eclipse and Mylyn. Server persistence enforces owner-scoped relations and
external-reference uniqueness, version checks and the single-open-interval rule
within the transaction. Merely checking an activity's `ownerId` at serialization
or filtering a shared repository after a service operation is insufficient.

A foreign resource or foreign relation is indistinguishable from a missing one:
404 `NOT_FOUND`, with no resource contents, versions or owner information.
Foreign filters also return 404. Lists, totals, provider lookups and exported
snapshots cannot reveal other owners. A portable import cannot assign an owner.
External identity `(providerId, repositoryId, externalId)` is unique **per owner**;
two owners may independently track the same Jira issue with different native
UUIDs. Linked tasks preserve their UUID/history when the provider is unavailable
or the reference is removed. `local` remains an embedded-only default and is
never a shared remote authentication fallback.

## Resources and client workflows

| Workflow | Operations |
| --- | --- |
| Projects | List/get, create, replace name, delete unused project |
| Tasks/subtasks | List/get/filter, create, replace summary/URL/project/parent, delete unused task |
| Provider integration | Find by provider identity, link/update/unlink reference with task version |
| Labels | List/get, create, replace name/color, delete unused label |
| Manual time | Create/get/edit/delete an interval with summary and assigned labels |
| Tracking | Read current interval, atomically activate/switch task, stop interval |
| Daily/weekly reports | Query intervals and authoritative daily/task totals in an explicit zone |
| Portable interchange | Export domain snapshot; atomic import with fresh UUIDs and relation remapping |
| Preferences | Read owner profile, explicitly set reporting zone |
| Concurrent clients | Owner-scoped committed change feed, resource reload, ETag conflict resolution |

Requests use immutable input values; responses are immutable snapshots with
UUIDs, owner IDs and versions, never persistence entities or lazy relationships.
PUT replaces all editable fields. Nullable relations/end/color/URL must be
explicitly present with null when unset. Omitted required fields and unknown
input properties return 400; clients cannot set IDs, owners, versions or manual
flags through normal CRUD. Input length/range/format failures return 422.
Names are trimmed and must contain a non-whitespace character. Optional URLs
are absolute URIs; labels use null or `#RRGGBB`. Unknown query parameters return
400 rather than accidentally widening a query. Invalid JSON and malformed
headers/UUID path parameters are 400. Unsupported media types are 415. Request bodies over 20 MiB return 413 before
JSON processing; transfer limits also apply to exported snapshots. Successful
JSON is `application/json`; errors are `application/problem+json` with matching
HTTP/body status and stable `code`. Clients branch on status/code, not prose.

Tasks may be unassigned, but a subtask must have the same non-null project as its
parent. Self-parenting, cycles, and moving a task with children to another project
are conflicts (409). Deleting a project containing tasks, a task with children
or activities, or a label assigned to activities returns 409; no implicit cascade
loses recorded time. All referenced projects/tasks/labels must be owned.
External references may be included atomically at task creation (the optional
`externalReferences` defaults to an empty list) and are subsequently updated
separately from the editable task fields. A reference conflict at creation must
not leave an orphan task. After an ambiguous provider-linked create, reconcile
through the external-reference lookup instead of creating a duplicate.

Task planning is a separate conditional `PUT /tasks/{id}/planning` resource.
Its `status` is `NOT_STARTED`, `IN_PROGRESS`, `COMPLETED`, `WAITING`, or
`DEFERRED`; `scheduledDate` is a date-only civil day, and `completedAt` is a UTC
instant or null. The request supplies all three fields, and `completedAt` is
only valid with `COMPLETED`. A missing planning field on task creation uses
`NOT_STARTED`/null defaults. Existing `PUT /tasks/{id}` clients update the
legacy fields while preserving task planning. The scheduled day never implies
a midnight deadline, reminder, or time zone conversion. Further provider task
metadata and synchronization behavior are tracked in #413.

Task checklist items are distinct from child tasks linked by `parentTaskId`.
Each item has a stable Timekeeper UUID, text, completion state, optional
completion instant, and zero-based position matching its array order. Clients
may include `checklistItems` when creating a task. A conditional
`PUT /tasks/{id}/checklist` with `{ "items": [...] }` replaces only that list;
an empty list clears it. IDs and positions must be unique, positions must be
contiguous, and a completion instant requires `completed: true`. The ordinary
task and planning updates preserve checklist items they do not understand.

Task start and due values are separate from `scheduledDate`. A conditional
`PUT /tasks/{id}/timing` supplies both `start` and `due`; `null` clears one.
`{ "kind": "DATE", "date": "2026-03-29" }` is a civil day without a time
or time zone. A `DATE_TIME` also carries `time` and the original `timeZone`
identifier, which may use IANA or Windows naming. Timekeeper preserves that
wall time and identifier, including at DST gaps and overlaps, without deriving
an instant or converting it to midnight. Neither form creates a reminder.
Omitting timing fields on task creation uses null defaults. Ordinary task,
planning, and checklist updates preserve existing timing.

Task importance has separate `priority`, `flagged`, and `urgent` fields.
`priority` is `UNSPECIFIED`, `LOW`, `NORMAL`, or `HIGH`; an explicit `NORMAL`
is distinct from no priority. A conditional `PUT /tasks/{id}/importance`
supplies all three fields and changes no other task data. A provider connector
must retain unsupported flags in Timekeeper without inventing a corresponding
provider value. Legacy task, planning, checklist, and timing updates preserve
importance.

Task notes use a separate conditional `PUT /tasks/{id}/body` with a complete
`{ "format": "TEXT" | "HTML", "content": "..." }` value. Empty plain text clears
notes. The body is optional on creation and defaults to empty text. Older task
updates and the other task section endpoints preserve it. HTML is untrusted
source content; clients must sanitize it before rendering.

External task references have optional `source` metadata: `createdAt`,
`modifiedAt`, `remoteEtag`, and `remoteRevision`. The timestamps are provider
instants, and the version strings are opaque. A conditional
`PUT /tasks/{id}/external-references/source` addresses one existing reference by
provider, repository, and external ID and replaces its complete source state.
Explicit null clears a value. Older link calls omit `source` and preserve state
for an existing identity. The remote ETag is independent of the Timekeeper
`If-Match` value.

Each reference may also carry a versioned `baseline` containing `capturedAt`
and the normalized `fields` last agreed with that provider. The field map is
sparse: absence means unsupported or unknown, not a request to erase a value.
After a successful provider exchange, use conditional `PUT
/tasks/{id}/external-references/sync-state` to replace `source` and `baseline`
in one task write. A null baseline clears it. Keep credentials and transport
state out of the field map. Ordinary task edits and legacy link updates retain
the baseline; identity replacement inherits it until a new exchange completes.
The snapshot is the comparison base for a later field-level three-way merge.

An explicit local deletion of a task with external identities writes a durable,
owner-scoped tombstone with the deleted task ID, deletion time, prior task
version, and all active and alias
provider identities. `GET /task-tombstones` is paginated;
`GET /task-tombstones/by-external-reference` resolves a former identity. A
tombstone means Timekeeper deleted its local task. It does not mean the provider
deleted its remote item, and a temporary missing provider response must never
be treated as this deletion. The task's `DELETED` change and the tombstone's
`CREATED` change are emitted in the same transaction for retrying connectors.
Tombstones are included in portable transfer and private account archives.
Their record IDs are remapped when imported into another owner, while the
historical deleted task ID remains visible as `taskId`. Older transfers without
the collection still import.

`PUT /tasks/{id}/external-references/replace` atomically changes one current
provider identity using `previous` (provider, repository, external ID) and a
complete `current` reference. Both identities must belong to the same provider.
The old key becomes an `externalAliases` lookup key, so a provider rescan that
changes a remote ID can still resolve the same Timekeeper task. Alias keys are
unique per owner and survive ordinary task edits, archive, and portable transfer.
The new link inherits source, list association, provider data, and baseline when omitted.
Ordinary unlink forgets its current identity and does not create an alias.

Provider accounts and lists have owner-scoped `/task-connections` and
`/task-lists` collections. A connection holds a provider and remote account
identity, display name, access flags, and enabled state; no OAuth or Apple
credentials are stored in these resources. A list holds a stable Timekeeper ID,
remote list ID, connection ID, name, `STANDARD`/`BUILT_IN`/`SMART` kind,
read/write and sharing/ownership flags, presence, and order. Lists are separate
from Timekeeper projects. Repeating an identical create returns the existing
resource; a conflicting representation of the same remote identity returns
409. Updates require `If-Match`. Mark a missing list `present=false` or a
disconnected account `enabled=false`; remote disappearance must not trigger
deletion of local tasks.

A provider list may include `sharing` with a remote owner ID and disclosed
participants and roles. This context is distinct from the list's `shared`,
`owner`, `readable`, and `writable` flags and does not grant Timekeeper access.
Providers may expose only the flags, leaving participant details empty.
`PUT /task-lists/{id}/sharing` replaces the context with `If-Match`; ordinary
list updates preserve it. Portable JSON/CSV and account archives retain it.

`/task-list-sections` stores optional, owner-scoped provider groupings inside
one task list. A section has a stable Timekeeper ID, remote section ID, name,
`STANDARD` or `GROCERY_CATEGORY` kind, order, presence, and section write
permission. Discovery is idempotent by list and remote ID. Missing sections
can be marked `present=false`; a list with sections cannot be deleted. These
rows do not create Timekeeper projects or imply that Google Tasks or Microsoft
To Do expose Apple section behavior. An external task reference can point to
a section only when that section belongs to its provider list.
Sections are included in portable JSON/CSV transfer and private account
archives. Their list IDs are remapped with the owned provider list; older
transfers without sections remain accepted.

Each connection also has a versioned `capabilities` declaration. Connectors
replace it conditionally through `PUT /task-connections/{id}/capabilities`.
Entries state whether a field can be read, written, and deleted; an omitted
field means unknown. Ordinary connection updates preserve the declaration.
The initial declaration has no field claims. See
[task sync capabilities](TASK-SYNC-CAPABILITIES.md) for the version 1 provider
mapping and export gate.

`/task-sync-cursors` holds durable, owner-scoped connector progress separately
from task payloads. A connection-level cursor tracks list discovery; a
list-level cursor tracks tasks in that owned list. `INITIAL` has no cursor,
`INCREMENTAL` has an opaque provider cursor or delta URL, and
`RESCAN_REQUIRED` deliberately clears it. Creates are idempotent for an
identical connection/list state; updates and deletes require `If-Match`.
Disabling a connection or marking a list absent retains cursor state and
tasks. Cursors are excluded from portable transfers and account archives:
after restoration, a connector must perform a full scan before interpreting
remote absence as deletion. The opaque cursor is never a Timekeeper version.

An external task reference can carry `taskListId` to point at an owned provider
list. The list's connection must use the same provider. The existing
`repositoryId` remains the provider identity namespace and must distinguish
accounts and lists when remote item IDs are only unique within a list. A legacy
link update that omits `taskListId` preserves the association. Portable import
remaps list IDs with the rest of the owned graph; deleting a referenced list is
rejected. The Timekeeper project remains independent of this association.
`sectionId` optionally identifies a section inside that list. Legacy link
updates preserve it when the list stays the same; moving a link to another list
clears the old section. `PUT /tasks/{id}/external-references/location` sets or
clears both IDs with `If-Match` and explicit nulls. Portable imports remap both
IDs, and deleting a referenced section is rejected.

An external reference may also carry `assignment` with an assignee, assigner,
and read-only provider source context. `sourceUrl` is separate from the task's
editable `url` and from linked resources. `writable=false` records that a
connector cannot edit the assignment even when the task itself is writable.
Legacy link updates preserve assignment data; `PUT
/tasks/{id}/external-references/assignment` conditionally replaces it or
clears it with explicit `null`. It survives account archives and portable
transfer without storing connector credentials.

An external reference can also hold an optional `providerData` envelope with
`namespace`, positive schema `version`, and an opaque JSON object `payload`.
The namespace is the provider ID or a dot-prefixed extension of it. The server
retains unknown payload fields through reads, unrelated and legacy writes,
portable transfers, and account archives. `PUT
/tasks/{id}/external-references/provider-data` conditionally replaces the
whole envelope for one linked identity; explicit `null` clears it. Connectors
must store only fields they can actually read, must keep their own OAuth or
Apple authorization material outside task data, and must not write an
approximation of an unsupported field to another provider.

Tasks have a nonnegative `siblingOrder` for local ordering among tasks with
the same parent. `PUT /tasks/{id}/order` changes only this value with `If-Match`;
legacy task and other section writes preserve it. Provider ordering tokens can
remain opaque in provider source state and are not inferred from this number.

Task `recurrence` separates an RFC 5545 rule from its `NEVER`, `UNTIL`, or
`COUNT` end condition. `UNTIL` uses the same date-only or zoned wall-time
representation as other task moments; connectors must convert it according to
their provider's rules. The `occurrences` array stores completed, skipped, or
moved exceptions keyed by each original generated slot. `PUT
/tasks/{id}/recurrence` replaces the entire series and its exceptions; `PUT
/tasks/{id}/occurrences` replaces exceptions without changing the series.
Both require `If-Match`. An empty series clears all exceptions, while older
task writes preserve them. The server stores these values but does not expand
rules or infer new due dates or alerts. A connector must report unsupported
recurrence before writing to a provider rather than approximate its rule.

Task `reminders` is a bounded list of individually identified triggers,
independent of scheduled, start, and due dates. `ABSOLUTE` requires a zoned
wall time; `RELATIVE` uses signed minutes before a `START` or `DUE` anchor
(negative means after). `LOCATION` stores a geofence and arrival/departure
transition; `CAR` stores connect/disconnect; `MESSAGE` stores an opaque contact
key. `PUT /tasks/{id}/reminders` conditionally replaces the whole list and
legacy task writes preserve it. Connectors must retain unsupported trigger
kinds locally and warn on attempted export; they must never convert them to a
timed alert or treat a due date as an alert. Apple companion authorization and
contact resolution remain outside task payloads.

Task `metadata` stores `tags`, `categories`, and `links` separately. Tags can
represent Apple Reminders tags; categories can represent Outlook categories.
Links have stable IDs and `LOCAL`, `PROVIDER_SOURCE`, or `PROVIDER_LINKED` kinds.
Provider source links, such as Google Tasks output-only links, remain separate
from the editable primary task `url`. Provider links carry a provider ID and
linked resources can carry a remote ID. `PUT /tasks/{id}/metadata` conditionally
replaces the full metadata value. Legacy task writes retain it, and connectors
must use their declared field capabilities before exporting any change.

An interval end must be at or after its start. A manual interval may be open,
but each owner has at most one open interval across all tasks and clients.
Manual editing sets `manual=true` and cannot change activity task/owner. Creating,
editing or importing an interval whose start is after the backend's clock returns
422 `VALIDATION`, whether the interval is open or closed. A start exactly at the
current instant is allowed. Invalid writes leave records and revisions unchanged. Automatic
start/stop always uses the server clock. Clients do not submit their clocks for
live tracking.

Activities are stored as separate records for each local calendar day in the
owner's configured time zone (the machine's calendar zone for local Eclipse
storage). Ongoing tracking closes the previous day's record and continues at
midnight, retaining the active UUID on the newest record. Startup catches up
missed days. Both manual writes and imports use the same day boundaries.
Each part retains task, owner, summary, labels and the original manual flag;
manual edits still mark their resulting parts as manual. Exact instants and
nanoseconds are preserved, including 23/25-hour DST days. Splits occur only at
boundaries that have elapsed; a permitted future end is split further as those
days arrive. An end exactly at midnight creates no empty completed record.

Create/update responses and Location/ETag identify the last resulting day;
earlier parts appear as additional activities and change events. Each daily
record can be edited or deleted independently. A pre-midnight editor has a stale
version after rollover and must reload. Changing the reporting zone can split
existing records at additional boundaries; it never joins independently edited
records. Day maintenance and all parts of a write commit atomically under the
same per-owner lock, including across server replicas.

Portable interchange exports `timekeeper-domain-v3` JSON, including clients, mandatory
project/session client links, session summaries and optional task, direct-project
assignments. Import also accepts older `timekeeper-domain-v1` and
`timekeeper-domain-v2` snapshots, assigning their projects/sessions to General.
The formats contain no SQL/JPA table names. CSV v3 includes client/summary
columns and writes `taskConnections.csv` and `taskLists.csv` together when
provider collections are present. The descriptor marks these files as required,
so deleting both cannot silently discard them. Earlier v3 CSV exports without
either file remain readable; an export with only one provider file is invalid. v2 includes
`workSessions.csv`. Every declared file must be present. Older CSV clients
reject the new format instead of silently discarding independent sessions or
client metadata.
Portable task CSV adds a `taskExtras` JSON object column when tasks contain
planning, timing, importance, notes, or sibling order. This preserves optional
fields, including absent fields in mixed older and newer rows. Its descriptor
marks the column as required, so an incomplete export is rejected.
Export reads a consistent owned snapshot. Import validates the entire graph,
then generates fresh UUIDs and rewrites references in one transaction; it never
updates existing records. Source IDs are correlation keys only. A failure leaves
no partial records, command receipt or events. External identity collisions are
409; invalid graphs are 422. Open imported intervals must respect the existing
owner's active interval. Import does not implicitly change the configured zone.
The result maps every source ID to its new ID. Transfer limits are 10,000 records
per type and 20 MiB; oversized import/export returns 413 without partial data.
Eclipse CSV and report-template adapters translate domain snapshots locally;
server API operations must not expose or write embedded persistence tables.
This is current-model interchange, not historical migration or database backup.

## Versions and atomic tracking

GET and successful create/update/command responses include a strong opaque
ETag for the canonical snapshot representation, alongside its nonnegative JSON
version. Echo the received tag; do not construct it from the JSON version.
Serialize set-valued references/label IDs deterministically. A tag must change
when either stored state or its wire representation changes (including an API
representation revision). PUT, DELETE,
reference changes, profile updates and stop require exactly one strong If-Match.
A missing header returns 428 `PRECONDITION_REQUIRED`; weak, wildcard, list,
empty or malformed tags return 400. An owned stale version returns 412
`STALE_VERSION` without changes. Authorization precedes version checks so a
foreign stale write still returns 404. Versions are safe JSON integers, never
floating-point counters. Each successful change increments its resource version;
delete checks the current version too. A database compare-and-set is required;
a read/check followed by an unconditional write is not sufficient.

Start requires `expectedActiveId` and `expectedActiveVersion`: both null when
there was no open interval, otherwise the ID/version last observed by the client.
The server compares and changes active state under an owner-level lock within
one transaction, including the empty-state race. A mismatch returns 409
`ACTIVE_STATE_CHANGED` and changes nothing. When the current interval already
belongs to the requested task, return it unchanged with 200. Otherwise close the
old interval and create the new one with the same server-clock instant, returning
201. Relation validation, stop, creation, command receipt and notifications all
commit or roll back together. Different owners do not share an activation lock.
Stop uses the server clock and If-Match; stopping an already ended interval with
its current version returns it unchanged with 200.

Start, stop and import require an `Idempotency-Key` UUID. The namespace is per
owner across these operations. Persist the parsed request fingerprint (method,
canonical path, normalized JSON and original If-Match) and successful HTTP
status/body/ETag/Location with the changes in the same transaction. Authenticated
same-owner retries with the same fingerprint return **the original response**
without another change/event, including after restart and when the original
If-Match or observed active state has become stale. Reusing a key for different
input returns 409 `IDEMPOTENCY_KEY_REUSED` without changes. Normalize JSON object
key order and insignificant JSON whitespace; arrays retain order and scalar
values remain exact. A changed scalar or array is different input even if a
domain normalization would ultimately produce the same stored value. Validate the complete request before executing;
failed requests leave no receipt and can be retried after correction. Concurrent
identical requests must share one committed result; an in-memory cache alone is
insufficient. Authorization is rechecked on every retry, including permission
revocation. Receipts survive resource deletion and are retained at least seven
days from successful commit. Clients must not retry an uncertain operation after
that window without reconciling current server state. Canceling an HTTP request
does not prove rollback; retry with the same key after a timeout/503.

The replayed snapshot may be older than the current resource. Clients use a
fresh GET/change feed before further edits, and never replace unsaved fields
merely because a delayed command response arrives. Standard CRUD POSTs are not
implicitly replayable; reconcile ambiguous creates by reloading instead of
blindly creating another record. Import provides durable retries for bulk data.

## Work sessions

`/work-sessions` supports owned listing and manual creation; individual records
support read, versioned edit and deletion. `/work-sessions/active` returns the
running session or 204. `/work-sessions/start` and `/{id}/stop` use durable
idempotency keys, and stop also requires the original ETag. Sessions can span
midnight and never start or stop activity timers implicitly. The owner lock
allows only one running session per owner, including across replicas. Several
completed sessions may belong to the same day.

Activities may omit their task and optionally name a direct project. They are
independent of sessions. Moving, resizing, stopping or deleting a session never
changes activities or task timers; activities may exist outside every session.

`/work-sessions/{id}/report` derives context from that owner's overlapping
activities, including running timers, in one consistent snapshot. Tracked time
sums each interval clipped to the session; allocated time is the union of those
portions. Unallocated time subtracts that union from session duration, preserving
nanoseconds without counting overlaps twice. Boundary-crossing activities are
identified; activities merely touching an endpoint do not overlap.

Compatibility: optional activity `workSessionId` values in create/start and
v1/v2/v3 imports are accepted but ignored, even when that session is absent.
Exports and activity responses omit the retired field. The deprecated
`PUT /activities/{id}/work-session` rejects non-null attachment requests with 409;
`null` is an authorized, version-checked detach/no-op. It cannot create a link.
Server startup removes only legacy link metadata under each owner's database
lock, retaining resource IDs, versions, timestamps, task/project/label references
and active timers. The canonical SQL schema is unchanged. Reads ignore stale
link metadata and session deletion tolerates links written by older replicas;
upgrade all replicas together to obtain consistent temporal-report semantics.

## Queries, time zones and refresh

Collection pages use `limit` (default 100, maximum 500) and an opaque cursor.
Projects/tasks/labels sort by UUID; activities sort by start then UUID. A cursor
binds the authenticated owner, normalized filters, last key and owner revision.
Malformed, foreign or mismatched-filter cursors return 400. A committed owner
change during pagination returns 409 `CURSOR_EXPIRED`, requiring a new traversal;
there are no silent duplicate/omitted records across revisions. Each page returns
`items`, `nextCursor` (null when finished) and `revision`. A substring `q` filter
matches project/label name or task summary after trimming, case-insensitively.
Task `unassigned=true` cannot be combined with a project filter. Filters combine
with AND. Activity label filtering matches membership of that label.

All wire/persistence activity times are UTC RFC 3339 instants ending in `Z`,
with at most nine fractional digits. Local dates/times are never timestamps.
A profile stores an IANA zone ID (default UTC at provisioning); clients may
explicitly configure it. Remote Eclipse and web reporting use this profile,
rather than silently adopting different host zones. Report override `zoneId`
is explicit and does not change the profile. Unknown zones return 422.

Ranges are `[from,to)`, with to strictly after from. Queries include intervals
that overlap the range and keep original timestamps in returned snapshots.
Reports clip to the range and split durations by actual local midnight instants;
DST days may have 23 or 25 hours. A week starts Monday. Open intervals use one
returned `generatedAt` instant for the whole report. Totals sum recorded durations
(including manual overlaps) with nanosecond precision, serialized as ISO 8601
duration strings, not rounded decimal hours. Pagination never truncates totals.

Each committed change receives an increasing owner-local sequence, persisted
atomically with the data. Collection/report revision is the current sequence.
`GET /changes?after=<sequence>&waitSeconds=25` provides long polling; duplicate
notifications are harmless and sequence gaps are allowed. Events contain only
entity type, change type, owned ID, sequence and timestamp. At least seven days
are retained; an expired/ahead sequence returns 409 `RESYNC_REQUIRED`. Empty
responses keep `nextSequence=after`; process items before advancing the cursor.
A replay/no-op emits no event. Clients refresh affected resources after events,
preserve unsaved edits, and resolve a later stale save through If-Match. Reconnect
resumes from the last processed sequence. Full resynchronization starts at the
current revision, loads consistent pages (restarting on CURSOR_EXPIRED), then
catches up changes since that revision. The version check remains authoritative
when notifications race or a resource was deleted. Offline edits stay pending
and must not silently create an embedded fork or overwrite remote changes.

## Calendar subscriptions and event conversion

API contract 1.1.0 adds owned `/calendar-subscriptions` CRUD, a write-authorized
manual refresh, `/calendar-events?fromDate=…&toDate=…` and
`POST /calendar-subscriptions/{id}/events/{key}/activity`. Date windows are civil
account dates with an exclusive end and at most 31 days. Configuration changes
use ETags; creation and conversion require an `Idempotency-Key`. Subscription
representations include refresh outcomes and a safe origin hostname, never the
credential-bearing feed URL. Leave `url` blank/absent on an update to preserve it.

Events have stable UID/recurrence keys and an `eventVersion` fingerprint covering
the displayed source interval/details and client association. Conversion supplies
that version, the original view window and a reviewed `ActivityInput`. The server
resolves the current cached occurrence again. A changed/cancelled occurrence
returns `409 CALENDAR_EVENT_CHANGED`; a second conversion command returns
`409 CALENDAR_ALREADY_CONVERTED`. A future activity start returns
`422 CALENDAR_FUTURE_ACTIVITY`. All-day events require an explicitly reviewed,
completed interval; zero-duration timed events remain valid markers.

Private `CAL_SUB`, `CAL_FEED` and `CAL_LINK` records use existing owner-scoped
storage. Network I/O runs outside owner transactions; refresh claims and final
publication are fenced by expiring database leases and source configuration.
Activity creation, daily fragments, permanent occurrence identity and command
receipt commit under the same owner lock. Conversion authority outlives deleted
subscriptions/activities; source changes do not mutate recorded work. Activity
fragments inherit their source history. Account export wraps this history in the
existing opaque `ARCHIVE_AUDIT` category; imports retain only inert audit copies,
without subscription credentials, cached feeds or conversion authority. The
account archive and domain transfer formats remain unchanged.

Subscription/cache changes use a separate read-only refresh in the Calendar view,
including periodic reads while visible; they do not emit invented client changes
or unknown domain change types. Creating an activity emits normal ACTIVITY changes.

## Compatibility and verification

Within `/api/v1`, preserve paths, methods, required inputs, existing fields,
meanings, status/code behavior, time precision and ownership rules. Additive
optional response properties and endpoints are allowed; clients ignore unknown
response properties and handle unknown problem codes via HTTP status. Inputs
remain strict; new optional input fields require clients to check server support
before sending them to an older server. New required fields, removals, type or
semantic changes require `/api/v2`, with a documented deprecation period. The
OpenAPI document's minor/patch version tracks compatible edits. Domain transfer
format versioning is separate; changing its closed schema requires a new format.

Run static specification/schema tests:

```sh
python3 -m venv /tmp/timekeeper-contract-venv
/tmp/timekeeper-contract-venv/bin/pip install -r common/api/tests/requirements.txt
/tmp/timekeeper-contract-venv/bin/python -m unittest discover -s common/api/tests -v
```

[http_acceptance.py](../../common/api/tests/http_acceptance.py) is the reusable black-box server
suite. It performs writes and requires an **isolated test server/database** with
two owners and a read-only token for the first owner. Supply fixture tokens via
`TIMEKEEPER_TEST_TOKEN_A`, `TIMEKEEPER_TEST_TOKEN_B` and
`TIMEKEEPER_TEST_TOKEN_READ_ONLY`; never use real accounts/data. Run:

```sh
python common/api/tests/http_acceptance.py --base-url http://127.0.0.1:8080/api/v1
```

The suite checks actual HTTP authentication/authorization, cross-owner relations,
stale writes, atomic switching, duplicate commands, provider identities, DST
reports, pagination, imports and owner-scoped notifications. Static schema tests
do not prove server enforcement. #218 must run this suite against the real
Quarkus server/database and separately verify restart/durable receipts, concurrent
requests, schema guards and operational configuration. #220/#221/#219 add actual
client behavior, unsaved-edit and reconnect acceptance before the parent closes.

## Clients in the server and web client

`/clients` provides owned, paginated CRUD for a name and required `#RRGGBB` color,
using the same authorization, opaque ETags and change feed as projects. A project
has exactly one `clientId`, and a client can own many projects. An optional
`icon` selects a bundled project symbol; omitted icon fields preserve the current
value on edits and explicit null clears it. The published `ProjectIcon` enum
limits values to known identifiers. Icons remain server metadata, survive legacy
Eclipse writes, and round-trip through account archives and domain JSON/CSV
transfers. Work sessions have exactly one `clientId` and an optional `summary`.
An optional, owned `projectId` derives the session client from the project.
A conflicting explicit client is rejected. Omitted projects are preserved on
edits; explicit null removes the project. Project filters are available on
`/work-sessions`. A referenced project cannot be deleted or moved to another
client until its sessions have been reassigned, preserving historical totals.
`/projects` and `/work-sessions` accept a `clientId` list filter. A referenced
client must belong to the authenticated owner. Deleting a referenced client is
409, with the whole transaction rolled back.

Client associations are server metadata stored in the canonical resource payload
and owned foreign-key relations. The common interval/project models and Eclipse
persistence/UI do not need client fields. Web writes supply clientId and an
optional summary (up to 4,000 characters). Omitted clients use General on creation
and preserve the existing client on edits; explicit null clients are rejected.
The separate title field is retired. Startup moves existing titles into summary,
preserving distinct notes and avoiding duplicate prefixes. Legacy title inputs
and archives remain accepted and are folded into summary; new responses omit title.
A server stop preserves the summary and client. Each mutation commits metadata
and its domain changes under one
owner lock, version and change event, and responses/ETags cover the complete
stored representation.

Startup backfills existing records for all stored owners, including owners no
longer in the token file. The backfill creates one owned General client, preserves
record IDs and exact intervals, increments changed resource versions and emits
change events. Repeated startup is idempotent. The generic SQL table schema is
unchanged. Back up the server data before upgrading, and upgrade all replicas
together to avoid older binaries dropping the new metadata.

## Toggl Track imports and Undo

The `/imports` endpoints accept normalized completed Detailed Report entries,
with explicit source-zone conversion and reviewed catalog mappings. CSV parsing
is separate from server authority. Preview writes no import or business records;
creating a job stores its immutable mappings, file/source fingerprints, account
report zone and selected count/time. Only authenticated ownership determines the
destination account. Source member/workspace identity never creates accounts or
shared workspaces. Work sessions are not inferred.

Commit sequential batches of at most 100 entries and 1,000 resulting local-day
activities. Each batch atomically commits domain changes, all daily IDs,
provenance, catalog decisions and its durable outcome. Repeating the same job,
index and input returns the original outcome; conflicting input is 409. After
an uncertain reply, GET the job before continuing: a cached historical outcome
can predate Undo. Completed jobs reconcile exact imported plus skipped source
nanoseconds and selected entry counts. Changing the account reporting zone
requires a new review/job; the importer does not change that preference.

Source IDs take priority over fingerprints of exact UTC instants, original
client/project/task names, descriptions and sorted tags. Filename, header variant
and chosen destination IDs are excluded. Later exports reuse the original active
claim, report edits without overwriting them, and retain the original creator.
Unclaimed exact matches require explicit distinct-create or version-checked
reuse. Indistinguishable source rows use reviewed occurrence numbers.

Whole-job Undo first fences new import batches, including a partially completed
job. Subsequent previews and commits each handle at most 100 records. Unchanged
created activities can be removed; edited records require explicit KEEP or
DELETE. Running timers, references from another active import and catalog
dependencies require keeping the record or resolving the blocker first. Reused
records are preserved. Children commit before their catalog parents are assessed.
Every commit rechecks its preview token under the owner lock, with durable
receipts and cursors for restart or lost replies. Automatic daily repartitioning
tracks all derived IDs; manual edits stay visible for review. Missing records are
reconciled without deleting anything else, and removing all original parts
releases only that original source claim, allowing deliberate re-import.

Private jobs, receipts, audit items, mapping plans and lineage use internal owned
resource types; public domain collections and portable exports omit them.
Protected database backups retain this authority. No database schema change is
required. Domain writes and deletions emit the normal owner-scoped change feed.

## Central service configuration

`/api/v1/admin/settings` returns redacted saved/effective configuration and an
ETag. PUT requires an actual administrator account session, CSRF and current
password proof; token-derived sessions cannot save. Shared desired settings take
effect at coordinated startup, with a monotonically newer origin epoch and a
local-version fence for running replicas. Schema 5 stores the global singleton
separately from owned work data; SMTP secrets are never part of API reads or
personal portability archives. HostPolicy applies the exact effective hostname
and loopback to every HTTP route, including static resources and health checks,
without forwarded-host trust. The framework localhost default is replaced by
this dynamic allowlist, rather than pinning the hostname in generated files.


### Work-session Undo

Clients may opt into Undo on conditional work-session PUT/DELETE requests with
`X-Timekeeper-Undo: true`. Successful responses include `X-Timekeeper-Undo-Id`
and `X-Timekeeper-Undo-Seconds` (600). Existing clients remain unchanged.
`POST /undo/{id}` accepts `{}` and requires an `Idempotency-Key` and write access.
Snapshots and command receipts are private to the authenticated owner.

Undo restores the original UUID, timestamps and metadata at a newer version,
rebuilding validated client/project/label references. It leaves activities
unchanged. It refuses a changed destination/session or a conflicting running
session with `UNDO_CONFLICT` (409); expired offers return `UNDO_EXPIRED` (410),
and missing offers return 404. No partial restore is committed. Repeating a
successful Undo returns its recorded result without writing again. At most 50
recent offers are retained per account; expired offers are pruned on subsequent
opt-in mutations. Undo snapshots are omitted from portable account exports.

## Private day labels and Markdown bodies

`/day-notes` is account-scoped web metadata independent of all timed work and
reports. Dates use real `YYYY-MM-DD` civil dates (years 0001–9999), independent
of time-zone changes. No owner ID is accepted from request content or queries.
Existing authentication, read/write permissions, CSRF, revocation, disabled-account
checks and optional `X-Timekeeper-Owner` binding apply to every operation.

- `GET /day-notes?fromDate=...&toDate=...` returns at most 62 inclusive dates as
  `{items,revision}`. Items are `DayNoteSummary` records with `id`, `ownerId`,
  `date`, `version`, `text` and `hasBody`. They never include body text. A label,
  a body, or both make a note discoverable; empty tombstones are excluded.
- `GET /day-notes/{date}` returns the complete `DayNote`, including `body` and
  `hasBody`, with its ETag. Untouched dates return empty strings, `hasBody:false`
  and version zero. Fetch the full note before a conditional write; the summary
  is not a substitute for its full-resource ETag.
- `PUT /day-notes/{date}` requires `If-Match` and at least one of `text` or `body`.
  An omitted field preserves its stored value. `text` remains an 80-character
  single-line label and retains its existing trimming/validation. `body` is exact
  valid UTF-8 Markdown, limited to **200,000 encoded bytes**; CRLF/LF, tabs,
  leading/trailing spaces and whitespace-only bodies are preserved. Neither
  field accepts null. `{text:""}` clears only the label, `{body:""}` clears only
  the body, and supplying both empty clears the note. Identical updates are no-ops.
- `DELETE /day-notes/{date}` requires `If-Match`, clears both fields and returns
  204 with the retained ETag. Deletion preserves the version tombstone; stale
  editors cannot recreate a removed note with an older ETag. All changed writes
  increment the version atomically. A concurrent label/body write must reload
  after a 412 rather than silently merging against a stale note.

Legacy stored labels acquire an empty body on read without rewriting their
label/version or changing the database schema. The existing owner/type/date
index and owner transaction lock remain in use. New response fields change the
full-resource ETag, so an editor held across an upgrade must reload after a 412.
Existing label-only clients remain safe: omitting `body` never erases Markdown.
The current calendar uses summary presence markers and conditional label updates;
formatted body editing is provided by the separate editor integration.

Note responses are private/no-store. Profile-style change invalidations remain
compatible with existing clients and contain no label/body content. Account
export streams present notes with complete canonical `markdown`, `text` and
`body`; label-only archives remain supported, and omitted legacy bodies preserve
an existing matching note's body. A differing active note produces a review
conflict and is never overwritten.
Empty deletion tombstones are not portable archive deletion commands.

`GET /journal-assets/{filename}` returns private attachment bytes with a strong
ETag and safe media type. `PUT` accepts JSON `{data}` with base64 bytes, using
`If-None-Match: *` for a new filename or `If-Match` for an existing one.
`DELETE` requires the current `If-Match` and retains a versioned deletion marker.
Attachment names are one safe ASCII path segment. PNG, JPEG, GIF and WebP files
with matching signatures can display inline; every other format downloads as an
octet-stream attachment. The browser route applies session, CSRF and owner
binding; WebDAV provides the same files under `/dav/journal/assets/` for clients.
The account archive includes active attachments and rejects conflicting filenames
during review.

See [journal storage protection](../operations/JOURNAL-STORAGE.md) for the
required deployment encryption, temporary/backup storage, key management and
readable-server trust boundary. The database and private account archive remain
authoritative. The [WebDAV collection](../operations/JOURNAL-WEBDAV.md) uses
the same owner, authorization and conditional-write rules.
