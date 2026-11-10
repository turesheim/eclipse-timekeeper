# Internet accounts, calendars and tasks

Ordinary calendar subscriptions import calendar events and any `VTODO` records
actually included in the feed. They do not grant access to a separate task
service. Google Calendar and Microsoft calendar subscriptions do not contain
Google Tasks or Microsoft To Do records. Connect Google or Microsoft under **Account → Internet accounts**, then select the lists to import. Choose an optional Dagsverk
project for each selected list. **Review import** shows counts and sample titles;
**Import this list** accepts the complete first import. Selecting a list alone
does not create tasks. The connection belongs to the signed-in Dagsverk account.

Project mappings apply to new root tasks. Existing tasks keep their project,
and new children inherit their parent's project even if the list mapping has
changed. Status polling and pause/resume retain unsaved list/project choices;
a competing configuration change requires reviewing the draft before saving.

Connections default to import-only: local edits stay in Dagsverk. Google also
offers **Two-way · review outgoing changes**. Select that access before Connect
or Reconnect and grant the Google Tasks write scope. Existing broad grants never
silently enable two-way mode. Microsoft remains import-only.

For an imported Google task, **Prepare a Google change** previews edits or a
deletion without sending it. Review the proposed values and restrictions, then
explicitly send the reviewed change. Supported edits are title, plain text notes,
completion/reopening and the scheduled civil day. Other values remain local;
Google's title and note limits are checked before writing. Assigned tasks cannot
be deleted here, and their notes must be edited at the original source. A Google
deletion keeps the ordinary Dagsverk task for separate review.

For an existing unlinked root task, choose **Create in Google**, select the task
and target list, then review and send. A confirmed result links the returned
Google ID to that same native task; its project, rich text and unsupported
fields remain intact, including edits made while the request was in flight.
Hierarchy and order stay local for tasks created this way, as stated in the
preview. Imported Google hierarchy/order still synchronize normally.

If an insert response is lost, Dagsverk never repeats the insert or guesses an
identity from its title. New unlinked items from that provider/account/list are
held for review; already linked tasks continue to synchronize. Choose **Check
Google result** to obtain a fresh full scan, then **Find unlinked Google tasks**.
Search titles or exact IDs, review both sets of values, and explicitly link the
correct provider identity. Results show at most 100 matching tasks; refine the
search to find another result. The scan and local task must still match the
reviewed versions. This local reconciliation sends no provider mutation and
does not claim that the original insert succeeded. If the local task was
explicitly deleted during the request, identifying the result records that
provider ID as locally deleted rather than recreating the task. If no correct
identity can be established, retain the uncertain result for investigation. An
unresolved write protects its connection and list from catalog deletion, so
reconnecting can still reach its recovery record. Disconnect remains available.

Concurrent changes are retained for reconciliation. Disconnecting or
deselecting a list keeps imported tasks. Missing tasks are not automatically
deleted; provider-reported deletions require review. Connection status reports
errors and the number of tasks needing review.

Tasks shows a provider's web link, assignment source and readable related links
separately from the task's own editable URL. Google's assignment context identifies
the original document or Chat space; it does not supply a person's identity.
These source fields are read-only, following the [Google Task resource](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks). Source-link identities include the Google
account/list namespace, so another connected account's resources are not matched
by title or provider name. Missing link observations retain existing resources;
a changed Google link array conflicts with independent local resource changes
rather than removing those resources. The full original provider context remains
in its namespaced task payload.

Google changes use conditional PATCH/DELETE with the observed provider ETag.
The server records a durable intent before the request and its acknowledgement
before changing a common baseline. A lost response or interrupted in-flight
request is **Outcome unknown** and is never resent automatically. **Check Google
result** reads current values, including with a subsequently read-only grant.
An absent item or HTTP 404 alone does not prove a deletion; a fresh explicit
deletion record is required.

When a result needs reconciliation, compare current Dagsverk and observed Google
values. Choosing either side for conflicts retains unrelated local edits and
accepts independent Google changes. Both the displayed proposal and the current
task have their own preconditions; stale decisions require a fresh review.
Reconciliation of an uncertain operation accepts observed values without
claiming the original request succeeded. The account page shows active operations
and the latest 20 completed results; credentials and private observations remain
outside portable account data. User-requested refreshes and queued writes honor
the provider's stored Retry-After delay.

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

## Choose sign-in or a subscription

**Account → Internet accounts** offers separate Google and Microsoft sections.
Choose Calendars, Tasks or both before connecting. One sign-in supports two
independent synchronizers: save selected calendars with optional client mappings,
and select task lists with optional project mappings separately. Calendar-only
access does not request task access. Read-only calendar events appear alongside
subscription events in Today and Week; they do not create tasks or worked time.
Each managed calendar can be refreshed or removed from **Account → Calendars**.
Saving calendar choices preserves unsaved task-list choices, and vice versa.

The same page offers **Add a calendar subscription**. This opens the ordinary
HTTPS/webcal feed setup without provider login. Specialized feeds that actually
include VTODO remain supported under Advanced feed options. Google and Microsoft
calendar feeds should use the normal Calendar events choice.

Shared sign-in is the default. Its one central OAuth registration serves all
installations; each user still authorizes their own account and selected
services. No local administrator provider enablement or per-NAS registration is
required. If shared sign-in is temporarily unavailable, existing imported data
and ordinary subscriptions remain usable. The service must first be hosted and
registered by the project; see [shared sign-in operations](SHARED-SIGN-IN.md).

Google calendar synchronization keeps incremental checkpoints, resetting expired
sync tokens with a complete scan. Microsoft reads complete calendar definitions,
recurring masters and exceptions. Incomplete or invalid pages keep the last
complete calendar cache. Calendar catalogs and event errors are reported
separately from task import errors. Sources above 5,000 event definitions or
32 MiB are rejected without replacing existing events; generated iCalendar also
has the existing 2 MiB bound. Windows time zones use the vendored Unicode CLDR 48
mapping and its bundled Unicode license.
Microsoft exception definitions count toward the same calendar budget. Each
recurring series also has a 4 MiB total response budget across its exception pages.

## Advanced: direct server registration

Existing self-managed registrations remain supported for task-only connections.
Opt out of shared sign-in with `timekeeper.connect.shared=false` (or
`TIMEKEEPER_CONNECT_SHARED=false`) before using these settings. Direct legacy
registration does not add the new calendar API synchronizer. No provider password
is entered into Dagsverk.

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
requests `openid` and `https://www.googleapis.com/auth/tasks.readonly` for
import-only access, or `https://www.googleapis.com/auth/tasks` after an explicit
two-way choice, with offline access for background refresh. In Testing, add intended accounts as
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
contain those records. Shared grants also depend on the central service retaining
its matching encrypted records; direct grants need matching operator app
registrations. Native application backups include local settings and records.

`TIMEKEEPER_TASK_AUTOMATIC=false` disables scheduled task imports for standalone
servers; in an operator properties file use `timekeeper.tasks.automatic=false`.
Manual refresh remains available. These settings take effect at startup.

Local checks use synthetic providers and real HTTP/account boundaries, including
consent return navigation in Chromium and WebKit. They do not prove real app
registration acceptance. Verify sign-in, selected-list import, provider edits,
token refresh and reconnect with a disposable provider list before enabling a
deployment.

### Incoming conflicts and confirmed deletions

Open **Account → Internet accounts → Review incoming changes** for the affected
list. The comparison uses the saved complete provider scan and shows its time,
source identity, local values and observed remote values. Filter by title or
exact source identity when a list has more than 100 decisions. Polling retains
an open comparison; a changed task, list selection or scan requires reopening
it. Comparisons expire after 15 minutes; refresh synchronization for a new scan.

Choose **Keep Dagsverk for conflicts** or **Use Google Tasks for conflicts**. Both
choices accept independent remote changes and preserve unsupported local
fields. Related resources added locally remain present when accepting changed
Google source links. This action updates Dagsverk's comparison baseline and
sends no writes to Google, including with a read-only provider grant.

A provider's explicit deletion offers **Keep the Dagsverk task** or **Delete the
Dagsverk task**. Keeping it acknowledges that deleted identity without inventing
a tombstone or removing the native task. A later live observation clears the
acknowledgement; a subsequent deletion needs another decision. Deletion is
available only with one active source link and passes the canonical task
service's deletion rules. Other source links, unresolved identities and
incomplete hierarchy remain review decisions. Missing access or absent items
never imply deletion. Dagsverk sessions with read-only access can inspect
comparisons but cannot resolve them.

If an uncertain creation cannot be identified, its review offers **Allow imports
without linking**. Acknowledge that a late Google result may be imported as a
separate task before choosing it. This records **Left unlinked · imports
allowed**: it does not assert that Google failed, cancel the original request,
change the native task or resend the creation. An identical retry returns the
stored decision. The acknowledgement also works while the connection is paused
or disconnected, without provider access. Read-only Dagsverk sessions cannot
make this decision. Subsequent imports may add a separate task for a late result;
no title-based matching is performed.

Disconnected accounts with pending requests remain visible using public
connection metadata only; their credentials are still removed. Unsent requests
can be cancelled there, and unidentified creations can be left unlinked. After
the last pending request is settled, the disconnected card disappears. Checking
Google or accepting a known provider result still requires reconnecting.

### Automated restart acceptance

From the repository root, with Java 21, Python 3.13 and Docker available:

```sh
python3 server/scripts/task_provider_restart.py --output server/target/verification/task-provider-restart.json
```

The runner starts its own disposable PostgreSQL 17 fixture and launches two
separate Quarkus server test JVMs against that same database. It records distinct
process IDs and checks acknowledged-page continuation, repeated synchronization
without duplicate tasks, an expired SENDING lease becoming UNKNOWN without a
provider request, and a durable ACK updating its baseline without another
provider request. The expiry fixture advances both stored lease and refresh
deadlines; this checks timeout recovery rather than claiming instant lease
reclamation after a crash. The provider observations are synthetic. No deployed
server or live Google account is contacted.

Use `--postgres-image postgres:18.6-bookworm` for PostgreSQL 18. Both supported
versions run in container CI and upload the JSON report with the corresponding
acceptance artifacts. `--expected-commit` requires the exact clean checkout;
reports record the commit and whether local source was dirty. The normal Maven
suite skips this one opt-in process-restart test.

### New identities and moved tasks

Google connections default to **Review new Google identities before importing**.
The first selected-list import still has its complete preview and approval.
After that, linked tasks continue to update automatically; new identities wait
for **Review new identities**. Uncheck the setting and save the selected lists
if you prefer automatic additions. A provider ID change can then appear as a
separate task; titles are never used to decide that records are the same.

Each observed identity offers **Import as a new task** or **Use the existing
Dagsverk task**. For replacement, choose the exact existing task and source link
from the same provider account, compare current and observed
values, and acknowledge the choice. The scan and native task each have their
own revision guard, and scans expire after 15 minutes. Stale choices retain the
comparison and require a fresh review. The target list must be selected and
readable. Explicitly retiring an existing owned link also works if its old list
is unselected or unavailable; it does not modify or fetch that old list.

Replacement keeps the native task ID and project, retains unrelated references,
records the previous key as a lookup alias, and preserves native hierarchy.
Hierarchy stays local for the reconciled identity, as it does for tasks created
at Google from native tasks. Independent observed field changes are accepted;
conflicting local values stay. Source resources refresh while unrelated or
locally edited resources remain. An explicit deletion of a retired alias cannot
delete the retained task; a live retired identity can be explicitly made current
again. Deliberate local deletion markers and uncertain outgoing creations cannot
be bypassed by identity review. These local decisions send no provider writes.

The [Google move API](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks/move)
returns a task resource, but does not promise cross-list ID stability. This
connector therefore requires explicit identity choices instead of assuming a
move from a title match or an absent record.
