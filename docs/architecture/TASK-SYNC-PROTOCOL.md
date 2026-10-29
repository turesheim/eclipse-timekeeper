# Task connector protocol

This document describes how a connector uses Timekeeper's task sync API. The
schema and per-field capabilities are in [API v1](API-V1.md) and the
[capability matrix](TASK-SYNC-CAPABILITIES.md). The macOS iCloud companion and
Google/Microsoft provider adapters are separate components. Timekeeper does
not hold their OAuth or EventKit credentials in task payloads.

## Identity and authorization

Create one `task-connection` per owner and remote account and one `task-list`
per remote list. Use a provider-qualified `repositoryId` that distinguishes
account and list whenever item IDs are only unique within a list. A task can
have several provider references. Resolve by the complete
`providerId`/`repositoryId`/`externalId` tuple; never match on title. Use
`/tasks/by-external-reference` before linking an already known task. For a
changed Apple local identifier or a Microsoft list-move identifier, use the
conditional reference-replacement API; it retains the old key as an alias.
An alias match in `/task-sync/preview` is `RECONCILE_ID`, not a new task.

Give the connector a Timekeeper token with read and write permission. Read
attachment content only when it transfers attachment bytes. Bind subsequent
calls to the owner returned in
`X-Timekeeper-Owner`; a token for another owner cannot inspect or import the
connection's tasks. Keep Google/Microsoft OAuth tokens in the connector's
credential store and Apple authorization on the Mac. Never put credentials in
`providerData`, task notes, baselines, or cursors.

## Restored baseline identities

Portable and account imports allocate new Timekeeper IDs. They remap normalized
`parentTaskId`, `taskListId`, and `sectionId` inside last-common baselines as well
as the task's current relationships. Task IDs are resolved after the entire
imported task graph exists, including historical parents created later in the
import order. The baseline version and provider observation time remain unchanged.
Provider reference keys, provider-data payloads, URLs, text and other opaque
values are not rewritten, even when a value looks like a Timekeeper UUID.

If a baseline refers to a historical task/list/section absent from the archive,
that comparison cannot be carried into the new graph. Import removes only the
paired baseline group (`parentTaskId`/`siblingOrder` or
`taskListId`/`sectionId`), retaining all other observations. Current task data
and provider identities remain intact. These fields are then unbased:
`NO_BASELINE` and incomplete-group checks require explicit reconciliation before
export. A fresh provider scan must not silently treat the current local value
as the missing common ancestor. Imported record versions can advance while the
baseline is finalized; clients must use returned ETags rather than assume zero.

## Provider list and section metadata

Store only observed, otherwise unnormalized list/section fields in their
`providerData` envelopes. For example, an `icloud.reminders` envelope may contain
a grocery list type or grouping information when a companion can actually read
it. A namespace belongs to the list's connection provider; section ownership
is derived through its parent list. Do not manufacture metadata from a UI
screenshot or claim to preserve fields that EventKit or the bridge cannot read.

Read the existing envelope and preserve unknown payload keys before explicitly
replacing it with `PUT /task-lists/{id}/provider-data` or
`PUT /task-list-sections/{id}/provider-data`. Send `{"providerData":null}` only
for an intentional clear. Both operations require the current record ETag and
Timekeeper write permission. A stale ETag requires re-reading and reconciling;
these operations do not call the provider. Ordinary list/sharing/section edits
retain the envelope. Keep credentials outside it, and publish only capabilities
that the connector has tested. Archives and portable transfers retain the
payload verbatim as JSON values, including unknown nested fields; they remap
Timekeeper parent IDs and leave provider payload IDs opaque.

## Initial scan and page commit

1. Discover the remote account and lists, create or update the Timekeeper
   connection/list records, and publish only tested effective field
   capabilities. Create an `INITIAL` cursor for each list with null cursor and
   timestamps.
2. Fetch one provider page. Normalize at most 100 observations with stable
   operation UUIDs, complete provider identities, `deleted`, and an explicit
   field mask. Omit fields the provider could not read. JSON null means an
   observed clear. Do not invent time for a date-only value.
3. Call `POST /task-sync/preview` for that page. Inspect every decision.
   `CREATE` can use `/task-sync/import` for a root task. `UPDATE`,
   `KEEP_LOCAL`, and `UNCHANGED` refer to an existing task and can use
   `/task-sync/apply` with its current ETag. `CONFLICT`, `NO_BASELINE`,
   `INCOMPLETE_GROUP`, `OTHER_LIST`, and `RECONCILE_ID` need reconciliation.
   Resolve remote parent identities to existing Timekeeper task IDs and import
   parents before children. A child must share its parent's provider list.
4. Complete every safe item before advancing the list cursor with `PUT
   /task-sync-cursors/{id}` and `If-Match`. Store the provider's opaque next
   page token or delta URL as-is. Set `lastCompleteAt` only after the entire
   list scan, not after an intermediate page. Keep the last committed cursor
   when a page has unresolved items.
5. If the process crashes before the cursor write, fetch the same page and
   retry with the same operation UUID and body. Receipts return the original
   outcome. A new operation UUID with an identical provider identity and
   baseline returns the existing task. A changed remote observation goes
   through preview and conflict handling. Do not reuse one operation UUID for
   a changed body.

A minimal import observation is:

```json
{
  "connectionId": "00000000-0000-4000-8000-000000000001",
  "listId": "00000000-0000-4000-8000-000000000002",
  "sourceState": "HEALTHY",
  "item": {
    "operationId": "00000000-0000-4000-8000-000000000003",
    "providerId": "google",
    "repositoryId": "account-1/list-1",
    "externalId": "remote-123",
    "deleted": false,
    "fields": {
      "summary": "Review plan",
      "status": "NOT_STARTED",
      "completedAt": null,
      "scheduledDate": "2026-10-27"
    }
  }
}
```

`operationId` belongs to the item, and the same item body is sent to the
import or apply endpoint after preview. The connector must use the actual
connection and list IDs and must declare the included fields readable.

For an update, use the same identity with a new stable operation UUID, send
the changed normalized observation to `POST /task-sync/preview`, then submit
it to `POST /task-sync/apply` with the previewed task's current ETag:

```http
POST /api/v1/task-sync/apply
If-Match: "current-timekeeper-task-etag"
Content-Type: application/json

{"connectionId":"00000000-0000-4000-8000-000000000001","listId":"00000000-0000-4000-8000-000000000002","sourceState":"HEALTHY","item":{"operationId":"00000000-0000-4000-8000-000000000004","providerId":"google","repositoryId":"account-1/list-1","externalId":"remote-123","deleted":false,"fields":{"status":"COMPLETED","completedAt":"2026-10-27T13:00:00Z"}}}
```

A `409 CONFLICT` leaves the task and baseline untouched. Fetch the task,
preview the current observation again, and present the conflicting field
decisions. A `412 STALE_VERSION` requires a fresh Timekeeper ETag and a new
operation UUID for the recomputed proposal.

## Incremental and outbound work

Resume from each list's committed cursor. Keep the cursor provider-specific:
Apple can use observation plus periodic full rescan, Google can use an overlap
window with paginated listing and periodic full reconciliation, and Microsoft
can retain opaque list/task delta links. A cursor is never a Timekeeper task
version. If it expires or cannot be trusted, set `RESCAN_REQUIRED`, clear the
opaque cursor, and perform a new full scan. Account restore intentionally
omits cursors, requiring this same full-scan path.

For an existing task, send the provider's normalized fields to preview and
apply. Apply rechecks the last-common baseline in the owner transaction and
requires the current Timekeeper `If-Match` ETag. Remote-only fields update
locally; local-only fields remain; converged fields advance the baseline.
Opposing edits to one field or one paired completion/hierarchy/list group
require a conflict decision. A stale task ETag means read and preview again.

For outbound work, read the task and call `POST /task-sync/export-preview` for
its specific provider reference. Only `writeFields` are eligible for that
provider; show every warning to the user before a potentially lossy export.
The connector must still verify per-item permissions, provider revision/ETag,
and provider-specific write restrictions. After a successful provider write,
store its source revision/ETag and accepted last-common fields through the
conditional external-reference sync-state API. Never advance a baseline on a
failed or uncertain provider write. A Timekeeper `If-Match` value must not be
used as a provider ETag.

### Partial metadata observations

A connector that reads categories but cannot read tags declares
`metadata.categories` readable and supplies only that component:

```json
{"fields":{"metadata":{"categories":["Work"]}}}
```

A category-only pull never clears tags or links. Use an empty categories array
for an observed category clear; omission means the provider did not supply it.
Preview reports component paths such as `metadata.categories`, so independent
tag and category changes can merge while two changes to the same component
still conflict. Export preview likewise emits only writable changed components.
Do not declare the aggregate `metadata` capability unless the connector can
read the whole value. Explicit component declarations take precedence over
that compatibility fallback. Link arrays remain one metadata component; a
connector must preserve resources it cannot read or reconcile before replacing
that component.

### Partial recurrence observations

Declare `recurrence.series` and `recurrence.occurrences` independently. A
connector that observes the rule but cannot read completed instances sends:

```json
{"fields":{"recurrence":{"series":{"rule":"FREQ=WEEKLY;BYDAY=MO","end":"COUNT","until":null,"count":8}}}}
```

Omitted occurrences remain local and retain their baseline. Independent rule
and exception edits can merge; competing changes to the same component still
require a conflict decision. `{}` observes nothing. Explicit `series:null`
requests a rule clear, and `occurrences:[]` requests an exception-history clear.
A rule cannot be cleared while unobserved nonempty local history depends on
it. Preview reports `INCOMPLETE_GROUP`, and apply returns `409 CONFLICT` with
the task and baseline unchanged. An unreadable occurrence clear cannot satisfy
that dependency. Nonempty occurrences also require an existing or observed
series. First-import preview reports `INCOMPLETE` with
`RECURRENCE_SERIES_REQUIRED` if no readable rule accompanies them; import rejects
that incomplete task with `422 VALIDATION`.

A series-only initial import creates an empty local occurrence list, but its
baseline contains only the observed series. That local default is not evidence
that provider history is empty. Export blocks a series clear until the last
common history is known empty or an eligible history clear is included. An
outbound nonempty occurrence list requires a known baseline series or a series
write in the same proposal. Empty occurrence clears remain independent. Show
`INCOMPLETE_GROUP` warnings and reconcile missing observations before writing.

Complete legacy recurrence observations and nested baselines remain readable.
Explicit component capabilities override the aggregate `recurrence` fallback.
Export retains the complete object for a legacy aggregate declaration with a
complete baseline and no component overrides, provided every included change
is eligible. A blocked component prevents that compatibility write. Other
exports contain only eligible changed components. Ordinary task responses and
`PUT /tasks/{id}/recurrence` retain the complete recurrence object.

Comparison uses the task recurrence codecs to normalize equivalent time
representations, retaining civil dates, wall times, and zones. Older loosely
typed baseline components that cannot be decoded remain stored but are treated
as unbased; a null occurrence value cannot establish an observed empty history.
A new valid provider observation can establish a common value through normal
convergence or explicit conflict reconciliation. Unsupported provider rules and
unreadable instances follow the capability gate and provider-data policy.

### Partial reminder observations

Declare capabilities by exact trigger kind: `reminders.ABSOLUTE`,
`reminders.RELATIVE`, `reminders.LOCATION`, `reminders.CAR`, and
`reminders.MESSAGE`. For example, a connector that observes timed reminders
but cannot read geofences sends:

```json
{"fields":{"reminders":{"ABSOLUTE":[]}}}
```

This clears only absolute reminders. Omitted kinds remain untouched; `{}`
observes nothing. Each group's entries must have its matching `kind`. Null,
unknown kinds, duplicate IDs across the merged task, and a resulting total
above 100 are invalid. A group's baseline and conflict decisions are separate
from other kinds. Concurrent changes to the same kind require reconciliation.
IDs remain stable; order within a kind is retained, and unaffected task entries
retain their relative order. Numeric and time representations are normalized
through the task reminder codec before comparison.

Legacy full-array observations and baselines remain accepted: an array observes
all five kinds, including an empty group for each absent kind. An explicit
kind capability overrides the legacy aggregate `reminders` declaration.
Baselines may migrate from arrays to nested per-kind objects during apply;
clients storing accepted last-common fields must retain this presence mask.
Export emits only eligible changed groups. For a legacy aggregate capability
with a complete baseline and no explicit kind declarations, export retains the
full-array representation. If a blocked change would be included in that whole
array, export omits it and reports `INCOMPLETE_GROUP` alongside the specific
blocked-kind warning. Task responses and `PUT /tasks/{id}/reminders`
continue using arrays.

A writable absolute group does not certify a provider's support for arbitrary
counts of timed alerts. The connector must check its effective per-item limits
before export; it must retain extra alerts locally and report the restriction,
never silently select one, or convert an unsupported kind to a timed reminder.

## Remote absence and deletion

A failed provider request, expired credentials, changed permissions, or a
missing list must set the corresponding unhealthy source state. Keep tasks,
links, and cursors; do not infer deletion. A deletion observation from an
incomplete scan is `DELETE_DEFERRED`. Even after a healthy complete scan it is
`DELETE_REVIEW` unless local changes make it `DELETE_CONFLICT`. No task sync
endpoint deletes a task automatically. A reviewer can keep it, unlink that
provider reference, or explicitly delete the Timekeeper task with its current
ETag. Prefer unlinking when another provider still links the same task. The
ordinary task delete records a tombstone, so a later stale provider page does
not silently recreate the same identity.

For example, after `DELETE_REVIEW`, a reviewer who chooses to remove the
Timekeeper task can call `DELETE /api/v1/tasks/{id}` with its current
`If-Match` ETag. For a task linked to another provider, the reviewer can call
`DELETE /api/v1/tasks/{id}/external-references` with the provider identity in
the query string and the task ETag; this unlinks only the deleted source.
Neither request is made automatically by a connector.

For an ID change, replace the reference conditionally instead of importing a
new task:

```http
PUT /api/v1/tasks/00000000-0000-4000-8000-000000000005/external-references/replace
If-Match: "current-timekeeper-task-etag"
Content-Type: application/json

{"previous":{"providerId":"microsoft","repositoryId":"account-1/list-a","externalId":"old-id"},"current":{"providerId":"microsoft","repositoryId":"account-1/list-b","externalId":"new-id","externalUrl":null}}
```

The old identity remains an alias for lookup. The connector must update the
owned provider-list association separately when the task moved lists.

## Retry and recovery

| Result | Connector action |
| --- | --- |
| `200`/`201` | Persist the item outcome; commit the page cursor after all items succeed. |
| `401`/`403` | Stop, refresh authorization or permissions, and leave cursor and tasks intact. |
| `404` | Recheck the owner, connection, list, and task identity; do not treat this alone as a remote deletion. |
| `409` | Re-preview or ask for reconciliation; an operation ID reused with changed input is rejected. |
| `412` | Fetch the current ETag, preview again, and use a new operation ID for the new proposal. |
| `422` | Fix invalid or unsupported normalized data; keep the page cursor. |
| `503` or transport uncertainty | Retry the exact operation ID and body with bounded backoff. |
| Provider rate limit | Respect the provider's retry delay and keep the Timekeeper cursor at the last completed page. |

Attachments use the owner-scoped task attachment endpoints and bounded bytes,
checksum, and content type. The content download route is for an authorized
connector; do not put attachment bytes into task baselines or provider-data
envelopes.
