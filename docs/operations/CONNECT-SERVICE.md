# OAuth service operations

This guide is for operators hosting the managed OAuth service for several
Dagsverk installations. For client registration and user connections, see
[Google and Microsoft OAuth setup](SHARED-SIGN-IN.md#managed).

## Hosting and credentials

Use a project-controlled HTTPS origin with a trusted certificate. Terminate TLS
at a reverse proxy forwarding to the loopback service port 8090. Forward only the
documented HTTP routes; disable query-string, Authorization, cookie and response
body logging, including proxy/CDN logs. Readiness is `/q/health/ready`.
Redirects use `DAGSVERK_CONNECT_ORIGIN`, never forwarded client headers.

Register the [Google](SHARED-SIGN-IN.md#google) and
[Microsoft](SHARED-SIGN-IN.md#microsoft) web clients with the service's
`/oauth/google/callback` and `/oauth/microsoft/callback` URIs. Supply credentials
through the private environment in `server/connect/deploy/service.env.example`.
Generate a random 32-byte key, encoded as Base64, for
`DAGSVERK_CONNECT_ENCRYPTION_KEY`. Keep it with an encrypted database backup:
losing or changing it requires users to reconnect. Keep filled environment files
out of source, images, CI artifacts and PRs.

## Build and run

Java 21 is required. From a clean checkout, build the standalone H2 distribution:

```sh
./mvnw -B -ntp -f server/pom.xml -pl connect -am verify
python3 server/scripts/package_connect.py --commit FULL_SOURCE_COMMIT --output server/target/connect-distribution
```

The ZIP includes a checksum, commit identity and unconfigured deployment templates.
Run `java -jar quarkus-run.jar` from the extracted directory with
`DAGSVERK_CONNECT_DB_URL=jdbc:h2:file:/PRIVATE_STATE/connect` and the required
private settings. Bind to loopback behind TLS; use one service process for H2.

For PostgreSQL, build the separate image from the repository root. Database kind
is selected at build time; environment variables cannot switch an H2 ZIP to PostgreSQL:

```sh
docker build -f server/connect/Dockerfile --build-arg DAGSVERK_BUILD_COMMIT=FULL_SOURCE_COMMIT --build-arg DAGSVERK_BUILD_DIRTY=false -t dagsverk-connect:review .
```

The Compose template uses PostgreSQL 18, retained storage and a loopback-only
published port. Put `CONNECT_DATABASE_PASSWORD` in a private `.env` and fill
`service.env` with mode 0600. Test logical database restoration with the encryption
key. CAS versions and a refresh lease fence concurrent replica operations.
Admission limits are per process; bound the deployment and proxy limits accordingly.
Expired encrypted records are pruned on new authorizations.

## Discovery and acceptance

After verifying hosting and registrations, publish project-owned discovery at
`https://turesheim.github.io/eclipse-timekeeper/connect/service.json`:

```json
{"protocol":1,"serviceUrl":"https://YOUR_SERVICE_HOST"}
```

Publish only a project-controlled hostname. No placeholder discovery file is
included. Installations can override discovery with `DAGSVERK_CONNECT_SERVICE_URL`
and restart; users do not configure it.

On a test installation, use **Administration → Internet accounts → Refresh setup status**.
This resolves discovery and reads `/v1/providers` for database health and provider
configuration. Then [verify live connections](SHARED-SIGN-IN.md#verify-the-connection)
with existing non-production Google, personal Microsoft and Microsoft 365 accounts.
Record the source commit and active mode. Include private Synology/Tailscale returns,
consent cancellation, token refresh after expiry and account isolation. Stop the
service to check readiness failure and recovery; existing data and subscriptions
must remain usable. Confirm that switching modes preserves saved selections.

Automated fixtures cover HTTPS returns, synthetic providers, one-use redemption,
browser/owner binding, encryption and refresh rotation. They do not establish live
provider consent, verification, proxy or NAS/VPN readiness. Complete those checks
before public availability.

## Grants and retained data

An installation creates a ten-minute request bound to its callback, owner/browser
state, selected services and S256 PKCE challenge. The service shows the destination
before consent; a Secure, HttpOnly cookie and CSRF nonce bind it to the browser.
Provider authorization has separate state and PKCE. After verifying identity,
the service returns a five-minute, one-use handoff code redeemable only by the
original installation. Provider tokens never appear in redirects.

Refresh tokens use AES-256-GCM encryption with per-record authenticated data in
`tk_connect_record`. Installations keep an opaque grant credential and short-lived
access token in private owner-scoped records. Calendar and task workers use the
same grant but keep separate selections/checkpoints. The service stores no event
or task contents. Grants expire after 180 days without a token refresh.
Logs must not record credentials.

Disconnect removes local credentials and connected calendars immediately. A
durable private queue retries central grant deletion while the service is
unavailable; other installations' grants remain valid. Users can revoke the whole
application at their provider. Portable account archives exclude credentials.
Instance backups contain opaque grants; restoring the service database with its
encryption key preserves them.
