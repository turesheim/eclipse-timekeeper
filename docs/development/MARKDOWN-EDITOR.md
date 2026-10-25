# Daily-note Markdown editor evaluation

Decision for [#393](https://github.com/turesheim/eclipse-timekeeper/issues/393),
2026-10-25: use **Milkdown 7.22.3**, with its CommonMark and GFM presets and
history, behind Timekeeper's own localized controls. The standalone prototype
is linked from **About → Try the Markdown editor prototype**, at
`/web/markdown-lab.html`. It is a working editing surface, not a persisted
calendar feature. The separate day label remains an 80-character text field;
no label, note, imported file or draft is written to the API or browser storage.
Use **Download Markdown** to keep a document; the label is separate and is not
included. Importing over an edited draft asks before replacing it, and leaving
an edited draft triggers the browser's unsaved-changes prompt.

Storage/API changes (#394), calendar integration (#395), Markdown-folder import
(#396) and optional bidirectional reconciliation (#397) remain separate work.
In particular, the prototype does not overload the existing day-note `text` field with Markdown.

## Alternatives and bundle measurements

Milkdown/Crepe was evaluated first. The upstream
[Crepe builder](https://github.com/Milkdown/milkdown/blob/main/packages/crepe/src/core/builder.ts)
combines the same Milkdown core, presets and history with additional feature
UI, upload handling and styling. A small kit integration lets us use native
localized buttons, existing theme tokens and explicit source protection,
without taking on image uploads, mathematics and code-language tooling.

[CodeMirror 6](https://codemirror.net/docs/guide/) is the alternative: a strong
source editor with Markdown language support, history and a decoration API.
Its source document naturally preserves Obsidian syntax. Achieving equivalent
formatted editing would require a custom live-preview layer for headings,
marks, lists, task controls, links and tables, plus selection, composition,
keyboard and screen-reader behavior where hidden markup meets editable text.
This is significantly more integration work than the Milkdown schema approach.
CodeMirror remains a good option if future requirements prioritize editing
Obsidian dialects inside the formatted surface rather than a source fallback.
The [Obsidian editor API](https://docs.obsidian.md/Plugins/Editor/Editor) operates
inside Obsidian's views; it is not an embeddable standalone editor distribution.

Measured locally using esbuild **0.28.2**, bundled/minified ESM, ES2022 target,
without source maps. Bytes are uncompressed / gzip; gzip is a comparison metric,
not a claim about the server's HTTP compression configuration.

| Candidate | JavaScript | CSS | Other assets | Result |
| --- | ---: | ---: | ---: | --- |
| Chosen Milkdown kit integration | 460,840 / 141,343 | 1,561 / 618 | None | Full evaluated formatted surface |
| Default Crepe 7.22.3 | 2,781,408 / 925,123 | 78,756 / 11,767 | 60 KaTeX fonts, 1,076,572 bytes | More features than daily notes need |
| CodeMirror source baseline | 506,628 / 175,030 | Runtime-generated | None | Does not include custom live preview |

The chosen bundle's exact uncompressed sizes are generated in
`server/web/src/assets/milkdown/sizes.json`. The generation log also reports gzip
estimates; the table above used Node 26.12.1 and zlib 1.2.12. Gzip sizes can vary
with zlib, so those estimates are not part of the reproducibility check.
Add `markdown.css` for Timekeeper's
small application styles. Complete license texts add approximately 96 kB;
those are static downloadable attribution, never part of the editor import.
Changing editor code can change the figures; regenerate rather than editing
the generated files.

The comparison used `@codemirror/view` 6.43.15, `@codemirror/commands` 6.11.1
and `@codemirror/lang-markdown` 6.5.2. The baseline entry constructs an
`EditorView` with `markdown()`, `history()`, `drawSelection()` and
`keymap.of([...defaultKeymap, ...historyKeymap])`. It has no live-preview code.
The Crepe entry exports `new Crepe({root}).create()` and imports
`@milkdown/crepe/theme/common/style.css` and `@milkdown/crepe/theme/frame.css`;
font files use esbuild's `file` loader. These comparison packages are not
Timekeeper dependencies. Optional Crepe features could be removed; this table
compares the upstream default against our explicit kit surface, not a minimum
possible Crepe build.

## Native-module integration and licenses

`server/web/editor/milkdown.js` is the build-time entry. Run
`npm ci --ignore-scripts --prefix server/web`, then
`npm run vendor:editor --prefix server/web`. The pinned esbuild executable is a
build tool only; there is no new Node service or runtime bundler. Maven packages
the generated local ESM and CSS with the rest of `server/web/src`. The prototype
loads neither until **Formatted** is requested; opening the normal calendar or
About only loads the small attribution metadata. No external scripts, fonts,
CDNs, image requests or editor services are needed.

`npm test` and `npm run check:assets` rebuild in memory and compare exact output.
`vendor-editor.mjs` follows the actual esbuild input graph, records all 86 bundled
packages and versions in `dependencies.json`, and includes their complete license
texts in `THIRD-PARTY-LICENSES.txt`. Included packages are **MIT or ISC** (permissive
licenses), including Milkdown, ProseMirror, remark, unified and micromark.
Unknown or missing licenses fail generation. The build-time esbuild package is
MIT; test-only dependencies are not included in the browser bundle. About lists
Milkdown's version, copyright, homepage, MIT license and full dependency notices.
Regenerate About with `npm run vendor:notices` after changing an editor version.

## Markdown preservation contract

A synthetic sample and the `tests/fixtures/markdown/{standard,obsidian}.md`
corpus exercise headings, emphasis, strikethrough, ordered/unordered lists,
interactive task checkboxes, links, quotations, fenced code and GFM tables.

- YAML frontmatter is opaque text, displayed separately as read-only metadata.
  It never enters Milkdown. BOM, CRLF, quoting, custom fields and closing `---`
  or `...` are retained. Unclosed frontmatter stays entirely in source mode.
- Opening formatted mode and returning without changing the document retains
  the original source exactly, including trailing newlines, spacing, marker
  choices and CRLF. Undoing all rich changes to the original document also
  restores its exact original source.
- An actual formatted edit serializes the body through Milkdown/remark. Bullet
  markers, emphasis markers, table padding, blank lines and line endings may
  normalize. Meaningful structure and text survive; frontmatter stays untouched.
  A visible status explains this before editing. Source edits are used verbatim.
- Wikilinks, embeds, callouts, highlights, math, comments, block IDs, footnotes,
  reference-style definitions, raw HTML, images and custom directives are
  conservatively source-only. Detection ignores fenced and inline code examples.
  Unsupported content is never silently converted to a different syntax.
  The source textarea remains editable and downloads the complete document.
- A second guard checks unsupported nodes and literal dialect text in the parsed
  remark AST, including escaped-backtick cases the fast scan cannot disambiguate.
  Image node views are inert so even a rejected image cannot trigger a request.
  It then compares the semantic remark AST before and after the actual
  Milkdown parse/serialize pass, ignoring only source positions and list spacing.
  A mismatch rejects rich mode before the draft is accepted. This catches schema
  limitations beyond the explicit dialect checks. It is a compatibility guard,
  not a promise to interpret every future Obsidian plugin's syntax.
- Switching modes preserves text, not cursor positions or per-mode undo history.
  Source mode uses a native textarea; recreating the formatted editor starts a
  new undo history. Rich paste is plain text, and drag/drop is disabled; this
  prototype intentionally has no image/upload feature. Links can be edited but
  activating one inside the editor does not navigate. Raw HTML is never rendered.
- Loading failures retain source and offer another attempt. A 200 kB prototype
  bound on imports and formatted bodies avoids unexpectedly large parse jobs.

## Interaction and accessibility checks

The prototype uses existing account locale initialization and all six Timekeeper
themes. All controls, task-checkbox names, mode announcements, import/download
messages and fallbacks have English and Norwegian Bokmål translations. Markdown
content and frontmatter are literal user text and are never translated.

The formatted content exposes a named multiline textbox. Controls are native
buttons and checkboxes with visible keyboard focus; modes announce their pressed
state. **Escape** moves to the source-mode button, providing an exit from table
Tab navigation. Outside tables, **Tab** moves between controls; inside tables it
moves between cells. Mode changes focus the new editor. Task toggling works with
keyboard Space and pointer/touch activation. The label remains separate through
all mode changes.

Browser acceptance checks cover content/metadata round trips, real rich edits,
source edits, task toggles, undo/redo, blocked link navigation, source-only
Obsidian imports, exact UTF-8/BOM/CRLF downloads, failed lazy loading, no external
requests or writes, keyboard focus, mobile overflow and WCAG A/AA axe checks in
all six themes. Chromium and WebKit runs provide cross-engine and mobile-layout
evidence. Axe and semantic-role checks cover screen-reader basics, not a narrated
VoiceOver/NVDA session or physical mobile IME/selection-handle behavior. Those
remain manual acceptance checks before integrating the editor in the calendar.

This choice is ready for the storage and calendar follow-ups, with explicit
source fallbacks for unsupported dialects and known prototype limitations.
