# Step 4: Upgrade normal storage to H2 2.5.250

Started from merged PR #193, `origin/main` revision `5e85e8b`, on September 21,
2026. Its Linux/Xvfb CI passed. The maintainer requested the latest H2 release;
[H2's official site](https://h2database.com/) and
[release notes](https://github.com/h2database/h2database/releases) identify
**2.5.250 (2026-08-29)** as the latest stable version checked for this change.

## Dependency and runtime

The bundled normal-runtime JAR and PDE classpath now use `h2-2.5.250.jar`.
The artifact was downloaded from Maven Central; its published SHA-1 matched
`c04a84d307abe871451edbcc71e00b43a2a3ee33`. Its locally computed SHA-256 is
`82a80a2ac06901b03cdb233c663d21c4f49c884bf6d0cf85022729e8db9ab86f`.

H2 1.4.194 remains bundled **only as an offline migration resource**, not on the
bundle/application classpath. `LegacyH2` extracts it into a scoped temporary JAR
and loads it with a platform-parent classloader, separate from the normal driver.
Connection close deregisters the old driver, closes its loader and removes that
temporary file. Shutdown hooks are disabled for scoped old connections. Normal
startup never invokes this reader or falls back to the old engine. It rejects
old-format files with explicit backup-upgrade guidance; a regression confirms
the tested original file remains byte-identical.

The reader rejects TCP/SSL, `AUTO_SERVER` and `INIT` URLs. Production recovery
opens only extracted copies with `IFEXISTS=TRUE;ACCESS_MODE_DATA=r`. Fixture
creation uses writable temporary old-engine connections. This classloader is
**not a security sandbox**; only trusted backups are supported. Keeping the old
JAR for compatibility does not make that version suitable for normal/server use.
Retiring the reader can be considered after the legacy migration support window.

JDBC schema recognition now accepts both H2 1.x `TABLE` and H2 2.x `BASE TABLE`
metadata, while retaining view/schema/unknown-table rejection. Unused H2-internal
package exports were removed. EclipseLink 2.7.16 and `javax.persistence` remain
unchanged; the tested persistence and UI paths work with the new engine.

## Format conversion

H2 documents that old persistent databases require logical export with the old
engine and import into a new database, not an in-place JAR swap. Timekeeper uses
an explicit column-aware JDBC export/import: old-driver SELECTs produce the
expected rows, and parameterized INSERTs populate freshly generated H2 2.5.250
tables. This avoids importing obsolete DDL and the known duplicate-index problem
in raw historical SQL exports. No source file is opened writable by recovery and
no foreign-key checks are disabled in the target.

Supported source boundaries:

| H2 1.4.194 source | New H2 2.5.250 target |
| --- | --- |
| Historical V1/V2 `TRACKEDTASK` schema | Existing verified mapping to the current Task model; marker origin `LEGACY_V1`/`LEGACY_V2` |
| Current Task model, unversioned | All nine application tables copied; new marker origin `H2_1_4` |
| Current Task model, valid ready version-1 marker | Same data copy; old marker remains in the source, new marker records engine-upgrade origin |

Current-model conversion includes labels/assignments, project types, direct
project associations, current-activity pointers and all task/activity fields.
The target remains `RECOVERING` until post-reopen verification succeeds. Ready
or populated targets, invalid source versions/states, extra schemas and invalid
dates are rejected. All mapped values are compared before commit and again after
JPA reopen. H2's different JDBC timestamp text formatting is canonicalized as
typed timestamps; booleans are also normalized without changing their values.

An additional regression found that H2 2.x's default timestamp precision rounds
nanoseconds that H2 1.4.194 retained. New JPA DDL explicitly uses `TIMESTAMP(9)`
for activity start/end and task tick. The negative test failed value verification
before commit; the corrected schema preserves all nine fractional digits through
conversion and JPA reopening. The logical model/schema marker remains version 1;
the engine and conversion recipe are separately identified in the receipt.

Receipts now identify source engine/layout and target H2 2.5.250, using conversion
recipe `h2-1.4.194-to-2.5.250-1`. Earlier receipts describe old-engine targets and
are rejected with reconversion guidance. An unused previous attempt can be redone
from its retained `backup.zip`. If its old converted target has since been used,
back up and upgrade **that current target**, preserving later records. The
[user procedure](../DATABASE-RECOVERY.md) covers this distinction and rollback.

## JDBC settings

The default shared URL retains `AUTO_SERVER=TRUE;AUTO_SERVER_PORT=9090`, removing
`AUTO_RECONNECT` and `FILE_LOCK=SOCKET`. Custom stored URLs are not silently
rewritten. The generated conversion URL is a local file URL with `IFEXISTS=TRUE`.
Use that for the initial trial. One synthetic mixed-mode test opens the new
database and a second connection with an ephemeral server port; this is not full
cross-process/server compatibility or a resolution of fixed-port conflicts.

## Verification

Full isolated-source Java 21 / macOS aarch64 run:

```sh
mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

- 115 database/report tests pass, including 14 H2-upgrade cases.
- 9 Eclipse UI/integration tests pass; one pre-existing CSV-export case remains ignored.
- All six reactor projects pass and produce the p2 repository/ZIP.

The existing historical-conversion suite now creates sources with the actual
isolated H2 1.4.194 driver and targets H2 2.5.250. The Eclipse/OSGi test also loads
the packaged reader, converts a real old-format fixture and confirms the live
database/preferences stay unchanged. Current-model fixtures cover both stamped
and unstamped inputs, unchanged original bytes, labels/relationships and 19,800
seconds, receipt verification, SQL restore, malformed sources, driver isolation,
normal-startup refusal, nanosecond preservation and supported shared settings.

Evidence: `/private/tmp/timekeeper-h2-final.log` and reports under
`/private/tmp/timekeeper-h2.6VH7xN/`. The expected precision regression is recorded
in `/private/tmp/timekeeper-h2-precision.log`. Temporary files may be removed by
the OS. Only the H2 entry in the database project's `.classpath` is part of this
change; all other local Eclipse edits and `.metadata/` were preserved/excluded.

## Remaining acceptance

No personal database was accessed. These are synthetic schema fixtures, not
databases produced by previous published releases. Other source H2 versions,
PageStore/encrypted databases, Flyway-history variants, cross-process clients,
remote-server deployment, OS coverage and clean installed-IDE upgrade acceptance
remain outstanding. CSV import/export compatibility and interrupted-activity
handling also remain open. H2 2.5.250 is the selected runtime; these limits do not
justify silently opening old files or changing preferences in place.
