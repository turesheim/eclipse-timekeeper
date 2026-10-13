# Real web / installed Eclipse acceptance

Issue #221/#219; stacked on #255. The test starts Chromium from the installed
Mylyn/account-B acceptance JVM and uses the same checksum-verified server as
the actual p2-installed Workweek. Neither client is substituted by a fake UI.

## Verified local candidate — 2026-10-13

Source: `ebb17c8f340ba3d58e56111353c8a152d257d184`.
ZIP SHA-256: `86eaa2c2d7a8f45ded36e8e4760e63e9a1fde29283ff14caf5e848dbe15330aa`.
Temurin 21.0.12.1 / macOS aarch64, Node 26.9.0, Playwright 1.64.0,
Chromium 156.0.8078.4. CI uses Node 24.

- Server reactor: 61 tests passed without failures/errors/skips.
- Selected installed reactor: 108 main tests plus seven secondary tests,
  all without failures/errors/skips. All nine installed reports pass. These
  counts overlap with other reactors and are not summed as unique tests.
- A real browser creates a project, task, label and one-nanosecond interval
  through form controls. The same UUIDs/labels/exact instants reach Workweek
  automatically, and its cached task report retains the one nanosecond.
- The browser changes the owner's time zone to Europe/Oslo; the installed
  calendar follows it automatically, then returns to the original zone.
- A browser edit preserves an open native draft and makes Save reject it.
  Cancel/reopen permits an explicitly reviewed native edit. A still-open browser
  draft then survives that native edit and rejects its stale Save. Neither
  draft is silently upgraded by refresh, and server versions advance once.
- Workweek activation appears in the browser and browser Stop ends that same
  UUID. Browser Start appears in the actual installed active snapshot with
  its assigned label; Workweek Deactivate ends that interval. No duplicate
  intervals are recorded; both client directions observe the stop.
- The browser's rendered daily and weekly totals match exact backend queries
  and the installed report cache for identical owner/zone/range. This checks
  native cached reports; it does not claim native totals render nanoseconds.
- Browser deletion removes intervals, task, project, label and Mylyn projection;
  later server/local/return/restart phases remain green. Owner A's recorded
  state stays unchanged. Original local storage stays closed and unchanged in
  remote phases.
- Five `phase.web.eclipse.*` markers pass alongside previous owner/switch
  markers. All 20 recorded browser/server/proxy PIDs have exited.

The first run passed records, drafts, tracking and reports but failed cleanup:
a Delete control was repeatedly detached while the activity table refreshed.
The helper now waits for the acknowledged row-count reduction; it also closes
the stdin protocol explicitly so normal exit does not require termination.
The actual installed snapshot check before native Deactivate was strengthened.
The entire nine-phase run then passed; no assertion was removed.

The peer uses synthetic credentials in its environment. The protocol sends
only synthetic names/times/actions; credentials are not put in command lines
or retained artifacts. Browser diagnostics and optional failure screenshots
exclude traces/videos and private registry/keyring files. CI installs the
locked dependencies before the installed runtime; missing dependencies fail
the test rather than skipping the browser requirement.

## Remaining release gates

CI for this follow-up must pass and its artifacts must be inspected. Parent
#255 passed all eight Build jobs and Server containers verification; downloaded
evidence proves eight browser scenarios and its nine preceding installed
phases. This new combined scenario is additional evidence.

Remaining native label/report/CSV preference and failure/uncertain-operation
acceptance, actual server-backup restoration with live clients, official Mylyn
Error Log #243, human review/merge, final tagged distributions and public GitHub
p2 installation remain required before Timekeeper 2.0 publication.
