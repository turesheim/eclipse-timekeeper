# Native remote preferences and transfer acceptance

Issue #220; stacked on the real browser/Eclipse acceptance #256. This checkpoint
keeps the unverified GTK picker scope explicit and does not close release gates.

## Reproduced and fixed

A real server label with no color made the native label preference page throw
`DataFormatException: Null doesn't represent a valid RGB` in both its painter
and color selection. The UI now displays black for an unset color without
changing the stored value. Empty selection disables invalid editors, Add selects
its new row, and Remove selects a remaining row or clears selection. Apply
retains the selected row after its temporary UUID is replaced by a server UUID;
the next edit updates that same record instead of losing the draft.

The installed scenario uses the actual label preferences, Add/Apply/Apply and
Close/Remove controls, Workweek's label command and report template/export menu.
It checks an unset color stays unset, read-only failure keeps an enabled draft,
permission restoration saves once, peer edits reject stale saves, label assignment
preserves exact activity instants, and the clipboard report matches the exact
selected remote week and task names. The temporary report is created and removed
through preferences. Original local storage remains closed and unchanged.

## Verified local candidate — 2026-10-13

Source: `1bbd2a1eb58d456cc354a7e7a387b563942eebfc`.
Server ZIP SHA-256: `df9ccd69ca84bb6ad59e0a543ab3d7af6dcbe908ba8e71ea3bc788a877408e53`.
Temurin 21.0.12.1, macOS aarch64. Server verification passed 61 tests. The selected
installed reactor passed 108 main plus seven secondary tests, no failures/errors/
skips, across all nine installed phases. Native label-preference/report-control
markers and the five prior browser/Eclipse markers passed. Shared reactor counts
overlap and are not summed as unique tests.

**Native OS file/folder choosers were not run on macOS.** The receipt explicitly
records `phase.native.folder-dialogs=not-run-on-this-platform`. The merged parent
browser refresh adjustment and additional GTK relationship/cache assertions are
newer than this local candidate and must pass the current CI.

## GTK picker verification pending

The Remote-Eclipse Linux job installs xdotool and enables
`-Dtimekeeper.native.dialog-tests=true`. The driver searches the actual dialog
title in that job's private Xvfb display, focuses that window and types a path.
It does not replace SWT dialogs, inject a chosen result or bypass preferences.
Missing/failed dialogs fail acceptance rather than being counted as passed.

The required flow covers native report-file Save, canceled CSV export, confirmed
CSV export, canceled import confirmation, and confirmed atomic import. Imported
records must have fresh IDs, preserve task/project/label relationships and exact
instants, keep original records unchanged, and disappear from the native cache
after cleanup. The export folder contains the actual domain CSV manifest.

Only a successful inspected CI receipt with `phase.native.folder-dialogs=passed`
can clear this scope. Remaining editor/inline and uncertain-command acceptance,
live-client backup restoration, official Mylyn Error Log #243, human review/merge,
final tagged artifacts and public GitHub p2 installation remain release gates.
