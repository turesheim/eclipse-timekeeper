# Dagsverk standalone server

The Java 21 server provides API v1 and the bundled browser client at `/web/`.
It uses Quarkus 3.40.1, H2 2.4.240 or PostgreSQL, and **server schema 10**.
Embedded Eclipse storage uses its own schema 3; it is not a server database.
Switching backends does not copy or merge records.

## Choose a deployment

| Deployment | Guide |
| --- | --- |
| Synology Package Center | [Installation, updates and recovery](SYNOLOGY-PACKAGE.md) |
| Local Docker | [Local setup](LOCAL-DOCKER.md) |
| PostgreSQL with two API replicas | [Compose deployment](DEPLOYMENT.md) |
| Java process | Configure and run below |

The [web guide](WEB-CLIENT.md) covers user workflows. [Accounts](ACCOUNTS.md)
covers first setup, recovery and personal Eclipse API tokens.

## Configure and run

Extract `timekeeper-server.zip` and verify its adjacent SHA-256 checksum. From
the extracted `timekeeper-server` directory, use existing private directories
writable by the service account:

```sh
export DAGSVERK_SERVER_DB_URL=jdbc:h2:/absolute/path/dagsverk/data/server
export DAGSVERK_SERVER_SETUP_FILE=/absolute/path/dagsverk/config/account-setup.json
python3 configure_accounts.py --output "$DAGSVERK_SERVER_SETUP_FILE"
java -jar quarkus-run.jar
```

The setup tool prints a one-time code. Open `http://127.0.0.1:8080/web/`, choose
**First administrator setup** and complete the [account setup](ACCOUNTS.md#first-administrator).
Use a new, separate database; preserve unsupported or historical storage intact.
Keep setup files and database credentials private and record the deployment's
source commit and distribution checksum.

| Setting | Purpose |
| --- | --- |
| `DAGSVERK_SERVER_DB_URL` | Explicit persistent JDBC URL |
| `DAGSVERK_SERVER_DB_USER`, `DAGSVERK_SERVER_DB_PASSWORD` | Database credentials; H2 user defaults to `sa` |
| `QUARKUS_HTTP_HOST`, `QUARKUS_HTTP_PORT` | Listener; defaults to `127.0.0.1:8080` |
| `DAGSVERK_PUBLIC_ORIGIN` | Exact external HTTPS origin when using a reverse proxy |
| `DAGSVERK_DEPLOYED_AT` | Actual UTC deployment time, retained across restarts |

The standard standalone ZIP uses H2. The [Compose deployment](DEPLOYMENT.md)
supplies the PostgreSQL build.

Use HTTPS outside loopback. **About** and `GET /api/v1/server` distinguish build,
deployment and process-start time; an unset deployment time is shown as
**Not recorded**. Environment defaults seed central service settings on first
startup; later environment changes do not overwrite saved administration values.
Follow [configuration names and recovery](CONFIGURATION.md) for precedence and
legacy aliases, and [Accounts](ACCOUNTS.md) for origin/RP/SMTP cutovers.

The database account needs schema initialization and H2 administration rights.
Allowed JDBC URL settings are `IFEXISTS`, `DB_CLOSE_ON_EXIT`, `AUTO_SERVER`,
`AUTO_SERVER_PORT` and `LOCK_TIMEOUT`. Credentials in URLs, arbitrary startup SQL,
read-only mode and persistent JDBC settings are rejected. Named memory storage
is for tests, not durable deployment.

## Data and operational behavior

Every account has one immutable owner ID. Administrators do not gain access to
other owners' business data. The API enforces permissions, owner-bound foreign
keys, optimistic ETags and atomic tracking/receipt commits. See
[API v1](../architecture/API-V1.md) for payload bounds, pagination, interchange
and synchronization contracts.

New accounts receive the shared default label catalog. A nonempty customized
catalog is retained; restarting the server restores defaults for accounts with an
empty catalog. Each account can set its IANA reporting zone. API requests cannot select
another owner or fall back to anonymous local storage.

Startup validates the canonical schema before accepting storage. Unknown or
incomplete layouts fail without repair. Current builds can explicitly upgrade
canonical server schemas 1–9 to 10; follow [the account upgrade procedure](ACCOUNTS.md#existing-data-and-accounts)
and stop older replicas first. Historical and embedded databases are not supported
upgrade inputs. H2 uses `WRITE_DELAY=0` so the tested acknowledged commits survive
immediate process termination; startup settings cannot weaken this.

Readiness is `/q/health/ready` and checks storage/schema and configured token
registry. Liveness is `/q/health/live`. A missing or malformed configured legacy
registry makes readiness/private requests unavailable; an unset or empty registry
is valid. New personal tokens are managed through My Account.

### H2 schema-session lifetime

H2 2.4.240 is retained for its acknowledged-commit crash compatibility. Its
[`IN` check-constraint regression](https://github.com/h2database/h2database/issues/4308)
can bind enum checks to the connection that created them. Closing that
connection during normal pool retirement can then break later writes with
SQL state `90098`, even while the database remains readable.

The server initializes H2 schema on a dedicated, unpooled connection and keeps
that session until runtime shutdown. It uses the configured JDBC URL,
properties, user and password, leaves application-pool capacity available,
and closes the session on startup failure or shutdown. Enum constraints,
schema versions, transaction durability and the H2 version stay unchanged.
PostgreSQL continues using its existing pooled bootstrap path. The regression
retires every pooled connection repeatedly against a real H2 file with a
one-slot pool and nondefault database credentials, then verifies authenticated
reads/writes and rejection of invalid enum values.

## Notes synchronization

Notes, journals and attachments are available through `/dav/notes/`. Users find
the collection URL under **My Account → Notes** and create separate API tokens
for external clients. See [WebDAV](NOTES-WEBDAV.md) for conditional writes and
conflicts, and [journal storage](JOURNAL-STORAGE.md) for encryption/key custody.

## Calendar feed access

Calendar subscriptions fetch public HTTPS iCalendar feeds on the server. URLs
may contain private access tokens and are never returned through subscription
responses or included in account archives. Database backups include the private
configuration and cached data, as they do other owned server storage.

By default, private, loopback, link-local, reserved and metadata destinations are
blocked. DNS answers are validated and pinned for each connection; redirects
are limited and revalidated. TLS hostname/certificate verification stays enabled.
No Dagsverk bearer token, browser cookie or recovery credential is forwarded.
A request has a bounded lifetime, a 2 MiB body limit, and no automatic HTTP retries.
At most four downloads run per server, with database leases coordinating replicas.

For a trusted internal calendar server, set
`DAGSVERK_CALENDAR_TRUSTED_ORIGINS` to a comma-separated list of exact origins,
for example `https://calendar.internal.example:8443`. Only those specific origins
may resolve to internal addresses. An explicitly listed `http://` origin may use
HTTP; HTTPS-to-HTTP redirects remain rejected. This is operator configuration,
not a setting calendar users can override. The supplied Compose file forwards
this variable. An empty value keeps the public-HTTPS-only policy.
`DAGSVERK_CALENDAR_AUTOMATIC=false` disables scheduled refresh; manual refresh
remains available. No database schema upgrade is required for subscriptions.

## Internet accounts

Users connect Google or Microsoft under **My Account → Internet accounts**.
Administrators choose a managed OAuth service or direct app registrations;
[OAuth setup](SHARED-SIGN-IN.md) links the provider consoles and required scopes.
[Task providers](TASK-PROVIDERS.md) describes import, review and reconnect.
Calendar feed subscriptions remain available without provider sign-in.

## Full-instance recovery

Use [encrypted full-instance backup and restore](INSTANCE-BACKUP.md) to preserve
accounts, credentials, work, Notes and installation security material. Follow its
private preparation, explicit confirmation and exclusive startup procedure.
Retain the active recovered material for subsequent restarts and disaster recovery.

A closed H2 file copy is only a database copy: stop every process using the file
before copying, preserve companion configuration/security material and verify
recovery in separate storage with a compatible server. Never copy an open file.
Personal JSON/CSV exports are interchange, not installation backups or downgrade
procedures. A binary rollback across an incompatible schema requires its matching
pre-upgrade backup and separate preservation of later work.

## Build and automatic acceptance

Prepare [developer prerequisites](../development/REPOSITORY.md), then run:

```sh
./mvnw -B -ntp -f server/pom.xml clean verify
python3 server/scripts/acceptance.py
```

The server suite covers the shared core and real HTTP/database behavior.
The process runner uses fresh storage and synthetic tokens to check the packaged
server, restart, durable receipts, ownership and interrupted writes. Its reports
are under `server/target/acceptance`. [Browser acceptance](../development/WEB-ACCEPTANCE.md)
uses the same clean-source distribution; dated results belong in
[verification](../verification/README.md).

## Distribution

The Build workflow's `server-standalone` artifact contains the server ZIP,
bundled web client, user guides, source provenance and checksum. It is separate
from the Eclipse p2 repository and Synology installer. Package a clean committed
build with:

```sh
python3 server/scripts/package_server.py --output server/target/distribution --commit <full-source-commit>
```

For release packaging, also supply `--tag <actual-tag>`; it must resolve to that
commit. See [release procedures](../development/RELEASING.md) for publication.
For a DSM installation use `synology-installer` from **Server containers**, rather
than the standalone Java ZIP.
