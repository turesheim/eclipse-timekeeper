import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {dateInZone, localDateTime, durationNanos, nanosText, decimalHoursText} from '../src/model.js';
const token = process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request, method, path, data, headers = {}) {
  const response = await request.fetch('/api/v1' + path, {method, data, headers: {Authorization: `Bearer ${token}`, ...headers}});
  expect(response.ok(), `${method} ${path}: ${response.status()}`).toBeTruthy(); return response;
}
async function signIn(page, credential = token) {
  await page.goto('/web/'); await page.locator('#auth-mode').selectOption('token'); await page.getByLabel('Access token').fill(credential);
  await page.getByRole('button', {name: 'Sign in', exact: true}).click(); await expect(page.locator('#status')).toHaveText('Up to date');
  await page.locator('[data-tab=week]').click();
}
async function fixture(request) {
  const profile = await api(request, 'GET', '/me'), previous = await profile.json(), records = [];
  await api(request, 'PUT', '/me', {zoneId: 'Europe/Oslo'}, {'If-Match': profile.headers().etag});
  async function create(collection, data) {const value = await (await api(request, 'POST', '/' + collection, data)).json(); records.unshift([collection, value.id]); return value;}
  const client = await create('clients', {name: 'Workweek client ' + randomUUID().slice(0, 6), color: '#6750a4'});
  return {client, create, async close() {
    for (const [collection, id] of records) {const loaded = await api(request, 'GET', `/${collection}/${id}`); await api(request, 'DELETE', `/${collection}/${id}`, undefined, {'If-Match': loaded.headers().etag});}
    const current = await api(request, 'GET', '/me'); await api(request, 'PUT', '/me', {zoneId: previous.zoneId}, {'If-Match': current.headers().etag});
  }};
}
test('Workweek shows client-colored session rows with local DST/week clipping and independent activity totals', async ({page, request}) => {
  const f = await fixture(request);
  try {
    const baseline = await (await api(request, 'GET', '/reports/activities?from=2025-10-19T22:00:00Z&to=2025-10-26T23:00:00Z&zoneId=Europe%2FOslo')).json();
    const activityTotal = durationNanos(baseline.total) + 3600000000000n;
    const session = await f.create('work-sessions', {title: 'Weekend work', clientId: f.client.id, summary: '', start: '2025-10-25T21:30:00Z', end: '2025-10-27T01:30:00Z'});
    const outside = await f.create('work-sessions', {title: 'Earlier week', clientId: f.client.id, summary: '', start: '2025-10-01T08:00:00Z', end: '2025-10-01T09:00:00Z'});
    await f.create('activities', {taskId: null, projectId: null, summary: 'Separate tracked time', labelIds: [], start: '2025-10-26T09:00:00Z', end: '2025-10-26T10:00:00Z'});
    await signIn(page); await page.getByLabel('Week containing', {exact: true}).fill('2025-10-26'); await expect(page.locator('#zone-caption')).toContainText('2025-10-20');
    const row = page.locator(`#week-sessions tr[data-work-session-id="${session.id}"]`);
    await expect(row).toContainText('Weekend work'); await expect(row).toContainText(f.client.name);
    expect(await row.locator('.client-color').evaluate(value => getComputedStyle(value, '::before').backgroundColor)).toBe('rgb(103, 80, 164)');
    await expect(row.locator('td').nth(6)).toHaveText('0h 30m'); await expect(row.locator('td').nth(7)).toHaveText('25h 0m'); await expect(row.locator('td').nth(8)).toHaveText('25h 30m');
    await expect(page.locator(`#week-sessions tr[data-work-session-id="${outside.id}"]`)).toHaveCount(0);
    await expect(page.locator('#week-total')).toHaveText(nanosText(activityTotal));
    await page.getByLabel('Duration format', {exact: true}).selectOption('decimal'); await expect(row.locator('td').nth(8)).toHaveText('25.50h'); await expect(page.locator('#week-total')).toHaveText(decimalHoursText(activityTotal));
    await page.locator('.day button').filter({hasText: '2025-10-21'}).click(); await expect(page.locator('#week-sessions-section')).not.toBeVisible();
    await page.locator('.day button').filter({hasText: '2025-10-21'}).click(); await expect(page.locator('#week-sessions-section')).toBeVisible();
    await page.screenshot({path: 'test-results/workweek-sessions-desktop.png', fullPage: true});
    await page.setViewportSize({width: 390, height: 844}); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    expect(await page.locator('#week-sessions').evaluate(value => value.scrollWidth > value.clientWidth)).toBeTruthy();
    await page.screenshot({path: 'test-results/workweek-sessions-mobile.png', fullPage: true});
    await page.getByLabel('Week containing', {exact: true}).fill('2025-10-27'); await expect(page.locator('#zone-caption')).toContainText('2025-10-27'); await expect(row.locator('td').nth(1)).toHaveText('2.50h'); await expect(row.locator('td').nth(8)).toHaveText('2.50h');
    await page.getByRole('button', {name: 'Sign out', exact: true}).click(); await expect(page.locator('#login')).toBeVisible(); await expect(page.locator('#week-sessions')).toBeEmpty();
  } finally {await f.close();}
});
test('running Workweek sessions remain editable without losing exact times and read-only writes are rejected', async ({page, request}) => {
  const f = await fixture(request);
  try {
    const session = await f.create('work-sessions', {title: 'Running workweek session', clientId: f.client.id, summary: '', start: new Date(Date.now() - 120000).toISOString(), end: null});
    await signIn(page); await page.getByLabel('Week containing', {exact: true}).fill(dateInZone(Date.now(), 'Europe/Oslo'));
    const row = page.locator(`#week-sessions tr[data-work-session-id="${session.id}"]`); await expect(row).toContainText('Running');
    await row.getByRole('button', {name: 'Edit session Running workweek session', exact: true}).click();
    await expect(page.getByLabel('Start (local time)', {exact: true})).toHaveValue(localDateTime(session.start, 'Europe/Oslo'));
    await page.getByLabel('Title', {exact: true}).fill('Updated running session'); await page.locator('#save').click(); await expect(page.locator('#editor')).not.toBeVisible(); await expect(row).toContainText('Updated running session');
    const saved = await (await api(request, 'GET', '/work-sessions/' + session.id)).json(); expect(saved.start).toBe(session.start); expect(saved.end).toBeNull();
    await page.getByRole('button', {name: 'Sign out', exact: true}).click(); await expect(page.locator('#login')).toBeVisible(); await signIn(page, process.env.TIMEKEEPER_WEB_READ_TOKEN);
    await expect(row.getByRole('button', {name: 'Edit session Updated running session', exact: true})).toBeDisabled();
    await expect(row.getByRole('button', {name: 'Delete session Updated running session', exact: true})).toBeDisabled();
    const current = await api(request, 'GET', '/work-sessions/' + session.id);
    const denied = await request.put('/api/v1/work-sessions/' + session.id, {data: {...saved, title: 'Rejected read-only change'}, headers: {Authorization: `Bearer ${process.env.TIMEKEEPER_WEB_READ_TOKEN}`, 'If-Match': current.headers().etag}});
    expect(denied.status()).toBe(403);
    expect((await (await api(request, 'GET', '/work-sessions/' + session.id)).json()).title).toBe('Updated running session');
  } finally {await f.close();}
});
