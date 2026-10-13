# Label preference snapshots after server restore

Issue #220; draft [#264](https://github.com/turesheim/eclipse-timekeeper/pull/264).

An actual installed label-preference draft overwrote a restored label whose
numeric version matched the original draft. The cold restore changed the
record's name and color at version 1; applying the old draft changed both fields
back and advanced it to version 2. The before-fix regression failed with
`Restored same-version label was overwritten without review`.

Label updates and deletions now compare the captured immutable DTO with a fresh
read on the serialized remote worker before the conditional write. The common
helper retains the existing task/project/activity dialog behavior, skips embedded
mode and uses the existing close/reopen instruction for changed records. The
preference page preserves its draft/deletion intent and acknowledged batch
progress. A missing record remains a visible not-found failure.

## Verified local candidate — 2026-10-13

Source: `b4fadece073105b163c9038ad013a9e55201d8fc`.
Server ZIP SHA-256:
`34648d3d9da43535b5987bc4fc26f93125dd9f95127268e1ad63a06669e2f522`.
Temurin 21.0.12.1 / macOS aarch64.

The scenario holds actual label preferences through real packaged-server shutdown
and cold-file restore. The administrative observer then writes different fields
with the same numeric version; the installed client detects the lower history
and refreshes automatically. Applying the old name/color draft must preserve
fields and reject the update. Removing its row and applying must retain the
deletion intent and reject deletion; a serialized read barrier observes that
completed operation before the assertions. Cancel/reopen then allows one reviewed
update and one reviewed deletion of that same UUID. Original local storage
remains closed and unchanged.

- The selected installed reactor passed 115 tests with no failures/errors/skips,
  across all nine native/Mylyn phases.
- All five `phase.label.restore.*` markers passed: cold-file, update-draft,
  delete-draft, reviewed-update and reviewed-delete.
- Separate task/browser and label backup/restored hashes match byte for byte.
- All 27 distinct recorded local fixture processes exited.
- Server verification passed 61 tests before the UI-only fix; server sources
  and dependencies are unchanged. Shared module counts are not summed.
- Publication tests (13), actionlint and diff whitespace checks passed separately.

An older concurrent-label regression initially expected the general HTTP conflict
message. It now requires the earlier captured-record close/reopen instruction;
its enabled-draft and unchanged-server-record assertions remain intact.

Current full Linux/GTK CI remains required. This evidence covers native label
preferences; it does not certify remaining inline/context-menu operation
snapshots. Official Mylyn Error Log #243, human review/merge, the final tagged
release build and public GitHub p2 installation remain open.
