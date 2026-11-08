# Notes full-text search pipeline measurements — 8 November 2026

These synthetic H2 measurements exercise canonical Notes writes, the durable
change log, production backfill/replay, private Lucene files and the public HTTP
search/status/rebuild API. They are separate from the [Lucene engine spike](2026-11-08-notes-lucene-spike.md).
They are host measurements, not Synology/NAS performance claims.

## Measured revisions and corpus

- Near quota: `4ee00fd26d525dd10f881ef788ff95581bcf4678`.
- Small corpus after integration with the import changes:
  `24036af774e172928bb7b20ede45019de371dc88`.
- Java 21.0.13, Lucene 10.6.0, pinned H2 2.4.240, macOS; test JVM
  `-Xmx768m` (805,306,368 bytes). No database-engine upgrade was involved.
- Deterministic mixed English/Norwegian Markdown with prose, headings, lists,
  links, YAML, code and technical identifiers. Canonical bytes and the source
  ETag were checked after indexing. Fifty warm public-API queries alternate a
  body-only term and a phrase; returned results include bounded highlighted
  excerpts and current-source revalidation.

| Measurement | Small corpus | Near quota |
| --- | ---: | ---: |
| Canonical bytes | 16,000,000 | 4,950,000,000 |
| Documents | 243 | 75,117 |
| Cold build | 2.134 s | 479.572 s |
| Warm API query p50 | 29.961 ms | 29.608 ms |
| Warm API query p95 | 45.618 ms | 44.340 ms |
| Committed save to successful search | 114.509 ms | 550.477 ms |
| Requested rebuild | 1.168 s | 451.440 s |
| Ready index bytes | 7,187,869 | 2,180,808,294 |
| Sampled peak Java heap | 262,867,536 bytes | 433,194,272 bytes |
| H2 fixture database bytes | 33,763,328 | 11,973,824,512 |

Heap usage was sampled every 50 ms, so this is not a proof of the instantaneous
peak. The large run reused an already seeded canonical SQL fixture, discarded
its earlier private indexes, and rebuilt from scratch. Its 44 ms fixture-resume
inspection is not an ingestion-throughput measurement. The small fixture was
created during its measured run (692 ms of canonical ingestion). The large
Maven invocation's maximum RSS was 1,522,761,728 bytes, including build/test
processes and mapped pages; the Java heap limit alone is not a process-RAM limit.
Other lightweight validation ran on the host.

## Defects exposed by the full pipeline

An initial near-quota run exposed an H2 full scan/sort for UUID metadata pages.
Ordering by the complete existing owner/type/UUID primary key lets H2 stop at
the page limit; the diagnostic plan scanned 32 rows for a 32-record page.
No index or schema migration was necessary.

A subsequent run exceeded the unchanged 20-minute readiness deadline while
frequently committing and refreshing partial backfill readers. Backfill now
checkpoints approximately once per second, keeps unpublished progress in memory,
and refreshes readers only when ready. Ready-state increments still commit
before being considered searchable. Both cold-build and rebuild phases then
passed the original deadline on the complete 4.95 GB corpus.

The configured 16 GB cache limit is a soft aggregate limit, with 1 GiB free-space
headroom. A rebuild temporarily retains old and new indexes; more accounts or
different content can need substantially more space. Parsing one maximum-size
note and a Lucene commit/merge can exceed the worker's per-batch time/byte targets.
See [search operations](../operations/NOTES-WEBDAV.md#search-cache-and-recovery)
for these limits, private per-replica directories and failure/recovery behavior.

## Reproduction

From a clean committed checkout, choose an empty, private disposable directory:

```sh
./mvnw -B -ntp -f server/pom.xml -pl app -am test \
  -Dtest=NotesSearchPipelineBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dquarkus.http.test-port=0 \
  -Dtimekeeper.notes.search.benchmark.bytes=16000000 \
  -Dtimekeeper.notes.search.benchmark.directory=/private/tmp/dagsverk-search-benchmark \
  -DargLine=-Xmx768m
```

Use `4950000000` bytes for the near-quota run and reserve space for the canonical
SQL fixture, both private indexes during rebuild, and Lucene merge work. The
opt-in test writes `measurements.json` in its marked fixture directory. Default
quality runs skip this capacity benchmark; behavioral regression tests remain
part of the normal server suite. Retain the measurements before removing only
the owned, stopped fixture directory.
