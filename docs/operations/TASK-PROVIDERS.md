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

1. Open **My Account → Internet accounts → Add Google account** or **Add Microsoft
   account**. Select Calendars, Tasks or both, then connect and approve access.
2. Select calendars and their Dagsverk clients, then choose **Save calendars**.
   Events appear read-only alongside subscriptions; they do not create worked time.
3. Select task lists and optional projects, then choose **Save task lists**.
   Synchronization starts automatically, including existing tasks and later additions.

Calendar and task selections save separately. Mappings appear for selected items;
status refreshes retain unsaved choices. New root tasks use the selected project,
while existing tasks keep theirs and children inherit their parent's project.

## Import and editing permissions

| Service | Access in Dagsverk |
| --- | --- |
| Google Calendar and Outlook calendars | Read-only |
| Microsoft To Do | Import-only; local edits stay in Dagsverk |
| Google Tasks | Import-only by default; optional **Two-way · review outgoing changes** |

To enable Google task editing, open **Connection settings**, select two-way task
access and **Reconnect** to approve it. Under **Changes to Google Tasks → Prepare
a Google change**, review supported edits, creation or deletion before sending.
Title, plain text notes, completion and scheduled day are supported; other values
remain local. An ordinary local edit is not sent automatically.

## Refresh, pause and reconnect

Selected lists normally refresh every 15 minutes. **Refresh from provider** also
discovers new calendars and lists. **Pause synchronization** and resume retain
selections. **Connection settings** contains permissions, reconnect and disconnect.
Deselecting a task list or disconnecting keeps its imported tasks.

Reconnect after revoked permission, an expired client secret or an account-archive
import. If a change needs review or shows **Outcome unknown**, follow the
[troubleshooting reference](TASK-PROVIDER-REFERENCE.md); avoid creating a replacement
Google task while its original result is uncertain. Provider-reported deletions
require review; a missing item alone does not delete a Dagsverk task.
