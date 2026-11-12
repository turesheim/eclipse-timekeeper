# OAuth connections: managed and direct registration

Users connect their **existing** Google or Microsoft accounts from **My Account →
Internet accounts**, approve Calendar and/or Tasks, then select calendars and task
lists. They do not register applications, choose infrastructure modes, or create
new provider accounts. Dagsverk authentication remains separate: provider consent
is not SSO into Dagsverk. Email is not synchronized.

Administrators select and maintain the application registration under
**Administration → Internet accounts**. Both modes support calendar-only,
tasks-only and combined grants with the same personal-account workflow. Google
Tasks defaults to import-only; reviewed two-way access requires explicit write
consent. Microsoft To Do and both providers' calendars remain read-only.

## Direct

Use **Direct registration (own OAuth application)** for independent self-hosting,
or when the managed service is unavailable. An administrator creates confidential
Web applications, not new user accounts, at Google Cloud and/or Microsoft Entra.
Configure the consent audience/test users and enable the Google Calendar and Tasks
APIs as needed. For Microsoft select **Accounts in any organizational directory
and personal Microsoft accounts** (`AzureADandPersonalMicrosoftAccount`). The
`/common` endpoint supports Outlook.com/Hotmail/Live and Microsoft 365 work/school
accounts; organizational tenant consent policies still apply.

1. Save the exact Dagsverk HTTPS **origin**, including any non-default port, in
   **Administration → General settings**, then restart all server instances.
2. Register the exact **Direct callback** displayed in Internet accounts for each
   provider, for example
   `https://nas.example-tailnet.ts.net:8443/web/task-provider-callback.html`.
   Register it as a Web redirect, without wildcard, query, fragment or trailing
   slash. The browser must trust its certificate and reach the address.
3. Enter the client ID and write-only secret in each provider's administration
   fieldset. Blank secrets preserve existing credentials; replacement and clear
   are explicit. Save with administrator proof and restart all instances.
   The **effective active mode** and restart message distinguish saved choices
   from running configuration. Existing encrypted instance backups and protected
   registration files retain their settings; no secret is returned in the API.
4. Select **Check setup**. Direct readiness checks the effective local client ID,
   secret and HTTPS callback. It cannot prove a provider accepts credentials,
   API enablement, consent audience or an exact registered redirect without consent.
5. Users select **Connect Google** or **Connect Microsoft** in My Account and use
   existing identities. Calendar and task selections/save state are independent.

For environment-based first setup, the corresponding supported variables are
`DAGSVERK_CONNECT_SHARED=false`, `DAGSVERK_PUBLIC_ORIGIN`,
`DAGSVERK_GOOGLE_TASKS_CLIENT_ID`, `DAGSVERK_GOOGLE_TASKS_CLIENT_SECRET`,
`DAGSVERK_MICROSOFT_TASKS_CLIENT_ID` and
`DAGSVERK_MICROSOFT_TASKS_CLIENT_SECRET`. Persisted administration settings remain
active across restarts and take precedence over first-boot environment defaults.
Use the documented offline settings recovery only when intentionally replacing
persisted configuration. See [configuration migration](CONFIGURATION.md).

## Managed

**Managed OAuth service (shared registration)** uses one project-operated broker
and central provider registrations for multiple Dagsverk installations. No
per-installation OAuth registration is needed. Each user still authorizes their
own provider identities. The broker must be deployed, reachable and configured
for the selected provider. Saving SHARED alone does not make it operational.

This repository does not deploy the managed service. Use direct registration
until an operator has supplied and verified hosting, provider registration and
project discovery. A successful build or synthetic test is not live readiness.

## Host and register once

Use a project-controlled HTTPS origin with a trusted certificate. Terminate TLS
at a reverse proxy forwarding to the loopback service port 8090. Forward only the
documented HTTP routes; disable query-string, Authorization, cookie and response
body logging, including proxy/CDN logs. Health readiness is `/q/health/ready`.
The service constructs redirects from `DAGSVERK_CONNECT_ORIGIN`, never from
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
encoded as Base64 for `DAGSVERK_CONNECT_ENCRYPTION_KEY`. Keep that key with an
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
`DAGSVERK_CONNECT_DB_URL=jdbc:h2:file:/PRIVATE_STATE/connect` and the required
private settings. Bind to loopback behind TLS. Use one service process for H2.

For PostgreSQL, build the separate image from the repository root. Database kind
is selected at build time; do not switch an H2 ZIP to PostgreSQL with environment
variables:

```sh
docker build -f server/connect/Dockerfile --build-arg DAGSVERK_BUILD_COMMIT=FULL_SOURCE_COMMIT --build-arg DAGSVERK_BUILD_DIRTY=false -t dagsverk-connect:review .
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
`DAGSVERK_CONNECT_SERVICE_URL`; users do not need to configure this setting.

## Confirm setup in Dagsverk

On an installation with its HTTPS origin saved and active, open
**Administration → Internet accounts → Check setup**. Confirm the resolved
service address and that Google is configured. Copy **Google authorized redirect
URI** to the Web application's authorized redirect list in Google Cloud. It ends
in `/oauth/google/callback`; the installation's `/web/task-provider-callback.html`
is the return handoff, not the shared Google callback.

Then open **Account → Internet accounts**, choose Google Calendars, Tasks or both,
and connect a test user. Save calendar choices and task-list choices separately;
review the first task import. Verify background refresh, disconnect and reconnect.
The Admin check resolves discovery and reads `/v1/providers`, which checks broker
database health and configured registrations. It does not contact Google or verify
API enablement and consent. No service deployment or real provider acceptance is
implied by a successful synthetic test.

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

## Private Synology and Tailscale callbacks

Dagsverk itself need not be publicly exposed for a browser-based callback. Use a
stable Tailscale DNS name with a valid trusted HTTPS certificate and an exact
registered redirect including its port. The browser doing consent must remain
connected to the VPN and able to reach that name. Provider validation policies
still apply; registering a private HTTPS DNS origin is different from using a
private IP address or an untrusted/self-signed certificate. Confirm the provider
accepts the exact URI before rollout; do not bypass its validation.

In direct mode the browser visits Google/Microsoft and returns to the installation's
`/web/task-provider-callback.html`. In managed mode the browser returns first to
`https://MANAGED_HOST/oauth/{provider}/callback`, then follows the one-use handoff
to the private installation callback. The central broker does not open an inbound
connection to the NAS. The installation needs outbound access to discovery/broker
and provider APIs; the browser needs both public provider/broker and VPN access.
See [Tailscale setup](TAILSCALE.md), [Google redirect rules](https://developers.google.com/identity/protocols/oauth2/web-server#uri-validation)
and [Microsoft redirect rules](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url).

Before rollout, on the actual Synology/Tailscale installation record the source
commit and active mode, then verify with existing non-production Google,
personal Microsoft and Microsoft 365 test accounts:

- Exact callback accepted by each provider, trusted certificate, port and active VPN.
- Calendar-only, tasks-only and combined consent in each available mode; no
  unselected service scopes or email access. Cancel consent and retry safely.
- Select one calendar and list; verify actual event/task retrieval, reviewed first
  task import, token refresh after expiry, and explicit reconnect.
- Two provider identities stay separate; a second Dagsverk owner cannot see either.
- Stop/unconfigure the broker for a readiness failure, restore it, and explicitly
  refresh status; existing synchronized data and subscriptions remain usable.
- Switch modes, restart and reauthorize explicitly; existing connections retain
  their original grant/refresh path and saved per-service selections.

Automated fixtures exercise HTTPS browser returns and OAuth transport, not a
real Synology VPN or live provider consent. Live checks remain pending until an
operator supplies a test deployment and provider authorization. No deployment is
performed by the code-change workflow.
