# Markdown presentation verification (#548)

Verified 5 November 2026 on macOS arm64 using the packaged Java 21 server.
The shared component serves Notes, Today and daily, weekly and event-note dialogs.

## Behavior

- A top-right badge combines fence language selection and exact source copying.
  Pinned highlight.js grammars supply decorations without replacing editable DOM.
  About exposes the version, copyright and complete local BSD-3-Clause license.
- Syntax colors use existing theme foregrounds; code and diagram surfaces retain
  their backgrounds. Unknown languages and fences over 200,000 characters stay plain.
- Normal Markdown, safe HTML anchors and wiki links follow clicks without changing
  the draft. Local links retain owner-aware resolution and unsaved-draft checks.
- Notes initially open formatted; revisiting retains an explicit in-memory mode.
- Mermaid is an atomic diagram in formatted mode. Its complete fenced source is
  editable in Markdown source, with no hidden editable code region.
- Note tools groups resource and document actions in a keyboard-accessible menu.
  Formatting wraps on mobile; Undo/Redo use keyboard shortcuts, including from
  note controls. Source and independent day-label inputs keep native history.

## Automated evidence

- 141 web unit cases and all pinned vendor/license checks passed.
- 93 shared-editor, fidelity, journal and Notes cases passed in WebKit over HTTPS.
  These include ordinary-letter Backspace, checklist caret behavior, history,
  paste/drop, drafts, read-only access, revisions and exact Markdown preservation.
- 35 final Chromium journal, Today and presentation cases passed. Additional
  Notes account/import, navigator, recent-note, wiki-link and manual-vault
  round-trip cases passed against the packaged server.
- The presentation case checks all six themes at 1440px and 320px with Axe,
  including keyword, string, number, literal and comment colors. It also checks
  local icon-font rendering. Visual review covers the compact toolbar, code badge
  and diagram-only presentation; screenshots are included in the user guide.

## Shared-batch and tooltip regression

The exact six-spec CI batch plus the tooltip suite passes locally: 69 Chromium
cases. A further 19 WebKit-over-HTTPS menu, import and tooltip cases pass, as do
all 141 unit/vendor checks.

Menu rows show their full labels without duplicate hints. The keyboard regression
opens a navigator tooltip behind the menu, advances the tooltip delay with the
virtual clock, then verifies that one Escape closes the active menu and restores
its trigger. A background hint cannot consume a native popover's Escape key;
hints inside a native dialog retain their existing dismissal behavior.

Import acceptance uses private test-scoped accounts pinned to Europe/Oslo.
Calendar/journal cases may change their own account zone without changing import
activity splitting, records or job history. Exact entry counts, nanosecond totals,
repeat detection, resumed writes and whole-job Undo assertions remain in place.

Tests use test-only routes for isolated component fixtures. The application ships
no editor demo or external runtime dependency. Browser automation does not certify
physical mobile keyboards, native screen readers or native Obsidian rendering;
the earlier [#515 audit](MARKDOWN-EDITOR-515.md) records those limits separately.
