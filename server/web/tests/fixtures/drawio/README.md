# Editable PNG fixtures

These synthetic drawings were exported by the unmodified draw.io **32.6.10**
web application from its official `draw.war` release. They contain no private
note data. Each PNG includes both the **Overview** and **Details** pages, with
editable rectangle `stage-a` and ellipse `stage-b` shapes.

`two-pages.drawio.png` shows Stage A. It was reopened in draw.io, its Details
page selected, and both shapes edited to **Stage A revised** and **Stage B revised** using the shape's text
editor. `revised.drawio.png` is that new export, with both pages retained.
Neither image has been optimized, resized or re-encoded after export.

The optional `../../drawio-roundtrip.mjs` scenario reopens exported/downloaded
PNGs in a separately running official draw.io editor. Dagsverk's production
preview uses its existing authenticated raster renderer and loads no draw.io
code. See [draw.io PNG export](https://www.drawio.com/docs/manual/export/export-to-png/).
