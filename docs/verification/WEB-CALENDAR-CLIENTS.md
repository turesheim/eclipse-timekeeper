# Web calendar and client hierarchy

The implementation for #295 also includes the requested server/web client model:
clients have a name and color, each project and work session has one client, and
work sessions have a title separate from their description. Eclipse models/UI
remain independent; server-side metadata survives legacy project/session edits.

Local validation used Java 21 and a checksum-verified server distribution built
from `5d260bb60af6e41a02c19397f49d30ab29c9ce38` (clean source), SHA-256
`b6c5654fa7dae90e54807864f97d7081e02df58063a87c0996904d6b1d4a2846`.
The following test-only commit extends the startup/HTTP assertions without
changing application code. CI rebuilds and verifies the pull request head.

- Maven server reactor: 102 tests passed (domain, service, HTTP client and server).
- OpenAPI/schema validation: 12 tests passed.
- Web pure model/transport/credential tests: 24 passed.
- Real Chromium against the packaged JVM: all 29 browser tests passed.
- Packaged HTTP acceptance: all 16 contract cases passed, followed by graceful
  restart, forced restart, durable command replay, revocation and long polling.

Browser coverage includes seven local calendar days, task issue links/activity
lists, multi-day sessions, repeated/skipped DST slots, actual pointer/touch moves,
both resize handles, keyboard controls, nanosecond preservation, independent live
activity timers, copy/paste without activities, stale writes, rejected future
starts, lost committed replies and read-only access. Client coverage includes
create/edit/delete, multiple projects, separate session title/description,
referential deletion restrictions, color updates, text contrast and reload.
Desktop/mobile screenshots were inspected locally; private test credentials and
browser traces are not retained in the repository.

Server tests cover cross-owner references, versioned client changes, startup
backfill of legacy owners, preservation of existing IDs/times, and idempotent
migration. JSON/CSV v3 round trips remap client/project/session references and
retain names, colors, titles and exact intervals. Older imports receive General;
unsupported CSV downgrades fail before silently discarding the client graph.

The local test server uses an isolated database. This evidence does not imply
that the user's running Docker deployment has been updated. PostgreSQL and
multi-replica acceptance run separately in the Server containers workflow.
