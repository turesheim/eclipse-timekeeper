# Changes

## 2.0.0 (unreleased)

Timekeeper 2.0.0 updates the plug-in for Eclipse 2026-09 (4.41), Mylyn Tasks
4.12 and Java 21. It establishes H2 2.4.240 schema version 3 as the supported
database baseline.

### Highlights

- Consolidated date navigation in Today’s mini calendar, with a Today button
  and access to daily and weekly journals on desktop and phones (#753).

- Highlighted public holidays and labeled days in the Today date navigator,
  with a split background when both apply, and marked weekly rest days in
  Today, Track and Calendar (#733).

- Matched emoji completion proposals to the Markdown editor body font (#746).

- Added correlated revision and request diagnostics for workspace sync failures.

- Added optional centralized logging on Synology using Grafana, Loki and Alloy.
  The standalone project collects structured Dagsverk logs and can ingest logs
  from selected containers and syslog sources (#726).

- Bundled an optional draw.io editor for Compose and Synology. Edit and save
  multi-page diagrams from the vault, with editable PNGs that remain portable
  through WebDAV and account archives (#708).

- Avoided the H2 2.5.250/2.5.252 crash-recovery data-loss regression by using
  the unaffected published H2 2.4.240 runtime in the server and Eclipse client.
  Added deterministic chunk-reuse and journal forced-restart regression checks.

- Unified image, diagram, vault asset and file insertion in the shared note
  editor, and opened embedded diagram editing directly from the pencil (#751).

- Made embedded draw.io diagrams easier to edit and reuse across notes, with
  a resizable editor, shared captions and an existing diagram picker (#745).

- Updated the build, target platform and runtime dependencies for Eclipse
  2026-09 and Java 21.
- Moved the main navigation to a collapsible left sidebar on desktop, with
  search, sync status and the account menu in a bottom bar (#713).
- Added ISO week numbers to the monthly Calendar, with direct access to saved
  weekly journal notes (#743).

- Showed events by day and tasks by deadline while editing a weekly note in
  Today, with the daily work-session list hidden (#738).
- Matched Today’s spacing below the active tracking card to Calendar (#744).
- Kept phone journal formatting applied to the selected text when opening Note tools.
- Added activity-based tracking with editable start and end times, improved
  refresh behavior and high-resolution display assets.
- Preserved tasks, projects, labels, activities and reporting in the new
  versioned database baseline.
- Made Timekeeper tasks provider-independent UUID aggregates with optional,
  uniquely constrained external task references.
- Scheduled new daily journal tasks on the note date with a same-day deadline.
  New weekly journal tasks are scheduled for Monday and due Sunday (#736).
- Added automatic synchronization with selected Google Tasks lists, with
  read-only access or opt-in two-way editing for supported fields (#621).
- Added explicit activity owner identities, UTC instant persistence and
  time-zone-aware daily and weekly reporting, including DST-safe boundaries.
- Added persistence-neutral Java domain and application-service modules with
  immutable commands and snapshots, transactional repository ports, stable
  validation failures and in-memory-tested standalone CRUD and time tracking.
- Added the embedded JPA service adapter and OSGi service registration, with
  atomic rollback coverage and UI service lookup, plus versioned startup,
  backup and storage coverage.
- Added bidirectional synchronization between native Timekeeper projects/tasks
  and the standard Mylyn Task List: categories map to projects, local tasks map
  to tasks, and nested local tasks map to subtasks without requiring a connector.
  Workweek remains focused on activity tracking and reporting.
- Routed Workweek display and activity creation, date/time editing, labels,
  start/stop and deletion through immutable application-service snapshots.
  Native project/task deletion is synchronized with Task List, and bundled
  reports support tasks without external identifiers or projects.
- Added native project and task creation, editing and deletion in Workweek,
  including project assignment, task URLs and subtasks. New native records
  do not create Mylyn objects unless Task List projection is enabled in preferences.
- Added guarded database initialization and future migration lifecycle
  infrastructure. Unsupported, unversioned and incomplete databases fail closed.
- Verified workspace, shared `AUTO_SERVER`, explicit file and local H2 TCP
  storage selected through Timekeeper preferences.
- Fixed installed-IDE packaging so Mylyn Tasks search contributions are present
  and only the host logging provider is installed. Bugzilla is no longer a
  mandatory dependency.
- Fixed interrupted-activity recovery, startup races, supplied activity end
  timestamps and database preference saving on current Eclipse releases.
- Updated embedded JNA to 5.19.1 and FreeMarker to 2.3.35.
- Added configurable FreeMarker report templates and current-schema CSV
  export/import. Automated checks cover manual activity editing, labels,
  reports, restart and native idle detection.
- Added Linux/X11, macOS and Windows CI coverage. Installed-IDE acceptance was
  completed on macOS Apple Silicon.
- Added the Quarkus API server, remote Eclipse backend and bundled browser
  client, with account-scoped records, authentication, optimistic concurrency
  and committed change notifications across connected clients.
- Added browser account setup, sign-in, personal API tokens and email recovery,
  plus server/web clients grouping projects and work sessions.
- Added note, tip, important, warning and caution callouts to the wiki editor’s
  formatting toolbar (#714).
- Preserved password-change confirmation when another browser request receives
  the session revocation first.
- Added Bootstrap 5.3.8 styling, Track month views and calendar session
  creation, dragging and resizing. Browser session editors use native date/time
  pickers in the account's time zone, with explicit choices for repeated DST times.
- Added Recent notes to the Tasks right sidebar, with the same document popup
  as Today and Notes (#715).
- Added independent work sessions in Eclipse and the web client: start/stop,
  retrospective editing, taskless activities and reports of exact tracked,
  covered and unallocated time derived from owner-scoped interval overlap.
  Sessions can span midnight; stopping a session leaves activity timers running.
  Manual activity/session associations are retired.
- Added portable domain-v3 JSON/CSV interchange with clients, independent work
  sessions, required project/session client links and session titles. Imports
  retain v1/v2 compatibility and ignore retired activity/session links.
  Native CSV includes session storage; known schema-2 databases receive guarded
  upgrades to schema 3 after a verified private backup.
- Added icons and translated type labels to wiki admonitions while preserving
  custom titles and Markdown source (#716).
- Show custom admonition titles in place of the default label and tint
  callouts by type in light and dark themes.

### Upgrade notes

Known, correctly stamped schema-2 databases are upgraded to schema 3 after a
verified backup. Historical databases and Mylyn attribute records are not migrated.
Preserve unsupported storage, then select a separate empty location for Timekeeper 2.0.0.
CSV export/import is a convenience interchange format, not a database backup or
rollback mechanism. See the [database policy](../operations/DATABASE-RECOVERY.md) and the
[installation and rollback instructions](../README.md#upgrade-and-rollback).

### Known limitations

- Mylyn 4.12 logs a theme color parsing error when a Task List tooltip is
  created on Eclipse 4.41. The maintainer deferred #243 beyond 2.0; the upstream
  parser error remains an accepted exception, and no experimental fix is bundled.
- Native idle detection requires X11/XScreenSaver on Linux and is unavailable
  in a pure Wayland session.
- Server-backed clients receive owner-scoped change notifications and reject
  stale writes. Embedded clients serialize database writes and validate record
  versions, but do not provide the server's cross-process change notifications.
- Externally managed remote H2 servers, authentication, encryption and network
  failure behavior have not received installed-runtime acceptance.

## 1.1.0

The previous public release. See the
[v1.1.0 tag](https://github.com/turesheim/eclipse-timekeeper/tree/v1.1.0)
for its source and release history.
