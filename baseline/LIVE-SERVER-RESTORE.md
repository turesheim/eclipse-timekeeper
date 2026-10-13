# Live clients across an actual server restore

This installed acceptance uses a real cold H2 backup/restore of the packaged
server, not a socket fixture modeling a history reset. The test runs in the
retained account-B runtime with actual Workweek/Mylyn and a real Chromium tab.
It does not access the application's closed embedded database.

A baseline task is saved, the server is stopped and its H2 file copied to a
private backup, then the server restarts. The task changes to version 1 and ten
temporary labels advance the change cursor. Both clients open drafts from that
later history. A second actual shutdown leaves both drafts/URLs intact; both
clients see the disconnect and original local storage remains closed/unchanged.

The stopped server file is replaced by the checksum-verified backup. The
administrative observer clears its known-stale ETags because it performed the
restore; the two live clients receive no manual refresh, injected reset or
modified draft. A different task update reuses the old editor's numeric version
1, while the owner's restored cursor is lower than its previous head. Both
clients must automatically reload the current history, retain their own draft,
reject Save and leave the restored record/version unchanged. Cancel/reopen loads
current values and one reviewed native Save advances the version exactly once.
Temporary records clear and later server/local/return/restart phases still pass.

## Verified local candidate — 2026-10-13

Source: `8c5811e1f5ab46ee422d7d7612a831cc4c0b94f6`.
Server ZIP SHA-256: `96d6970c4dd64386f146b5230b3a000aaf73ae33eab84256c0aa6b84d2f10c47`.
Temurin 21.0.12.1 / macOS aarch64, Node 26.9.0, Chromium via Playwright 1.64.0.

- 61 server tests passed, no failures/errors/skips.
- Selected installed reactor: 108 main plus seven secondary tests passed, no
  failures/errors/skips. All nine installed reports and five restore markers
  passed alongside the earlier browser/Eclipse, native label/report, owner and
  backend-switch evidence. Shared reactor counts overlap and are not summed.
- Backup and pre-start restored-file SHA-256 match byte for byte.
- Both browser driver PIDs are retained, together with the two added server
  recovery PIDs. All 23 recorded process IDs exited.
- Private backup/database/token/keyring files are excluded from CI artifacts.

The first run restored the real file and preserved both offline drafts, but its
administrative observer retained the higher-version ETag from the old history
and could not issue the intended post-restore setup update. The observer now
explicitly resynchronizes; both live UIs still detect the reset themselves. The
full nine-phase run then passed without removing assertions.

This verifies client behavior against a real H2 rollback. PostgreSQL deployment
backup/restore and failover have separate container acceptance; this checkpoint
does not claim a live PostgreSQL restore was tested with both UIs. GTK file/folder
pickers were not exercised on macOS and must pass their dedicated Linux CI.
Remaining native operation acceptance, official Mylyn Error Log #243, human
review/merge, final tagged artifacts and public GitHub p2 installation remain
release gates. No release is published by this checkpoint.


## Verified Linux CI — 2026-10-13

[Build run 38332322613](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38332322613)
passed all eight jobs at source `63aadd9b57a6dee826361c49b896195df656c51f`.
The downloaded installed acceptance artifact contains nine successful reports
with zero failures/errors/skips. Its server ZIP SHA-256 is
`9903b6149e1cb6624759dd36fe9de0e34783130f8be5084b53eeade5d395cffe`.
The receipt records the actual Linux GTK folder-dialog scope as passed, all five
live-restore phases as passed, and all four ordinary-create timeout phases and
their four cancel/review phases as passed. This supersedes the pending Linux CI
qualification above; the local macOS evidence remains limited to that platform.

The final release workflow/tag execution, remaining native operation acceptance,
official Mylyn Error Log #243, human review/merge and public GitHub p2 installation
remain open. Successful branch CI does not establish final release acceptance.
