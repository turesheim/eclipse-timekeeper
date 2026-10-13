# Timekeeper upgrade plan

Created: September 21, 2026.

Goal: Timekeeper can be installed and used in Eclipse IDE 2026-09
(Eclipse Platform 4.41), preserving records in the new versioned database baseline.
Historical data migrations are explicitly out of scope.
This was the latest stable Eclipse release when the plan was created.

Recorded checkpoint: October 12, 2026. Timekeeper 2.0 requires Eclipse 4.41 and
Java 21 and uses **H2 2.5.250 / schema version 2**. The native domain, service,
project/task/activity UI and optional Mylyn adapter from #211 are implemented.
Historical migration support remains out of scope; experimental schema-1 files
are unsupported and are not upgraded automatically.

The dated results below preserve the original upgrade evidence, including the
earlier schema-1 installed sessions and test totals. They are not evidence of a
final 2.0 build or current publication. Use the checkpoint below for remaining
release gates and [DATABASE-RECOVERY.md](../operations/DATABASE-RECOVERY.md) for current storage
and backup policy.
See the [scope-change report](../verification/MIGRATION-SIMPLIFICATION.md).
See the [original baseline](../verification/README.md),
[step 2 results](../verification/BUILD-UPGRADE.md) and
[test-enablement results](../verification/TEST-UPGRADE.md) and
[Mylyn/UI results](../verification/MYLYN-UI-UPGRADE.md) and
[database-safety results](../verification/DATABASE-SAFETY.md) and
[storage-mode acceptance](../verification/STORAGE-MODES.md) and
[installed-IDE acceptance](../verification/INSTALLED-IDE.md) and
[concurrent installed-instance acceptance](../verification/CONCURRENT-INSTANCES.md) and
[interrupted-activity acceptance](../verification/INTERRUPTED-ACTIVITY.md) and
[Error Log acceptance](../verification/ERROR-LOG.md).
The [database policy](../operations/DATABASE-RECOVERY.md) documents current backups and the future migration contract.

For current release procedures, see [RELEASING.md](RELEASING.md). The
checkpoint and historical results below retain the status recorded on their
respective dates; current work is tracked in GitHub issues and pull requests.

## Release gates recorded at the October 12 checkpoint

At this checkpoint, main is
[`baa407b`](https://github.com/turesheim/eclipse-timekeeper/commit/baa407b7ad268b6b042810bdeab6c048a0a8a1fa).
[Main CI 38243900341](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38243900341)
passed publication, Linux, macOS and Windows jobs. Its downloaded Linux reports
contain **142 tests with no failures, errors or skips**. Workweek label rendering
(#235 / #240) and direct native task activation (#234 / #241) are merged.
The `v2.0.0` release remains a draft with no assets; no 2.0 RC has been published.

The maintainer decided on October 12 to complete the **full client/server
scope before 2.0.0**. #187 and all five children belong to the 2.0 milestone.
The completed embedded foundation in #211 does not complete these follow-ups.

| Gate | Evidence and remaining work | Status |
| --- | --- | --- |
| Workweek labels (#235) | [PR #240](https://github.com/turesheim/eclipse-timekeeper/pull/240) is merged; rendering and image-lifecycle regressions run in both native runtimes. | Complete |
| Native task activation (#234) | [PR #241](https://github.com/turesheim/eclipse-timekeeper/pull/241) is merged; direct menu activation/deactivation and native/Mylyn switching regressions pass. | Complete |
| Combined feature acceptance | Merged-main Linux CI passed 142 tests, including both native runtimes, real Mylyn switching, installed JiraConnector and second-process restart. The earlier local candidate receipt below is historical evidence. | Verified on merged main |
| Client/server deployment (#187) | Required for 2.0: versioned API/ownership (#217), Quarkus server (#218), remote Eclipse client (#220), standalone web frontend (#221), and owner-scoped change notification/synchronization (#219). Both clients must use server authority, reject cross-owner access and handle stale/concurrent operations. | Open |
| Optional Mylyn Error Log (#243) | The installed Task List symbolic-color parser error remains open in [ERROR-LOG.md](../verification/ERROR-LOG.md). [#243](https://github.com/turesheim/eclipse-timekeeper/issues/243) records reproduction, local patch validation and official-fix acceptance. Upstream follow-up is deferred; no experimental Mylyn bundle is distributed. Consume an official correction and recheck a clean installed workspace. | Open |
| Jira acceptance (#230) | The maintainer checked the acceptance criteria and [closed #230 with “Tested OK”](https://github.com/turesheim/eclipse-timekeeper/issues/230#issuecomment-6123692190). Automatic evidence uses the released connector with synthetic REST responses. The exact manually tested Jira deployment/authentication combination is not recorded; do not infer certification of all combinations. The separate Mylyn Error Log gate is #243. | Accepted by maintainer |
| Default planning label (#245) | [PR #246](https://github.com/turesheim/eclipse-timekeeper/pull/246) is merged as `8a03446`; #245 is closed. Planning is included in the appropriate default label, preserving user-customized labels and existing records. | Complete |
| Final tagged build and release artifacts | Build the accepted commit through [release-p2.yml](../../.github/workflows/release-p2.yml). Record the actual tag, commit, feature version, test results, archive URL, checksum and provenance, plus client/server deliverables. Current candidate artifacts do not meet this gate. | Open |
| Public GitHub p2 installation | After publication and Pages deployment, install from the public stable channel in a clean Eclipse 4.41 / Java 21, without GitHub authentication. Check base and optional features, restart, embedded/remote settings and synthetic records. Also verify the archive path and the retained release-specific site. | Open |

Do not publish a release candidate or final release merely because individual
PR checks are green. Full client/server delivery, installed acceptance,
the Error Log and final artifact checkboxes remain separate release gates.

### Combined candidate receipt

The local integration checkout merges the recorded #240 and #241 heads onto
main `8a5afbee06deef9b4d7fde2240c768eb5a23b421` (before the squash merges). Its source tree is
`c074019ea2ce95cf1a181780ff4ffda7aa0029be`; local merge commit
`cc63376d85fbe72152c5ceb1589a4bff28e9d674` is not a published release or tag.

```sh
./mvnw -B -ntp -s /private/tmp/timekeeper-clean-settings.xml clean verify \
  -Pjira-connector-tests -Dtycho.localArtifacts=ignore
```

Verified October 12 on Temurin 21 / macOS aarch64: **142 tests, zero failures,
errors or skips**. The settings file contains a minimal Maven configuration
without private repository mirrors. The two feature groups are the base
Timekeeper feature and optional Mylyn feature, both `2.0.0.202610121114`.
The production repository passed `scripts/p2_release.py`'s repository validator;
all declared artifacts exist and no Timekeeper test bundles are included.

Candidate archive:
`net.resheim.eclipse.timekeeper-site/target/net.resheim.eclipse.timekeeper-2.0.0.202610121114.zip`.
SHA-256:
`4b7d12f10cf80e156e9d31f9d4d9e1d1998762778a4b7d4358d04632636260d3`.
It exists only in the temporary local acceptance checkout and is not attached to
the release draft. Rebuild from the accepted tag before producing final assets.

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
- See [baseline/README.md](../verification/README.md) for commands, expected values and limitations.
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
- See [baseline/BUILD-UPGRADE.md](../verification/BUILD-UPGRADE.md) for detailed verification.

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
are documented in [baseline/MYLYN-UI-UPGRADE.md](../verification/MYLYN-UI-UPGRADE.md).
Clean installed-IDE verification fixed missing Tasks search contributions and a
duplicate logging provider through feature metadata. The runtime-error checkbox
remains open: Mylyn's theme color parser also fails in the installed Task List.
See [installed-IDE acceptance](../verification/INSTALLED-IDE.md).

## 4. Secure the database baseline and future migration infrastructure

Scope decision: historical database/Mylyn data migration is not required.
The legacy converters, recovery UI, old H2 runtime and unused Flyway libraries
have been removed. Old report results below `baseline/` are retained as history.
Do not add published-release fixtures or legacy migration recipes for this release.

- [x] Preserve the current Task/activity/project/label model and reporting.
- [x] Use EclipseLink 2.7.16 / javax.persistence 2.2.1; no Jakarta migration is needed.
- [x] Use H2 2.5.250 as the only database engine.
- [x] Establish the current schema version 2 as the supported baseline; initialize only empty databases.
- [x] Reject unknown/unversioned schemas and malformed versions without changing them.
- [x] Persist initialization/migration state and refuse incomplete targets at normal startup.
- [x] Retain generic empty migration-target creation and explicit validated completion.
  There are no active migration recipes. Future runners must add source-version
  dispatch, backups, data transformation and validation before using these hooks.
- [x] Document the future migration contract, current backups and deliberate rollback.
- [x] Test current records, labels, relationships, time totals, timestamp precision,
  restart, closed-file copy, SQL restore and transaction rollback with synthetic data.
- [x] Test future migration lifecycle safeguards without implementing old migrations.
- [x] Verify storage preferences and supported server configurations in installed Eclipse.
  The installed preference dialog selected workspace, shared `AUTO_SERVER`, an
  explicit file URL and a local-only H2 TCP server in turn. Each selection took
  effect after restart and opened a separate schema 1 READY database. The
  persistence tests additionally cover separate JVMs, paths with spaces,
  missing database refusal and server-side version/state guards.
- [x] Test concurrent access from multiple installed Eclipse instances.
  Two isolated installed clients read the same shared database and one committed
  a new activity while the other remained connected. A stale-client shutdown
  did not overwrite the addition, and a client saved successfully after the
  original AUTO_SERVER owner shut down. A fixed-port conflict failed visibly
  without changing the database. There is no live cross-process cache refresh
  or active-activity owner/lease; simultaneous edits to the same task are not
  supported. See [the acceptance report](../verification/CONCURRENT-INSTANCES.md).
- [x] Verify new/current database startup and interrupted-activity behavior in installed Eclipse.
  An already durable open activity retained its ID and start time across restart,
  `SIGKILL` and another restart while its Mylyn task remained active. With the
  task inactive, startup durably cleared the current reference and ended the
  activity at its persisted last-active tick. The run fixed an uncommitted,
  potentially future recovery timestamp and a null idle-status startup race.
  An immediate kill after a brand-new activation exposed a bounded durability
  window before the record reached recoverable storage; see the acceptance report.

Completion criterion: new/current-baseline storage works, records are preserved,
unsupported databases fail safely, and future migrations have a documented,
tested lifecycle. Backward migration support is not a release criterion.

See [migration simplification](../verification/MIGRATION-SIMPLIFICATION.md) and the
[current database policy](../operations/DATABASE-RECOVERY.md). Historical baseline totals
remain 2 projects, 3 tasks, 5 activities, 2 labels, 3 assignments and 19,800 seconds
for the current synthetic fixture. Next: remaining automated and cross-platform acceptance.

The September storage-baseline build passed 64 database/report/packaging tests and 10 UI/integration
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
  [dependency audit](../verification/DEPENDENCY-AUDIT.md).
- [x] Update GitHub Actions for checkout, Java, caching, reports and artifacts.
- [x] Run automated tests for time tracking, manual editing, labels,
  report export, import and restart.
  A clean macOS aarch64 run verifies task activation/deactivation and recorded
  time, manual activity editing, labels and relationships after file-backed
  restart, every bundled report template, and current-schema CSV export/import.
  The run passed 64 database/report/packaging tests and 10 UI tests. The manual
  editor remains guarded on Linux due to the known SWTBot editor-focus issue;
  see [test-enablement results](../verification/TEST-UPGRADE.md).
- [x] Run UI tests in CI with the required display/Xvfb configuration.
  PR #185 passed Linux/Xvfb CI before merge; subsequent changes require their own CI result.
- [x] Check idle detection on Windows, macOS and Linux.
  A native smoke test passed on GitHub-hosted Windows x86_64, macOS aarch64 and
  Ubuntu x86_64/X11, and also on the local macOS aarch64 system.
- [x] Verify Apple Silicon and assess X11/Wayland separately; document limitations.
  Apple Silicon and X11/XScreenSaver passed. Pure Wayland is unsupported by the
  current detector; XWayland requires a usable `DISPLAY` and XScreenSaver.
- [x] Document the OS/architecture combinations that have been tested and are supported.
  See [platform acceptance](../verification/PLATFORM-ACCEPTANCE.md) for validation
  levels and the combinations that remain build-only or unverified.

Completion criterion: Relevant tests are discovered, executed and pass. CI
produces test results and a p2 repository, and platform support is documented.

Results and deviations: Clean `verify` passes on macOS aarch64 with 64 database/report
tests and 11 active UI tests passing, including manual activity time-range editing
and the native idle smoke test. The CI matrix passes on Linux/X11 x86_64, macOS
aarch64 and Windows x86_64. Linux runs the full UI suite; macOS and Windows run
the native idle test in the UI harness after compiling and packaging every module.
The manual-edit test remains guarded on Linux due to the known SWTBot editor-focus
issue. Installed-IDE acceptance outside macOS remains limited. See
[test-enablement results](../verification/TEST-UPGRADE.md) for the coverage mapping and
remaining test limitations and [platform acceptance](../verification/PLATFORM-ACCEPTANCE.md)
for the support boundaries.

## 6. Verify installation and prepare the release

- [x] Update feature and p2 metadata for the selected dependencies.
- [x] Build the p2 repository and install into a clean Eclipse 2026-09.
- [x] Check that required dependencies are included or can be installed automatically.
- [x] Test plugin updates against the new versioned database baseline.
  Historical data migration is out of scope.
- [x] Verify restart, settings and existing data after upgrading.
  Restart and stored activity were verified during the package update. The
  follow-up installed-IDE run verified preference switching and restart across
  workspace, shared, explicit file and local H2 TCP storage.
- [ ] Check the Eclipse Error Log for Timekeeper and dependency errors.
  All retained installed and current test-runtime logs are classified in the
  [Error Log report](../verification/ERROR-LOG.md). Timekeeper-owned errors are fixed or
  belong to deliberate negative scenarios, but the installed Mylyn theme-parser
  dependency error remains an open release blocker.
- [x] Update version numbers, README, CHANGES and any migration instructions.
  Version 2.0.0 is confirmed in the Maven, OSGi bundle and feature metadata.
  Maven `-SNAPSHOT` and OSGi `.qualifier` suffixes remain intentionally so
  verified builds receive a timestamped p2 version until publication. Release
  notes now describe the supported schema baseline and the lack of historical
  migration support.
- [x] Document supported Eclipse/Java versions, installation and rollback.
  The README documents Eclipse 2026-09 / Java 21 requirements, evidence by
  platform, p2 installation, separate-storage upgrade and deliberate rollback.
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
[the installed-IDE report](../verification/INSTALLED-IDE.md) for artifacts and limitations.

The follow-up activity lifecycle test passes (63 database tests). It also fixes
`Task.endActivity(LocalDateTime)`, which previously ignored the timestamp used
by the idle-time recovery path. Current-schema CSV export and manual editing now
pass in the UI harness, and installed interrupted-activity scenarios are verified.

## Decisions to track

| Decision | Status |
| --- | --- |
| Exact Mylyn version and required connectors | Mylyn 4.12 Tasks; Bugzilla no longer mandatory; local lifecycle verified in step 3 |
| Minimum Java version and tested runtime | Java 21; build tested with Temurin 21.0.12.1 |
| EclipseLink/JPA version and possible Jakarta migration | EclipseLink 2.7.16 / javax.persistence 2.2.1; no namespace migration for test enablement |
| H2 and migration scope | H2 2.5.250 / schema 2; no historical migrations; experimental schema 1 is unsupported; future lifecycle infrastructure retained |
| Supported operating systems and architectures | macOS aarch64 fully exercised; Linux x86_64/X11 full CI; Windows x86_64 build/native idle smoke; see platform acceptance for limitations |
| New Timekeeper version | 2.0.0; build qualifiers remain until publication |

Installed-IDE acceptance outside macOS remains limited. Multi-instance storage
is characterized but intentionally lacks
live cache synchronization and active-activity ownership. Historical data
migration is no longer a release blocker.

## Sources

- [Eclipse releases and documentation](https://www.eclipse.org/documentation/)
- [Tycho versions and Maven/JDK requirements](https://tycho.eclipseprojects.io/doc/latest/tycho-compiler-plugin/plugin-info.html)
- [H2 migration to 2.x](https://h2database.com/html/migration-to-v2.html)

These sources were reviewed when the plan was created. Reconfirm version
choices if work resumes after newer releases become available.
