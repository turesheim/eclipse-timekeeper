# Native project and activity UI through the service boundary

Issue #213 continues the native task hierarchy design merged in #225. Projects
and tasks are managed in the standard Mylyn Task List: categories represent
projects and local tasks represent native tasks and subtasks. These are
Timekeeper-owned records, independent of repository connectors. Workweek is the
activity and reporting surface.

## Delivered behavior

- Creating and renaming native categories/tasks uses the application service.
  Deleting an empty native task or project now deletes its service record.
  The service refuses deletion while activities, subtasks or tasks remain;
  the UI explains the refusal and restores the projection without discarding
  recorded history. Cleanup is limited to previously observed native
  projections, so startup does not delete unrelated or old-workspace tasks.
- Workweek reads immutable project, task, activity and label snapshots from
  `TimekeeperService`. Native tasks are available before their first activity;
  task ancestors remain visible when a subtask has recorded time.
- **New activity** records an interval on a selected task, including tasks
  without a Mylyn projection. The dialog accepts explicit start/end dates,
  summary and labels in the Eclipse host time zone. An empty end records an
  ongoing activity; its start cannot be in the future.
- **Edit activity...** changes dates, times, summary and labels. Inline summary
  and time editing also use the service. Invalid intervals and stale versions
  leave persisted values unchanged and explain how to continue.
- **Start activity**, **Stop activity** and **Delete activity** use service-owned
  transactions. Only completed activities offer the delete action. Starting
  while the local owner already has an open activity reports a conflict.
- Active status and bold rows use service activity state. Mylyn activation
  reuses the same current activity and observes its persisted pointer after
  service changes; switching tasks closes a service-started interval first.
- Refreshes preserve row identity and wait for cell editing to finish, avoiding
  disposal of tree items while an editor or selection is using them.
- HTML exports obtain their data through the service and build detached
  template beans. Native tasks without external IDs and tasks without projects
  render correctly. Existing user-defined templates should treat `taskId` as
  optional; saved template preferences are not overwritten.

## Verification

Verified October 11, 2026 on Temurin Java 21 / macOS aarch64 with a clean build:

```sh
mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

The database/report suites cover an edited standalone interval with labels,
close/reopen equality, both bundled HTML templates and unassigned tasks. SWTBot
exercises native project/task creation, rename and deletion in Task List, plus
activity creation, validation, stale-save refusal, editing, CSV export, start,
stop and deletion with the Mylyn synchronizer stopped and no projected task.
Summary-only edits preserve sub-minute timestamp precision. A separate integration case verifies that Mylyn reuses a service-started
activity and preserves edited times and summary on deactivation.

All 80 database/report tests and 16 Eclipse UI tests passed. The build also
assembled the p2 update site and installation archive.

The existing database safety, hierarchy, reporting, preference, idle and UI
regressions remain active. Temporary synthetic storage is used throughout.

## Remaining architectural scope

The project/task editing surface follows #225. The older acceptance wording in
#213 about creating projects/tasks without a Mylyn UI object needs to be
reconciled with that chosen design before closing the issue. #214 retains the
optional Mylyn adapter and full activation/idle-path extraction. #220 retains
remote client execution, cancellation and network error behavior. This change
uses the embedded client service; cross-platform CI provides separate evidence
from the local macOS run. The dependency Error Log release blocker in
`ERROR-LOG.md` remains separate.
