# Timekeeper Quarkus server

The server implements the [owner-scoped v1 API](../api/README.md) for #218.
It uses Quarkus **3.40.1 LTS**, Java **21**, H2 **2.5.250**, and its own schema
version **1**. The same ordinary Java domain/application-service sources are
compiled by the standalone Maven reactor and the Eclipse build. The server has
no Eclipse Platform, workspace, SWT, Mylyn, OSGi or JPA dependency.

The bundled web client is available at `/web/`; see [web client documentation](../web/README.md) for usage and browser tests.

Remote Eclipse (#220), web frontend (#221) and client synchronization (#219)
acceptance remain required before Timekeeper 2.0 publication. Server HTTP tests
do not certify those clients or a published p2 installation.

## Build and automatic acceptance

```sh
./mvnw -B -ntp -f server/pom.xml clean verify
python -m pip install -r api/tests/requirements.txt
python server/scripts/acceptance.py
```

JUnit checks the shared core, real Quarkus/HTTP/H2 bootstrap, protocol limits,
and refusal of damaged/unknown schemas. The process suite runs all twelve
black-box API contract cases against a fresh file database, then restarts in new
JVMs and verifies IDs, provider links, assigned labels, an open interval, durable
command replay, immediate forced termination after stop, token revocation,
long-poll delivery, import rollback, hierarchy conflicts, nanoseconds and both
DST transitions. Fixtures include paths with spaces and supplementary Unicode.
The runner uses generated test tokens and removes its own database/configuration
when finished. Logs and a credential-free receipt are under
`server/target/acceptance/` and uploaded by CI.

## Configure and run

For Synology Container Manager, use the complete [Docker Compose deployment](deploy/README.md).
It supplies two PostgreSQL-backed server replicas, a three-node PostgreSQL/Patroni
cluster, persistent host storage, health checks, backups and rolling API updates.
The JVM distribution below continues to default to H2. PostgreSQL images are
built with the `postgresql` Quarkus profile; the database kind is fixed at build
time. PostgreSQL storage uses its own canonical schema and is not an H2 file
migration. Its bootstrap is transactional and serialized across server replicas.

Use a separate empty server database; the embedded Eclipse schema 2 and
historical databases are unsupported. The server never copies or merges embedded
data automatically. Set an explicit JDBC URL and a token registry file:

```sh
python create_token.py --owner alice --file /secure/timekeeper/tokens.json
export TIMEKEEPER_SERVER_DB_URL='jdbc:h2:/data/timekeeper/server'
export TIMEKEEPER_SERVER_TOKENS_FILE='/secure/timekeeper/tokens.json'
java -jar quarkus-run.jar
```

The token command prints one newly generated 256-bit bearer token. Save it in the
client's credential store; the registry contains only SHA-256 hashes. Run from
the extracted `timekeeper-server` distribution directory. From a source checkout,
the corresponding paths are `server/scripts/create_token.py` and
`server/app/target/quarkus-app/quarkus-run.jar`.

The database directory and registry are administrator-owned. Registry entries
use `ownerId`, `sha256`, `permissions` (`read`, optionally `write`) and optional
`expiresAt` (UTC timestamp). Owners are stable, nonblank IDs; `local` is reserved
for embedded mode. Multiple generated tokens can belong to one owner. New
owners receive a UTC profile on their first successful operation and can set an
IANA reporting zone through `PUT /api/v1/me` with its ETag. API requests never
select another owner.

Use `--read-only` or `--expires-at 2026-12-31T23:59:59Z` when generating a token.
Rotate by adding a new token for the same owner, configuring the client, then
removing the old hash. Replace the registry atomically when editing it. Every
request/retry reloads it; revocation and permission reduction also apply to
previously stored command responses. Pending change polls recheck authorization.
A malformed or missing registry fails startup or makes readiness DOWN, and
private API requests fail with 503.

By default the server binds `127.0.0.1:8080`. `QUARKUS_HTTP_PORT` changes the port;
`QUARKUS_HTTP_HOST` changes the bind address. Use TLS at Quarkus or a TLS reverse
proxy before exposing it beyond loopback. Bearer tokens are sent only in the
Authorization header, never URL parameters or database records. The server has
no account-sharing/admin-data API and no anonymous local-owner fallback.

Optional JDBC credentials use `TIMEKEEPER_SERVER_DB_USER` (default `sa`) and
`TIMEKEEPER_SERVER_DB_PASSWORD`. The server database account requires schema
initialization and H2 database-administration rights. Permitted URL settings are
`IFEXISTS`, `DB_CLOSE_ON_EXIT`, `AUTO_SERVER`, `AUTO_SERVER_PORT` and
`LOCK_TIMEOUT`; arbitrary startup SQL, credentials in URLs, read-only mode and
persistent JDBC settings are rejected before opening storage. Named memory
storage is suitable for tests; production uses durable file/server storage.

## Data and operational behavior

`GET /api/v1/openapi.json` serves the canonical public contract. All data routes
require the token's read/write permission and bind repository operations to its
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
operator to select new storage or restore a known valid backup. No historical or
embedded-schema migration is provided.

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

## Distribution

The CI `timekeeper-server` artifact contains a complete JVM distribution ZIP,
checksum and embedded source provenance, separate from the Eclipse p2 repository.
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
