# Toggl Track import and Undo acceptance

Local candidate verification on 2026-10-17 for #319. The real supplied Detailed
Report remains unchanged. The committed Member/Stop fixture replaces identities,
project/tag/description text and dates while retaining seven intervals totaling
42:04:22. User/End compatibility cases are synthetic, not another captured export.

| Boundary | Verified behavior |
| --- | --- |
| CSV and source selection | UTF-8/BOM, CRLF, quoting/newlines, strict headers/durations, incomplete/future/error rows, explicit zone/date format and independent source-user selection. Browser requests exclude other source users and raw email identities. |
| Time and mapping | Exact nanoseconds across midnight and 25-hour DST days; reviewed fallback clients/projects, distinct equally named projects, independent task descriptions and labels. Skipped civil dates are covered by planner tests. Work sessions are not inferred. |
| Atomic authority | Owned domain writes, immutable/version-checked mappings, rollback, simultaneous batch retries, exact count/time reconciliation and persistent per-entry source intervals/outcomes. |
| Repeat and resume | Same-source repeats reuse the creator's claims without overwriting edits. Ambiguous unclaimed matches require explicit decisions. A dropped batch reply, reload and original-file re-selection resume at committed progress. |
| Whole-job Undo | Bounded, resumable activity-first/catalog-child-first deletion; edited/running/dependency review; preserved reused data; later-import references; daily-part lineage; original-claim retirement and deliberate re-import. Old Undo receipts cannot remove replacements. |
| Operational durability | Two actual JVM replicas on PostgreSQL 17.11 and 18.6; database/server restart; protected logical backup/restore of partial jobs, claims and receipts; cross-owner denial. |
| Browser lifecycle | Module worker and preview disposed on sign-out/account switch; no CSV or credentials in browser storage; safe JSON diagnostics/audit downloads; accessible desktop and 390px layouts. |

## Local runs and limits

- Full server reactor: 95 application tests plus domain/service/HTTP-client suites
  passed on `2a6e141`. Subsequent per-entry audit and expanded Undo-step handling
  passed the focused 12-test ledger/API/import/Undo run. The final CI repeats the
  complete server suite at its checked-out source.
- Web unit tests: 48 passed, including the anonymized real fixture and skipped-date
  batch planning.
- Full packaged Chromium: 65 passed, one SMTP-only test skipped on `2a6e141`.
- Packaged HTTPS WebKit: six passed on `cb69a7d`, including administrator setup,
  actual loopback SMTP recovery and all four import scenarios. Audit JSON downloads
  retain source lines, exact durations and per-entry outcomes.
- PostgreSQL replica/restore runs passed with image source `7954211` on both
  supported PostgreSQL versions. Their JSON receipts record the actual server
  source and every asserted durability boundary.
- Eclipse reactor compilation and all 96 local persistence tests passed. The
  optional repository hook delegates to ordinary save in the Eclipse adapter;
  no Eclipse import UI is added.
- API contract tests: 12 passed. Packaging/publication tooling tests: 72 passed.

These are disposable local fixtures; no existing account, real mail service or
NAS was changed. WebKit automation does not claim a separate manual Safari test.
The largest exercised browser import has 201 source rows; server Undo tests use
300 entries. Bounds and resumable progress are verified, not a 100,000-row
performance benchmark. Installed Eclipse and native Synology deployment checks
remain in the normal CI matrix.

Reproduce the focused operational run after building the candidate image:

```sh
python server/scripts/toggl_replica_acceptance.py --server-image timekeeper-server:test
python server/scripts/toggl_replica_acceptance.py --server-image timekeeper-server:test \
  --postgres-image postgres:17.11-bookworm --output server/target/toggl-pg17-acceptance.json
```

The container workflow runs these checks and uploads the owner/secret-free JSON
receipts. Browser acceptance uses the checksum-verified server archive; its
receipt records the archive digest and source commit. The screenshots in the
[web guide](../development/WEB-CLIENT.md#toggl-track-import) come from that browser
run. Traces containing authorization headers are disabled.
