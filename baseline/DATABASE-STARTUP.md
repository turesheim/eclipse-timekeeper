# Step 4: Guard database startup before schema creation

Started on September 21, 2026, from PR #189's branch at `77d1871`. That PR was
still open, so this is a dependent changeset, not a claim that conversion has
merged. Origin's `a84dfc8` review fix rejects activities assigned to both a task
and a project. A regression for that fix was verified and pushed separately to
PR #189 before starting this work.

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

Recognition checks table/column layout, not a durable database version, every
column type/constraint, or record-level consistency. It is intentionally strict:
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

Next: an explicit backup/copy/conversion/recovery workflow with durable version
metadata, followed by previous-release fixtures and shared/server compatibility.
Historical databases are now refused safely at startup, but automatic conversion
is still disabled and no end-user conversion wizard/command is supplied yet.
