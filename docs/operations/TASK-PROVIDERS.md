# Google Tasks and Microsoft To Do

Ordinary calendar subscriptions import calendar events and any `VTODO` records
actually included in the feed. They do not grant access to a separate task
service. Connect Google Tasks or Microsoft To Do under **Account → Task
connections**, then select the lists to import. Choose an optional Dagsverk
project for each selected list. **Review import** shows counts and sample titles;
**Import this list** accepts the complete first import. Selecting a list alone
does not create tasks. The connection belongs to the signed-in Dagsverk account.

Project mappings apply to new root tasks. Existing tasks keep their project,
and new children inherit their parent's project even if the list mapping has
changed. Status polling and pause/resume retain unsaved list/project choices;
a competing configuration change requires reviewing the draft before saving.

The current cloud connectors import tasks and subsequent provider changes.
Local edits stay in Dagsverk; completing a task here does not complete it at the
provider. Concurrent changes are retained for reconciliation. Disconnecting or
deselecting a list keeps imported tasks. Missing tasks are not automatically
deleted; provider-reported deletions require review. Connection status reports
errors and the number of tasks needing review.

Selected lists refresh in the background, normally every 15 minutes. **Refresh
from provider** requests an earlier scan and discovers newly created lists.
**Pause synchronization** stops provider I/O and keeps acknowledged pages;
**Resume synchronization** continues with the same selection. Staged pages are
private and survive a server restart. Repeated pages or incomplete scans do not
advance the public synchronization checkpoint. A first-import preview expires
after 15 minutes; refresh from the provider before approving an expired preview.
Manual refresh replaces ready previews for every selected list. Deselecting a
list or disconnecting discards its temporary pages while keeping imported tasks
and their completed checkpoints. A connection requiring reauthorization stays
stopped when resumed until the user reconnects.
Sources above 10,000 tasks or 32 MiB per list report a limit error and keep
existing tasks and the previous completed checkpoint.

Google polls an overlapping five-minute `updatedMin` window after a successful
import and performs a full reconciliation at least every 24 hours. Partial
observations keep unchanged siblings' provider positions. Google's `due` value
is a scheduled civil day in Dagsverk, without a fabricated deadline or reminder.
Reconnecting the same provider account retains its connection and list selection.
A revoked grant, changed app registration or expired client secret requires
reconnecting. Transient failures leave existing tasks intact.

iCloud Reminders is not connected by these cloud adapters. Calendar subscription
feeds that supply tasks remain supported; direct Reminders access requires a
separate Apple-authorized connector.

## Register the server

An operator configures a confidential web application before its Connect button
becomes available. Each user then grants their own read access. No provider
password is entered into Dagsverk.

Configure Dagsverk's public HTTPS origin first, including its port. For example,
`https://dagsverk.example.ts.net:8443` requires this exact redirect:

```text
https://dagsverk.example.ts.net:8443/web/task-provider-callback.html
```

Use the same hostname when opening Dagsverk and completing sign-in. The return
is bound to the initiating account and browser. Do not append a fragment, query
or trailing slash. Changing the public origin requires updating registrations
and reconnecting.

For Google, enable the Google Tasks API in a Google Cloud project, configure
the OAuth consent audience, and create a **Web application** OAuth client.
Register the redirect above and retain its client ID and secret. Dagsverk
requests `openid` and `https://www.googleapis.com/auth/tasks.readonly`, with
offline access for background refresh. In Testing, add intended accounts as
test users; Google's testing and verification policies apply. See Google's
[web-server flow](https://developers.google.com/identity/protocols/oauth2/web-server)
and [Tasks scopes](https://developers.google.com/workspace/tasks/auth).

For Microsoft, register an Entra application supporting accounts in any
organizational directory and personal Microsoft accounts. Add a **Web** platform
with the redirect above. Create a client secret and retain its **value**, not its
secret ID. Configure Graph delegated `User.Read` and `Tasks.Read`; Dagsverk also
requests `openid` and `offline_access`. Tenant consent policies may require
administrator approval. The connector uses the `common` authorization endpoint.
See [app registration](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app)
and [Graph permissions](https://learn.microsoft.com/en-us/graph/permissions-reference#tasksread).

## Retain operator settings

Use a Java properties file with only the configured providers:

```properties
timekeeper.tasks.google.client-id=REPLACE_WITH_CLIENT_ID
timekeeper.tasks.google.client-secret=REPLACE_WITH_CLIENT_SECRET
timekeeper.tasks.microsoft.client-id=REPLACE_WITH_CLIENT_ID
timekeeper.tasks.microsoft.client-secret=REPLACE_WITH_CLIENT_SECRET
```

These server app registrations are separate from each account's OAuth grant.
Preserve Java properties escaping for any backslashes in values. Keep filled
settings outside source control, browser code and Compose environment fields.

For the **Synology package**, edit the retained
`/var/packages/timekeeper/var/server/secrets/task_providers.properties` as an
operator, then restart Dagsverk in Package Center. Startup copies it into the
protected, read-only container mount. Edit the retained file, rather than its
generated copy under `target/private`. Upgrades preserve it. Native and Hyper
Backup application archives include it with a verified checksum. Restoring an
older archive without it clears destination app registrations; configure them
and reconnect as needed. Actual Package Center and Hyper Backup repository
restore still require [NAS acceptance](SYNOLOGY-PACKAGE.md).

For the **Compose distribution**, run `python3 setup.py` from `server/deploy`
after updating deployment files. It preserves existing credentials and creates
a comment-only `<state>/secrets/task-providers/application.properties`. Edit it,
retain the enclosing `secrets` directory's private permissions, and acquire the
updated mount:

```sh
docker compose up -d --force-recreate --wait api-1 api-2
```

Only the task-provider directory is mounted into the API replicas. Logical
`backup.sh`/`restore.sh` retains it; older backups restore without app
registrations. The host `secrets` parent remains private; mounted files are
readable by the container's application user.

For a **standalone Java server**, load the properties file through
`QUARKUS_CONFIG_LOCATIONS=file:/absolute/path/to/task-providers.properties`, or
set `TIMEKEEPER_GOOGLE_TASKS_CLIENT_ID`, `TIMEKEEPER_GOOGLE_TASKS_CLIENT_SECRET`,
`TIMEKEEPER_MICROSOFT_TASKS_CLIENT_ID` and
`TIMEKEEPER_MICROSOFT_TASKS_CLIENT_SECRET` in its protected service environment.
Restart after changes. Set `TIMEKEEPER_PUBLIC_ORIGIN` to the external HTTPS
origin unless central service settings already provide it.

## Account grants and recovery

Access and refresh tokens reside in private, owner-scoped server records. Public
connection resources, portable exports and account archives exclude them.
Reconnect after importing an account archive. Whole-instance/database backups
contain those records and need matching operator app registrations. Native
application backups include both.

`TIMEKEEPER_TASK_AUTOMATIC=false` disables scheduled task imports for standalone
servers; in an operator properties file use `timekeeper.tasks.automatic=false`.
Manual refresh remains available. These settings take effect at startup.

Local checks use synthetic providers and real HTTP/account boundaries, including
consent return navigation in Chromium and WebKit. They do not prove real app
registration acceptance. Verify sign-in, selected-list import, provider edits,
token refresh and reconnect with a disposable provider list before enabling a
deployment.
