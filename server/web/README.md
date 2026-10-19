# Bundled web client

`src/` contains the browser client included in the server JAR at `/web/`.
`tests/` contains unit, browser and installed-Eclipse peer tests.
Production uses native browser modules and requires no separate Node service.

Bootstrap CSS and Font Awesome Free solid icons are pinned and bundled under `src/assets`.
`npm run check:assets` verifies their generated files against the lockfile;
`npm run vendor:bootstrap` and `npm run vendor:icons` regenerate them after an
intentional dependency update. The icon command also updates the project catalog
and API allow-list from Font Awesome metadata.
No Bootstrap JavaScript or third-party runtime asset requests are needed.

i18next 26.5.2 and its license are also bundled locally. English and Norwegian
Bokmål catalogs live in `src/locales/`. Run `npm run vendor:i18n` after editing
either catalog; the generator validates keys and interpolation variables and
regenerates the native-module resources. `npm run check:assets` verifies them.
See the language and region section of the development guide for the account
preference and browser fallback behavior.

date-holidays 3.37.0 is bundled as a lazy native-module engine with its national
country catalog, upstream data attribution and dependency licenses. It runs
locally when Track needs public holidays for the selected locale. Run
`npm run vendor:holidays` after an intentional dependency update;
`npm run check:assets` also verifies these generated assets.

Calendar subscriptions use the server's pinned iCal4j engine and native browser
modules. The acceptance runner starts a loopback iCalendar provider as well as
its optional HTTPS/SMTP fixtures. Tests exercise actual server fetching, refresh,
recurrence, reviewed conversion, retries and private account boundaries; no live
calendar provider or external account is required.

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
