# Changes

## 2.0.0 (unreleased)

Timekeeper 2.0.0 updates the plug-in for Eclipse 2026-09 (4.41), Mylyn Tasks
4.12 and Java 21. It establishes H2 2.5.250 schema version 1 as the supported
database baseline.

### Highlights

- Updated the build, target platform and runtime dependencies for Eclipse
  2026-09 and Java 21.
- Added activity-based tracking with editable start and end times, improved
  refresh behavior and high-resolution display assets.
- Preserved tasks, projects, labels, activities and reporting in the new
  versioned database baseline.
- Made Timekeeper tasks provider-independent UUID aggregates with optional,
  uniquely constrained external task references.
- Added explicit activity owner identities, UTC instant persistence and
  time-zone-aware daily and weekly reporting, including DST-safe boundaries.
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

### Upgrade notes

Historical databases and Mylyn attribute records are not migrated. Preserve any
existing database, then select a separate empty location for Timekeeper 2.0.0.
CSV export/import is a convenience interchange format, not a database backup or
rollback mechanism. See the [database policy](DATABASE-RECOVERY.md) and the
[installation and rollback instructions](README.md#upgrade-and-rollback).

### Known limitations

- Mylyn 4.12 logs a theme color parsing error when a Task List tooltip is
  created on Eclipse 4.41. This upstream dependency error remains a release
  blocker even though the Workweek view continues to function.
- Native idle detection requires X11/XScreenSaver on Linux and is unavailable
  in a pure Wayland session.
- Concurrent clients do not receive live cross-process cache invalidation, and
  an active activity has no client owner or lease. Restart reloads committed
  records; simultaneous edits to the same task are unsupported.
- Externally managed remote H2 servers, authentication, encryption and network
  failure behavior have not received installed-runtime acceptance.

## 1.1.0

The previous public release. See the
[v1.1.0 tag](https://github.com/turesheim/eclipse-timekeeper/tree/v1.1.0)
for its source and release history.
