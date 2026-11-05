import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';

test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});

test('Recent notes uses account days, excludes journals and protects drafts when opening notes',async({page,request})=>{
  const username='recent-'+randomUUID().slice(0,8),password='Private recent notes password';
  const created=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});expect(created.status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;};
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'UTC',locale:'en-GB'},{'If-Match':profile.headers().etag});
  await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const now=Date.now(),today=new Date(now).toISOString().slice(0,10),ago=days=>new Date(now-days*86400000).toISOString();
  const upload=async(path,modifiedAt=ago(0))=>(await api('PUT','/notes/files?'+new URLSearchParams({path,modifiedAt}),Buffer.from('Body of '+path),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const first=await upload('Notes/Current00.md');for(let n=1;n<21;n++)await upload(`Notes/Current${String(n).padStart(2,'0')}.md`);
  await upload('Notes/Five days ago.md',ago(5));await upload('Notes/Too old.md',ago(10));await upload('journal/'+today+'.md');await upload('journal/Private.md');
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');
  const recent=page.locator('#today-recent-notes');await expect(recent.locator('[data-recent-note]')).toHaveCount(20);
  expect(await page.locator('#today-planned-tasks').evaluate(node=>node.closest('section').nextElementSibling.id||node.closest('section').nextElementSibling.getAttribute('aria-labelledby'))).toBe('today-recent-notes-title');
  await recent.getByRole('button',{name:'More notes',exact:true}).click();await expect(recent.locator('[data-recent-note]')).toHaveCount(22);await expect(recent).not.toContainText('Too old');await expect(recent).not.toContainText('journal/');
  await page.locator('#today-date').fill('2020-01-02');await expect(page.locator('#today-title')).toContainText('2020');await expect(recent.locator('[data-recent-note]')).toHaveCount(22);
  expect((await new AxeBuilder({page}).include('.today-sidebar').analyze()).violations).toEqual([]);
  await page.locator('#today-journal').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#today-journal-body').fill('Keep my unsaved Today draft');
  page.once('dialog',dialog=>dialog.dismiss());await recent.locator(`[data-recent-note="${first.id}"]`).click();await expect(page.locator('#today-panel')).toBeVisible();await expect(page.locator('#today-journal-body')).toHaveValue('Keep my unsaved Today draft');
  page.once('dialog',dialog=>dialog.accept());await recent.locator(`[data-recent-note="${first.id}"]`).click();await expect(page.locator('#notes-document-title')).toHaveText('Notes/Current00.md');
  await page.evaluate(()=>location.hash='#account/notes');const settings=page.locator('#account-content section').filter({has:page.getByRole('heading',{name:'Notes settings',exact:true})});
  const days=settings.getByLabel('Recent notes: number of days',{exact:true});await expect(days).toHaveValue('7');await days.fill('1');await settings.getByRole('button',{name:'Save',exact:true}).click();await expect(settings).toContainText('Notes settings saved.');
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await page.locator('[data-tab=today]').click();await expect(recent.locator('[data-recent-note]')).toHaveCount(20);await recent.getByRole('button',{name:'More notes',exact:true}).click();await expect(recent.locator('[data-recent-note]')).toHaveCount(21);await expect(recent).not.toContainText('Five days ago');
  for(const width of [1440,390,320]){await page.setViewportSize({width,height:900});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}
});

test('Recent notes ignores stale account responses and refreshes after local midnight',async({page})=>{
  await page.clock.install({time:new Date('2026-11-05T12:00:00Z')});await page.goto('/web/');
  await page.evaluate(async()=>{
    const {RecentNotes}=await import('/web/recent-notes.js'),root=document.createElement('div');root.id='recent-lifecycle-test';document.body.append(root);
    const entry=path=>({id:path,path,modifiedAt:'2026-11-05T12:00:00Z'});
    let oldReply;const old={request:()=>new Promise(resolve=>{oldReply=resolve;})};
    let calls=0;const current={request:async()=>{calls++;return {data:{snapshot:'current',items:[entry('Current owner.md')]}};}};
    const list=new RecentNotes(root,{onUnauthorized:()=>{throw new Error('Unexpected authorization failure');}});
    list.update(old,'UTC',1);list.show();list.update(current,'UTC',1);
    await new Promise(resolve=>queueMicrotask(resolve));oldReply({data:{snapshot:'old',items:[entry('Other account secret.md')]}});
    await new Promise(resolve=>queueMicrotask(resolve));
    window.recentLifecycle={list,calls:()=>calls};
  });
  const root=page.locator('#recent-lifecycle-test');await expect(root).toContainText('Current owner.md');await expect(root).not.toContainText('Other account secret');
  expect(await page.evaluate(()=>window.recentLifecycle.calls())).toBe(1);
  await page.evaluate(()=>window.recentLifecycle.list.refresh());expect(await page.evaluate(()=>window.recentLifecycle.calls())).toBe(1);
  await page.clock.setFixedTime(new Date('2026-11-06T00:00:00Z'));await page.evaluate(()=>window.recentLifecycle.list.refresh());expect(await page.evaluate(()=>window.recentLifecycle.calls())).toBe(2);
  await page.evaluate(()=>{const {list}=window.recentLifecycle;list.hide();list.refresh(true);});expect(await page.evaluate(()=>window.recentLifecycle.calls())).toBe(2);
  await page.evaluate(()=>window.recentLifecycle.list.reset());await expect(root).toBeEmpty();
});
