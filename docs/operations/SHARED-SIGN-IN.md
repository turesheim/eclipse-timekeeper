# Shared sign-in service

The separate `server/connect` application holds Dagsverk's Google and Microsoft
OAuth registrations. Users sign in through it, then select calendars and task
lists on their own Dagsverk installation. Ordinary calendar subscriptions remain
available without provider sign-in. No OAuth application registration is required
on each NAS or server.

This source prepares the service for review. A public hostname, hosting account,
provider registrations and provider verification must be supplied before the
shared experience is available. Nothing in a build publishes or deploys it.

## Host and register once

Use a project-controlled HTTPS origin with a trusted certificate. Terminate TLS
at a reverse proxy forwarding to the loopback service port 8090. Forward only the
documented HTTP routes; disable query-string, Authorization, cookie and response
body logging, including proxy/CDN logs. Health readiness is `/q/health/ready`.
The service constructs redirects from `TIMEKEEPER_CONNECT_ORIGIN`, never from
forwarded client headers. It does not need to contact the user's private NAS.

Register confidential web clients with these exact central redirect URIs:

```text
https://YOUR_SHARED_HOST/oauth/google/callback
https://YOUR_SHARED_HOST/oauth/microsoft/callback
```

For Google, enable the Google Tasks API and Google Calendar API, configure the
consent audience and register a Web application. The requested scopes are
`openid email profile`, plus `tasks.readonly` or explicitly selected `tasks`,
and `calendar.readonly` when calendars are selected (the last three use the
`https://www.googleapis.com/auth/` prefix). Offline access is required. Testing
audiences need test users; production availability depends on Google's consent
and verification policies. See the
[web-server flow](https://developers.google.com/identity/protocols/oauth2/web-server).

For Microsoft, support organizational and personal accounts. Use delegated
`User.Read`, `Tasks.Read` and `Calendars.Read` with `openid offline_access`;
only selected services are requested. Tenant consent policies still apply.
Microsoft tasks and both providers' calendars are read-only. See the
[authorization code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow).

Supply the central registrations through the private service environment shown
in `server/connect/deploy/service.env.example`. Generate a random 32-byte key
encoded as Base64 for `TIMEKEEPER_CONNECT_ENCRYPTION_KEY`. Keep that key with an
encrypted database backup: changing or losing it requires all users to reconnect.
Never copy filled environment files into source, images, CI artifacts or PRs.

## Reviewable distributions

Java 21 is required. From a clean checkout, build and package the standalone H2
variant:

```sh
./mvnw -B -ntp -f server/pom.xml -pl connect -am verify
python3 server/scripts/package_connect.py --commit FULL_SOURCE_COMMIT --output server/target/connect-distribution
```

The ZIP includes its checksum, commit identity and unconfigured deployment
templates. Run `java -jar quarkus-run.jar` from its extracted directory with
`TIMEKEEPER_CONNECT_DB_URL=jdbc:h2:file:/PRIVATE_STATE/connect` and the required
private settings. Bind to loopback behind TLS. Use one service process for H2.

For PostgreSQL, build the separate image from the repository root. Database kind
is selected at build time; do not switch an H2 ZIP to PostgreSQL with environment
variables:

```sh
docker build -f server/connect/Dockerfile --build-arg TIMEKEEPER_BUILD_COMMIT=FULL_SOURCE_COMMIT --build-arg TIMEKEEPER_BUILD_DIRTY=false -t dagsverk-connect:review .
```

The Compose template uses PostgreSQL 18, retained database storage and a
loopback-only published service port. Place `CONNECT_DATABASE_PASSWORD` in a
private `.env` and fill `service.env` with mode 0600 before deploying. Retain
logical database backups and test restoration together with the encryption key.
CAS versions and a refresh lease fence concurrent replica operations. Admission
limits are per process; configure a bounded deployment and proxy limits for the
chosen hosting environment. Encrypted expired records are pruned on new sign-ins.

After hosting and registrations are verified, publish project-owned discovery
at `https://turesheim.github.io/eclipse-timekeeper/connect/service.json`:

```json
{"protocol":1,"serviceUrl":"https://YOUR_SHARED_HOST"}
```

Only publish a hostname controlled by the project. No discovery file containing
a placeholder is included. Installations can optionally override discovery with
`TIMEKEEPER_CONNECT_SERVICE_URL`; users do not need to configure this setting.

## Protocol and retained data

The installation creates a ten-minute request with its exact callback origin,
owner/browser state, selected services and S256 PKCE challenge. The shared page
shows the destination installation before asking the user to continue. A Secure,
HttpOnly cookie and CSRF nonce bind that consent to the browser. Provider sign-in
has a separate state and PKCE verifier. After verifying provider identity, the
service returns a five-minute, one-use handoff code to the installation; provider
tokens never appear in a redirect. Only the original installation can redeem it.

Refresh tokens are encrypted with AES-256-GCM and per-record authenticated data
in `tk_connect_record`. Installations retain an opaque grant credential and a
short-lived access token in private owner-scoped records. Calendar and task
workers share that grant while retaining separate selections and checkpoints.
The service does not store calendar events or task contents. Each grant expires
after 180 days without a token refresh. Logs must not record credentials.

Disconnect removes local account credentials and connected calendar sources
immediately. A durable private queue retries deletion of the central grant if
the service is unavailable. Other installations' grants remain valid; users
can separately revoke the entire application through their provider account.
Portable account archives exclude credentials. Instance backups contain opaque
grants; restoring a service backup with its encryption key preserves grants.

Automated tests cover synthetic providers, one-use redemption, browser/owner
binding, encrypted storage, refresh rotation and independent grants. Live Google
and Microsoft consent, hosting/proxy behavior and provider verification require
manual acceptance before public availability.
