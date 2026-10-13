# Timekeeper HTTP API v1

This contract implements the design part of #217 and is the authority for the
Quarkus server (#218), remote Eclipse adapter (#220), web frontend (#221) and
change synchronization (#219). All five are required before Timekeeper 2.0.
The embedded implementation remains available and its database is not implicitly
copied, merged or opened by the server.

[openapi.json](openapi.json) describes `/api/v1` using
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
The server administrator provisions owners/tokens; v1 has no account-management
or cross-owner sharing endpoint. TLS is required outside loopback development.
Tokens are never returned in DTOs, URLs, logs, errors or change notifications.

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

An interval end must be at or after its start. A manual interval may be open,
but each owner has at most one open interval across all tasks and clients.
Manual editing sets `manual=true` and cannot change activity task/owner. Manual
future recorded intervals follow the embedded domain's current policy; automatic
start/stop always uses the server clock. Clients do not submit their clocks for
live tracking. #82 is a separate future-start policy issue.

Portable interchange uses `timekeeper-domain-v1` JSON, not SQL/JPA table names.
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
/tmp/timekeeper-contract-venv/bin/pip install -r api/tests/requirements.txt
/tmp/timekeeper-contract-venv/bin/python -m unittest discover -s api/tests -v
```

[http_acceptance.py](tests/http_acceptance.py) is the reusable black-box server
suite. It performs writes and requires an **isolated test server/database** with
two owners and a read-only token for the first owner. Supply fixture tokens via
`TIMEKEEPER_TEST_TOKEN_A`, `TIMEKEEPER_TEST_TOKEN_B` and
`TIMEKEEPER_TEST_TOKEN_READ_ONLY`; never use real accounts/data. Run:

```sh
python api/tests/http_acceptance.py --base-url http://127.0.0.1:8080/api/v1
```

The suite checks actual HTTP authentication/authorization, cross-owner relations,
stale writes, atomic switching, duplicate commands, provider identities, DST
reports, pagination, imports and owner-scoped notifications. Static schema tests
do not prove server enforcement. #218 must run this suite against the real
Quarkus server/database and separately verify restart/durable receipts, concurrent
requests, schema guards and operational configuration. #220/#221/#219 add actual
client behavior, unsaved-edit and reconnect acceptance before the parent closes.
