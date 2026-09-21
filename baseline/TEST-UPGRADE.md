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

Verified September 21, 2026: macOS 27.0 / aarch64, Temurin 21.0.12.1, Maven 3.9.16.
Builds run from a clean temporary source copy, excluding local IDE metadata,
uncommitted classpath edits and generated files. The normal Maven dependency
cache is used; this is not an empty-cache verification.

```sh
mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

The local run additionally used an empty temporary Maven settings file with `-s`
to exclude unrelated private repositories. No skip flags were used.
The final clean build completed in approximately 17 seconds. Local evidence is
in `/private/tmp/timekeeper-tests-final.log` and the `target/surefire-reports`
directories under `/private/tmp/timekeeper-tests-clean.LIDs1m/`. These temporary
files are not committed and may be removed by the operating system.

| Suite / check | Result |
| --- | --- |
| `SharedStorageTest` (JUnit 4 via Vintage) | 2 passed |
| `TemplateTest` (JUnit Jupiter) | 2 template cases passed |
| `IntegrationTest` (SWTBot/JUnit 4) | 2 passed, 1 existing explicit skip |
| Six-project reactor and p2 repository/ZIP | Passed |
| English legacy fixture and closed-file database copy | Passed with JDK 11 |

Passing UI checks cover workweek navigation, clipboard-template menu interaction
and preferences. They are not comprehensive time-tracking or clipboard-content tests.
The test suite still has an explicitly ignored CSV export test, which targets
the obsolete `TRACKEDTASK` schema. `testEditTimeRange` still has its `@Test`
annotation commented out and is not counted as an executed or skipped test.
These existing coverage gaps remain in the plan; no active tests were disabled.

## Remaining limitations

- Existing-database migration, full SQL restore, label round-trips, import/export
  and restart are not established by the passing tests. No personal database was used.
- The macOS test runtime reports missing theme/Cocoa menu contributions and a
  Mylyn `CommonColors` shutdown error (`Invalid thread access`). Passing Surefire
  results do not establish an error-free Eclipse runtime or release readiness.
- Linux/Xvfb CI and Windows runtime results must be checked separately.
- The legacy synthetic fixture still reproduces the documented full SQL restore
  failure. English descriptions retain explicit Unicode samples (`æøå`).

## Sources

- [EclipseLink 2.7.16 release, including ASM updates](https://github.com/eclipse-ee4j/eclipselink/releases/tag/2.7.16)
- [Pinned EclipseLink p2 repository](https://download.eclipse.org/rt/eclipselink/updates/2.7.16.v20250613-47773a333b/)
- [Surefire JUnit Platform and Vintage configuration](https://maven.apache.org/surefire/maven-surefire-plugin/examples/junit-platform.html)
- [JUnit 5.13.4 release notes](https://docs.junit.org/5.13.4/release-notes/)
- [Maven Compiler 3.14.1](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.14.1/usage.html)
- [Tycho test runtime and timeout parameters](https://tycho.eclipseprojects.io/doc/latest/tycho-surefire-plugin/test-mojo.html)
