# Note and screenshot read-cost investigation

The production baseline was `cc592b91f6bb2c7b287469253b0ce427eedef26e`.
The synthetic `VaultReadPerformanceTest` exposed two complete canonical SQL
payload copies per content read for both ordinary files and journal screenshots.
Both single-copy regression checks failed on that baseline. Canonical readers
already check ownership, existence and deletion; removing the separate metadata
preflight preserves those checks and byte validation while reading one payload.
Native source keys are checked against their canonical resource IDs.

Eight warm reads of a synthetic 4,000,000-byte image in local H2 produced:

| Source | Payload copies, before/after | p50 ms, before/after | p95 ms, before/after |
| --- | --- | --- | --- |
| Ordinary file | 16 / 8 | 21.771 / 15.537 | 24.522 / 34.243 |
| Journal screenshot | 16 / 8 | 18.708 / 10.877 | 22.655 / 12.838 |

These small samples show the reduced work, with timing variation including a
higher ordinary-file p95. They do not reproduce or establish resolution of the
reported 10–20 second NAS delay. The benchmark runs explicitly with
`-Dtimekeeper.notes.read.benchmark=true`; normal verification skips its timing
method and runs the deterministic byte, identity, deletion and copy-count checks.
The targeted server run passed 28 behavioral tests with that timing method
skipped. The explicit benchmark run also completed after the read-path change.
The same 28 behavioral checks passed independently on PostgreSQL 17.11 and
18.6. Both PostgreSQL CI jobs now select the deterministic read regressions.

Browser resource tests establish that concurrent native images share an
in-flight collection-settings lookup, later operations read current settings,
and ordinary references with a known origin avoid that lookup. Wiki image
resolution filters the filename before title extraction and reads every matching
page before resolving relative paths and ambiguity. Older account responses,
invalidated requests and failed old catalog reads cannot populate or evict the
current request cache. All 154 web unit tests passed at this stage.

Reproduce the server investigation from the repository root:

```sh
./mvnw -B -ntp -f server/pom.xml -pl app -am test \
  -Dtest=VaultReadPerformanceTest,VaultSourcesTest,VaultCollectionTest,VaultDavHttpTest \
  -Dtimekeeper.notes.read.benchmark=true -Dsurefire.failIfNoSpecifiedTests=false
```

Metrics are written to `server/app/target/vault-read-performance.json`. Browser
acceptance records request counts and render timings using synthetic notes and
images; full affected verification and packaged-browser results are recorded in
their current receipts. On-NAS timings and representative screenshots remain
necessary to assess the original reported delay.
