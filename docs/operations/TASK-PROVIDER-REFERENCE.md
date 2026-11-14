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

Open **Review incoming changes** for the affected list. Compare the saved Google
scan with current Dagsverk values; filter by title or exact identity for lists
with more than 100 decisions. Choose **Keep Dagsverk for conflicts** or **Use Google
Tasks for conflicts**. Both accept independent remote changes and retain
unsupported local fields. These review decisions send no writes to Google.

A confirmed Google deletion offers **Keep the Dagsverk task** or **Delete the
Dagsverk task**. Other source links, incomplete hierarchy or unresolved identities
can prevent deletion. Lost access and absent records never imply deletion.
Read-only Dagsverk sessions can inspect comparisons but cannot resolve them.

Polling preserves open comparisons. A changed task, list selection or scan
requires reopening the review. Comparisons and first-import previews expire
after 15 minutes; refresh from the provider for a fresh scan. Manual refresh
replaces ready first-import previews for all selected lists.

## Newly found or moved Google tasks

After the approved first import, **Review new tasks** offers **Import as a new
task** or **Use the existing Dagsverk task**. For replacement, choose the exact
existing task/source link from the same provider account, compare values and
acknowledge the choice. A provider ID change may represent a moved task; titles
and missing records are insufficient to decide that two records are the same.

The target list must be selected and readable. An old link can be retired even
when its list is unselected or unavailable, without fetching or changing that
list. Replacement retains the Dagsverk task ID, project, local hierarchy and
unrelated references. Conflicting local values stay local; independent remote
changes are accepted. The retired identity remains an alias. Its later deletion
cannot delete the retained task, and a live retired identity can be explicitly
made current again. Deliberate local deletions and uncertain outgoing creations
still require their own review. These choices send no provider writes.

## Google editing restrictions

Each outgoing change has a separate preview and explicit confirmation. The
account page shows active operations and the latest 20 completed results.
Creating at Google links the returned ID to the existing Dagsverk task; hierarchy,
order, rich text and unsupported fields stay local. Imported Google hierarchy
and order continue to synchronize. Google's due date is a scheduled civil day,
not a deadline or reminder. Assigned tasks cannot be deleted here; edit their
notes at the original assignment source.

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
operator properties file) disables scheduled task imports at startup. Manual
refresh remains available.

For developer contracts, see [API v1](../architecture/API-V1.md) and the
[task connector protocol](../architecture/TASK-SYNC-PROTOCOL.md). Synthetic
[restart acceptance](../development/WEB-ACCEPTANCE.md#provider-restart-acceptance)
does not establish live Google/Microsoft app-registration acceptance; use
[Verify configuration](SHARED-SIGN-IN.md#verify-the-connection) and a disposable
provider list to test a deployment's consent, import, refresh and reconnect.
