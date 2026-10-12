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

## Remaining before #220 can be completed

- Verify the server CSV preference-page flow in the installed Eclipse runtime,
  including cancellation, restart after a lost response and unchanged local
  database files.
- Scope Mylyn projection identities and category mappings by backend and owner;
  verify that changing backend never imports another backend's projections.
- Exercise the complete installed Eclipse flow against the actual packaged
  server, both without Mylyn and with Mylyn activation/deactivation, including
  secure storage, restart, remote edits, labels, reports and tracking.
- Prove the local database stays closed and existing local files stay unchanged
  in remote mode, including authentication, timeout, cancellation and conflict
  failures. Verify that form drafts survive each failure.
- Complete uncertain-command and form-draft acceptance for the installed
  client; retain the atomic import and owner-scoped retry evidence above.

Concurrent client notifications and reconnect acceptance remain required in
[#219](https://github.com/turesheim/eclipse-timekeeper/issues/219); the web client
remains required in [#221](https://github.com/turesheim/eclipse-timekeeper/issues/221).
