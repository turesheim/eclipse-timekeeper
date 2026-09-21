# Recovering a historical Timekeeper database

This workflow is part of the Eclipse 2026-09 upgrade under development. It
supports the repository's historical V1/V2 `TRACKEDTASK` schemas using **H2
1.4.194**, converting into the current `Task` model. It is not a general H2
upgrade, a CSV importer, or a promise of compatibility with every published
Timekeeper release. Current-model databases do not need this conversion.

The converter never opens your original database: it reads a supplied backup
ZIP, retains a copy, and works inside a newly created recovery directory. It
does not change storage preferences or merge with an existing database.

## 1. Record the old configuration and make a backup

1. Record the installed Timekeeper version, workspace, and **Preferences >
   Timekeeper > Database** settings, including the original JDBC URL. Keep the
   old plugin/Eclipse installation available for rollback.
2. Deactivate any active task. Stop every Eclipse instance and H2 server using
   this database, including clients on other computers. Do not continue tracking
   in the old database after your final backup: later records are not included
   or automatically merged.
3. Make a backup with the **same H2 1.4.194** tool, choosing a new ZIP filename:

   ```sh
   java -cp /path/to/h2-1.4.194.jar org.h2.tools.Backup \
     -file /private-backups/timekeeper-before-upgrade.zip \
     -dir /path/to/database-directory -db h2db
   ```

   Replace these example paths and `h2db` with your database's basename (without
   `.mv.db`). The matching JAR is bundled at
   `net.resheim.eclipse.timekeeper.db/lib/h2-1.4.194.jar` in the source tree, or
   inside the installed database plugin JAR under `lib/`. Do not substitute a
   newer H2 tool. Never reuse an existing backup filename.
4. Keep the original file and an independent copy of the backup. This workflow
   accepts exactly one `.mv.db` entry at the ZIP root, without folders or other
   files. A ZIP of a **closed** `.mv.db` file is also acceptable. Never ZIP/copy
   an open database. If the tool includes auxiliary files, make a separate ZIP
   of the closed `.mv.db` file; preserve the complete original backup too.

H2 distinguishes its offline `Backup` tool from the online `BACKUP TO` SQL
command; see the [official backup documentation](https://h2database.github.io/html/tutorial.html#backup).
The procedure tested here uses the offline tool with all connections closed.
Do not use the Timekeeper CSV Export/Import buttons for migration: they still
need current-model compatibility work.

## 2. Open the recovery tools

Use the upgraded plugin in a separate workspace for the trial. To avoid opening
your usual database at all, launch Eclipse with a temporary in-memory database:

```sh
eclipse -data /path/to/separate-recovery-workspace -vmargs \
  "-Dnet.resheim.eclipse.timekeeper.db.url=jdbc:h2:mem:timekeeper_recovery_session"
```

Use your Eclipse launcher's actual path. This database is temporary: **do not
track real work in this recovery session**. The URL override takes precedence
over preferences; remove it before deliberately switching to persistent storage.

Open **Preferences > Timekeeper > Database > Historical database recovery**.
These actions also remain available when normal database startup has failed.

## 3. Convert the backup

1. Choose **Convert historical backup…** and select your trusted backup ZIP.
2. Select a private, local parent folder with enough free space for the backup,
   extracted source and converted database. The operation creates its own new
   `timekeeper-recovery-<unique ID>` subfolder; it never reuses an earlier attempt.
3. Review and confirm the destination. Conversion runs in the background.
   Cancellation is checked between phases and during file copying/hashing;
   a database phase already in progress must finish before cancellation returns.
4. Wait for **Recovered database validated**. Record the reported project,
   task, activity and open-activity counts and closed duration.

The directory contains:

| File | Purpose |
| --- | --- |
| `started.properties` | Marks the attempt and conversion/receipt format versions |
| `backup.zip` | Retained, byte-verified copy of the supplied backup |
| `source.mv.db` | Extracted historical source; opened read-only |
| `converted.mv.db` | Separate current-model database; not automatically activated |
| `validated.properties` | Completed validation receipt with hashes, versions, totals and the new JDBC URL |

Conversion compares every mapped value before commit, then closes/reopens the
database, loads the model through JPA, and compares all mapped rows again through
read-only connections. It does not infer missing activity end times or seed labels.
The receipt is written only after these checks. Archive and extracted database
limits are 256 MiB and 1 GiB respectively; conversion also holds rows in memory,
so larger datasets may require more memory or a separate migration approach.

Only use backups you trust. This is not a sandbox for hostile H2 database content.
Encrypted/password-protected backups, `.h2.db` PageStore files, multiple databases,
extra Flyway history and unknown/mixed schemas are outside this workflow.

## 4. Verify and deliberately switch

1. Before using the converted database, choose **Verify recovered database…**
   and select the recovery subfolder. This can be done after restarting Eclipse.
   It checks the completed receipt's versions and file hashes, then compares the
   data again without writing either database.
2. Compare the totals with your records. If open activities remain, **do not
   switch yet**: retain the files for review. Conversion preserves them exactly,
   but normal startup's interrupted-activity recovery still needs broader testing.
3. Keep the old database, backup and recorded configuration. When satisfied,
   manually choose **Specified by JDBC URL** and copy `target.jdbcUrl` from the
   receipt into the database preference. It points to the new `converted` file
   and includes `IFEXISTS=TRUE`, preventing creation at an accidentally missing
   location. Save the preferences and restart Eclipse, removing any temporary
   system-property URL override.
4. Inspect the workweek history, reports and Eclipse Error Log before resuming
   normal tracking. Do not point old clients at the converted database, or use
   old and new clients against one shared database.

Java properties files escape punctuation, so `target.jdbcUrl=jdbc\:h2\:...`
in the receipt means `jdbc:h2:...` in the preference. The success dialog also
shows the unescaped URL. Paths must not contain semicolons or line breaks.

The receipt describes the **unused conversion snapshot**, not an ongoing backup
or live database version ledger. Once normal tracking changes the database,
the old hash is expected to fail verification; do not convert it again or edit
the receipt to make it pass. Keep making separate backups of new work.

New conversions also contain an in-database `TIMEKEEPER_SCHEMA` marker with
current-model version 1 and the historical source version. Startup refuses a
marker that is unknown or still `CREATING`/`RECOVERING`. Existing unversioned
current-model databases are not automatically stamped or changed. Receipts from
the initial recovery workflow remain supported for those unversioned targets.

## 5. Failure, interruption and rollback

- If conversion fails or is cancelled, retain the attempt directory for diagnosis.
  No preferences were switched. Retry from a known-good backup into a **new**
  directory, never into the partial output.
- If `validated.properties` is absent (even if a `.pending` file or apparently
  complete database exists), the attempt is incomplete. Do not use its database.
- An unsupported atomic rename or failed receipt write is a failed attempt.
  Files are flushed before publishing the completion receipt, but this is not a
  guarantee against storage failure/power loss. Reverify before first use and
  keep an independent backup.
- The database becomes `READY` after data validation but before the receipt is
  published. A late failure can therefore leave a ready marker without a receipt;
  that is still an incomplete workflow. Do not bypass the missing-receipt rule
  or manually edit the marker. Startup checks database state, not receipt presence.
- To roll back after a deliberate switch: stop the upgraded clients, preserve
  the new database and its later records, then use the old plugin with the old
  configuration and original database. Do not overwrite the original with the
  converted file. **Records added after the switch are not merged back.**

Version adoption for existing unversioned databases, previous published-release
fixtures, shared/server concurrency and clean installed-IDE acceptance remain
open in [the upgrade plan](UPGRADE-PLAN.md). See the
[recovery evidence](baseline/DATABASE-RECOVERY.md) and
[schema-versioning evidence](baseline/DATABASE-VERSIONING.md).
