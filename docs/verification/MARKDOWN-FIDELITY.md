# Markdown fidelity foundation — issue #515

The shared Milkdown engine retains the authored source of unchanged top-level
blocks during rich edits. Immutable ProseMirror siblings identify stable blocks;
equal-sized edited runs retain their source slots. Original source is reused
only when parsing the result agrees with the canonical serializer's Markdown
semantics. Cached results avoid repeating this work for an unchanged editor state. Shared
host dirty indicators compare document states without serializing on each
keystroke; exact Markdown is produced on explicit reads such as saving and
changing modes.

The corpus in `server/web/tests/fixtures/markdown/fidelity/blocks.md` contains
frontmatter, a setext heading, unusual bullet/strong spelling, tilde code fences
and an aligned table. Browser cases exercise CRLF and mixed line endings, exact
paragraph edits, duplicate paragraphs, insertion, block-boundary changes,
undo/redo, mode switches and a saved vault-note edit through the real API.
Authored HTML remains exact source; load-time cleanup must not remove it.

Run against the committed Java 21 server distribution:

```sh
npm --prefix server/web test
python3 server/web/tests/acceptance.py \
  --distribution server/target/distribution/timekeeper-server.zip \
  --spec markdown-fidelity.spec.js --spec markdown-editor.spec.js --spec notes.spec.js
```

Use `--https --browser webkit` for the corresponding WebKit cases. Existing daily,
weekly, event-note and wikilink browser suites cover the other shared hosts.

This is an initial increment, not completion of #515 or its first milestone.
Editing within a compound block still serializes that entire block. Structural
edits may normalize their boundaries, and ambiguous projections can normalize
the whole body through the existing serializer. Unknown dialects still use the
whole-document source fallback. Obsidian links/embeds, opaque source fragments,
clipboard/file handling and the remaining editor roadmap stay open in #515.

The long-note browser fixture uses 350 paragraphs (53,089 UTF-8 bytes). It
measures dirty-check, explicit serialization and cached-read costs separately.
Its regression budgets are a dirty-check p95 below 50 ms and 100 cached reads
below one second on the named acceptance browser. These are coarse regression
bounds, not a claim of completed performance work or mobile-device certification.
