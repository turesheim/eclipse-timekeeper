# Bundled web client acceptance

Issue #221, stacked on the remote-client/synchronization/owner-binding drafts.
This checkpoint does not close #221 or certify release acceptance.

The server packages native browser modules at `/web/`. The UI uses only the
public owner-scoped API: token sign-in, project/task/label/activity CRUD,
start/stop, exact daily/weekly reports and account time-zone editing. No browser
database, CDN or production Node process is used. Credentials remain in tab
memory; reload/sign-out end the session. HTTPS is required outside loopback.
Web responses carry a same-origin content security policy, no-referrer and
nosniff headers. Server ZIP documentation links resolve to the source commit.

Long polling refreshes consistent snapshots across all pages, profile, reports
and active tracking, catches missed changes after reconnect and preserves open
drafts. Editors keep the ETag captured at open; numeric versions or notification
refreshes cannot upgrade it. Start/stop explicitly retry a frozen payload with
its original durable key after an uncertain result. Ordinary uncertain writes
require cancel/refresh/review. Neither reconnect nor the proxy replays writes.
Live token reassignment rejects the bound owner before mutation. Activity first
click selects; another click/Enter while selected edits. Exact UTC input strings
and BigInt duration totals preserve nanoseconds.

## Verified local candidate — 2026-10-13

Source: `98fa08156a5d3584a0056b81141da59327959e7d`.
ZIP SHA-256: `fcf02b58a1f85e352ba20788b7ba546001f5f9e37e96551d9cef8ba29613a781`.
Temurin 21.0.12.1, macOS aarch64, Node 26.9.0, Playwright 1.64.0,
Chromium 156.0.8078.4. CI uses Node 24 and its own packaged candidate.

- Server reactor: 61 tests, no failures/errors/skips.
- Node: nine tests, no failures/skips, covering owner binding, safe errors,
  opaque ETags, cursor progress, Oslo 23/25-hour days, skipped civil dates,
  week boundaries, exact nanoseconds and precise daily clipping.
- Browser: eight scenarios, no failures/skips, against the checksum-verified
  distribution and a fresh real JVM/file database. Core CRUD/tracking,
  actual 25-hour daily/weekly report and one-nanosecond edit, selection/Enter,
  dependency rejection, two-browser notifications/stale drafts,
  read-only/validation draft preservation, confirmed committed start/stop
  replies lost then replayed once, uncertain create requiring review,
  live owner reassignment, memory-only credentials and offline missed-change
  recovery with a preserved stale draft.
- Desktop and 390px mobile screenshots visually inspected. The activity table
  scrolls within its card on small screens; the page does not overflow.
- All child processes exit and synthetic registry/database files are deleted.
  Retained browser XML, screenshots and candidate provenance contain no tokens;
  traces/videos are disabled. Server logs live outside Playwright's cleared
  output directory so they remain available for diagnostics.

The first browser run exposed a native `fetch` receiver error, then exact-label
selectors exposed missing stable control labels. Both were fixed and the full
eight-scenario suite rerun successfully; assertions were not removed.

## Remaining gates

CI for the web draft must pass and its artifact must be inspected. Actual
web-to-installed-Eclipse bidirectional changes/tracking/reports remain required
for #221/#219: two browser sessions and HTTP adapter tests are narrower evidence.
Actual server backup restoration, remaining remote native UI acceptance,
official Mylyn Error Log gate #243, review/merge, final tagged deliverables and
public GitHub p2 installation remain required before Timekeeper 2.0 publication.
