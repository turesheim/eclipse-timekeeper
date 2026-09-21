# H2 storage-mode acceptance

> Historical report: the maintainer subsequently removed legacy migration support.
> Historical conversion steps and related release-acceptance requirements below
> are superseded by [migration simplification](MIGRATION-SIMPLIFICATION.md) and the
> [current database policy](../DATABASE-RECOVERY.md). Version/lifecycle infrastructure
> and current-storage tests remain; old test totals are historical results.

Follow-up to merged PR #194, using H2 2.5.250 and Java 21.

## Automated coverage

`StorageModesTest` adds seven cases:

| Case | Verified behavior |
| --- | --- |
| Automatic mixed mode | First JPA client stays open while a separate JVM verifies all baseline records and commits a nanosecond-precision task tick |
| Explicit TCP URL | Same two-process check through a local H2 server; JPA creates the schema in a pre-created empty file |
| Workspace-style embedded storage | Without mixed mode, a second JVM is rejected with H2 error 90020; records remain readable |
| Incompatible old client | A child with only H2 1.4.194 and the JDBC probe on its classpath is rejected by the new server with error 90047; baseline records remain intact |
| Future schema version over TCP | Startup rejects version 999; complete SQL snapshots before and after are identical |
| Incomplete recovery over TCP | Startup rejects a valid but pending recovery marker; complete SQL snapshots before and after are identical |
| Missing server database | `IFEXISTS=TRUE` refuses an absent database without creating its file |

For both sharing modes, the parent explicitly evicts its JPA caches and verifies
the child's commit while still connected. All clients and the server are then
closed, and the same file is reopened in embedded mode. The baseline still
contains 2 projects, 3 tasks, 5 activities, 2 labels, 3 assignments and 19,800
seconds; the new tick retains all nine fractional digits.

All databases live under JUnit temporary directories. Paths with spaces are
covered. Ports are ephemeral (`AUTO_SERVER_PORT=0` or `-tcpPort 0`), avoiding
the user's normal port 9090. Explicit servers do not enable `-tcpAllowOthers`
or remote database creation. Child JVMs have a 30-second deadline, captured
output and forced cleanup on failure. No real home/workspace database, user
preferences or configured server is accessed.

## Configuration implications

- Shared storage uses `AUTO_SERVER=TRUE;AUTO_SERVER_PORT=9090` in production.
  A second process must use compatible settings and the same database path.
- Workspace storage remains an ordinary embedded file, exclusive to one JVM.
  Do not point two workspaces at it without explicitly selecting shared storage.
- Configured TCP storage requires a running compatible server and a deliberately
  prepared database. For the verified configuration, use H2 2.5.250 on both
  sides. H2 1.4.194 clients cannot participate in the upgraded server.
- Stored custom JDBC URLs are not rewritten. Preserve the original settings for
  rollback and deliberately select the converted database. Do not add
  `IGNORE_UNKNOWN_SETTINGS` to hide an obsolete option.
- The plugin currently supplies `sa` with an empty password. These local tests
  do **not** establish a secure remotely accessible server deployment; do not
  expose such a server to untrusted networks.

See H2's [connection-mode and mixed-mode documentation](https://h2database.github.io/html/features.html#auto_mixed_mode)
and [server guidance](https://h2database.github.io/html/tutorial.html#using_server).
The [recovery procedure](../DATABASE-RECOVERY.md) still applies before an old
database can be used by either sharing mode.

## Boundaries

These are persistence-layer processes, not two running Eclipse installations.
They do not prove automatic UI cache refresh, simultaneous edits of the same
task, multi-instance active-task ownership, crash/failover behavior, concurrent
first-time schema creation, network-disconnect recovery, fixed-port conflicts,
TLS/authenticated remote deployment, old-server/new-client behavior, or Windows
file locking. No production settings or database logic changed in this follow-up.

The step 4 shared/server and multi-instance checkboxes remain open for those
remaining acceptance checks. Published-release provenance is a separate gap;
see [the release audit](PUBLISHED-RELEASES.md).

## Verification

Run the focused suite from clean sources:

```sh
mvn -B -ntp clean verify -pl net.resheim.eclipse.timekeeper.db -am \
  -Dtest=StorageModesTest -Dtycho.localArtifacts=ignore
```

Verified September 21, 2026, on macOS aarch64 with Java 21 from an isolated
source export. The final `mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore`
passed all six reactor modules and produced the p2 repository ZIP:

- 122 database/report tests passed, including all seven storage-mode cases.
- 9 UI/integration tests passed; the existing CSV-export test remains ignored.
- No test-skipping build flags were used. Existing EclipseLink and Eclipse/Mylyn
  runtime warnings remain; this is not a clean installed-IDE log certification.

Local evidence: `/private/tmp/timekeeper-storage-verified.log` and the test
reports under `/private/tmp/timekeeper-storage.biqWDu/`. CI must independently
verify the Linux run. Installed-IDE acceptance remains required before release.
