# Migration scope simplification

Decision: September 21, 2026. The maintainer confirmed that historical storage
formats have no users and requested removal of their migration support, while
retaining infrastructure for future migrations.

## Removed

- Historical `TRACKEDTASK` V1/V2 conversion and H2 1.4.194 current-model conversion.
- The old H2 reader/JAR, backup conversion/receipt workflow and preference-page actions.
- Unused Flyway 6.3.3/6.4.0 JARs and historical SQL migration resources.
- Tests whose sole purpose was running those retired migration paths, including
  the old-driver TCP probe. No historical fixture or published-release migration
  acceptance is required for this release.

These files remain recoverable from Git history. No user database was opened,
changed or removed. The old baseline reports remain as historical evidence,
with supersession notices; they are not current user instructions.

## Retained

- H2 2.5.250 and the current Task/activity/project/label model.
- A strict version-1 schema baseline, initialization-state marker and rejection
  of unknown, unversioned, malformed or incomplete databases before JPA DDL.
- Generic package-private migration-target creation and explicit validated
  completion. Targets remain blocked from normal startup until ready, and
  existing/pending targets cannot be reused by target creation.
- Synthetic current-model tests for records, relationships, labels, duration,
  rollback, restart, backup copy, SQL restore, nanosecond precision, shared
  storage, TCP and exclusive embedded-file locking.
- Future-migration lifecycle tests: pending-target refusal, validated completion
  after reopen, no overwrite/retry, incomplete-target refusal and caller
  transaction protection.

`MIGRATING` and `MIGRATION` replace recipe-specific recovery states/origins.
No transformation recipes or public migration actions are enabled. Explicit
source-version dispatch, backup/data-copy orchestration and data validation
must accompany the first future schema change. See the
[current database policy and migration contract](../DATABASE-RECOVERY.md).

## Verification

Verified on macOS aarch64 with Java 21 from isolated sources:

```sh
mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

All six reactor modules passed and the p2 repository ZIP was produced:

- 60 database/report tests passed, including 25 schema/lifecycle cases.
- 8 UI/integration tests passed; the existing CSV-export test remains ignored.
- Packaged DB/UI inventories contain no old H2/Flyway JARs, legacy converter
  classes, recovery actions or historical SQL migration resources.
- The first full run caught UI assertions for the removed recovery buttons.
  Updated assertions now verify their absence and accessible storage settings
  when startup fails; the complete rerun passes.

Local evidence: `/private/tmp/timekeeper-simplify-verified.log` and reports/artifacts
under `/private/tmp/timekeeper-simplify.8HECTv/`. No test-skipping flags were used.
Existing EclipseLink/Eclipse/Mylyn warnings remain. Historical test-count
reductions reflect retired support, not skipped current-storage tests.
Installed-IDE/multi-instance acceptance remains open.
