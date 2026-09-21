# Step 4: Guard database startup before schema creation

> Historical report: the maintainer subsequently removed legacy migration support.
> Historical conversion steps and related release-acceptance requirements below
> are superseded by [migration simplification](MIGRATION-SIMPLIFICATION.md) and the
> [current database policy](../DATABASE-RECOVERY.md). Version/lifecycle infrastructure
> and current-storage tests remain; old test totals are historical results.

Started on September 21, 2026, from PR #189's branch at `77d1871`. That PR was
still open, so this is a dependent changeset, not a claim that conversion has
merged. Origin's `a84dfc8` review fix rejects activities assigned to both a task
and a project. A regression for that fix was verified and pushed separately to
PR #189 before starting this work.

## Main-branch integration follow-up

PR #189 merged into `main` as `54d1640`. PR #190 subsequently merged into
`codex/legacy-database-conversion` as `02ecba7`, rather than into `main`, with
passing Linux/Xvfb CI. Its exact reviewed changes were cherry-picked onto a new
branch based on `origin/main`; no approved production changes were dropped.
The follow-up PR targets `main` directly, avoiding another dependency on the
already-merged conversion branch.

Integration completed in PR #191 (`3d63647`), merged into `main` with passing
Linux/Xvfb CI. The [recovery-workflow follow-up](DATABASE-RECOVERY.md) builds on it.

Origin's review fixes retain cancelled status when startup fails during plugin
shutdown and return an empty label stream when the entity manager is closed.
`DatabaseAvailabilityTest` adds isolated regressions for absent and closed entity
managers, restoring the prior test connection afterward. The closed-manager case
fails when the pre-review guard is restored in the temporary source copy.

Full local verification of the main-based integration passes 44 database/report
cases and 8 UI cases, with the one existing export test still ignored. All six
reactor projects pass and produce the p2 repository/ZIP. Evidence is in
`/private/tmp/timekeeper-integration.log` and the corresponding
`timekeeper-integration` temporary source directory; the negative regression run
is recorded in `/private/tmp/timekeeper-integration-before.log`.
The original verification results below remain the historical PR #190 baseline.

## Startup behavior

`DatabaseSchema` provides shared table/column recognition for startup and the
explicit converter. `DatabaseStartup` inspects a JDBC connection before creating
the JPA factory:

| Database state | Startup behavior |
| --- | --- |
| No application tables | Allow JPA schema creation; verify the resulting table/column layout |
| Recognized current layout | Open with DDL generation disabled |
| Historical V1 or V2 layout | Refuse startup with backup/separate-conversion guidance |
| Unknown, mixed or incomplete layout | Refuse startup without creating missing tables |
| Views or tables outside `PUBLIC` | Refuse startup |
| Read-only database | Refuse time tracking, which requires writable storage |

URLs containing an `INIT` command are rejected before opening a connection because
that command could modify schema before inspection. Non-H2 and unnamed in-memory
URLs are rejected. Named in-memory databases work without a close-delay setting:
the inspection connection remains open until JPA connects and initialization is
verified. JDBC inspection uses the bundled H2 driver directly to avoid reliance
on driver discovery across OSGi class loaders.

The system-property URL override is evaluated before workspace/shared storage
resolution, so isolated test URLs do not require creating a workspace database
directory. Existing shared/workspace/configured URL selection remains otherwise
unchanged. Normal startup does not call the converter, re-enable Flyway, upgrade
H2, change preferences or overwrite databases.

## Failure handling and UI

The startup worker initializes the database before publishing its entity manager
and ready status. Failures publish an error status and are recorded in the Eclipse
Error Log. The Workweek status label shows connecting/failure messages rather than
presenting a failed connection as an empty, ready database. Cleanup closes both
the entity manager and factory on initialization failure or plugin shutdown.

UI preference initialization no longer sleeps waiting for database readiness.
The existing seven default labels, with unchanged names/colors, are initialized
by the database worker before readiness is published, and only when no labels
exist. Other preference defaults remain independent of database startup.

Label preferences opened before readiness are invalid and cannot save. They must
be reopened once storage is available, preventing an initially empty editor from
overwriting labels loaded later. Label reads tolerate an absent entity manager,
and the idle-reactivation path tolerates an unavailable tracked task.

## Verification

Full clean-source Java 21 / Eclipse 2026-09 verification on macOS/aarch64:

```sh
mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

The run used an isolated source copy, an empty temporary Maven settings file and
the normal dependency cache. Local Eclipse classpath edits and workspace metadata
were excluded and preserved. No personal database was opened and no test-skip
flags were used.

- 42 database/report cases pass, including 14 new startup cases.
- 8 UI cases pass, plus the pre-existing ignored CSV-export test.
- All six reactor projects pass and the p2 repository/ZIP are generated.

Startup cases cover new file creation/reopen, idempotent default labels and custom
label preservation, named memory storage, the frozen current-model fixture, V1/V2
refusal, mixed/unknown/view schemas, missing current-model tables, read-only
storage, unsupported URLs and rejection of `INIT` before any file is created.
Full SQL snapshots of schema and rows remain equal before/after opening a current
fixture and before/after refusing historical or unknown layouts. These are logical
schema/data checks, not a claim that a normal H2 connection never updates file
headers or connection settings.

The new UI regression temporarily injects a terminal startup-error status and hides
the live entity manager, restoring both without changing the suite's database.
It verifies preference initialization completes
within three seconds and the label page cannot save. Restoring the old blocking
loop in the temporary source copy causes the expected timeout; the test restores
the status from its worker thread even on failure so subsequent UI tests continue.
The fixed version passes. Existing lifecycle, history and disposal tests also pass.

Local evidence (temporary files may be removed by the OS):

- `/private/tmp/timekeeper-review-189.log`: origin review fix plus its regression.
- `/private/tmp/timekeeper-startup.log`: full fixed-source verification.
- `/private/tmp/timekeeper-startup-blocking.log`: expected UI timeout with the old loop restored.
- `/private/tmp/timekeeper-startup.6qCrqa/`: isolated source copy and reports.

## Limits and next work

The original recognition checks table/column layout, not every application-column
type/constraint or record-level consistency. The subsequent
[versioning change](DATABASE-VERSIONING.md) adds durable identity/state checks for
new/recovered databases; existing unversioned layouts remain unstamped. Recognition is intentionally strict:
extra Flyway history tables, schema variants and previous-release databases need
explicit compatibility work. It never guesses how to repair an incomplete schema.
If new-schema creation itself fails, H2 DDL may leave a partial database; preserve
it for diagnosis. Startup does not attempt a destructive cleanup or retry.

Inspection and initialization are not an atomic cross-process migration lock.
Shared/server startup, concurrent first creation, incompatible clients and the
fixed shared-server port still need separate tests. URL `INIT` support is
deliberately restricted; other existing JDBC settings have not all been audited.
The UI regression simulates a failure status; a clean installed-IDE run against a
refused database is still required. Existing Eclipse/Mylyn runtime-log warnings
remain documented in the earlier reports.

The subsequent [recovery workflow](DATABASE-RECOVERY.md) adds explicit backup
conversion, revalidation and versioned conversion receipts. In-database markers
are added in the [versioning follow-up](DATABASE-VERSIONING.md); existing-version
adoption, previous-release fixtures and shared/server compatibility remain open.
Historical databases are still refused at startup; automatic conversion is disabled.
