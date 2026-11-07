# Shared Markdown editor

`MarkdownField` is the product component used by Notes, Today and the daily,
weekly and event-note dialogs. It owns the common toolbar, plain Markdown source
area, formatting mode, uploads and revision comparison. Hosts supply resource
resolution and guarded save operations; they retain ownership of the day label,
ETag, unsaved state and dismissal decisions.

The [user guide](../operations/MARKDOWN-EDITOR.md) defines the supported dialect
and interactions. The original Milkdown decision is recorded in issue #393;
the separate About prototype and its scratchpad controls have been retired.

## Runtime boundaries

- `src/markdown-field.js` manages modes and the in-memory document. YAML
  frontmatter stays separate, opaque and read-only in the formatted projection.
  Unchanged source, BOM and mixed line endings survive a round trip exactly.
- `editor/milkdown.js` provides the shared Milkdown/ProseMirror schema, history,
  keyboard behavior and semantic admission guard. `tracked-document.js` reuses
  unchanged top-level Markdown spans after a rich edit. Unfamiliar blocks remain
  inert editable source fragments rather than being discarded or executed.
- Markdown source is a native textarea. There is no enhanced-source editor,
  source-tools toolbar, separate document outline or split-preview renderer.
  Switching modes retains the rich document/history when it can be reconciled.
- `src/markdown-experience.js` adds resource-aware completion, existing-asset
  selection, image properties and guarded revision comparison through the same
  component. `src/markdown-actions.js` groups these and host document actions in
  one keyboard-accessible popover. Cursor and scroll positions remain in memory
  while the host is open; new Notes documents initially open formatted.
- `editor/code-highlight.js` supplies read-only language parsers for code-block
  decorations. It lazy-loads only for code content, preserving the block's
  authored source. Pinned highlight.js grammars supply validated decoration
  ranges; unknown languages and fences over 200,000 characters remain plain.
  `shell` fences use the Bash script grammar without requiring a prompt;
  `console` and `shellsession` retain the prompt-aware transcript grammar.
  Arrow Down from the last visual line, Arrow Right at the end, or clicking below
  a final code block moves to following prose. An empty trailing caret paragraph
  does not change the authored Markdown until text is entered.
  The top-right badge groups language selection and copying. Theme surfaces stay
  unchanged; token foregrounds blend existing semantic and body-text colors.
- Emoji shortcodes use a micromark token and an inline atom, preserving the
  authored alias through source switches, undo and serialization. The pinned
  local gemoji data is regenerated with `npm run vendor:emoji`; code, escapes
  and URL destinations remain literal.
- Mermaid fences use an atomic presentation node and lazy-load the local
  renderer. Formatted mode shows only an inert SVG image; Markdown source edits
  its complete fenced text. Script and external-resource requests are rejected.
  Safe HTML anchors project through the normal link schema; authored HTML is
  never inserted as executable document content.

The component retains private drafts in memory. Clearing a host or ending its
session destroys the formatted projection and clears its source. The shared `DocumentAutosave` queue serializes guarded host writes, drains edits
made during a save and flushes before navigation. Writes are debounced for one
second with a five-second typing deadline; failures keep the draft and block
navigation. Unknown replies are reconciled by reads before further writes. Body edits
and day labels use independent records and validators.

## Assets and licenses

Install the pinned dependencies, then regenerate editor assets and attribution:

```sh
npm ci --ignore-scripts --prefix server/web
npm run vendor:editor --prefix server/web
npm run vendor:notices --prefix server/web
npm test --prefix server/web
```

The build-time entries are `editor/milkdown.js` and `editor/code-highlight.js`.
Maven packages their generated local ESM/CSS under `src/assets/milkdown`.
There is no runtime bundler, Node service or external editor CDN.
`vendor-editor.mjs` follows the actual bundle graph and records complete licenses
and dependency versions. Unknown or missing licenses fail generation. About's
highlight.js attribution includes its pinned version and complete BSD-3-Clause
license. The obsolete direct CodeMirror/Lezer language dependencies are removed.

Deterministic uncompressed sizes are recorded in `assets/milkdown/sizes.json`;
the generation log also reports local gzip estimates. Compare that metadata
rather than carrying historical prototype sizes forward as current measurements.

## Verification

Component tests use the real `MarkdownField` through test-only Playwright routes
and small file-load/capture adapters under `tests/fixtures/editor`. These fixtures
are outside Maven's web resources and are never shipped. Tests explicitly check
that the retired prototype and enhanced-source asset return 404.

Editor/fidelity cases cover exact untouched Markdown, edited-block preservation,
opaque metadata, mode-switch history, ordinary Backspace, checklist caret and
keyboard behavior, paste/drop, code highlighting, Mermaid, safe links and
read-only access. Notes/Today/journal suites exercise the same component with
actual authenticated persistence. Manual-client acceptance verifies guarded
folder synchronization and browser edits against the packaged server over HTTPS.
The [#515 verification record](../verification/MARKDOWN-EDITOR-515.md) separates
historical measurements from current implementation and platform coverage.

The [#548 presentation verification](../verification/MARKDOWN-EDITOR-548.md)
records the subsequent code badge, link, diagram and note-action coverage.
