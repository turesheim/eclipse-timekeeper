import {localDateTime} from '../src/model.js';
import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {readFile, writeFile, rename} from 'node:fs/promises';
const token = process.env.TIMEKEEPER_WEB_TOKEN;
const readToken = process.env.TIMEKEEPER_WEB_READ_TOKEN;
test.beforeAll(async({request})=>{const clients=(await (await api(request,'GET','/clients')).json()).items;if(!clients.length) await api(request,'POST','/clients',{name:'Browser client',color:'#2563eb'});});
async function signIn(page, credential = token) {
  await page.goto('/web/'); await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(credential); await page.getByRole('button', {name:'Sign in', exact:true}).click();
  await expect(page.locator('#status')).toHaveText('Up to date');
}
async function api(request, method, path, data, headers = {}) {
  const response = await request.fetch(`/api/v1${path}`, {method, data, headers:{Authorization:`Bearer ${token}`, ...headers}});
  expect(response.ok()).toBeTruthy(); return response;
}
async function editorTime(request, instant) {
  const profile = await (await api(request, 'GET', '/me')).json();
  return localDateTime(instant, profile.zoneId);
}
async function editSessionFromWorkweek(page, request, session) {
  await page.locator('[data-tab=week]').click();
  await page.getByLabel('Week containing').fill((await editorTime(request,session.start)).slice(0,10));
  await page.locator(`#week-sessions tr[data-work-session-id="${session.id}"]`).getByRole('button',{name:/^Edit session /}).click();
}
async function createTask(request, summary = 'Browser task ' + randomUUID()) {
  return (await api(request, 'POST', '/tasks', {summary, projectId:null, parentTaskId:null, url:null})).json();
}
async function save(page) {
  await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click(); await expect(page.locator('#editor')).not.toBeVisible();
}
async function newNamed(page, tab, singular, name) {
  await page.getByRole('button', {name:tab, exact:true}).click(); await page.getByRole('button', {name:`New ${singular}`, exact:true}).click();
  await page.getByLabel('Name', {exact:true}).fill(name);if(singular==='project') await page.getByLabel('Client',{exact:true}).selectOption({index:1});await save(page);
}
test('account time zone controls local editors and build dates across DST and browser zones', async ({browser, request}) => {
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,timezoneId:'America/Los_Angeles',locale:'en-GB'}), page=await context.newPage();
  const original=await (await api(request,'GET','/me')).json();
  const summary='Local clock '+randomUUID(); let session, activity;
  try {
    await signIn(page);
    await page.getByRole('button',{name:'Time zone',exact:true}).click();
    await page.getByRole('textbox',{name:'Time zone',exact:true}).fill('Europe/Oslo');await save(page);
    await expect(page.locator('#server-build')).toContainText('10:30:00');
    session=await (await api(request,'POST','/work-sessions',{summary,start:'2025-10-25T22:30:00.123456789Z',end:'2025-10-26T02:30:00.987654321Z'})).json();
    await page.getByRole('button',{name:'Refresh',exact:true}).click();
    await editSessionFromWorkweek(page,request,session);
    await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue('2025-10-26T00:30:00.123456789');
    await expect(page.getByLabel('End (local time, blank while running)',{exact:true})).toHaveValue('2025-10-26T03:30:00.987654321');
    await expect(page.locator('#hint-start')).toContainText('Europe/Oslo');
    // A peer's preference change cannot reinterpret this editor's unchanged wall times.
    const profile=await api(request,'GET','/me');
    await api(request,'PUT','/me',{zoneId:'America/New_York'},{'If-Match':profile.headers().etag});
    await expect(page.locator('#zone-caption')).toContainText('America/New_York');
    await page.getByLabel('Summary',{exact:true}).fill(summary+' edited');await save(page);
    let saved=await (await api(request,'GET',`/work-sessions/${session.id}`)).json();
    expect([saved.start,saved.end]).toEqual([session.start,session.end]);
    await page.getByRole('button',{name:'Time zone',exact:true}).click();
    await page.getByRole('textbox',{name:'Time zone',exact:true}).fill('Europe/Oslo');await save(page);
    await editSessionFromWorkweek(page,request,session);
    await page.getByLabel('Start (local time)',{exact:true}).fill('2025-10-26T02:30:00.123456789');
    await page.locator('#save').click();await expect(page.locator('#editor-error')).toContainText('occurs twice');
    await page.getByLabel('Start (local time)',{exact:true}).fill('2025-10-26T02:30:00.123456789+01:00');await save(page);
    saved=await (await api(request,'GET',`/work-sessions/${session.id}`)).json();
    expect(saved.start).toBe('2025-10-26T01:30:00.123456789Z');
    await page.getByLabel('Week containing').fill('2025-10-26');
    await page.getByRole('button',{name:'Add time',exact:true}).click();
    await page.getByLabel('Summary',{exact:true}).fill(summary+' activity');
    await page.getByLabel('Start (local time)',{exact:true}).fill('2025-10-26T02:30:00.123456789+02:00');
    await page.getByLabel('End (local time, blank while tracking)',{exact:true}).fill('2025-10-26T02:30:00.123456790+02:00');await save(page);
    activity=(await (await api(request,'GET','/activities?from=2025-10-25T00:00:00Z&to=2025-10-27T00:00:00Z')).json()).items.find(value=>value.summary===summary+' activity');
    expect(activity.start).toBe('2025-10-26T00:30:00.123456789Z');
    expect(activity.end).toBe('2025-10-26T00:30:00.123456790Z');
    await page.locator('#activities tr').filter({hasText:activity.summary}).getByRole('button',{name:'Edit',exact:true}).click();
    await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue('2025-10-26T02:30:00.123456789+02:00');
    await save(page);
    const unchanged=await (await api(request,'GET',`/activities/${activity.id}`)).json();
    expect([unchanged.start,unchanged.end]).toEqual([activity.start,activity.end]);
    await page.getByRole('button',{name:'Add time',exact:true}).click();
    await page.getByLabel('Start (local time)',{exact:true}).fill('2025-03-30T02:30:00');
    await page.getByLabel('End (local time, blank while tracking)',{exact:true}).fill('2025-03-30T03:30:00');
    await page.locator('#save').click();await expect(page.locator('#editor-error')).toContainText('does not exist');
    await page.getByRole('button',{name:'Cancel',exact:true}).click();
    await page.screenshot({path:'test-results/local-time-zone.png',fullPage:true});
  } finally {
    for (const [collection,value] of [['activities',activity],['work-sessions',session]]) if(value) {
      const read=await api(request,'GET',`/${collection}/${value.id}`);
      await api(request,'DELETE',`/${collection}/${value.id}`,undefined,{'If-Match':read.headers().etag});
    }
    const current=await api(request,'GET','/me');
    await api(request,'PUT','/me',{zoneId:original.zoneId},{'If-Match':current.headers().etag});
    await context.close();
  }
});
test('server build and deployment details describe the actual packaged server after reload', async ({page, request}) => {
  const information = await (await api(request, 'GET', '/server')).json();
  expect(information.commit).toBe(process.env.TIMEKEEPER_WEB_BUILD_COMMIT);
  expect(information.dirty).toBe(false);
  expect(information.deployedAt).toBe('2026-10-13T08:30:00Z');
  expect(information.startedAt).not.toBe(information.deployedAt);
  expect(Date.parse(information.builtAt)).toBeLessThanOrEqual(Date.now());
  await signIn(page);
  await expect(page.locator('#server-build')).toContainText(information.commit.slice(0,8));
  await page.locator('#server-build').click();
  await expect(page.locator('#server-version')).toHaveText(information.version);
  await expect(page.locator('#server-commit')).toHaveText(information.commit);
  await expect(page.locator('#server-deployed')).not.toHaveText('Not recorded');
  await expect(page.locator('#server-started')).not.toHaveText(await page.locator('#server-deployed').textContent());
  await page.screenshot({path:'test-results/server-information-desktop.png', fullPage:true});
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:'test-results/server-information-mobile.png', fullPage:true});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.reload();
  await expect(page.locator('#server-build')).toContainText(information.commit.slice(0,8));
  await page.getByRole('button', {name:'Sign out',exact:true}).click();
  await expect(page.locator('#server-info')).not.toBeVisible();
});
test('core browser workflow manages records, tracks time, and preserves exact reports', async ({page, request}) => {
  const suffix = randomUUID().slice(0,8), project = 'Project ' + suffix, task = 'Task ' + suffix, label = 'Label ' + suffix;
  await signIn(page); await newNamed(page, 'Projects', 'project', project); await newNamed(page, 'Labels', 'label', label);
  await page.getByRole('button', {name:'Tasks', exact:true}).click(); await page.getByRole('button', {name:'New task', exact:true}).click();
  await page.getByLabel('Summary', {exact:true}).fill(task); await page.getByLabel('Project', {exact:true}).selectOption({label:project}); await save(page);
  let taskRow = page.locator('#tasks tr').filter({hasText:task}); await taskRow.getByRole('button', {name:'Start', exact:true}).click();
  await page.locator('#editor').getByLabel(label, {exact:true}).check(); await page.locator('#editor').getByRole('button', {name:'Start', exact:true}).click();
  await expect(page.locator('#editor')).not.toBeVisible(); await expect(page.locator('#active-title')).toHaveText(task);
  await page.getByRole('button', {name:'Stop tracking', exact:true}).click(); await page.locator('#editor').getByRole('button', {name:'Stop', exact:true}).click(); await expect(page.locator('#editor')).not.toBeVisible();
  await page.getByRole('button', {name:'Time zone', exact:true}).click(); await page.getByRole('textbox', {name:'Time zone', exact:true}).fill('Europe/Oslo'); await save(page);
  await page.getByRole('button', {name:'Workweek', exact:true}).click(); await page.getByLabel('Week containing').fill('2025-10-26');
  await expect(page.locator('#zone-caption')).toContainText('2025-10-20'); await page.getByRole('button', {name:'Add time', exact:true}).click();
  await page.getByLabel('Task', {exact:true}).selectOption({label:task}); await page.getByLabel('Summary', {exact:true}).fill('Autumn DST');
  await page.getByLabel('Start (local time)', {exact:true}).fill('2025-10-25T22:00:00Z'); await page.getByLabel('End (local time, blank while tracking)', {exact:true}).fill('2025-10-26T23:00:00Z'); await save(page);
  await expect(page.locator('#week-total')).toHaveText('25h 0m'); await expect(page.locator('#days .day').filter({hasText:'2025-10-26'})).toContainText('25h 0m');
  const activity = page.locator('#activities tr').filter({hasText:'Autumn DST'});
  await activity.getByText('Autumn DST', {exact:true}).click(); await expect(page.locator('#editor')).not.toBeVisible();
  await activity.press('Enter'); await expect(page.locator('#editor')).toBeVisible();
  await page.getByLabel('Summary', {exact:true}).fill('One nanosecond'); await page.getByLabel('Start (local time)', {exact:true}).fill('2025-10-25T22:00:00.123456789Z'); await page.getByLabel('End (local time, blank while tracking)', {exact:true}).fill('2025-10-25T22:00:00.123456790Z'); await save(page);
  await expect(page.locator('#week-total')).toHaveText('0h 0m');
  await page.locator('#activities tr').filter({hasText:'One nanosecond'}).getByRole('button', {name:'Edit', exact:true}).click(); await expect(page.getByLabel('Start (local time)', {exact:true})).toHaveValue(await editorTime(request, '2025-10-25T22:00:00.123456789Z'));
  await page.getByRole('button', {name:'Cancel', exact:true}).click();
  await page.screenshot({path:'test-results/workweek-desktop.png', fullPage:true});
  await page.setViewportSize({width:390, height:844}); await page.screenshot({path:'test-results/workweek-mobile.png', fullPage:true});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  // Dependency rejection keeps the delete confirmation and does not cascade.
  await page.getByRole('button', {name:'Projects', exact:true}).click(); await page.locator('#projects tr').filter({hasText:project}).getByRole('button', {name:'Delete', exact:true}).click();
  await page.locator('#editor').getByRole('button', {name:'Delete', exact:true}).click(); await expect(page.locator('#editor-error')).toContainText('conflicts'); await page.getByRole('button', {name:'Cancel', exact:true}).click();
  await page.getByRole('button', {name:'Workweek', exact:true}).click(); await page.locator('#activities tr').filter({hasText:'One nanosecond'}).getByRole('button', {name:'Delete', exact:true}).click(); await page.locator('#editor').getByRole('button', {name:'Delete', exact:true}).click(); await expect(page.locator('#editor')).not.toBeVisible();
  const tasks = await (await api(request, 'GET', '/tasks')).json(); expect(tasks.items.find(v => v.summary === task)).toBeTruthy();
  // Start/stop interval remains; delete it explicitly before task/project cleanup.
  const id = tasks.items.find(v => v.summary === task).id;
  const activities = await (await api(request,'GET',`/activities?from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z&taskId=${id}`)).json();
  for (const value of activities.items) { const read = await api(request,'GET',`/activities/${value.id}`); await api(request,'DELETE',`/activities/${value.id}`,undefined,{'If-Match':read.headers().etag}); }
  await page.getByRole('button', {name:'Tasks', exact:true}).click(); await taskRow.getByRole('button', {name:'Delete', exact:true}).click(); await page.locator('#editor').getByRole('button', {name:'Delete', exact:true}).click(); await expect(page.locator('#editor')).not.toBeVisible();
  for (const [tabName, container, value] of [['Projects','projects',project], ['Labels','labels',label]]) { await page.getByRole('button', {name:tabName, exact:true}).click(); await page.locator(`#${container} tr`).filter({hasText:value}).getByRole('button', {name:'Delete', exact:true}).click(); await page.locator('#editor').getByRole('button', {name:'Delete', exact:true}).click(); await expect(page.locator('#editor')).not.toBeVisible(); }
});
test('manual intervals spanning midnight create editable daily records with exact DST time and labels', async ({page, request}) => {
  const task = await createTask(request, 'Daily browser task ' + randomUUID());
  const label = (await (await api(request, 'GET', '/labels')).json()).items[0];
  const summary = 'Daily interval ' + randomUUID();
  await signIn(page);
  await page.getByRole('button', {name:'Time zone', exact:true}).click();
  await page.getByRole('textbox', {name:'Time zone', exact:true}).fill('Europe/Oslo'); await save(page);
  await page.getByLabel('Week containing').fill('2025-10-26');
  await page.getByRole('button', {name:'Add time', exact:true}).click();
  await page.getByLabel('Task', {exact:true}).selectOption({label:task.summary});
  await page.getByLabel('Summary', {exact:true}).fill(summary);
  await page.getByLabel('Start (local time)', {exact:true}).fill('2025-10-25T21:59:59.123456789Z');
  await page.getByLabel('End (local time, blank while tracking)', {exact:true}).fill('2025-10-27T22:00:00.987654321Z');
  await page.locator('#editor').getByLabel(label.name, {exact:true}).check(); await save(page);
  const range = `/activities?from=2025-10-25T00:00:00Z&to=2025-10-28T00:00:00Z&taskId=${task.id}`;
  const before = (await (await api(request, 'GET', range)).json()).items.sort((a,b) => a.start.localeCompare(b.start));
  expect(before).toHaveLength(3);
  expect(before.map(v => [v.start,v.end])).toEqual([
    ['2025-10-25T21:59:59.123456789Z','2025-10-25T22:00:00Z'],
    ['2025-10-25T22:00:00Z','2025-10-26T23:00:00Z'],
    ['2025-10-26T23:00:00Z','2025-10-27T22:00:00.987654321Z'],
  ]);
  for (const value of before) { expect(value.summary).toBe(summary); expect(value.manual).toBe(true); expect(value.labelIds).toEqual([label.id]); }
  await expect(page.locator('#activities tr').filter({hasText:summary})).toHaveCount(2);
  await expect(page.locator('#days .day').filter({hasText:'2025-10-26'})).toContainText('25h 0m');
  await page.getByLabel('Week containing').fill('2025-10-27');
  const row = page.locator('#activities tr').filter({hasText:summary}); await expect(row).toHaveCount(1);
  await row.getByRole('button', {name:'Edit', exact:true}).click();
  await expect(page.getByLabel('Start (local time)', {exact:true})).toHaveValue(await editorTime(request, before[2].start));
  await page.getByLabel('End (local time, blank while tracking)', {exact:true}).fill('2025-10-27T22:00:00.987654322Z'); await save(page);
  const after = (await (await api(request, 'GET', range)).json()).items.sort((a,b) => a.start.localeCompare(b.start));
  expect(after.slice(0,2)).toEqual(before.slice(0,2)); expect(after[2].id).toBe(before[2].id);
  expect(after[2].end).toBe('2025-10-27T22:00:00.987654322Z');
  const report = (await (await api(request, 'GET', `/reports/activities?from=2025-10-25T00:00:00Z&to=2025-10-28T00:00:00Z&taskId=${task.id}&zoneId=Europe%2FOslo`)).json());
  expect(report.total).toBe('PT48H1.864197533S');
  for (const value of after) {
    const response = await api(request, 'GET', `/activities/${value.id}`);
    await api(request, 'DELETE', `/activities/${value.id}`, undefined, {'If-Match':response.headers().etag});
  }
  const response = await api(request, 'GET', `/tasks/${task.id}`);
  await api(request, 'DELETE', `/tasks/${task.id}`, undefined, {'If-Match':response.headers().etag});
});
test('live peer changes preserve a draft and a stale save cannot overwrite them', async ({page, request, browser}) => {
  const task = await createTask(request); await signIn(page); await page.getByRole('button', {name:'Tasks', exact:true}).click();
  await page.locator('#tasks tr').filter({hasText:task.summary}).getByRole('button', {name:'Edit', exact:true}).click(); await page.getByLabel('Summary', {exact:true}).fill('My unsaved draft');
  const peer = await browser.newPage({baseURL:process.env.TIMEKEEPER_WEB_URL}); await signIn(peer); await peer.getByRole('button', {name:'Tasks', exact:true}).click();
  await peer.locator('#tasks tr').filter({hasText:task.summary}).getByRole('button', {name:'Edit', exact:true}).click(); await peer.getByLabel('Summary', {exact:true}).fill('Peer edit ' + task.id); await save(peer);
  await expect(page.locator('#tasks')).toContainText('Peer edit ' + task.id); await expect(page.getByLabel('Summary', {exact:true})).toHaveValue('My unsaved draft');
  await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click(); await expect(page.locator('#editor-error')).toContainText('This record changed'); await expect(page.getByLabel('Summary', {exact:true})).toHaveValue('My unsaved draft'); await expect(page.getByLabel('Summary', {exact:true})).toBeEnabled();
  const record = await (await api(request, 'GET', '/tasks/' + task.id)).json(); expect(record.summary).toBe('Peer edit ' + task.id); await peer.close();
});
test('read-only authorization and validation remain visible with the draft intact', async ({page}) => {
  await signIn(page, readToken); await page.getByRole('button', {name:'Projects', exact:true}).click(); await page.getByRole('button', {name:'New project', exact:true}).click(); await page.getByLabel('Name', {exact:true}).fill('Read-only draft'); await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click();
  await expect(page.locator('#editor-error')).toContainText('permission'); await expect(page.getByLabel('Name', {exact:true})).toHaveValue('Read-only draft');
  await page.getByRole('button', {name:'Cancel', exact:true}).click(); await page.getByRole('button', {name:'Sign out', exact:true}).click(); await signIn(page);
  await page.getByRole('button', {name:'Time zone', exact:true}).click(); await page.getByRole('textbox', {name:'Time zone', exact:true}).fill('Invalid/Zone'); await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click(); await expect(page.locator('#editor-error')).toContainText('Check'); await expect(page.getByRole('textbox', {name:'Time zone', exact:true})).toHaveValue('Invalid/Zone');
});
test('lost start/stop replies retry the exact durable command only once', async ({page, request}) => {
  const task = await createTask(request); await signIn(page); await page.getByRole('button', {name:'Tasks', exact:true}).click();
  const keys = [];
  await page.route('**/api/v1/activities/start', async route => { keys.push(route.request().headers()['idempotency-key']); const response = await route.fetch(); expect(response.status()).toBe(201); await route.abort('failed'); }, {times:1});
  await page.locator('#tasks tr').filter({hasText:task.summary}).getByRole('button', {name:'Start', exact:true}).click(); await page.locator('#editor').getByRole('button', {name:'Start', exact:true}).click();
  await expect(page.locator('#editor-notice')).toContainText('outcome is unknown'); await expect(page.getByLabel('Summary', {exact:true})).toBeDisabled();
  page.on('request', request => { if (request.url().endsWith('/activities/start')) keys.push(request.headers()['idempotency-key']); });
  await page.getByRole('button', {name:'Retry same command', exact:true}).click(); await expect(page.locator('#editor')).not.toBeVisible(); expect(new Set(keys).size).toBe(1);
  const active = await (await api(request, 'GET', '/activities/active')).json(); expect(active.taskId).toBe(task.id);
  await page.route('**/api/v1/activities/*/stop', async route => { const response = await route.fetch(); expect(response.ok()).toBeTruthy(); await route.abort('failed'); }, {times:1});
  await page.getByRole('button', {name:'Stop tracking', exact:true}).click(); await page.locator('#editor').getByRole('button', {name:'Stop', exact:true}).click(); await expect(page.locator('#editor-notice')).toContainText('outcome is unknown');
  await page.getByRole('button', {name:'Retry same command', exact:true}).click(); await expect(page.locator('#editor')).not.toBeVisible();
  const activities = await (await api(request,'GET',`/activities?from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z&taskId=${task.id}`)).json(); expect(activities.items).toHaveLength(1); expect(activities.items[0].end).not.toBeNull(); expect(activities.items[0].version).toBe(1);
});
test('lost create reply requires record review before another submission', async ({page, request}) => {
  const name = 'Uncertain project ' + randomUUID(); await signIn(page); await page.getByRole('button', {name:'Projects', exact:true}).click(); await page.getByRole('button', {name:'New project', exact:true}).click(); await page.getByLabel('Name', {exact:true}).fill(name);
  await page.route('**/api/v1/projects', async route => { if (route.request().method() !== 'POST') return route.continue(); const response = await route.fetch(); expect(response.status()).toBe(201); await route.abort('failed'); });
  await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click(); await expect(page.locator('#editor-notice')).toContainText('Cancel and refresh'); await expect(page.locator('#save')).toBeDisabled();
  expect((await (await api(request,'GET','/projects')).json()).items.filter(v => v.name === name)).toHaveLength(1); await page.unroute('**/api/v1/projects'); await page.getByRole('button', {name:'Cancel', exact:true}).click(); await page.getByRole('button', {name:'Refresh', exact:true}).click(); await expect(page.locator('#projects')).toContainText(name);
});
test('same-token owner reassignment cannot switch a live browser session', async ({page, request}) => {
  const file = process.env.TIMEKEEPER_WEB_REGISTRY, original = await readFile(file, 'utf8');
  await signIn(page); await page.getByRole('button', {name:'Projects', exact:true}).click(); await page.getByRole('button', {name:'New project', exact:true}).click(); await page.getByLabel('Name', {exact:true}).fill('Keep in original account');
  try {
    const registry = JSON.parse(original); registry[0].ownerId = 'web-other-owner'; const temporary = file + '.new'; await writeFile(temporary, JSON.stringify(registry), {mode:0o600}); await rename(temporary, file);
    await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click(); await expect(page.locator('#login')).toBeVisible();await expect(page.locator('#workspace')).not.toBeVisible();await expect(page.locator('#fields')).toBeEmpty();
    expect((await (await api(request,'GET','/projects')).json()).items).toHaveLength(0);
  } finally { const temporary = file + '.new'; await writeFile(temporary, original, {mode:0o600}); await rename(temporary, file); }
});
test('reload restores the HttpOnly session and sign-out removes it', async ({page}) => {
  await signIn(page); expect(await page.locator('#token').inputValue()).toBe('');
  expect(await page.evaluate(() => localStorage.getItem('timekeeper.access-token'))).toBeNull();
  const cookie=(await page.context().cookies()).find(value=>value.name==='__Host-timekeeper-session');expect(cookie.httpOnly).toBe(true);expect(cookie.secure).toBe(true);
  expect(await page.evaluate(() => ({keys:Object.keys(localStorage), session:sessionStorage.length}))).toEqual({keys:[], session:0});
  await page.reload(); await expect(page.locator('#workspace')).toBeVisible(); await expect(page.locator('#status')).toHaveText('Up to date');
  await expect(page.locator('#login')).not.toBeVisible(); expect(await page.locator('#token').inputValue()).toBe('');
  await page.getByRole('button', {name:'Sign out', exact:true}).click(); await expect(page.locator('#tasks')).toBeEmpty();
  expect(await page.evaluate(() => localStorage.getItem('timekeeper.access-token'))).toBeNull();
  await page.reload(); await expect(page.locator('#login')).toBeVisible(); await expect(page.locator('#workspace')).not.toBeVisible();
});
test('an invalid remembered token is forgotten and a valid replacement can sign in', async ({page}) => {
  await page.addInitScript(() => localStorage.setItem('timekeeper.access-token', 'invalid-saved-fixture'));
  await page.goto('/web/'); await expect(page.locator('#login-error')).toContainText('no longer authorized');
  expect(await page.evaluate(() => localStorage.getItem('timekeeper.access-token'))).toBeNull();
  await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(token); await page.getByRole('button', {name:'Sign in', exact:true}).click();
  await expect(page.locator('#status')).toHaveText('Up to date');
});
test('blocked legacy storage still allows cookie sign-in and reload', async ({page}) => {
  await page.addInitScript(() => Object.defineProperty(window, 'localStorage', {get() {throw new DOMException('Blocked', 'SecurityError');}}));
  await signIn(page);await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');
  await page.getByRole('button', {name:'Sign out', exact:true}).click(); await expect(page.locator('#login')).toBeVisible();await page.reload();await expect(page.locator('#workspace')).not.toBeVisible();
});
test('future starts are rejected without losing browser drafts or changing recorded time', async ({page, request}) => {
  const task = await createTask(request, 'Future boundary ' + randomUUID());
  const future = new Date(Date.now() + 86400000).toISOString();
  const later = new Date(Date.now() + 86460000).toISOString();
  const before = await (await api(request, 'GET', '/data/export')).json();
  await signIn(page); await page.getByRole('button', {name:'Add time', exact:true}).click();
  await page.getByLabel('Task', {exact:true}).selectOption({label:task.summary});
  await page.getByLabel('Summary', {exact:true}).fill('Keep future draft');
  await page.getByLabel('Start (local time)', {exact:true}).fill(future);
  await page.getByLabel('End (local time, blank while tracking)', {exact:true}).fill(later);
  await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click();
  await expect(page.locator('#editor-error')).toContainText('Check the values');
  await expect(page.getByLabel('Summary', {exact:true})).toHaveValue('Keep future draft');
  expect(await (await api(request, 'GET', '/data/export')).json()).toEqual(before);
  await page.getByRole('button', {name:'Cancel', exact:true}).click();
  const activity = await (await api(request, 'POST', '/activities', {taskId:task.id, start:'2025-10-25T12:00:00Z', end:'2025-10-25T13:00:00Z', summary:'Original recorded time', labelIds:[]})).json();
  await page.getByLabel('Week containing').fill('2025-10-26');
  await page.locator('#activities tr').filter({hasText:'Original recorded time'}).getByRole('button', {name:'Edit', exact:true}).click();
  await page.getByLabel('Start (local time)', {exact:true}).fill(future);
  await page.getByLabel('End (local time, blank while tracking)', {exact:true}).fill(later);
  await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click();
  await expect(page.locator('#editor-error')).toContainText('Check the values');
  expect(await (await api(request, 'GET', '/activities/' + activity.id)).json()).toEqual(activity);
  await page.getByRole('button', {name:'Cancel', exact:true}).click();
  const read = await api(request, 'GET', '/activities/' + activity.id);
  await api(request, 'DELETE', '/activities/' + activity.id, undefined, {'If-Match':read.headers().etag});
  const taskRead = await api(request, 'GET', '/tasks/' + task.id);
  await api(request, 'DELETE', '/tasks/' + task.id, undefined, {'If-Match':taskRead.headers().etag});
});
test('offline reconnect catches missed peer writes without replacing an open draft', async ({page, request}) => {
  const task = await createTask(request); await signIn(page); await page.getByRole('button', {name:'Tasks', exact:true}).click();
  await page.locator('#tasks tr').filter({hasText:task.summary}).getByRole('button', {name:'Edit', exact:true}).click(); await page.getByLabel('Summary', {exact:true}).fill('Offline draft');
  await page.route('**/api/v1/**', route => route.abort('failed'));
  await expect(page.locator('#status')).toContainText('Reconnecting');
  const read = await api(request, 'GET', '/tasks/' + task.id);
  await api(request, 'PUT', '/tasks/' + task.id, {summary:'Missed peer ' + task.id, projectId:null, parentTaskId:null, url:null}, {'If-Match':read.headers().etag});
  await expect(page.getByLabel('Summary', {exact:true})).toHaveValue('Offline draft');
  await page.unroute('**/api/v1/**'); await expect(page.locator('#status')).toHaveText('Up to date'); await expect(page.locator('#tasks')).toContainText('Missed peer ' + task.id);
  await expect(page.getByLabel('Summary', {exact:true})).toHaveValue('Offline draft');
  await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click(); await expect(page.locator('#editor-error')).toContainText('This record changed');
});

test('workweek groups linked tasks under projects with seven day columns and concise totals', async ({page, request}) => {
  const suffix = randomUUID().slice(0,8);
  const profile = await api(request,'GET','/me');
  await api(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag});
  const project = await (await api(request,'POST','/projects',{name:'Workweek layout '+suffix})).json();
  const jiraUrl = 'https://jira.example/browse/PLATFORM-3396';
  const parent = await (await api(request,'POST','/tasks',{summary:'Clean registry '+suffix,projectId:project.id,parentTaskId:null,url:null,externalReferences:[{providerId:'jira',repositoryId:'https://jira.example',externalId:'10017',externalUrl:jiraUrl}]})).json();
  const githubUrl = 'https://github.com/example/project/issues/17';
  const child = await (await api(request,'POST','/tasks',{summary:'Check registry '+suffix,projectId:project.id,parentTaskId:parent.id,url:null,externalReferences:[{providerId:'github',repositoryId:'https://github.com/example/project',externalId:'17',externalUrl:githubUrl}]})).json();
  const activity = await (await api(request,'POST','/activities',{taskId:child.id,summary:'DST review '+suffix,start:'2025-10-25T22:00:00.123456789Z',end:'2025-10-26T23:00:00Z',labelIds:[]})).json();
  await signIn(page); await page.getByLabel('Week containing').fill('2025-10-26');
  await expect(page.locator('#zone-caption')).toContainText('2025-10-20');
  expect(await page.evaluate(async () => {
    await document.fonts.load('900 18px "Font Awesome 7 Free"');
    return [...document.fonts].some(font => font.family.includes('Font Awesome 7 Free') && font.status === 'loaded');
  })).toBeTruthy();
  const iconAction = page.locator('#activities tr[data-kind=task]').first().getByRole('button', {name:'Edit task', exact:true});
  await expect(iconAction).toHaveAttribute('title', 'Edit task');
  await expect(iconAction.locator('.icon')).toHaveAttribute('aria-hidden', 'true');
  expect(await iconAction.evaluate(node => !node.textContent.includes('Edit task') && node.getBoundingClientRect().width >= 32)).toBeTruthy();
  expect(await page.locator('.tree-toggle').first().evaluate(node => node.getBoundingClientRect().height >= 32)).toBeTruthy();
  const table = page.locator('#activities table');
  await expect(table.locator('th')).toHaveCount(11);
  await expect(table.locator('tr[data-kind=project]').filter({hasText:project.name})).toContainText('24h 59m 59s');
  const parentRow = table.locator('tr[data-kind=task]').filter({hasText:'PLATFORM-3396: Clean registry '+suffix});
  await expect(parentRow.getByRole('link')).toHaveAttribute('href',jiraUrl);
  await expect(parentRow).toContainText('24h 59m 59s');
  await expect(parentRow.locator('td').nth(8)).toHaveText('24h 59m 59s');
  const childRow = table.locator('tr[data-kind=task]').filter({hasText:'#17: Check registry '+suffix});
  await expect(childRow.getByRole('link')).toHaveAttribute('href',githubUrl);
  await expect(childRow.getByRole('link')).toHaveAttribute('rel','noopener noreferrer');
  const intervalRow = table.locator(`tr[data-activity-id="${activity.id}"]`);
  await expect(intervalRow).toHaveAttribute('data-task-id',child.id);
  await expect(intervalRow).toContainText('DST review '+suffix);
  await expect(page.locator('#week-total')).toHaveText('24h 59m 59s');
  await parentRow.getByRole('button',{name:'Collapse PLATFORM-3396: Clean registry '+suffix,exact:true}).click();
  await expect(intervalRow).toHaveCount(0); await expect(parentRow).toContainText('24h 59m 59s');
  await parentRow.getByRole('button',{name:'Expand PLATFORM-3396: Clean registry '+suffix,exact:true}).click();
  await expect(intervalRow).toBeVisible();
  await page.getByRole('button',{name:'Hide activities',exact:true}).click();
  await expect(intervalRow).toHaveCount(0);await expect(parentRow).toBeVisible();await expect(childRow).toBeVisible();
  await page.getByRole('button',{name:'Show activities',exact:true}).click();await expect(intervalRow).toBeVisible();
  await page.getByLabel('Duration format',{exact:true}).selectOption('decimal');
  await expect(page.locator('#week-total')).toHaveText('25.00h');await expect(parentRow.locator('td').nth(8)).toHaveText('25.00h');
  await page.getByLabel('Duration format',{exact:true}).selectOption('clock');
  await intervalRow.getByRole('button',{name:'Edit',exact:true}).click();
  await expect(page.getByLabel('Task',{exact:true})).toHaveValue(child.id);
  await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue(await editorTime(request, '2025-10-25T22:00:00.123456789Z'));
  await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await page.screenshot({path:'test-results/workweek-hierarchy-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.screenshot({path:'test-results/workweek-hierarchy-mobile.png',fullPage:true});
  const report = await (await api(request,'GET','/reports/activities?from=2025-10-19T22:00:00Z&to=2025-10-26T23:00:00Z&zoneId=Europe%2FOslo')).json();
  expect(report.total).toBe('PT24H59M59.876543211S');
  await page.getByRole('button',{name:'Tasks',exact:true}).click();
  await expect(page.locator('#tasks').getByRole('link',{name:'PLATFORM-3396: Clean registry '+suffix,exact:true})).toHaveAttribute('href',jiraUrl);
  await page.locator('#tasks tr').filter({hasText:'#17: Check registry '+suffix}).getByRole('button',{name:'Start',exact:true}).click();
  await expect(page.getByLabel('Task',{exact:true})).toHaveValue(child.id);
  await expect(page.getByLabel('Task',{exact:true}).locator('option:checked')).toHaveText('#17: Check registry '+suffix);
  await page.locator('#editor').getByRole('button',{name:'Start',exact:true}).click();
  await expect(page.locator('#editor')).not.toBeVisible();
  await expect(page.locator('#active-title').getByRole('link')).toHaveAttribute('href',githubUrl);
  await expect(page.locator('#active-title')).toHaveText('#17: Check registry '+suffix);
  await page.getByRole('button',{name:'Stop tracking',exact:true}).click();
  await page.locator('#editor').getByRole('button',{name:'Stop',exact:true}).click();
  await expect(page.locator('#editor')).not.toBeVisible();
});

test('live label changes update an open tracking draft without dropping selected references', async ({page, request}) => {
  const suffix = randomUUID().slice(0,8), task = await createTask(request);
  const original = await (await api(request,'POST','/labels',{name:'Original '+suffix,color:'#123456'})).json();
  await signIn(page); await page.getByRole('button',{name:'Tasks',exact:true}).click();
  await page.locator('#tasks tr').filter({hasText:task.summary}).getByRole('button',{name:'Start',exact:true}).click();
  await page.getByLabel('Summary',{exact:true}).fill('Keep my tracking draft');
  await page.getByLabel(original.name,{exact:true}).check();
  const added = await (await api(request,'POST','/labels',{name:'Peer addition '+suffix,color:'#abcdef'})).json();
  await expect(page.getByLabel(added.name,{exact:true})).toBeVisible();
  await expect(page.getByLabel(added.name,{exact:true})).not.toBeChecked();
  await expect(page.getByLabel(original.name,{exact:true})).toBeChecked();
  const read = await api(request,'GET','/labels/'+original.id);
  const renamed = await (await api(request,'PUT','/labels/'+original.id,{name:'Peer rename '+suffix,color:'#765432'},{'If-Match':read.headers().etag})).json();
  await expect(page.getByLabel(renamed.name,{exact:true})).toBeChecked();
  const current = await api(request,'GET','/labels/'+original.id);
  await api(request,'DELETE','/labels/'+original.id,undefined,{'If-Match':current.headers().etag});
  await expect(page.getByLabel(renamed.name+' (deleted)',{exact:true})).toBeChecked();
  await expect(page.getByLabel('Summary',{exact:true})).toHaveValue('Keep my tracking draft');
  await page.locator('#editor').getByRole('button',{name:'Start',exact:true}).click();
  await expect(page.locator('#editor-error')).toContainText('no longer exists');
  await expect(page.getByLabel(renamed.name+' (deleted)',{exact:true})).toBeChecked();
  await expect(page.getByLabel('Summary',{exact:true})).toHaveValue('Keep my tracking draft');
  expect((await (await api(request,'GET','/activities?from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z&taskId='+task.id)).json()).items).toHaveLength(0);
  await page.getByRole('button',{name:'Cancel',exact:true}).click();
  const extra = await api(request,'GET','/labels/'+added.id);await api(request,'DELETE','/labels/'+added.id,undefined,{'If-Match':extra.headers().etag});
  const final = await api(request,'GET','/tasks/'+task.id);await api(request,'DELETE','/tasks/'+task.id,undefined,{'If-Match':final.headers().etag});
});

async function removeSessionFixture(request, sessionIds) {
  for (const id of sessionIds) {
    const report = (await (await api(request,'GET',`/work-sessions/${id}/report`)).json());
    for (const value of report.activities) {
      const read = await api(request,'GET',`/activities/${value.id}`);
      await api(request,'DELETE',`/activities/${value.id}`,undefined,{'If-Match':read.headers().etag});
    }
    const read = await api(request,'GET',`/work-sessions/${id}`);
    await api(request,'DELETE',`/work-sessions/${id}`,undefined,{'If-Match':read.headers().etag});
  }
}
test('work session controls start once and stop independently of a task activity', async ({page,request}) => {
  const task = await createTask(request,'Session browser task '+randomUUID());await signIn(page);
  await page.getByRole('button',{name:'Start work session',exact:true}).click();await page.getByLabel('Title',{exact:true}).fill('Browser work session');await page.getByLabel('Client',{exact:true}).selectOption({index:1});await page.locator('#save').click();
  await expect(page.locator('#work-session-state')).toHaveText('Running');await expect(page.locator('#editor')).not.toBeVisible();
  const first = await (await api(request,'GET','/work-sessions/active')).json();
  await page.getByRole('button',{name:'Tasks',exact:true}).click();
  await page.locator('#tasks tr').filter({hasText:task.summary}).getByRole('button',{name:'Start',exact:true}).click();
  await expect(page.locator('#editor').getByLabel('Work session',{exact:true})).toHaveValue(first.id);
  await page.locator('#editor').getByRole('button',{name:'Start',exact:true}).click();await expect(page.locator('#editor')).not.toBeVisible();
  const active = await (await api(request,'GET','/activities/active')).json();expect(active.workSessionId).toBe(first.id);
  await expect(page.locator('#work-session-title')).toHaveText(first.title);
  await expect(page.locator('#work-session-elapsed')).not.toBeEmpty();
  await expect(page.locator('#work-session')).not.toContainText('No associated activities');
  await expect(page.locator('#work-session-choice')).not.toBeVisible();
  const compactHeight=(await page.locator('#work-session').boundingBox()).height;
  const sessionTitleSize=await page.locator('#work-session-title').evaluate(node=>getComputedStyle(node).fontSize);
  const sessionDetailSize=await page.locator('#work-session-current').evaluate(node=>getComputedStyle(node).fontSize);
  for(const tab of ['week','calendar','tasks','clients','projects','labels']) {
    await page.locator(`[data-tab=${tab}]`).click();
    await expect(page.locator('#work-session')).toBeVisible();
    await expect(page.locator('#work-session-state')).toHaveText('Running');
    await expect(page.locator('#active-title')).toHaveText(task.summary);
    expect((await page.locator('#work-session').boundingBox()).height).toBe(compactHeight);
    await expect(page.locator('#active-title')).toHaveCSS('font-size',sessionTitleSize);
    await expect(page.locator('#active-details')).toHaveCSS('font-size',sessionDetailSize);
  }
  const history=await (await api(request,'POST','/work-sessions',{title:'Earlier session',clientId:first.clientId,summary:'History must not replace the live summary',start:'2025-10-20T06:00:00Z',end:'2025-10-20T07:00:00Z'})).json();
  await page.locator('[data-tab=calendar]').click();
  await page.getByRole('button',{name:'Refresh',exact:true}).click();
  await expect(page.locator('#work-session-history')).toHaveCount(0);
  await expect(page.locator('#work-session-title')).toHaveText(first.title);
  await expect(page.locator('#work-session-elapsed')).not.toHaveText('1h 0m');
  await page.getByRole('button',{name:'Edit work session',exact:true}).click();
  await expect(page.getByLabel('Title',{exact:true})).toHaveValue(first.title);
  await expect(page.getByLabel('Summary',{exact:true})).toHaveValue(first.summary);
  await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await page.locator('#work-session').screenshot({path:'test-results/compact-running-session.png'});
  await page.setViewportSize({width:390,height:844});
  await page.locator('#work-session').screenshot({path:'test-results/compact-running-session-mobile.png'});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.getByRole('button',{name:'Stop work session',exact:true}).click();await expect(page.locator('#work-session-state')).toHaveText('Stopped');
  expect((await (await api(request,'GET','/activities/active')).json()).id).toBe(active.id);
  await page.getByRole('button',{name:'Start work session',exact:true}).click();await page.getByLabel('Title',{exact:true}).fill('Browser work session');await page.getByLabel('Client',{exact:true}).selectOption({index:1});await page.locator('#save').click();await expect(page.locator('#work-session-state')).toHaveText('Running');
  const second = await (await api(request,'GET','/work-sessions/active')).json();expect(second.id).not.toBe(first.id);
  await page.getByRole('button',{name:'Stop tracking',exact:true}).click();await page.locator('#editor').getByRole('button',{name:'Stop',exact:true}).click();await expect(page.locator('#editor')).not.toBeVisible();
  expect((await (await api(request,'GET','/work-sessions/active')).json()).id).toBe(second.id);
  await page.getByRole('button',{name:'Stop work session',exact:true}).click();await expect(page.locator('#work-session-state')).toHaveText('Stopped');
  await removeSessionFixture(request,[first.id,second.id,history.id]);
  const read = await api(request,'GET',`/tasks/${task.id}`);await api(request,'DELETE',`/tasks/${task.id}`,undefined,{'If-Match':read.headers().etag});
});
test('session toolbar targets the displayed session and preserves historical tools and read-only access', async ({page,request,browser}) => {
  const username='session-tools-'+randomUUID().slice(0,8), password='independent session toolbar passphrase';
  await api(request,'POST','/auth/users',{username,password,displayName:username,email:username+'@example.org',administrator:false});
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');
  await page.locator('#login-form').getByLabel('Username',{exact:true}).fill(username);await page.getByLabel('Password',{exact:true}).fill(password);await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.locator('#status')).toHaveText('Up to date');
  const account=await (await page.request.get('/api/v1/auth/session')).json();
  const own=async(method,path,data,headers={})=>{
    const response=await page.request.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':account.csrf,...headers}});
    expect(response.ok(),`${method} ${path}: ${response.status()}`).toBeTruthy();return response;
  };
  const tools=page.getByRole('group',{name:'Work session tools',exact:true});
  await expect(page.locator('#work-session-history')).toHaveCount(0);
  for(const action of await tools.getByRole('button').all()) await expect(action).toBeDisabled();
  await expect(page.getByRole('button',{name:'Start work session',exact:true})).toBeEnabled();
  await expect(page.getByRole('button',{name:'Record work session',exact:true})).toBeEnabled();
  const client=await (await own('POST','/clients',{name:'Toolbar client',color:'#6750a4'})).json();
  const history=await (await own('POST','/work-sessions',{title:'Historical toolbar session',clientId:client.id,summary:'Historical record',start:'2025-10-20T08:00:00Z',end:'2025-10-20T09:00:00Z'})).json();
  await page.getByRole('button',{name:'Refresh',exact:true}).click();await expect(page.locator('#work-session-title')).toHaveText(history.title);
  await expect(page.locator('#work-session-elapsed')).toHaveText('1h 0m');
  await page.getByRole('button',{name:'Start work session',exact:true}).click();
  await page.getByLabel('Title',{exact:true}).fill('Current toolbar session');await page.getByLabel('Client',{exact:true}).selectOption(client.id);await page.locator('#save').click();
  await expect(page.locator('#editor')).not.toBeVisible();await expect(page.locator('#work-session-state')).toHaveText('Running');
  const active=await (await own('GET','/work-sessions/active')).json();
  const activity=await (await own('POST','/activities',{taskId:null,summary:'Independent toolbar activity',start:'2025-10-20T08:15:00Z',end:'2025-10-20T08:30:00Z',labelIds:[],workSessionId:null})).json();
  await page.getByRole('button',{name:'Refresh',exact:true}).click();
  // Opening a historical record must not leave an invisible selection behind in the shared panel.
  await page.locator('[data-tab=week]').click();await page.getByLabel('Week containing').fill('2025-10-20');
  await page.locator(`#week-sessions tr[data-work-session-id="${history.id}"]`).getByRole('button',{name:/^Edit session /}).click();
  await expect(page.getByLabel('Title',{exact:true})).toHaveValue(history.title);await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await page.getByRole('button',{name:'Edit work session',exact:true}).click();
  await expect(page.getByLabel('Title',{exact:true})).toHaveValue(active.title);await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await page.getByRole('button',{name:'Add activity to session',exact:true}).click();
  await expect(page.locator('#editor').getByLabel('Work session',{exact:true})).toHaveValue(active.id);await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await page.getByRole('button',{name:'Associate activity',exact:true}).click();
  await expect(page.locator('#editor').getByLabel('Work session',{exact:true})).toHaveValue(active.id);await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await page.getByRole('button',{name:'Delete work session',exact:true}).click();
  await expect(page.locator('#fields')).toContainText(active.title);await page.getByRole('button',{name:'Cancel',exact:true}).click();
  expect((await (await own('GET','/work-sessions/active')).json()).id).toBe(active.id);
  await page.locator('#work-session').screenshot({path:'test-results/session-toolbar-desktop.png'});
  await page.setViewportSize({width:390,height:844});
  for(const tab of ['week','calendar','tasks','clients','projects','labels']) {
    await page.locator(`[data-tab=${tab}]`).click();await expect(tools).toBeVisible();await expect(page.locator('#work-session-title')).toHaveText(active.title);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  }
  await page.locator('#work-session').screenshot({path:'test-results/session-toolbar-mobile.png'});
  await page.getByRole('button',{name:'Stop work session',exact:true}).click();await expect(page.locator('#work-session-state')).toHaveText('Stopped');
  await expect(page.locator('#work-session-title')).toHaveText(active.title);
  await page.getByRole('button',{name:'Delete work session',exact:true}).click();await page.locator('#save').click();await expect(page.locator('#editor')).not.toBeVisible();
  await expect(page.locator('#work-session-title')).toHaveText(history.title);
  expect(await (await own('GET',`/activities/${activity.id}`)).json()).toEqual(activity);
  const read=await (await own('POST','/auth/tokens',{name:'Toolbar read-only',permissions:['read'],expiresAt:null})).json();
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL});
  try {
    const peer=await context.newPage();await signIn(peer,read.token);await expect(peer.locator('#work-session-title')).toHaveText(history.title);
    for(const id of ['session-start','session-new','session-edit','session-delete','session-add','session-associate']) await expect(peer.locator('#'+id)).toBeDisabled();
  } finally {await context.close();}
  await page.locator('[data-tab=week]').click();
  await page.locator(`#week-sessions tr[data-work-session-id="${history.id}"]`).getByRole('button',{name:/^Delete session /}).click();
  await expect(page.locator('#fields')).toContainText(history.title);await page.locator('#save').click();await expect(page.locator('#editor')).not.toBeVisible();
  await expect(page.locator('#work-session-title')).toHaveText('Your work session');
  for(const action of await tools.getByRole('button').all()) await expect(action).toBeDisabled();
});
test('session editors and taskless activities preserve exact overlap accounting and warn outside bounds', async ({page,request}) => {
  const tasksBefore = (await (await api(request,'GET','/tasks')).json()).items.length;await signIn(page);
  await page.getByRole('button',{name:'Record work session',exact:true}).click();await page.getByLabel('Title',{exact:true}).fill('Recorded work session');await page.getByLabel('Client',{exact:true}).selectOption({index:1});
  await page.getByLabel('Summary',{exact:true}).fill('Browser session '+randomUUID());
  await page.getByLabel('Start (local time)',{exact:true}).fill('2025-10-25T22:00:00.123456789Z');
  await page.getByLabel('End (local time, blank while running)',{exact:true}).fill('2025-10-26T23:00:00.123456789Z');await save(page);
  const session = (await (await api(request,'GET','/work-sessions')).json()).items[0];
  const label = (await (await api(request,'GET','/labels')).json()).items[0];
  for (const [summary,start,end] of [['First overlap','2025-10-26T08:00:00.123456789Z','2025-10-26T12:00:00.123456789Z'],['Second overlap','2025-10-26T10:00:00.123456789Z','2025-10-26T14:00:00.123456789Z']]) {
    await page.getByRole('button',{name:'Add activity to session',exact:true}).click();
    await expect(page.getByLabel('Task',{exact:true})).toHaveValue('');await expect(page.locator('#editor').getByLabel('Work session',{exact:true})).toHaveValue(session.id);
    await page.getByLabel('Summary',{exact:true}).fill(summary);await page.getByLabel('Start (local time)',{exact:true}).fill(start);
    await page.getByLabel('End (local time, blank while tracking)',{exact:true}).fill(end);await page.locator('#editor').getByLabel(label.name,{exact:true}).check();await save(page);
  }
  await expect(page.locator('#work-session-elapsed')).toHaveText('25h 0m');
  const report=await (await api(request,'GET',`/work-sessions/${session.id}/report`)).json();
  expect(report.time).toMatchObject({duration:'PT25H',trackedActivityTime:'PT8H',allocatedTime:'PT6H',unallocatedTime:'PT19H'});
  expect((await (await api(request,'GET','/tasks')).json()).items.length).toBe(tasksBefore);
  await page.getByRole('button',{name:'Edit work session',exact:true}).click();
  await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue(await editorTime(request, session.start));
  await page.getByLabel('End (local time, blank while running)',{exact:true}).fill('2025-10-26T23:00:00.123456790Z');await save(page);
  expect((await (await api(request,'GET',`/work-sessions/${session.id}`)).json()).end).toBe('2025-10-26T23:00:00.123456790Z');
  await page.getByRole('button',{name:'Add activity to session',exact:true}).click();await page.getByLabel('Summary',{exact:true}).fill('Outside session');
  await page.getByLabel('Start (local time)',{exact:true}).fill('2025-10-27T00:00:00Z');await page.getByLabel('End (local time, blank while tracking)',{exact:true}).fill('2025-10-27T01:00:00Z');
  await expect(page.locator('#activity-session-warning')).toContainText('outside the session');await save(page);
  expect((await (await api(request,'GET',`/work-sessions/${session.id}/report`)).json()).time.outsideSession.length).toBeGreaterThan(0);
  await page.screenshot({path:'test-results/work-session-desktop.png',fullPage:true});await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:'test-results/work-session-mobile.png',fullPage:true});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await removeSessionFixture(request,[session.id]);
});
test('lost session start and stop replies retry the exact durable command', async ({page,request}) => {
  await signIn(page);const keys=[];
  await page.route('**/api/v1/work-sessions/start',async route=>{keys.push(route.request().headers()['idempotency-key']);const response=await route.fetch();expect(response.status()).toBe(201);await route.abort('failed');},{times:1});
  await page.getByRole('button',{name:'Start work session',exact:true}).click();await page.getByLabel('Title',{exact:true}).fill('Browser work session');await page.getByLabel('Client',{exact:true}).selectOption({index:1});await page.locator('#save').click();await expect(page.locator('#editor-notice')).toContainText('outcome is unknown');
  page.on('request',r=>{if(r.url().endsWith('/work-sessions/start')) keys.push(r.headers()['idempotency-key']);});
  await page.getByRole('button',{name:'Retry same command',exact:true}).click();await expect(page.locator('#editor')).not.toBeVisible();expect(new Set(keys).size).toBe(1);
  const active=await (await api(request,'GET','/work-sessions/active')).json();
  await page.route('**/api/v1/work-sessions/*/stop',async route=>{const response=await route.fetch();expect(response.ok()).toBeTruthy();await route.abort('failed');},{times:1});
  await page.getByRole('button',{name:'Stop work session',exact:true}).click();await expect(page.locator('#editor-notice')).toContainText('outcome is unknown');
  await page.getByRole('button',{name:'Retry same command',exact:true}).click();await expect(page.locator('#editor')).not.toBeVisible();
  expect((await (await api(request,'GET',`/work-sessions/${active.id}`)).json()).version).toBe(1);await removeSessionFixture(request,[active.id]);
});
test('peer session edits preserve an open draft and reject a stale save', async ({page,request}) => {
  const session=await (await api(request,'POST','/work-sessions',{start:'2025-10-26T08:00:00Z',end:'2025-10-26T09:00:00Z',summary:'Peer session '+randomUUID()})).json();
  await signIn(page);await editSessionFromWorkweek(page,request,session);await page.getByLabel('Summary',{exact:true}).fill('Unsaved session draft');
  const read=await api(request,'GET',`/work-sessions/${session.id}`);await api(request,'PUT',`/work-sessions/${session.id}`,{start:session.start,end:session.end,summary:'Peer changed session'},{'If-Match':read.headers().etag});
  await expect(page.getByLabel('Summary',{exact:true})).toHaveValue('Unsaved session draft');
  await page.locator('#editor').getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#editor-error')).toContainText('This record changed');
  await expect(page.getByLabel('Summary',{exact:true})).toHaveValue('Unsaved session draft');await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await removeSessionFixture(request,[session.id]);
});
