# Database baseline, backups and future migrations

The embedded Eclipse backend uses **H2 2.4.240, schema version 3** as the
supported baseline. Historical H2 databases, unversioned databases, and Mylyn
attribute storage are not migration inputs. The maintainer removed historical
migration support because there are no users depending on those formats.

H2 2.4.240 is pinned after acknowledged-write loss was reproduced with
2.5.250 and 2.5.252 during crash recovery and chunk reuse. See the
[dated durability report](../verification/H2-CRASH-DURABILITY.md) for the tested
versions and upstream issue. Existing supported schema-2/3
files retain their layout; this change does not authorize historical migrations.
Stop all users of shared storage and make a closed-file backup before replacing
the runtime. Use the same engine version in every client and H2 server sharing it.
See the [durability verification](../verification/H2-CRASH-DURABILITY.md).

There is no upgrade/recovery wizard, legacy driver, Flyway runtime or historical
migration recipe. Old conversion receipts are not used. Earlier migration
reports under `docs/verification/` describe superseded work, not supported procedures.

## Starting and restarting

Use **Preferences > Timekeeper > Database** to select new, separate storage.
An empty database is initialized with the current schema and a durable
`TIMEKEEPER_SCHEMA` marker. Restart reopens only a recognized, versioned,
`READY` database. Unknown versions/layouts, unversioned databases and unfinished
initialization or migration targets are refused without schema changes.

If an unsupported database is selected, preserve it and choose a different,
empty path. Never delete or overwrite it merely to make startup succeed. The
plugin does not rewrite stored JDBC URLs or silently switch to another database.

## Upgrading the known version-2 baseline

A READY database with the exact supported version-2 layout is upgraded to
version 3 for Work Sessions. The plugin first writes a private SQL backup and
restores it into isolated temporary storage to verify that it is readable. For
local file databases, backups are stored in `.timekeeper-backups` beside the
original database. Memory and TCP/SSL databases use `~/.timekeeper/backups` on
the Eclipse client's computer. Administrators may set
`net.resheim.eclipse.timekeeper.db.upgrade-backup-directory` to another writable
location.

If backup creation or verification fails, the original schema and records stay
unchanged. The upgrade adds the Work Session table, a nullable activity relation
and a transaction-lock table; all previous data rows, IDs, timestamps, labels
and task/project relationships are retained. The existing JDBC URL remains in
use. Subsequent restarts do not repeat the upgrade.

H2 commits DDL independently. An interrupted upgrade stays marked MIGRATING and
is refused at startup. Its error identifies the verified backup; stop all
clients and restore that SQL into separate empty storage before retrying.
Unknown layouts and historical/unversioned files remain unsupported. An older
plugin cannot use schema 3; rolling back requires restoring the pre-upgrade
backup with the matching earlier plugin, preserving later work separately.

## Backing up and restoring the current baseline

1. Stop open activities in Workweek, then stop every Eclipse instance and H2
   server using the database. Copy the closed `.mv.db` file into independent backup storage.
   Keep the original location, JDBC URL and matching plugin/H2 version recorded.
   Never copy an open database file.
2. To test a backup, copy it to a **new** directory. Use the same H2 2.4.240
   runtime and an explicit JDBC URL to the copied file's basename, without its
   `.mv.db` suffix, adding `;IFEXISTS=TRUE`. Do not test by overwriting the original.
3. Deliberately select the copy and restart. Verify tasks, activities, labels and
   totals before using it. To roll back, stop all clients and restore the recorded
   configuration. Keep work added since the backup separate: it is not merged.

Closed-file copy, restart and SQL export/restore have automated synthetic
coverage. Current-schema CSV import/export is a convenience interchange format,
**not** a database backup, historical migration or rollback mechanism. It covers
projects, tasks, activities, work sessions, labels, external references and their relationships;
it does not replace the closed-file backup procedure above. Installed-IDE
acceptance remains in the upgrade plan.

## Infrastructure retained for future migrations

The [data model](../architecture/DATA-MODEL.md#timekeeper_schema) describes the
schema marker. Future migrations need an explicit supported source/target,
verified backup, interruption tests and data-equivalence checks before activation.
Normal startup does not infer a migration from an unknown layout. The existing
version-2 extension above is the only supported embedded upgrade.

This guide covers embedded Eclipse storage. For the independent server database,
use [server operations](SERVER.md) and [full-instance recovery](INSTANCE-BACKUP.md).
