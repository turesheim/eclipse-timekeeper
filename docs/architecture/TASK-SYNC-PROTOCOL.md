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
