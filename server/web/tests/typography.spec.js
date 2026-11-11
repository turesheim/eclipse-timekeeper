import {test, expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {randomUUID} from 'node:crypto';
import {captureScreenshot} from './screenshots.js';
import {browserRequest} from './browser-request.js';
import {openAccountMenu} from './navigation.js';

async function createUser(request) {
  const username = 'typography-' + randomUUID().slice(0, 8), password = 'Private typography test passphrase';
  const response = await request.post('/api/v1/admin/users', {
    headers: {Authorization: `Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},
    data: {username, password, displayName: username, email: username + '@example.org', administrator: false}
  });
  expect(response.ok()).toBeTruthy();return {...(await response.json()).user, username, password};
}
async function signIn(page, user) {
  await page.goto('/web/#account/typography');await page.locator('#auth-mode').selectOption('login');
  await page.locator('#username').fill(user.username);await page.locator('#password').fill(user.password);
  await page.locator('#login-form button[type=submit]').click();
  await expect(page.getByRole('link', {name: 'Font sizes', exact: true})).toHaveAttribute('aria-current', 'page');
  await expect(editor(page)).toBeVisible();
}
async function openSettings(page, request) {const user = await createUser(request);await signIn(page, user);return user;}
const editor = page => page.getByLabel('Font size CSS');
const save = page => page.getByRole('button', {name: 'Save font sizes', exact: true}).click();
async function saved(page) {await expect(page.locator('.form-feedback')).toHaveText('Font sizes saved to your account.');}
async function profile(page) {return (await browserRequest(page).get('/api/v1/me')).json();}
async function putProfile(page, body) {
  const current = await browserRequest(page).get('/api/v1/me'), auth = await (await browserRequest(page).get('/api/v1/auth/session')).json();
  return browserRequest(page).put('/api/v1/me', {
    headers: {'If-Match': current.headers().etag, 'X-CSRF-Token': auth.csrf, 'X-Timekeeper-Owner': Buffer.from(auth.ownerId).toString('base64url')},
    data: {zoneId: (await current.json()).zoneId, ...body}
  });
}

test('typography CSS changes the rem base and named text roles only after the account save succeeds', async ({page, request}) => {
  await openSettings(page, request);
  const defaults = await editor(page).inputValue();expect(defaults).toContain('--font-base: 14px;');
  expect(defaults).toContain('Level 1 heading in formatted Markdown notes.');
  await editor(page).fill(defaults.replace('--font-base: 14px;', '--font-base: 112.5%;')
    .replace('--font-section-heading: 1.25rem;', '--font-section-heading: 1.5rem;')
    .replace('--font-button: 1rem;', '--font-button: 1.125rem;')
    .replace('--font-input: 1rem;', '--font-input: 1.125rem;'));
  await expect(page.locator('html')).toHaveCSS('font-size', '14px');await save(page);await saved(page);
  await expect(page.locator('html')).toHaveCSS('font-size', '18px');
  await expect(page.locator('.account-card h2')).toHaveCSS('font-size', '27px');
  await expect(page.getByRole('button', {name: 'Save font sizes', exact: true})).toHaveCSS('font-size', '20.25px');
  expect((await profile(page)).typographyCss).toContain('--font-base: 112.5%;');
  await page.getByRole('link', {name: 'Profile', exact: true}).click();
  await expect(page.getByLabel('Account username', {exact: true})).toHaveCSS('font-size', '20.25px');
  await page.getByRole('link', {name: 'Font sizes', exact: true}).click();
  await expect(editor(page)).toHaveValue(/--font-base: 112.5%;/);
  await page.reload();await expect(editor(page)).toHaveValue(/--font-base: 112.5%;/);
  await expect(page.locator('html')).toHaveCSS('font-size', '18px');
});

test('typography validates the entire CSS fragment and preserves invalid and unsaved drafts', async ({page, request}) => {
  await openSettings(page, request);
  const valid = ':root { /* A larger base. */ --font-base: 18px; --font-hint: 1rem; }';
  await editor(page).fill(valid);await save(page);await saved(page);
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
    await editor(page).fill(css);await save(page);await expect(page.locator('.form-feedback')).toContainText(message);
    await expect(editor(page)).toHaveValue(css);await expect(editor(page)).toHaveAttribute('aria-invalid', 'true');
    await expect(page.locator('html')).toHaveCSS('font-size', '18px');expect((await profile(page)).typographyCss).toBe(valid);
  }
  page.once('dialog', dialog => dialog.dismiss());await page.getByRole('link', {name: 'Profile', exact: true}).click();
  await expect(page).toHaveURL(/#account\/typography$/);await expect(editor(page)).toBeVisible();
  await editor(page).fill(':root { --font-base: 20px; }');await save(page);await saved(page);
  await expect(page.locator('#account-identity')).toHaveCSS('font-size', '17.5px');
  await page.getByRole('button', {name: 'Reset font sizes', exact: true}).click();
  await expect(page.locator('.form-feedback')).toHaveText('Default font sizes restored.');
  await expect(page.locator('html')).toHaveCSS('font-size', '14px');await expect(editor(page)).toHaveValue(/--font-base: 14px;/);
  expect((await profile(page)).typographyCss).toBeNull();
  await page.reload();await expect(editor(page)).toBeVisible();await expect(page.locator('html')).toHaveCSS('font-size', '14px');
});

test('account typography persists in another browser and stays isolated when signing out and switching users', async ({page, request, browser}) => {
  const user = await openSettings(page, request), css = ':root { --font-base: 18px; }';
  await editor(page).fill(css);await save(page);await saved(page);
  const previous = await profile(page);
  const otherContext = await browser.newContext({baseURL: new URL(page.url()).origin, ignoreHTTPSErrors: true});
  try {
    const other = await otherContext.newPage();await signIn(other, user);
    await expect(other.locator('html')).toHaveCSS('font-size', '18px');await expect(editor(other)).toHaveValue(css);
    const second = await createUser(request);
    await openAccountMenu(page);await page.getByRole('button', {name: 'Sign out', exact: true}).click();
    await expect(page.locator('#login')).toBeVisible();await expect(page.locator('html')).toHaveCSS('font-size', '14px');
    await signIn(page, second);await expect(page.locator('html')).toHaveCSS('font-size', '14px');
    expect((await profile(page)).typographyCss).toBeNull();
    await page.evaluate(data => window.timekeeperTypography.receive({data}), previous);
    await expect(page.locator('html')).toHaveCSS('font-size', '14px');
    await expect(other.locator('html')).toHaveCSS('font-size', '18px');
    await other.getByRole('button', {name: 'Reset font sizes', exact: true}).click();
    await expect(other.locator('html')).toHaveCSS('font-size', '14px');
  } finally {await otherContext.close();}
});

test('a session expiring between profile bootstrap and restore clears account font sizes from sign-in', async ({page, request}) => {
  await openSettings(page, request);await editor(page).fill(':root { --font-base: 18px; }');await save(page);await saved(page);
  let reads = 0;
  await page.route('**/api/v1/auth/session', route => {
    if (++reads === 1) return route.continue();
    return route.fulfill({status: 401, contentType: 'application/json', body: JSON.stringify({code: 'UNAUTHENTICATED'})});
  });
  await page.reload();await expect(page.locator('#login')).toBeVisible();
  await expect(page.locator('html')).toHaveCSS('font-size', '14px');
});

test('typography follows confirmed peer changes and all web entry pages without adding CSP violations', async ({page, context, request, browserName}) => {
  await context.addInitScript(() => {
    window.typographyPolicyViolations = [];
    document.addEventListener('securitypolicyviolation', event => window.typographyPolicyViolations.push({directive: event.violatedDirective, blocked: event.blockedURI}));
  });
  await openSettings(page, request);await expect(page.locator('#status')).toHaveText('Up to date');
  const startup = await page.evaluate(() => [...window.typographyPolicyViolations]);
  expect(browserName === 'webkit' ? startup.filter(value => value.blocked !== 'inline') : startup).toEqual([]);
  const other = await context.newPage();await other.goto('/web/#account/typography');await expect(editor(other)).toBeVisible();
  const css = ':root { --font-base: 18px; --font-brand: 2rem; }';
  await editor(page).fill(css);await save(page);await saved(page);
  await expect(other.locator('html')).toHaveCSS('font-size', '18px');await expect(editor(other)).toHaveValue(css);
  const draft = ':root { --font-base: 19px; }';await editor(other).fill(draft);
  await editor(page).fill(':root { --font-base: 20px; --font-brand: 2rem; }');await save(page);await saved(page);
  await expect(other.locator('html')).toHaveCSS('font-size', '20px');await expect(editor(other)).toHaveValue(draft);
  await editor(other).fill(await editor(page).inputValue());
  for (const path of ['/admin/', '/web/project-report.html', '/web/']) {
    await other.goto(path);await expect(other.locator('html')).toHaveCSS('font-size', '20px');
    await expect(other.locator('.brand')).toHaveCSS('font-size', '40px');
    const mainPage=path==='/web/';
    if(mainPage){
      await expect(other.locator('#workspace')).toBeVisible();
      await other.locator('[data-tab=notes]').click();await expect(other.locator('#notes-panel')).toBeVisible();
    }
    // A full main-page reload has the same startup baseline as the initial
    // sign-in page; separate entry pages must still have no policy violations.
    expect(await other.evaluate(() => window.typographyPolicyViolations),path).toEqual(mainPage?startup:[]);
  }
  await page.getByRole('button', {name: 'Reset font sizes', exact: true}).click();
  await expect(page.locator('html')).toHaveCSS('font-size', '14px');
  await other.bringToFront();await other.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  await expect(other.locator('html')).toHaveCSS('font-size', '14px');
  expect(await page.evaluate(() => window.typographyPolicyViolations)).toEqual(startup);await other.close();
});

test('stale and uncertain account font saves keep the draft and never overwrite or retry a peer change', async ({page, request}) => {
  await openSettings(page, request);const draft = ':root { --font-base: 19px; }';await editor(page).fill(draft);
  const peer = ':root { --font-base: 20px; }';expect((await putProfile(page, {typographyCss: peer})).ok()).toBeTruthy();
  await expect(page.locator('html')).toHaveCSS('font-size', '20px');await save(page);
  await expect(page.locator('.form-feedback')).toContainText('Your draft is kept');await expect(editor(page)).toHaveValue(draft);
  expect((await profile(page)).typographyCss).toBe(peer);
  page.once('dialog', dialog => dialog.accept());await page.getByRole('button', {name: 'Reload current values', exact: true}).click();
  await expect(editor(page)).toHaveValue(peer);await editor(page).fill(draft);
  let writes = 0;await page.route('**/api/v1/me', route => {
    if (route.request().method() !== 'PUT') return route.continue();writes++;return route.abort('failed');
  });
  await save(page);await expect(page.locator('.form-feedback')).toContainText('The outcome is unknown');
  await expect(editor(page)).toHaveValue(draft);await expect(page.locator('html')).toHaveCSS('font-size', '20px');
  await expect(page.getByRole('button', {name: 'Save font sizes', exact: true})).toBeDisabled();
  await expect(page.getByRole('button', {name: 'Reset font sizes', exact: true})).toBeDisabled();
  expect((await profile(page)).typographyCss).toBe(peer);expect(writes).toBe(1);
});

test('account typography ignores legacy browser-wide CSS and works with browser storage unavailable', async ({page, request}) => {
  const user = await createUser(request);
  await page.addInitScript(() => {
    localStorage.setItem('timekeeper.typography', ':root { --font-base: 30px; }');
    Storage.prototype.getItem = Storage.prototype.setItem = () => {throw new DOMException('Unavailable', 'SecurityError');};
  });
  await signIn(page, user);await expect(page.locator('html')).toHaveCSS('font-size', '14px');
  const css = ':root { --font-base: 18px; }';await editor(page).fill(css);await save(page);await saved(page);
  await page.reload();await expect(editor(page)).toHaveValue(css);await expect(page.locator('html')).toHaveCSS('font-size', '18px');
});

test('invalid CSS stored through the profile API remains inert and can be corrected in the editor', async ({page, request}) => {
  await openSettings(page, request);const invalid = ':root { --font-base: 30px; background: red; } body { display: none; }';
  expect((await putProfile(page, {typographyCss: invalid})).ok()).toBeTruthy();await page.reload();
  await expect(editor(page)).toHaveValue(invalid);await expect(page.locator('html')).toHaveCSS('font-size', '14px');
  await editor(page).fill(':root { --font-base: 18px; }');await save(page);await saved(page);
  await expect(page.locator('html')).toHaveCSS('font-size', '18px');
});

test('typography reaches shared formatted Markdown headings and plain source editors', async ({page, request}) => {
  await openSettings(page, request);
  await editor(page).fill(':root { --font-base: 18px; --font-markdown-h1: 2rem; --font-markdown-source: 1.25rem; }');await save(page);await saved(page);
  await page.goto('/web/#today');const field=page.locator('#today-note-editor');
  await field.getByRole('button', {name: 'Markdown source', exact: true}).click();
  const source=field.locator('.journal-source textarea');await source.fill('# Typography heading\n\nSource text.\n');
  await expect(source).toHaveCSS('font-size', '22.5px');
  await field.getByRole('button', {name: 'Formatted', exact: true}).click();
  await expect(field.locator('.ProseMirror h1')).toHaveCSS('font-size', '36px');
});

test('typography settings fit mobile screens, support keyboard editing and are accessible in every theme', async ({page, request, browserName}) => {
  await openSettings(page, request);
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({width, height: 900});expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    const bounds=await editor(page).boundingBox(),heading=await page.locator('.account-card h2').boundingBox();expect(Math.abs(bounds.width-heading.width)).toBeLessThan(2);
    await expect(page.locator('.css-editor')).toHaveClass(/is-highlighted/);
    await editor(page).focus();await page.keyboard.press(browserName === 'webkit' && process.platform === 'darwin' ? 'Alt+Tab' : 'Tab');
    await expect(page.getByRole('button', {name: 'Save font sizes', exact: true})).toBeFocused();
    for (const theme of ['timekeeper', 'timekeeper-dark', 'timekeeper-reinvented', 'timekeeper-reinvented-dark', 'solarized-light', 'solarized-dark', 'nord', 'dracula', 'newspaper']) {
      await page.evaluate(theme => {document.documentElement.dataset.theme = theme;document.documentElement.dataset.bsTheme = ['timekeeper', 'timekeeper-reinvented', 'solarized-light', 'newspaper'].includes(theme) ? 'light' : 'dark';}, theme);
      expect((await new AxeBuilder({page}).include('#account-page').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()).violations, theme).toEqual([]);
    }
    await page.evaluate(() => {document.documentElement.dataset.theme = 'timekeeper';document.documentElement.dataset.bsTheme = 'light';});
    await captureScreenshot(page, {path: `test-results/typography-${width}.png`, fullPage: true});
  }
});

test('CSS colors keep native text editing, literal source and scrolling aligned across widths',async({page,request})=>{
  await openSettings(page,request);const field=editor(page),mirror=page.locator('.css-editor-highlight');
  await expect(mirror.locator('.hljs-comment').first()).toBeAttached();await expect(mirror).toHaveAttribute('aria-hidden','true');
  const source='/* <img src=x onerror="window.cssExecuted=true"> */\n:root {\n  --font-base: 14px;\n  --font-hint: clamp(.875rem, 1vw, 1rem);\n}\n';
  await field.fill(source);await expect(mirror).toContainText('--font-base');await expect(mirror.locator('img,script')).toHaveCount(0);expect(await page.evaluate(()=>window.cssExecuted)).toBeUndefined();
  await field.press('x');await field.press('ControlOrMeta+z');await expect(field).not.toHaveValue(source+'x');await field.press('ControlOrMeta+Shift+z');await expect(field).toHaveValue(source+'x');await field.press('Backspace');await expect(field).toHaveValue(source);
  await field.selectText();expect(await field.evaluate(node=>node.selectionEnd-node.selectionStart)).toBe(source.length);
  const long='/* A wrapped comment with enough words to exercise matching native textarea and syntax mirror wrapping. */\n'.repeat(120)+source;await field.fill(long);
  for(const width of [1440,320]){
    await page.setViewportSize({width,height:900});await field.hover();await page.mouse.wheel(0,800);
    await expect.poll(()=>field.evaluate(node=>node.scrollTop)).toBeGreaterThan(0);
    await expect.poll(async()=>Math.abs(await field.evaluate(node=>node.scrollTop)-await mirror.evaluate(node=>node.scrollTop))).toBeLessThan(2);
    const metrics=await field.evaluate(node=>{const pre=node.parentElement.querySelector('pre'),a=getComputedStyle(node),b=getComputedStyle(pre);return {same:['fontFamily','fontSize','lineHeight','padding','whiteSpace','overflowWrap','tabSize'].every(key=>a[key]===b[key]),width:Math.abs(node.clientWidth-pre.getBoundingClientRect().width),caret:a.caretColor};});expect(metrics.same).toBe(true);expect(metrics.width).toBeLessThan(2);expect(metrics.caret).not.toBe('rgba(0, 0, 0, 0)');
  }
  await page.evaluate(()=>document.documentElement.dir='rtl');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect(await field.evaluate(node=>Math.abs(node.parentElement.querySelector('pre').getBoundingClientRect().left-node.getBoundingClientRect().left-node.clientLeft))).toBeLessThan(2);await expect(field).toHaveValue(long);
  await page.emulateMedia({forcedColors:'active'});await expect(mirror).toHaveCSS('visibility','hidden');expect(await field.evaluate(node=>getComputedStyle(node).color)).not.toBe('rgba(0, 0, 0, 0)');await expect(field).toHaveValue(long);
});

test('CSS editing and saving remain available if syntax coloring cannot load',async({page,request})=>{
  await page.route('**/assets/milkdown/code-highlight.js*',route=>route.abort('failed'));await openSettings(page,request);
  const source=':root { --font-base: 18px; }';await editor(page).fill(source);await expect(page.locator('.css-editor')).not.toHaveClass(/is-highlighted/);expect(await editor(page).evaluate(node=>getComputedStyle(node).color)).not.toBe('rgba(0, 0, 0, 0)');await save(page);await saved(page);expect((await profile(page)).typographyCss).toBe(source);
});

test('read-only sessions use saved account typography but cannot change it and Norwegian settings are localized', async ({page, request}) => {
  const headers = {Authorization: `Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`};
  async function seed(css) {
    const current = await request.get('/api/v1/me', {headers});
    const response = await request.put('/api/v1/me', {headers: {...headers, 'If-Match': current.headers().etag}, data: {zoneId: (await current.json()).zoneId, typographyCss: css}});
    expect(response.ok()).toBeTruthy();
  }
  await seed(':root { --font-base: 18px; }');
  try {
    await page.goto('/web/#account/typography');await page.locator('#auth-mode').selectOption('token');
    await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_READ_TOKEN);await page.locator('#login-form button[type=submit]').click();
    await expect(editor(page)).toBeVisible();await expect(page.locator('html')).toHaveCSS('font-size', '18px');
    await expect(page.getByRole('button', {name: 'Save font sizes', exact: true})).toBeDisabled();
    await expect(page.getByRole('button', {name: 'Reset font sizes', exact: true})).toBeDisabled();
    await page.evaluate(async () => {const {setLocale} = await import('/web/i18n.js');setLocale('nb-NO');location.hash = '#account/profile';});
    await page.getByRole('link', {name: 'Skriftstørrelser', exact: true}).click();await expect(page.getByLabel('CSS for skriftstørrelser')).toBeVisible();
    await expect(page.getByRole('button', {name: 'Lagre skriftstørrelser', exact: true})).toBeDisabled();
  } finally {await seed(null);}
});
