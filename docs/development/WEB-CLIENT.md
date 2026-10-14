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
Maven includes `server/web/src` in the server JAR. An administrator issues access tokens
as described in [server/README.md](../operations/SERVER.md). Use HTTPS outside loopback.
The browser remembers the token in same-origin local storage and signs in
automatically after reload. **Sign out** removes the remembered token. Records
and editor drafts stay in memory. If browser storage is blocked, sign-in still
works and the client explains that it cannot remember the token.

Font Awesome Free 7.3.1 supplies locally bundled icon fonts for larger tree
controls and action buttons. Buttons retain accessible names and hover titles;
fonts and their upstream license are included in the server distribution.

Projects, tasks, labels, activities and work sessions use API v1. Start/stop uses durable
idempotency keys. Editors/deletion retain the original opaque ETag, including
across full resynchronization, rather than upgrading a stale draft. Validation,
permission, network and concurrency failures preserve drafts. An uncertain
ordinary write requires cancel, refresh and review; uncertain start/stop offers
explicit retry of the same frozen payload/key. Acknowledged saves remain
successful when a subsequent refresh fails.

Reports use the account's IANA time zone and the server's exact daily/weekly
totals. The default Workweek groups projects, tasks, subtasks and activities,
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
UTC activity fields retain up to nine fractional digits. Intervals across
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
Deleting a session requires removing its activity associations first. Exact UTC
editor fields retain nanoseconds. Stale versions preserve the user's draft;
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
