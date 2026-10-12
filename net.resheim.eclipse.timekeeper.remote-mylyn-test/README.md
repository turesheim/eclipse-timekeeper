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
