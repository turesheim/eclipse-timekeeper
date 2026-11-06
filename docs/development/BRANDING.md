# Dagsverk naming transition

**Dagsverk** is the product name, formerly **Timekeeper**. Use Dagsverk for new
product copy, agent skills and documentation. An optional English descriptor is
“Time tracking for focused work”.

## Current phase

The web client's page titles, application headers, theme labels and English/
Norwegian copy use Dagsverk. The README and documentation entry page introduce
the new name and explain the transition. Existing Eclipse installation names,
operational guides, screenshots and historical verification reports may still
say Timekeeper; they refer to the same application.

Compatibility identifiers remain unchanged:

- Java packages/classes, Eclipse bundle/extension IDs and Maven coordinates.
- Repository, website/wiki URLs, p2 sites and distribution filenames.
- `TIMEKEEPER_*` environment variables, API headers and archive/schema fields.
- Browser storage keys, event names, theme IDs and translation keys.
- Existing database and synchronization configuration.

There is no data migration or new archive format in this phase. Existing themes
and saved preferences continue to work. User-facing theme labels are Dagsverk
and Dagsverk Dark, while their stored IDs remain `timekeeper` and `timekeeper-dark`.

## Later phases

Update Eclipse display labels, feature descriptions and current installation
guides together, with installed-client verification. Update operational copy
alongside any generated emails, authentication issuer names and installation
metadata that require their own compatibility review.

Treat repository/distribution renaming as a separate change with redirects,
update-channel and automation checks. Rename stable technical identifiers only
for a demonstrated benefit and with a documented compatibility strategy. Preserve
historical verification records as evidence of what was tested under the old name.

## Logo and app icons

The integrated wordmark replaces the initial D in Dagsverk with the D/clock
symbol. The approved SVG/PNG package lives in
[`server/web/src/assets/branding/`](../../server/web/src/assets/branding/).
The logo uses Avenir Next Demi Bold outlines: no font installation or external
requests are required. Use `logo.svg` on light backgrounds, `logo-dark.svg` on
dark backgrounds, and `logo-mono.svg` for a single-color treatment. The
`logo-*-header.svg` files crop only the transparent margins for application
headers; they preserve the approved artwork.

The original theme colors remain #176653 (green), #233B38 (wordmark), #79D0AD
(dark accent), #14231E (dark icon surface) and #EDF5F0 (light wordmark).
The web client, administration and project-report headers select the light or
dark wordmark using the existing Bootstrap theme attribute. Both image variants
have the accessible name Dagsverk; the hidden variant is not announced.
The repository README also supplies both variants.

`favicon.svg` uses the package's app icon, while `apple-touch-icon.png` uses its
256 px PNG. The Synology package retains its existing technical icon filenames
and sizes, rasterized from the same SVG. The other bundled PNG icon sizes run
from 16 to 1024 px. The previous `docs/assets/logo.idraw` is a legacy source and
is not the source for this identity.
