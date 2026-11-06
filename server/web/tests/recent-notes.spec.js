import {saveMarkdown} from './editor-interactions.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {noteEntry} from './note-entry.js';
import {tooltipFor} from './tooltips.js';
import {captureScreenshot} from './screenshots.js';

test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});

async function setup(page,request){
  const username='recent-'+randomUUID().slice(0,8),password='Private recent notes password';
  const created=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});expect(created.status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;};
  return api;
}

test('Recent notes uses account days, excludes journals and protects drafts when opening notes',async({page,request})=>{
  const api=await setup(page,request);
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
  await recent.locator(`[data-recent-note="${first.id}"]`).click();await expect(page.locator('#notes-document-title')).toHaveText('Notes/Current00.md');expect((await(await api('GET','/day-entries/2020-01-02')).json()).body).toBe('Keep my unsaved Today draft');
  await page.evaluate(()=>location.hash='#account/notes');const settings=page.locator('#account-content section').filter({has:page.getByRole('heading',{name:'Notes settings',exact:true})});
  const days=settings.getByLabel('Recent notes: number of days',{exact:true});await expect(days).toHaveValue('7');await days.fill('1');await settings.getByRole('button',{name:'Save',exact:true}).click();await expect(settings).toContainText('Notes settings saved.');
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await page.locator('[data-tab=today]').click();await expect(recent.locator('[data-recent-note]')).toHaveCount(20);await recent.getByRole('button',{name:'More notes',exact:true}).click();await expect(recent.locator('[data-recent-note]')).toHaveCount(21);await expect(recent).not.toContainText('Five days ago');
  for(const width of [1440,390,320]){await page.setViewportSize({width,height:900});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}
});

test('note views use event titles and document headings while paths and Markdown stay intact',async({page,request})=>{
  const api=await setup(page,request);await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const upload=async(path,text)=>(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(text),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const ordinaryText='---\ntitle: Metadata is not the headline\n---\n\n```markdown\n# Example only\n```\n\n## Project **review**\n\nPrivate body\n';
  const prefix=ordinaryText.slice(0,ordinaryText.indexOf('\n---\n')+5);
  const ordinary=await upload('Notes/document.md',ordinaryText),plain=await upload('Notes/plain.md','Plain body without a heading.');
  const subscription=randomUUID(),eventKey='cd'.repeat(32),title='Planning <img src=x onerror="window.noteTitleExecuted=true"> & review';
  const eventPath='events/event-'+subscription+'-'+eventKey+'.md',eventText='---\ntimekeeper-event: '+JSON.stringify({subscriptionId:subscription,eventKey,event:{uid:'private-title-test',recurrenceId:'',title}})+'\n---\n# Different note heading\n\nDecisions\n';
  const event=await upload(eventPath,eventText);await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');
  const recent=page.locator('#today-recent-notes');
  await expect(recent.locator(`[data-recent-note="${ordinary.id}"] strong`)).toHaveText('Project review');await expect(recent.locator(`[data-recent-note="${plain.id}"] strong`)).toHaveText('plain.md');await expect(recent.locator(`[data-recent-note="${event.id}"] strong`)).toHaveText(title);
  const eventTooltip=await tooltipFor(recent.locator(`[data-recent-note="${event.id}"]`));await expect(eventTooltip).toContainText(title);await expect(eventTooltip).not.toContainText(eventKey);await expect(eventTooltip.locator('img')).toHaveCount(0);await page.keyboard.press('Escape');
  await expect(recent.locator('img')).toHaveCount(0);expect(await page.evaluate(()=>window.noteTitleExecuted)).toBeUndefined();
  await recent.locator(`[data-recent-note="${event.id}"]`).click();await expect(page.locator('#notes-document-title')).toHaveText(title);const navigation=page.locator('#notes-navigation');await navigation.getByRole('button',{name:'events',exact:true}).click();await expect(noteEntry(navigation,eventPath)).toHaveText(title);const navigationTooltip=await tooltipFor(noteEntry(navigation,eventPath));await expect(navigationTooltip).toHaveText(title);await expect(navigationTooltip).not.toContainText(eventKey);await page.keyboard.press('Escape');
  await navigation.getByRole('button',{name:'Notes',exact:true}).click();await expect(noteEntry(navigation,ordinary.path)).toHaveText('Project review');await noteEntry(navigation,ordinary.path).click();await expect(page.locator('#notes-document-title')).toHaveText('Project review');
  await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();await expect(page.locator('#notes-body')).toHaveValue(ordinaryText.slice(prefix.length));
  const updated='# Updated review\n\nThe filename stays document.md.\n';await page.locator('#notes-body').fill(updated);await saveMarkdown(page.locator('#notes-panel'));await expect(page.locator('#notes-document-title')).toHaveText('Updated review');await expect(noteEntry(navigation,ordinary.path)).toHaveText('Updated review');
  await noteEntry(navigation,plain.path).click();await expect(page.locator('#notes-document-title')).toHaveText(plain.path);
  await page.locator('[data-tab=today]').click();await expect(recent.locator(`[data-recent-note="${ordinary.id}"] strong`)).toHaveText('Updated review');
  expect(await(await api('GET','/notes/entries/'+ordinary.id+'/content')).text()).toBe(prefix+updated);expect(await(await api('GET','/notes/entries/'+event.id+'/content')).text()).toBe(eventText);
  expect((await new AxeBuilder({page}).include('.today-sidebar').analyze()).violations).toEqual([]);
  await page.setViewportSize({width:1440,height:900});await captureScreenshot(page,{path:'test-results/note-display-titles.png',fullPage:false});
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
