# Timekeeper web client

The standalone server serves its bundled client at `/web/`. Production uses
native browser modules; no Node process, CDN or separate database is needed.
Maven includes `web/src` in the server JAR. An administrator issues access tokens
as described in [server/README.md](../server/README.md). Use HTTPS outside loopback.
The token stays in tab memory; reload or sign-out ends the browser session.

Projects, tasks, labels and activities use API v1. Start/stop uses durable
idempotency keys. Editors/deletion retain the original opaque ETag, including
across full resynchronization, rather than upgrading a stale draft. Validation,
permission, network and concurrency failures preserve drafts. An uncertain
ordinary write requires cancel, refresh and review; uncertain start/stop offers
explicit retry of the same frozen payload/key. Acknowledged saves remain
successful when a subsequent refresh fails.

Reports use the account's IANA time zone and the server's exact daily/weekly
totals. UTC activity fields retain up to nine fractional digits. First click
selects an activity; another click or Enter while selected opens its editor.
Long polling loads consistent owner-scoped snapshots without replacing drafts.
Offline reads reconnect with backoff and never replay writes. Sessions bind to
the authenticated owner and reject live token owner reassignment.

## Automated verification

```sh
npm ci --ignore-scripts --prefix web
npm test --prefix web
cd web && npx playwright install --with-deps chromium && cd ..
./mvnw -B -ntp -f server/pom.xml clean verify
python3 server/scripts/package_server.py --output server/target/distribution --commit "$(git rev-parse HEAD)"
python3 web/tests/acceptance.py --distribution server/target/distribution/timekeeper-server.zip
```

Packaging requires a clean committed tree. Browser acceptance launches the
checksum-verified distribution with temporary file storage and synthetic tokens,
then stops the JVM and removes private fixtures. Reports, screenshots and source
provenance go under `web/test-results`. Traces/videos are off because they could
retain authorization headers. CI's Web job uses the same Server artifact as
Remote-Eclipse.

Browser tests cover core tracking, authorization/validation, peer synchronization,
stale drafts, lost committed replies, exact nanoseconds, autumn DST, mobile
layout and memory-only credentials. The `remote-client-tests` Eclipse profile
also launches a real browser peer against its installed runtime's server;
[WEB-ECLIPSE.md](../baseline/WEB-ECLIPSE.md) records that bidirectional verification.
Final release installation remains a separate gate.
