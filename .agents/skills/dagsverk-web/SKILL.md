---
name: dagsverk-web
description: Implement or fix Dagsverk browser UI, CSS, localization, themes and browser interactions. Use for server/web changes; this application uses Bootstrap 5.3.8 and native modules, not the installed Bootstrap 6 authoring workflows.
---

# Dagsverk web client

Read root-relative `server/web/README.md` and the applicable sections of
`docs/development/WEB-CLIENT.md`. For the journal editor, also consult
`docs/development/JOURNAL-EDITOR.md` or `MARKDOWN-EDITOR.md` as appropriate.

- Follow existing native browser modules and pinned Bootstrap 5.3.8 CSS.
  The production client is bundled in the server and needs no Node service.
  Preserve offline/local assets and avoid external runtime asset requests.
- Reuse established components, account navigation and dialog/focus behavior.
  Check keyboard operation, accessible names, narrow screens and affected
  light/dark themes. Use browser inspection/screenshots for layout changes.
- Update both `src/locales/en.json` and `nb.json`, preserving interpolation
  variables and stable translation keys. Run
  `npm run vendor:i18n --prefix server/web` after catalog changes. Never patch
  generated `src/locales/resources.js` directly.
- Use Dagsverk in visible copy. Preserve `timekeeper` theme IDs, local-storage
  keys, events, API headers, URLs and archive fields for compatibility.
- Update vendored assets through their existing generators after an intentional
  dependency change; use `npm run check:assets --prefix server/web` to verify.
- Run `python3 scripts/quality.py --affected`. For changed UI behavior, exercise
  relevant Playwright scenarios against a freshly packaged server as described
  in the web acceptance guide. Select Chromium/WebKit according to the affected
  behavior. Do not treat a stale package as evidence for changed source.
- Retain existing security behavior and fixture protections. Browser traces can
  contain credentials; follow the repository's trace/screenshot conventions.
