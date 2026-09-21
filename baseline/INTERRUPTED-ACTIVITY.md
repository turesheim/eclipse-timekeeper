# Installed interrupted-activity acceptance

Verified September 21, 2026, on Eclipse Platform 4.41, Temurin Java 21 and
macOS Cocoa aarch64. All workspaces and databases were disposable synthetic
copies under `/private/tmp/timekeeper-installed.RaDTib`; no personal database
was opened.

## Scenarios and results

The test started with the installed feature build `2.0.0.202609211112`, a
linked local Mylyn task and one completed activity. The database used H2
`AUTO_SERVER` on an isolated port so a second connection could inspect committed
state while Eclipse was running.

1. Activating the task created activity
   `25E2B8B3-623A-4050-A2F7-8C17F5402107`, starting at
   `2026-09-21 14:28:38.793037`. A normal workspace save and shutdown made the
   open activity durable.
2. Restarting with the active Mylyn task retained that exact activity ID and
   start time. Killing only the isolated Eclipse process with `SIGKILL`, then
   reopening the database and restarting Eclipse, retained the same open
   activity again. Workweek continued its elapsed total instead of creating a
   duplicate activity.
3. A copy of the durable open database was then paired with the same workspace
   after the Mylyn task had been deactivated. The original startup cleanup
   exposed two defects: it selected the end of a 30-minute probe interval,
   which could be in the future, and it did not commit the change when default
   labels already existed.
4. Build `2.0.0.202609211243` fixes both defects. Startup now performs cleanup
   in an explicit transaction, prefers the persisted last-active `tick`, uses
   Mylyn elapsed time only as a fallback, and clamps the result between activity
   start and current time. The copied activity was durably closed at
   `2026-09-21 14:35:36.31337`; `CURRENTACTIVITY_ID` became `NULL`. Workweek
   displayed the completed six-minute activity, and a closed-database query
   returned the same values.

The run also exposed an independent UI startup race: an active Mylyn task may
exist before the idle detector has initialized `lastActiveTime`. Workweek's
status updater then threw a `NullPointerException`. `getIdleSince()` now returns
unknown (`null`) during that interval. The installed corrected build showed a
normal `Active since ...` status and started and stopped without that exception.

## Automated verification

`mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore` passed all six reactor
projects with 64 database/report/packaging tests and 10 UI/integration tests.
New coverage verifies that recovered end times use a valid tick, never precede
the activity start or exceed the current time, and that an active task with an
uninitialized idle sample does not fail status calculation.

One preceding full run hit the already documented macOS SWTBot editor-focus
race: `testEditTimeRange` could not find the Workweek tree within 20 seconds.
The unchanged test passed in the complete retry, as did every other test.

## Durability boundary

An exploratory attempt killed Eclipse immediately after a brand-new activation.
The row was visible through a concurrent `AUTO_SERVER` connection but was absent
after H2 recovered the killed owner process. Repeating the crash after a normal
workspace save retained the record exactly. The acceptance result therefore
covers already durable activity records and startup reconciliation; it does not
claim that a process or power failure inside the storage engine's immediate
post-activation durability window cannot lose the newest activation.

No filesystem or power-loss fault was injected, and the result does not extend
multi-instance support: concurrent clients still have no live cache invalidation
or active-activity ownership lease. See
[concurrent installed-instance acceptance](CONCURRENT-INSTANCES.md).
