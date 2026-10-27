# Timekeeper Quarkus server

The server implements the [owner-scoped v1 API](../architecture/API-V1.md) for #218.
It uses Quarkus **3.40.1 LTS**, Java **21**, H2 **2.4.240**, and its own schema
version **2**. The same ordinary Java domain/application-service sources are
compiled by the standalone Maven reactor and the Eclipse build. The server has
no Eclipse Platform, workspace, SWT, Mylyn, OSGi or JPA dependency.

The bundled web client is available at `/web/`; see [web client documentation](../development/WEB-CLIENT.md) for usage and browser tests.

See [client/server acceptance](../verification/CLIENT-SERVER-ACCEPTANCE.md) for
the inspected merged-main Eclipse, browser and synchronization receipts. Final
tagged artifacts and public p2 installation remain separate release gates.

## Build and automatic acceptance

```sh
./mvnw -B -ntp -f server/pom.xml clean verify
python -m pip install -r common/api/tests/requirements.txt
python server/scripts/acceptance.py
```

JUnit checks the shared core, real Quarkus/HTTP/H2 bootstrap, protocol limits,
and refusal of damaged/unknown schemas. The process suite runs the black-box
API contract cases against a fresh file database, then restarts in new
JVMs and verifies IDs, provider links, assigned labels, an open interval, durable
command replay, immediate forced termination after stop, token revocation,
long-poll delivery, import rollback, hierarchy conflicts, nanoseconds and both
DST transitions. Fixtures include paths with spaces and supplementary Unicode.
The runner uses generated test tokens and removes its own database/configuration
when finished. Logs and a credential-free receipt are under
`server/target/acceptance/` and uploaded by CI.

## Configure and run

For Docker on your own computer, follow the [local Docker setup guide](LOCAL-DOCKER.md).
It covers a source build, persistent storage, credentials and Eclipse connection settings.

For Synology Container Manager, use the complete [Docker Compose deployment](DEPLOYMENT.md).
It supplies two PostgreSQL-backed server replicas, one PostgreSQL instance using
its official image, and HAProxy. Data lives in a separate host directory. The
updater drains and replaces one server at a time, verifies readiness and restores
the previous images on failure. Database upgrades are separate maintenance work.

Use a separate empty server database; the embedded Eclipse schema 3 and
historical databases are unsupported. The server never copies or merges embedded
data automatically. Follow [account setup and migration](ACCOUNTS.md) to create
the first administrator, claim existing owners explicitly, and issue personal
Eclipse API tokens. The same guide describes password recovery and session
lifetimes. Configure an explicit JDBC URL before starting the JVM.

The authenticated `GET /api/v1/server` reports this server's build identity and
timestamps, which the web client displays in its footer. For a direct JVM
deployment, set `TIMEKEEPER_DEPLOYED_AT` to the actual UTC deployment timestamp
(for example, `2026-10-13T08:30:00Z`) and retain that value in the service's
environment across restarts. Without a recorded deployment timestamp, the web
client shows **Not recorded**; process-start time remains available separately.

Each account has a stable owner ID; usernames and display names can change.
All business data is private to that owner, including for administrators. New
owners receive a UTC profile and can set their IANA reporting zone through
`PUT /api/v1/me` with its ETag. API requests never select another owner.

Each account starts with the same eight labels as embedded Eclipse:
Production issue, Testing, Prototyping, Planning, Programming, Debugging,
Communication and Meeting, with their standard colors. Labels have separate
UUIDs for each owner. Server startup initializes an existing account only when
its label set is empty; any nonempty set, including customized labels, is kept
unchanged. Deleting all labels leaves the running account empty; restarting
restores the defaults, matching embedded initialization. New owners added while
running are initialized atomically on their first successful operation. Both
deployments use the shared catalog in `common/timekeeper-domain`.

The legacy token registry remains available during migration; new personal API
tokens are managed under **Account** in the web client. An unset registry or an
empty array is valid. A configured missing/malformed registry fails startup or
readiness and private API requests return 503.

By default the server binds `127.0.0.1:8080`. `QUARKUS_HTTP_PORT` changes the port;
`QUARKUS_HTTP_HOST` changes the bind address. Use TLS outside loopback and set
`TIMEKEEPER_PUBLIC_ORIGIN` when terminating HTTPS at a reverse proxy. There is no
cross-account sharing or anonymous local-owner fallback.

Optional JDBC credentials use `TIMEKEEPER_SERVER_DB_USER` (default `sa`) and
`TIMEKEEPER_SERVER_DB_PASSWORD`. The server database account requires schema
initialization and H2 database-administration rights. Permitted URL settings are
`IFEXISTS`, `DB_CLOSE_ON_EXIT`, `AUTO_SERVER`, `AUTO_SERVER_PORT` and
`LOCK_TIMEOUT`; arbitrary startup SQL, credentials in URLs, read-only mode and
persistent JDBC settings are rejected before opening storage. Named memory
storage is suitable for tests; production uses durable file/server storage.

## Journal synchronization

Daily Markdown notes are available at the server's WebDAV collection. Follow
the [WebDAV guide](JOURNAL-WEBDAV.md) for authentication, conditional writes,
deletion and tested client compatibility. Users find the collection URL under
**My account → Journal** and create personal API tokens under **My account → API tokens**.
The [journal storage policy](JOURNAL-STORAGE.md) covers encryption and backup-key
custody; the server processes readable Markdown.

## Calendar feed access

Calendar subscriptions fetch public HTTPS iCalendar feeds on the server. URLs
may contain private access tokens and are never returned through subscription
responses or included in account archives. Database backups include the private
configuration and cached data, as they do other owned server storage.

By default, private, loopback, link-local, reserved and metadata destinations are
blocked. DNS answers are validated and pinned for each connection; redirects
are limited and revalidated. TLS hostname/certificate verification stays enabled.
No Timekeeper bearer token, browser cookie or recovery credential is forwarded.
A request has a bounded lifetime, a 2 MiB body limit, and no automatic HTTP retries.
At most four downloads run per server, with database leases coordinating replicas.

For a trusted internal calendar server, set
`TIMEKEEPER_CALENDAR_TRUSTED_ORIGINS` to a comma-separated list of exact origins,
for example `https://calendar.internal.example:8443`. Only those specific origins
may resolve to internal addresses. An explicitly listed `http://` origin may use
HTTP; HTTPS-to-HTTP redirects remain rejected. This is operator configuration,
not a setting calendar users can override. The supplied Compose file forwards
this variable. An empty value keeps the public-HTTPS-only policy.
`TIMEKEEPER_CALENDAR_AUTOMATIC=false` disables scheduled refresh; manual refresh
remains available. No database schema upgrade is required for subscriptions.

## Data and operational behavior

`GET /api/v1/openapi.json` serves the canonical public contract. All data routes
require the session or token's read/write permission and bind repository operations to its
owner. Database foreign keys include owner/type/UUID; external identities are
unique per owner and only one interval may be open per owner. Resource updates
use both strong opaque ETags and SQL version predicates. An owner row lock
serializes compound tracking operations; different owners have separate locks.
Start, stop, events and command receipt commit together. Receipts and committed
changes currently remain indefinitely (at least the contract's seven-day retry
window); no maintenance task silently removes history.

The server validates table/column/key/index/check definitions and the READY
version marker before accepting existing storage. Unknown tables/views,
non-public schemas, user sequences/domains, incomplete initialization and missing
ownership constraints fail without schema/data repair. H2 DDL commits during
initialization, so interrupted creation retains `INITIALIZING` and requires an
operator to select new storage or restore a known valid backup. The canonical
server schema 1, 2 or 3 upgrades to schema 4 as described in the [account guide](ACCOUNTS.md);
no historical or embedded-schema migration is provided.

After schema validation, the server sets
[`WRITE_DELAY=0`](https://h2database.com/html/commands.html#set_write_delay)
so acknowledged commits survive the tested immediate JVM termination. Startup
settings cannot weaken this. The forced-restart acceptance preserves the exact
stopped interval and the original response/ETag, not a newly executed stop.

Readiness is `/q/health/ready`; it checks connections, schema and registry.
Liveness is `/q/health/live`. Monitoring responses expose no tokens/owner data.
API errors are stable problem responses; they do not expose SQL or configuration
contents. HTTP requests are limited to 20 MiB, with typed 413 responses before
JSON processing. Portable import/export also allows at most 10,000 records per
type. Provider lookup URLs allow the percent-encoded forms of the declared Unicode
identifier bounds. Larger exports fail as a whole, and failed imports roll back
all records, events and receipts. Imports always allocate fresh UUIDs and never
change the owner's zone or overwrite records.

For a file backup, stop the server, copy its H2 file, and restart. To restore,
stop it, preserve the current file, replace it with a verified server-schema-1
backup, and reopen the same configuration. A JSON export is current-model
interchange, not a physical backup or legacy migration. Protect the registry
separately; tokens are never included in data export. Keep the server version,
source commit and distribution checksum with operational records.

Work Sessions use the server's existing generic SQL schema 1, while portable
JSON/CSV export uses domain-v2 to retain sessions and optional activity links.
Older exports remain readable. Older server binaries do not preserve this new
domain model during writes or export, even though the SQL tables are unchanged.
The [first-upgrade procedure](DEPLOYMENT.md#automatic-server-image-updates)
therefore requires pausing writes until every replica supports Work Sessions.
Keep compatible server builds for subsequent image rollback; a downgrade to the
previous domain model requires a matching pre-upgrade backup.

## Distribution

The Build workflow's `server-standalone` artifact contains the Java 21 server
distribution `timekeeper-server.zip` and its SHA-256 checksum. The ZIP includes
the bundled web client and source provenance. It is separate from the Eclipse
p2 repository and the DSM installer. For Synology, download `synology-installer`
from the Server containers workflow instead; that artifact contains the `.spk`,
its checksum and installation metadata.
CI runs the process suite from the extracted ZIP, verifying its checksum and
actual runtime layout. Packaging requires the exact clean committed source.
Package the built source with:

```sh
python server/scripts/package_server.py --output server/target/distribution \
  --commit <full-source-commit>
```

For release packaging, additionally supply `--tag <actual-tag>`; the tool verifies
that the tag resolves to the recorded source commit. Untagged CI builds are
explicitly marked as candidates.

Release publication must build this from the accepted tag and publish the server
archive alongside the tested Eclipse/web deliverables. A CI artifact is a
candidate, not a published 2.0 release. See the repository's release checkpoint.
