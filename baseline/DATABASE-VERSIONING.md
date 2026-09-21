# Step 4: Durable schema identity and initialization state

Started from `origin/main` revision `7422d52` on September 21, 2026. PR #192
merged with passing Linux/Xvfb CI. Its receipt-validation review changes are
preserved and now have explicit regression coverage. All databases used here
are synthetic; no personal database was accessed.

Merged as PR #193 (`5e85e8b`) with passing Linux/Xvfb CI. The
[H2-upgrade follow-up](H2-UPGRADE.md) moves normal storage to H2 2.5.250 and
adds backed-up adoption for H2 1.4.194 current-model databases. The sections below
record the original versioning baseline, before that engine upgrade.

## Version contract

New databases and newly converted historical backups contain `TIMEKEEPER_SCHEMA`.
It has exactly one row with `ID=1`, `VERSION=1`, `STATE` and `ORIGIN`.
This is version 1 of the **current Task model**, not historical migration V1.
`ORIGIN` is `NEW`, `LEGACY_V1` or `LEGACY_V2`. Application tables and entity
mappings are unchanged; H2 remains 1.4.194.

| State | Meaning | Normal startup |
| --- | --- | --- |
| `CREATING` | Marker created before JPA schema creation | Refuse; never complete partial DDL automatically |
| `RECOVERING` | Current tables exist; explicit recovery has not completed post-reopen validation | Refuse, even if copied data was committed |
| `READY` | New schema was checked, or recovery data passed post-reopen validation | Open only if the current application-table layout also matches |

The transitions are `CREATING -> READY` for a new database and
`CREATING -> RECOVERING -> READY` for explicit historical recovery. State updates
are single conditional, autocommitted statements. Helpers refuse a caller's
active transaction instead of committing it. Schema creation writes the marker
first because [H2 DDL can commit independently](https://h2database.github.io/html/commands.html#create_table).
Even a marker table without its row is an invalid attempt, never an empty
database to be initialized again.

Recognition checks the marker's exact columns, JDBC column types, supported
version, singleton ID/row, state and origin. Missing/duplicate/null/malformed
records, unknown versions and invalid state/origin values cause refusal before
JPA initialization. No automatic downgrade, guessed version or repair is attempted.
The existing recognition restrictions for views, extra tables and non-PUBLIC
schemas remain in effect. Application column-type/constraint validation is not
expanded by this change.

## Recovery and compatibility

Only the explicit recovery wrapper can create/reopen `RECOVERING` targets. It
retains the source version in the marker, and the converter rejects a target
prepared for another historical version. Ready versioned databases cannot be
used as conversion targets, even when their application tables are empty.
The lower-level converter still accepts unversioned empty targets for existing
API callers/tests; it does not invent provenance for them.

The wrapper promotes the marker only after JPA reopen, read-only comparison of
all mapped values and an unchanged source hash. It then closes the connection,
hashes the final target and publishes the completion receipt. Database state and
the filesystem receipt **are not one atomic transaction**: a late cancellation
or receipt-write failure can leave a validated `READY` database without a receipt.
The user procedure still treats that directory as incomplete and requires a new
attempt. Normal startup checks database state, not sidecar-file presence; it
cannot certify that the receipt was published. No automatic preference switch
occurs in either case.

New receipts use conversion identifier `legacy-v1-v2-to-versioned-1`; verification
also checks the ready marker and historical origin. PR #192's
`legacy-v1-v2-to-current-1` receipts remain verifiable for **unversioned** targets.
Origin's engine, target URL and recomputed-total checks are retained. A receipt
cannot simply relabel a versioned target as the old format.

Existing unversioned current-model databases remain readable **without any
schema or version write**. There is no automatic in-place adoption/stamping.
An explicit backed-up adoption path for these databases is future work; their
compatibility still rests on strict table/column recognition. Unknown/historical
databases are not treated as unversioned current databases.

## Verification

Full isolated-source Java 21 / macOS aarch64 run:

```sh
mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

- 99 database/report tests pass, including 22 new version-marker cases.
- 9 Eclipse UI/integration tests pass; one pre-existing CSV-export test remains ignored.
- All six reactor projects pass and produce the p2 repository/ZIP.

Tests cover versioned data/labels across restart, closed-file copy and SQL
restore; unsupported versions and malformed metadata; incomplete creation
phases; refusal of pending recovery by normal startup; wrong-origin targets;
safe state transitions; active-transaction rejection; and leaving unversioned
databases untouched. Schema/data SQL snapshots are unchanged after rejected opens.

Recovery coverage now additionally verifies ready markers for V1/V2, startup
refusal after cancellation following the data commit, late cancellation after
database validation without receipt publication, all origin-reviewed receipt
metadata fields, and previous-format receipt compatibility. The latter is a
synthetic recreation of PR #192's format, not a fixture from a published release.
The OSGi integration test checks the target's marker while retaining the running
database's connection, data and preferences.

Evidence: `/private/tmp/timekeeper-version-final.log` and reports under
`/private/tmp/timekeeper-version.CxsBpu/`. Temporary evidence may be removed by
the OS. Existing local Eclipse classpaths and `.metadata/` were excluded.

## Remaining work

This is a baseline version/state marker, not a general migration-history engine;
Flyway remains disabled. Existing unversioned-database adoption, previous
published-release fixtures, H2 release-version selection, shared/server
concurrency, incompatible clients and installed-IDE acceptance remain open.
The marker cannot stop older released clients that do not inspect it. Simulated
intermediate states/cancellation are tested, not process-kill, power-loss or
disk-full fault injection. Independent backups are still required.
