# Browser acceptance

Run from the repository root with Java 21, Python 3.13 and Node 24. See
[repository setup](REPOSITORY.md) for Python dependencies and the Maven wrapper.
These checks use synthetic accounts and temporary storage.

## Unit checks and browser prerequisites

```sh
npm ci --ignore-scripts --prefix server/web
npm test --prefix server/web
npm exec --prefix server/web -- playwright install --with-deps chromium webkit
```

Unit/asset checks do not establish browser behavior. Linux browser installation
may need system packages; CI prepares its APT mirror with
`scripts/ci_ubuntu_mirror.py`. HTTPS fixtures also require `openssl` on `PATH`.

## Build and package the exact source

Commit the source to be tested and keep the checkout clean. The package tool
checks both Git state and the build identity embedded in the server. Rebuild if
that identity is stale; do not relabel an older distribution.

```sh
./mvnw -B -ntp -f server/pom.xml clean verify
python3 server/scripts/package_server.py --output server/target/distribution --commit "$(git rev-parse HEAD)"
python3 server/web/tests/acceptance.py --distribution server/target/distribution/timekeeper-server.zip --https
```

The runner verifies the ZIP checksum, starts its bundled application in a new JVM,
prepares isolated accounts/storage and stops it afterwards. The HTTPS reverse
proxy uses a temporary fixture certificate accepted only by the test browser.
For WebKit, use the same package and include `--browser webkit --https`.

## Select scenarios

Use `--spec <filename>` to select spec files and `--grep <pattern>` for scenarios.
Unselected scenarios remain unverified; record the selector with the result.
For example:

```sh
python3 server/web/tests/acceptance.py --distribution server/target/distribution/timekeeper-server.zip --https --spec administration.spec.js
python3 server/web/tests/acceptance.py --distribution server/target/distribution/timekeeper-server.zip --https --browser webkit --spec task-providers.spec.js --grep 'callback'
```

Find current scenarios in [server/web/tests](../../server/web/tests/). Common
starting points are:

| Change | Scenarios |
| --- | --- |
| Account authority or administrator settings | `accounts.spec.js`, `administration.spec.js` |
| Refresh, stale drafts or reconnect | `synchronization.spec.js`, `today-synchronization.spec.js` |
| Tasks, provider consent or imports | `tasks.spec.js`, `task-providers.spec.js` |
| Sessions and time-zone boundaries | `workweek-sessions.spec.js`, `session-editor.spec.js`, `calendar.spec.js` |
| Calendar feeds and conversion | `calendar-subscriptions.spec.js` |
| Markdown and journal editing | `markdown-editor.spec.js`, `markdown-fidelity.spec.js`, `day-notes.spec.js` |
| Notes navigation and search | `notes.spec.js`, `notes-navigator.spec.js`, `recent-notes.spec.js` |
| Typography or layout | `typography.spec.js`, `today-layout.spec.js`, plus the affected feature |

The runner also supports `--shard 1/4` through `4/4` for bounded CI batches.
Use the shared synthetic provider-page fixtures for consent/callback tests;
real external accounts are not needed for automated acceptance.

## Specialized fixtures

| Option | Purpose | Requirement |
| --- | --- | --- |
| `--mail` | Capture actual email delivery through loopback SMTP | `--https` |
| `--passkeys` | Virtual WebAuthn registration, sign-in and fresh proofs | Chromium and `--https` |
| `--restore` | Enable private instance-restore staging in the fixture | Use the corresponding restore scenarios |

For example, the HTTPS mail/passkey suite runs with:

```sh
python3 server/web/tests/acceptance.py --distribution server/target/distribution/timekeeper-server.zip --https --mail --passkeys
```

Cross-replica protocol tests use separate containers and PostgreSQL:

```sh
python3 server/scripts/passkey_replica_acceptance.py --server-image timekeeper-server:test
python3 server/scripts/passkey_replica_acceptance.py --server-image timekeeper-server:test --postgres-image postgres:17.11-bookworm --output server/target/passkey-pg17-acceptance.json
```

Virtual keys do not establish Touch ID, native Password AutoFill, physical
security-key or cross-device interoperability. Record those checks separately
against the relevant HTTPS hostname/port. Installed Eclipse/browser interaction
uses the [remote-client acceptance](REMOTE-ECLIPSE.md) profile.

## Layout and evidence

For changed surfaces, check keyboard operation, accessible names, mobile widths,
light/dark themes and both languages. Inspect screenshots as well as assertions.
Use `captureScreenshot` from `tests/screenshots.js`: when fixed bottom navigation
is visible it captures the viewport, avoiding misleading full-page placement.
Pages without that navigation can use full-page captures.

Reports, screenshots, server logs and source/checksum provenance are written to
`server/web/test-results`. Retain evidence for the exact package tested. Traces
and videos are disabled because they can retain credentials. Keep real tokens,
passwords, private keys, database dumps and user data out of committed evidence.
Do not sum overlapping runs or describe skipped/unavailable checks as passing.
Dated reports belong in [verification](../verification/README.md), not this guide.
