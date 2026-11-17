# Internet account troubleshooting reference

For connecting accounts and selecting calendars or tasks, use the
[short connection guide](TASK-PROVIDERS.md). Administrators use
[OAuth setup](SHARED-SIGN-IN.md) and [retained settings](CONFIGURATION.md#retain-operator-settings).

## Outcome unknown

A lost Google write response leaves **Outcome unknown**. Dagsverk records the
request before sending it and never automatically resends an uncertain operation.
Choose **Check Google result** to read current values. Checking also works after
reconnecting with import-only access. A missing task or HTTP 404 alone does not
prove that a deletion succeeded.

For an uncertain creation, choose **Find unlinked Google tasks**. Search by title
or exact ID, compare the values and explicitly link the correct Google task.
Searches show up to 100 matches; narrow the search if needed. Dagsverk never
matches identities by title. Already linked tasks keep synchronizing while new
items from the affected account/list wait for review.

Linking records the observed identity without claiming that the original request
succeeded. If the local task was explicitly deleted during the request, linking
records the Google identity as locally deleted and does not recreate it.

If no correct identity can be established, keep the result for investigation or
choose **Allow imports without linking**. Acknowledge that a late Google result
may then be imported as a separate task. This local decision does not resend or
cancel the creation, change the native task or claim that Google failed. It also
works while paused or disconnected. Read-only Dagsverk sessions cannot make it.

Disconnected accounts with pending requests remain visible without credentials.
Unsent requests can be cancelled and uncertain creations left unlinked. After
the last pending request is settled, the card disappears. Reading Google again
or accepting a known Google result requires reconnecting.

## Conflicts and deletions

Healthy incoming changes merge automatically. For a conflicting list, open
**Review incoming changes** and compare the saved scan with current Dagsverk
values. **Keep Dagsverk for conflicts** or **Use Google Tasks for conflicts**
resolves opposing values; independent remote changes still merge and unsupported
local fields stay intact. With two-way access, keeping a supported local value
queues its normal background write. Read-only access sends no provider mutation.

Ordinary confirmed deletions synchronize while retaining recorded work and
identity/deletion history. A complete healthy reconciliation can also establish
absence; a failed/incomplete scan, lost permission or an isolated 404 cannot.
If a local deletion races with a Google edit, choose whether to keep the deletion
or restore the Google task. Other source links or unresolved hierarchy/identity
can block deletion until resolved. Read-only Dagsverk sessions can inspect these
comparisons but cannot decide them.

Polling preserves open comparisons. A changed task, list selection or scan
requires reopening the review. Comparisons expire after 15 minutes; refresh from
the provider for a fresh scan. Large reviews support title/identity filtering.

## Task identities

Existing tasks and later additions synchronize automatically for selected lists.
Tasks are identified by provider account, list and task ID; matching titles do
not establish that a task was moved. A genuinely new ID creates a separate task.

**Task links needing attention** is shown only when an observed identity aliases an
existing source link. Review its values and choose the exact Dagsverk task/link
before replacement. This retains the task ID, project, local hierarchy and
unrelated references. Retired identities remain aliases; their later deletion
cannot delete the retained task. Local deletions and uncertain outgoing creations
still need their own decisions. Identity resolution sends no provider writes.

## Google editing restrictions

With two-way OAuth consent, supported local changes synchronize automatically.
The account page exposes exceptions and the latest 20 completed results; legacy
preview endpoints remain for older clients, rather than requiring normal edits
to be prepared and sent manually.

Creation links the returned identity to the existing Dagsverk task. Parent/order
and supported list moves synchronize without moving its Dagsverk project. A
proven changed provider ID retains an alias; matching titles never prove a move.
Google’s scheduled civil day does not create a timed deadline or reminder.
Unsupported rich text, timed dates and other fields remain local, with the
restriction identified beside the affected task. Google Docs assignments cannot
have notes; assigned/repeating tasks restrict placement, and assigned tasks
cannot be deleted here. Edit source-specific content at its original surface.

Google's title/note limits and observed version are checked before a write.
Concurrent changes need a fresh comparison. Resolving an uncertain operation
accepts observed values without asserting that the original write succeeded.
Refreshes and queued writes honor the provider's retry delay.

Source links and assignment context are read-only. A Google document or Chat
assignment identifies its source, not a person. Source identities include the
connected account/list; names do not match resources between accounts. Unknown
provider context remains in the task's namespaced payload, and independent local
resource edits are retained or offered for conflict review.

## Limits and incomplete synchronization

Failed or incomplete scans retain existing data and the last complete checkpoint.
Acknowledged pages survive a server restart. Deselecting a task list or
disconnecting discards staged pages while keeping imported tasks and completed
checkpoints. Resuming a connection that needs reauthorization still requires
reconnecting.

- Task lists: at most 10,000 tasks or 32 MiB per scan. Google uses a five-minute
  overlap for incremental reads and a complete reconciliation at least daily.
- Calendars: at most 5,000 event definitions or 32 MiB; generated iCalendar is
  limited to 2 MiB. Each Microsoft recurring series has a 4 MiB exception budget.
  Expired Google calendar tokens trigger a complete scan. Invalid pages never
  replace the last complete calendar cache. Windows zones use bundled CLDR 48.

Calendar errors and task-import errors are reported separately. iCloud Reminders
requires a separate Apple-authorized connector; this integration does not connect
it. Subscription feeds containing real VTODO records remain supported.

## Backups and verification

OAuth tokens are private and excluded from portable account archives; reconnect
after importing one. Whole-instance backups contain local grant records. Managed
grants also need the service's matching encrypted records; direct grants need
the matching app credentials. See [instance backup](INSTANCE-BACKUP.md) and
[OAuth service operations](CONNECT-SERVICE.md).

`DAGSVERK_TASK_AUTOMATIC=false` (or `timekeeper.tasks.automatic=false` in an
operator properties file) disables scheduled task synchronization at startup. Manual
refresh remains available.

For developer contracts, see [API v1](../architecture/API-V1.md) and the
[task connector protocol](../architecture/TASK-SYNC-PROTOCOL.md). Synthetic
[restart acceptance](../development/WEB-ACCEPTANCE.md#provider-restart-acceptance)
does not establish live Google/Microsoft app-registration acceptance; use
[Verify configuration](SHARED-SIGN-IN.md#verify-the-connection) and a disposable
provider list to test a deployment's consent, import, refresh and reconnect.

For a repeatable Google-specific automated and opt-in live round-trip check, use
[Google Tasks connector acceptance](../development/GOOGLE-TASKS-ACCEPTANCE.md).
