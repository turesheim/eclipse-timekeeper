# Automatic task-list synchronization

Status: implementation under verification, 15 November 2026.

Selecting a Google Tasks or Microsoft To Do list subscribes it to automatic,
two-way synchronization. Existing tasks, new tasks and subsequent edits flow in
both directions. Changes are made in the ordinary task editor; there is no
**Prepare a change**, **Review change**, **Review result** or **Send** step.

The Dagsverk server coordinates this work. Browsers and Eclipse clients using
that server see the same records and committed updates. Provider synchronization
continues when all clients are closed. Eclipse's embedded local database remains
a separate backend.

## User experience

1. Connect an Internet account and authorize task read/write access once.
2. Select task lists and calendars, then use the account’s single **Save** button. Persist both selections atomically and show
   **Synchronizing**. If saving fails, retain the previous selection and explain
   the failure beside the list.
3. Import existing tasks automatically. An optional project mapping supplies the
   project for new root tasks; it does not determine which tasks synchronize.
4. Edit, complete, reopen or delete a task in either application. Synchronization
   follows automatically. Creating a Dagsverk task in the subscribed list creates
   its provider counterpart.
5. Show **Up to date**, **Synchronizing**, **Paused**, **Reconnect required** or
   **Needs attention**, plus the last successful sync and an actionable reason.
   **Synchronize now** requests a background pass, not approval.

Existing read-only grants require one explicit reauthorization before two-way
synchronization starts. A provider list without write permission is labelled
**Read only**, never successfully configured for two-way synchronization.

Unchecking a list stops synchronization and retains both copies. Disconnecting
also retains Dagsverk tasks. Neither action means “delete the tasks.” Pause
retains selections and pending changes. Resuming reconciles both sides against
their retained common baseline before sending anything.

## Membership and identity

The boundary is **Dagsverk owner → Internet account → task list**. A selected list
covers all its tasks, including completed tasks where the provider exposes them.
Pagination or resource limits must not silently produce a “complete” partial import.

- A task's assigned provider list is its synchronization home. Creating a task
  within that list assigns the home; the generic task editor offers the same
  list selection.
- Selecting a list does not export unrelated Dagsverk tasks merely because they
  share its mapped project. Projects, clients, labels and work remain local data.
- Store the Dagsverk ID and complete provider/account/list/task identity. Never
  match by title, notes or dates. Equal titles can represent different tasks.
- Retain old IDs as aliases after a proven provider-side identity change.
  A task has one active synchronization home; unrelated external references do
  not authorize copying it into another provider account.
- A move to another subscribed list preserves the Dagsverk ID, project and
  history. Use a supported provider move. An unsupported move becomes a specific
  exception, not a silent create-and-delete operation. Transfers between provider
  accounts require a separate explicit design.

## Data that synchronizes

Use the existing [capability matrix](TASK-SYNC-CAPABILITIES.md) and
[normalized task model](API-V1.md). Effective support is the intersection of
adapter support, granted permissions, list capabilities and task restrictions.

| Data | Behavior |
| --- | --- |
| Title and notes | Synchronize supported values and explicit clears. Never truncate text or silently convert rich content to plain text. |
| Completion and reopening | Synchronize status and completion time as a group. Do not invent a provider timestamp that was not observed. |
| Google scheduled day | Map Google **due** to Dagsverk's civil scheduled date, never to a timed deadline. |
| Microsoft dates | Preserve supported start/due dates, wall times and zones; Google's mapping does not apply. |
| Hierarchy and order | Synchronize supported relationships; create parents before children. Unsupported nesting stays local. A To Do checklist item is not a Dagsverk child task. |
| Creation, movement and deletion | Synchronize identities and membership with the safeguards below. Completion is not deletion. |
| Provider-only fields | Preserve them; send only supported changed fields. |
| Dagsverk-only fields | Retain them locally without blocking independent supported changes. |

Google's [task resource](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks)
defines date-only scheduling and assigned-task restrictions. Its
[move API](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks/move)
supports list/parent/order changes with additional restrictions, including for
repeating tasks. Microsoft's
[task resource](https://learn.microsoft.com/en-us/graph/api/resources/todotask?view=graph-rest-1.0)
and [update API](https://learn.microsoft.com/en-us/graph/api/todotask-update?view=graph-rest-1.0)
define its distinct fields and HTML body-write requirement.

Explain capability limits once in connection/list details and, where useful,
beside the affected field. If one changed field cannot be represented, retain it
locally and synchronize independent supported changes. This must not introduce
a review/send workflow or erase unsupported values on either side.

## Server coordination

Reuse the [connector protocol](TASK-SYNC-PROTOCOL.md), field baselines, source
observations, durable receipts and fenced worker leases. The change is automatic
scheduling and reconciliation, not browser-driven provider requests.

1. Every committed task mutation from a browser, Eclipse, API or other supported
   server input records synchronization intent in the same owner transaction.
   Include membership changes and deletion tombstones.
2. Persist intent before acknowledging the save: task/reference, operation ID,
   connection generation, intended delta and source version. Coalesce unsent
   edits to the latest desired state. An operation already sent retains its
   immutable identity and payload.
3. A worker claims work, reads the current provider state and compares it with
   the common baseline and current Dagsverk fields. Network requests run outside
   database locks.
4. Apply remote-only changes through normal Dagsverk services and publish them to
   connected clients. Apply local-only changes through the provider adapter.
5. Persist the acknowledgement and fields actually accepted before advancing the
   baseline. If a user edited during the request, retain that newer local value
   and queue its remaining difference.
6. Continue until both supported projections agree. Imported observations and
   acknowledged writes are not new outbound user edits; baseline comparisons
   prevent echo loops.

Queued work survives restart without an open client. A stale worker cannot
acknowledge into a newer connection generation. Unsubscribe, pause, reconnect and
disconnect invalidate incompatible work. Already transmitted requests may finish;
retain and reconcile their outcomes without starting another mutation after stop.

Healthy local commits should begin an outbound attempt within five seconds.
Aim to discover provider edits within one minute where quotas permit; poll
adaptively with fairness, jitter and visible backoff status. These are scheduling
targets, not outage or large-import guarantees. Google uses
[paginated reads](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks/list)
with overlap and full reconciliation; Microsoft can use
[task delta queries](https://learn.microsoft.com/en-us/graph/api/todotask-delta?view=graph-rest-1.0).
Commit complete scans/delta rounds durably, without skipping unresolved data.

## Concurrent edits

Compare each supported field or dependent field group with its common baseline:

| What changed | Automatic action |
| --- | --- |
| Only Dagsverk changed | Send the supported local delta. |
| Only the provider changed | Apply the remote change. |
| Both reached the same value | Advance the baseline without another write. |
| Different fields changed | Merge the independent changes. |
| Both changed the same field differently | Preserve both values; identify a task-specific conflict. |

Do not pick a winner using unrelated device clocks or blindly overwrite a newer
provider value. Use current Dagsverk validators and tested provider preconditions;
reread and recompute after a stale version. Google describes
[conditional read/modify/write](https://developers.google.com/workspace/tasks/performance#using-patch-in-a-read-modify-write-cycle).
A Dagsverk ETag is never a provider ETag.

Verify conditional writes for every enabled operation, including deletion and
movement. If a provider operation cannot enforce a precondition, document and
test the narrower concurrency guarantee before rollout. A read-before-write
check alone does not establish lossless concurrent updates.

A genuine conflict shows the task, affected field and both values, with a direct
way to keep either. The resolution becomes an ordinary committed change and
synchronization resumes automatically. Blocked tasks do not block unrelated
tasks or lists. There is no routine “awaiting review” queue.

## Deletion and uncertain outcomes

A deliberate deletion is a synchronized operation. Persist its identity and
intent as a tombstone before removing it from active views. Retain worked time
and historical references; physical cleanup is a separate retention operation.
Dependencies that prevent safe removal produce a task-specific exception.
Assigned/provider-derived tasks need accurate deletion semantics;
[Google assigned-task deletion](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks)
can also delete the task at its original Docs/Chat source.
Do not silently substitute deletion for unlinking or unsupported removal.

Provider deletion requires trustworthy evidence from a healthy complete scan or
delta round. A missing page, inaccessible list, failed request, expired token or
isolated 404 is not deletion evidence. Stale pages must not recreate deleted
identities. A delete/edit race preserves the edit for conflict resolution.

Retry known-not-applied operations with bounded backoff and the provider's retry
delay. Retain pending edits during outages. Refresh credentials automatically
when possible; otherwise show **Reconnect required**, retaining tasks and baselines.

A lost mutation response is an unknown outcome, not permission to resend it.
Read back known identities and acknowledge proven results automatically.
Creation retries require provider idempotency or a proven stable correlation
mechanism. Google's [insert contract](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks/insert)
alone does not establish idempotent creation. Never guess by title, silently add
tracking text to users' notes, or claim universal exactly-once delivery. When the
result cannot be proven, retain the local task, isolate that creation and ask
only for the exceptional identity decision. Other tasks continue synchronizing.

## Permissions and transition

New task connections use the existing [OAuth setup](../operations/SHARED-SIGN-IN.md)
and request Google task write access or Microsoft delegated
**Tasks.ReadWrite**, as appropriate. Microsoft's required permission is documented
in its [update API](https://learn.microsoft.com/en-us/graph/api/todotask-update?view=graph-rest-1.0).
Read-only Dagsverk sessions cannot edit tasks or connection settings; background
work uses only the separately authorized provider grant.

Connected task subscriptions always synchronize automatically within their granted
access. Existing subscriptions upgrade during background refresh without a new
browser save. Preserve confirmed queued operations and receipts, reconcile unknown
outcomes, and leave unconfirmed prepared changes unsent. Start automatic work from
current task/baseline differences; old prepared payloads never authorize a write.

The server now uses its durable coordinator for automatic creation, updates,
Google placement/list moves, and retained deletion. Microsoft has a separate
Graph writer; HTML notes, timed start/deadline values, importance and categories
retain their distinct mappings. Microsoft list moves and hierarchy remain
unsupported, with task-specific exceptions. Legacy manual receipts remain
recoverable; unconfirmed previews never become automatic payloads.

`Task.syncListId` is explicit membership. A nullable home on update unlinks;
omitting it preserves membership. `GET /task-sync/lists` supplies owned writable
subscription choices to the browser and remote Eclipse editors. Deleted linked
tasks retain their row, activity relations and tombstone. Transfer/CSV preserves
that state and remaps the home; importing data never supplies provider credentials.

Internet-account calendar selections also determine their visibility. Previously
hidden selected calendars become visible when refreshed or saved. Subscription-link
calendars retain their separate visibility setting. Provider task IDs remain stored
identities and do not prefix Google or Microsoft task titles in the browser.

Both writers send the exact provider `If-Match` revision for updates and deletion;
Google moves also carry it. Synthetic tests verify the request and 412 handling.
The Microsoft To Do and Google move method documentation does not establish that
every live operation enforces these headers. Controlled account tests must verify
that behavior before rollout. Read-before-write alone still has an intervening
write window if a provider ignores its header; no lossless guarantee is claimed
for such an operation. Lost move responses require an explicit identity decision
from a fresh complete destination scan, unless a successful move receipt was
already persisted. This work must not be released on synthetic evidence alone.

## Acceptance criteria

- Checking a list imports its complete contents and enables both-way edits
  without an import, preparation, approval or send action.
- A task created in that list from the browser or remote Eclipse appears once
  at the provider; provider-created tasks appear once in both clients.
- Supported text, dates, completion, reopening, hierarchy and membership converge;
  unsupported data remains intact.
- An edit queues automatically, survives closing clients/restarting the server,
  and is not lost when made during an in-flight provider request.
- Independent concurrent edits merge; a same-field conflict preserves both values
  and identifies the actual task/field while unrelated work continues.
- Deliberate deletions propagate safely. Incomplete reads, revoked permission,
  missing lists and stale pages never cause mass deletion or resurrection.
- Lost responses, expired leases, duplicate delivery and replica races do not
  create duplicates or blindly repeat unknown mutations.
- Unsubscribe, pause and disconnect retain tasks and prevent new writes; resume
  reconciles accumulated edits without a first-import review.
- Read-only grants, special tasks and unsupported values have accurate status.
  Reauthorization is a connection action, not a per-task step.
- Identical remote IDs/titles in different accounts remain isolated. Verify H2
  and PostgreSQL restart behavior, provider adapter contracts and controlled
  real-account integration before enabling writes.
- English/Norwegian, keyboard, narrow-screen and read-only browser/Eclipse
  scenarios contain no routine **Prepare**, **Review** or **Send** controls.
