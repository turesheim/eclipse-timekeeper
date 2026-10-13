# Unknown native deletion outcomes

Issue #220; stacked on [#261](https://github.com/turesheim/eclipse-timekeeper/pull/261).

The p2-installed base feature, without Mylyn, now exercises actual Workweek
Delete activity, Delete task and Delete project controls. Each covers the normal
HTTP timeout and cancellation of the actual running Eclipse Job after the real
server has acknowledged DELETE with 204. Task/project deletion uses the normal
confirmation dialog; no service result or UI selection is substituted.

The fixture withholds the confirmed response. The UI must show its normal
outcome/review instruction, the observer must still see the target absent, and
explicit refresh must remove it from the installed canonical snapshot. After
each activity deletion the parent task and project stay unchanged; after task
deletion its project stays unchanged. The complete original projects/tasks/
labels/profile/active state and precise activity records/totals remain unchanged
after each three-deletion sequence. The local database stays closed and its
original checksum is preserved.

## Verified local candidate — 2026-10-13

Source: `89649abcca1973d55d2f4595e0fe124baa182ac4`.
Server ZIP SHA-256:
`b18523f235ba52bd9f8f936cfb9f90b637ef14f01737e59dc1337dd9635a61a1`.
Temurin 21.0.12.1 / macOS aarch64.

- Server verification: 61 tests passed, no failures/errors/skips.
- Selected installed native reactor: 108 tests passed across 21 reports, no
  failures/errors/skips. Shared module counts overlap with server verification
  and are not summed as unique tests.
- All six `phase.deletion.{timeout,cancel}.{activities,tasks,projects}` receipts
  passed. A second real Eclipse/server process, retaining the workspace and
  encrypted token, requires all six receipts alongside the four tracking
  receipts and the original exact records.
- All four distinct recorded fixture process IDs exited.

This verifies confirmed deletion timeout/cancellation and native reconciliation;
it does not certify every authorization/offline deletion path, uncertain label
preference deletion, or lost-import replay after installed restart. The existing
Mylyn rejected-deletion/permission/offline-intent checks are separate evidence.
Current Linux/full installed CI, remaining #220 acceptance, official Mylyn
Error Log #243, human review/merge, the final release-tag build and public
GitHub p2 installation remain open.
