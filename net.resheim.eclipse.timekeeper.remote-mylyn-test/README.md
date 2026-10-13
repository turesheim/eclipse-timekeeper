# Installed remote Mylyn acceptance

The account-switch phase also launches a real headless Chromium client against
the same packaged server and verifies bidirectional Workweek changes, preserved
conflicting drafts, activation/deactivation and exact daily/weekly reports.
Prepare its locked test dependencies before running `-Premote-client-tests`:

```sh
npm ci --ignore-scripts --prefix web
cd web && npx playwright install --with-deps chromium && cd ..
```

Node must be on the test JVM's PATH. CI installs it and the browser explicitly.
There is no production Node dependency. The token goes only through the child
environment; the line protocol and retained diagnostics contain no credentials.
Details and limits: [WEB-ECLIPSE.md](../baseline/WEB-ECLIPSE.md).

This module extends the isolated packaged-server bootstrap from `remote-test`
and installs the official optional Timekeeper/Mylyn feature using p2. It runs
in its own workspace and credential fixture, separate from the native-only
runtime. The app's embedded entity manager must remain null and the valid local
fixture database file must retain its original checksum across every phase.

The first phase projects remote native tasks into the real Mylyn Task List and
uses `TasksUi.getTaskActivityManager()` activation events to start, switch and
stop tracking on the server. It compares the actual remote UUIDs and verifies a
late deactivation cannot stop the successor. It writes a custom Task List
marker and waits for Mylyn's official externalization/save operation and
persistent preferences before the test VM exits.

The second phase retains the first workspace (`deleteOsgiDataDirectory=false`)
while launching a new Eclipse JVM and server JVM. It requires the saved marker,
so recreating a projection does not satisfy the test. Activation must reuse the
original remote task UUID, create a new interval and preserve prior history.

The second phase also opens the real backend preference page and saves a
second account token through its password field. The third Eclipse/server
launch retains that same workspace and must authenticate as account B. Its
server data starts empty; account A's saved projection remains in Task List but
must neither resolve in account B nor stop account B's active interval. The test
creates a project/task with the same visible names and requires distinct
projections and remote UUIDs, then checks that account A's revision and history
are unchanged. The account credentials and a second owner-B session credential
are encrypted in the isolated keyring; the registry contains only their hashes
and fixed owner mappings.

The account-B phase also deletes a projected task with recorded time and its
nonempty project category through the real Task List. Both server conflicts
must display an error and restore the task/category hierarchy without changing
server revision or history. A later empty-task deletion must succeed, proving
the rejected deletions no longer block reconciliation. This covers definitive
conflict rejection; authentication/transport, other permission paths and uncertain deletion
outcomes still require installed acceptance.

The account-B phase then atomically removes write permission from the live
fixture registry. A Workweek task save must show the permission error, preserve
the enabled draft fields, leave the dialog open, and leave the server record
unchanged. Deleting an empty task and empty project through Mylyn must show the
same permission rejection and restore their projections without changing
server revision. After write permission returns, another reconciliation must
retain those records until the user explicitly deletes them; the explicit task
and project deletions must then succeed. The unchanged fixture keyring/account
and closed local database are checked throughout.

The same retained account-B runtime also exercises token revocation and
optimistic task-editor conflicts. Revocation leaves the draft and enabled
summary/URL fields intact while marking the connection unavailable; local
storage stays closed even in that failure state. Restoring authorization lets
the same still-open editor save once. A second independent HTTP client changes
another task while its editor has a draft. Rejected stale saves must preserve
both the draft and competing record. A cache refresh must not upgrade the
opened editor's captured version; a repeat save still conflicts. Cancel/reopen
loads the competing values and permits an explicitly reviewed save. Stale
record editors now show the matching close/reopen instruction. Temporary tasks
are removed and prior data/history remain unchanged.

Offline acceptance stops the actual packaged-server process while a native
task draft remains open. The failed save preserves its enabled fields and
error; local storage stays closed. A Mylyn deletion made while disconnected
remains pending. Restart uses the same server database/port and records a new
recovery PID separately from the Eclipse-fork PIDs. The server retains the
original task, projects, labels and active interval before retry; automatic
reconnect may already complete the explicitly queued deletion. The same editor
then saves once and the pending deletion completes.
A subsequent real Task List edit must still synchronize with one version
increment.

The failure scenario exposed Mylyn importing unchanged old projection fields
over a newly saved native task. Projections now retain scoped state hashes and
expected versions (task attributes and project preferences). Unchanged source
fields do not overwrite newer backend data; actual Mylyn edits use the version
they were projected from. The restart fixture edits the second task on the
server before Eclipse starts, then requires the original saved Task List handle
and unchanged new server version. This proves the baseline survives restart.

Editor error checks inspect the editor's own visible message and enabled Save
button. They do not depend on the mutable shared backend status; the fixture
records when another job changes that status during rejection.

The final account-B phase source-launches a disposable loopback reply gate in
a separate Java process. The real server moves behind it while Eclipse retains
its configured endpoint. The gate forwards one PUT, observes the real successful
response, then withholds that response. One scenario reaches the normal client
request timeout; another cancels the actual Eclipse save Job after confirmed
commit. Both keep the editor open with enabled unchanged draft fields and the
appropriate error, while the server retains exactly one committed update. After
refresh, repeating Save from the original editor must conflict rather than
upgrade its expected version or overwrite the committed record. The fixtures
are then removed. No credentials/request/response bodies are logged. The gate
source is packaged only in the acceptance-test bundle, and both child processes
are stopped on exit.

Mapped tasks/projects that disappear between synchronization reads are never
recreated automatically. Mylyn version adoption is permitted only when the
fresh backend editable-state hash still equals the projected baseline; this
handles legacy JPA project-renaming version side effects while preserving newer
changed backend fields. The existing Mylyn test bundle validates its normal
rename/CRUD/activation/connecting/scope workflows alongside remote acceptance.

The account-B runtime also uses a separately authorized owner-B HTTP session to
create tasks, labels and precise intervals, edit a task while its native editor
has a draft, and update/delete intervals. Eclipse must update its cached records,
Workweek rows and existing report totals without manual refresh. Notification
refresh cannot replace the open draft or upgrade its captured expected version;
Save still rejects the stale edit. Owner A receives none of those events. The
selected owner-B token is then revoked while the second session commits another
task. Restoring the selected token must recover that missed record automatically,
without replaying a command. Peer deletions must also remove cached records and
Mylyn projections. Receipts record `phase.changes.owner-scope`,
`phase.changes.activity-report`, `phase.changes.live-draft` and
`phase.changes.reconnect`.

`remote-client-tests` includes both native-only and Mylyn modules. CI's
`Remote-Eclipse` job runs all nine installed phases and uploads reports and
credential-free receipts/logs. The fixture password, keyring and token registry
are excluded. The known upstream theme-color/Error Log issue #243 is still a
separate release gate; these functional tests do not certify a clean Error Log.

Four further JVMs retain the workspace through server A/account B, a second
server with the same account, embedded storage, server A again and embedded
storage again. All selections use the actual backend preference page and restart
notice. Applying settings must retain the running identity, task snapshot and
connection until restart. The second server and local database must not receive
copies of the first server's tasks. Workweek creates same-named native records
in each backend; Mylyn activation records time only against that backend's UUID
and owner. Foreign projections reject resolution. Returning to the first server
and local storage must reuse the saved projection handles and random markers,
record IDs and exact intervals. Both inactive servers retain their state.

The embedded phase deliberately opens and updates the existing local fixture.
Its new checksum is accepted only after successful embedded acceptance and must
stay unchanged when server mode is selected again. All remote phases keep the
app connection null; both isolated server processes stop on runtime exit. The
bootstrap honors persisted backend settings after its first initialization.
See [backend-switch acceptance](../baseline/REMOTE-BACKEND-SWITCH.md).

Still required in #220: remaining native label/report/CSV preference flows, and form preservation
through authentication/offline/timeout/cancellation/conflict failures, including
uncertain-command and failed-deletion outcomes. The Eclipse portion of #219 is
covered by the synchronization checkpoint; web synchronization and #221 web UI
remain required before 2.0. See [the checkpoint](../baseline/REMOTE-SYNCHRONIZATION.md)
for full-resynchronization tests and evidence limits.
