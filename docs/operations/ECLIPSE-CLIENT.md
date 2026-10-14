# Connect Eclipse to the Timekeeper server

Install **Timekeeper for Eclipse** from the [testing update site](https://turesheim.github.io/eclipse-timekeeper/updates/testing/)
for verified unreleased 2.0 main builds, or the [stable update site](https://turesheim.github.io/eclipse-timekeeper/updates/)
for published releases.
In **Help > Install New Software... > Add...**, paste that URL into **Location**,
select the feature and restart. Add **Timekeeper Mylyn integration** if you want
Task List integration. Timekeeper 2.0 requires Eclipse 2026-09 and Java 21;
see the [installation guide](https://github.com/turesheim/eclipse-timekeeper/wiki/Installing-Timekeeper-2.0)
for publication status and candidate installation.

## Select the remote backend

1. Obtain the server URL and a bearer token for your account from its operator.
2. Open **Preferences > Timekeeper > Backend** and select **Timekeeper server**.
3. Enter the server's API URL, ending in `/api/v1`, and the token.
4. Click **Test connection** to check the entered URL and token without saving
   them or changing the running backend. A successful test shows the account and
   time zone. Authentication, connection and timeout failures appear on the page;
   correct the fields and try again. The test runs in the background and makes
   no changes to server records.
5. Apply the settings and restart Eclipse. Open **Window > Show View > Other... >
   Timekeeper > Workweek**.

Use `http://127.0.0.1:8080/api/v1` for a server on the same computer. Other hosts
require HTTPS. The [shared Tailscale guide](TAILSCALE.md)
describes private HTTPS access for local Docker and Synology deployments. The server remains the authority for
records; selecting a backend does not copy or merge databases. Mylyn activation
uses the selected backend after restart.

GitHub tasks activated from the Task List use their Mylyn category as the
Timekeeper project, or their query name when there is no category. Queries can
therefore represent separate projects within the same repository. For a task in
several queries, new imports use the first query by name, then by query handle;
an existing explicit project assignment is retained. Activating a GitHub task
previously imported under **Uncategorized** or **Unmatched** repairs its grouping
while retaining its task ID, external reference and recorded activities.

Eclipse and the web client share projects, tasks, activities, labels and work sessions when
their tokens belong to the same server account. Different accounts have separate
records, even on the same server. Open label preferences and activity editors
receive label changes while preserving unsaved fields and selected labels.
Conflicting label-definition edits retain their original version and are
rejected for review rather than overwriting another client's changes.

Use **New activity** or **Edit activity...** in Workweek to record or adjust an
interval. Its start cannot be in the future, whether it is ongoing or completed.
The selected backend validates its own clock; an invalid edit or import leaves
stored records unchanged.

Activities that cross midnight become separate daily records. This applies to
ongoing tracking, manually entered intervals and imports. Tracking continues
with the same active identity on the new day; summary, task and labels follow
it. Each completed day can then be edited or deleted separately. Local storage
uses the computer's calendar time zone; server storage uses the account's time
zone. Missed boundaries are processed after startup, with exact elapsed time
preserved through daylight-saving changes. An editor opened before rollover
must reload the changed record before saving.

CSV exports include label definitions and activity-label assignments. Older
exports without these two files remain supported; when merging such an export,
existing labels on a split activity are copied to its new daily records.
New exports also include work sessions and their activity associations. Keep all
exported files together; an activity's session reference requires the session file.

## Work sessions

The compact **Work Session** panel above Workweek shows the current running status,
session start/end, duration, tracked time, covered time and unallocated time.
**Start work session** and **Stop work session** operate independently of task
timers. Stopping the session leaves a running activity unchanged. Only one
session may run per account; several completed sessions can be recorded in a day.
Sessions can span midnight without stopping, while their activities split into
daily records and retain the association.

Expand **Session details** to show history, associated activities and editing
actions. Use **Record session** for an earlier interval, or choose a session in the history
list and use **Edit session**. These editors accept exact UTC timestamps,
including fractional seconds. Leave the end blank for a running session.
**Add activity** assigns the new interval to the selected session; choosing a
task is optional, and a taskless activity can optionally belong directly to a
project. **Associate activity** links an existing interval without changing its
times or task. Select a row and use **Remove association** to detach it. Double
click an associated row to edit the activity. A session must be detached from
all activities before deletion.

Tracked time sums each activity's overlap with the session. Covered time counts
the union of those intervals, so overlaps do not cover time twice. Unallocated
time is session duration minus covered time. An activity outside the session is
allowed with a warning; only its overlap contributes to these totals. Session
associations do not change Jira/GitHub task references.

Remote reads and writes run in background jobs. Failed edits preserve drafts.
If a start/stop reply is lost, **Retry session command** repeats the original
command key and payload instead of creating a second session or stopping twice.

## If the token cannot be saved

Timekeeper encrypts tokens using Eclipse secure storage. A storage error occurs
before the new backend settings are saved; resolve it and apply the settings
again. The error now identifies a missing provider, unavailable master password,
encryption/decryption failure, or an unwritable storage file.

Open **Preferences > General > Security > Secure Storage**. On the **Passwords**
tab, inspect the available master-password providers. On macOS, the operating
system provider obtains its master password from the OS keyring; allow the
corresponding Keychain prompt if it appears. If the provider malfunctions,
Eclipse allows selecting another enabled provider for new entries. An existing
encrypted entry still requires its original provider and master password.

For a file-write error, use the **Contents** tab to find the storage location
and check that your account can write it. Recover an existing master password
using Eclipse's recovery option when it was configured. Deleting the shared
secure-storage file removes other Eclipse credentials too, so preserve it when
troubleshooting. Timekeeper does not fall back to plaintext preferences.

See Eclipse's [secure-storage preferences](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/reference/ref-securestorage-prefs.htm)
and [how secure storage works](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/reference/ref-securestorage-works.htm).

## Workweek display

Workweek includes daily columns and **Weekly total** for projects, tasks
(including subtasks) and activities. The **Collapse all** toolbar button hides
activity rows while keeping the project/task outline visible; **Expand all**
restores them. Under **Preferences > Timekeeper**, select **Show durations as
decimal hours** to display, for example, 90 minutes as `1.50`. The same option is
available in the Workweek view menu. It changes presentation immediately and
persists for this Eclipse installation; clock-based activity editing and stored
timestamps keep their original precision.

## Conflicts and interrupted requests

Changes from other clients refresh Workweek automatically. Open editors retain
their original drafts and record version; if another client changes that
record, close the editor, review the refreshed record and reopen it before
saving. Authentication and connection errors appear in the client and preserve
the server's data. After a request times out or is cancelled, follow its review
instruction: a request may already have committed. Ordinary creates are not
automatically repeated. Start/stop and CSV import use owner-bound command keys
for the explicitly supported retry flows.
