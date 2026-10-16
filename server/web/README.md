# Bundled web client

`src/` contains the browser client included in the server JAR at `/web/`.
`tests/` contains unit, browser and installed-Eclipse peer tests.
Production uses native browser modules and requires no separate Node service.

Bootstrap CSS and Font Awesome are pinned and bundled under `src/assets`.
`npm run check:assets` verifies Bootstrap's generated files against the lockfile;
`npm run vendor:bootstrap` regenerates them after an intentional dependency update.
No Bootstrap JavaScript or third-party runtime asset requests are needed.

Browser acceptance defaults to Chromium. Install Playwright's WebKit browser
(`npx playwright install webkit` from this directory), then add `--browser webkit`
and `--grep 'one-time codes'` to `tests/acceptance.py` to check the setup/recovery form
semantics in WebKit. The loopback HTTP fixture does not support WebKit's Secure
session cookies; full WebKit sign-in acceptance needs an HTTPS fixture. Safari's
native macOS Password AutoFill UI also needs a manual check.

See [web client development and acceptance](../../docs/development/WEB-CLIENT.md).
