# Database baseline, backups and future migrations

The Eclipse 2026-09 upgrade establishes **H2 2.5.250, schema version 1** as the
supported baseline. Historical H2 databases, unversioned databases, and Mylyn
attribute storage are not migration inputs. The maintainer removed historical
migration support because there are no users depending on those formats.

There is no upgrade/recovery wizard, legacy driver, Flyway runtime or active
migration recipe. Old conversion receipts are not used. Earlier migration
reports under `baseline/` describe superseded work, not supported procedures.

## Starting and restarting

Use **Preferences > Timekeeper > Database** to select new, separate storage.
An empty database is initialized with the current schema and a durable
`TIMEKEEPER_SCHEMA` marker. Restart reopens only a recognized, versioned,
`READY` database. Unknown versions/layouts, unversioned databases and unfinished
initialization or migration targets are refused without schema changes.

If an unsupported database is selected, preserve it and choose a different,
empty path. Never delete or overwrite it merely to make startup succeed. The
plugin does not rewrite stored JDBC URLs or silently switch to another database.

## Backing up and restoring the current baseline

1. Deactivate active tasks, then stop every Eclipse instance and H2 server using
   the database. Copy the closed `.mv.db` file into independent backup storage.
   Keep the original location, JDBC URL and matching plugin/H2 version recorded.
   Never copy an open database file.
2. To test a backup, copy it to a **new** directory. Use the same H2 2.5.250
   runtime and an explicit JDBC URL to the copied file's basename, without its
   `.mv.db` suffix, adding `;IFEXISTS=TRUE`. Do not test by overwriting the original.
3. Deliberately select the copy and restart. Verify tasks, activities, labels and
   totals before using it. To roll back, stop all clients and restore the recorded
   configuration. Keep work added since the backup separate: it is not merged.

Closed-file copy, restart and SQL export/restore have automated synthetic
coverage. The obsolete CSV import/export path is **not** a database backup or
rollback mechanism. Installed-IDE acceptance remains in the upgrade plan.

## Infrastructure retained for future migrations

`DatabaseVersion` records a singleton schema `VERSION`, `STATE` and `ORIGIN`.
Version 1 is the starting point for future supported migrations, not a claim of
compatibility with old experimental version-1 files.

- New database: `CREATING / NEW` → `READY / NEW` after schema verification.
- Future migration target: `CREATING / MIGRATION` → `MIGRATING / MIGRATION`
  → `READY / MIGRATION` only after explicit validation.
- `DatabaseStartup.openMigrationTarget` is package-private infrastructure. It
  creates only an empty target and rejects existing or interrupted attempts.
  Normal startup never opens a pending target or invokes migration logic.
- `DatabaseVersion.migrationValidated` checks the prior state, full current
  table/column layout and an idle writable connection before advancing the
  marker. The caller must validate the **data** first; the marker method cannot
  establish data equivalence itself.
- Unknown schema versions fail closed. No migration steps are registered, and
  no old version is implicitly accepted or stamped during normal startup.

When a future schema change is needed:

1. Define the supported source version and a new target version explicitly.
   Add a version-specific source reader/recipe rather than accepting arbitrary
   older layouts. Keep normal startup separate from migration execution.
2. Back up and preserve the source. Build in separate new storage, with no
   automatic preference change. DDL can commit independently, so interrupted
   attempts stay blocked; do not retry them in place.
3. Apply changes transactionally where possible, close/reopen and compare every
   relevant value, relationship, count, timestamp precision and duration using
   read-only verification connections. Only then mark the target ready.
4. Test interruption, malformed/unsupported versions, failed validation,
   rollback, restart and incompatible clients using synthetic fixtures from the
   actual supported baseline. Document the explicit switch and rollback steps.

The retained hooks and tests cover target lifecycle and refusal safeguards.
Backup orchestration, source/target version dispatch, data transformation,
validation and user-facing migration actions must be implemented for the first
real future migration; they are not supplied by these hooks alone.
