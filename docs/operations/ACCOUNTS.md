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
15 characters twice, and the one-time code. The username is used as the initial
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

The container runs as UID 10001. The mounted digest file must be readable by that
UID; `setup.py` sets mode 0644 within the read-only `/config` mount. For a manually
generated Docker challenge, place it at `state/config/account-setup.json`, set its
mode to 0644, set `TIMEKEEPER_SERVER_SETUP_FILE=/config/account-setup.json` in
`.env`, and recreate the replicas. Retain operator-only write access to that file.

## Existing data and accounts

Back up before upgrading. Schema 2 upgrades only the exact canonical server
schema 1, preserving owner IDs, UUIDs, timestamps, relations, versions and
revisions. PostgreSQL upgrades transactionally under a database advisory lock.
Stop both replicas for the first schema upgrade: an older replica cannot serve
schema 2. Upgrading is refused by default. After backing up and stopping both
older replicas, set `TIMEKEEPER_ALLOW_SCHEMA_UPGRADE=true` in `.env` (or the JVM
environment), select the same new image for both replicas, and start them. Remove
the flag after successful migration. A fresh database needs no upgrade flag.
Automatic rolling updates therefore fail safely against schema 1 and retain the
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
no public signup or email recovery service.

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

**Account** lets users rename their login/display name, change their password,
and create/revoke their own named API tokens. Password changes revoke all that
account's existing sessions and tokens and require signing in again. **Sign out**
revokes the current browser session, closes requests and clears rendered records,
editors and the calendar clipboard. Other browser sessions and Eclipse tokens
remain valid. Expired/revoked sessions and owner changes clear the local account
view before another account can sign in.

Administrators can create, enable, disable and rename accounts and change the
administrator role. New users receive private default labels. Disabling a user or
changing their role revokes their sessions/tokens; enabling does not resurrect
old credentials. The last enabled administrator cannot be disabled or demoted.
Account deletion is not exposed: disabling retains the user's data.

## Eclipse and personal API tokens

Create a separate named token for each installation under **Account**. Choose
read-only or read/write access and an optional expiry (entered in local time).
Copy the token when it is shown; only its hash is stored and it cannot be read
back. Lists expose the token's name, scope, expiry, revocation and last-use time.
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
