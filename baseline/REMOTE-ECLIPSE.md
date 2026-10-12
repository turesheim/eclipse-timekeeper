# Remote Eclipse client acceptance checkpoint

Issue: [#220](https://github.com/turesheim/eclipse-timekeeper/issues/220).
This is implementation work in progress; it does not clear the 2.0 release gate.

The HTTP module implements the domain service over `/api/v1` using Java 21's HTTP
client and explicit JSON mapping. Eclipse uses immutable UI snapshots and Jobs
for remote operations. Backend preferences select embedded or remote mode at
restart and store the token in Equinox encrypted storage. Invalid remote
configuration keeps that backend selected and reports an error.

Project, task, activity, label and tracking operations have been routed through
the selected service. Report template export fetches the service on a worker
instead of exporting an empty loading snapshot. A save acknowledged by the
server remains successful if fetching the following snapshot fails. Record
forms remain open on failure and freeze their inputs while a save is pending.

## Local evidence, 2026-10-12

Java: Temurin 21.0.12.1; host: macOS aarch64. Commands use `./mvnw` and clean
Maven settings, with no change to the user's Eclipse workspace or credentials.

- `./mvnw -f server/pom.xml verify`: **45 tests**, no failures, errors or skips.
  These include actual Quarkus HTTP integration for owned CRUD, external
  references, atomic tracking, reports, authentication and stale versions, plus
  **seven** socket tests for timeout, cancellation, refused connection, missing
  token, safe invalid responses and rejection of redirects.
- `./mvnw -Pjira-connector-tests verify -Dtycho.localArtifacts=ignore`:
  **148 tests**, no failures, errors or skips, including native Eclipse without
  Mylyn, optional Mylyn and Jira connector regression coverage. The first run
  failed to locate the activity dialog's Save button after taking a screenshot;
  scoping that lookup to the dialog made the rerun pass.
- A subsequent native Eclipse test run selected `BackendJobTest`: **three new
  tests**, no failures, errors or skips. They exercise a real Display and Job
  runtime for a responsive UI during a waiting operation, cancellation,
  preservation of an acknowledged save after refresh failure, and prevention
  of embedded fallback when remote configuration is unavailable. These are
  dispatch tests; they do not replace installed remote-client acceptance.

The server and root-reactor counts overlap for shared modules; do not add them
together as unique tests. The three Job tests were run separately after the
full regression run.

The first draft commit `c6942454a02050db25aef8c2c28bede5b9da39a1` passed all six
jobs in [CI run 38266819210](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38266819210).
The retained server evidence has 45 Java tests and 12 packaged HTTP contract
cases, including normal/forced restart, revocation, long polling and readiness.

## CSV implementation checkpoint, 2026-10-12

Server-mode import/export preferences now use a domain CSV transfer on workers,
with local storage controls disabled. The new format preserves the complete
domain graph and precision; embedded mode retains its original H2 CSV format.
See [the HTTP client README](../timekeeper-http-client/README.md) for file layout
and retry behavior. Selecting another backend does not trigger a transfer.

An unresolved import retains a key scoped by backend, account and payload in a
workspace journal. A lost HTTP reply followed by a new client instance reuses
the key; definite failures and acknowledged imports clear it. No tokens or
records enter the journal. Local file/rendering failures no longer mark a
healthy server connection unavailable.

- Server reactor: **52 tests**, no failures, errors or skips. Actual Quarkus
  integration checks imported hierarchy, labels, provider references, exact
  instants, fresh owner-scoped IDs, and complete rollback/revision preservation
  when importing an already-linked provider reference.
- Full Eclipse reactor with `jira-connector-tests clean verify`: **157 tests**,
  no failures, errors or skips. A preceding incremental run encountered stale
  Jira p2 installation qualifiers; the clean test installation passed.
- These tests include six additional CSV/journal/HTTP-retry cases. The new
  client-instance retry test uses actual sockets and the persisted key; an
  installed Eclipse restart remains part of the acceptance below.

Multi-platform CI must be rechecked for the CSV commit.

The CSV commit `d7b442d8784e43dad59a8295f90181f2ad60b871` passed all six jobs in
[CI run 38269524428](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38269524428).

## Mylyn scope implementation checkpoint, 2026-10-12

Task UUID mappings and project/category mappings are scoped by the selected
backend and account. Native Task List projections retain their origin scope;
foreign projections are excluded from synchronization and cannot be resolved
for activation. Late deactivation of a foreign projection is ignored. Shared
provider items retain separate UUID mappings for each scope.

The embedded identity is captured from the database address actually opened,
instead of pending settings. Remote endpoint/account components are encoded
separately before hashing, so separator characters cannot alias another scope.
Persisted scope metadata contains hashes and resource/category identifiers,
without tokens. Legacy unscoped mappings are accepted once in local mode;
missing UUIDs cannot cause an old projection to create a new backend record.
Owned stale projections/categories are removed when their records disappear.

Mylyn dirty flags use atomic claims so UI signals arriving during a background
operation are retained. Remote synchronization failures reach the selected
backend's error state and Workweek status instead of being treated as a
successful save.

`MylynBackendScopeTest` uses a real Mylyn Task List and two independent JPA
databases. It switches owner and server scopes, checks separate categories with
the same name, checks identical UUIDs on different servers, processes mixed
foreign/owned edit and deletion batches, and removes stale projections without
recreating their records. It also checks that the actual adapter rejects a
foreign native projection before creating records. The test injects scope
identities and services; it does **not** prove installed remote authentication
or backend restart behavior.

The full clean Eclipse/Mylyn/Jira regression command passed **158 tests** with
no failures, errors or skips. CI must be rechecked for the scope commit. The
server reactor remains covered by the 52-test CSV checkpoint; scope changes do
not change the standalone server implementation.

## Installed native acceptance checkpoint, 2026-10-12

`net.resheim.eclipse.timekeeper.remote-test` now provisions Eclipse Platform and
the Timekeeper base feature using `p2Installed`, without Mylyn. Its bootstrap
checks the isolated keyring/password arguments, stores an encrypted fixture
token, selects remote mode before Timekeeper startup, and launches the complete
checksum-verified server ZIP. It then delegates to the actual IDE application.

The two phases use distinct Eclipse and server JVMs. Installed Workweek controls
create project/task records, edit the task, start/stop tracking, and edit a
precise activity. A rejected interval update keeps the dialog and draft open
and leaves the server record unchanged. CSV transfer uses the selected client's
remote wrapper; the native folder chooser and preference-page transfer buttons
are not automated by this test.

The restart phase decrypts the stored token and verifies the same project/task/
activity IDs and exact instants. Both phases assert that the app's entity
manager remains null, local database readiness stays false, and the valid local
fixture file retains its original SHA-256. The local database is explicitly
prepared and closed by the fixture before UI launch; it is not the app's
connection. No user IDE, keyring, credentials or database are selected.

Local full command with `jira-connector-tests,remote-client-tests clean verify`:
**159 main tests and two separate restart tests**, no failures, errors or skips.
The two restart reports cover Jira and the new remote runtime respectively.
The remote receipt records server PIDs `59420,59442`, distribution SHA-256
`bb0a9b5d12a3ea4527d87d087db8d7ad13ae1c40b43a90e0eef4a5be51762ad8`, both
phases passed, and unchanged local file SHA-256
`b45159cf74e5e89b0a41447adbd14abf803affa0df4be7cc6ba71818fa9486ea`.

CI's new `Remote-Eclipse` job consumes the Server job's distribution and runs
the installed phases under Xvfb. It uploads credential-free receipts/reports
and runtime logs, excluding the fixture password, keyring and token registry.
The new job must pass on the acceptance commit before this evidence is used as
CI acceptance. See [the test README](../net.resheim.eclipse.timekeeper.remote-test/README.md).

## Installed Mylyn acceptance checkpoint, 2026-10-12

The new `remote-mylyn-test` module provisions the official optional Mylyn
feature and uses the same isolated, checksum-verified packaged-server
bootstrap. Real Task List/Task Activity Manager activation events start, switch
and stop the linked remote UUIDs. A late deactivation is followed by a queued
observation and must leave the successor unchanged.

The first phase marks the actual Task List element, waits for Mylyn's official
externalization/save operation and persistent preferences, then exits. The
restart phase retains the workspace and requires that marker on the reconnected
projection. It reuses the original remote task UUID, creates a new interval,
and verifies both earlier intervals remain unchanged. Recreating a new Task
List element cannot satisfy the marker assertion. Both phases retain the null
app entity manager and unchanged original local-file checksum assertions.

An initial test run revealed that Tycho clears its OSGi data directory before
each execution by default. Restart executions now explicitly retain that
workspace; this models an actual restart rather than a new workspace. The
first-phase save is awaited before the test harness terminates the process.

Local full command with `jira-connector-tests,remote-client-tests clean verify`:
**160 main tests and three separate restart tests**, no failures, errors or
skips. The Mylyn receipt records server PIDs `61367,61373`, both Mylyn phases
passed, the preserved projection marker, original remote IDs, and unchanged
local file SHA-256
`88ce3969ff579e515bfc38569f12c3dc984ebf668d6c039c02b0811b4e6e02bb`.
It uses the same server ZIP SHA-256 as the earlier native checkpoint.

The CI job now includes both remote modules and their reports/receipts, while
excluding private keyring/password/token files. The `7060c6f` Mylyn acceptance
commit passed all seven jobs in
[run 38283968133](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38283968133).
The downloaded installed reports confirm both native phases and both Mylyn
phases passed with no failures, errors or skips. Mylyn's CI receipt records
server PIDs `4268,4407`; the native receipt records `3966,4121`.
These functional checks do not clear the separate Mylyn theme/Error Log gate
in #243. See [the Mylyn test README](../net.resheim.eclipse.timekeeper.remote-mylyn-test/README.md).

## Installed account-switch acceptance checkpoint, 2026-10-12

The Mylyn restart phase now opens the real backend preference page and saves
account B's token using the password field and **Apply and Close**. It verifies
the page persisted an encrypted credential and displayed the restart notice;
the running client remains account A until restart. The fixture uses two fixed
owner/hash entries, so the second account cannot be simulated by remapping the
original owner to a different token.

A third Eclipse/server process retains the same workspace and must authenticate
as `remote-owner-b`. Its initial projects/tasks/labels and active interval are
empty. The account A projection retains its saved marker but cannot resolve in
B. Account B creates records with the same visible names, receives a distinct
projection and remote task UUID, and starts/stops through the actual Mylyn
activity manager. A late account A deactivation must leave B's interval
unchanged. A's metadata revision and both original intervals remain identical.
All phases keep the app entity manager null and local database checksum
unchanged. This covers switching accounts on one endpoint; it does not yet
cover changing the server endpoint or switching embedded/remote mode.

The selected clean reactor (`remote-client-tests` with the CI module list)
passed **105 main tests, two restart tests and one account-switch test**, with
no failures/errors/skips. The Mylyn receipt records PIDs `63148,63157,63161`,
all four phase markers passed, account A revision `9`, and preserved local
SHA-256 `6e98ac48606e2b6ab30d5ee6db2b718987e7007a92b7b506517875b1e7bcd6ae`.
The server ZIP checksum is unchanged from the preceding local checkpoint.
The CI artifact list now includes the third execution's account report and
continues to exclude credentials. The account-switch commit `abf2dfb` passed
all seven jobs in [run 38286650257](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38286650257),
including the full Linux/Xvfb regression and installed-Eclipse job.

A subsequent broader local macOS run failed in existing native tests on
window-activation timeouts; it is retained as a failed run, and the remaining
modules were skipped. The remote dialogs now address controls in their own
shells rather than requiring OS focus. The native remote scenario also closes
Workweek before invoking both refresh entry points, covering a disposed-view
exception observed when a modal editor returned during test shutdown. The
production refresh entry point safely returns after its viewer is disposed.
The account-switch commit subsequently passed the full Linux/Xvfb regression
and all other CI jobs in run 38286650257.

## Installed rejected-deletion checkpoint, 2026-10-12

The installed account-B phase now deletes a projected task with recorded time
and its nonempty project category using the actual Mylyn Task List. Before the
fix, the test failed waiting for the deletion-error dialog; the installed Error
Log showed `RemoteFailure` from `deleteTask` and synchronization retrying. The
server conflict had escaped a handler that only caught local service failures.

The synchronizer now treats remote `CONFLICT` as a rejected deletion, restores
the hierarchy from the selected service before importing Mylyn changes, and
removes that rejected command from the pending set. Other transport failures
continue to propagate and retain pending deletion intent for reconciliation.
The Mylyn bundle explicitly declares the HTTP-client dependency used for the
typed failure check.

After the fix, the same installed test passes both task and project rejection,
requires the original remote UUIDs/hierarchy, and compares server metadata
revision and interval content before/after. A subsequent empty-task deletion
must succeed and disappear from both the server and Task List; protected
records/history and the foreign account remain unchanged.

The selected clean reactor passed **105 main tests, two restart tests and one
account/deletion test**, without failures/errors/skips. The receipt records
server PIDs `63795,63801,63806`, all five Mylyn phase markers passed, and preserved
local SHA-256 `b8efa78553b602433e5f7eb0c38d2cfea84a732e738ffe41ce2bf23864d99dc2`.
The same checksum-verified packaged server was used for the failing and passing
runs. The conflict fix `046e3d2` passed all seven jobs in
[run 38287925395](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38287925395).
Downloaded reports confirm all five installed phases. The native CI receipt
records server PIDs `4024,4185`; the Mylyn receipt records `4335,4477,4618` and
the additional deletion phase passed. The preceding `abf2dfb` commit's full
Linux regression and separate installed-Eclipse job both passed, along with
all other jobs in run 38286650257.

This covers definitive server-conflict rejection and later valid deletion.
Permission/authentication/offline/timeout/cancellation/unknown deletion outcomes
remain open; they are not inferred from this test.

## Installed read-only acceptance checkpoint, 2026-10-12

The live fixture token registry can now atomically remove account B's write
permission without changing its token, owner or endpoint. Before the fix,
Mylyn task deletion failed waiting for the error/restoration dialog and logged
`RemoteFailure` with the permission-denied message. HTTP `FORBIDDEN` now follows
the rejected-deletion path, alongside `CONFLICT`; unknown transport failures
still propagate and keep pending intent for reconciliation.

The installed account-B phase covers a Workweek task editor save with only read
permission: the dialog stays open, fields become enabled again, the user's draft
is unchanged, the permission error is visibly displayed, and the server task
is identical to its pre-save value. The test then cancels that draft.
The editor message is inspected in its actual SWT Text control, normalizing
JFace's leading presentation whitespace.

Mylyn deletion of an empty task and an empty project must show the specific
permission error and restore both projections without changing server metadata
revision. After write permission returns, a new backend task forces another
reconciliation; the previously rejected records must still exist. Only explicit
subsequent task/project deletions may remove them, and those deletions must
succeed. All phases keep encrypted credentials, account separation, null app
entity manager and unchanged original local database file.

The selected clean reactor passed **105 main tests, two restart tests and one
account/deletion/permission test**, with no failures/errors/skips. The receipt
records PIDs `65482,65487,65492`, all Mylyn phase markers passed, and preserved
local SHA-256 `4cfb8d6981b953219bc2f559f839a183dd75b79670de0d2c759bfd58ab7981b0`.
The same packaged server ZIP was used in the before/after runs. The preceding
conflict fix passed all seven CI jobs and its downloaded installed reports;
The read-only extension `a6395e6` passed all seven jobs in
[run 38290019269](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38290019269).
Its downloaded reports confirm all five installed phases and the permission
marker.

This covers permission denial in the native task editor and Mylyn task/project
deletions. Other editor/operation permission paths, authentication failures and
unknown transport outcomes remain open.

## Installed authentication/conflict checkpoint, 2026-10-12

The account-B installed phase now exercises two more task-editor failure
paths using the real packaged server. Revoking B's token in the fixture registry
causes HTTP authentication failure while A remains authorized. The task editor
must stay open with its summary/URL draft and enabled fields, display the sign-in
message and mark the connection unavailable. The app entity manager remains
null, local readiness false and the original local file checksum unchanged.
After authorization is restored, server metadata and the original task must
still be identical. Saving the same still-open draft then succeeds once with
exactly one version increment and reaches the original Mylyn projection.

A second independent HTTP client updates another task while its native editor
has an unsaved draft. Saving the old version must leave both draft fields and
the competing server record unchanged. Refreshing the cache while that editor
remains open must preserve the draft and must not upgrade its captured expected
version: repeating Save still conflicts. Cancel/reopen loads the competing
version; an explicitly reviewed save then increments that version exactly once.
Both temporary fixture tasks are removed, while prior account data/history and
local storage remain unchanged.

Stale-version errors in record editors now explain that the editor must be
closed and reopened before saving. This mapping applies specifically to remote
`CONFLICT` with `STALE_VERSION`; other conflict types keep their existing
messages. The generic transport/Workweek status message is unchanged.

The selected clean reactor passed **105 main tests, two restart tests and one
account/failure test**, without failures/errors/skips. The receipt records PIDs
`67109,67120,67126`, authentication and conflict markers passed, and preserved
local SHA-256 `df4d438f1993c4c6e636057fb16cfb60506034303208c8255b39493012ff9f43`. The same checksum-verified packaged
server was used. The `ac3ce33` extension's Remote-Eclipse job failed in
[run 38291647914](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38291647914),
while the other six jobs passed. The downloaded report timed out on a rejected
save check that depended on mutable shared backend status. This failure remains
recorded; the next checkpoint corrects that test condition and adds real
offline/restart coverage.

These checks cover token revocation/recovery and optimistic task-editor
conflicts through manual refresh. Other editors, offline/timeout/cancellation,
unknown commands and automatic concurrent-client notification remain open.

## Offline recovery and durable projection checkpoint, 2026-10-12

The installed account-B task scenario stops the actual packaged server while an
editor has an unsaved summary/URL draft. The failed save must keep its fields
enabled, its draft unchanged and its own error visible, with remote readiness
false and local entity manager/readiness/file unchanged. A separate Mylyn task
is deleted while disconnected. A new server process uses the same data and
port; server metadata and both tasks must be unchanged before the retry. The
same still-open editor then saves with one version increment, the pending
Mylyn deletion completes, and a subsequent actual Task List edit still
synchronizes with exactly one additional version increment.

This scenario exposed a real lost-update bug. The native draft was committed
as version 1, but reconciliation of the unrelated Mylyn deletion imported the
unchanged old projection fields back as version 2. A read-only inspection of
only the stopped disposable test database confirmed the old summary/URL in the
version-2 record. No user database was read or changed.

Task projections now persist their last exported editable-state hash and
expected version in attributes scoped by backend/account. Project categories
persist the equivalent hash/version in scoped preferences. Unchanged projection
fields yield to newer backend data; real Mylyn changes use their captured
expected version instead of adopting the version just fetched from the backend.
The restart bootstrap changes the second server task while Eclipse is stopped.
The next launch must retain its original Task List handle and display that new
server value without incrementing its version again. The installed restart and
post-reconnect Mylyn-edit checks both pass.

The rejected-save wait now checks the editor's own visible error and enabled
Save button. The receipt's `test.task.shared-status-drift=observed` confirms that
another job changed shared backend status while the editor's error remained
available. Draft/value/version assertions remain intact. Workweek also guards
project/task selection after a modal editor returns during view shutdown;
missing saved records or disposed controls are not selected.

The final selected clean reactor passed **105 main tests, two restart tests and
one account/failure test**, with no failures/errors/skips. The receipt records
Eclipse-fork server PIDs `72732,72738,72745`, recovery PID
`72757`, persisted-baseline/offline/authentication/conflict
markers passed, and unchanged local SHA-256 `9881fed149073869cf30f91e67dbe21cad7be2c896679e38237087bee866d377`.
The packaged server ZIP checksum is unchanged. CI for this corrective commit
is pending, including the full optional Mylyn regression.

This covers task-editor offline recovery, pending empty-task deletion,
between-launch server edits and real Mylyn edits afterward. Other editors,
timeout/cancellation and unknown-command outcomes remain open; automatic
notification and broader reconnect/missed-event behavior remain in #219.

## Remaining before #220 can be completed

- Verify the server CSV preference-page flow in the installed Eclipse runtime,
  including cancellation, restart after a lost response and unchanged local
  database files.
- Verify the scoped Mylyn mappings in the installed remote runtime, including
  server-endpoint and embedded/remote switches. Account switching and late
  activation/deactivation events are covered above.
- Exercise remaining native label/report/preference UI flows. Installed native and Mylyn editing/tracking,
  encrypted-token reading and marker-preserving restart are covered above.
- Repeat closed-local-storage and form-draft checks through authentication,
  offline, timeout, cancellation and optimistic-conflict failures. The normal
  and rejected-validation installed paths are covered above. Native task
  permission/token-revocation/conflict paths and recovery are also covered;
  the other editor/operation paths remain open.
- Complete uncertain-command and form-draft acceptance for the installed
  client, including uncertain/authentication/permission/transport deletion
  outcomes. Definitive conflict-rejected task/project deletions and subsequent
  valid deletions are covered above; retain the atomic import and owner-scoped
  retry evidence.

Concurrent client notifications and reconnect acceptance remain required in
[#219](https://github.com/turesheim/eclipse-timekeeper/issues/219); the web client
remains required in [#221](https://github.com/turesheim/eclipse-timekeeper/issues/221).
