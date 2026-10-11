# Native project and activity UI through the service boundary

Issue #213 provides native project, task and activity management in Workweek.
The standard Mylyn Task List integration from #225 remains available alongside
the native editors. Timekeeper-owned records need no Mylyn object or repository
connector.

## Delivered behavior

- **New project...** and **New task...** are available in the Workweek toolbar
  and context menu, including an empty installation. **Edit project...** renames
  a project. **Edit task...** edits its summary, optional URL, project and parent.
  **New subtask...** defaults to the selected parent and its project. A task may
  have no project; subtasks require the same project as their parent. The parent
  picker excludes the task itself and all its descendants.
- Native dialogs read immutable snapshots and save with their original versions
  through `TimekeeperService`. Invalid values and stale saves leave the dialog
  open and preserve stored data. External references remain intact when editing
  a task and are displayed even when the provider task is unavailable.
- **Delete project...** and **Delete task...** require confirmation and respect
  service guards for recorded activities and children. Empty records can be
  deleted; failed deletion preserves the record and explains the refusal.
- New native records do not create Mylyn tasks or categories by default. The
  user can enable Task List projection in preferences. Previously linked native
  records and Task List-created records continue to synchronize, and refused
  Task List deletion still restores the existing projection.
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
./mvnw -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

The database/report suites cover edited standalone projects, tasks and intervals
with labels, close/reopen equality, both bundled HTML templates and unassigned
tasks. SWTBot exercises project, task, subtask and completed activity creation
through Workweek with the production synchronizer running, while asserting that
no Mylyn category or task is created. It also checks rename, task URLs, project
and parent changes, unassigned tasks, validation, stale project/task saves,
guarded deletion and CSV export. Separate activity coverage checks editing,
stale saves, start/stop, labels and deletion without a Mylyn projection.
An unavailable external provider case verifies that task edits retain external
references, identity and recorded history. Optional Task List integration
retains native CRUD, hierarchy and project/parent move coverage, including a
new native ancestor for an already projected child when projection is off.
Summary-only edits preserve sub-minute timestamp precision. A separate case verifies that Mylyn reuses a service-started
activity and preserves edited times and summary on deactivation.

All 80 database/report tests and 18 Eclipse UI tests passed. The build also
assembled the p2 update site and installation archive.

The first Linux PR build failed before tests during dependency collection after
the runner image upgraded Maven to 3.10.0. Reproducing locally with 3.10.0 and
`-e` identified the rejected Tycho 5.0.4 nested-JAR classifiers
`lib/h2-2.5.250.jar` and `lib/freemarker-2.3.35.jar`. The Apache Maven wrapper pins
3.9.16 for all CI jobs and local builds, with a distribution SHA-256 checksum.
The Maven version rule refuses 3.10 and later with instructions to use the
wrapper until that compatibility issue is resolved.

The existing database safety, hierarchy, reporting, preference, idle and UI
regressions remain active. Temporary synthetic storage is used throughout.

## Remaining architectural scope

#213's native creation and editing workflow is implemented. #214 retains the
optional adapter bundle and full activation/idle-path extraction, including
removing mandatory Mylyn bundle dependencies from persistence and the base UI.
#220 retains
remote client execution, cancellation and network error behavior. This change
uses the embedded client service; cross-platform CI provides separate evidence
from the local macOS run. The dependency Error Log release blocker in
`ERROR-LOG.md` remains separate.
