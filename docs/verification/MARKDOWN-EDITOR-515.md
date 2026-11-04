# Markdown editor completion audit (#515)

Updated 4 November 2026. Daily, weekly, event and general Notes share the same
editor. Implementation and verification are recorded separately. The issue was
closed by its author on 3 November; that does not certify the untested platforms
listed below.

The 4 November cleanup removes the prototype page and its About link, and deletes
the withdrawn enhanced-source toolbar, source search/line-number controls and
split preview. The earlier measurements below remain historical evidence for
those increments. Current component cases use the actual `MarkdownField` through
test-only fixture routes; code-block highlighting remains supported.

## Delivered

PR #516 delivered the source-preservation foundation; PR #517 delivered the
shared editor experience and revision history. PR #522 removed the date-dependent
Notes/Today image-test setup. PR #523 removed the unwanted Lists and tables panel
and floating formatting bar, and made checklists containing a bare inline
`<br />` editable without rewriting their unchanged source. #523 is merged and
all its selected GitHub checks passed in [run 41596127827](https://github.com/turesheim/eclipse-timekeeper/actions/runs/41596127827).
PR #524 fixed source/split-preview position and asynchronous-render handling;
its selected checks passed in [run 41602725620](https://github.com/turesheim/eclipse-timekeeper/actions/runs/41602725620).

The user explicitly rejected the extra button areas. Issue #515 now records that
choice: use the existing toolbar and shortcuts; do not reintroduce a floating
selection toolbar or a separate structured-editing button panel.

| Area | Implementation and automated evidence |
| --- | --- |
| Fidelity | Exact unchanged source, opaque frontmatter, mixed line endings, edited-block reuse, inert editable fragments, line breaks and mode-switch history: editor/fidelity browser suites |
| Vault compatibility | Ordinary/daily wikilinks, aliases, headings, completion, sized authenticated image embeds, callouts and footnotes: Notes/editor/wikilink suites |
| Paste and attachments | Safe rich/plain paste, URL over selection, screenshots and file drop, insertion bookmarks, upload progress/retry and existing assets: editor/Notes suites |
| Structured content | List/task behavior, keyboard-accessible table commands and spreadsheet paste, language/copy/highlight controls, Mermaid previews: editor suites; extra command panels were removed after user review |
| Navigation | Heading links, link previews, guarded navigation and in-memory cursor restoration: editor/Notes/wikilink suites |
| Saving and recovery | Canonical revision hooks for native and DAV writes, diff before draft restoration, retention limits, owner isolation and guarded stale writes: Java revision tests and Notes/editor browser suites |
| Privacy and access | Read-only schema/source/upload guards; drafts stay in memory and clear on session changes: shared editor and host suites |

Saving remains explicit. Autosave was considered and left off to retain guarded
manual saving during sync/conflict review. Persistent offline draft storage was
not introduced; it would require an explicit privacy/retention design.

## Verified evidence

- Full Java 21 verification at `5f7ab149`: 514 tests, zero failures/errors,
  12 opt-in skips. Later UI-only increments do not change server behavior.
- Web unit and API contract suites at the editor increment: 124 and 54 cases.
- #523: packaged server build, 124 web unit tests and 40 focused Chromium editor,
  fidelity and prototype browser cases passed locally; subsequent GitHub checks
  passed. Its checklist regression checks unchanged bytes, checkbox editing and
  absence of the removed controls.
- Packaged manual WebDAV client/representative-vault checks verified exact
  Markdown and binary bytes, daily/weekly/event/general note associations,
  unaffected markup after a rich edit, guarded conflicts and interrupted replies.
  The round-trip fixture now explicitly recovers a consistent workspace after
  external DAV writes before attempting an edit.
- Chromium 156.0.8078.4 on macOS arm64: the long-note fixture is 350 paragraphs / 53,089 bytes.
  At #523, dirty-check p95 was below 1 ms, cached reads below 1 ms, explicit
  serialization about 37 ms and input-to-next-frame p95 about 32 ms. These are
  named local regression measurements, not physical-mobile certification.
- Foundation HTTPS WebKit checks passed (28 cases at `7237bf5c`).
- Split/source continuation: packaged build, 124 web unit tests, 44 focused
  Chromium editor/fidelity cases and 7 WebKit-over-HTTPS cases pass locally.
  The browser cases cover plain/highlighted source, native wheel scrolling,
  caret/focus preservation, source-tool toggles, reordered asynchronous renders,
  clearing pending private content and Find after a delayed source-module load.
  Firefox remains unverified; its earlier local profile-startup failure was
  infrastructure evidence, not a passed test or an application regression.
- Final keyboard increment: Java 21 packaged build, 124 web unit tests and
  47 focused Chromium editor/fidelity cases passed. Five WebKit-over-HTTPS cases
  covered table operations, list continuation/indentation, header boundaries and
  controls across themes/narrow viewports. Five additional Chromium cases passed
  the packaged manual-client vault round-trip, journal/wikilink navigation and
  keyboard-table accessibility checks. Axe reported no violations in the table
  keyboard test. No backend behavior changed in this increment.
- Final Chromium long-note measurements: dirty-check p95 0.1 ms, explicit
  serialization 59 ms, cached reads below 1 ms, input-to-next-frame p95 41.1 ms
  on the same 53,089-byte fixture and browser/platform named above.

The user guide is [Shared Markdown editor](../operations/MARKDOWN-EDITOR.md).
It defines the supported dialect, normalization boundaries, shortcuts, storage,
save/recovery behavior and limitations. API/data-model documents cover revisions.

## Final implementation

Split preview previously rebuilt its DOM before a replacement was ready and
could lose reading position. The continuation keeps the previous preview until
new content is ready, rejects stale asynchronous renders, clears pending private
content when a note closes, and synchronizes pane scrolling proportionally.
It retains source focus/caret and uses the live selection when switching between
plain and highlighted source. Find waits for editor readiness rather than a
fixed timer, and repeated activation cannot start duplicate source editors.
No new controls are added.

The existing Table button also exposes keyboard insertion, deletion, movement
and column alignment, described by its tooltip, accessible description and the
user guide. Real keyboard navigation reaches the button from a table cell;
Safari/macOS uses Option+Tab when its default Tab behavior skips buttons.
Repeated operations retain both button focus and the nearest table cell. Header
rows stay valid, Undo restores structural edits, and read-only notes reject the
commands. List Enter/Tab/Shift+Tab behavior has a matching browser regression.

## Acceptance limits

- Reopen the representative synchronized vault in native Obsidian. The synthetic
  vault at `/private/tmp/timekeeper-515-obsidian-vault` contains no credentials or
  sync state. The app connection has repeatedly stalled; obtaining its window
  title is not evidence of successful note rendering. The supported Obsidian CLI
  was checked on 3 November: the installed launcher is 1.8.3, the loaded app is
  1.15.0, and CLI access is disabled. Enabling it is a user setting; it was not
  changed automatically. Native rendering is therefore still unverified.
- Native screen-reader and physical mobile keyboard checks are not certified by
  automated accessibility, emulated viewports or synthetic composition events.
  Record actual manual results before claiming those platforms verified.

The automated first-milestone workflow passes, including exact untouched bytes
and assets after synchronization. Its native Obsidian reopening step remains
unchecked. Do not treat automated browser coverage as a passed native/manual
check or mark that acceptance item complete without evidence.
