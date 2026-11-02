# Markdown editor completion audit (#515)

This audit records implementation and evidence separately. Issue #515 stays open
until every required acceptance area is verified; a passing focused suite alone
is not completion. Work is isolated in `codex/markdown-editor-fidelity`; the dirty
user checkout remains untouched.

## Implemented, awaiting final regression and visual review

- Validated source reuse for unchanged blocks; exact open/save bytes, frontmatter
  and mixed line endings; editable inert fragments around unsupported syntax.
- Vault wikilinks, aliases, heading targets, completion and authenticated raster
  embeds with Obsidian width/height syntax. Missing/ambiguous references are
  explicit. Vault links participate in unsaved-draft navigation guards.
- Callouts and footnotes, source retention, language/copy/highlight controls for
  code, existing Mermaid previews, list indentation and table insertion,
  deletion, movement, column alignment and spreadsheet paste.
- Sanitized formatted paste, explicit plain paste, URL over selection, file
  paste/drop at tracked insertion points, upload status/retry and existing assets.
  Daily, weekly, event and general note hosts share these capabilities.
- Active formatting, shortcut hints, contextual controls, slash commands, keyboard
  suggestions, compact toolbar overflow and a collapsible heading outline.
- Optional CodeMirror source tools, highlighting, find/replace, indentation,
  line-number toggle and synchronized split preview; plain source remains a
  resilient fallback. Rich history survives unchanged mode round trips.
- In-memory note cursor restoration and prospective server revision snapshots;
  comparison precedes restoration into an unsaved draft. Native writes, vault
  writes and WebDAV writes use the same canonical content hooks.
- Read-only checks remain at schema transactions, source widgets and host upload/
  write boundaries. No note contents enter localStorage/sessionStorage.

## Evidence recorded so far

- Foundation: 121 unit tests; 219 Chromium browser cases passed, 7 opt-in cases
  skipped; 28 selected HTTPS WebKit cases passed (commit `7237bf5c`).
- New experience increment: 26 selected Chromium cases passed at `ebb7fdfc`,
  including authenticated wiki resolution/embeds, revision comparison, mixed-EOL
  source tools, safe paste, table commands, callouts/footnotes, code highlighting,
  mode-switch history and independent day labels.
- `NoteRevisionsTest`: bounded retention, exact bytes, daily/weekly/vault native
  writes, owner isolation, independent labels and stale-write rejection pass.
- OpenAPI validation: 54 contract cases pass after registering revision reads.

These results precede the final combined commit and must be repeated for it.

## Remaining acceptance work

- Exercise completion, asset selection, drop positioning and multi-file retry;
  review all new controls with keyboard, mobile viewport, IME and theme checks.
- Check selection/scroll behavior across edited mode changes and note navigation;
  review image alt/sizing controls and structured-editing boundary cases.
- Verify revision API access control, event-note revisions, account retention and
  document archive/backup treatment.
- Run full server tests, full browser regressions and HTTPS WebKit/Firefox checks;
  inspect screenshots and resolve every applicable CI failure.
- Complete a representative manual WebDAV vault round trip and reopen it in
  Timekeeper and the installed Obsidian application. Include Unicode/spaced names,
  daily/weekly/event notes, unknown constructs, images, attachments and conflicts.
- Finish user/API/data-model documentation, syntax contract, normalization,
  shortcuts, saving/recovery behavior, performance evidence and platform limits.

## Design decisions

CodeMirror is optional and loaded separately, retaining the plain source fallback.
Milkdown's existing table commands are reused. Crepe's image components expose upload and URL-proxy hooks; adopting them would
still require adapters for authenticated bytes, portable vault paths and the
source-preservation schema. The existing authenticated views retain that contract. Code highlighting reuses CodeMirror's
language parsers without replacing the rich code node's editable DOM.

Saving remains explicit. Autosave was considered: quietly issuing writes while a
note is being reviewed for sync/conflict resolution would change the current
contract. This increment keeps manual guarded saving. Offline draft recovery is
in-memory only, cleared with the session; persistent browser recovery would need
an explicit account privacy/retention option before introduction.

Component evaluation references: [Crepe feature surface](https://github.com/Milkdown/milkdown/blob/main/packages/crepe/src/feature/index.ts), [image adapters](https://github.com/Milkdown/milkdown/blob/main/packages/crepe/src/feature/image-block/index.ts), and [selection toolbar](https://github.com/Milkdown/milkdown/blob/main/packages/crepe/src/feature/toolbar/index.ts). The installed Kit/component interfaces were also checked at 7.22.3; upstream main is architectural context, not a pinned runtime dependency.
