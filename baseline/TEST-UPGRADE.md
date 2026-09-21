# Test execution on Java 21

The maintainer requested that tests execute before continuing the remaining
upgrade steps. This brings the persistence compatibility and test-runner work
from steps 4/5 forward without changing the database format.

## Changes

- EclipseLink 2.7.16 and its ASM 9.8.0 bundle replace EclipseLink 2.7.3/ASM 6.2.
  The target pins the official release repository and exact bundle versions;
  the database manifest requires compatible versions. `javax.persistence` 2.2.1,
  H2 1.4.194 and the existing entity mappings remain unchanged.
- Surefire 3.5.5 runs JUnit Platform with Jupiter and Vintage 5.13.4, preserving
  JUnit 4 tests and discovering both parameterized JUnit 5 report cases.
  Maven Compiler 3.14.1 compiles tests with release 21.
- Each database test gets a unique in-memory database, closed with its connection
  pool afterwards. Persistence assertions clear both JPA caches before reading.
- Report fixtures now populate task IDs and projects through the existing model.
  Previously undiscovered tests tried to persist tasks with null primary keys.
  Reports use closed UTF-8 writers and assert project, task and activity content.
  Generated reports are under `target/test-reports` and included in CI artifacts.
- SWTBot's runtime explicitly includes the SDK product and SLF4J provider.
  Startup polls the database's latched ready state for at most 30 seconds,
  avoiding a missed-notification race. The forked UI process has a 180-second
  timeout. Both test runners fail if no tests are found.
- UI setup uses the workbench shell rather than assuming an active OS window;
  workweek screenshots also use that shell, avoiding zero-sized layout containers.
  CI retains the Eclipse runtime log for failures not reflected in test counts.

## Verification

Verified September 21, 2026 after the interrupted-activity recovery merge:
macOS 27.0 / aarch64, Temurin 21.0.12.1, Maven 3.9.16. The build ran from a
clean worktree, excluding local IDE metadata and uncommitted classpath edits.
The normal Maven dependency cache was used; this was not an empty-cache
verification.

```sh
mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

No skip flags were used. The clean build completed in approximately 31 seconds.
The generated Surefire XML reports and p2 repository were inspected in their
module `target` directories; these generated files are not committed.

| Suite / check | Result |
| --- | --- |
| Database/report/packaging suites | 64 passed |
| `TemplateTest` | 2 bundled report templates rendered and content checked |
| `IntegrationTest` (SWTBot/JUnit 4) | 6 passed |
| `WeekViewContentProviderTest` (SWTBot/JUnit 4) | 4 passed |
| Six-project reactor and p2 repository/ZIP | Passed |
| Current-model fixture, restart, copy and SQL restore | Passed with Java 21 |

The automated acceptance coverage maps to the upgrade-plan item as follows:

- Time tracking: `IntegrationTest.testTaskActivationAndDeactivation` verifies
  activity creation, closure, duration and workweek totals.
- Manual editing: `IntegrationTest.testEditTimeRange` edits a workweek cell and
  verifies the resulting duration.
- Labels and restart: the current-model fixture verifies two labels, three
  assignments, relationships and labelled totals after closing and reopening
  file-backed storage in `StorageModesTest` and `FileStorageTest`.
- Report export: `TemplateTest` renders every bundled template and checks
  project, task and activity content; SWTBot also invokes both clipboard export
  paths from the workweek view.
- Import/export: `IntegrationTest.testExport` checks current-schema CSV headers
  and performs a non-empty export/import round trip.

No active tests were disabled. The UI run also covers deleted-Mylyn-task
history, preference storage and UI-thread refresh behavior.

## Remaining limitations

- The manual-edit test remains guarded on Linux because SWTBot can focus the
  workbench's Find Actions editor instead of the workweek cell editor.
- Template files are content-checked directly, while the SWTBot clipboard test
  verifies command/menu execution rather than reading the OS clipboard payload.
- Linux/Xvfb, Windows and native idle detection still require their separate
  platform acceptance steps. No personal database was used.

## Sources

- [EclipseLink 2.7.16 release, including ASM updates](https://github.com/eclipse-ee4j/eclipselink/releases/tag/2.7.16)
- [Pinned EclipseLink p2 repository](https://download.eclipse.org/rt/eclipselink/updates/2.7.16.v20250613-47773a333b/)
- [Surefire JUnit Platform and Vintage configuration](https://maven.apache.org/surefire/maven-surefire-plugin/examples/junit-platform.html)
- [JUnit 5.13.4 release notes](https://docs.junit.org/5.13.4/release-notes/)
- [Maven Compiler 3.14.1](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.14.1/usage.html)
- [Tycho test runtime and timeout parameters](https://tycho.eclipseprojects.io/doc/latest/tycho-surefire-plugin/test-mojo.html)
