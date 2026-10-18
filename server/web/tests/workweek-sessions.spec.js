import {openAccountMenu} from './navigation.js';
import {workSessionAction,workSessionDetails} from './workweek-session-actions.js';
import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
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
test('work-session label choices, compact display and timer edits stay independent of activity labels',async({page,request})=>{
  const f=await fixture(request);let id;
  try {
    const first=await f.create('labels',{name:'Office '+randomUUID().slice(0,6),color:'#176653'});
    const second=await f.create('labels',{name:'Billable '+randomUUID().slice(0,6),color:'#6750a4'});
    await signIn(page);await page.getByRole('button',{name:'Start work session',exact:true}).click();
    await page.getByLabel('Summary',{exact:true}).fill('Labelled working day');await page.getByLabel('Client',{exact:true}).selectOption(f.client.id);
    await page.getByLabel(first.name,{exact:true}).check();await page.locator('#save').click();await expect(page.locator('#editor')).not.toBeVisible();
    const active=await (await api(request,'GET','/work-sessions/active')).json();id=active.id;expect(active.labelIds).toEqual([first.id]);
    await expect(page.locator('#work-session-labels')).toContainText(first.name);
    await page.getByRole('button',{name:'Edit work session',exact:true}).click();await expect(page.getByLabel(first.name,{exact:true})).toBeChecked();
    await page.getByLabel(first.name,{exact:true}).uncheck();await page.getByLabel(second.name,{exact:true}).check();await page.locator('#save').click();await expect(page.locator('#editor')).not.toBeVisible();
    await expect(page.locator('#work-session-labels')).toContainText(second.name);await expect(page.locator('#work-session-labels')).not.toContainText(first.name);
    expect((await (await api(request,'GET','/work-sessions/'+id)).json()).labelIds).toEqual([second.id]);
    const audit=await new AxeBuilder({page}).include('#work-session').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
    await page.locator('#work-session').screenshot({path:'test-results/work-session-labels-desktop.png',timeout:10000});
    await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await page.locator('#work-session').screenshot({path:'test-results/work-session-labels-mobile.png',timeout:10000});
    await page.getByRole('button',{name:'Stop work session',exact:true}).click();await expect(page.locator('#work-session-state')).toHaveText('Stopped');
    expect((await (await api(request,'GET','/work-sessions/'+id)).json()).labelIds).toEqual([second.id]);
    await expect(page.locator(`#week-sessions [data-work-session-id="${id}"]`).first()).toHaveAttribute('title',new RegExp(second.name));
  } finally {
    if(id) {const current=await api(request,'GET','/work-sessions/'+id);await api(request,'DELETE','/work-sessions/'+id,undefined,{'If-Match':current.headers().etag});}
    await f.close();
  }
});
test('Workweek groups local session times by client/project with exact DST totals independent of activities', async ({page, request}) => {
  const f = await fixture(request);
  try {
    const baseline = await (await api(request, 'GET', '/reports/activities?from=2025-10-19T22:00:00Z&to=2025-10-26T23:00:00Z&zoneId=Europe%2FOslo')).json();
    const activityTotal = durationNanos(baseline.total) + 3600000000000n;
    const session = await f.create('work-sessions', {clientId: f.client.id, summary:'Weekend work', start: '2025-10-25T21:30:00Z', end: '2025-10-27T01:30:00Z'});
    const outside = await f.create('work-sessions', {clientId: f.client.id, summary:'Earlier week', start: '2025-10-01T08:00:00Z', end: '2025-10-01T09:00:00Z'});
    await f.create('activities', {taskId: null, projectId: null, summary: 'Separate tracked time', labelIds: [], start: '2025-10-26T09:00:00Z', end: '2025-10-26T10:00:00Z'});
    await signIn(page); await page.getByLabel('Week containing', {exact: true}).fill('2025-10-26'); await expect(page.locator('#zone-caption')).toContainText('2025-10-20');
    const row=page.locator(`#week-sessions tr[data-week-project=""][data-client-id="${f.client.id}"]`),client=page.locator(`#week-sessions .week-session-client[data-client-id="${f.client.id}"]`);
    await expect(row.locator('[data-work-session-id]')).toHaveCount(2);await expect(client).toContainText(f.client.name);
    expect(await client.locator('.client-color').evaluate(value => getComputedStyle(value, '::before').backgroundColor)).toBe('rgb(103, 80, 164)');
    const saturday=row.locator('[data-session-date="2025-10-25"] [data-work-session-id]'),sunday=row.locator('[data-session-date="2025-10-26"] [data-work-session-id]');
    await expect(saturday.locator('[data-endpoint=start]')).toHaveValue('23:30');await expect(saturday.locator('[data-endpoint=end]')).toHaveValue('24:00');await expect(saturday.locator('[data-endpoint=end]')).toHaveAttribute('readonly','');
    await expect(sunday.locator('[data-endpoint=start]')).toHaveValue('00:00');await expect(sunday.locator('[data-endpoint=end]')).toHaveValue('24:00');
    await expect(client.locator('td').nth(5)).toHaveText('0h 30m');await expect(client.locator('td').nth(6)).toHaveText('25h 0m');await expect(row.locator('td').last()).toHaveText('25h 30m');
    await expect(page.locator(`#week-sessions [data-work-session-id="${outside.id}"]`)).toHaveCount(0);
    await expect(page.locator('#week-total')).toHaveText(nanosText(activityTotal));
    await page.getByLabel('Duration format', {exact: true}).selectOption('decimal'); await expect(row.locator('td').last()).toHaveText('25.50h'); await expect(page.locator('#week-total')).toHaveText(decimalHoursText(activityTotal));
    await expect(page.locator('#activities-panel')).not.toBeVisible();await page.locator('[data-tab=activities]').click();
    await expect(page.locator('#activities')).toBeVisible();await expect(page.locator('#days')).toBeVisible();await expect(page.locator('#week-sessions-section')).not.toBeVisible();
    await expect(page.getByLabel('Duration format',{exact:true})).toHaveValue('decimal');await expect(page.getByLabel('Week containing',{exact:true})).toHaveValue('2025-10-26');
    await page.locator('.day button').filter({hasText:'2025-10-21'}).click();await expect(page.locator('#week-total')).toBeVisible();
    await page.locator('[data-tab=week]').click();await expect(page.locator('#week-sessions-section')).toBeVisible();await expect(row.locator('td').last()).toHaveText('25.50h');await expect(page.locator('#days')).not.toBeVisible();
    await page.screenshot({path: 'test-results/workweek-sessions-desktop.png', fullPage: true});
    await page.setViewportSize({width: 390, height: 844}); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    expect(await page.locator('#week-sessions').evaluate(value => value.scrollWidth > value.clientWidth)).toBeTruthy();
    await page.screenshot({path: 'test-results/workweek-sessions-mobile.png', fullPage: true});
    await page.getByLabel('Week containing', {exact: true}).fill('2025-10-27'); await expect(page.locator('#zone-caption')).toContainText('2025-10-27'); await expect(row.locator('[data-endpoint=start]')).toHaveValue('00:00');await expect(row.locator('[data-endpoint=end]')).toHaveValue('02:30');await expect(row.locator('td').last()).toHaveText('2.50h');
    await openAccountMenu(page);await page.getByRole('button', {name: 'Sign out', exact: true}).click(); await expect(page.locator('#login')).toBeVisible(); await expect(page.locator('#week-sessions')).toBeEmpty();
  } finally {await f.close();}
});
test('running Workweek sessions remain editable without losing exact times and read-only writes are rejected', async ({page, request}) => {
  const f = await fixture(request);
  try {
    const session = await f.create('work-sessions', {clientId: f.client.id, summary:'Running workweek session', start: new Date(Date.now() - 120000).toISOString(), end: null});
    await signIn(page); await page.getByLabel('Week containing', {exact: true}).fill(dateInZone(Date.now(), 'Europe/Oslo'));
    const row = page.locator(`#week-sessions [data-work-session-id="${session.id}"]`).first(); await expect(row).toContainText('Running');
    await workSessionAction(page,session.id);
    await expect(page.getByLabel('Start (local time)', {exact: true})).toHaveValue(localDateTime(session.start, 'Europe/Oslo').slice(0,19).replace(/:00$/, ''));
    await page.getByLabel('Summary', {exact: true}).fill('Updated running session'); await page.locator('#save').click(); await expect(page.locator('#editor')).not.toBeVisible(); await expect(row).toHaveAttribute('title',/Updated running session/);
    const saved = await (await api(request, 'GET', '/work-sessions/' + session.id)).json(); expect(saved.start).toBe(session.start); expect(saved.end).toBeNull();
    await openAccountMenu(page);await page.getByRole('button', {name: 'Sign out', exact: true}).click(); await expect(page.locator('#login')).toBeVisible(); await signIn(page, process.env.TIMEKEEPER_WEB_READ_TOKEN);
    for(const input of await row.locator('input').all())await expect(input).toBeDisabled();
    const details=await workSessionDetails(page,session.id);await expect(details.getByRole('button', {name: 'Edit session Updated running session', exact: true})).toBeDisabled();
    await expect(details.getByRole('button', {name: 'Delete session Updated running session', exact: true})).toBeDisabled();
    const current = await api(request, 'GET', '/work-sessions/' + session.id);
    const denied = await request.put('/api/v1/work-sessions/' + session.id, {data: {...saved, summary: 'Rejected read-only change'}, headers: {Authorization: `Bearer ${process.env.TIMEKEEPER_WEB_READ_TOKEN}`, 'If-Match': current.headers().etag}});
    expect(denied.status()).toBe(403);
    expect((await (await api(request, 'GET', '/work-sessions/' + session.id)).json()).summary).toBe('Updated running session');
  } finally {await f.close();}
});
test('compact Workweek cells group sessions and edit exact endpoints without changing activities or metadata',async({browser,request})=>{
  const f=await fixture(request),context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,timezoneId:'America/Los_Angeles',locale:'en-GB',ignoreHTTPSErrors:true}),page=await context.newPage();
  try {
    const project=await f.create('projects',{name:'Client website',clientId:f.client.id});
    const label=await f.create('labels',{name:'Office hours',color:'#6750a4'});
    const first=await f.create('work-sessions',{clientId:f.client.id,projectId:project.id,summary:'Morning planning',labelIds:[label.id],start:'2025-10-20T06:00:00.123456789Z',end:'2025-10-20T10:00:00.987654321Z'});
    await f.create('work-sessions',{clientId:f.client.id,projectId:project.id,summary:'Afternoon review',start:'2025-10-20T12:00:00Z',end:'2025-10-20T14:00:00Z'});
    await f.create('work-sessions',{clientId:f.client.id,projectId:project.id,summary:'Tuesday work',start:'2025-10-21T06:00:00Z',end:'2025-10-21T14:00:00Z'});
    await f.create('work-sessions',{clientId:f.client.id,summary:'Without a project',start:'2025-10-22T08:00:00Z',end:'2025-10-22T09:00:00Z'});
    const other=await f.create('clients',{name:'Another client',color:'#2563eb'}),otherProject=await f.create('projects',{name:'Client website',clientId:other.id});
    await f.create('work-sessions',{clientId:other.id,projectId:otherProject.id,summary:'Separate client work',start:'2025-10-23T07:00:00Z',end:'2025-10-23T12:00:00Z'});
    const activity=await f.create('activities',{taskId:null,projectId:project.id,summary:'Independent activity',labelIds:[],start:'2025-10-20T06:00:00Z',end:'2025-10-20T07:00:00Z'});
    await signIn(page);await page.getByLabel('Week containing',{exact:true}).fill('2025-10-20');
    const row=page.locator(`#week-sessions [data-week-project="${project.id}"]`),entry=page.locator(`#week-sessions [data-work-session-id="${first.id}"]`),end=entry.locator('[data-endpoint=end]');
    await expect(row).toHaveCount(1);await expect(row.locator('[data-work-session-id]')).toHaveCount(3);await expect(row.locator('td[data-session-date="2025-10-20"] [data-work-session-id]')).toHaveCount(2);
    await expect(page.locator('#week-sessions .week-session-client')).toHaveCount(2);await expect(page.locator('#week-sessions [data-week-project=""]')).toContainText('No project');
    await expect(entry.locator('[data-endpoint=start]')).toHaveValue('08:00');await expect(end).toHaveValue('12:00');
    let writes=0;await page.route('**/api/v1/work-sessions/'+first.id,async route=>{if(route.request().method()==='PUT')writes++;await route.continue();});
    await end.fill('13:30');await end.press('Enter');await expect.poll(async()=>(await (await api(request,'GET','/work-sessions/'+first.id)).json()).end).toBe('2025-10-20T11:30:00Z');
    const saved=await (await api(request,'GET','/work-sessions/'+first.id)).json();expect(saved.start).toBe(first.start);expect(saved.summary).toBe(first.summary);expect(saved.clientId).toBe(first.clientId);expect(saved.projectId).toBe(first.projectId);expect(saved.labelIds).toEqual(first.labelIds);expect(writes).toBe(1);
    expect(await (await api(request,'GET','/activities/'+activity.id)).json()).toEqual(activity);
    await expect(end).toBeEnabled();await end.fill('14:00');await end.press('Escape');await expect(end).toHaveValue('13:30');expect(writes).toBe(1);
    await end.fill('14:00');await page.getByRole('heading',{name:'Work sessions',exact:true}).click();
    await expect.poll(async()=>(await (await api(request,'GET','/work-sessions/'+first.id)).json()).end).toBe('2025-10-20T12:00:00Z');expect(writes).toBe(2);
    await expect(page.locator('.week-session-draft')).not.toBeVisible();
    const audit=await new AxeBuilder({page}).include('#week-sessions-section').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
    await page.screenshot({path:'test-results/compact-workweek-desktop.png',fullPage:true});
    await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect(await page.locator('#week-sessions').evaluate(node=>node.scrollWidth>node.clientWidth)).toBe(true);
    // Every compact clock must fit its field even at the table's minimum width.
    for(const input of await page.locator('#week-sessions input').all())expect(await input.evaluate(node=>{const style=getComputedStyle(node),canvas=document.createElement('canvas'),context=canvas.getContext('2d');context.font=style.font;return context.measureText(node.value).width+parseFloat(style.paddingLeft)+parseFloat(style.paddingRight)<=node.clientWidth;})).toBe(true);
    await page.screenshot({path:'test-results/compact-workweek-mobile.png',fullPage:true});
    const details=await workSessionDetails(page,first.id);await expect(details).toContainText('Morning planning');await expect(details).toContainText(label.name);await expect(details).toContainText(project.name);
  } finally {await context.close();await f.close();}
});
test('Workweek inline edits keep stale and uncertain proposals without overwriting or repeating writes',async({page,request})=>{
  const f=await fixture(request);
  try {
    const record=await f.create('work-sessions',{clientId:f.client.id,summary:'Concurrent inline edit',start:'2025-10-20T06:00:00.123456789Z',end:'2025-10-20T14:00:00.987654321Z'});
    await signIn(page);await page.getByLabel('Week containing',{exact:true}).fill('2025-10-20');
    const entry=page.locator(`#week-sessions [data-work-session-id="${record.id}"]`),end=entry.locator('[data-endpoint=end]');
    await end.fill('17:00');const current=await api(request,'GET','/work-sessions/'+record.id);
    await api(request,'PUT','/work-sessions/'+record.id,{clientId:f.client.id,summary:'Peer notes retained',start:record.start,end:record.end},{'If-Match':current.headers().etag});
    await expect(page.locator('#work-session-title')).toHaveText('Peer notes retained');await expect(end).toHaveValue('17:00');
    let writes=0;await page.route('**/api/v1/work-sessions/'+record.id,async route=>{if(route.request().method()==='PUT')writes++;await route.continue();});
    await end.press('Enter');await expect(page.locator('.week-session-feedback')).toContainText('proposed times are kept');expect(writes).toBe(0);await expect(page.locator('.week-session-draft').getByRole('button',{name:'Save',exact:true})).toBeDisabled();
    await page.getByRole('button',{name:'Reload current session',exact:true}).click();await expect(end).toHaveValue('16:00');
    await page.unroute('**/api/v1/work-sessions/'+record.id);await page.route('**/api/v1/work-sessions/'+record.id,async route=>{if(route.request().method()==='PUT'){writes++;expect((await route.fetch()).status()).toBe(200);await route.abort('failed');}else await route.continue();});
    await end.fill('17:00');await end.press('Enter');await expect(page.locator('.week-session-feedback')).toContainText('proposed times are kept');await end.press('Enter');expect(writes).toBe(1);
    expect((await (await api(request,'GET','/work-sessions/'+record.id)).json()).end).toBe('2025-10-20T15:00:00Z');
    await page.getByRole('button',{name:'Reload current session',exact:true}).click();await expect(end).toHaveValue('17:00');expect((await (await api(request,'GET','/work-sessions/'+record.id)).json()).summary).toBe('Peer notes retained');
  } finally {await f.close();}
});
test('Workweek inline DST edits require an explicit occurrence and preserve the other endpoint',async({page,request})=>{
  const f=await fixture(request);
  try {
    const record=await f.create('work-sessions',{clientId:f.client.id,summary:'Repeated local clock',start:'2025-10-26T00:15:00.123456789Z',end:'2025-10-26T03:00:00Z'});
    await signIn(page);await page.getByLabel('Week containing',{exact:true}).fill('2025-10-26');
    const end=page.locator(`#week-sessions [data-work-session-id="${record.id}"] [data-endpoint=end]`);
    await end.fill('02:45');await end.press('Enter');await expect(page.locator('.week-session-feedback')).toContainText('occurs twice');await expect(end).toHaveValue('02:45');
    await end.fill('02:45+01:00');await end.press('Enter');await expect.poll(async()=>(await (await api(request,'GET','/work-sessions/'+record.id)).json()).end).toBe('2025-10-26T01:45:00Z');
    expect((await (await api(request,'GET','/work-sessions/'+record.id)).json()).start).toBe(record.start);
  } finally {await f.close();}
});
