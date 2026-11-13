import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {clickNoteAction,notesSource} from './editor-interactions.js';
import {noteEntry} from './note-entry.js';
import {openAccountMenu} from './navigation.js';
import {captureScreenshot} from './screenshots.js';

test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin};const status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function setup(page,request){
  const username='note-clients-'+randomUUID().slice(0,8),password='Private note client test passphrase';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;};
  const alpha=await(await api('POST','/clients',{name:'Alpha client',color:'#345678'})).json(),beta=await(await api('POST','/clients',{name:'Beta client with a long name for a narrow editor',color:'#b03060'})).json();
  await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const source='# Client document\r\n\r\nExact original body.\r\n',entry=await(await api('PUT','/notes/files?path=Notes%2FClient.md',Buffer.from(source),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.locator('#refresh').click();await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');return {api,client,alpha,beta,entry,source};
}
async function openNote(page,path='Notes/Client.md'){
  await page.locator('[data-tab=notes]').click();const navigation=page.locator('#notes-navigation');await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await expect(navigation.locator('[role=tree]')).toHaveAttribute('aria-busy','false');
  const folder=noteEntry(navigation,'Notes');if(await folder.locator('..').getAttribute('aria-expanded')!=='true')await folder.click();await noteEntry(navigation,path).click();await expect(page.locator('#notes-document-title')).toBeVisible();
}
async function choose(page,scope,client){
  await clickNoteAction(page,scope,'Associate with client or project');const dialog=page.getByRole('dialog',{name:'Associate with client or project',exact:true});await expect(dialog.getByLabel('Client',{exact:true})).toBeEnabled();await dialog.getByLabel('Client',{exact:true}).selectOption(client||'');await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();
}
const association=entry=>'/notes/client?'+new URLSearchParams({sourceType:entry.sourceType,sourceKey:entry.sourceKey});

test('an optional note project is inherited by existing and new tasks and client changes update them together',async({page,request})=>{
  const {api,alpha,beta,entry}=await setup(page,request),project=await(await api('POST','/projects',{name:'Shared note project',clientId:alpha.id,icon:null})).json(),path='/notes/entries/'+entry.id+'/content',before=await api('GET',path);
  await api('PUT',path,Buffer.from('- [ ] Existing checklist task\r\n'),{'If-Match':before.headers().etag,'Content-Type':'application/octet-stream'});await page.locator('#refresh').click();await openNote(page);const scope=page.locator('#notes-panel');
  await clickNoteAction(page,scope,'Associate with client or project');const dialog=page.getByRole('dialog',{name:'Associate with client or project',exact:true});await dialog.getByLabel('Project',{exact:true}).selectOption(project.id);await expect(dialog.getByLabel('Client',{exact:true})).toHaveValue(alpha.id);await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();await expect(scope.locator('.note-client-label')).toHaveText(alpha.name+' · '+project.name);
  let tasks=(await(await api('GET','/tasks?limit=500')).json()).items;expect(tasks[0].projectId).toBe(project.id);let content=await api('GET',path);await api('PUT',path,Buffer.from('- [ ] Existing checklist task\r\n- [ ] New checklist task\r\n'),{'If-Match':content.headers().etag,'Content-Type':'application/octet-stream'});tasks=(await(await api('GET','/tasks?limit=500')).json()).items;expect(tasks).toHaveLength(2);expect(tasks.every(task=>task.projectId===project.id)).toBe(true);
  await choose(page,scope,beta.id);tasks=(await(await api('GET','/tasks?limit=500')).json()).items;expect(tasks.every(task=>task.projectId===null&&task.clientId===beta.id)).toBe(true);expect((await(await api('GET',association(entry))).json()).projectId).toBeNull();await expect(scope.locator('.note-client-label')).toHaveText(beta.name);
});

test('No client clears a selected project and its inherited client',async({page,request})=>{
  const {api,alpha,entry}=await setup(page,request),project=await(await api('POST','/projects',{name:'Temporary project',clientId:alpha.id})).json();
  await page.locator('#refresh').click();await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');await openNote(page);
  const scope=page.locator('#notes-panel');await clickNoteAction(page,scope,'Associate with client or project');const dialog=page.getByRole('dialog',{name:'Associate with client or project',exact:true});
  await dialog.getByLabel('Project',{exact:true}).selectOption(project.id);await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();
  await clickNoteAction(page,scope,'Associate with client or project');await expect(dialog.getByLabel('Client',{exact:true})).toBeEnabled();await dialog.getByLabel('Client',{exact:true}).selectOption('');await expect(dialog.getByLabel('Project',{exact:true})).toHaveValue('');
  await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();const value=await(await api('GET',association(entry))).json();expect(value.clientId).toBeNull();expect(value.projectId).toBeNull();await expect(scope.locator('.note-client-label')).toBeHidden();
});

test('client choice includes clients outside the focus filter and preserves note bytes, dates and a failed body draft',async({page,request})=>{
  const {api,alpha,beta,entry,source}=await setup(page,request),focusId=randomUUID();const focus=await api('GET','/focus-areas');await api('PUT','/focus-areas',{areas:[{id:focusId,name:'Only Alpha',clientIds:[alpha.id]}]},{'If-Match':focus.headers().etag});
  await page.locator('#refresh').click();await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');await openAccountMenu(page);await page.locator('#focus-select').selectOption(focusId);await page.getByRole('button',{name:'Account menu',exact:true}).click();await openNote(page);
  const scope=page.locator('#notes-panel'),before=await api('GET','/notes/entries/'+entry.id),metadata=await before.json();
  await choose(page,scope,beta.id);await expect(scope.locator('.note-client-label')).toHaveText(beta.name);await expect(scope.locator('.note-client-swatch')).toHaveCSS('background-color','rgb(176, 48, 96)');
  const after=await api('GET','/notes/entries/'+entry.id),saved=await after.json();expect(after.headers().etag).toBe(before.headers().etag);expect(saved.createdAt).toBe(metadata.createdAt);expect(saved.modifiedAt).toBe(metadata.modifiedAt);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(source);
  await page.route('**/api/v1/notes/entries/*/content',route=>route.request().method()==='PUT'?route.abort('internetdisconnected'):route.continue());const draft='A private body draft that must remain unsaved.';await(await notesSource(page)).fill(draft);
  await choose(page,scope,alpha.id);await expect(scope.locator('.note-client-label')).toHaveText(alpha.name);await expect(page.locator('#notes-body')).toHaveValue(draft);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(source);
  await choose(page,scope,null);await expect(scope.locator('.note-client-label')).toBeHidden();await expect(page.locator('#notes-body')).toHaveValue(draft);
});

test('empty daily and weekly pages and an ordinary note popup share independent client metadata',async({page,request})=>{
  const {api,alpha,beta,entry}=await setup(page,request),day='2026-11-19';await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(day);const today=page.locator('#today-note-editor');await expect(today.locator('.ProseMirror')).toBeVisible();
  await choose(page,today,alpha.id);await expect(today.locator('.note-client-label')).toHaveText(alpha.name);const daily=await(await api('GET','/day-notes/'+day+'/content')).json();expect(daily.version).toBe(0);expect(daily.body).toBe('');
  await page.locator('#today-month-grid [data-week]').first().click();const weekly=page.locator('#today-week-note-editor');await expect(weekly).toBeVisible();await choose(page,weekly,beta.id);await expect(weekly.locator('.note-client-label')).toHaveText(beta.name);await weekly.getByRole('button',{name:'Close',exact:true}).click();
  const recent=page.locator('#today-recent-notes [data-recent-note="'+entry.id+'"]');await expect(recent.locator('.today-recent-note-label')).toHaveText('Client');await recent.click();const popup=page.locator('#note-popup-editor');await expect(popup).toBeVisible();await expect(popup.locator('#note-popup-title')).toHaveText('Client');await choose(page,popup,beta.id);await expect(popup.locator('.note-client-label')).toHaveText(beta.name);await popup.getByRole('button',{name:'Close',exact:true}).click();
  await openNote(page);await expect(page.locator('#notes-panel .note-client-label')).toHaveText(beta.name);expect((await(await api('GET',association(entry))).json()).clientId).toBe(beta.id);
});

test('a stale client choice reloads actual metadata and requires an explicit second decision',async({page,request})=>{
  const {api,alpha,beta,entry}=await setup(page,request);await openNote(page);const scope=page.locator('#notes-panel');await clickNoteAction(page,scope,'Associate with client or project');const dialog=page.getByRole('dialog',{name:'Associate with client or project',exact:true});await expect(dialog.getByLabel('Client',{exact:true})).toBeEnabled();await dialog.getByLabel('Client',{exact:true}).selectOption(alpha.id);
  const current=await api('GET',association(entry));await api('PUT',association(entry),{clientId:beta.id},{'If-Match':current.headers().etag});let writes=0;page.on('request',request=>{if(request.method()==='PUT'&&new URL(request.url()).pathname==='/api/v1/notes/client')writes++;});
  await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog.getByRole('alert')).toBeVisible();await expect(dialog.getByLabel('Client',{exact:true})).toHaveValue(beta.id);await expect(scope.locator('.note-client-label')).toHaveText(beta.name);expect(writes).toBe(1);
  await dialog.getByLabel('Client',{exact:true}).selectOption(alpha.id);await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();await expect(scope.locator('.note-client-label')).toHaveText(alpha.name);expect(writes).toBe(2);
});

test('an uncertain client save reads its outcome without repeating the command',async({page,request})=>{
  const {api,alpha,entry}=await setup(page,request);await openNote(page);let writes=0;await page.route('**/api/v1/notes/client?*',async route=>{if(route.request().method()!=='PUT')return route.continue();writes++;await route.fetch();await route.abort('internetdisconnected');});
  await clickNoteAction(page,page.locator('#notes-panel'),'Associate with client or project');const dialog=page.getByRole('dialog',{name:'Associate with client or project',exact:true});await expect(dialog.getByLabel('Client',{exact:true})).toBeEnabled();await dialog.getByLabel('Client',{exact:true}).selectOption(alpha.id);await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog.getByRole('alert')).toBeVisible();await expect(dialog.getByLabel('Client',{exact:true})).toHaveValue(alpha.id);await expect(dialog.getByLabel('Client',{exact:true})).toBeEnabled();expect(writes).toBe(1);expect((await(await api('GET',association(entry))).json()).clientId).toBe(alpha.id);
  await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();expect(writes).toBe(1);
});

test('client labels and the keyboard selector fit all themes at desktop and phone sizes',async({page,request})=>{
  const {beta}=await setup(page,request);await openNote(page);const scope=page.locator('#notes-panel');await choose(page,scope,beta.id);
  for(const width of [1440,320]){
    await page.setViewportSize({width,height:900});for(const theme of ['timekeeper','timekeeper-dark']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=(theme==='timekeeper')?'light':'dark';},theme);
      await expect(scope.locator('.note-client-label')).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      const swatch=scope.locator('.note-client-swatch');await expect(swatch).toHaveCSS('background-color','rgb(176, 48, 96)');
      const label=await scope.locator('.note-client-label').boundingBox(),card=await scope.locator('.notes-editor').boundingBox(),toolbar=await scope.locator('.markdown-toolbar').boundingBox();expect(label.y+label.height).toBeLessThanOrEqual(toolbar.y+1);expect(label.x+label.width).toBeGreaterThan(card.x+card.width/2);expect(label.y-card.y).toBeLessThan(40);
      await clickNoteAction(page,scope,'Associate with client or project');const dialog=page.getByRole('dialog',{name:'Associate with client or project',exact:true});await expect(dialog.getByLabel('Client',{exact:true})).toBeFocused();expect((await new AxeBuilder({page}).include('.note-client-dialog[open]').analyze()).violations).toEqual([]);await page.keyboard.press('Escape');await expect(dialog).toBeHidden();await expect(page.locator('#notes-main-actions').getByRole('button',{name:'Associate with client or project',exact:true})).toBeFocused();
    }
    await captureScreenshot(page,{path:`test-results/note-client-label-${width}.png`});
  }
});

test('a delayed client read cannot label the next document with the previous client',async({page,request})=>{
  const {api,alpha,beta,entry}=await setup(page,request),other=await(await api('PUT','/notes/files?path=Notes%2FOther.md',Buffer.from('# Other\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  for(const [note,client]of [[entry,beta],[other,alpha]]){const current=await api('GET',association(note));await api('PUT',association(note),{clientId:client.id},{'If-Match':current.headers().etag});}
  let held=false,release;const pending=new Promise(resolve=>{release=resolve;});await page.route('**/api/v1/notes/client?*',async route=>{if(new URL(route.request().url()).searchParams.get('sourceKey')!==entry.sourceKey)return route.continue();const response=await route.fetch();held=true;await pending;await route.fulfill({response}).catch(()=>{});});
  try{
    await openNote(page);await expect.poll(()=>held).toBe(true);await noteEntry(page.locator('#notes-navigation'),'Notes/Other.md').click();await expect(page.locator('#notes-panel .note-client-label')).toHaveText(alpha.name);release();await page.unrouteAll({behavior:'wait'});await expect(page.locator('#notes-panel .note-client-label')).toHaveText(alpha.name);
  }finally{release();await page.unrouteAll({behavior:'wait'});}
});

test('a read-only session sees the client label and cannot change the association',async({page,request,browser})=>{
  const {api,alpha,entry}=await setup(page,request);const current=await api('GET',association(entry));await api('PUT',association(entry),{clientId:alpha.id},{'If-Match':current.headers().etag});
  const token=await(await api('POST','/auth/tokens',{name:'Read-only note client','permissions':['read'],'expiresAt':null})).json(),context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true});
  try{
    const reader=await context.newPage();let writes=0;reader.on('request',request=>{if(request.method()==='PUT'&&new URL(request.url()).pathname==='/api/v1/notes/client')writes++;});
    await reader.goto('/web/');await reader.locator('#auth-mode').selectOption('token');await reader.locator('#token').fill(token.token);await reader.locator('#login-form button[type=submit]').click();await expect(reader.locator('#workspace')).toBeVisible();await openNote(reader);
    const scope=reader.locator('#notes-panel');await expect(scope.locator('.note-client-label')).toHaveText(alpha.name);await expect(reader.locator('#notes-main-actions').getByRole('button',{name:'Associate with client or project',exact:true})).toBeDisabled();expect(writes).toBe(0);expect((await(await api('GET',association(entry))).json()).clientId).toBe(alpha.id);
  }finally{await context.close();}
});

test('a failed metadata read can be retried and the loading selector remains cancellable',async({page,request})=>{
  const {entry,source,api}=await setup(page,request);let reads=0,writes=0,held=false,release;const pending=new Promise(resolve=>{release=resolve;});
  await page.route('**/api/v1/notes/client?*',async route=>{
    if(route.request().method()==='PUT'){writes++;return route.continue();}if(new URL(route.request().url()).searchParams.get('sourceKey')!==entry.sourceKey)return route.continue();
    if(++reads===1)return route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'UNAVAILABLE'})});const response=await route.fetch();held=true;await pending;await route.fulfill({response});
  });
  try{
    await openNote(page);await expect(page.locator('#notes-panel > [role=alert]')).toBeVisible();await clickNoteAction(page,page.locator('#notes-panel'),'Associate with client or project');await expect.poll(()=>held).toBe(true);
    const dialog=page.getByRole('dialog',{name:'Associate with client or project',exact:true});await expect(dialog.getByLabel('Client',{exact:true})).toBeDisabled();await expect(dialog.getByRole('button',{name:'Cancel',exact:true})).toBeEnabled();await page.keyboard.press('Escape');await expect(dialog).toBeHidden();release();await page.unrouteAll({behavior:'wait'});expect(writes).toBe(0);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(source);
  }finally{release();await page.unrouteAll({behavior:'wait'});}
});

test('signing out clears client labels and the private chooser contents',async({page,request})=>{
  const {alpha}=await setup(page,request);await openNote(page);await choose(page,page.locator('#notes-panel'),alpha.id);await expect(page.locator('#notes-panel .note-client-label')).toHaveText(alpha.name);
  await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login-form')).toBeVisible();
  expect(await page.locator('.note-client-label').allTextContents()).not.toContain(alpha.name);await expect(page.locator('.note-client-dialog option')).toHaveCount(0);
});

test('a client deleted while the chooser is open reports the conflict and permits a fresh choice',async({page,request})=>{
  const {api,alpha,beta,entry}=await setup(page,request);await openNote(page);await clickNoteAction(page,page.locator('#notes-panel'),'Associate with client or project');const dialog=page.getByRole('dialog',{name:'Associate with client or project',exact:true});await expect(dialog.getByLabel('Client',{exact:true})).toBeEnabled();await dialog.getByLabel('Client',{exact:true}).selectOption(beta.id);
  const refreshed=page.waitForResponse(async response=>response.request().method()==='GET'&&new URL(response.url()).pathname==='/api/v1/clients'&&!(await response.json()).items.some(client=>client.id===beta.id));
  const client=await api('GET','/clients/'+beta.id);await api('DELETE','/clients/'+beta.id,undefined,{'If-Match':client.headers().etag});await page.evaluate(()=>document.dispatchEvent(new CustomEvent('timekeeper:notes-changed')));await refreshed;await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');
  await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog.getByRole('alert')).toBeVisible();await expect(dialog.locator(`option[value="${beta.id}"]`)).toHaveCount(0);await expect(dialog.getByLabel('Client',{exact:true})).toBeEnabled();
  await dialog.getByLabel('Client',{exact:true}).selectOption(alpha.id);await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();expect((await(await api('GET',association(entry))).json()).clientId).toBe(alpha.id);
});
