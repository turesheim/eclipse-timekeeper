# Timekeeper upgrade plan

Created: September 21, 2026.

Goal: Timekeeper can be installed and used in Eclipse IDE 2026-09
(Eclipse Platform 4.41), preserving existing time records.
This was the latest stable Eclipse release when the plan was created.

Status: PRs #185/#186/#188/#189 are merged into `main`. PR #190 passed Linux/Xvfb
CI but merged into the old conversion branch, not `main`. Its reviewed startup
guards and nonblocking preferences are now carried forward on a main-based
integration branch, including origin's shutdown and closed-connection fixes.
A user-facing migration/recovery workflow, durable versioning, runtime-log
follow-up and broader test coverage remain outstanding. Test enablement from
steps 4/5 was brought forward at the maintainer's request.
See the [original baseline](baseline/README.md),
[step 2 results](baseline/BUILD-UPGRADE.md) and
[test-enablement results](baseline/TEST-UPGRADE.md) and
[Mylyn/UI results](baseline/MYLYN-UI-UPGRADE.md) and
[database-safety results](baseline/DATABASE-SAFETY.md) and
[historical-conversion results](baseline/LEGACY-CONVERSION.md) and
[startup-safety results](baseline/DATABASE-STARTUP.md).

## Following this plan

- Work through the steps in order and check off tasks once verified.
- Record results, selected versions and deviations under the relevant step.
- Use separate changesets for build/target platform, Mylyn/UI, database and release work.
- Test database changes on copies or synthetic data. Preserve original data and
  a documented rollback path.
- Preserve pre-existing local changes to `.java-version` and the database and UI
  projects' `.classpath` files, reconciling overlapping changes when updating these settings.
- Use English for project documentation, comments, fixture descriptions and pull requests.

## Original baseline

- Active target platform: Eclipse 2022-03 in `default.target` and `default.tpd`.
- Build: Tycho 2.7.5 and Java 11 in Maven, manifests and CI.
  The local `.java-version` selected Java 17.0.4.1.
- The UI manifest limited core Mylyn dependencies to `[3.0.0,4.0.0)`.
- Several classes used internal Mylyn APIs.
- The database layer used H2 1.4.194, EclipseLink 2.7.3 and `javax.persistence`.
- The Flyway call during database startup was commented out.
- Test configuration mixed JUnit 4 and 5 with older Surefire and SWTBot versions.
- A separate Neon target platform existed in `net.resheim.eclipse.timekeeper.target/`.

## 1. Establish a reproducible baseline

- [x] Record JDK and Maven versions and run the existing build.
- [x] Document build failures, test results and startup verification status.
- [x] Check which tests are actually discovered and executed, including JUnit 5 tests.
  Initial step 2 checks exposed missing Jupiter execution; the test-enablement
  follow-up runs both JUnit 4 methods and both JUnit 5 template cases.
- [x] Create representative synthetic tasks, activities, a separate label
  specification and report data.
- [x] Record expected counts, relationships and time totals for later comparison.
- [x] Create a synthetic database using the repository's historical SQL schema
  and a verified file copy for upgrade testing.
- [x] Also verify labels and relationships in a fixture created through the current JPA model.
  Completed in step 4 with file-backed restart, copy and SQL restore tests.

Completion criterion: A documented baseline and test data can reveal lost or
changed records. Existing failures are distinguished from newly introduced failures.

Results and deviations, September 21, 2026:

- Used Java 11.0.32.1 and Maven 3.9.16 without changing local Java settings.
- The build failed before compilation: the target repository could not provide
  `org.eclipse.mylyn.bugzilla_feature.feature.group/3.25.2.v20200814-0512`.
- No tests ran in the original baseline build. A historical 2022 report contains
  two passing database tests; the test inventory and JUnit 5 uncertainty are documented.
- Synthetic fixtures were selected because no existing database was available.
  The original and file copy were validated with 3 tasks, 5 activities and a total of 5 h 30 min.
- The label specification contains 2 labels and 3 assignments, but the historical
  SQL schema does not support labels. Step 4 now verifies the same assignments
  through the current JPA model; this does not add labels to the historical schema.
- The maintainer associates the model changes with the Timewarrior/Taskwarrior goal in
  [#165](https://github.com/turesheim/eclipse-timekeeper/issues/165).
  Git history shows `TrackedTask` → `Task` in November 2020. Preserve the current
  model; the historical fixture is migration input only. See the baseline report for details.
- Full SQL restore fails on a duplicate index even with H2 1.4.194 on both sides.
  This is documented for step 4; the historical migrations have not been changed.
- See [baseline/README.md](baseline/README.md) for commands, expected values and limitations.
  Follow up on the remaining checks above as later steps progress.

## 2. Upgrade the build and consolidate the target platform

- [x] Upgrade Tycho to 5.0.4 and use Maven 3.9.9 or newer.
- [x] Use JDK 21 for the build and verify the target platform's runtime requirements.
- [x] Set the plugin's minimum Java version and align Maven, manifests,
  `.settings`, `.classpath`, `.java-version` and CI.
- [x] Update `default.target` to Eclipse 2026-09 and remove the parallel `.tpd` definition.
- [x] Pin Mylyn and other build dependencies; persistence compatibility remains in step 4.
- [x] Use HTTPS and versioned repositories where available.
- [x] Review the need for Gemini JPA, old Orbit libraries and connector dependencies.
- [x] Consolidate on one target platform and retire the old Neon definition.
- [x] Update the relevant launch configurations.
- [x] Verify dependency resolution from clean sources without local Eclipse artifacts.

Completion criterion: Dependencies resolve consistently against Eclipse 2026-09,
and the development environment and command-line build use the same target.
Remaining source compatibility issues are documented for steps 3 and 4.

Results and deviations, September 21, 2026:

- Eclipse 4.41, Mylyn 4.12 and SWTBot 4.3 resolve from one dated SimRel repository.
- The minimum Java version is 21. The build uses Tycho 5.0.4 and JaCoCo 0.8.15.
- A clean source export compiles all modules and produces a p2 repository with
  tests explicitly skipped. UI-test classes are freshly compiled rather than reused from `bin/`.
- Initial `verify` ran the tests and failed in `SharedStorageTest`: EclipseLink 2.7.3
  rejects Java 21 bytecode (`Unsupported class file major version 65`) and
  did not recognize the entity model. Resolved by the test-enablement follow-up.
- The initial provider did not execute JUnit 5 report tests. Resolved by adding
  Jupiter and Vintage engines with Surefire 3.5.5.
- CI still runs full `verify`; this change is not ready for release.
- See [baseline/BUILD-UPGRADE.md](baseline/BUILD-UPGRADE.md) for detailed verification.

## 3. Adapt the Mylyn integration and UI

- [x] Confirm that the selected Mylyn release works with Eclipse 2026-09.
  Verified local-task lifecycle and workweek behavior; live remote connectors remain untested.
- [x] Update manifest version ranges based on verified compatibility.
- [x] Review internal Mylyn APIs in `TimekeeperPlugin`, `Task`, `Project`,
  `WorkWeekView`, and the content and label providers.
- [x] Replace internal APIs with public APIs where possible.
- [x] Consolidate and document any remaining internal API dependencies.
  See the compatibility-boundary inventory in the step 3 report.
- [x] Determine whether Bugzilla must remain a mandatory dependency.
  Removed the unused bundle requirement and target root; connectors can be installed separately.
- [ ] Fix necessary compilation and runtime issues in Eclipse/SWT/JFace integration.
- [x] Verify task activation/deactivation, categories and workweek view updates.
  Synthetic lifecycle and deleted-task tests pass; category reassignment is not yet covered.

Completion criterion: The plugin starts in Eclipse 2026-09 and tracks time
correctly using test data. Final verification depends on a working database layer in step 4.

Results and deviations: PR #186 merged with passing Linux/Xvfb CI. Its clean
`verify` passed on macOS aarch64 with 4 database/report cases and 7 active UI
tests passing, plus the existing ignored export test, including the three
background-notification/disposal regressions added during review.
Fixed blank task/project totals, updates targeting obsolete row types and null
Mylyn links for deleted tasks. Public APIs now cover activity-manager access and
task icons. Remaining internals and the existing macOS/Mylyn runtime-log errors
are documented in [baseline/MYLYN-UI-UPGRADE.md](baseline/MYLYN-UI-UPGRADE.md).
The runtime-error checkbox remains open pending clean installed-IDE verification.

## 4. Secure the database layer and upgrade path

- [ ] Test the existing database format and persistence configuration on the new runtime.
  Current-model JPA and frozen pre-label-fix schema fixtures pass; the historical
  V1/V2 fixtures now convert into a readable current-model database. Databases
  from previous published releases are still outstanding.
- [x] Preserve the current `Task`/activity model and reporting independent of Mylyn
  when migrating from the historical `TRACKEDTASK` schema, using #163 and #165 as design context.
  Verified for the repository's V1/V2 schemas through an explicit converter into
  a separate empty target. No automatic migration is enabled.
- [x] Select a compatible EclipseLink/JPA combination and document the versions.
- [x] Determine whether migration from `javax.persistence` to `jakarta.persistence` is necessary;
  if so, make it a separate, testable change.
- [ ] Review and repair schema creation and migration, including the disabled Flyway call.
  Conversion data writes are transactional and verified before commit. Startup
  now creates schema only in empty databases and refuses historical/mixed/unknown
  layouts. Durable versioning and the recovery workflow remain open; Flyway stays disabled.
- [ ] Decide whether to upgrade H2 in this release, documenting the rationale and any follow-up.
- [ ] If moving to H2 2.x, export with the old H2 version and import into a new database,
  with backups, validation and documented rollback.
- [ ] Verify existing JDBC parameters, shared storage, workspace storage and configured servers.
- [ ] Test concurrent access from multiple Eclipse instances and handling of incompatible clients.
- [ ] Test new databases, existing databases, migration failures and restart.
  New/current-model databases, restart, closed-file copy, SQL restore and transaction
  rollback are covered. Explicit V1/V2 conversion failures and converted-database
  restart/restore are covered. Startup guards and UI preference failure behavior
  now have automated coverage; installed-IDE and shared/server startup remain open.
- [x] Compare counts, relationships and time totals with the step 1 baseline.
  Current-model synthetic data matches the baseline, including labels. Converted
  historical data also matches its 19,800-second baseline; historical schemas
  contain no labels, so conversion leaves labels empty.

Completion criterion: New database creation and supported upgrades of existing
data work. Records are preserved, and failed migrations can be handled without losing original data.

Results and deviations: Current-model fixtures verify 2 projects, 3 tasks,
5 activities, 2 labels, 3 assignments and 19,800 seconds after restart/copy/restore.
Fixed label initialization, toggle identity and cascading deletion. The label
mapping retains the existing join table and is tested against a frozen pre-change
schema without DDL generation. H2 remains unchanged in this changeset; the release
version decision and historical migration remain open. Clean `verify` passes
13 database/report and 7 UI cases, with one existing UI test ignored. See
[database-safety results](baseline/DATABASE-SAFETY.md) for evidence and limitations.

Test-enablement follow-up: EclipseLink 2.7.16 with ASM 9.8.0 reads Java 21
entities while retaining `javax.persistence` 2.2.1 and H2 1.4.194. No Jakarta
namespace or database-format migration is required just to execute the tests.
Current-model compatibility and explicit V1/V2 conversion now have synthetic
coverage. Conversion preserves a read-only source byte-for-byte and rejects
unknown/mixed schemas and inconsistent associations. Converted data can be
exported/restored without the old schema's duplicate-index DDL. The original
historical export remains non-restorable. Full local verification now passes
27 database/report and 7 UI cases, plus the existing ignored UI test. See
[historical-conversion results](baseline/LEGACY-CONVERSION.md). Startup follow-up
passes 44 database/report and 8 UI cases, plus the existing ignored UI test after
adding the connection-availability review regressions; see
[startup-safety results](baseline/DATABASE-STARTUP.md). Automatic migration,
durable versioning, end-user recovery and previous-release compatibility remain open.

## 5. Modernize tests, libraries and CI

- [x] Update SWTBot, Surefire/test execution and JaCoCo to compatible versions.
- [x] Ensure that existing JUnit 4 and JUnit 5 tests actually run,
  or consolidate them on one test platform.
- [ ] Review and update JNA, FreeMarker and logging as required for compatibility.
- [x] Update GitHub Actions for checkout, Java, caching, reports and artifacts.
- [ ] Run automated tests for time tracking, manual editing, labels,
  report export, import and restart.
- [x] Run UI tests in CI with the required display/Xvfb configuration.
  PR #185 passed Linux/Xvfb CI before merge; subsequent changes require their own CI result.
- [ ] Check idle detection on Windows, macOS and Linux.
- [ ] Verify Apple Silicon and assess X11/Wayland separately; document limitations.
- [ ] Document the OS/architecture combinations that have been tested and are supported.

Completion criterion: Relevant tests are discovered, executed and pass. CI
produces test results and a p2 repository, and platform support is documented.

Results and deviations: Clean `verify` passes on macOS aarch64 with 4 database/report
cases and 2 active UI tests passing. One legacy export test remains ignored and
the manual-edit test remains inactive. CI/platform validation and broader coverage
are still open. See [test-enablement results](baseline/TEST-UPGRADE.md), including
the remaining Eclipse runtime log warnings/errors.

## 6. Verify installation and prepare the release

- [ ] Update feature and p2 metadata for the selected dependencies.
- [ ] Build the p2 repository and install into a clean Eclipse 2026-09.
- [ ] Check that required dependencies are included or can be installed automatically.
- [ ] Test upgrading from the previous published Timekeeper release.
- [ ] Verify restart, settings and existing data after upgrading.
- [ ] Check the Eclipse Error Log for Timekeeper and dependency errors.
- [ ] Update version numbers, README, CHANGES and any migration instructions.
- [ ] Document supported Eclipse/Java versions, installation and rollback.
- [ ] Record the final build, test results and release artifact location.

Completion criterion: Installation, upgrade, restart and use with existing data
work without dependency errors. A verified p2 repository and the required
documentation are ready for publication.

Results and deviations: Not started.

## Decisions to track

| Decision | Status |
| --- | --- |
| Exact Mylyn version and required connectors | Mylyn 4.12 Tasks; Bugzilla no longer mandatory; local lifecycle verified in step 3 |
| Minimum Java version and tested runtime | Java 21; build tested with Temurin 21.0.12.1 |
| EclipseLink/JPA version and possible Jakarta migration | EclipseLink 2.7.16 / javax.persistence 2.2.1; no namespace migration for test enablement |
| H2 upgrade and migration procedure | Decide in step 4 |
| Supported operating systems and architectures | Verify in step 5 |
| New Timekeeper version | Decide before completing step 6 |

Mylyn compatibility and database migration are the largest uncertainties.
Resolve them early before finalizing scope and estimates.

## Sources

- [Eclipse releases and documentation](https://www.eclipse.org/documentation/)
- [Tycho versions and Maven/JDK requirements](https://tycho.eclipseprojects.io/doc/latest/tycho-compiler-plugin/plugin-info.html)
- [H2 migration to 2.x](https://h2database.com/html/migration-to-v2.html)

These sources were reviewed when the plan was created. Reconfirm version
choices if work resumes after newer releases become available.
