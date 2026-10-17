# Account data portability acceptance

Local verification on 2026-10-17 for #341, integrated with main’s optional
work-session projects and service administration. The packaged runtime at
`115f6f55854dac9bb3958c39eabe136923c02b0f` has schema 5. Its ZIP SHA-256 is
`26e801c3ec470b0624b08fb16d655d75db911628868ad1c1226a18b9c48ba49d`.
Subsequent documentation and fixture changes do not change that runtime.

| Boundary | Verified behavior |
| --- | --- |
| Complete private download | Administrator, ordinary and read-only exports remain owner-scoped. JSON and CSV contain all six work types, preferences, management metadata and inert import audit. A real export of 11,001 tasks exceeds 20 MiB without list-page truncation. |
| Secrets and spreadsheet reuse | Actual stored token digests, passkey IDs/handles/public keys, another owner’s address and global SMTP secrets are excluded. CSV quotes values and neutralizes formula prefixes after Unicode whitespace/control characters; JSON preserves the original text. |
| Reviewed restore | Fresh IDs retain task hierarchy, external references, labels, optional work-session projects and exact nanoseconds. Identity, password, roles, recovery addresses, passkeys and API tokens remain those of the target account. Source import authority is retained only as inert audit. |
| Validation and concurrency | Malformed ZIP/JSON, broken references, owner substitution, running-timer conflicts, stale work/profile versions and changed archive identity are rejected atomically. Repeated or concurrent commits retain one graph and one durable outcome. |
| Restart and recovery | Two actual JVMs on PostgreSQL 17.11 and 18.6 resume private chunks across a literal container restart and reconcile simultaneous restores. Protected database backup/restore retains work, upload outcomes and receipts together. |
| Browser lifecycle | Actual ZIP download, worker hashing, lost chunk reply/reload/reselection, stale review and lost committed reply are exercised. Read-only users can download repeatedly. Desktop, 390px and 320px layouts have no horizontal overflow and pass automated accessibility checks. |

The integrated server reactor passed all 188 application tests plus the domain,
service and HTTP-client suites. Nine archive integration tests passed on each
real PostgreSQL major. The API contract suite passed 12 tests, web unit tests
passed 58, and packaging/channel tooling passed 74. Full packaged HTTPS Chromium
passed 93 browser scenarios. Full packaged HTTPS WebKit passed 87, with six
expected skips for Chromium-only virtual authenticators and CDP touch fixtures.
All four account portability scenarios passed in both engines.

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
