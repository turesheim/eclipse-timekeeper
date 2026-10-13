// A real browser controlled by the installed Eclipse acceptance JVM.
// The line protocol carries synthetic record names/times, never credentials.
import {chromium, expect} from '@playwright/test';
import {createInterface} from 'node:readline';
import {writeFile} from 'node:fs/promises';
import {durationText} from '../src/model.js';

const browser = await chromium.launch();
const page = await browser.newPage({baseURL: process.env.TIMEKEEPER_WEB_URL});
page.setDefaultTimeout(15000);
const record = {};
async function tab(name) { await page.getByRole('button', {name, exact:true}).click(); }
async function signIn() {
  await page.goto('/web/');
  await expect.poll(async () => await page.locator('#workspace').isVisible() || await page.locator('#login-form button').isEnabled()).toBe(true);
  if (!await page.locator('#workspace').isVisible()) {
    await page.getByLabel('Access token').fill(process.env.TIMEKEEPER_WEB_TOKEN);
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
  await page.getByRole('button', {name:'Time zone', exact:true}).click();
  await page.getByRole('textbox', {name:'Time zone', exact:true}).fill(value); await save();
}
async function command(input) {
  switch (input.action) {
    case 'attach':
      record.project = input.project; record.task = input.summary;
      await signIn();
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
      await tab('Tasks'); await page.getByRole('button', {name:'New task', exact:true}).click();
      await page.getByLabel('Summary', {exact:true}).fill(record.task); await page.getByLabel('Project', {exact:true}).selectOption({label:record.project}); await save();
      await tab('Workweek'); await page.getByLabel('Week containing').fill(input.date); await page.getByRole('button', {name:'Add time', exact:true}).click();
      await page.getByLabel('Task', {exact:true}).selectOption({label:record.task}); await page.getByLabel('Summary', {exact:true}).fill(input.prefix + ' precise interval');
      await page.getByLabel('Start (UTC)', {exact:true}).fill(input.start); await page.getByLabel('End (UTC, blank while tracking)', {exact:true}).fill(input.end);
      await page.getByLabel(record.label, {exact:true}).check(); await save();
      break;
    case 'edit':
      await tab('Tasks'); await page.locator('#tasks tr').filter({hasText:record.task}).getByRole('button', {name:'Edit', exact:true}).click();
      await page.getByLabel('Summary', {exact:true}).fill(input.summary); await page.getByLabel('URL (optional)', {exact:true}).fill(input.url); await save(); record.task = input.summary;
      break;
    case 'draft':
      await tab('Tasks'); await page.locator('#tasks tr').filter({hasText:record.task}).getByRole('button', {name:'Edit', exact:true}).click();
      await page.getByLabel('Summary', {exact:true}).fill(input.summary); await page.getByLabel('URL (optional)', {exact:true}).fill(input.url);
      break;
    case 'label-draft':
      await tab('Tasks');await page.locator('#tasks tr').filter({hasText:record.task}).getByRole('button',{name:'Start',exact:true}).click();
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
      await expect(page.locator('#tasks')).toContainText(input.summary);
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
      await tab('Tasks'); await page.locator('#tasks tr').filter({hasText:record.task}).getByRole('button', {name:'Start', exact:true}).click();
      await page.getByLabel('Summary', {exact:true}).fill('Browser tracked interval'); await page.getByLabel(record.label, {exact:true}).check(); await save('Start');
      break;
    case 'observe-stopped':
      await expect(page.locator('#active-title')).toHaveText('Ready when you are'); await expect(page.locator('#stop')).not.toBeVisible();
      break;
    case 'report':
      await tab('Workweek'); await expect(page.locator('#zone-caption')).toContainText(input.zone);
      await expect(page.locator('#week-total')).toHaveText(durationText(input.total));
      for (const day of JSON.parse(input.days)) await expect(page.locator('#days .day').filter({hasText:day.date})).toContainText(durationText(day.total));
      await expect(page.locator('#activities')).toContainText(record.task);
      break;
    case 'cleanup':
      await tab('Tasks');
      const taskId = await page.locator('#tasks tr').filter({hasText:record.task}).locator('.task-title').getAttribute('data-task-id');
      await tab('Workweek');
      const rows = page.locator(`#activities tr[data-kind="activity"][data-task-id="${taskId}"]`);
      while (await rows.count()) {
        const before = await rows.count();
        await rows.first().getByRole('button', {name:'Delete', exact:true}).click(); await save('Delete');
        await expect(rows).toHaveCount(before - 1);
      }
      for (const [tabName, container, name] of [['Tasks','tasks',record.task], ['Projects','projects',record.project], ['Labels','labels',record.label]]) {
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
      await page.screenshot({path:process.env.TIMEKEEPER_WEB_FAILURE_IMAGE, fullPage:true});
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
} finally { lines.close(); process.stdin.destroy(); await browser.close(); }
