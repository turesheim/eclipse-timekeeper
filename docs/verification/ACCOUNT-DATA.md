# Account data portability acceptance

Local verification on 2026-10-17 for #341, integrated with main’s optional
work-session projects and service administration. The packaged runtime at
`69c21b54ee574ec7033b89eaa631506aadac5013` has schema 5. Its ZIP SHA-256 is
`c1dc7ba158c77326672943ec6745d58d3ce91fb3d7e0e438bd8c5dcb592c82a9`.
Subsequent documentation updates retain the same application code.

| Boundary | Verified behavior |
| --- | --- |
| Complete private download | Administrator, ordinary and read-only exports remain owner-scoped. JSON and CSV contain all six work types, preferences, management metadata and inert import audit. A real export of 11,001 tasks exceeds 20 MiB without list-page truncation. |
| Secrets and spreadsheet reuse | Actual stored token digests, passkey IDs/handles/public keys, another owner’s address and global SMTP secrets are excluded. CSV quotes values and neutralizes formula prefixes after Unicode whitespace/control characters; JSON preserves the original text. |
| Reviewed restore | Preview shows record and relationship counts. Fresh IDs retain task hierarchy, external references, labels, optional work-session projects and exact nanoseconds. Identity, password, roles, recovery addresses, passkeys and API tokens remain those of the target account. Source import authority is retained only as inert audit. |
| Validation and concurrency | Malformed ZIP/JSON, broken references, owner substitution, running-timer conflicts, stale work/profile versions and changed archive identity are rejected atomically. Repeated or concurrent commits retain one graph and one durable outcome. |
| Restart and recovery | Two actual JVMs on PostgreSQL 17.11 and 18.6 resume private chunks across a literal container restart and reconcile simultaneous restores. Protected database backup/restore retains work, upload outcomes and receipts together. |
| Browser lifecycle | Actual ZIP download, worker hashing, lost chunk reply/reload/reselection, stale review and lost committed reply are exercised. Read-only users can download repeatedly. Desktop, 390px and 320px layouts have no horizontal overflow and pass automated accessibility checks. |

The integrated server reactor passed all 189 application tests plus the domain,
service and HTTP-client suites. Ten archive integration tests passed on each
real PostgreSQL major. The API contract suite passed 12 tests, web unit tests
passed 58, and packaging/channel tooling passed 74. Full packaged HTTPS Chromium
passed 93 browser scenarios. Full packaged HTTPS WebKit passed 87, with six
expected skips for Chromium-only virtual authenticators and CDP touch fixtures.
All four account portability scenarios passed in both engines. All 13 inline
web-guide images are bundled locally; the packaging test rejects image links
that fall back to GitHub HTML pages.

Replica/restart/backup checks initially passed on `115f6f5`; CI repeats these
against its exact current source. The latest local PostgreSQL runs include the
new task-hierarchy and work-session-label restore regression on both majors.

These use synthetic private fixtures and do not modify existing accounts or the
NAS. WebKit automation does not claim manual Safari, Touch ID or security-key
acceptance; those hardware checks remain explicitly deferred. Limits and
progress recovery are tested; this is not a 250,000-record performance benchmark.

Reproduce the operational checks after building a candidate image:

```sh
python server/scripts/archive_replica_acceptance.py \
  --server-image timekeeper-server:test --expected-commit "$(git rev-parse HEAD)"
python server/scripts/archive_replica_acceptance.py \
  --server-image timekeeper-server:test --expected-commit "$(git rev-parse HEAD)" \
  --postgres-image postgres:17.11-bookworm
```

The container workflow uploads receipts with source/image identity and asserted
checks. Packaged browser receipts identify the distribution digest and source.
Traces and videos containing credentials are disabled. See the
[user guide](../operations/ACCOUNT-DATA.md) and
[desktop/mobile review screenshots](../development/WEB-CLIENT.md#account-data-portability).

## Separate preparation and download (#469)

On 2026-10-28, candidate `8854638f1c44e8c20b4803cfc648e9351c547e5b`
passed all 97 web unit tests and all five packaged account-portability scenarios
in Chromium and HTTPS WebKit. The tested distribution SHA-256 was
`5a9062f9d94d1c70bfbf7a94a403b379a272e23c97fe8532adfc10298ff5411f`.

The browser regressions observe zero download events after **Prepare archive**,
then exactly one after **Download archive**, with a dated server filename.
Read-only sessions repeat both operations; preparing again replaces and revokes
the previous private object URL. Existing upload/review, lost-reply recovery,
concurrent session refresh and duplicate-safe restore checks pass through this
new download flow. Desktop, 390px and 320px layouts pass automated accessibility
and overflow checks. The desktop/mobile review screenshots were refreshed from
the WebKit run and visually inspected. These use disposable local accounts.
