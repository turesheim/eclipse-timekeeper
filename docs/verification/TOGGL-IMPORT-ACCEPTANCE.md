# Toggl Track import and Undo acceptance

Local candidate verification on 2026-10-17 for #319. The real supplied Detailed
Report remains unchanged. The committed Member/Stop fixture replaces identities,
project/tag/description text and dates while retaining seven intervals totaling
42:04:22. A second real export, supplied on 2026-10-18, provides the Legacy
User/End fixture with distinct Client and Project columns and the same seven
durations. Its identities, client/project names and timestamps are anonymized;
the original file remains unchanged. Parser and planner regression tests cover
both formats, including Legacy client precedence in both import modes.

| Boundary | Verified behavior |
| --- | --- |
| CSV and source selection | UTF-8/BOM, CRLF, quoting/newlines, strict headers/durations, incomplete/future/error rows, explicit zone/date format and independent source-user selection; workspace metadata is ignored and does not alter duplicate identity. Browser requests exclude other source users and raw email identities. |
| Time and mapping | Exact nanoseconds across midnight and 25-hour DST days; reviewed fallback clients/projects, distinct equally named projects, independent task descriptions and labels. Skipped civil dates are covered by planner tests. Work sessions are explicitly selected (the browser default), preserve each full interval and use only a reviewed client plus description-derived title. Activities remain an alternative; modes have independent duplicate claims. |
| Atomic authority | Owned domain writes, immutable/version-checked mappings, rollback, simultaneous batch retries, exact count/time reconciliation and persistent per-entry source intervals/outcomes. |
| Repeat and resume | Same-source repeats reuse the creator's claims without overwriting edits. Ambiguous unclaimed matches require explicit decisions. A dropped batch reply, reload and original-file re-selection resume at committed progress. |
| Whole-job Undo | Bounded, resumable session/activity-first/catalog-child-first deletion; edited/running/dependency review; preserved reused data; later-import references; daily-part lineage; original-claim retirement and deliberate re-import. Old Undo receipts cannot remove replacements. |
| Operational durability | Two actual JVM replicas on PostgreSQL 17.11 and 18.6; database/server restart; protected logical backup/restore of partial jobs, claims and receipts; cross-owner denial. |
| Browser lifecycle | Module worker and preview disposed on sign-out/account switch; no CSV or credentials in browser storage; safe JSON diagnostics/audit downloads; accessible desktop and 390px layouts. |

## Local runs and limits

- Clean server reactor: 100 application tests plus domain/service/HTTP-client
  suites passed after the work-session extension. Focused import/API/provenance/Undo
  checks cover both modes (15 tests), including continuous DST intervals, independent
  source claims, reused sessions, edits, owner denial and deliberate re-import.
- Web unit tests: 50 passed, including the anonymized real fixture, work-session
  default/client mapping and skipped-date activity batch planning.
- Packaged Chromium: all six import scenarios passed. Both activity and work-session
  jobs resume a 201-row import after a dropped reply and reload, then review edited
  records during Undo. The real seven-row session fixture contributes 42h 4m to
  Track’s monthly client total and creates no companion records.
- Packaged HTTPS WebKit: all six import scenarios passed on `277e042`, including ignored workspace metadata,
  accessibility checks and narrow-screen history. Earlier full-suite and SMTP
  recovery runs remain recorded in the preceding implementation’s CI history.
- PostgreSQL replica/restore runs exercise both import modes on 17.11 and 18.6.
  They verify concurrent session batches, restart and protected restore of a partial
  session job, edited/reused-session Undo and mode-isolated repeat detection.
  JSON receipts record the server source and each asserted durability boundary.
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
