# Shared Markdown editor

Daily, weekly, calendar-event and vault notes use the same editor. A day label is
an independent resource: erasing a note body does not erase its day label.
Saving is explicit. Formatted and Markdown source are two views of the same draft.

## Source preservation and supported syntax

Opening, changing modes and saving without editing preserves the original file,
including UTF-8 BOM, opaque YAML frontmatter and line endings. Frontmatter is
displayed separately and is read-only; edit it with an external source editor.
Formatted edits preserve unchanged top-level blocks when the resulting Markdown
passes a semantic equivalence check. Editing inside a list, table or quotation
can normalize that whole block. Structural edits and ambiguous boundaries can
require canonical serialization of the body. Review source before saving if
exact spelling of edited markup matters.

The formatted dialect is CommonMark/GFM: paragraphs, H1–H6, emphasis, strong,
strikethrough, links, quotations, horizontal rules, nested/task lists, tables and
fenced code. Enter creates a paragraph; Shift+Enter creates a hard line break.
An ordinary newline in Markdown is a soft break. Newly formatted rules use `---`.
Unknown extensions, raw HTML, reference-style links and code fences with extra
metadata remain inert, editable source fragments. Their HTML is never executed.

Vault extensions include:

| Syntax | Behavior |
| --- | --- |
| `[[2026-11-02]]` | Opens that day in Today |
| `[[Notes/Design]]` | Opens a vault note |
| `[[Notes/Design#Decisions\|design]]` | Uses an alias and jumps to a heading |
| `![[journal/assets/image.png]]` | Authenticated raster preview |
| `![[image.png\|320]]`, `![[image.png\|320x200]]` | Obsidian image sizing, up to 2000 pixels per dimension |
| `> [!NOTE] Title` followed by quoted content | Editable callout; `+`/`-` controls initial folding |
| `Text[^one]` and `[^one]: Footnote.` | Footnote reference and editable definition |

Type `[[` for note completion; `![[` suggests assets. Exact vault/relative paths
take priority over a unique filename. Missing and ambiguous references are
reported visibly; rename/move does not rewrite other files' links. Hover or focus
a wiki reference for its preview. Normal web/relative Markdown links open with
Command/Ctrl-click in an editable note; read-only links open on an ordinary click.
Unsafe schemes and paths leaving the vault are rejected. Obsidian plugins,
transclusions of entire Markdown notes and executable HTML are not supported.

## Writing and source tools

The heading menu selects plain text or H1–H6. Toolbar tooltips name each action
and available shortcut; active formatting is indicated. Use the main toolbar to
format a selection. Type `/` at the start of a paragraph for commands.
On narrow screens, **More formatting** reveals the remaining actions. The
collapsible outline navigates headings. Returning to a note retains its in-memory
cursor and scroll position while the session remains open.

Use Command/Ctrl+B for bold, Command/Ctrl+I for italic, and Command/Ctrl+Z for undo.
Redo uses Command/Ctrl+Shift+Z (or the toolbar). Mode changes retain rich history;
source edits become history boundaries when returning to formatted mode.

**Source editor tools** loads the optional CodeMirror editor with Markdown/code
highlighting, indentation, find/replace and optional line numbers. Find uses
Command/Ctrl+F. **Split preview** refreshes the formatted pane after source edits
without moving source focus or resetting its reading position. The two panes
scroll together by relative document position, including with highlighted source.
Escape followed by Tab leaves the source editor; Tab alone indents. Turning the
tools off restores the plain textarea. Source tools require constructed style
sheets; unavailable features leave plain source editing usable. Split previews
are limited to 200 KB bodies to bound background parsing.

Code blocks have a language field and a copy button. Highlighting supports
JavaScript/TypeScript, Java, Python, JSON, SQL, HTML, CSS, shell, YAML and Markdown;
unknown languages stay plain. Mermaid fences retain editable source and render
a local preview. External diagram resources are rejected.

Use the main toolbar for lists and tables. Tab in a table moves between cells.
Tab-separated clipboard data can create a table.

## Paste and attachments

Formatted paste retains safe headings, lists, links and tables. Scripts, embedded
media and remote clipboard images are discarded. Command/Ctrl+Shift+V pastes
literal text. Pasting an HTTP(S) URL over selected text creates a link without
replacing its label. Code fields accept literal text.

Paste screenshots or drop files to upload them at a tracked insertion point.
The current upload shows its filename/count; **Retry** retains failed files and
does not duplicate completed uploads. Uploads are limited to 5 MB per file and
also obey account/server storage limits. Existing assets can be selected without
uploading another copy. PNG, JPEG, GIF and WebP use authenticated previews; other
attachments remain download links. References stay portable vault paths.
Select an image for alternative-text and sizing controls; sizing explicitly
converts an ordinary Markdown image into an Obsidian embed. Sized embeds use
the filename as their label. Journal uploads go in the configured journal assets
subfolder. No external image URL is fetched automatically.

## Saving, conflicts and history

Save status belongs to the host note. Failed uploads or save requests retain the
draft. A stale save is rejected rather than overwriting another writer. Use
**Revision history** to compare the current draft with a stored revision or the
latest server version. Restoring places the chosen content in the draft; review
and explicitly save it using the original write guard. Navigating away from an
unsaved draft requires confirmation.

Revisions begin with the first content change after this feature is installed;
they cannot recover previously lost content. The server retains at most 20
revisions per note and 1000 revisions/8 MB per account, evicting the oldest first.
History excludes binary assets and independent day labels. It is server-local:
portable account exports omit revisions; full instance backups include them.

Autosave remains off: background writes during synchronization/conflict review
would change the guarded manual-save contract. Drafts and remembered positions
live in memory only. Reloading, closing the tab or logging out can discard them;
there is no silent localStorage/sessionStorage copy of private note contents.
Read-only accounts can navigate, preview and compare but cannot edit or upload.

## Verification and limits

See the [completion audit](../verification/MARKDOWN-EDITOR-515.md) for actual
browser/platform evidence, pending manual checks and representative-vault
results. Automated accessibility checks and emulated mobile viewports do not
certify a physical mobile keyboard, assistive technology or Obsidian rendering.
The issue remains open while required acceptance evidence is incomplete.
