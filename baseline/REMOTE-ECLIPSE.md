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
excluding private keyring/password/token files. CI must be verified on the
Mylyn acceptance commit. The preceding `c9fbd45` commit passed all seven jobs in
[run 38281235190](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38281235190),
including the repaired Windows test and installed native acceptance.
These functional checks do not clear the separate Mylyn theme/Error Log gate
in #243. See [the Mylyn test README](../net.resheim.eclipse.timekeeper.remote-mylyn-test/README.md).

## Remaining before #220 can be completed

- Verify the server CSV preference-page flow in the installed Eclipse runtime,
  including cancellation, restart after a lost response and unchanged local
  database files.
- Verify the scoped Mylyn mappings in the installed remote runtime, including
  actual backend/account switches and late activation/deactivation events.
- Exercise remaining native label/report/preference UI flows and token-setting
  writer/account-switch acceptance. Installed native and Mylyn editing/tracking,
  encrypted-token reading and marker-preserving restart are covered above.
- Repeat closed-local-storage and form-draft checks through authentication,
  offline, timeout, cancellation and optimistic-conflict failures. The normal
  and rejected-validation installed paths are covered above.
- Complete uncertain-command and form-draft acceptance for the installed
  client; retain the atomic import and owner-scoped retry evidence above.

Concurrent client notifications and reconnect acceptance remain required in
[#219](https://github.com/turesheim/eclipse-timekeeper/issues/219); the web client
remains required in [#221](https://github.com/turesheim/eclipse-timekeeper/issues/221).
