# Bundled web client

`src/` contains the browser client included in the server JAR at `/web/`.
`tests/` contains unit, browser and installed-Eclipse peer tests.
Production uses native browser modules and requires no separate Node service.

Bootstrap CSS and Bootstrap Icons are pinned and bundled under `src/assets`.
`npm run check:assets` verifies their generated files against the lockfile;
`npm run vendor:bootstrap` and `npm run vendor:icons` regenerate them after an
intentional dependency update. The icon command also updates the project catalog
and API allow-list from Bootstrap icon names.
No Bootstrap JavaScript or third-party runtime asset requests are needed.

Browser acceptance defaults to Chromium. For WebKit, install its browser with
`npx playwright install --with-deps webkit` from this directory. After building
and packaging the server, run from the repository root:

```sh
python3 server/web/tests/acceptance.py --distribution server/target/distribution/timekeeper-server.zip --browser webkit --https
```

The existing HTTPS fixture supports WebKit's Secure session cookies and requires
`openssl` on `PATH` for its temporary test certificate. Add
`--grep 'one-time codes'` for the focused setup/recovery form check. The `--mail`
option enables actual email-delivery checks through a temporary loopback SMTP
fixture and requires `--https`. Safari's native macOS Password AutoFill UI also
needs a manual check.

See [web client development and acceptance](../../docs/development/WEB-CLIENT.md).
