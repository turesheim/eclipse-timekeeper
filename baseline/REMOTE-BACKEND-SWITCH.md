# Installed backend-switch acceptance

Issue: [#220](https://github.com/turesheim/eclipse-timekeeper/issues/220).
This extends the remote Eclipse client and synchronization acceptance. It does
not clear the remaining native preference/editor workflows or the 2.0 release.

## Scenario

The optional Mylyn fixture now retains one installed Eclipse workspace through
seven separate JVMs. The first three still verify remote activation, restart,
account switching, failures and peer synchronization. The next four follow the
actual backend preference page through:

1. Server A/account B to a second server using the same account.
2. The second server to the existing local fixture database.
3. Local storage back to server A/account B.
4. Server A back to the local database again.

Both servers are independently launched from the checksum-verified packaged
Quarkus distribution, with distinct loopback ports and database files. All data,
credentials and the workspace belong to the disposable acceptance fixture.
Every runtime starts both servers so their retained data can be checked while
the other backend is selected. The native-only fixture retains its two phases
and does not launch the second server.

Applying a mode or endpoint change must show the restart notice while preserving
the running backend identity, task snapshot and actual embedded connection
object. Server URL/token controls enable only in server mode; the token remains
encrypted and its input uses the password style. After restarting, the actual
installed client must use the mode and URL saved by the preference page. The
bootstrap initializes backend preferences only in the first phase and then
honors their persisted values.

The new server and local database must initially have no tasks copied from the
previous backend. Native Workweek controls create projects/tasks with the same
visible names in each backend. Actual Mylyn activation/deactivation must record
time against that backend's own task UUID and owner. Foreign saved projections
must reject resolution and remain excluded from the selected backend.

Each backend's projection receives a random test marker, saved through Mylyn's
official externalization operation. Returning to server A and to local storage
must reconnect the original handle and marker, resource UUIDs and exact recorded
intervals. Recreating an unmarked projection cannot satisfy those checks. Owned
metadata revisions/state and interval state are hashed in the credential-free
receipt, then compared across subsequent launches. This checks that switching
does not transfer records or alter the inactive backends.

The local fixture's initial checksum stays unchanged throughout the remote-only
phases. The deliberate embedded phase then opens that database and creates
local records. The next remote phase accepts the resulting checksum only after
the embedded acceptance marker has passed, records it separately, and requires
the app connection to be null with that file unchanged again. Local record and
interval assertions prove persistence on the final embedded restart.

## Evidence

Verified locally on October 13, 2026, with Temurin 21.0.12.1 / macOS aarch64:
**108 main tests plus seven separate restart/account/switch tests passed**,
with no failures, errors or skips. This includes all nine installed phase
reports: two native-only and seven optional-Mylyn phases. All previous receipt
markers and all five backend-switch markers passed. Both server fixtures record
seven distinct process IDs. Server ZIP SHA-256:
`739e932b5196620eaa9175243593564b03edf109b37d63970b6e77de1f33b9eb`.

The selected reactor runs shared domain, HTTP and database tests; counts across
separate reactors overlap and are not summed. The local task-state checkpoint
is captured after activation/stop, because JPA updates the task version through
its activity relationship. Restart must preserve that acknowledged final state.

The new reports are retained in
`surefire-reports-server`, `surefire-reports-local`, `surefire-reports-return`
and `surefire-reports-local-restart`. CI uploads these alongside the existing
reports and credential-free receipts/logs. Keyrings, password files and token
registries remain excluded.

The parent synchronization commit `f3fbf3c` passed all seven Build jobs in
[run 38313548997](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38313548997)
and Server containers verification in
[run 38313549045](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38313549045).
Its downloaded installed evidence confirms all five phases and the four
peer-synchronization markers. These are parent-commit results; CI must verify
the new switching commit separately. That verification subsequently passed:
all seven Build jobs for `81e205a` in
[run 38316380367](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38316380367)
and Server containers verification in
[run 38316380282](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38316380282).
Downloaded reports confirm all nine installed phases and five backend-switch
markers, with fixture secrets excluded from the artifact.

## Remaining release acceptance

Native label/report/CSV preference flows and other form/uncertain-operation
failure paths remain in #220. #221 web workflows, bidirectional web/Eclipse
synchronization in #219, the separate Mylyn Error Log gate #243, final tagged
artifacts and public GitHub p2 installation remain required before 2.0.
