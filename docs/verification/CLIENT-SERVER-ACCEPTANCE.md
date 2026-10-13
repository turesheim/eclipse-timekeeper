# Client/server acceptance for #187

Verified October 13, 2026 against merged main
[`102a341fdcffd73fbc1622e39894befbe1badf18`](https://github.com/turesheim/eclipse-timekeeper/commit/102a341fdcffd73fbc1622e39894befbe1badf18).
The foundation (#211), API (#217) and server (#218) are completed. The remote
Eclipse, web and synchronization implementations from #248 and #251–#264 were
integrated by [#266](https://github.com/turesheim/eclipse-timekeeper/pull/266).
[#267](https://github.com/turesheim/eclipse-timekeeper/pull/267) reorganized that
implementation and documented local Docker/Tailscale access.

## Requirements and evidence

| Acceptance requirement | Implementation and verified behavior |
| --- | --- |
| Versioned Quarkus API for owned projects, tasks, activities and labels | [API v1](../architecture/API-V1.md), the canonical [OpenAPI contract](../../common/api/openapi.json), repository-backed application services and the actual packaged HTTP acceptance suite cover CRUD, relationships, validation, optimistic versions, atomic operations and precise timestamps. Server/core builds have no Eclipse or Mylyn dependency. |
| Explicit authentication and owner isolation | Hashed bearer-token registry, read/write permissions, revocation and owner-scoped resources/change feeds. HTTP tests reject cross-owner access; installed account/server switches and `phase.owner.binding` prevent an old draft from writing into a reassigned account. |
| Remote Eclipse selection and optional Mylyn tracking | `phase.native` and `phase.restart` cover native records and encrypted token reuse; `phase.mylyn*` covers real activation/deactivation and retained Task List identity. `phase.backend*` covers server, embedded and remote return transitions. Remote phases retain closed, unchanged local storage; selection does not transfer data. |
| Standalone web workflows, interoperable with Eclipse | Eight Chromium cases use the real server. `phase.web.eclipse.{records,drafts,tracking,reports,cleanup}` proves actual browser/installed-Eclipse interoperability, shared UUIDs, exact totals, owner time zone and stale-draft rejection. The frontend uses API v1 and never connects to the database. |
| Defined concurrent refresh, conflicts and reconnect | Owner-scoped committed long polling; `phase.changes.{owner-scope,activity-report,live-draft,reconnect}` verifies automatic refresh and recovery. Real database restoration is covered by `phase.restore.*` and `phase.label.restore.*`, including reused numeric versions, preserved drafts and reviewed saves. |
| Visible failures and safe interrupted operations | `phase.task.*` covers authentication, offline operation, conflicts and committed timeout/cancellation. Create review, four tracking outcomes and six native deletion outcomes require explicit reconciliation. Four `phase.import.*` markers cover a committed lost reply, retained command key and receipt replay after an actual installed restart. No implicit local fallback or ordinary-write replay is used. |
| Native preferences, reports and interchange | `phase.native.{label-preferences,report-controls,folder-dialogs}` covers label drafts, exact reports, actual GTK file/folder pickers, cancelled transfers and fresh-ID import. The installed base feature separately verifies import replay across restart. |
| Deployable server and operational instructions | [Server guide](../operations/SERVER.md), [Docker setup and Tailscale](../operations/LOCAL-DOCKER.md), [deployment](../operations/DEPLOYMENT.md) and [Eclipse connection](../operations/ECLIPSE-CLIENT.md). Container acceptance covers PostgreSQL, rolling updates, backup/restore and durable restart. |

## Inspected merged-main receipts

All eight jobs in [Build run 38368609913](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38368609913)
passed: API-contract, Server, Web, Remote-Eclipse, Publication and the Linux,
macOS and Windows builds. [Server containers run 38368609937](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38368609937)
also passed verification.

Downloaded evidence was inspected rather than inferring acceptance from a green
workflow alone:

- `server-test-results`: 61 Java cases, no failures/errors/skips; the HTTP receipt
  confirms 12 contract cases, normal/forced restart, revocation, long polling,
  readiness and hierarchy/rollback/precision checks.
- `web-acceptance`: eight Chromium cases, no failures/errors/skips, with
  `passed=true` and source commit matching merged main.
- `remote-eclipse-acceptance`: all nine installed test reports contain one
  executed case each, with no failures/errors/skips. The base receipt contains
  16 passing phase markers; the optional Mylyn receipt contains 48. This includes
  actual GTK pickers and browser/Eclipse interaction, not only adapter tests.

The server, browser and both installed receipts use the same distribution
SHA-256:

```text
1f16fb8b7886f3365b30869613783678c577a200fab33acb37e8b9ba49251c3b
```

Its downloaded ZIP checksum and `timekeeper-release.json` were checked against
that digest and commit `102a341fdcffd73fbc1622e39894befbe1badf18`.
Counts from overlapping Java reactors are not added as unique tests. Workflow
artifacts may expire; this report preserves the source/run identities and the
inspected conclusions. The existing CI continuously reruns these scenarios.

## Completion boundary

These receipts satisfy #187 and its client/synchronization children #220, #221
and #219. Earlier checkpoint lists remain historical evidence; installed
preferences/pickers, backend switches, web interoperability, real H2 restore
and interrupted-operation/restart checks now have the evidence above.

This is functional client/server acceptance, not a published 2.0 release. The
optional Mylyn symbolic-color Error Log issue [#243](https://github.com/turesheim/eclipse-timekeeper/issues/243),
final tagged artifacts and public p2 installation remain separate release
gates in the [release process](../development/RELEASING.md). H2 restoration with
both live UIs and PostgreSQL Compose restoration are separate tested scenarios;
this does not claim both live UIs were exercised during PostgreSQL restoration.
Failure scenarios establish the behaviors listed above, not every possible
failure/operation combination.

Encrypted-token restart tests use an isolated test master password. They do
not certify the user's macOS Keychain configuration. The client's storage
diagnostic now identifies the Equinox failure category and points to Eclipse
secure-storage preferences; the [connection guide](../operations/ECLIPSE-CLIENT.md#if-the-token-cannot-be-saved)
describes provider/password/file recovery without deleting shared credentials
or storing plaintext. The earlier generic screenshot alone cannot identify
which provider failed.

## Default-label follow-up

The completion change also initializes configured server accounts with the
eight embedded defaults, using one shared domain catalog. Existing nonempty
label sets retain their records, colors and versions. Initialization uses the
normal owner transaction and service ports, including change notifications.
Accounts added at runtime receive the same defaults on first use; an empty
existing account is initialized at the next server startup.

Four additional real-database tests cover startup before client requests,
concurrent first use/owner isolation, customized and empty existing accounts,
and rollback without partial defaults. Packaged process acceptance additionally
checks initial names/colors, distinct owner UUIDs and unchanged data across
restart. The earlier merged-main receipt above predates this follow-up; its
source/checksum is retained as recorded rather than presented as this change's
validation.
