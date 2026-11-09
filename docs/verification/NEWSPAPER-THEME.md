# Newspaper theme verification

Date: 2026-11-09.

Newspaper is available under **My Account → Profile → Theme**, with **Avis** as
its Norwegian label. It uses black ink (`#000`) and warm almost-white paper
(`#faf9f6`), Georgia/Times serif text, square edges and double rules. Hard-edged
pixel dithering replaces tinted surfaces and the dialog backdrop. Markdown
source and code retain monospace fonts. Client and label colors display in
monochrome without changing their saved values.

## Evidence

The final packaged source is
`19a6f02c8a5de54adbdf912517f018f9e72aab71`. Its distribution SHA-256 is
`4602633a3dc6d0702ebc80d273a796fcc8843303f8fa4a8a6c480161fed86177`.
Subsequent changes add only this report and review screenshots.

- `python3 scripts/quality.py --affected --base origin/main`: quick checks,
  generated-asset verification and all 163 web unit tests pass on Node 24.22.2.
- `./mvnw -B -ntp -f server/pom.xml -DskipTests package` and the distribution
  provenance/checksum packager pass with Java 21.0.13 and Python 3.13.3.
- Chromium: all four theme tests pass, including WCAG A/AA audits across all
  seven themes, persistence, cross-tab changes and unavailable storage. This
  full run used `45dff5dd49ccd4cf99ec36f1b14f910b4b3f2456`; the subsequent
  change only adjusts a keyboard assertion in the test harness. The final
  Newspaper scenario was rerun successfully against the final package.
- WebKit over the isolated HTTPS fixture: both the final Newspaper scenario
  and Newspaper persistence test pass against the final package.

The Newspaper scenario checks actual rendered foreground, background, border
and focus colors, serif journal text, monospace source, dithered weekends,
the local select-arrow image, restoration of client colors when switching
themes, keyboard dialog activation, and layouts at 1440, 390 and 320 pixels.
It audits Today, Calendar, Week, record dialogs, Tasks, Notes, account settings,
Administration and sign-in. Existing theme coverage also audits Workweek,
Activities, Clients, Projects and label settings. All records are synthetic;
browser traces and videos are disabled.

The broader Java `verify` run was stopped before completion. No complete Java
test-suite result is claimed; the review package uses `-DskipTests`.

Reproduce browser checks after building and packaging the clean source:

```sh
python3 server/web/tests/acceptance.py --distribution server/target/distribution/timekeeper-server.zip --spec newspaper.spec.js --spec themes.spec.js
python3 server/web/tests/acceptance.py --distribution server/target/distribution/timekeeper-server.zip --browser webkit --https --spec newspaper.spec.js --spec themes.spec.js --grep 'Newspaper prints|theme newspaper persists'
```

## Screenshots

Workspace captures preserve the viewport and fixed bottom navigation. Account
and sign-in captures can include the full page. Both engines use the final
package identified above.

| View | Chromium | WebKit |
| --- | --- | --- |
| Today, desktop | [Screenshot](../assets/screenshots/newspaper-today-desktop.png) | [Screenshot](../assets/screenshots/newspaper-webkit-today-desktop.png) |
| Calendar, desktop | [Screenshot](../assets/screenshots/newspaper-calendar-desktop.png) | [Screenshot](../assets/screenshots/newspaper-webkit-calendar-desktop.png) |
| Today, 390 px | [Screenshot](../assets/screenshots/newspaper-today-mobile.png) | [Screenshot](../assets/screenshots/newspaper-webkit-today-mobile.png) |
| Today, 320 px | [Screenshot](../assets/screenshots/newspaper-today-320.png) | [Screenshot](../assets/screenshots/newspaper-webkit-today-320.png) |
| Week, desktop | [Screenshot](../assets/screenshots/newspaper-week-desktop.png) | [Screenshot](../assets/screenshots/newspaper-webkit-week-desktop.png) |
| Client dialog, desktop | [Screenshot](../assets/screenshots/newspaper-editor.png) | [Screenshot](../assets/screenshots/newspaper-webkit-editor.png) |
| Client dialog, 320 px | [Screenshot](../assets/screenshots/newspaper-editor-mobile.png) | [Screenshot](../assets/screenshots/newspaper-webkit-editor-mobile.png) |
| Profile and theme selection | [Screenshot](../assets/screenshots/newspaper-account.png) | [Screenshot](../assets/screenshots/newspaper-webkit-account.png) |
| Administration | [Screenshot](../assets/screenshots/newspaper-administration.png) | [Screenshot](../assets/screenshots/newspaper-webkit-administration.png) |
| Tasks | [Screenshot](../assets/screenshots/newspaper-tasks.png) | [Screenshot](../assets/screenshots/newspaper-webkit-tasks.png) |
| Notes | [Screenshot](../assets/screenshots/newspaper-notes.png) | [Screenshot](../assets/screenshots/newspaper-webkit-notes.png) |
| Sign-in | [Screenshot](../assets/screenshots/newspaper-sign-in.png) | [Screenshot](../assets/screenshots/newspaper-webkit-sign-in.png) |
