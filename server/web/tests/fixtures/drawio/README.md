# Editable PNG fixtures

These synthetic drawings were exported by the unmodified draw.io **32.6.10**
web application from its official `draw.war` release. They contain no private
note data. Each PNG includes both the **Overview** and **Details** pages, with
editable rectangle `stage-a` and ellipse `stage-b` shapes.

`two-pages.drawio.png` shows Stage A. It was reopened in draw.io, its Details
page selected, and both shapes edited to **Stage A revised** and **Stage B revised** using the shape's text
editor. `revised.drawio.png` is that new export, with both pages retained.
Neither image has been optimized, resized or re-encoded after export.

## Cross-origin embed check

Use the [official draw.io 32.6.10 release](https://github.com/jgraph/drawio/releases/tag/v32.6.10).
Extract the static assets from `draw.war` into a temporary directory.
From the repository root, start the editor host in one terminal:

```sh
editor_dir=$(mktemp -d)
unzip -q /path/to/draw.war -d "$editor_dir" -x 'META-INF/*' 'WEB-INF/*'
python3 -m http.server 18764 --bind 127.0.0.1 --directory "$editor_dir"
```

With the web dependencies and Playwright browsers installed, run in another terminal:

```sh
for engine in chromium webkit; do
  node server/web/tests/drawio-roundtrip.mjs http://127.0.0.1:18764/ \
    server/web/tests/fixtures/drawio/two-pages.drawio.png "target/drawio-embed/$engine" "$engine"
done
```

The harness serves its parent page on a separate loopback origin. It uses the
bundled web client's Content Security Policy with a `frame-src` exception for
the editor origin. The sandboxed iframe runs draw.io in offline embed mode,
and Playwright blocks HTTP requests outside the two test origins. The harness
accepts messages from the active editor frame and its configured origin,
edits both shapes, exports an editable PNG, and opens that PNG in a fresh
editor to verify both pages and edits.

This checks static hosting using synthetic fixtures over loopback HTTP.
Packaged Dagsverk dialog behavior, vault writes and HTTPS deployment are
separate acceptance scenarios.
