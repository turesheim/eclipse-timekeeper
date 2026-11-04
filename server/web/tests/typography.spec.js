import {test, expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {randomUUID} from 'node:crypto';
import {captureScreenshot} from './screenshots.js';

const storageKey = 'timekeeper.typography';
async function openSettings(page, request) {
  const username = 'typography-' + randomUUID().slice(0, 8), password = 'Private typography test passphrase';
  const response = await request.post('/api/v1/admin/users', {
    headers: {Authorization: `Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},
    data: {username, password, displayName: username, email: username + '@example.org', administrator: false}
  });
  expect(response.ok()).toBeTruthy();
  await page.goto('/web/#account/typography');
  await page.locator('#auth-mode').selectOption('login');
  await page.locator('#username').fill(username);await page.locator('#password').fill(password);
  await page.locator('#login-form button[type=submit]').click();
  await expect(page.getByRole('link', {name: 'Font sizes', exact: true})).toHaveAttribute('aria-current', 'page');
  await expect(page.getByLabel('Font size CSS')).toBeVisible();
}
const editor = page => page.getByLabel('Font size CSS');
const save = page => page.getByRole('button', {name: 'Save font sizes', exact: true}).click();
const rootSize = page => page.locator('html').evaluate(element => getComputedStyle(element).fontSize);

test('typography CSS changes the rem base and named text roles only after saving', async ({page, request}) => {
  await openSettings(page, request);
  const defaults = await editor(page).inputValue();
  expect(defaults).toContain('--font-base: 100%;');
  expect(defaults).toContain('Level 1 heading in formatted Markdown notes.');
  await editor(page).fill(defaults.replace('--font-base: 100%;', '--font-base: 112.5%;')
    .replace('--font-section-heading: 1.25rem;', '--font-section-heading: 1.5rem;')
    .replace('--font-input: 1rem;', '--font-input: 1.125rem;'));
  expect(await rootSize(page)).toBe('16px');
  await save(page);await expect(page.locator('#account-content')).toContainText('Font sizes saved in this browser.');
  expect(await rootSize(page)).toBe('18px');
  await expect(page.locator('.account-card h2')).toHaveCSS('font-size', '27px');
  await page.getByRole('link', {name: 'Profile', exact: true}).click();
  await expect(page.getByLabel('Account username', {exact: true})).toHaveCSS('font-size', '20.25px');
  await page.getByRole('link', {name: 'Font sizes', exact: true}).click();
  await expect(editor(page)).toHaveValue(/--font-base: 112.5%;/);
  await page.reload();await expect(editor(page)).toHaveValue(/--font-base: 112.5%;/);
  expect(await rootSize(page)).toBe('18px');
});

test('typography validates the entire CSS fragment and preserves invalid and unsaved drafts', async ({page, request}) => {
  await openSettings(page, request);
  const valid = ':root { /* A larger base. */ --font-base: 18px; --font-hint: 1rem; }';
  await editor(page).fill(valid);await save(page);
  for (const [css, message] of [
    [':root { --font-unknown: 1rem; }', 'Unknown font property: --font-unknown'],
    [':root { --font-base: 1rem; }', 'Set --font-base to a positive percentage or pixel value'],
    [':root { --font-base: 0px; }', 'Enter a valid, nonzero CSS font size'],
    [':root { --font-hint: 1rem; --font-hint: 2rem; }', 'occurs more than once'],
    [':root { --font-hint: url(https://example.invalid/font); }', 'Enter a valid, nonzero CSS font size'],
    [':root { --font-hint: var(--font-body); }', 'Enter a valid, nonzero CSS font size'],
    [':root { --font-hint: 1rem !important; }', 'Enter a valid, nonzero CSS font size'],
    [':root { --font-hint: 1rem; color: red; }', 'Use one :root'],
    [':root { --font-hint: 1rem; } body { display: none; }', 'Use one :root'],
    [':root { /* unfinished comment --font-hint: 1rem; }', 'Use one :root']
  ]) {
    await editor(page).fill(css);await save(page);
    await expect(page.locator('.form-feedback')).toContainText(message);
    await expect(editor(page)).toHaveValue(css);await expect(editor(page)).toHaveAttribute('aria-invalid', 'true');
    expect(await rootSize(page)).toBe('18px');
    expect(await page.evaluate(key => localStorage.getItem(key), storageKey)).toBe(valid);
  }
  page.once('dialog', dialog => dialog.dismiss());
  await page.getByRole('link', {name: 'Profile', exact: true}).click();
  await expect(page).toHaveURL(/#account\/typography$/);await expect(editor(page)).toBeVisible();
  await editor(page).fill(':root { --font-base: 20px; }');await save(page);
  await expect(page.locator('#account-identity')).toHaveCSS('font-size', '17.5px');
  expect(await rootSize(page)).toBe('20px');
  await page.getByRole('button', {name: 'Reset font sizes', exact: true}).click();
  expect(await rootSize(page)).toBe('16px');
  await expect(editor(page)).toHaveValue(/--font-base: 100%;/);
  expect(await page.evaluate(key => localStorage.getItem(key), storageKey)).toBeNull();
  await page.reload();await expect(editor(page)).toBeVisible();expect(await rootSize(page)).toBe('16px');
});

test('typography follows other tabs and applies to all web entry pages without CSP violations', async ({page, context, request, browserName}) => {
  await context.addInitScript(() => {
    window.typographyPolicyViolations = [];
    document.addEventListener('securitypolicyviolation', event => window.typographyPolicyViolations.push({directive: event.violatedDirective, blocked: event.blockedURI, source: event.sourceFile, line: event.lineNumber}));
  });
  await openSettings(page, request);
  await expect(page.locator('#status')).toHaveText('Up to date');
  // Existing WebKit inline-attribute reports during app startup are recorded
  // before editing fonts; saving and resetting must add no policy violations.
  const startupViolations = await page.evaluate(() => [...window.typographyPolicyViolations]);
  expect(browserName === 'webkit' ? startupViolations.filter(value => value.blocked !== 'inline') : startupViolations).toEqual([]);
  const other = await context.newPage();
  await other.goto('/web/#account/typography');await expect(editor(other)).toBeVisible();
  await editor(page).fill(':root { --font-base: 18px; --font-brand: 2rem; }');await save(page);
  await expect(other.locator('html')).toHaveCSS('font-size', '18px');
  await expect(editor(other)).toHaveValue(':root { --font-base: 18px; --font-brand: 2rem; }');
  const draft = ':root { --font-base: 19px; }';await editor(other).fill(draft);
  await editor(page).fill(':root { --font-base: 20px; --font-brand: 2rem; }');await save(page);
  await expect(other.locator('html')).toHaveCSS('font-size', '20px');await expect(editor(other)).toHaveValue(draft);
  await editor(other).fill(await editor(page).inputValue());
  for (const path of ['/admin/', '/web/project-report.html', '/web/markdown-lab.html']) {
    await other.goto(path);await expect(other.locator('html')).toHaveCSS('font-size', '20px');
    await expect(other.locator('.brand')).toHaveCSS('font-size', '40px');
    expect(await other.evaluate(() => window.typographyPolicyViolations)).toEqual([]);
  }
  await page.getByRole('button', {name: 'Reset font sizes', exact: true}).click();
  await expect(other.locator('html')).toHaveCSS('font-size', '16px');
  expect(await page.evaluate(() => window.typographyPolicyViolations)).toEqual(startupViolations);
  await other.close();
});

test('typography recovers from invalid stored CSS and reports unavailable storage without losing the draft', async ({page, request}) => {
  await page.addInitScript(key => localStorage.setItem(key, ':root { --font-base: 30px; background: red; }'), storageKey);
  await openSettings(page, request);expect(await rootSize(page)).toBe('16px');
  const draft = ':root { --font-base: 18px; }';await editor(page).fill(draft);
  await page.evaluate(() => {Storage.prototype.setItem = () => {throw new DOMException('Unavailable', 'SecurityError');};});
  await save(page);await expect(page.locator('.form-feedback')).toContainText('Allow browser storage for Timekeeper');
  await expect(editor(page)).toHaveValue(draft);expect(await rootSize(page)).toBe('16px');
});

test('typography reaches formatted Markdown headings and source editors inside shadow roots', async ({page, request}) => {
  await openSettings(page, request);
  await editor(page).fill(':root { --font-base: 18px; --font-markdown-h1: 2rem; --font-markdown-source: 1.25rem; }');await save(page);
  await page.goto('/web/markdown-lab.html');
  await page.getByRole('button', {name: 'Formatted', exact: true}).click();
  await expect(page.locator('.ProseMirror h1')).toHaveCSS('font-size', '36px');
  await page.evaluate(async () => {
    const {createSourceEditor} = await import('/web/assets/milkdown/source-editor.js');
    const root = document.createElement('div');root.id = 'typography-source-preview';document.querySelector('main').append(root);
    const source = createSourceEditor({root, value: '# Source text', label: 'Typography source preview', changed() {}});
    await source.ready;
  });
  await expect(page.locator('#typography-source-preview .cm-scroller')).toHaveCSS('font-size', '22.5px');
});

test('typography settings fit mobile screens, support keyboard editing and are accessible in every theme', async ({page, request, browserName}) => {
  await openSettings(page, request);
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({width, height: 900});
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    await editor(page).focus();await page.keyboard.press(browserName === 'webkit' && process.platform === 'darwin' ? 'Alt+Tab' : 'Tab');
    await expect(page.getByRole('button', {name: 'Save font sizes', exact: true})).toBeFocused();
    for (const theme of ['timekeeper', 'timekeeper-dark', 'solarized-light', 'solarized-dark', 'nord', 'dracula']) {
      await page.evaluate(theme => {document.documentElement.dataset.theme = theme;document.documentElement.dataset.bsTheme = ['timekeeper', 'solarized-light'].includes(theme) ? 'light' : 'dark';}, theme);
      expect((await new AxeBuilder({page}).include('#account-page').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()).violations, theme).toEqual([]);
    }
    await page.evaluate(() => {document.documentElement.dataset.theme = 'timekeeper';document.documentElement.dataset.bsTheme = 'light';});
    await captureScreenshot(page, {path: `test-results/typography-${width}.png`, fullPage: true});
  }
});

test('read-only sessions can save browser typography and Norwegian settings are localized', async ({page}) => {
  await page.goto('/web/#account/typography');await page.locator('#auth-mode').selectOption('token');
  await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_READ_TOKEN);
  await page.locator('#login-form button[type=submit]').click();await expect(editor(page)).toBeVisible();
  await expect(page.getByRole('button', {name: 'Save font sizes', exact: true})).toBeEnabled();
  await editor(page).fill(':root { --font-base: 18px; }');await save(page);expect(await rootSize(page)).toBe('18px');
  await page.evaluate(async () => {const {setLocale} = await import('/web/i18n.js');setLocale('nb-NO');location.hash = '#account/profile';});
  await expect(page.getByRole('link', {name: 'Skriftstørrelser', exact: true})).toBeVisible();
  await page.getByRole('link', {name: 'Skriftstørrelser', exact: true}).click();
  await expect(page.getByLabel('CSS for skriftstørrelser')).toBeVisible();
  await expect(page.getByRole('button', {name: 'Lagre skriftstørrelser', exact: true})).toBeEnabled();
  await page.getByRole('button', {name: 'Tilbakestill skriftstørrelser', exact: true}).click();
  await expect(page.locator('.form-feedback')).toHaveText('Standard skriftstørrelser er gjenopprettet.');
});
