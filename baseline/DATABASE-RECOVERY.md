# Step 4: Explicit backup recovery workflow

> Historical report: the maintainer subsequently removed legacy migration support.
> Historical conversion steps and related release-acceptance requirements below
> are superseded by [migration simplification](MIGRATION-SIMPLIFICATION.md) and the
> [current database policy](../DATABASE-RECOVERY.md). Version/lifecycle infrastructure
> and current-storage tests remain; old test totals are historical results.

Started from `origin/main` revision `3d63647` on September 21, 2026. PR #191
merged the reviewed startup guards into `main` and passed Linux/Xvfb CI.
This changeset adds an end-user workflow around the tested conversion engine.
No personal database was opened; all verification used synthetic fixtures.

Merged as PR #192 (`7422d52`) with passing Linux/Xvfb CI. Origin's review added
verification of the receipt's engine, target URL and recomputed data totals.
The [schema-versioning follow-up](DATABASE-VERSIONING.md) preserves these checks,
adds regressions for them and introduces an in-database marker for new recoveries.

The later [H2-upgrade change](H2-UPGRADE.md) supersedes the engine/UI boundaries
below: normal storage uses H2 2.5.250 and backup upgrades also accept current-model
H2 1.4.194 sources with labels. Earlier receipts require reconversion, as explained
in the current [user procedure](../DATABASE-RECOVERY.md).

## Behavior and recovery boundary

The Database preference page offers **Convert historical backup…** and
**Verify recovered database…**, including when normal storage startup fails.
Both run outside the UI thread and operate without changing the running entity
manager, database preferences, original database or CSV importer.

`DatabaseRecovery` reserves a new directory atomically before any output. It
copies a supplied H2 backup ZIP, verifies the copy with SHA-256, extracts exactly
one root-level `.mv.db` to a fixed filename, and opens it read-only. Unknown
schemas, multi-file archives, paths inside archives, non-MVStore formats and JDBC
setting injection through the output path are rejected. Bounded streaming limits
the ZIP to 256 MiB and the extracted/converted files to 1 GiB. Only trusted H2
1.4.194 backups with the existing default credentials are supported.

The existing converter writes into a separate current-model target and compares
all values before commit. The recovery wrapper closes/reopens the target, reads
Project/Task/Activity objects through JPA, closes it again and invokes the new
read-only `LegacyDatabaseConverter.verify` to compare every mapped value after
reopening. The extracted source hash must remain unchanged.

A versioned `started.properties` records the attempt. A separately written,
flushed, atomically renamed `validated.properties` records successful completion,
the engine/conversion/receipt versions, SHA-256 file hashes, counts, duration and
new URL. These are **conversion receipts, not an in-database schema version**.
An incomplete/pending receipt never passes the verification action. Cancellation
after commit still leaves an unvalidated attempt, not a published success.
Failures preserve the backup and all diagnostic output; retries require a fresh
directory. No deletion, automatic preference switch or in-place migration occurs.

Read the [user procedure](../DATABASE-RECOVERY.md) for backup creation, isolated
trial startup, validation, intentional activation and rollback. The procedure
explicitly defers switching databases with open activities until reviewed,
because normal startup's interrupted-activity handling remains unverified.
It also explains that receipt hashes cease to match after normal time tracking.

## Verification

Full local verification on macOS/aarch64 with Java 21, an isolated source export,
an empty Maven settings file and the normal dependency cache:

```sh
mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

- 64 database/report tests pass, including 20 new recovery cases.
- 9 Eclipse UI/integration tests pass; the one existing CSV-export test remains ignored.
- All six reactor projects pass and produce the p2 repository/ZIP.

Recovery tests cover V1 and V2 offline H2 backups; byte-identical original,
retained archive and extracted source; all baseline V2 counts and 19,800 seconds;
spaces/Unicode paths; open activities; successful read-only revalidation;
unknown schemas and inconsistent associations; cancellation before reservation
and after commit; refusing an existing directory; traversal/multi-file/unsupported
archives; invalid ZIPs; path-based JDBC setting injection; bounded copying;
changed summaries with unchanged totals; and unknown receipt versions.

The Eclipse-runtime integration case converts/reverifies a synthetic historical
backup while the normal test database is open. Its original entity manager,
task count, ready state and storage preferences remain unchanged. The existing
startup-failure UI regression also checks that both recovery buttons are enabled
without changing preferences.

Evidence: `/private/tmp/timekeeper-recovery-final.log` and reports under
`/private/tmp/timekeeper-recovery.E43WDb/`. Temporary evidence can be removed by
the OS. Local `.classpath` edits and `.metadata/` were excluded and preserved.

## Remaining work

This is not release acceptance or a complete schema-migration framework. Durable
**in-database** identity/state is added by the [follow-up](DATABASE-VERSIONING.md);
adoption of existing unversioned databases remains open. Previous published-release files,
other H2 versions/storage formats, real server/shared mode and concurrent clients
remain outside the verified boundary. The workflow expects exclusive access to
its private recovery folder. It does not lock other clients or detect later
writes to the original database after the backup was made.

Automated tests cover the engine, OSGi coexistence and action availability, not
native file dialogs or a full manually installed IDE session. Process crashes,
power loss and disk-full errors are not fault-injected; incomplete receipts fail
closed, but independent backups remain necessary. Existing Eclipse/Mylyn runtime
log issues and the ignored CSV-export test still need follow-up.
