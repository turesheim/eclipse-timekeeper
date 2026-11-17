# Connect Google, Microsoft or a calendar subscription

Choose the connection that provides the data you need:

| Connection | How you add it | What it provides |
| --- | --- | --- |
| **Google or Microsoft account** | Sign in with Google or Microsoft under **My Account → Internet accounts** | Google Calendar/Tasks or Outlook calendars/Microsoft To Do. Choose calendars, tasks or both. |
| **Calendar subscription** | Paste an HTTPS/webcal feed address under **My Account → Calendars** | Read-only events and any to-do items actually included in that feed. Google/Microsoft calendar feeds do not include Google Tasks or Microsoft To Do. |

Dagsverk sign-in is separate. A connected account belongs to the Dagsverk user
who added it; email is not synchronized.

## Administrator setup and user connections

An **OAuth app registration** identifies Dagsverk to Google or Microsoft. An
administrator configures its client ID, secret and redirect URI once so users
can connect their own accounts. Each user's **Internet account** then stores
that user's authorization and calendar/task selections.

Administrators choose **Direct registration** (their own app registration) or a
**Managed OAuth service** (an operator maintains it). For a self-hosted installation,
use direct registration unless an operator has supplied a working OAuth service.
See [the mode comparison and setup steps](SHARED-SIGN-IN.md#choose-the-oauth-mode).

## Connect and select what to import

1. Open **My Account → Internet accounts → Add account** and choose Google or Microsoft. Select Calendars, Tasks or both. For Google, choose **Read only** or **Two-way synchronization**, then connect and approve the requested access.
2. Select calendars and their Dagsverk clients, and task lists and optional projects.
3. Choose the account’s single **Save** button to save those subscriptions together.
   Task import starts automatically, including existing tasks and later additions.

Google Tasks uses its own API; no calendar subscription or intermediate file is
needed. Calendar selections display events without creating worked time. Mappings
appear for selected items, and status refreshes retain unsaved choices. New root
tasks use the selected project; existing tasks keep theirs and children inherit
their parent’s project. A list mapping does not export the other tasks in that
project.

## Import and editing permissions

Synchronization is automatic whenever an account and its selected lists are
active. Task access controls whether supported local changes can reach the provider:

| Google Tasks access | Behavior |
| --- | --- |
| **Read only** | Import automatically; local edits stay in Dagsverk. OAuth requests `tasks.readonly`. |
| **Two-way synchronization** | Import and send supported changes automatically after approving the `tasks` write scope. |

For an existing account, open **Details → More options**, change its task access
and **Reconnect** to approve the new permission. Reconnecting with read-only access
stops new provider writes. With two-way access, supported pending local edits
can synchronize too; review those differences before granting access. Calendars
remain read-only. Microsoft has different
field capabilities; see the [provider reference](TASK-PROVIDER-REFERENCE.md).

With Google write access, ordinary supported edits, completion/reopening and
explicit deletion synchronize in the background. To create a native task in
Google, choose its **Synchronized list** in the task editor. Supported parent/order
and list moves synchronize too. Dagsverk retains its own project assignment.
Healthy synchronization has no Prepare/Review/Send step. Review appears for a
conflict, uncertain identity or result, or a provider restriction.

Google supports titles, plain text notes, completion and a scheduled civil day.
Its scheduled day is **not** a timed deadline or reminder. Rich notes, timed
start/deadline values, alerts and other unsupported fields stay local while
independent supported changes continue. Source links/assignment context are
read-only. See [editing restrictions and recovery](TASK-PROVIDER-REFERENCE.md).

## Refresh, pause and reconnect

Selected task lists normally refresh about once a minute; provider backoff or an
outage can extend that interval. **Refresh from provider** also
discovers new calendars and lists. **Pause synchronization** and resume retain
selections. Each account’s **Details** contains service switches and list choices. **More
options** contains task access, reconnect and disconnect.
Deselecting a task list or disconnecting keeps its imported tasks.

Reconnect after revoked permission, an expired client secret or an account-archive
import. If a change needs review or shows **Outcome unknown**, follow the
[troubleshooting reference](TASK-PROVIDER-REFERENCE.md); avoid creating a replacement
Google task while its original result is uncertain. Confirmed deletions synchronize with history safeguards. A failed, incomplete or
unauthorized scan never implies deletion; conflicting deletion needs a decision.
