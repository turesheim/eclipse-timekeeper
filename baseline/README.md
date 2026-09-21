# Baseline before the Eclipse upgrade

> Historical report: the maintainer subsequently removed legacy migration support.
> Historical conversion steps and related release-acceptance requirements below
> are superseded by [migration simplification](MIGRATION-SIMPLIFICATION.md) and the
> [current database policy](../DATABASE-RECOVERY.md). Version/lifecycle infrastructure
> and current-storage tests remain; old test totals are historical results.

Date: September 21, 2026. Source commit:
`0105303faa60193f3796cd98c5ff01eac5234ca5`, with pre-existing local changes
to `.java-version` and the database/UI projects' `.classpath` files.
Those changes were preserved. The baseline work did not change production
code or build configuration. This document records the original baseline;
see [BUILD-UPGRADE.md](BUILD-UPGRADE.md) for subsequent build changes.

## Build environment and result

| Property | Observed value |
| --- | --- |
| OS / architecture | macOS 27.0 / aarch64 |
| Maven | Homebrew Maven 3.9.16 |
| JDK used for the build | Homebrew OpenJDK 11.0.32.1 |
| Tycho | 2.7.5, unchanged |
| Target platform | Eclipse 2022-03, unchanged |
| Default Java selection | Failed: jenv could not find the pinned `17.0.4.1` |
| Build result | Exit 1 during target resolution, before compilation or tests |

Command run from the project root, explicitly selecting a JDK to bypass the
unavailable jenv version:

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home \
  /opt/homebrew/bin/mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

The sandboxed attempt stopped because Tycho could not lock the Maven cache
(`Unable to create lock manager`). An approved run outside that restriction
reached the following repository error:

```text
Failed to resolve target definition .../default.target:
Could not find "org.eclipse.mylyn.bugzilla_feature.feature.group/3.25.2.v20200814-0512"
in the repositories of the current location
```

`default.target` requested that exact version from
`http://download.eclipse.org/mylyn/releases/latest`, where it was unavailable
during this run. SLF4J also reported a missing `StaticLoggerBinder` and disabled
logging, but Mylyn resolution was the build blocker.

No new tests ran, no usable plugin was built, and Eclipse startup was not tested.
The failure occurred before Maven executed `clean`, leaving historical output
in place at that point. Those files were not evidence of a successful baseline run.

Local logs were stored in `/private/tmp/timekeeper-baseline.gvEheO/`:
`build.log` (sandbox), `build-unrestricted.log` (repository failure),
`fixture.log` and `restore.log`. Temporary files may disappear; this document
preserves the conclusions and reproduction commands.

## Test inventory

| Test | Declared in source | Baseline run |
| --- | --- | --- |
| `SharedStorageTest` | Two JUnit 4 tests: simple persistence and daily duration | Not reached |
| `TemplateTest` | One parameterized JUnit 5 test, two files in `templates/` | Not reached |
| `IntegrationTest.testNavigateWorkweekView` | Active JUnit 4/SWTBot test | Not reached |
| `IntegrationTest.testOpenPreferences` | Active JUnit 4/SWTBot test | Not reached |
| `IntegrationTest.testExport` | `@Test` with `@Ignore` | Disabled |
| `IntegrationTest.testEditTimeRange` | `@Test` commented out | Not an active test |

The historical report in the database project's `target/surefire-reports/`
was dated September 19, 2022: two passing `SharedStorageTest` tests, no errors
or skips, Java 11.0.15 and Maven 3.8.6. A copy was saved with the baseline logs.
This was historical evidence, not a new verification.

The original database POM used Surefire 2.19.1 and Jupiter API/params 5.8.2,
without a Jupiter engine or an explicit JUnit Platform provider. No historical
`TemplateTest` report was present. JUnit 5 discovery and execution therefore
needed explicit verification; a successful Maven exit alone would not suffice.

## Synthetic data and expectations

The user selected synthetic fixtures if no existing database was available.
No database file was found in the project. No personal Timekeeper database
was opened or modified.

### Model history and intent

The user connected the database redesign to alignment with Timewarrior/Taskwarrior.
[Issue #165](https://github.com/turesheim/eclipse-timekeeper/issues/165), created
April 20, 2020, establishes that project goal. At baseline review, it was open
with no description or comments defining a concrete compatibility format.

Git history shows the change from `TrackedTask` to `Task`:

- `da5ab90`: shared database storage introduced in 2016/2017, using `TrackedTask`.
- `7cb249f`, November 4, 2020: project, task URL and summary stored in the database.
  [Issue #163](https://github.com/turesheim/eclipse-timekeeper/issues/163) describes
  reporting without loading Mylyn tasks or contacting their repository.
- `66590d6`, November 8, 2020: `TrackedTask` renamed to `Task`, `TRACKEDTASK`
  to `TASK`, and `TrackedTaskId` to `GlobalTaskId`, with clearer optional Mylyn linkage.
- `0105303`, October 26, 2022: label work from
  [issue #166](https://github.com/turesheim/eclipse-timekeeper/issues/166) merged.

This supports treating the SQL/current-model difference as intentional evolution.
The rename commit does not document a direct link to #165; the user's explanation
is recorded as design context. Preserve the current model and Mylyn-independent
reporting. The legacy fixture is migration input, not a proposal to revert to
`TRACKEDTASK`. New Timewarrior/Taskwarrior integration is outside the upgrade scope.

`legacy-data.sql` uses the unchanged `V1__baseline.sql` and
`V2__add_project_taskurl_and_tasksummary.sql`. It represents the repository's
historical SQL schema, not a proven schema from a particular published release
or a database created by the current JPA model.

| Check | Expected |
| --- | --- |
| Projects | 2 |
| Tasks | 3, including one without activities |
| Task ID `1` | Two repositories, distinct composite keys |
| Activities / task-activity links | 5 / 5 |
| Manually adjusted activities | 1 |
| Duration for task `a/1` / `b/1` | 9,900 / 9,900 seconds |
| Total duration | 19,800 seconds = 5 h 30 min |
| September 18, 2022 | 1,800 seconds = 30 min |
| September 19, 2022 | 13,500 seconds = 3 h 45 min |
| September 20, 2022 | 4,500 seconds = 1 h 15 min |
| ISO weeks 37 / 38 | 30 min / 5 h |
| Labels / assignments in the separate specification | 2 / 3 |
| Billable / Internal / unlabelled | 2 h 45 min / 45 min / 2 h |

The data covers multiple activities per task, Unicode text, manual adjustment,
midnight and Sunday-to-Monday boundaries. Later report checks must compare these
values, not merely verify that an output file exists.

`labels.csv` specifies labels and activity assignments. The historical schema
has no label tables, so these are separate. The utility checks counts, colors
and references to existing activities. This does not verify JPA label persistence.

## Reproduce and verify the database copy

Run from the project root with JDK 11 and the bundled H2 1.4.194:

```sh
/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home/bin/java \
  -cp net.resheim.eclipse.timekeeper.db/lib/h2-1.4.194.jar \
  baseline/LegacyFixture.java
```

Replace the Java path with another installed JDK 11 as needed. The utility uses
JDBC directly, requires neither Maven nor Eclipse, and creates a new temporary
directory for each run. It prints the location and creates `original.mv.db`,
`copy.mv.db` and `export.sql`. The database is closed before copying.
Both databases are checked for counts, relationships, manual adjustment,
Unicode text and duration totals. This is not a JPA or UI test.

Original baseline result: **passed**, exit 0. Data and verified copy were created in
`/var/folders/8m/2g_5_qdj5490htv6jfkzkcsh0000gn/T/timekeeper-legacy-fixture-7656006107624216071/`.
Generated binary files are not committed; the source fixtures make them reproducible.

To reproduce the observed SQL restore failure, append `--restore` to the command.
This creates another temporary directory and attempts to restore the full SQL
export into `restored.mv.db`.

Original baseline result: **failed**, exit 1, after validating the original and copy:

```text
Index "PRIMARY_KEY_1" already exists
CREATE UNIQUE INDEX PUBLIC.PRIMARY_KEY_1 ON PUBLIC.TRACKEDTASK_ACTIVITY(...)
[42111-194]
```

The failure was reproduced with the same H2 version on both sides. Historical
migrations have not been rewritten to hide it. Full SQL export is not a verified
rollback path for this schema; that remains part of step 4.

## Baseline follow-up items

- Step 2: fix Mylyn target resolution and rerun the baseline tests.
- Steps 3/5: verify plugin startup and UI tests once a build is available.
- Step 4: reconcile historical `TRACKEDTASK` with current `TASK` and labels,
  and create a fixture through the actual JPA model.
- Step 4: verify label persistence and a working export/import/rollback path.
- Step 5: verify Jupiter discovery and address the disabled UI tests.

The Flyway call in `TimekeeperPlugin` is commented out, but `persistence.xml`
still enables EclipseLink `create-tables`. Schema creation is not entirely
disabled; the missing part is a verified, versioned migration path.
The original default JDBC URL also has a duplicate `jdbc:h2:` prefix, although
plugin startup and `PersistenceHelper` override it.
