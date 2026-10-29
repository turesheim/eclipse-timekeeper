# Accounts, browser sign-in and API tokens

Each login account has one stable internal owner ID. Clients (customers), projects,
tasks, labels, activities, work sessions, reporting preferences, revisions,
change feeds, cursors, command receipts and portable imports/exports belong to
that owner. Changing a username or display name never changes ownership.
A CLIENT is a customer record, not a login account. There is no sharing between
accounts. Administrators manage account access and account metadata; their role
never grants access to another account's work records or tokens.

## First administrator

For a fresh Docker installation, `python3 setup.py` prints a one-time code and
sets `TIMEKEEPER_SERVER_SETUP_FILE` in `.env`. Start the deployment, open `/web/`,
choose **First administrator setup**, and enter a username, a password of at least
15 characters twice, a primary recovery email address, and the one-time code. The username is used as the initial
display name. The code expires after 15 minutes.
An optional `setup.py --owner alice` explicitly binds setup to that owner ID;
omitting it creates a new random owner ID when setup completes.

For a standalone JVM, from the extracted server distribution:

```sh
python3 configure_accounts.py --output /secure/timekeeper/account-setup.json
export TIMEKEEPER_SERVER_SETUP_FILE=/secure/timekeeper/account-setup.json
export TIMEKEEPER_SERVER_DB_URL=jdbc:h2:/data/timekeeper/server
java -jar quarkus-run.jar
```

The file stores only the code's SHA-256 digest, expiry and intended purpose. Keep
it operator-controlled. The raw code is shown once; do not put it in URLs or
routine logs. In a source checkout, the tool is
`server/scripts/configure_accounts.py`. It is also included in the deployment
bundle beside `setup.py`.

To replace an expired code, use `--replace` explicitly and restart the replicas
with the new file. Completion and consumption are atomic in the shared database.
Restarts, old files and concurrent replicas cannot reopen completed first setup.
All replicas must use the same database and operator configuration.

If the setup code is rejected, no account is created and the chosen username and
password cannot be used for sign-in yet. Stay in **First administrator setup**
and use the code from the latest installation. Codes from an earlier installation
can have been replaced as well as expired. For the DSM package, reinstalling with
retained data renews a pending setup code without resetting completed accounts.
The sign-in page and server details show the installed SPK version when available.

The container runs as UID 10001. The mounted digest file must be readable by that
UID; `setup.py` sets mode 0644 within the read-only `/config` mount. For a manually
generated Docker challenge, place it at `state/config/account-setup.json`, set its
mode to 0644, set `TIMEKEEPER_SERVER_SETUP_FILE=/config/account-setup.json` in
`.env`, and recreate the replicas. Retain operator-only write access to that file.

## Language and region

The web client follows the browser's preferred language by default: Norwegian
Bokmål for Norwegian language preferences and English otherwise. Under
**My Account → Profile → Language and region**, choose browser defaults,
English (United Kingdom), English (United States), or Norsk bokmål (Norge).
An explicit choice follows the account across devices and takes effect after
the page reloads. Returning to browser defaults lets each device choose its own
language again. Changing language leaves account records, imports and time zone
unchanged; dates and display numbers follow the selected region.

## Existing data and accounts

Back up before upgrading. Schema 9 upgrades only the exact canonical server
schema 1, 2, 3, 4, 5, 6, 7 or 8, preserving owner IDs, UUIDs, timestamps, relations, versions and
revisions. PostgreSQL upgrades transactionally under a database advisory lock.
Stop both replicas for the first schema upgrade: an older replica cannot serve
schema 9. Pre-schema-4 browser sessions are ended because earlier versions did
not record their origin and browser binding. Schema-4 sessions and passkeys are
preserved; changing service settings later retires sessions at activation.
Passwords and Eclipse API tokens remain valid. Upgrading is refused by default. After backing up and stopping both
older replicas, set `TIMEKEEPER_ALLOW_SCHEMA_UPGRADE=true` in `.env` (or the JVM
environment), select the same new image for both replicas, and start them. Remove
the flag after successful migration. A fresh database needs no upgrade flag.
Automatic rolling updates therefore fail safely against an older schema and retain the
old schema instead of silently upgrading one replica under an older peer.
H2 marks an upgrade `MIGRATING` before its committing DDL; interruption
fails closed and requires restoring the pre-upgrade backup. There is no downgrade
or embedded Eclipse database migration.

Existing owners initially have an **Unclaimed** account. Their configured API
tokens keep their original owner and permissions. To turn one into the first
administrator, generate a SETUP challenge with its exact existing owner ID:

```sh
python3 configure_accounts.py --output /secure/timekeeper/account-setup.json --owner alice --replace
```

Configure the file and restart as above. Do not omit `--owner` when claiming
existing data. A matching username never claims an existing owner. Other legacy
owners require an explicit owner-bound RECOVERY challenge after first setup.
The administrator UI deliberately cannot claim an unclaimed owner's data.

The legacy JSON token registry is optional for new installations. If configured,
its file must remain valid/readable; an empty array is supported. Live registry
revocation, expiry, owner changes and permission reductions still apply on every
request, long poll and replay. A token revoked in the account UI remains revoked
even while its old registry entry exists. Never reassign an issued token to a
different owner. Replace registry files atomically.

An old browser token saved under `timekeeper.access-token` is exchanged once for
a cookie session and removed after acknowledgement. The **Existing API token**
sign-in option provides the same transition without writing the token to browser
storage. Such sessions remain dependent on the parent token's owner, validity
and permissions. Password-based sessions are independent of API-token revocation.

## Browser sessions and account management

Sign in with username/password. Usernames are case-insensitive ASCII letters,
digits, dots, underscores or hyphens (3–64 characters). Passwords are not trimmed
or truncated: at least 15 Unicode code points, at most 1024 UTF-8 bytes. Password
hashes use PBKDF2-HMAC-SHA256 with 600,000 iterations and a random salt. There is
no public signup. Email recovery is available when the operator configures SMTP.

The browser cookie is HttpOnly, Secure, SameSite=Lax, host-only and scoped to `/`.
Its opaque secret is stored only as a hash in the database. Normal sessions have
a 12-hour absolute and 30-minute idle lifetime; **Remember me** uses a persistent
cookie with a 30-day absolute and seven-day idle lifetime. Reload restores the
cookie session. Session revocation works across replicas without sticky routing.
CSRF values stay in memory; unsafe cookie requests require `X-CSRF-Token` and a
matching browser Origin when present. No new password or bearer token is saved
in localStorage/sessionStorage. First setup and login attempts are rate limited.

Use HTTPS outside loopback. For Tailscale Serve or another TLS reverse proxy, set
`TIMEKEEPER_PUBLIC_ORIGIN` to the exact browser origin, for example
`https://timekeeper.example.ts.net` (no trailing slash or path), on all replicas.
The server does not implicitly trust forwarded headers. Direct loopback HTTP is
supported for development by browsers that accept Secure cookies on loopback.

**My Account** opens a dedicated page with **Profile**, **Password** and
**API tokens** sections. Profile includes the username, optional display name
(blank uses the username), and time zone with a local-time preview and a
**Use this device's time zone** action. Each form saves independently. Password
changes require the current password and confirmation of the new password;
show/hide controls keep the current/new password autocomplete roles distinct.
Password changes revoke all that
account's existing sessions and tokens and require signing in again. **Sign out**
revokes the current browser session, closes requests and clears rendered records,
editors and the calendar clipboard. Other browser sessions and Eclipse tokens
remain valid. Expired/revoked sessions and owner changes clear the local account
view before another account can sign in.

Open **Account menu → Administration** (`/admin/`) for service settings and
**Users**. This page is available only to administrators with write
access. It lists independent accounts with their username, role and access
state. **Add user** and each row's **Edit** action open a full details page;
**Back to users** returns to the list. Administrators can create, enable,
disable and rename accounts and change the
administrator role. New users receive private default labels. Disabling a user or
changing their role revokes their sessions/tokens; enabling does not resurrect
old credentials. The last enabled administrator cannot be disabled or demoted.
Account deletion is not exposed: disabling retains the user's data.

User-management API requests use `/api/v1/admin/users` and
`/api/v1/admin/users/{key}`. The older preview `/api/v1/auth/users` routes are
removed so they cannot bypass a reverse-proxy policy on the administration
prefix; update preview automation to use the new paths.
**Administration → General settings** edits the public HTTPS address, passkey
configuration and SMTP delivery. Saved and effective values are shown separately.
Changes are stored centrally, so every server instance uses the same saved
configuration. Database connection details remain deployment-managed.

Saves require an administrator account session and the current administrator
password. An API token or a session created from an API token cannot save service
settings. Conditional versions protect concurrent edits; a lost reply requires
reading current values rather than repeating the write. SMTP passwords are
write-only: leave the field blank to preserve a saved password, supply a
replacement, or explicitly clear it. Reads never return the password or a hash.

After reviewing the saved values, stop and restart every server instance
(Timekeeper in Package Center, or both Compose API services). The first startup
activates the saved version and advances the shared authentication epoch. Browser
sessions, unfinished passkey requests and old recovery links are retired; account
identities, passwords, passkeys, API tokens and private work records remain.
An old running instance fails readiness and cannot continue browser or API access.
The effective hostname also controls the HTTP Host allowlist, alongside local
health-check names. Forwarded host headers never select the trusted hostname.

Schema 5 seeds settings once from the existing Docker/Synology configuration.
Normal upgrades and restarts preserve central settings; editing the old deployment
variables/files afterward does not overwrite them. `GET /api/v1/admin/settings`
returns a redacted snapshot and ETag; `PUT` saves the reviewed desired settings.

A reverse proxy can apply an additional network/access policy to `/admin` and
`/admin/` (including descendants) **and** `/api/v1/admin/` (including descendants).
Protect both namespaces; restricting the HTML alone does not restrict API
requests. Keep the backend reachable only by trusted infrastructure. Server-side
administrator/write checks and normal cookie/CSRF rules apply regardless of
proxy policy. Old `/web/#users` bookmarks redirect to `/admin/#users`; there is
no legacy user-management API alias.

**My Account → Your data** downloads a complete private JSON/CSV archive and
reviews a restore into the signed-in account. Work and selected preferences can
be restored; account identity, roles and credentials remain under the target
account's control. See [account downloads and restores](ACCOUNT-DATA.md).

Personal settings support `/web/#account/profile`, `/web/#account/password`,
`/web/#account/passkeys`, `/web/#account/tokens`, `/web/#account/data` and `/web/#account/import`.
Administration supports `/admin/#settings`, `/admin/#users` and
`/admin/#users/new`.
Navigation keeps activity/work-session timers running. Unsaved forms prompt
before leaving, stale conditional saves retain the draft, and uncertain writes
require reviewing current records before another submission. Sign-out or an
account change clears the page, drafts, displayed token secrets and pending
results. No shared workspace, membership or invitation model is introduced.

## Journal WebDAV access

**My account → Journal** shows the WebDAV collection URL and connection guidance.
Create a personal API token with read and write permissions under **My account →
API tokens** for each client. The token is the HTTP Basic password; revoke it
there to end that client's access. See the [WebDAV guide](JOURNAL-WEBDAV.md)
for guarded writes and tested client compatibility.

## Recovery email addresses

New accounts require a primary email address. Existing accounts can add one in
**My Account → Profile**, along with an optional independent backup address.
Each change requires the current password. Verify each address using its email
link; a pending replacement does not replace the previous verified address until
confirmed. Addresses are private to their owner, and administrators cannot edit
another account's recovery addresses. They do not identify or merge user accounts.

Choose **Forgot password?** and enter the username. The response is the same
whether the account exists or has verified addresses. For an enabled account,
Timekeeper sends a separate reset link to each verified primary/backup address.
An unavailable mailbox does not prevent delivery to the other one. Follow a link,
enter the new password twice, and submit explicitly. Merely opening or scanning a
link does not consume it. Links expire after 15 minutes, are single-use, and a
new reset request replaces earlier reset links. Token secrets are held in the URL
fragment and then in page memory; only their hashes are stored by the server.

Successful email recovery revokes all that account's sessions, API tokens and
pending recovery links. It preserves ownership, work records, username and role,
and requires ordinary sign-in with the new password. It cannot enable a disabled
account or claim a legacy owner. With SMTP unavailable or no verified address,
use the operator recovery procedure below. Ordinary setup and sign-in still work
when mail is disabled; initial addresses remain pending for later verification.

### Configure SMTP

Use a trusted SMTP service with an authorized sender address. After schema 5 is
initialized, use **Administration → General settings** and restart to apply changes.
The following deployment-file recipe seeds a new installation or an explicit
operator recovery; it does not replace existing central settings. For Docker Compose,
run the bundle's `python3 setup.py` once after upgrading to create the optional
mail files without replacing existing settings. Stop both API replicas, edit
`state/config/mail.properties`, and put the SMTP password in
`state/secrets/timekeeper_smtp_password`. For Synology, stop the package and edit
`/var/packages/timekeeper/var/server/config/mail.properties` and
`/var/packages/timekeeper/var/server/secrets/smtp_password`. The package copies
these retained files to its private mounts on start; do not edit the generated
`target/private` copies. Keep the password file readable by container UID 10001
(mode 0444 inside its private host directory). Example properties:

```properties
timekeeper.mail.enabled=true
quarkus.mailer.host=smtp.example.org
quarkus.mailer.port=587
quarkus.mailer.from=Timekeeper <timekeeper@example.org>
quarkus.mailer.username=timekeeper@example.org
quarkus.mailer.tls=false
quarkus.mailer.start-tls=REQUIRED
```

For implicit TLS, use the service's TLS port and `quarkus.mailer.tls=true` with
`quarkus.mailer.start-tls=DISABLED`. Restart/recreate both Compose API replicas,
or start the DSM package. Use My Account to request verification and confirm
receipt at both addresses before relying on recovery. SMTP credentials never
belong in Compose files, issue reports or screenshots. Delivery failure logs
contain fixed messages without links, addresses or credentials.

A standalone JVM accepts `TIMEKEEPER_MAIL_ENABLED`, `TIMEKEEPER_SMTP_HOST`,
`TIMEKEEPER_SMTP_PORT`, `TIMEKEEPER_SMTP_FROM`, `TIMEKEEPER_SMTP_USERNAME`,
`TIMEKEEPER_SMTP_PASSWORD`, `TIMEKEEPER_SMTP_TLS` and `TIMEKEEPER_SMTP_START_TLS`.
The container also accepts `TIMEKEEPER_SMTP_PASSWORD_FILE`. Mail defaults off;
the production mailer is never configured in mock mode.

### Change the public hostname

Recovery links use the configured exact HTTPS `TIMEKEEPER_PUBLIC_ORIGIN`, never
the incoming Host header or forwarded headers. Moving from a VPN hostname to a
public hostname does not change account ownership or verified email addresses.
Choose the intended long-term hostname when possible, but migration is supported:

1. Retain a protected database/configuration backup. If the current address is
   reachable, sign in as administrator, save the new exact HTTPS address and RP
   setting in General settings, and review the pending values.
2. Stop every instance, configure the new DNS/certificate/proxy/Tailscale route,
   retire the old route and restart every instance. Activation chooses a strictly
   newer authentication epoch; do not restore an old database to undo routing.
3. Sign in at the new address and verify recovery delivery. Existing accounts and
   verified addresses remain. Old sessions, passkey requests and email links are
   invalidated. An unrelated RP hostname requires replacement passkey enrollment.

If the old address is already unreachable, use explicit **operator recovery**:
stop every instance and retain a protected backup; set the desired public origin,
RP/mail deployment values and an authentication epoch strictly higher than the
saved/effective epochs. Set `TIMEKEEPER_SETTINGS_RECOVERY=true` for a JVM/Compose,
or `timekeeper.service-settings.recover=true` in DSM's retained
`/var/packages/timekeeper/var/server/config/mail.properties`. In DSM, the same
file can explicitly set `timekeeper.auth.public-origin`,
`timekeeper.auth.passkey-rp-id`, and `timekeeper.auth.origin-epoch`; add
`config_ordinal=500` so these explicit recovery values take precedence over the
generated runtime file. Remove the recovery flag before the normal restart. For Compose,
use `.env` for the origin/RP/epoch and the retained mail file for SMTP settings.
Start **one** instance to publish recovery configuration, stop it, remove the
recovery flag, then start all instances normally. A repeated or older recovery
epoch is refused; leaving the flag set deliberately prevents an accidental second
recovery. Ordinary deployment defaults never override the central configuration.
This operation replaces service settings, not account passwords or work data.

Protect dumps and mail configuration as credentials. Current native and Compose
backups include mail settings and the SMTP password. Restoring a schema-2 native
backup upgrades its database to schema 9 and keeps the target's current SMTP
configuration, because older backups did not contain those settings.

## Passkeys

Sign in with a password, open **My Account → Passkeys**, and choose **Add
passkey**. Give it a name and confirm your current password or a registered
passkey. Your browser handles Touch ID, device PIN, password manager or security
key selection. You can register several passkeys; a synced passkey can be used
from several devices. The list shows creation and last-used dates in your account
time zone. Names can be changed later.

Use **Sign in with a passkey** on the sign-in page. No username is required.
**Remember me** has the same cookie lifetime as password sign-in. Canceling a
browser prompt returns to the form; it does not start another prompt. Password
sign-in remains available. API-token sessions cannot add or remove passkeys;
sign in with your account first. Administrators cannot enroll keys for other
users.

Removing a passkey requires fresh password or passkey proof and immediately ends
sessions established with that key on every replica. Removing the key used for
the current session signs you out. Removal does not delete it from your password
manager; remove the saved entry there separately. You may remove the last key
because your password remains available. Changing your password retains passkeys
but ends existing sessions and API tokens. Explicit operator or email recovery
revokes passkeys too; enroll replacements after recovery. If every device is
lost, use your password, verified-email recovery, or operator recovery.

### Configure the origin and RP

Deployed passkeys require HTTPS with a DNS hostname. In **Administration → General
settings**, set the public address to the exact browser origin, without a trailing
slash or path. Passkey RP ID `AUTO` selects that hostname without its port; you can
pin it explicitly to the same hostname. Subdomains, unrelated domains,
wildcards and forwarded request headers do not select the RP. IP-based addresses
do not offer passkeys; password sign-in remains available. HTTPS `localhost` is
supported for isolated development tests.

For `https://seshat.tailad1f0.ts.net:8443`, use RP
`seshat.tailad1f0.ts.net`. Tailscale Serve or DSM Reverse Proxy terminates HTTPS;
the container still listens on its private HTTP address. See the
[Tailscale guide](TAILSCALE.md). Do not create a separate Tailscale instance.

Save the configuration and restart every server instance to activate it.
Disabling passkeys keeps credential records and password access. Central settings
survive upgrades and protected backups.

For initial deployment or explicit operator recovery, Compose passes
`TIMEKEEPER_PUBLIC_ORIGIN`, `TIMEKEEPER_PASSKEYS_ENABLED` (default `true`) and
`TIMEKEEPER_PASSKEY_RP_ID` (default `AUTO`) to both replicas. DSM accepts the
corresponding `timekeeper.auth.public-origin`, `timekeeper.auth.passkeys-enabled`
and `timekeeper.auth.passkey-rp-id` properties in retained `config/mail.properties`.
These deployment values do not overwrite existing central settings on a normal
restart; use the recovery procedure above when the saved address is unreachable.

Changing the network, server IP or proxy while keeping the HTTPS hostname and
port unchanged preserves passkeys, sessions and pending requests. A port change
keeps the RP, so passkeys remain compatible, but changes the exact origin: follow
the coordinated epoch cutover above and sign in again. Browser cookies are scoped
to hosts, not ports; retired ports cannot use the new origin's browser authority.

An unrelated hostname requires new passkeys. Follow the cutover procedure, using
the managed cutover or explicit operator recovery above, and sign in at the new address with the
existing password or recovery method. Add and test a replacement key there before
removing old ones. Old-RP credentials are identified as unavailable at this server;
their public keys are retained for owner review, not relabeled. This path works
when the old VPN hostname has already disappeared. Accounts, work records,
passwords, verified email addresses and Eclipse tokens keep their ownership.
There is no cross-host redirect carrying authentication secrets.

To roll back an origin configuration, keep the current database and use an epoch
higher than every previously deployed value, including on the old hostname. Never
lower the epoch or restore an older authentication backup merely to undo a routing
change. A schema-8 or earlier binary cannot serve schema 9: rolling back that binary requires
a matching pre-upgrade backup and loses later changes. Perform backup recovery
offline and account for revoked credentials as described below.

## Eclipse and personal API tokens

Create a separate named token for each installation under **My Account → API tokens**. Choose
read-only or read/write access and an optional expiry (entered in local time).
Copy the token when it is shown; only its hash is stored and it cannot be read
back. The **Copy token** button copies only the newly issued secret; **Done** or
navigating away clears it. Lists expose the token's name, scope, expiry,
revocation and last-use time, displayed in the account's configured time zone.
Revocation asks for confirmation using the token's name and explains which
connections lose access. **Delete** permanently removes a personal token from
the list and invalidates its client connections and derived browser sessions.
Active, expired and revoked personal tokens can be deleted. Imported registry
tokens retain their revocation entries so the registry cannot recreate access.
Paste it into Eclipse's Timekeeper backend preferences, where Eclipse secure
storage retains it. Surrounding whitespace from a pasted token is ignored by
Eclipse and browser sign-in; whitespace inside a token is rejected as an invalid
credential. No CLIENT or WORK_SESSION UI is required in Eclipse.

Eclipse's **Test connection** reports the API address and HTTP status when a
server rejects a request. If a token is rejected, verify that the Server URL
matches the server where it was created. Tokens belong to that server; another
Timekeeper installation will not recognize them. Connection failures also give
guidance for network/VPN problems, HTTPS certificates, redirects and incorrect
API paths, without displaying credentials or server response bodies.

To rotate, create a replacement, configure the client, then revoke the old token.
Revocation is independent per token and applies to pending polls and command
replays. Browser sessions derived from that token are also invalidated; ordinary
password sessions remain valid. Data requests reject token and cookie credentials sent together. The explicit
`/auth/token-login` transition instead uses the supplied bearer credential and
replaces the browser cookie after checking its Origin.

## Operator recovery

Recovery requires filesystem/deployment control and an explicit existing owner:

```sh
python3 configure_accounts.py --purpose RECOVERY --owner alice --output /secure/timekeeper/account-setup.json --replace
```

Configure the file, restart the replicas, then choose **Recover account** in the
web client. Enter the username, the new password twice, and the recovery code.
The username is also used as the display name.
Recovery preserves owner IDs and the existing administrator role, can re-enable
a disabled account, and revokes all its prior passkeys, pending passkey requests,
sessions and API tokens. For an
unclaimed legacy owner it establishes a normal user account. It cannot create
an administrator after bootstrap. Codes expire and are single-use across
restarts and replicas; do not use recovery to transfer another user's data.

Protect database dumps, deployment secrets and operator configuration as
administrative credentials. Backups include account/password/token hashes and
session state, passkey public keys, opaque user handles, revocation records and
short-lived challenges. No private keys or biometric data are stored. Portable
work-data exports do not contain passkey records. Restoring an older backup can
restore credentials revoked after that backup: use explicit recovery for affected
accounts before exposing a restored deployment.
