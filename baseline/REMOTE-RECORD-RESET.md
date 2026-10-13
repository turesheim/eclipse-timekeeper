# Remote record editors after resynchronization

Related: [#219](https://github.com/turesheim/eclipse-timekeeper/issues/219) and
[#220](https://github.com/turesheim/eclipse-timekeeper/issues/220).

## Reproduced failure and change

Full resynchronization correctly discards old opaque ETags. A previously open
native editor, however, retained only its original numeric version as the write
precondition. If the server's restored history reused that version for different
field values, fetching a new ETag could allow the old draft to overwrite the
changed record.

Existing project, task and activity dialogs now retain their original immutable
record and read the current remote record on the save worker before mutation.
Both version and fields must still match. Changed records return `STALE_VERSION`
and the existing close/reopen instruction; missing records return `NOT_FOUND`.
The dialog and draft remain open and enabled. Reopening loads the current values
and permits a reviewed save. The verifying read supplies the ETag for the
following conditional write, which still rejects a later racing change.

Create dialogs and embedded editing retain their existing behavior. This is a
pre-write check; it does not change acknowledged-save handling or repeat a
command after a lost response.

## Evidence and limits, 2026-10-13

`RemoteChangeJobTest.sameVersionResetPreservesNativeDraftUntilReopenedAndReviewed`
uses a real HTTP socket fixture, Eclipse Display/Jobs and the actual native task
dialog. The fixture restores its revision from two to one, retains task version
two, changes the task fields and supplies a new opaque ETag. Before the fix, the
test observed one unreviewed PUT and failed its zero-write assertion. After the
fix, Save shows the stale instruction, keeps the enabled draft, and sends no
update. Cancel/reopen displays the restored values; an explicitly reviewed save
then sends one update with the new ETag.

The test temporarily substitutes only the isolated test runtime's UI backend,
then closes its dialog and restores the original backend in `finally`. It does
not select a user workspace or keyring. This models the HTTP behavior of a
restored history; it does not prove restoration of an actual server backup.
The dedicated same-version UI case covers the task dialog. Project/activity
dialogs use the shared save path; their remaining failure paths still belong to
the #220 acceptance work.

The focused Job reactor passed all five Eclipse Job tests, including the
existing acknowledgement, cancellation and configuration checks. The final
selected clean reactor passed **113 main tests plus seven separate restart/
account/switch tests**, with no failures, errors or skips. All nine installed
phases retain the packaged-server, account, draft, synchronization, server/
embedded switch and restart checks. All previous receipt markers passed. The
packaged Server ZIP SHA-256 is
`739e932b5196620eaa9175243593564b03edf109b37d63970b6e77de1f33b9eb`.
Shared module counts across reactors overlap and are not summed. This new commit
still requires its own full CI verification.

The first CI Linux run failed waiting for polling when a previous native test
left Workweek open. Its report loads were unsupported by the small socket
fixture and made that temporary backend unavailable. The fixture now serves
empty activity pages/reports, and the reset test explicitly opens Workweek and
restores its earlier visibility/cache afterward. All five focused Job tests
passed with that state. The broad local macOS UI run had dialog-activation
timeouts, so it does not certify the full native suite; Linux CI must recheck
the corrected fixture.

Parent `81e205a` passed all seven Build jobs in
[run 38316380367](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38316380367)
and Server containers verification in
[run 38316380282](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38316380282).
Downloaded reports confirm all nine installed phases and five backend-switch
markers, with fixture secrets excluded from the artifact.

## Remaining release work

#220 remains open for native label/report/CSV preference flows, inline and other
editor failures, live owner-change/binding acceptance and uncertain create/
start/stop/import/delete outcomes. The credential-switching tests do not prove
same-token owner remapping. #221 web workflows, web/Eclipse synchronization,
Mylyn Error Log #243, final tagged artifacts and public p2 installation still
apply before 2.0.
