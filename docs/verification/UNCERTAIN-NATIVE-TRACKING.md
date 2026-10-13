# Unknown native tracking outcomes

Issue #220; draft [#261](https://github.com/turesheim/eclipse-timekeeper/pull/261).

The p2-installed base feature, without Mylyn, now exercises the actual Workweek
Activate/Deactivate menu after successful server commits whose replies are held
back. Start and stop each cover normal HTTP timeout and cancellation of the
actual running Eclipse Job. The loopback proxy confirms the backend's real 201
or 200 before withholding the reply; no service response is substituted.

Each case requires the normal visible outcome/review instruction, one interval
only, unchanged committed UUID/start time/version, and explicit refresh to the
server's active or stopped state. Stop increments the original interval once.
The retained-workspace restart requires all four tracking receipts. All phases
keep the app entity manager null and the original local file checksum unchanged.

## Verified local candidate — 2026-10-13

Source: `a8b68f32086be52425bd09c4c7657fb2e26156f6`.
Server ZIP SHA-256:
`e4dcd736284ca9610473064b4dfb5bbe7b19b5e8eae9a6608e0db3bb07b4b872`.
Temurin 21.0.12.1 / macOS aarch64.

- Server verification: 61 tests passed.
- Complete selected installed reactor: 115 tests passed across 28 reports,
  without failures/errors/skips; all nine installed native/Mylyn phases passed.
- All four `phase.tracking.{timeout,cancel}.{start,stop}` receipts passed and
  survived restart. Existing create/review, restore, browser/Eclipse and backend
  switch receipts passed too.
- All 25 distinct process IDs recorded by the two fixture receipts exited.
- Publication tests (13), actionlint and diff whitespace checks passed separately.

The initial new test looked for the error shell before the 15-second request
timeout created it. It now explicitly waits for the actual dialog, retaining
the message, cancellation and interval assertions. CI also exposed a separate
fixture-cleanup race: backend preferences captured a task snapshot while
notifications were still removing the deleted uncertain-create task. Cleanup
now waits for canonical tasks/projects/labels/active state before that capture.

The current stack additionally includes a GTK driver change: Escape is sent to
the matched focused chooser through keyboard events, avoiding X_SendEvent to
its already destroyed window. Command success, chooser closure and canceled
transfer state remain required. Three isolated SWT 3.135.0 GTK probes returned
null with exit zero. This change is newer than the local candidate above;
macOS did not exercise GTK pickers and current Linux CI remains required.

This covers committed native tracking timeout/cancellation and state review.
It does not certify lost-import/deletion outcomes or replaying an unresolved
tracking command after restart. Those remaining #220 checks, official Mylyn
Error Log #243, human review/merge, the final release-tag workflow and public
GitHub p2 installation remain open.
