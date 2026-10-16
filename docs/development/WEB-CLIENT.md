# Timekeeper web client

The signed-in footer reads `/api/v1/server` to identify the running server
version and commit. Its details distinguish immutable build time, explicitly
recorded deployment time and process-start time. Information refreshes after
long polls and manual refreshes, including during rolling updates. Missing
deployment metadata is shown as **Not recorded**. The Docker/Synology deployment
guide records `TIMEKEEPER_DEPLOYED_AT`; direct JVM deployments may supply the
same UTC timestamp as an environment variable. Maven embeds Git identity and
build time in the server, and Docker builds receive Git identity as build args.

The standalone server serves its bundled client at `/web/`. Production uses
native browser modules; no Node process, CDN or separate database is needed.
Maven includes `server/web/src` in the server JAR. Users sign in with username and
password; the [account guide](../operations/ACCOUNTS.md) describes first setup,
legacy owner migration and recovery. The browser restores an HttpOnly Secure
cookie after reload. **Remember me** controls persistence across browser restarts.
API tokens for Eclipse are created under **Account**, shown once, and stored only
as hashes on the server. New credentials never enter browser storage. Existing
saved tokens are exchanged for a parent-bound cookie and then removed. Sign out
revokes the cookie session and clears records, drafts, requests and clipboard.

Font Awesome Free 7.3.1 supplies locally bundled icon fonts for larger tree
controls and action buttons. Buttons retain accessible names and hover titles;
fonts and their upstream license are included in the server distribution.

## Design baseline

Bootstrap 5.3.8 supplies the shared buttons, forms, navigation, cards and tables.
The small application theme in `style.css` defines the green palette, typography,
spacing and visible keyboard focus. The work-session card establishes the baseline:
a client-color accent, explicit running/stopped status, four time totals and
consistently sized icon actions. Client color is always accompanied by text.
The same controls apply to sign-in/setup/recovery, account management, activities,
clients, projects, tasks and labels. Record tables become labelled cards on narrow
screens so actions stay in view; Workweek and Calendar retain their specialized
scrollable layouts. Calendar geometry is isolated in `calendar.css`.

These screenshots use synthetic records from `tests/design.spec.js` at 1440 and
390 pixels wide. The design tests also capture all workspace views and account,
sign-in and validation states under `server/web/test-results/design-*.png`.
Review those images after layout changes; automated accessibility checks cannot
replace visual review or keyboard testing.

![Desktop work-session baseline](../assets/screenshots/bootstrap-work-session-desktop.png)

![Narrow-screen work-session baseline](../assets/screenshots/bootstrap-work-session-narrow.png)

Bootstrap's exact npm version and integrity are pinned in `package-lock.json`.
Its compiled CSS and MIT license are committed under `src/assets/bootstrap`,
alongside the SVGs extracted from its data URLs. No Bootstrap JavaScript is needed:
native modal dialogs and the existing application navigation handle interactions.
The deployed client uses only same-origin files, including icon fonts, and retains
its `img-src 'self'` security policy. Maven packages these assets directly; it does
not require npm or an external CDN at runtime or when building the server.

After deliberately updating the pinned dependency, run:

```sh
cd server/web
npm ci --ignore-scripts
npm run vendor:bootstrap
npm run check:assets
```

Commit the regenerated CSS, SVGs and license together. CI checks that the bundled
assets match the pinned dependency, then runs real-server browser tests with axe
WCAG A/AA checks for all views at desktop and narrow widths. Those tests verify
that no design asset triggers a third-party request or is blocked by CSP, and exercise
keyboard focus, required fields and error recovery. Native browser validation and
the API's existing concurrency and authorization rules remain in use.

macOS WebKit reports inline-style CSP diagnostics for native select controls even
on a plain page without application styles/scripts; Playwright screenshots also
add inline-style diagnostics. The WebKit asset check excludes those inline reports
while continuing to reject blocked design assets. Chromium checks every CSP event.
The production security policy is unchanged. The macOS WebKit keyboard check uses
Option-Shift-Tab to include buttons, matching
[Safari's native keyboard navigation](https://support.apple.com/guide/safari/cpsh003/mac).

Projects, tasks, labels, activities and work sessions use API v1. Start/stop uses durable
idempotency keys. Editors/deletion retain the original opaque ETag, including
across full resynchronization, rather than upgrading a stale draft. Validation,
permission, network and concurrency failures preserve drafts. Loss of authorization
or a changed owner clears the account view and its drafts. An uncertain
ordinary write requires cancel, refresh and review; uncertain start/stop offers
explicit retry of the same frozen payload/key. Acknowledged saves remain
successful when a subsequent refresh fails.

Reports use the account's IANA time zone and the server's exact daily/weekly
totals. At sign-in, an untouched UTC profile (version zero) adopts the browser's
local IANA time zone. This is saved to the account so work sessions, activities,
calendar boundaries and other clients use the same zone. An explicitly selected
zone, including UTC, is retained when signing in from another device. Read-only
tokens retain the existing account zone. Use **Time zone** to change it explicitly.
The default Workweek groups projects, tasks, subtasks and activities,
with seven daily duration columns, a weekly total column and expandable rows
like the Eclipse view. Task totals include their subtasks. **Hide activities**
keeps the project/task outline visible; **Show activities** restores its rows.
**Duration format** chooses conventional time or decimal hours for this browser
session. Decimal hours use two places, rounded from each exact aggregate rather
than adding rounded daily cells. Display options do not change recorded times.
Daily and weekly summaries show whole seconds; stored times and calculations
retain nanoseconds. Linked task titles include their external ID (the Jira browse
key or GitHub issue number when available) and open the recorded HTTP/HTTPS issue
URL in a separate tab. Native task UUIDs are not display IDs.
Activity fields show and accept local dates and times in the account’s configured
time zone, retaining up to nine fractional digits. Intervals across
midnight are stored as separate activities in the account's time zone, including
manual entries. Live tracking continues into the next day with the same active
identity and labels. Each day's activity is edited or deleted separately; a
rollover invalidates the earlier editor version. Day boundaries preserve exact
time through daylight-saving changes.
Start cannot be in the future, for either an ongoing or completed interval.
The server rejects invalid creates, edits and imports without changing records.
First click
selects an activity; another click or Enter while selected opens its editor.
Long polling loads consistent owner-scoped snapshots without replacing drafts.
Label choices in open activity and tracking dialogs receive additions and
renames from other clients while retaining their checked IDs and draft fields.
A selected label deleted by another client stays visible as deleted until the
user reviews it; saving cannot silently drop that reference.
Offline reads reconnect with backoff and never replay writes. Sessions bind to
the authenticated owner and reject live token owner reassignment.

## Work sessions

The persistent **Work Session** card shows the account's running status and
lets the user start or stop a session independently of activity timers. History
selects the session whose start/end, duration, tracked, covered and unallocated
time are displayed. Counters use the report's server timestamp and elapsed
browser time; when offline they freeze at the last confirmed report.

The card provides retrospective session creation/editing, associated activity
editing, taskless activity creation, existing-activity association and removal
of associations. A session can contain activities from several projects or no
project/task at all. Generic activity creation remains standalone; the card's
**Add activity** defaults explicitly to its selected session. A task timer can
choose the current session or remain standalone.

Tracked time sums clipped intervals; covered time is their union. Unallocated
time subtracts covered time from session duration, so overlap is not counted
twice. Out-of-bounds activities are accepted with a warning. Sessions can span
midnight without stopping, and activity day parts retain their association.
Deleting a session requires removing its activity associations first. Local editor fields retain nanoseconds. Repeated clock times include an offset;
nonexistent or ambiguous local input is rejected instead of silently moved.
An open editor keeps its original time zone if account settings change. Stale versions preserve the user's draft;
lost start/stop replies offer retry with the same frozen command key and payload.

Portable domain-v2 JSON/CSV export includes work sessions and association IDs.
Import remaps the complete graph into the destination account; v1 exports
remain readable. Native Eclipse CSV and portable server CSV are distinct formats.

## Automated verification

```sh
npm ci --ignore-scripts --prefix server/web
npm test --prefix server/web
cd server/web && npx playwright install --with-deps chromium && cd ../..
./mvnw -B -ntp -f server/pom.xml clean verify
python3 server/scripts/package_server.py --output server/target/distribution --commit "$(git rev-parse HEAD)"
python3 server/web/tests/acceptance.py --distribution server/target/distribution/timekeeper-server.zip
```

Packaging requires a clean committed tree. Browser acceptance launches the
checksum-verified distribution with temporary file storage and synthetic tokens,
then stops the JVM and removes private fixtures. Reports, screenshots and source
provenance go under `server/web/test-results`. Traces/videos are off because they could
retain authorization headers. CI's Web job uses the same Server artifact as
Remote-Eclipse.

Browser tests cover core tracking, authorization/validation, peer synchronization,
stale drafts, lost committed replies, exact nanoseconds, autumn DST, mobile
layout, expandable project/task hierarchy, external issue links and remembered
credentials. The `remote-client-tests` Eclipse profile
also launches a real browser peer against its installed runtime's server;
[WEB-ECLIPSE.md](../verification/WEB-ECLIPSE.md) records that bidirectional verification.
Final release installation remains a separate gate.

## Calendar

The **Calendar** tab beside Workweek shows all seven days. Both tabs share the
chosen week and the account's time zone. Use the arrows, **Today**, or the date
field to navigate. On smaller screens, scroll inside the calendar to see the
remaining days and hours.

Strips represent work sessions, using the associated client’s color and the
session title. Select a strip to see its full interval;
double-click it or use **Edit selected work session** to edit. Drag the strip
to move it between times or days, and drag its top or bottom handle to resize.
With a strip focused, Up/Down moves it by 15 minutes and Left/Right by one local
day. On a resize handle, arrow keys adjust that endpoint. Moving preserves exact
elapsed duration and fractional seconds. Sessions spanning midnight retain one
identity while appearing in each affected day.

Use **Copy work session** and **Paste work session**, or Ctrl/Cmd+C and Ctrl/Cmd+V,
to create a separate session. Select a day heading to retain the copied start
clock time, or select a calendar slot to choose a new time. The paste editor
lets you review the interval before saving. The client, title, interval and summary are
copied; activities and task references are not. The copy buffer is private to
the signed-in browser session and is cleared on sign-out. A running source keeps
its blank end in the copy; finish the copy's interval if another session is
still running. Starts in the future remain invalid.

Task cards use the task's title and issue link and list its activities. Separate
gaps remain visible. Taskless activity cards and overlapping intervals are also
shown. Tasks without recorded time this week appear in the expandable list below
the calendar. Activity times and associations remain unchanged when a work
session is moved, resized or copied. Out-of-bounds activity warnings remain in
the session details.

Repeated clock times have separate rows; shaded slots do not occur on a given
day. A drag retains the original session snapshot and saves with its opaque
version precondition. Peer edits, rejected changes and unknown save outcomes
preserve the proposed interval for review instead of silently overwriting data
or repeating an ordinary write.

## Clients, projects and work sessions

Use **Clients** to create, rename, recolor or delete a client. A client has a
required name and hex color. Each project belongs to exactly one client; choose
it in the project editor. A client may have many projects. Each work session also
belongs to exactly one client and has a required title plus an optional summary.
Choose these when starting or recording a session. The calendar uses the title
and client color; light colors receive dark text and dark colors receive light
text. Changing a client’s color updates all its sessions without editing them.

Create a client before creating your first project or work session. Clients in
use by projects or sessions cannot be deleted; reassign or remove those records
first. A project’s client and a session’s client can be changed independently.
Existing activities and their times are retained when these associations change.

On server upgrade, existing projects and sessions are assigned to **General**.
Existing session summaries remain descriptions and also supply the initial title
(or **Work session** when empty). General can be renamed and recolored like any
other client. Import/export v3 preserves clients, project ownership, session
titles and colors. Older v1/v2 imports receive General automatically.

Client management and session titles are server/web features. Eclipse project
creation receives General on the server, and Eclipse project edits preserve its
client. No client selection or title fields are added to Eclipse.
