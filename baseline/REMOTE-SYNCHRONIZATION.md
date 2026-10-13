# Eclipse change synchronization acceptance

Issue: [#219](https://github.com/turesheim/eclipse-timekeeper/issues/219).
This implements the Eclipse portion; web-client synchronization depends on #221
and remains required before the 2.0 release.

## Behavior

The remote backend watches the existing owner-scoped `/api/v1/changes` endpoint
in a cancellable system Job, using five-second long polls. The watcher does not
hold the scheduling rule used by saves and reads. Refresh jobs share that rule,
coalesce pending notifications and reload immutable metadata plus already loaded
activity reports. The next poll starts from the revision of that complete
metadata snapshot, so an incomplete read cannot advance the notification cursor.
If a report's selected task or project has disappeared from the owned snapshot,
that report becomes empty; a now-invalid report filter cannot block updates to
the other records and views.

Refresh preserves open editor drafts and their original expected versions.
Inline Workweek editing already defers view refresh until editing ends. Failed
authorization or transport marks the remote connection unavailable while keeping
local storage closed. Reconnection retries full reads with a bounded exponential
delay of one to thirty seconds and never replays an acknowledged command.

`RESYNC_REQUIRED`, or an empty change page indicating unavailable history,
requests full resynchronization. That serialized refresh discards old opaque
ETags before loading the current server snapshot. A reset arriving during an
existing refresh schedules another reset instead of being lost. Closing the
backend interrupts polling and cancels queued work. Embedded mode has no watcher.

## Validation, 2026-10-12

Host: macOS aarch64, Temurin 21.0.12.1. All Eclipse runtimes use private workspaces;
the installed fixtures use their own encrypted keyring and packaged server.

The final selected reactor passed **112 main tests, two restart tests and one
account/synchronization test**, with no failures, errors or skips. The five
installed phases and all four new synchronization receipt markers passed. Server
ZIP SHA-256: `739e932b5196620eaa9175243593564b03edf109b37d63970b6e77de1f33b9eb`,
from the verified Server artifact in run `38299034375`, head `8e803fa`.

- `HttpTransportTest` validates ordered change cursors, missing-history signals,
  safe rejection of malformed pages and dropping pre-reset ETags. The last case
  models a restored task version through a socket fixture.
- `RemoteChangeJobTest` uses real HTTP and Eclipse Jobs to return
  `RESYNC_REQUIRED` after restoring a fixture's revision and task version from
  two to one. Without manual refresh, the UI snapshot must show version one;
  saving a reviewed edit must use its new ETag and commit once. The same test
  checks Display responsiveness during long polling and cancellation on close.
  This proves the client reset path, not restoration of an actual server backup.
- Installed native and optional Mylyn phases run against the actual packaged
  Quarkus server. An independently authorized session creates/edits/deletes
  owner-B tasks, labels and precise activities. Existing cached reports must
  update their records and exact totals, and Workweek must render peer changes
  automatically. A live task notification leaves the native editor's draft
  intact and its Save stale. Owner A's event cursor stays unchanged. Revoking
  the selected B token, committing from the second B session and restoring
  authorization must recover the missed record without manual refresh.
- The installed report scenario initially reproduced stalled refresh after a
  peer deleted a task selected by an already loaded report. The server correctly
  rejected that missing task filter; the client kept retrying the report instead
  of publishing its new metadata. Reload now resolves such owned missing filters
  to empty reports. The regression retains the loaded filter through deletion
  and requires both metadata removal and an empty report.
- Credential-free receipts retain all previous remote acceptance markers and
  add owner scope, activity report, live draft and reconnect markers. Every
  installed phase checks the null embedded entity manager and unchanged local
  fixture database checksum. Fixture secrets are excluded from CI artifacts.

The selected installed reactor is run with:

```sh
./mvnw -B -ntp -s /private/tmp/timekeeper-clean-settings.xml clean verify \
  -Premote-client-tests \
  -pl .,timekeeper-domain,timekeeper-service-api,timekeeper-http-client,net.resheim.eclipse.timekeeper.db,net.resheim.eclipse.timekeeper.ui,net.resheim.eclipse.timekeeper-feature,net.resheim.eclipse.timekeeper.mylyn,net.resheim.eclipse.timekeeper.mylyn-feature,net.resheim.eclipse.timekeeper.native-test,net.resheim.eclipse.timekeeper.remote-test,net.resheim.eclipse.timekeeper.remote-mylyn-test \
  '-Dui.test.include=**/*JobTest.java' \
  -Dtimekeeper.remote.distribution=/absolute/path/timekeeper-server.zip \
  -Dtycho.localArtifacts=ignore
```

The separate targeted Mylyn command selected `**/Mylyn*Test.java` and passed all
six adapter/scope tests, including project/task renaming and competing-edit
protection. The four Eclipse Job tests also passed separately. Shared domain,
HTTP and database tests run in each reactor and are not unique additional tests.

A broader local Mylyn UI run failed while waiting for native dialogs to become
active and eventually hit its 180-second process timeout. Its completed
adapter/scope/Jira classes passed, but that run is not acceptance evidence for
the full suite. Full Linux/Xvfb regression and all installed phases must pass in
CI on the synchronization commit. All seven Build jobs for `f3fbf3c` subsequently
passed in [run 38313548997](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38313548997),
including the complete Linux/Xvfb regression and all installed phases. Server
containers verification also passed in
[run 38313549045](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38313549045).
Downloaded installed reports confirm the five phases and four synchronization
markers; secrets are excluded from the artifact. The base remote-client commit `5707be6`
already passed all seven Build jobs in
[run 38300177616](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38300177616).

## Remaining release work

Backend-switch acceptance continues in [REMOTE-BACKEND-SWITCH.md](REMOTE-BACKEND-SWITCH.md).
The new code does not clear the remaining #220 native preference-flow and
uncertain-operation acceptance. Web workflows and web
synchronization are still missing in #221/#219. Mylyn Error Log issue #243 is a
separate gate; these functional tests do not certify a clean Error Log.
