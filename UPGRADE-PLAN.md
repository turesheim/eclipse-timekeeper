# Timekeeper upgrade plan

Created: September 21, 2026.

Goal: Timekeeper can be installed and used in Eclipse IDE 2026-09
(Eclipse Platform 4.41), preserving records in the new versioned database baseline.
Historical data migrations are explicitly out of scope.
This was the latest stable Eclipse release when the plan was created.

Status: PRs #185/#186/#188/#189/#191/#192/#193/#194/#195/#196 are merged into `main`,
with PR #196's Linux/Xvfb CI passing. Normal storage uses H2 2.5.250.
The maintainer has removed historical migration support from scope: no one
depends on those formats. Schema versioning and guarded target lifecycle hooks
remain for future migrations. Clean installed-IDE creation, update and restart
are verified on macOS Apple Silicon; broader runtime acceptance remains open.
Earlier migration reports are historical, not active
support promises. Test enablement from steps 4/5 was brought forward.
See the [scope-change report](baseline/MIGRATION-SIMPLIFICATION.md).
See the [original baseline](baseline/README.md),
[step 2 results](baseline/BUILD-UPGRADE.md) and
[test-enablement results](baseline/TEST-UPGRADE.md) and
[Mylyn/UI results](baseline/MYLYN-UI-UPGRADE.md) and
[database-safety results](baseline/DATABASE-SAFETY.md) and
[storage-mode acceptance](baseline/STORAGE-MODES.md) and
[installed-IDE acceptance](baseline/INSTALLED-IDE.md).
The [database policy](DATABASE-RECOVERY.md) documents current backups and the future migration contract.

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
- [x] Fix necessary compilation and runtime issues in Eclipse/SWT/JFace integration.
  PR #202 guards the optional JDBC URL field against lazy JFace control creation,
  allowing the database location preference to be saved on Eclipse 2026-09.
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
Clean installed-IDE verification fixed missing Tasks search contributions and a
duplicate logging provider through feature metadata. The runtime-error checkbox
remains open: Mylyn's theme color parser also fails in the installed Task List.
See [installed-IDE acceptance](baseline/INSTALLED-IDE.md).

## 4. Secure the database baseline and future migration infrastructure

Scope decision: historical database/Mylyn data migration is not required.
The legacy converters, recovery UI, old H2 runtime and unused Flyway libraries
have been removed. Old report results below `baseline/` are retained as history.
Do not add published-release fixtures or legacy migration recipes for this release.

- [x] Preserve the current Task/activity/project/label model and reporting.
- [x] Use EclipseLink 2.7.16 / javax.persistence 2.2.1; no Jakarta migration is needed.
- [x] Use H2 2.5.250 as the only database engine.
- [x] Establish schema version 1 as the supported baseline; initialize only empty databases.
- [x] Reject unknown/unversioned schemas and malformed versions without changing them.
- [x] Persist initialization/migration state and refuse incomplete targets at normal startup.
- [x] Retain generic empty migration-target creation and explicit validated completion.
  There are no active migration recipes. Future runners must add source-version
  dispatch, backups, data transformation and validation before using these hooks.
- [x] Document the future migration contract, current backups and deliberate rollback.
- [x] Test current records, labels, relationships, time totals, timestamp precision,
  restart, closed-file copy, SQL restore and transaction rollback with synthetic data.
- [x] Test future migration lifecycle safeguards without implementing old migrations.
- [ ] Verify storage preferences and supported server configurations in installed Eclipse.
  Embedded/mixed-mode/TCP tests cover separate JVMs, paths with spaces, restart,
  missing database refusal and server-side version/state guards.
- [ ] Test concurrent access from multiple installed Eclipse instances.
  Persistence-layer sharing is covered; UI cache refresh, concurrent edits,
  active-task ownership, disconnect/failover and fixed-port conflicts remain open.
- [ ] Verify new/current database startup and interrupted-activity behavior in installed Eclipse.
  New/current embedded startup, normal shutdown and restart are verified in a
  clean installation using the URL override; interrupted activity remains open.

Completion criterion: new/current-baseline storage works, records are preserved,
unsupported databases fail safely, and future migrations have a documented,
tested lifecycle. Backward migration support is not a release criterion.

See [migration simplification](baseline/MIGRATION-SIMPLIFICATION.md) and the
[current database policy](DATABASE-RECOVERY.md). Historical baseline totals
remain 2 projects, 3 tasks, 5 activities, 2 labels, 3 assignments and 19,800 seconds
for the current synthetic fixture. Next: remaining installed-IDE and multi-instance runtime acceptance.

The latest clean build passes 63 database/report/packaging tests and 10 UI/integration
tests. Current-schema CSV export coverage is active again. Removed historical
tests are no longer applicable, not skipped tests.

## 5. Modernize tests, libraries and CI

- [x] Update SWTBot, Surefire/test execution and JaCoCo to compatible versions.
- [x] Ensure that existing JUnit 4 and JUnit 5 tests actually run,
  or consolidate them on one test platform.
- [x] Review and update JNA, FreeMarker and logging as required for compatibility.
  Updated embedded JNA to 5.19.1 and FreeMarker to 2.3.35; the host logging
  provider packaging was corrected in PR #197. Native idle behavior across
  operating systems remains a separate acceptance item. See
  [dependency audit](baseline/DEPENDENCY-AUDIT.md).
- [x] Update GitHub Actions for checkout, Java, caching, reports and artifacts.
- [ ] Run automated tests for time tracking, manual editing, labels,
  report export, import and restart.
  Current-schema CSV export/import round-trip and manual activity editing are
  verified on macOS aarch64; Linux remains guarded due to the known SWTBot
  editor-focus issue.
- [x] Run UI tests in CI with the required display/Xvfb configuration.
  PR #185 passed Linux/Xvfb CI before merge; subsequent changes require their own CI result.
- [ ] Check idle detection on Windows, macOS and Linux.
- [ ] Verify Apple Silicon and assess X11/Wayland separately; document limitations.
- [ ] Document the OS/architecture combinations that have been tested and are supported.

Completion criterion: Relevant tests are discovered, executed and pass. CI
produces test results and a p2 repository, and platform support is documented.

Results and deviations: Clean `verify` passes on macOS aarch64 with 63 database/report
tests and 10 active UI tests passing, including manual activity time-range editing.
The manual-edit test remains guarded on Linux due to the known SWTBot editor-focus
issue. CI/platform validation and broader coverage are still open. See [test-enablement results](baseline/TEST-UPGRADE.md), including
the remaining Eclipse runtime log warnings/errors.

## 6. Verify installation and prepare the release

- [x] Update feature and p2 metadata for the selected dependencies.
- [x] Build the p2 repository and install into a clean Eclipse 2026-09.
- [x] Check that required dependencies are included or can be installed automatically.
- [x] Test plugin updates against the new versioned database baseline.
  Historical data migration is out of scope.
- [ ] Verify restart, settings and existing data after upgrading.
  Restart and stored activity verified; storage preference switching remains open.
- [ ] Check the Eclipse Error Log for Timekeeper and dependency errors.
  Inspected installed runtime logs; the Mylyn theme-parser error remains unresolved.
- [ ] Update version numbers, README, CHANGES and any migration instructions.
- [ ] Document supported Eclipse/Java versions, installation and rollback.
- [ ] Record the final build, test results and release artifact location.

Completion criterion: Installation, upgrade, restart and use with existing data
work without dependency errors. A verified p2 repository and the required
documentation are ready for publication.

Results and deviations: Clean p2 install and update passed on Eclipse Platform
4.41 / Java 21 / macOS aarch64. Requiring the complete Mylyn Tasks feature fixed
missing search contributions; removing the bundled Equinox SLF4J provider fixed
duplicate providers. Synthetic task/activity/label data survived update and
restart, including display in a fresh Mylyn workspace. Relinking the local task
normalized its URL from NULL to an empty string; activity data was unchanged.
Full build: 73 passing tests with CSV export/import and manual-edit coverage active. This is
partial release acceptance, not publication approval. See
[the installed-IDE report](baseline/INSTALLED-IDE.md) for artifacts and limitations.

The follow-up activity lifecycle test passes (63 database tests). It also fixes
`Task.endActivity(LocalDateTime)`, which previously ignored the timestamp used
by the idle-time recovery path. Current-schema CSV export now passes in the UI
harness; manual editing and interrupted-activity GUI scenarios remain open.

## Decisions to track

| Decision | Status |
| --- | --- |
| Exact Mylyn version and required connectors | Mylyn 4.12 Tasks; Bugzilla no longer mandatory; local lifecycle verified in step 3 |
| Minimum Java version and tested runtime | Java 21; build tested with Temurin 21.0.12.1 |
| EclipseLink/JPA version and possible Jakarta migration | EclipseLink 2.7.16 / javax.persistence 2.2.1; no namespace migration for test enablement |
| H2 and migration scope | H2 2.5.250 / schema 1; no historical migrations; future lifecycle infrastructure retained |
| Supported operating systems and architectures | macOS aarch64 clean install/update tested; Linux/Xvfb CI passes; broader support remains open |
| New Timekeeper version | Decide before completing step 6 |

Installed-IDE, multi-instance and platform acceptance remain the largest
uncertainties. Historical data migration is no longer a release blocker.

## Sources

- [Eclipse releases and documentation](https://www.eclipse.org/documentation/)
- [Tycho versions and Maven/JDK requirements](https://tycho.eclipseprojects.io/doc/latest/tycho-compiler-plugin/plugin-info.html)
- [H2 migration to 2.x](https://h2database.com/html/migration-to-v2.html)

These sources were reviewed when the plan was created. Reconfirm version
choices if work resumes after newer releases become available.
