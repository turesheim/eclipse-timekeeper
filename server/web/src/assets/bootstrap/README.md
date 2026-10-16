# Bootstrap 5.3.8

CSS and MIT license from the exact `bootstrap` npm dependency in
`server/web/package-lock.json`. Regenerate with `npm ci --ignore-scripts` and
`npm run vendor:bootstrap` in `server/web`.

The upstream CSS is unchanged except that embedded SVG data URLs become local,
content-addressed SVG files and the source-map reference is removed. This keeps
`img-src 'self'` intact and avoids external requests. No Bootstrap JavaScript is
included: navigation uses the existing application state and dialogs use the
browser's native modal dialog behavior.
