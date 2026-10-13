import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {readFile, writeFile, rename} from 'node:fs/promises';
const token = process.env.TIMEKEEPER_WEB_TOKEN;
const readToken = process.env.TIMEKEEPER_WEB_READ_TOKEN;
async function signIn(page, credential = token) {
  await page.goto('/web/'); await page.getByLabel('Access token').fill(credential); await page.getByRole('button', {name:'Sign in', exact:true}).click();
  await expect(page.locator('#status')).toHaveText('Up to date');
}
async function api(request, method, path, data, headers = {}) {
  const response = await request.fetch(`/api/v1${path}`, {method, data, headers:{Authorization:`Bearer ${token}`, ...headers}});
  expect(response.ok()).toBeTruthy(); return response;
}
async function createTask(request, summary = 'Browser task ' + randomUUID()) {
  return (await api(request, 'POST', '/tasks', {summary, projectId:null, parentTaskId:null, url:null})).json();
}
async function save(page) {
  await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click(); await expect(page.locator('#editor')).not.toBeVisible();
}
async function newNamed(page, tab, singular, name) {
  await page.getByRole('button', {name:tab, exact:true}).click(); await page.getByRole('button', {name:`New ${singular}`, exact:true}).click();
  await page.getByLabel('Name', {exact:true}).fill(name); await save(page);
}
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
  await page.getByRole('button', {name:'Workweek', exact:true}).click(); await page.getByLabel('Week containing').fill('2026-10-25');
  await expect(page.locator('#zone-caption')).toContainText('2026-10-19'); await page.getByRole('button', {name:'Add time', exact:true}).click();
  await page.getByLabel('Task', {exact:true}).selectOption({label:task}); await page.getByLabel('Summary', {exact:true}).fill('Autumn DST');
  await page.getByLabel('Start (UTC)', {exact:true}).fill('2026-10-24T22:00:00Z'); await page.getByLabel('End (UTC, blank while tracking)', {exact:true}).fill('2026-10-25T23:00:00Z'); await save(page);
  await expect(page.locator('#week-total')).toHaveText('25h 0m'); await expect(page.locator('#days .day').filter({hasText:'2026-10-25'})).toContainText('25h 0m');
  const activity = page.locator('#activities tr').filter({hasText:'Autumn DST'});
  await activity.getByText('Autumn DST', {exact:true}).click(); await expect(page.locator('#editor')).not.toBeVisible();
  await activity.press('Enter'); await expect(page.locator('#editor')).toBeVisible();
  await page.getByLabel('Summary', {exact:true}).fill('One nanosecond'); await page.getByLabel('Start (UTC)', {exact:true}).fill('2026-10-24T22:00:00.123456789Z'); await page.getByLabel('End (UTC, blank while tracking)', {exact:true}).fill('2026-10-24T22:00:00.123456790Z'); await save(page);
  await expect(page.locator('#week-total')).toHaveText('0h 0m');
  await page.locator('#activities tr').filter({hasText:'One nanosecond'}).getByRole('button', {name:'Edit', exact:true}).click(); await expect(page.getByLabel('Start (UTC)', {exact:true})).toHaveValue('2026-10-24T22:00:00.123456789Z');
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
    await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click(); await expect(page.locator('#editor-error')).toContainText('account for this session has changed'); await expect(page.getByLabel('Name', {exact:true})).toHaveValue('Keep in original account');
    expect((await (await api(request,'GET','/projects')).json()).items).toHaveLength(0);
  } finally { const temporary = file + '.new'; await writeFile(temporary, original, {mode:0o600}); await rename(temporary, file); }
});
test('sign-out and reload retain no token or account records in browser storage', async ({page}) => {
  await signIn(page); expect(await page.locator('#token').inputValue()).toBe('');
  expect(await page.evaluate(() => ({local:localStorage.length, session:sessionStorage.length}))).toEqual({local:0, session:0});
  await page.reload(); await expect(page.locator('#login')).toBeVisible(); await expect(page.locator('#workspace')).not.toBeVisible();
  await signIn(page); await page.getByRole('button', {name:'Sign out', exact:true}).click(); await expect(page.locator('#tasks')).toBeEmpty();
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
  const activity = await (await api(request,'POST','/activities',{taskId:child.id,summary:'DST review '+suffix,start:'2026-10-24T22:00:00.123456789Z',end:'2026-10-25T23:00:00Z',labelIds:[]})).json();
  await signIn(page); await page.getByLabel('Week containing').fill('2026-10-25');
  await expect(page.locator('#zone-caption')).toContainText('2026-10-19');
  const table = page.locator('#activities table');
  await expect(table.locator('th')).toHaveCount(10);
  await expect(table.locator('tr[data-kind=project]').filter({hasText:project.name})).toContainText('24h 59m 59s');
  const parentRow = table.locator('tr[data-kind=task]').filter({hasText:'PLATFORM-3396: Clean registry '+suffix});
  await expect(parentRow.getByRole('link')).toHaveAttribute('href',jiraUrl);
  await expect(parentRow).toContainText('24h 59m 59s');
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
  await intervalRow.getByRole('button',{name:'Edit',exact:true}).click();
  await expect(page.getByLabel('Task',{exact:true})).toHaveValue(child.id);
  await expect(page.getByLabel('Start (UTC)',{exact:true})).toHaveValue('2026-10-24T22:00:00.123456789Z');
  await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await page.screenshot({path:'test-results/workweek-hierarchy-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.screenshot({path:'test-results/workweek-hierarchy-mobile.png',fullPage:true});
  const report = await (await api(request,'GET','/reports/activities?from=2026-10-18T22:00:00Z&to=2026-10-25T23:00:00Z&zoneId=Europe%2FOslo')).json();
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
