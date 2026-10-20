# Bootstrap Icons

Pinned source: `bootstrap-icons@1.13.2` from the official npm package.

The unmodified CSS, WOFF2/WOFF fonts and MIT license are bundled for offline use.
See https://icons.getbootstrap.com/usage/ for upstream usage.

WOFF2 SHA-256: `2b7689746f87e0beeae25338bc021dd2515c2f6f003f3d3d064d878d380b2150`.

Run `npm run vendor:icons` to regenerate the assets, project catalog and API allow-list.

Category membership is pinned separately in `server/web/scripts/bootstrap-icon-categories.json`
from [Bootstrap's category metadata](https://github.com/twbs/icons/tree/afbfcbcb9bea79ea9923fa9ef82ea03d7ee6c01f/docs/content/icons). The catalog generator
checks that every bundled icon belongs to exactly one category. When upgrading
the dependency, update this snapshot from the same release's `docs/content/icons`
and category names in `astro/src/lib/icons.ts` before regenerating the catalog.
