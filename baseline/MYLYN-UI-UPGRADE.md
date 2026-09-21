# Step 3: Mylyn integration and workweek UI

Started from merged PR #185, `origin/main` revision `d2724f0`, on September 21,
2026. The preceding PR's Linux/Xvfb CI checks passed before merge. Pre-existing
local classpath edits and Eclipse metadata were preserved and excluded from this work.

## Compatibility decisions

- Both production bundles require Mylyn Tasks core/UI `[4.12.0,5.0.0)`.
  Verification uses the exact 4.12 bundles pinned by the Eclipse 2026-09 target.
  The upper bound is a compatibility boundary, not a claim that every future
  Mylyn 4.x release has been tested.
- Bugzilla is not mandatory. No production code imports its API: existing
  Bugzilla grouping uses generic task data and the connector-kind string.
  The unused UI bundle requirement and target feature root were removed.
  Users can install a Bugzilla connector separately; no live server was tested.
- Public `TasksUi.getTaskActivityManager()` replaces internal access in the
  content provider and workweek view. Task icons delegate to the public
  `TaskElementLabelProvider` rather than a copied implementation importing
  internal category and connector classes. `IRepositoryQuery` replaces the
  concrete internal query type. The model's `Project` class no longer imports
  an internal type solely for documentation.
- The persisted `local-<UUID>` repository identity format is unchanged and now
  defined by Timekeeper rather than an internal Mylyn constant.

## Remaining internal API audit

Reviewed the source bundles actually selected by the target:
`org.eclipse.mylyn.tasks.core.source/4.12.0.v20260826-1729` and
`org.eclipse.mylyn.tasks.ui.source/4.12.0.v20260721-0835`.

| Boundary | Retained internal dependency | Reason |
| --- | --- | --- |
| `TimekeeperPlugin` project grouping | `AbstractTask`, `AbstractTaskContainer` | Public `ITask`/`ITaskContainer` expose no parent-container enumeration. The entry point now accepts `ITask` and checks the concrete type. |
| `Task` project association | `AbstractTask`, `AbstractTaskCategory`, `AbstractTaskContainer` | Category membership and parent enumeration still require internal types. Query detection now uses public `IRepositoryQuery`. |
| `WorkWeekView` task-list changes | `TasksUiPlugin.getTaskList()`, `ITaskListChangeListener`, `TaskContainerDelta` | Public `TasksUi`/`IRepositoryModel` do not expose a task-list change subscription. Activation subscriptions use the public API. |
| Synthetic tests only | `LocalTask`, `TaskCategory`, `TaskList`, associated abstract types | Construct and delete local fixtures without contacting a task server. |

These are explicit compatibility boundaries to recheck on future Mylyn upgrades,
not evidence that internal APIs are supported contracts. No internal Mylyn imports
remain in the title/time label providers, week content provider or `Project`.

## Runtime fixes

- Workweek time columns now handle the current `Task` and `Project` model types.
  The old `ITask`/`String` checks left their daily totals blank.
- Periodic updates refresh tracked task/project rows rather than Mylyn objects
  that are not elements of the workweek tree. The content provider reports the
  matching task/activity parent objects.
- Missing/deleted Mylyn tasks unlink safely without erasing persisted IDs,
  summaries, projects or activities. Historical rows retain their task ID and
  generic icon. Font decoration and context menus tolerate an absent Mylyn link;
  activation and opening a Mylyn editor are unavailable for such rows.
- Repeated deactivation is harmless when no activity is open. Successful
  deactivation notifies the view, and database-change refreshes are scheduled
  onto the UI thread with disposed-control checks.

## Verification

Clean source-copy build on macOS 27.0/aarch64, Temurin 21.0.12.1, Maven 3.9.16:

```sh
mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

The local command used an empty temporary Maven settings file to exclude unrelated
private repositories. No tests were disabled or skipped through build flags.
The normal dependency cache was retained; IDE output and local classpath changes
were not copied into the test checkout.

- Database/report tests: 4 passed.
- UI tests: 4 passed, 1 pre-existing ignored CSV-export test.
- All six reactor projects passed and the p2 repository/ZIP were generated.
- New lifecycle coverage activates a synthetic local task through Mylyn, checks
  its category and open activity, deactivates it, checks the end time and repeated
  deactivation, and verifies project/task/activity totals in the workweek tree.
- New historical-record coverage deletes a synthetic Mylyn task and verifies
  that its recorded activity, summary, category, daily total and context menu
  remain usable. UI-thread assertions are propagated to JUnit explicitly.

Local evidence: `/private/tmp/timekeeper-mylyn-3.log` and reports under
`/private/tmp/timekeeper-mylyn.WUttZ3/`. Temporary evidence may be removed by the OS.

## Limits and follow-up

This is not a release-readiness or data-migration sign-off. Existing databases,
labels, import/export and restart remain step 4/5 work. The old manual-edit UI
test remains inactive. Remote Bugzilla/JIRA/GitHub repositories and category
reassignment have not been exercised against live connectors.

The test runtime still reports missing theme/Cocoa menu contributions and Mylyn's
`CommonColors` shutdown `Invalid thread access` error, as recorded before this
step. No new Timekeeper event-loop exception appeared in the successful run.
Clean installed-IDE verification remains necessary; these harness errors are not
hidden by the passing test count. The site POM change received from origin also
produces an `additionalFileSets` unknown-parameter warning; it was preserved for
the release-packaging review rather than silently reverted.
