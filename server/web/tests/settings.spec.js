import {captureScreenshot} from './screenshots.js';
import {browserRequest} from './browser-request.js';
import {openAccountMenu} from './navigation.js';
import {test, expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {randomUUID} from 'node:crypto';
const token = process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request, method, path, data, headers = {}) {
  const response = await request.fetch('/api/v1' + path, {method, data, headers: {Authorization: `Bearer ${token}`, ...headers}});
  expect(response.ok(), `${method} ${path}: ${response.status()}`).toBeTruthy(); return response;
}
async function fresh(request) {
  const username = 'settings-' + randomUUID().slice(0, 8), password = 'private settings passphrase';
  const {user} = await (await api(request, 'POST', '/admin/users', {username, password, displayName: username, email: username + '@example.org', administrator: false})).json();
  return {...user, password};
}
async function login(page, user, hash = '') {
  await page.goto('/web/' + hash); await page.locator('#auth-mode').selectOption('login');
  await page.locator('#login-form').getByLabel('Username', {exact: true}).fill(user.username);
  await page.getByLabel('Password', {exact: true}).fill(user.password);
  await page.getByRole('button', {name: 'Sign in', exact: true}).click();
  await expect(page.locator('#owner')).toHaveText(user.displayName || user.username);
  await expect(page.locator('#status')).toHaveText(/^(Up to date|Administration ready\.|Administration unavailable\.)$/);
}
const key = user => Buffer.from(user.ownerId).toString('base64url');
async function ready(page) {await expect(page.locator('#account-content')).toHaveAttribute('aria-busy', 'false');}
async function ownHeaders(page) {
  const account = await (await browserRequest(page).get('/api/v1/auth/session')).json();
  return {'X-CSRF-Token': account.csrf, 'X-Timekeeper-Owner': key(account)};
}
test('My Account direct links, mobile layouts and Back navigation preserve running timers', async ({page, request}) => {
  const user = await fresh(request); await login(page, user, '#account/profile'); await ready(page);
  await expect(page.locator('#account-heading')).toBeFocused();
  await expect(page.locator('#workspace')).not.toBeVisible(); await expect(page.locator('#users-link')).not.toBeVisible();
  const headers = await ownHeaders(page);
  const client = await (await browserRequest(page).post('/api/v1/clients', {headers, data: {name: 'Private timer client', color: '#176653'}})).json();
  const running = await browserRequest(page).post('/api/v1/work-sessions/start', {headers: {...headers, 'Idempotency-Key': randomUUID()}, data: {clientId: client.id, summary:'Private running session'}});
  expect(running.status()).toBe(201); const session = await running.json();
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({width, height: 900});
    for (const part of ['Password', 'API tokens', 'Profile']) {
      await page.getByRole('link', {name: part, exact: true}).click(); await ready(page);
      await expect(page.getByRole('link', {name: part, exact: true})).toHaveAttribute('aria-current', 'page');
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
      const result = await new AxeBuilder({page}).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
      expect(result.violations.map(value => value.id)).toEqual([]);
    }
    await captureScreenshot(page,{path: `test-results/my-account-profile-${width}.png`, fullPage: true});
  }
  await page.getByLabel('Time zone', {exact: true}).fill('Invalid/Zone'); await page.getByRole('button', {name: 'Save time zone', exact: true}).click();
  await expect(page.locator('#account-content')).toContainText('Enter a valid time zone');
  const deviceZone = await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone);
  await page.getByRole('button', {name: /Use this device's time zone/}).click(); await page.getByRole('button', {name: 'Save time zone', exact: true}).click();
  await expect(page.locator('#account-content')).toContainText('Time zone saved');
  expect((await (await browserRequest(page).get('/api/v1/me')).json()).zoneId).toBe(deviceZone);
  expect((await (await browserRequest(page).get('/api/v1/work-sessions/' + session.id)).json()).start).toBe(session.start);
  await page.getByRole('link', {name: 'Password', exact: true}).click(); await ready(page);
  await expect(page).toHaveURL(/#account\/password$/); await expect(page.getByLabel('Current password', {exact: true})).toBeVisible();
  await page.goBack(); await expect(page.getByLabel('Account username', {exact: true})).toHaveValue(user.username);
  await page.reload(); await ready(page); await expect(page.locator('#account-heading')).toHaveText('My Account');
  await page.getByRole('link', {name: 'Back to time tracking', exact: true}).click();
  await expect(page.locator('#workspace')).toBeVisible();
  const active = await (await browserRequest(page).get('/api/v1/work-sessions/active')).json(); expect(active.id).toBe(session.id);
  const current = await browserRequest(page).get('/api/v1/work-sessions/' + session.id);
  expect((await browserRequest(page).post(`/api/v1/work-sessions/${session.id}/stop`, {headers: {...headers, 'If-Match': current.headers().etag, 'Idempotency-Key': randomUUID()}})).ok()).toBeTruthy();
});
test('profile drafts survive cancelled navigation and stale saves; blank display name uses username', async ({page, request}) => {
  const user = await fresh(request); await login(page, user, '#account/profile'); await ready(page);
  await page.getByLabel('Display name (optional)', {exact: true}).fill('My unsaved name');
  page.once('dialog', dialog => dialog.dismiss()); await page.getByRole('link', {name: 'Password', exact: true}).click();
  await expect(page.getByLabel('Display name (optional)', {exact: true})).toHaveValue('My unsaved name');
  const loaded = await api(request, 'GET', '/admin/users/' + key(user));
  await api(request, 'PUT', '/admin/users/' + key(user), {username: user.username, displayName: 'Peer name', enabled: true, administrator: false}, {'If-Match': loaded.headers().etag});
  await page.getByRole('button', {name: 'Save profile', exact: true}).click();
  await expect(page.locator('#account-content')).toContainText('Your draft is kept');
  await expect(page.getByLabel('Display name (optional)', {exact: true})).toHaveValue('My unsaved name');
  page.once('dialog', dialog => dialog.accept()); await page.getByRole('button', {name: 'Reload current values', exact: true}).click();
  await expect(page.getByLabel('Display name (optional)', {exact: true})).toHaveValue('Peer name');
  await page.getByLabel('Display name (optional)', {exact: true}).fill('');
  await page.getByRole('button', {name: 'Save profile', exact: true}).click(); await expect(page.locator('#owner')).toHaveText(user.username);
  await expect(page.locator('#account-content')).toContainText('Profile saved.');
});
test('Users page protects the last administrator, keeps stale drafts and confirms access changes', async ({page, request}) => {
  const user = await fresh(request);
  await login(page, {username: 'browser-admin', password: process.env.TIMEKEEPER_WEB_PASSWORD}, '#users'); await ready(page);
  const self = (await (await api(request, 'GET', '/auth/account')).json());
  await page.locator('#account-content tr').filter({hasText: 'browser-admin'}).getByRole('link', {name: 'Edit', exact: true}).click(); await ready(page);
  await expect(page.getByLabel('Enabled', {exact: true})).toBeDisabled(); await expect(page.getByLabel('Administrator', {exact: true})).toBeDisabled();
  await page.getByRole('link', {name: 'Back to users', exact: true}).click(); await ready(page);
  await page.locator('#account-content tr').filter({hasText: user.username}).getByRole('link', {name: 'Edit', exact: true}).click(); await ready(page);
  await page.getByLabel('Display name (optional)', {exact: true}).fill('Retain this user draft');
  const loaded = await api(request, 'GET', '/admin/users/' + key(user));
  await api(request, 'PUT', '/admin/users/' + key(user), {username: user.username, displayName: 'Changed by peer', enabled: true, administrator: false}, {'If-Match': loaded.headers().etag});
  await page.getByRole('button', {name: 'Save user', exact: true}).click(); await expect(page.locator('#account-content')).toContainText('Your draft is kept');
  await expect(page.getByLabel('Display name (optional)', {exact: true})).toHaveValue('Retain this user draft');
  page.once('dialog', dialog => dialog.accept()); await page.getByRole('button', {name: 'Reload current values', exact: true}).click(); await ready(page);
  await page.getByLabel('Enabled', {exact: true}).uncheck();
  page.once('dialog', dialog => dialog.dismiss()); await page.getByRole('button', {name: 'Save user', exact: true}).click();
  expect((await (await api(request, 'GET', '/admin/users/' + key(user))).json()).user.state).toBe('ENABLED');
  page.once('dialog', dialog => dialog.accept()); await page.getByRole('button', {name: 'Save user', exact: true}).click();
  await expect(page.locator('#account-content tr').filter({hasText: user.username})).toContainText('Disabled');
  await page.locator('#account-content tr').filter({hasText: user.username}).getByRole('link', {name: 'Edit', exact: true}).click(); await ready(page);
  await page.getByLabel('Enabled', {exact: true}).check(); await page.getByRole('button', {name: 'Save user', exact: true}).click();
  await expect(page.locator('#account-content tr').filter({hasText: user.username})).toContainText('Enabled');
  expect((await (await api(request, 'GET', '/admin/users/' + key(self))).json()).user.administrator).toBe(true);
  await captureScreenshot(page,{path: 'test-results/users-desktop.png', fullPage: true});
  await page.setViewportSize({width: 390, height: 844}); await captureScreenshot(page,{path: 'test-results/users-mobile.png', fullPage: true});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  const result = await new AxeBuilder({page}).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze(); expect(result.violations).toEqual([]);
});
test('personal tokens copy distinct secrets and support confirmed revocation and deletion', async ({page, request}) => {
  const user = await fresh(request); await login(page, user, '#account/tokens'); await ready(page);
  let failList = false;
  page.on('response', response => {if (response.request().method() === 'POST' && response.url().endsWith('/auth/tokens')) failList = true;});
  await page.route('**/api/v1/auth/tokens', async route => {
    if (route.request().method() === 'GET' && failList) {failList = false; await route.abort('failed');} else await route.continue();
  });
  await page.evaluate(() => {Object.defineProperty(navigator, 'clipboard', {value: {writeText: async value => {window.testCopiedToken = value;}}});});
  await page.getByLabel('Token name', {exact: true}).fill('Eclipse laptop'); await page.getByLabel('Token access', {exact: true}).selectOption('read');
  await page.getByRole('button', {name: 'Create token', exact: true}).click(); const secret = await page.getByLabel('New API token', {exact: true}).inputValue(); expect(secret).toMatch(/^tkp_/);
  await expect(page.locator('#account-content')).toContainText('Token created. Unable to refresh');
  await page.getByRole('button', {name: 'Refresh token list', exact: true}).click(); await ready(page);
  await page.getByRole('button', {name: 'Copy token', exact: true}).click(); expect(await page.evaluate(() => window.testCopiedToken)).toBe(secret);
  const row = page.locator('#account-content tr').filter({hasText: 'Eclipse laptop'}); await expect(row).toContainText('Read-only'); await expect(row).toContainText('Never used');
  await page.getByRole('link', {name: 'Profile', exact: true}).click(); await ready(page); await expect(page.getByLabel('New API token', {exact: true})).toHaveCount(0);
  await page.getByRole('link', {name: 'API tokens', exact: true}).click(); await ready(page);
  await page.getByLabel('Token name', {exact: true}).fill('Eclipse desktop');
  await page.getByRole('button', {name: 'Create token', exact: true}).click();
  await expect(page.locator('#account-content')).toContainText('Token created. Unable to refresh');
  const secondSecret = await page.getByLabel('New API token', {exact: true}).inputValue(); expect(secondSecret).not.toBe(secret);
  await page.getByRole('button', {name: 'Refresh token list', exact: true}).click(); await ready(page);
  await page.getByRole('button', {name: 'Copy token', exact: true}).click(); expect(await page.evaluate(() => window.testCopiedToken)).toBe(secondSecret);
  expect((await request.get('/api/v1/me', {headers: {Authorization: `Bearer ${secondSecret}`}})).status()).toBe(200);
  await page.getByRole('button', {name: 'Done', exact: true}).click();
  await page.setViewportSize({width: 390, height: 844});
  const secondRow = page.locator('#account-content tr').filter({hasText: 'Eclipse desktop'});
  await secondRow.getByRole('button', {name: 'Delete', exact: true}).click(); await expect(secondRow).toContainText('Delete “Eclipse desktop”?');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  const accessibility = await new AxeBuilder({page}).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze(); expect(accessibility.violations).toEqual([]);
  await captureScreenshot(page,{path: 'test-results/personal-token-delete-mobile.png', fullPage: true});
  await secondRow.getByRole('button', {name: 'Cancel', exact: true}).click(); await expect(secondRow.getByRole('button', {name: 'Revoke', exact: true})).toBeVisible();
  await row.getByRole('button', {name: 'Revoke', exact: true}).click(); await expect(row).toContainText('Revoke “Eclipse laptop”?');
  await row.getByRole('button', {name: 'Cancel', exact: true}).click();
  expect((await request.get('/api/v1/me', {headers: {Authorization: `Bearer ${secret}`}})).status()).toBe(200);
  await row.getByRole('button', {name: 'Revoke', exact: true}).click(); await row.getByRole('button', {name: 'Revoke token', exact: true}).click(); await expect(row).toContainText('Revoked');
  expect((await request.get('/api/v1/me', {headers: {Authorization: `Bearer ${secret}`}})).status()).toBe(401);
  expect((await request.get('/api/v1/me', {headers: {Authorization: `Bearer ${secondSecret}`}})).status()).toBe(200);
  await row.getByRole('button', {name: 'Delete', exact: true}).click(); await row.getByRole('button', {name: 'Delete token', exact: true}).click(); await expect(row).toHaveCount(0);
  await secondRow.getByRole('button', {name: 'Delete', exact: true}).click(); await secondRow.getByRole('button', {name: 'Delete token', exact: true}).click(); await expect(secondRow).toHaveCount(0);
  await expect(page.locator('#account-content')).toContainText('No API tokens yet.');
  expect((await request.get('/api/v1/me', {headers: {Authorization: `Bearer ${secondSecret}`}})).status()).toBe(401);
  expect(await page.evaluate(() => ({local: localStorage.length, session: sessionStorage.length}))).toEqual({local: 0, session: 0});
});

test('token sign-in accepts surrounding pasted whitespace and reports malformed tokens as authentication failures', async ({page, request}) => {
  const user = await fresh(request); await login(page, user, '#account/tokens'); await ready(page);
  await page.getByLabel('Token name', {exact: true}).fill('Browser sign-in');
  await page.getByRole('button', {name: 'Create token', exact: true}).click();
  const secret = await page.getByLabel('New API token', {exact: true}).inputValue();
  await page.getByRole('button', {name: 'Done', exact: true}).click();
  await openAccountMenu(page); await page.getByRole('button', {name: 'Sign out', exact: true}).click();
  await expect(page.locator('#login')).toBeVisible();
  await page.locator('#auth-mode').selectOption('token'); await page.getByLabel('Access token', {exact: true}).fill('  '+secret+'  ');
  await page.getByRole('button', {name: 'Sign in', exact: true}).click(); await expect(page.locator('#owner')).toHaveText(user.displayName);
  await openAccountMenu(page); await page.getByRole('button', {name: 'Sign out', exact: true}).click();
  await expect(page.locator('#login')).toBeVisible();
  await page.locator('#auth-mode').selectOption('token'); await page.getByLabel('Access token', {exact: true}).fill(secret+' extra');
  await page.getByRole('button', {name: 'Sign in', exact: true}).click();
  await expect(page.locator('#login-error')).toContainText('token contains invalid characters');
  await expect(page.locator('#login-error')).not.toContainText('could not be reached');
});
test('password validation preserves drafts and a successful change revokes only this account credentials', async ({page, request}) => {
  const user = await fresh(request); await login(page, user, '#account/password'); await ready(page);
  const headers = await ownHeaders(page), tokenReply = await browserRequest(page).post('/api/v1/auth/tokens', {headers, data: {name: 'Old password token', permissions: ['read'], expiresAt: null}});
  expect(tokenReply.status()).toBe(201); const secret = (await tokenReply.json()).token;
  await expect(page.getByLabel('Current password', {exact: true})).toHaveAttribute('autocomplete', 'current-password');
  await expect(page.locator('#account-content').getByLabel('New password', {exact: true})).toHaveAttribute('autocomplete', 'new-password');
  await page.getByLabel('Current password', {exact: true}).fill('incorrect current password'); await page.locator('#account-content').getByLabel('New password', {exact: true}).fill('a replacement private password');
  await page.locator('#account-content').getByLabel('Confirm new password', {exact: true}).fill('different replacement password'); await page.getByRole('button', {name: 'Change password and sign out', exact: true}).click();
  await expect(page.locator('#account-content')).toContainText('Passwords do not match');
  await page.locator('#account-content').getByLabel('Confirm new password', {exact: true}).fill('a replacement private password'); await page.getByRole('button', {name: 'Show new password', exact: true}).click();
  await expect(page.locator('#account-content').getByLabel('New password', {exact: true})).toHaveAttribute('type', 'text');
  await page.getByRole('button', {name: 'Change password and sign out', exact: true}).click(); await expect(page.locator('#account-content')).toContainText('current password was not accepted');
  await page.getByLabel('Current password', {exact: true}).fill(user.password); await page.getByRole('button', {name: 'Change password and sign out', exact: true}).click();
  await expect(page.locator('#login')).toBeVisible(); await expect(page.locator('#account-content')).toBeEmpty(); await expect(page.locator('#login-error')).toContainText('Password changed');
  expect((await request.get('/api/v1/me', {headers: {Authorization: `Bearer ${secret}`}})).status()).toBe(401);
  expect((await api(request, 'GET', '/me')).ok()).toBeTruthy();
  await login(page, {...user, password: 'a replacement private password'});
});
test('standard users cannot open Users directly and a late account response cannot survive sign-out', async ({page, request}) => {
  const user = await fresh(request); await login(page, user, '#users'); await ready(page);
  await expect(page.locator('#users-link')).not.toBeVisible(); await expect(page.locator('#account-content')).toContainText('Administrator access is required');
  expect((await browserRequest(page).get('/api/v1/admin/users')).status()).toBe(403);
  await page.goto('/web/');await expect(page.locator('#status')).toHaveText('Up to date');
  await page.evaluate(() => {
    const fetch = window.fetch;
    window.fetch = async (...args) => {
      const response = await fetch(...args);
      if (args[0] === '/api/v1/auth/account') {window.accountReplyCaptured = true; await new Promise(resolve => {window.releaseAccountReply = resolve;});}
      return response;
    };
  });
  await openAccountMenu(page);await page.getByRole('link', {name: 'My Account', exact: true}).click(); await expect.poll(() => page.evaluate(() => window.accountReplyCaptured)).toBe(true);
  await openAccountMenu(page);await page.getByRole('button', {name: 'Sign out', exact: true}).click(); await expect(page.locator('#login')).toBeVisible();
  await page.evaluate(() => window.releaseAccountReply()); await expect(page.locator('#account-content')).toBeEmpty(); await expect(page.locator('#account-page')).not.toBeVisible();
});
test('a lost token-creation reply requires review and never repeats the write', async ({page, request}) => {
  const user = await fresh(request); await login(page, user, '#account/tokens'); await ready(page);
  let writes = 0;
  page.on('request', request => {if (request.method() === 'POST' && request.url().endsWith('/auth/tokens')) writes++;});
  await page.route('**/api/v1/auth/tokens', async route => {
    if (route.request().method() !== 'POST') {await route.continue(); return;}
    const response = await route.fetch(); expect(response.status()).toBe(201); await route.abort('failed');
  });
  await page.getByLabel('Token name', {exact: true}).fill('Lost token reply'); await page.getByRole('button', {name: 'Create token', exact: true}).click();
  await expect(page.locator('#account-content')).toContainText('outcome is unknown'); await expect(page.getByRole('button', {name: 'Create token', exact: true})).toBeDisabled(); expect(writes).toBe(1);
  page.once('dialog', dialog => dialog.accept()); await page.getByRole('button', {name: 'Reload current values', exact: true}).click(); await ready(page);
  await expect(page.locator('#account-content tr').filter({hasText: 'Lost token reply'})).toHaveCount(1); await expect(page.getByLabel('New API token', {exact: true})).toHaveCount(0); expect(writes).toBe(1);
});
for (const [mode, title] of [
  ['early', 'old token list reads cannot unlock a pending token write'],
  ['late', 'old token list reads cannot replace a newer acknowledged list'],
  ['unauthorized', 'old token list authorization failures still clear the account']
]) test(title, async ({page, request}) => {
  const late = mode !== 'early';
  const user = await fresh(request); await login(page, user, '#account/tokens'); await ready(page);
  let writes = 0, failList = false, holdList = false, releaseRead, releaseWrite, readArrived, writeArrived;
  const readGate = new Promise(resolve => {releaseRead = resolve;}), writeGate = new Promise(resolve => {releaseWrite = resolve;});
  const reading = new Promise(resolve => {readArrived = resolve;}), writing = new Promise(resolve => {writeArrived = resolve;});
  await page.route('**/api/v1/auth/tokens', async route => {
    if (route.request().method() === 'POST') {
      const response = await route.fetch(); expect(response.status()).toBe(201); ++writes;
      if (writes === 1) failList = true;
      else {writeArrived(); await writeGate;}
      await route.fulfill({response});
    } else if (failList) {failList = false; await route.abort('failed');}
    else if (holdList) {
      holdList = false; const response = await route.fetch(); readArrived(); await readGate;
      if (mode === 'unauthorized') await route.fulfill({status: 401, contentType: 'application/json', body: JSON.stringify({code: 'OWNER_CHANGED'})});
      else await route.fulfill({response});
    } else await route.continue();
  });
  try {
    await page.getByLabel('Token name', {exact: true}).fill('First token'); await page.getByRole('button', {name: 'Create token', exact: true}).click();
    await expect(page.getByRole('button', {name: 'Refresh token list', exact: true})).toBeVisible();
    holdList = true; await page.getByRole('button', {name: 'Refresh token list', exact: true}).click(); await reading;
    await page.getByLabel('Token name', {exact: true}).fill('Second token'); await page.getByRole('button', {name: 'Create token', exact: true}).click(); await writing;
    const second = page.locator('#account-content tr').filter({hasText: 'Second token'});
    if (!late) {
      const reply = page.waitForResponse(response => response.url().endsWith('/auth/tokens') && response.request().method() === 'GET');
      releaseRead(); await reply;
      await page.getByRole('link', {name: 'Profile', exact: true}).click();
      await expect(page.locator('#account-error')).toContainText('Wait for the current change');
      await expect(page.getByLabel('Token name', {exact: true})).toHaveValue('Second token'); await expect(page.getByRole('button', {name: 'Create token', exact: true})).toBeDisabled();
    }
    releaseWrite(); await expect(second).toBeVisible();
    if (late) {
      const reply = page.waitForResponse(response => response.url().endsWith('/auth/tokens') && response.request().method() === 'GET');
      releaseRead(); await reply;
    }
    if (mode === 'unauthorized') {await expect(page.locator('#login')).toBeVisible(); await expect(page.locator('#account-content')).toBeEmpty();}
    else {await ready(page); await expect(second).toBeVisible();}
    expect(writes).toBe(2);
  } finally {releaseRead(); releaseWrite(); await page.unrouteAll({behavior: 'wait'});}
});


test('passkey labels stay separate from password confirmation and keep account draft protection',async({page,request})=>{
  const user=await fresh(request);await login(page,user,'#account/passkeys');await ready(page);
  const label=page.getByLabel('Passkey name',{exact:true}),password=page.getByLabel('Current password',{exact:true});
  await expect(password).toHaveAttribute('autocomplete','current-password');await expect(password).toHaveAttribute('type','password');
  expect(await label.evaluate(input=>({ownForm:!!input.form,credentialFields:input.form.querySelectorAll('input[type=password],input[autocomplete=username],input[autocomplete=current-password]').length,autocomplete:input.form.autocomplete}))).toEqual({ownForm:true,credentialFields:0,autocomplete:'off'});
  await expect(label).toHaveAttribute('autocomplete','off');await expect(label).toHaveAttribute('name','passkey-label');
  expect(await password.evaluate(input=>input.form.querySelector('[name="passkey-label"]'))).toBeNull();
  await label.fill('Personal laptop');
  page.once('dialog',dialog=>dialog.dismiss());await page.getByRole('link',{name:'Profile',exact:true}).click();
  await expect(page).toHaveURL(/#account\/passkeys$/);await expect(label).toHaveValue('Personal laptop');
  page.once('dialog',dialog=>dialog.accept());await page.getByRole('link',{name:'Profile',exact:true}).click();await ready(page);
  await expect(page).toHaveURL(/#account\/profile$/);await expect(label).toHaveCount(0);
});
