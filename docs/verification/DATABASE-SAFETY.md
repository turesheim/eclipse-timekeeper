# Step 4: Current-model persistence and recovery baseline

Started from `origin/main` revision `186355a` on September 21, 2026, after
PR #186 merged with passing Linux/Xvfb CI. This changeset covers synthetic
current-model persistence and label safety, not the historical schema migration.
No personal database was opened. Local Eclipse classpath edits and workspace
metadata were preserved and excluded from the verification source copy.

## Fixtures and invariants

`CurrentModelFixture` creates the step 1 data through the real JPA entities:
2 projects, 3 tasks, 5 activities, 2 labels and 3 label assignments. The two
repositories deliberately both contain task ID `1`; one task has no activities.
The fixture includes Unicode, manual adjustment, midnight and week boundaries.
Task identity is supplied by a small `ITask` metadata stub, then unlinked; reads
and time calculations require neither a Mylyn workspace nor a live connector.

Assertions cover both directions of task/activity and project/task associations,
label names/colors, summaries, URLs, external project IDs, generated label IDs,
and the original expected totals:

- Total: 19,800 seconds (5 h 30 min); each populated task: 9,900 seconds.
- September 18/19/20, 2022: 1,800 / 13,500 / 4,500 seconds.
- Billable / Internal / unlabelled: 9,900 / 2,700 / 7,200 seconds.

`FileStorageTest` additionally compares every row in all application tables,
including generated activity/label UUIDs and the association tables, before and
after recovery. All file databases are created under JUnit temporary directories.
Validation uses `IFEXISTS=TRUE` and disables schema generation, so an absent file
or table cannot silently be replaced during validation. A separate reopen checks
the production `create-tables` setting on an existing current-model database.

The committed SQL fixture
`net.resheim.eclipse.timekeeper.db/src/test/java/net/resheim/eclipse/timekeeper/db/fixtures/current-model-186355a.sql`
was exported using the mappings from `186355a`, EclipseLink 2.7.16 and H2 1.4.194.
Only Java label initialization/toggling was repaired to seed it; the original
`OneToMany(cascade = ALL)` annotation and all schema-generation mappings were
unchanged. The generated synthetic SA credential was omitted from the export.
This frozen fixture proves read/write compatibility of the new label mapping
with the preceding mapping's tables, with DDL generation disabled. It is **not**
a database from a previous published release or the historical `TRACKEDTASK` schema.

## Defects reproduced and fixed

1. New activities left `labels` null, causing `toggleLabel` to fail. The collection
   is now initialized for both constructors.
2. Toggling could dereference an unsaved label's null ID, and removing a copied
   label used object identity rather than removing the matching stored instance.
   Unsaved labels now match by instance; persisted labels match by ID.
3. Activity deletion cascaded into label deletion. With a shared label this
   caused a foreign-key failure and rollback; a label with no other assignment
   was also at risk of deletion. Labels now use a many-to-many association with
   only persist/merge cascades. The existing `ACTIVITY_ACTIVITYLABEL` table and
   `ACTIVITY_ID` / `LABELS_ID` columns are explicitly retained.

The mapping follows the [JPA 2.2 many-to-many association contract](https://jakarta.ee/specifications/persistence/2.2/apidocs/javax/persistence/manytomany).
No production schema migration, JDBC preference change, or H2/JPA version change
is included. In particular, the disabled Flyway migration was not re-enabled.

## Verification

Full clean-source verification uses Java 21, Maven 3.9.16, Tycho 5.0.4 and the
Eclipse 2026-09 target on macOS/aarch64:

```sh
mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

The local run used an empty temporary Maven settings file to avoid unrelated
private repositories, with the normal dependency cache retained. No test-skip
flags were used. Results: 13 database/report tests and 7 UI tests pass; the one
pre-existing ignored CSV-export UI test remains ignored. All six reactor
projects pass and the p2 repository/ZIP are generated.

The nine new cases cover three label-toggle scenarios and six file-backed cases:
previous-mapping compatibility, restart/closed-file copy, SQL export/restore,
transaction rollback/restart, and deletion with either a shared label or its last
assignment. The three toggle cases and initial file-backed cases failed on the
original null collection. After repairing initialization/toggling alone, the
shared-label deletion test failed with the expected foreign-key violation.
The final mapping passes those regressions.

Local evidence (temporary and subject to OS cleanup):

- `/private/tmp/timekeeper-database-before.log`: original label initialization failures.
- `/private/tmp/timekeeper-database-labels.log`: shared-label cascade failure after the Java-only repair.
- `/private/tmp/timekeeper-database.log`: final full build.
- `/private/tmp/timekeeper-database.RNK1Dk/`: isolated source copy and test reports.

## Recovery boundary and remaining work

For these synthetic H2 1.4.194 current-model databases, a copy of the closed
`.mv.db` file and a full H2 `SCRIPT` / `RunScript` round trip preserve every row.
The file-copy test closes the entity manager and connection pool first, checks
the copy's SHA-256 against the source, verifies the copy, and confirms that
opening the copy did not alter the original. A separate test flushes changes
and rolls them back, then verifies the reopened database against its snapshot.
Transaction rollback is **not** a claim that schema-changing DDL can be rolled back.

Any future migration must still work on a separate copy, leaving the closed
original untouched until verification succeeds. Do not copy an open database or
overwrite an original during restore. These embedded-file tests do not establish
a backup procedure for shared/server databases.

H2 stays at 1.4.194 in this changeset to isolate model safety from file-format
migration. This is not a security or release-support endorsement of that version.
The release's H2 decision remains open: [H2's official upgrade procedure](https://h2database.com/html/migration-to-v2.html)
requires export with the old engine and import into a fresh database with the new
engine; it is not an in-place dependency replacement.

Still outstanding: historical `TRACKEDTASK` conversion, its known duplicate-index
SQL restore failure, migration versioning/failure recovery, previous-release
fixtures, interrupted active activities, shared/workspace/configured-server
startup, multi-instance access and incompatible clients. Label editing/deletion
through the UI and full report/export checks remain step 5 work. The previously
documented Eclipse/Mylyn runtime-log warnings and release-installation checks
also remain open. This is a tested part of step 4, not release readiness.
