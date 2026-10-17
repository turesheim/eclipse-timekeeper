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

## Existing data and accounts

Back up before upgrading. Schema 3 upgrades only the exact canonical server
schema 1 or 2, preserving owner IDs, UUIDs, timestamps, relations, versions and
revisions. PostgreSQL upgrades transactionally under a database advisory lock.
Stop both replicas for the first schema upgrade: an older replica cannot serve
schema 3. Upgrading is refused by default. After backing up and stopping both
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

The separate **Users** page is available only to administrators with write
access. It lists independent accounts with their username, role and access
state. **Add user** and each row's **Edit** action open a full details page;
**Back to users** returns to the list. Administrators can create, enable,
disable and rename accounts and change the
administrator role. New users receive private default labels. Disabling a user or
changing their role revokes their sessions/tokens; enabling does not resurrect
old credentials. The last enabled administrator cannot be disabled or demoted.
Account deletion is not exposed: disabling retains the user's data.

Both settings pages support bookmarks and browser Back under `/web/`:
`#account/profile`, `#account/password`, `#account/tokens` and `#users`.
Navigation keeps activity/work-session timers running. Unsaved forms prompt
before leaving, stale conditional saves retain the draft, and uncertain writes
require reviewing current records before another submission. Sign-out or an
account change clears the page, drafts, displayed token secrets and pending
results. No shared workspace, membership or invitation model is introduced.

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

Use a trusted SMTP service with an authorized sender address. For Docker Compose,
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

1. Stop every replica and retain a database/configuration backup. Set the new
   origin, configure HTTPS/forwarding for it, and retire access through the old
   origin. Do not allow an old replica to continue serving the old configuration.
2. Increase `TIMEKEEPER_AUTH_ORIGIN_EPOCH` in Compose `.env` on every replica;
   for a JVM, set that environment variable, or for DSM increase
   `timekeeper.auth.origin-epoch` in retained `config/mail.properties` and update
   the public origin through the upgrade wizard. The first epoch is 1. Use a
   strictly larger value even when deliberately returning to an earlier hostname.
3. Start the new configuration consistently. The database records the origin and
   epoch; a stale/mismatched replica refuses mail recovery. All previous email
   links are invalidated and pending deliveries recheck the epoch. Ask users to
   request fresh verification/reset links at the new address. Old hostname cookies
   do not transfer; users sign in again at the new hostname.

Restarting with the same origin/epoch leaves unexpired links usable. Restoring a
backup or importing from another host retains verified addresses and the stored
origin; configure a newer epoch before enabling mail at a different origin.
Protect dumps and mail configuration as credentials. Current native and Compose
backups include mail settings and the SMTP password. Restoring a schema-2 native
backup upgrades its database to schema 3 and keeps the target's current SMTP
configuration, because older backups did not contain those settings.

## Eclipse and personal API tokens

Create a separate named token for each installation under **My Account → API tokens**. Choose
read-only or read/write access and an optional expiry (entered in local time).
Copy the token when it is shown; only its hash is stored and it cannot be read
back. The **Copy token** button copies only the newly issued secret; **Done** or
navigating away clears it. Lists expose the token's name, scope, expiry,
revocation and last-use time, displayed in the account's configured time zone.
Revocation asks for confirmation using the token's name and explains which
connections lose access.
Paste it into Eclipse's Timekeeper backend preferences, where Eclipse secure
storage retains it. No CLIENT or WORK_SESSION UI is required in Eclipse.

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
a disabled account, and revokes all its prior sessions/API tokens. For an
unclaimed legacy owner it establishes a normal user account. It cannot create
an administrator after bootstrap. Codes expire and are single-use across
restarts and replicas; do not use recovery to transfer another user's data.

Protect database dumps, deployment secrets and operator configuration as
administrative credentials. Backups include account/password/token hashes and
session state. Restoring an older backup can restore credentials revoked after
that backup: revoke sessions/tokens or use explicit recovery for affected
accounts before exposing a restored deployment.
