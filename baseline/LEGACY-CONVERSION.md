# Step 4: Explicit historical database conversion

> Historical report: the maintainer subsequently removed legacy migration support.
> Historical conversion steps and related release-acceptance requirements below
> are superseded by [migration simplification](MIGRATION-SIMPLIFICATION.md) and the
> [current database policy](../DATABASE-RECOVERY.md). Version/lifecycle infrastructure
> and current-storage tests remain; old test totals are historical results.

Started from merged PR #188, `origin/main` revision `1b81564`, on September 21,
2026. That PR passed Linux/Xvfb CI. This change adds and tests the conversion
engine; it does not activate automatic migration or provide a finished end-user
migration wizard/command. No personal database was opened or modified.

This is the original H2 1.4.194 baseline. The subsequent
[H2 upgrade](H2-UPGRADE.md) now uses an isolated H2 1.4.194 source and an H2 2.5.250
target, including exact timestamp-value comparison and nanosecond target columns.
Use the current [upgrade procedure](../DATABASE-RECOVERY.md) for end-user steps.

## Supported boundary

`LegacyDatabaseConverter.convert(source, target)` accepts the table/column layout
of the repository's unchanged `V1__baseline.sql`, optionally followed by
`V2__add_project_taskurl_and_tasksummary.sql`. Both connections must use the
verified H2 1.4.194 engine. The source must be opened read-only, and the target
must be a separate, empty database with the current JPA schema already created.
Both connections must be in autocommit mode on entry, with no other clients.

The [versioning follow-up](DATABASE-VERSIONING.md) additionally supports versioned
targets prepared in `RECOVERING` state by the explicit recovery workflow. Their
historical origin must match the source. Versioned `READY` databases are not
conversion targets, even when empty; unversioned empty targets remain supported.

Unknown tables/columns, mixed historical/current schemas, views, other application
schemas, and extra Flyway history tables are rejected rather than silently
discarded. Recognition is deliberately limited to table/column shape, not a
promise of support for every database ever produced by a released plugin.
Previous-release fixtures remain necessary to expand this support boundary.

The new converter is independent of Mylyn and is not called by plugin startup or
the existing CSV importer. It does not re-enable Flyway, upgrade H2, rewrite the
historical SQL files, or alter JDBC preferences.

## Data mapping

| Historical data | Current representation |
| --- | --- |
| `TRACKEDTASK` | `TASK`, preserving repository/task composite identity, tick, URL, summary and current-activity reference |
| `TRACKEDTASK.PROJECT` | `TASK.TASK_PROJECT` plus `PROJECT_TASK` membership |
| `TRACKEDTASK_ACTIVITY` | `TASK_ACTIVITY`, preserving each activity ID and owner |
| `ACTIVITY` | `ACTIVITY`, preserving UUID value as text, timestamps, adjustment flag, summary and composite task reference |
| `ACTIVITY.PROJECT` | `ACTIVITY.ACTIVITY_PROJECT` plus `PROJECT_ACTIVITY` membership |
| `PROJECT` | `PROJECT`, preserving name, repository URL and external ID |

V1 has no project/task-reporting metadata, so those new fields remain null.
Neither legacy schema stores labels or project types: their new tables are left
empty. The separate baseline label specification is not silently imported or
presented as recovered historical data. Current-activity pointers and open end
times are preserved, not automatically stopped or assigned a guessed duration.

Missing task/project references, inconsistent task/activity memberships, current
activities belonging to another task, activities assigned to both a task and a
project, null start/adjustment values and negative
closed durations are rejected. This prevents ambiguous historical data from being
"repaired" through unreviewed guesses. No foreign-key checks are disabled.

## Transaction and recovery behavior

Schema creation happens **before** conversion, outside its data transaction.
The converter performs only reads on the source and inserts/updates on the empty
target. Projects/tasks are copied first, then activities and associations; current
activity pointers are restored after their referenced activities exist.

Before committing, every mapped column of every target row is compared with the
source-derived expectations, including empty label/type tables and generated
project associations. The returned result reports source schema version, record
counts, open-activity count and total closed duration. A failed write or value
comparison rolls back the data transaction. Autocommit is not re-enabled if
rollback fails, avoiding an accidental commit of partial data. Callers should
close and discard a failed target, retaining the original and its backup.
Populated targets are rejected; this is not a merge/import-into-existing-data API.

The converter assumes exclusive offline use and holds the source-derived rows in
memory. It is not a concurrent/server conversion API or a streaming bulk importer.
It refuses a caller's active transaction rather than committing or rolling it back.
Callers own and must close both connections. A commit/connection failure can have
an uncertain outcome; do not automatically retry into the same target.

## Verification

The tests load the historical SQL and baseline data directly from their existing
repository files via test resources. There is no second handwritten schema copy.
All files are synthetic and live in JUnit temporary directories.

Fifteen new conversion test cases cover:

- V1 conversion with absent metadata kept null.
- V2 conversion: 2 projects, 3 tasks, 5 activities and 19,800 seconds, including
  Unicode, manual adjustment, duplicate task IDs across repositories and daily totals.
- Reopening through the current JPA model without schema generation or Mylyn links.
- SQL export/restore of the converted database followed by the same JPA checks.
- Open activity/current pointer, fractional-second tick and project-only activity.
- Eight malformed/ambiguous source cases, leaving the target empty, including
  the dual task/project association rejected by the PR review fix.
- A constraint failure after partial insertion, rollback, reopen and successful retry.
- A test trigger that changes copied summaries: verification detects the change
  and rolls back before commit.
- Writable-source, populated-target and caller-transaction rejection.

Read-only source connections use `IFEXISTS=TRUE;ACCESS_MODE_DATA=r`, consistent
with [H2's documented file-access modes](https://h2database.com/html/features.html#custom_access_mode).
SHA-256 checks confirm the original synthetic source file is byte-for-byte
unchanged after successful conversion and the tested failures. A full SQL restore
of the **converted current-model database** succeeds. This avoids carrying the
legacy duplicate-index DDL into the target; it does not make a raw SQL export of
the unmodified legacy schema restorable.

Local full verification on macOS/aarch64, Java 21 and Maven 3.9.16:

```sh
mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

Used an isolated source copy, the normal dependency cache and an empty temporary
Maven settings file. No tests were disabled through build flags. Results:
28 database/report tests and 7 UI tests pass, with the one pre-existing ignored
CSV-export UI test unchanged. All six reactor projects pass and produce the p2
repository/ZIP. Local evidence is `/private/tmp/timekeeper-legacy.log` and reports
under `/private/tmp/timekeeper-legacy.ewI8ND/`; temporary files may be removed by the OS.

## Required next integration work

1. Detect historical/current/unknown databases before startup schema creation;
   do not silently create empty current-model tables alongside historical data.
2. Add an explicit user-facing copy/backup and conversion workflow, with durable
   conversion/version metadata, post-restart validation and a rollback choice.
   Never overwrite the source or switch database preferences before validation.
3. Test previous published-release databases and decide how to handle Flyway
   history, schema variants and inconsistent records, without weakening rejection
   checks by guessing.
4. Decide the H2 release version and test any separate engine/file-format migration.
5. Cover shared/workspace/server startup, concurrency, incompatible clients and
   interrupted-activity recovery. Verify reports and installation/restart in a
   clean IDE; the earlier runtime-log limitations still apply.

This completes a tested conversion building block, not step 4 or release readiness.
