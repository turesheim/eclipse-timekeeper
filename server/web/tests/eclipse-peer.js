import {captureScreenshot} from './screenshots.js';
// A real browser controlled by the installed Eclipse acceptance JVM.
// The line protocol carries synthetic record names/times, never credentials.
import {chromium, expect, request} from '@playwright/test';
import {createInterface} from 'node:readline';
import {writeFile} from 'node:fs/promises';
import {durationText} from '../src/model.js';
import {setTimeZone} from './navigation.js';
import {openTaskHistory,clearTaskHistory} from './task-history.js';

const browser = await chromium.launch();
const page = await browser.newPage({baseURL: process.env.TIMEKEEPER_WEB_URL});
page.setDefaultTimeout(15000);
const record = {};
const apiRequest=await request.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true});
async function tab(name) { await page.getByRole('button', {name, exact:true}).click(); }
async function api(method,path,data,headers={}) {
  const reply=await apiRequest.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`,...headers}});
  expect(reply.ok()).toBe(true);return reply;
}
async function taskHistory() {
  if(!record.taskId)record.taskId=(await (await api('GET','/tasks')).json()).items.find(task=>task.summary===record.task).id;
  return openTaskHistory(page,{id:record.taskId},apiRequest);
}
async function signIn() {
  await page.goto('/web/');
  await expect.poll(async () => await page.locator('#workspace').isVisible() || await page.locator('#login-form button[type="submit"]').isEnabled()).toBe(true);
  if (!await page.locator('#workspace').isVisible()) {
    await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(process.env.TIMEKEEPER_WEB_TOKEN);
    await page.getByRole('button', {name:'Sign in', exact:true}).click();
  }
  await expect(page.locator('#status')).toHaveText('Up to date');
}
async function save(name = 'Save') {
  await page.locator('#editor').getByRole('button', {name, exact:true}).click();
  await expect(page.locator('#editor')).not.toBeVisible();
}
async function named(tabName, kind, name) {
  await tab(tabName); await page.getByRole('button', {name:'New ' + kind, exact:true}).click();
  await page.getByLabel('Name', {exact:true}).fill(name); await save();
  await expect(page.locator(`#${tabName.toLowerCase()} tr`).filter({hasText:name})).toBeVisible();
}
async function zone(value) {
  await setTimeZone(page, value);
}
async function command(input) {
  switch (input.action) {
    case 'attach':
      record.project = input.project; record.task = input.summary;record.taskId=null;
      await signIn();
      // Attach before the restore fixture takes its backup, so the later task
      // draft still has its Activities row after that backup is restored.
      await taskHistory();
      break;
    case 'clear-history':
      await clearTaskHistory(apiRequest);
      break;
    case 'offline-draft':
      await expect(page.locator('#status')).toContainText('Reconnecting', {timeout:15000});
      await expect(page.getByLabel('Summary', {exact:true})).toHaveValue(input.draft);
      await expect(page.getByLabel('URL (optional)', {exact:true})).toHaveValue(input.url);
      break;
    case 'create':
      record.project = input.prefix + ' project'; record.task = input.prefix + ' task'; record.label = input.prefix + ' label';
      await signIn();
      await zone(input.zone); await named('Projects', 'project', record.project); await named('Labels', 'label', record.label);
      const project=(await (await api('GET','/projects')).json()).items.find(value=>value.name===record.project);
      record.taskId=(await (await api('POST','/tasks',{summary:record.task,projectId:project.id,parentTaskId:null,url:null})).json()).id;
      await tab('Activities'); await page.getByLabel('Week containing').fill(input.date);
      // The task was created outside this browser. Finish a fresh snapshot
      // before opening an editor whose task choices come from that snapshot.
      await page.locator('#refresh').click();
      await expect(page.locator('#workspace')).toHaveAttribute('aria-busy', 'false');
      await page.getByRole('button', {name:'Add time', exact:true}).click();
      await page.getByLabel('Task', {exact:true}).selectOption({label:record.task}); await page.getByLabel('Summary', {exact:true}).fill(input.prefix + ' precise interval');
      await page.getByLabel('Start (local time)', {exact:true}).fill(input.start); await page.getByLabel('End (local time, blank while tracking)', {exact:true}).fill(input.end);
      await page.getByLabel(record.label, {exact:true}).check(); await save();
      break;
    case 'edit':
      await taskHistory(); await page.locator('#activities tr[data-kind=task]').filter({hasText:record.task}).getByRole('button', {name:'Edit task', exact:true}).click();
      await page.getByLabel('Summary', {exact:true}).fill(input.summary); await page.getByLabel('URL (optional)', {exact:true}).fill(input.url); await save(); record.task = input.summary;
      break;
    case 'draft':
      await taskHistory(); await page.locator('#activities tr[data-kind=task]').filter({hasText:record.task}).getByRole('button', {name:'Edit task', exact:true}).click();
      await page.getByLabel('Summary', {exact:true}).fill(input.summary); await page.getByLabel('URL (optional)', {exact:true}).fill(input.url);
      break;
    case 'label-draft':
      await taskHistory();await page.locator('#activities tr[data-kind=task]').filter({hasText:record.task}).getByRole('button',{name:'Start',exact:true}).click();
      await page.getByLabel('Summary',{exact:true}).fill(input.draft);await page.getByLabel(record.label,{exact:true}).check();break;
    case 'observe-label-choice':
      await expect(page.getByLabel(input.label,{exact:true})).toBeVisible();
      await expect(page.getByLabel(record.label,{exact:true})).toBeChecked();
      await expect(page.getByLabel('Summary',{exact:true})).toHaveValue(input.draft);
      await page.getByLabel(input.label,{exact:true}).check();break;
    case 'observe-deleted-label-choice':
      await expect(page.getByLabel(input.label+' (deleted)',{exact:true})).toBeChecked();
      await expect(page.getByLabel(record.label,{exact:true})).toBeChecked();
      await expect(page.getByLabel('Summary',{exact:true})).toHaveValue(input.draft);
      await page.getByRole('button',{name:'Cancel',exact:true}).click();break;
    case 'conflict':
      await expect(page.locator('#activities')).toContainText(input.summary);
      await expect(page.getByLabel('Summary', {exact:true})).toHaveValue(input.draft);
      await page.locator('#editor').getByRole('button', {name:'Save', exact:true}).click();
      await expect(page.locator('#editor-error')).toContainText('This record changed');
      await expect(page.getByLabel('Summary', {exact:true})).toHaveValue(input.draft); await expect(page.getByLabel('Summary', {exact:true})).toBeEnabled();
      if (input.draftUrl) await expect(page.getByLabel('URL (optional)', {exact:true})).toHaveValue(input.draftUrl);
      await page.getByRole('button', {name:'Cancel', exact:true}).click(); record.task = input.summary;
      break;
    case 'observe-active':
      await expect(page.locator('#active-title')).toHaveText(record.task); await expect(page.locator('#stop')).toBeVisible();
      break;
    case 'stop':
      await page.getByRole('button', {name:'Stop tracking', exact:true}).click(); await save('Stop');
      break;
    case 'start':
      await taskHistory(); await page.locator('#activities tr[data-kind=task]').filter({hasText:record.task}).getByRole('button', {name:'Start', exact:true}).click();
      await page.getByLabel('Summary', {exact:true}).fill('Browser tracked interval'); await page.getByLabel(record.label, {exact:true}).check(); await save('Start');
      break;
    case 'observe-stopped':
      await expect(page.locator('#active-title')).toHaveText('Ready when you are'); await expect(page.locator('#stop')).not.toBeVisible();
      break;
    case 'report':
      await tab('Activities'); await expect(page.locator('#activities')).toBeVisible(); await expect(page.locator('#zone-caption')).toContainText(input.zone);
      await expect(page.locator('#week-total')).toHaveText(durationText(input.total));
      for (const day of JSON.parse(input.days)) await expect(page.locator('#days .day').filter({hasText:day.date})).toContainText(durationText(day.total));
      await expect(page.locator('#activities')).toContainText(record.task);
      break;
    case 'cleanup':
      await taskHistory();
      const taskId = await page.locator('#activities tr[data-kind=task]').filter({hasText:record.task}).locator('.task-title').getAttribute('data-task-id');
      await tab('Activities');
      const rows = page.locator(`#activities tr[data-kind="activity"][data-task-id="${taskId}"]`);
      while (await rows.count()) {
        const before = await rows.count();
        await rows.first().getByRole('button', {name:'Delete', exact:true}).click(); await save('Delete');
        await expect(rows).toHaveCount(before - 1);
      }
      await clearTaskHistory(apiRequest);
      const taskRead=await api('GET','/tasks/'+record.taskId);await api('DELETE','/tasks/'+record.taskId,undefined,{'If-Match':taskRead.headers().etag});
      for (const [tabName, container, name] of [['Projects','projects',record.project], ['Labels','labels',record.label]]) {
        await tab(tabName); await page.locator(`#${container} tr`).filter({hasText:name}).getByRole('button', {name:'Delete', exact:true}).click(); await save('Delete');
      }
      await zone(input.zone); break;
    default: throw new Error('Unknown browser peer action');
  }
}
const lines = createInterface({input:process.stdin});
try {
  for await (const line of lines) {
    const input = JSON.parse(line); if (input.action === 'quit') break;
    try { await command(input); process.stderr.write('Browser phase passed: ' + input.action + '\n'); process.stdout.write('{"ok":true}\n'); }
    catch (error) {
      await captureScreenshot(page,{path:process.env.TIMEKEEPER_WEB_FAILURE_IMAGE, fullPage:true});
      await writeFile(process.env.TIMEKEEPER_WEB_FAILURE_TEXT, await page.locator('body').innerText());
      const records = {};
      for (const collection of ['projects','labels']) {
        try {
          const response = await page.request.get(`/api/v1/${collection}`, {headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`}});
          records[collection] = (await response.json()).items?.map(({id,name}) => ({id,name}));
        } catch { records[collection] = 'diagnostic read failed'; }
      }
      await writeFile(process.env.TIMEKEEPER_WEB_FAILURE_RECORDS, JSON.stringify(records));
      const message = String(error.message).replaceAll(process.env.TIMEKEEPER_WEB_TOKEN, '[redacted]').slice(0, 4000);
      process.stdout.write(JSON.stringify({ok:false, message}) + '\n'); process.exitCode = 1; break;
    }
  }
} finally { lines.close(); process.stdin.destroy(); await apiRequest.dispose();await browser.close(); }
