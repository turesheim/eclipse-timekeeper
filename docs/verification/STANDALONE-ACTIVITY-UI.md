# Native project and activity UI through the service boundary

Issue #213 provides native project, task and activity management in Workweek.
The standard Mylyn Task List integration from #225 remains available alongside
the native editors when the optional adapter is installed. Timekeeper-owned records need no Mylyn object or repository
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
  ongoing activity. The start cannot be in the future for ongoing or completed
  intervals; invalid creates, edits and imports leave stored records unchanged.
- **Edit activity...** changes dates, times, summary and labels. Inline summary
  and time editing also use the service. Invalid intervals and stale versions
  leave persisted values unchanged and explain how to continue.
- **Activate task** starts tracking directly for native and linked tasks without
  opening an activity editor. Switching native tasks closes the previous local
  owner's interval and clears a linked provider's activation state. Repeating
  native activation reuses an already open interval for that task.
  **Deactivate task** stops its current interval even when Workweek shows a
  different week. **New activity** and **Edit activity...** retain their dialogs.
  **Stop activity** and **Delete activity** use service-owned transactions; only
  completed activities offer the delete action.
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

The #213 build passed 80 database/report tests and 18 Eclipse UI tests.
The #214 adapter split adds a separate Eclipse runtime with no Mylyn bundles;
it runs the same native CRUD and unavailable-provider scenarios plus idle-return,
away-policy and optimistic-conflict tests. The adapter runtime also verifies
external-key reconnection and duplicate/late activation events. The clean #214
build passed 116 tests: 3 domain, 6 service, 81 database/report/packaging, 6
without-Mylyn UI and 20 adapter/UI tests. Plain Java tests are explicitly bound
to the normal Tycho build so CI runs them too. The build assembles separate base
and Mylyn features in one p2 update site.

The first Linux PR build failed before tests during dependency collection after
the runner image upgraded Maven to 3.10.0. Reproducing locally with 3.10.0 and
`-e` identified the rejected Tycho 5.0.4 nested-JAR classifiers
`lib/h2-2.5.250.jar` and `lib/freemarker-2.3.35.jar`. The Apache Maven wrapper pins
3.9.16 for all CI jobs and local builds, with a distribution SHA-256 checksum.
The Maven version rule refuses 3.10 and later with instructions to use the
wrapper until that compatibility issue is resolved.

The existing database safety, hierarchy, reporting, preference, idle and UI
regressions remain active. Temporary synthetic storage is used throughout.

### Native activation acceptance (#234)

Verified October 12, 2026 on Java 21 / macOS aarch64 with
`clean verify -Pjira-connector-tests -Dtycho.localArtifacts=ignore`: 138 tests
passed, including installed JiraConnector acceptance and second-process restart.
The native activation scenario runs both without Mylyn and with the optional
adapter. It covers immediate clock-based start without a dialog, bold active
rows, editing an open interval, task switching, deactivation from a different
displayed week, reuse when activation occurs while a menu is open, and retaining
the manual activity dialog. The adapter scenario switches through the real
Mylyn activation manager in both directions and clears an activation flag whose
interval was stopped manually. Its added assertion reproduced the stale flag
before the correction. The original native scenario failed because its
**Activate task** menu entry was absent.

## Optional adapter boundary (#214)

`net.resheim.eclipse.timekeeper.mylyn` owns all production Mylyn imports,
activation listeners, provider metadata, Task List synchronization and linked
images/open actions. It uses `TimekeeperService` exclusively to read or mutate
Timekeeper data. The base UI discovers the optional `TaskIntegration` OSGi
service and works without it. The database no longer links JPA entities to Mylyn
objects or closes open activities based on provider activation state. Native
tracking remains authoritative when an external task is absent or inactive.

Creating a task with initial external references commits native identity and
provider links together. Reconnection resolves the stable provider/repository/task
key before a projection UUID, preserving recorded history. Late deactivation is
limited to the task that owns the service's current local activity. Deleted
external local tasks are not recreated; protected native projections still use
the service's deletion guards.

Task List synchronization polls client snapshots once per second, preserves
pending changes across temporary client failures, and cancels its poll on stop.
Idle corrections use immutable activity snapshots and expected versions;
concurrent edits leave the activity untouched and report a conflict. The detector
timer and display filters are removed on UI shutdown. The sampler captures the
workbench Display and tolerates disposal races without creating a new Display
on its background thread.

`net.resheim.eclipse.timekeeper.mylyn.feature` depends on the base Timekeeper
feature and the complete Mylyn Tasks feature. The base feature has no Mylyn
requirement. Native-only installations hide the Task List projection preference.

## Remaining architectural scope

#213 and #214's native editing and optional adapter boundaries are implemented.
#220 retains remote client execution, cancellation and network error presentation.
These changes use the embedded client implementation; they do not claim an
implemented HTTP transport. The existing upstream Mylyn Error Log release blocker
in `ERROR-LOG.md` remains separate.
