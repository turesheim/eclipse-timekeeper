# Concurrent installed-instance acceptance

Verified September 21, 2026 on macOS aarch64 with Eclipse Platform 4.41,
Java 21 and installed Timekeeper feature build `2.0.0.202609211112`.
All workspaces, homes and records were synthetic and isolated below
`/private/tmp/timekeeper-installed.RaDTib`.

## Setup

Two copies of the same installed Eclipse application used separate workspaces
and one temporary `user.home`. The default shared-storage preference therefore
resolved in both processes to:

```text
jdbc:h2:~/.timekeeper/h2db;AUTO_SERVER=TRUE;AUTO_SERVER_PORT=9090
```

The starting database was a closed copy of the earlier installed-IDE fixture:
schema version 1 in `READY`/`NEW` state, one task, one completed activity and
seven default labels. Workweek was already open in both workspaces so both
Timekeeper plug-ins connected during startup. Console output confirmed the same
JDBC URL in both processes, and both views displayed the task and its `0:01`
Monday total while the first process owned the AUTO_SERVER port.

## Concurrent write and stale-cache result

The second client created a new activity while the first remained connected.
On normal shutdown, the second workspace's save participant committed the new
activity and task's `CURRENTACTIVITY_ID`. A third H2 client concurrently read
two activities from the database. The first Eclipse client still displayed only
the original activity: database changes made by another process do not publish
a Timekeeper database-state event or invalidate EclipseLink/UI caches.

Closing the stale first client did not overwrite or remove the second client's
activity. A direct read after both clients closed still found both activities
and the new current-activity reference. Restarting either installed client
reloaded the database and displayed both activities.

This establishes safe persistence for the exercised sequential cross-process
edit, but not live synchronization. A user must restart before relying on
changes made by another running instance. Simultaneous conflicting edits to the
same record were deliberately not treated as supported behavior.

## AUTO_SERVER owner shutdown

The first client was then started as AUTO_SERVER owner and the second as its
remote client. After both displayed the two-activity state, the owner shut down
normally while the second client remained open. The second client ended the
current activity, created another and shut down normally. Its save transaction
succeeded after the original owner had exited.

The closed database contained three activities: the prior current activity had
an end timestamp, the new activity was current, and the schema marker remained
version 1 `READY`/`NEW`. This verifies the exercised graceful owner-handoff path.
Abrupt owner termination, network loss and retry during an already-running
transaction remain unverified.

## Active-activity ownership

The model stores a single current-activity identifier on the shared task, with
no Eclipse-instance owner or lease. Both clients therefore render the same
running activity after restart, and either client can replace it. This behavior
is observable and data remained consistent in the exercised sequence, but
multi-instance active tracking has no ownership semantics. Users should not
actively track or edit the same task from two instances at once.

## Fixed-port conflict

A separate local-only H2 server was bound to loopback port 9090 before starting
Timekeeper. Workweek stayed responsive and displayed:

```text
Timekeeper database unavailable: Exception opening port "9090"
(port may be in use), cause: "java.net.BindException: Address already in use"
```

The Eclipse Error Log contained the same H2 `90061` failure. SQL scripts taken
before and after the rejected startup compared byte-for-byte equal; schema,
three activities and the current-activity reference were unchanged. Shutdown
also logged the expected secondary message that no database connection was
available for persistence.

The fixed shared port is therefore a visible, non-destructive operational
constraint. Users must free port 9090 or select workspace/explicit URL storage;
Timekeeper does not negotiate an alternative port.

## Acceptance boundary

Verified:

- two installed UI clients can read the same shared database concurrently;
- one client can commit while the other stays connected without record loss;
- a stale client shutdown does not overwrite the committed addition;
- the exercised graceful AUTO_SERVER owner handoff preserves a later write; and
- a fixed-port conflict fails visibly without modifying the database.

Not certified:

- live cross-process UI/cache refresh;
- simultaneous conflicting edits or active-task ownership;
- abrupt process or network failure during a transaction; and
- multi-instance behavior on Windows, Linux or externally managed servers.
