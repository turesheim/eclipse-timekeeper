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

## Remaining before #220 can be completed

- Route data import/export preferences through remote transfer operations;
  preserve CSV data and relationships without accessing an embedded database.
- Scope Mylyn projection identities and category mappings by backend and owner;
  verify that changing backend never imports another backend's projections.
- Exercise the complete installed Eclipse flow against the actual packaged
  server, both without Mylyn and with Mylyn activation/deactivation, including
  secure storage, restart, remote edits, labels, reports and tracking.
- Prove the local database stays closed and existing local files stay unchanged
  in remote mode, including authentication, timeout, cancellation and conflict
  failures. Verify that form drafts survive each failure.
- Review uncertain command results and retry behavior, particularly atomic
  imports, and distinguish server failures from local rendering/file errors.

Concurrent client notifications and reconnect acceptance remain required in
[#219](https://github.com/turesheim/eclipse-timekeeper/issues/219); the web client
remains required in [#221](https://github.com/turesheim/eclipse-timekeeper/issues/221).
