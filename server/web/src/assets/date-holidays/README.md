# Public holiday data

Source: [date-holidays 3.37.0](https://github.com/commenthol/date-holidays), pinned in the npm lockfile.
Data revision: 2026-09-20; 207 countries.

The upstream browser bundle is wrapped in a native-module bridge with its debug source-map reference removed. Its executable code is unchanged. The upstream map is used only at build time to discover dependency licenses. The bundle loads only when Calendar needs public holidays. All calendar calculations run locally.

Upstream bundle SHA-256: `39cb077321830d8417fd2a573bcf652c3d76b9ad6c1d877974a835ebb94d59b8`.

Code is ISC/MIT; holiday data is CC BY-SA 3.0 as specified in the upstream LICENSE, including its source attributions. The upstream notices and complete dependency license texts accompany the bundle.

Regenerate with `npm run vendor:holidays`; verify with `npm run check:assets`.
