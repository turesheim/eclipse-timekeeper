# Installed remote Mylyn acceptance

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
are unchanged. Both fixture credentials are encrypted in the isolated keyring;
the registry contains only their hashes and fixed owner mappings.

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

`remote-client-tests` includes both native-only and Mylyn modules. CI's
`Remote-Eclipse` job runs all five installed phases and uploads reports and
credential-free receipts/logs. The fixture password, keyring and token registry
are excluded. The known upstream theme-color/Error Log issue #243 is still a
separate release gate; these functional tests do not certify a clean Error Log.

Still required in #220: switching server endpoints and local/remote backends,
remaining native label/report/CSV preference flows, and form preservation
through authentication/offline/timeout/cancellation/conflict failures, including
uncertain-command and failed-deletion outcomes. #219 notification/reconnect and
#221 web UI remain required before 2.0.
