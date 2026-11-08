# Notes Lucene engine spike — 2026-11-08

This is dated evidence for the initial experiments in issue #579. It does not
establish that production Notes search is implemented or that a later checkout
passes.

The server app pins Lucene core, analysis-common and highlighter to 10.6.0.
The experiments ran with Java 21.0.13 on the local macOS development host.

## Behavior exercised

`LuceneSearchSpikeTest` passed body-only and quoted-phrase matches, title
boosting, English/Norwegian case handling, NFC/NFD query equivalence, technical
identifiers, code text and leading-YAML exclusion. Canonical fixture text
remained unchanged. A hit at the end of an exactly 10,000,000-byte Markdown
document was both indexed and highlighted; the highlighter's default character
cap must be overridden.

Independent file indexes replayed committed SQL generations and entry IDs on
H2 2.4.240, PostgreSQL 17.11 and PostgreSQL 18.6. Scenarios covered rollback,
update, delete, restart, an offline replica, the SQL-commit/index-update gap,
owner isolation and rebuilding an index solely from SQL.

All five scenarios passed with the PostgreSQL 18 fixture. The PostgreSQL replay
scenario also passed independently on 17.11. Disposable containers were removed.
The class is selected in both existing PostgreSQL CI jobs and accepts their
`TIMEKEEPER_TEST_PG_*` fixture variables.

These are synthetic SQL fixtures, not the application's mutation/outbox,
restore fencing, search authorization or multi-replica HTTP acceptance.

## Capacity measurements

The mixed corpus contains deterministic English/Norwegian Markdown, lists,
quotations, code, links and varied technical identifiers. Forty timed phrase
queries follow five warmups. Each query reopens a committed Lucene reader and
loads stored result documents; these are not production API timings.

| Mixed corpus | Documents | Canonical bytes | Index bytes | Index build | Query p50 | Query p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Small | 122 | 8,034,206 | 3,172,174 | 444 ms | 1.390 ms | 2.190 ms |
| Near default quota | 75,924 | 5,000,003,590 | 1,934,206,710 | 211.218 s | 17.241 ms | 40.972 ms |

The large run used `-Xmx768m`. Its before/after indexing heap snapshots were
375,459,576 and 248,971,920 bytes; GC makes these snapshots unsuitable as peak
memory measurements. The macOS `time -l` wrapper reported maximum RSS of
1,545,355,264 bytes for the Maven invocation, including build/test processes and
mapped pages, rather than an isolated steady-state search service. Other
validation was running on the host.

A highly repetitive 5 GB English-text control produced a much smaller
313,098,071-byte index, a 94.670 s build and 8.851 ms query p95. The corpus
therefore materially changes the result. No production resource budget is set
from these measurements.

## Reproduction

From the repository root:

```sh
./mvnw -B -ntp -f server/pom.xml -pl app -am test \
  -Dtest=LuceneSearchSpikeTest -Dsurefire.failIfNoSpecifiedTests=false

/usr/bin/time -l ./mvnw -B -ntp -f server/pom.xml -pl app -am test \
  -Dtest=LuceneSearchSpikeTest#recordSyntheticCorpusBuildQueryAndDiskMeasurements \
  -Dtimekeeper.lucene.spike.bytes=5000000000 -DargLine=-Xmx768m \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The default run uses approximately 8 MB; the explicit large run temporarily
needs space for the canonical SQL fixture and the derived index. Metrics are
written to `server/app/target/lucene-spike-metrics.json`. PostgreSQL scenarios
require the named test fixture variables and are skipped when unconfigured.

Production work still includes durable application mutation tracking, bounded
catch-up/backfill and excerpts, local-cache lifecycle/restore fencing, the
restricted query grammar, current-source permission revalidation, API/UI
pagination, and failure/recovery acceptance.
