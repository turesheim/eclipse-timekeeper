import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {browserRequest} from './browser-request.js';

async function fresh(page,request){
  const username='wiki-'+randomUUID().slice(0,8),password='Private daily wikilink passphrase';
  const result=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.org',administrator:false}});expect(result.status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,extra={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...extra}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBeTruthy();return reply;};
  const save=async(date,body)=>{const loaded=await api('GET','/day-notes/'+date+'/content');return api('PUT','/day-notes/'+date+'/content',{body},{'If-Match':loaded.headers().etag});};
  return {api,save};
}
const rich=page=>page.locator('#today-journal-formatted [role=textbox]');
const dateLink=(root,date)=>root.locator(`a[data-day-note-date="${date}"]`);

test('daily wikilinks render as inline nodes and preserve Markdown through mode changes, edits, typing and paste',async({page})=>{
  await page.goto('/web/markdown-lab.html');const original='---\r\ntitle: Daily links\r\n---\r\n\r\nSee **[[2026-10-26]]**.\r\n\r\nCode `[[2026-10-27]]`.\r\n';
  await page.locator('#markdown-file').setInputFiles({name:'wiki.md',mimeType:'text/markdown',buffer:Buffer.from(original)});
  await page.locator('#markdown-rich').click();const formatted=page.locator('#markdown-formatted');await expect(dateLink(formatted,'2026-10-26')).toHaveText('2026-10-26');await expect(dateLink(formatted,'2026-10-26')).toHaveAttribute('href','/web/?day=2026-10-26');await expect(dateLink(formatted,'2026-10-27')).toHaveCount(0);
  await page.locator('#markdown-source').click();const pending=page.waitForEvent('download');await page.locator('#markdown-download').click();expect(await readFile(await(await pending).path(),'utf8')).toBe(original);
  await page.locator('#markdown-rich').click();await formatted.locator('p').last().click();await page.keyboard.press('End');await page.keyboard.press('Enter');await page.keyboard.type('[[2026-10-28]]');await expect(dateLink(formatted,'2026-10-28')).toHaveCount(1);
  await page.keyboard.press('Enter');await formatted.locator('[role=textbox]').evaluate(root=>{const data=new DataTransfer();data.setData('text/plain','[[2026-10-29]]');root.dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));});await expect(dateLink(formatted,'2026-10-29')).toHaveCount(1);
  await page.locator('#markdown-source').click();const source=await page.locator('#markdown-body').inputValue();for(const date of ['2026-10-26','2026-10-28','2026-10-29'])expect(source).toContain('[['+date+']]');expect(source).not.toContain('/web/?day=');expect(source).not.toContain('\\[\\[');
  await page.locator('#markdown-rich').click();await expect(dateLink(formatted,'2026-10-29')).toHaveCount(1);
});

test('one click opens Today and protects inline, week and day-dialog drafts before navigation',async({page,request})=>{
  test.setTimeout(90000);const errors=[];page.on('pageerror',error=>errors.push(error.message));const {api,save}=await fresh(page,request);
  const original='See [[2026-10-26]] or [[2026-10-27]].\n\nA code example `[[2026-10-29]]`.\n';
  await save('2026-10-30',original);await save('2026-10-26','# Target day\n');await save('2026-10-27','# Other day\n');
  const week=await api('GET','/week-notes/2026-W44');await api('PUT','/week-notes/2026-W44',{body:'Weekly link [[2026-10-30]].\n'},{'If-Match':week.headers().etag});
  await page.locator('#today-date').fill('2026-10-30');await expect(dateLink(rich(page),'2026-10-26')).toBeVisible();await expect(dateLink(rich(page),'2026-10-29')).toHaveCount(0);
  await dateLink(rich(page),'2026-10-26').click();await expect(page.locator('#today-date')).toHaveValue('2026-10-26');await expect(rich(page)).toContainText('Target day');await expect(page).toHaveURL(/\?day=2026-10-26$/);
  await page.locator('#today-date').fill('2026-10-30');await expect(dateLink(rich(page),'2026-10-26')).toBeVisible();await rich(page).locator('p').last().click();await page.keyboard.press('End');await page.keyboard.type(' Inline draft');
  page.once('dialog',dialog=>dialog.dismiss());await dateLink(rich(page),'2026-10-26').click();await expect(page.locator('#today-date')).toHaveValue('2026-10-30');await expect(rich(page)).toContainText('Inline draft');
  page.once('dialog',dialog=>dialog.accept());await dateLink(rich(page),'2026-10-26').click();await expect(page.locator('#today-date')).toHaveValue('2026-10-26');expect((await(await api('GET','/day-notes/2026-10-30/content')).json()).body).toBe(original);
  await page.locator('#today-month-grid button[data-week="2026-W44"]').click();const weekly=page.locator('#today-week-note-editor');await expect(dateLink(weekly,'2026-10-30')).toBeVisible();await weekly.locator('[role=textbox]').click();await page.keyboard.press('End');await page.keyboard.type(' Week draft');
  page.once('dialog',dialog=>dialog.dismiss());await dateLink(weekly,'2026-10-30').click();await expect(weekly).toBeVisible();await expect(weekly).toContainText('Week draft');
  page.once('dialog',dialog=>dialog.accept());await dateLink(weekly,'2026-10-30').click();await expect(weekly).not.toBeVisible();await expect(page.locator('#today-date')).toHaveValue('2026-10-30');
  await page.locator('#today-date').fill('2026-10-26');await expect(rich(page)).toContainText('Target day');await rich(page).click();await page.keyboard.press('End');await page.keyboard.type(' Hidden draft');
  await page.locator('[data-tab=track]').click();await page.locator('#track-month').fill('2026-10');await page.locator('button[data-track-note-date="2026-10-30"]').click();const daily=page.locator('#track-note-editor');await expect(dateLink(daily,'2026-10-27')).toBeVisible();await daily.locator('[role=textbox] p').last().click();await page.keyboard.press('End');await page.keyboard.type(' Modal draft');
  let prompts=0;const rejectSecond=dialog=>++prompts===1?dialog.accept():dialog.dismiss();page.on('dialog',rejectSecond);await dateLink(daily,'2026-10-27').click();page.off('dialog',rejectSecond);expect(prompts).toBe(2);await expect(daily).toBeVisible();await expect(daily).toContainText('Modal draft');
  const accept=dialog=>dialog.accept();page.on('dialog',accept);await dateLink(daily,'2026-10-27').click();page.off('dialog',accept);await expect(daily).not.toBeVisible();await expect(page.locator('#today-panel')).toBeVisible();await expect(page.locator('#today-date')).toHaveValue('2026-10-27');await expect(rich(page)).toContainText('Other day');expect(errors).toEqual([]);
});

test('read-only daily links and modifier clicks open the requested date without making writes',async({page,request,browser})=>{
  const {api,save}=await fresh(page,request);await save('2026-10-26','[[2026-10-27]]\n');await save('2026-10-27','# Read target\n');const token=await(await api('POST','/auth/tokens',{name:'Wikilink reader',permissions:['read'],expiresAt:null})).json();
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:process.env.TIMEKEEPER_WEB_URL.startsWith('https:')});
  try{
    const read=await context.newPage();await read.goto('/web/?day=2026-10-26');await read.locator('#auth-mode').selectOption('token');await read.locator('#token').fill(token.token);await read.locator('#login-form button[type=submit]').click();await expect(read.locator('#status')).toHaveText('Up to date');await expect(read.locator('#today-date')).toHaveValue('2026-10-26');await expect(rich(read)).toHaveAttribute('aria-readonly','true');
    const writes=[];context.on('request',r=>{if(['POST','PUT','DELETE','PATCH'].includes(r.method()))writes.push(new URL(r.url()).pathname);});
    const opened=context.waitForEvent('page');await dateLink(rich(read),'2026-10-27').click({modifiers:['ControlOrMeta']});const second=await opened;await expect(second.locator('#today-date')).toHaveValue('2026-10-27');await expect(rich(second)).toContainText('Read target');await expect(read.locator('#today-date')).toHaveValue('2026-10-26');
    await dateLink(rich(read),'2026-10-27').click();await expect(read.locator('#today-date')).toHaveValue('2026-10-27');await expect(rich(read)).toContainText('Read target');expect(writes).toEqual([]);
  }finally{await context.close();}
});
