import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {browserRequest} from './browser-request.js';
import {noteEntry} from './note-entry.js';
import {notesSource,clickNoteAction,noteActions} from './editor-interactions.js';
test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin};const status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function setup(page,request){
  const username='notes-'+randomUUID().slice(0,8),password='Private collection test password';
  const created=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});expect(created.status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page);const identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;};
  await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-title')).toBeVisible();return api;
}

async function dailyNote(page){
  // Load the target day before switching its fully rendered editor to source.
  await page.goto('/web/?day=2026-11-02');await expect(page.locator('#status')).toHaveText('Up to date');
  await page.locator('[data-tab=today]').click();
  const daily=page.locator('#today-note-editor');
  await expect(daily.getByRole('textbox',{name:'Note body',exact:true})).toBeVisible();
  await expect(daily.locator('.dialog-actions > [role=status]')).toHaveText('Saved');
  await daily.getByRole('button',{name:'Markdown source',exact:true}).click();
  await expect(page.locator('#today-journal-body')).toBeVisible();return daily;
}

async function document(page,api,name='First.md'){
  const entry=await(await api('PUT','/notes/files?'+new URLSearchParams({path:'Notes/'+name}),Buffer.from('Original'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();
  const folder=noteEntry(page.locator('#notes-navigation'),'Notes');if(await folder.locator('..').getAttribute('aria-expanded')!=='true')await folder.click();
  await noteEntry(page.locator('#notes-navigation'),entry.path).click();await notesSource(page);return entry;
}

test('Notes saves periodically and before document, tab, account and sign-out navigation',async({page,request})=>{
  const api=await setup(page,request),entry=await document(page,api),body=page.locator('#notes-body');
  await expect(page.locator('#notes-panel').getByRole('button',{name:'Save',exact:true})).toHaveCount(0);await expect(page.locator('#notes-panel').getByRole('button',{name:'Cancel',exact:true})).toHaveCount(0);
  await body.fill('Saved without a button');await expect.poll(async()=>(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('Saved without a button');
  const second=await(await api('PUT','/notes/files?path=Notes%2FSecond.md',Buffer.from('Second'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await expect(noteEntry(page.locator('#notes-navigation'),second.path)).toBeVisible();
  await body.fill('Saved on document navigation');await noteEntry(page.locator('#notes-navigation'),second.path).click();await expect(page.locator('#notes-document-title')).toHaveText(second.path.split('/').at(-1).replace(/\.md$/i,''));expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('Saved on document navigation');
  await notesSource(page);await body.fill('Saved on tab navigation');await page.locator('[data-tab=today]').click();await expect(page.locator('#today-panel')).toBeVisible();expect(await(await api('GET','/notes/entries/'+second.id+'/content')).text()).toBe('Saved on tab navigation');
  await page.locator('[data-tab=notes]').click();await body.fill('Saved before account settings');await page.evaluate(()=>location.hash='#account/profile');await expect(page.locator('#account-page')).toBeVisible();expect(await(await api('GET','/notes/entries/'+second.id+'/content')).text()).toBe('Saved before account settings');
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await body.fill('Saved before logout');await page.locator('#account-menu > summary').click();await page.locator('#sign-out').click();await expect(page.locator('#login')).toBeVisible();
});

test('typing during a slow autosave remains editable and navigation waits for the newest draft',async({page,request})=>{
  const api=await setup(page,request),entry=await document(page,api);let release;const gate=new Promise(resolve=>release=resolve),writes=[];
  await page.route('**/api/v1/notes/entries/'+entry.id+'/content',async route=>{if(route.request().method()!=='PUT'){await route.continue();return;}writes.push(route.request().postData());if(writes.length===1)await gate;await route.continue();});
  try{
    await page.locator('#notes-body').fill('First write');await expect.poll(()=>writes.length).toBe(1);await expect(page.locator('#notes-body')).toBeEditable();await page.locator('#notes-body').fill('Latest draft while saving');await page.locator('[data-tab=today]').click();await expect(page.locator('#notes-panel')).toBeVisible();release();
    await expect(page.locator('#today-panel')).toBeVisible();expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('Latest draft while saving');expect(writes).toEqual(['First write','Latest draft while saving']);
  }finally{release();await page.unrouteAll({behavior:'wait'});}
});

test('a lost autosave reply is checked by reading the result without duplicating the write',async({page,request})=>{
  const api=await setup(page,request),entry=await document(page,api);let writes=0;
  const path='**/api/v1/notes/entries/'+entry.id+'/content';await page.route(path,async route=>{if(route.request().method()!=='PUT'){await route.continue();return;}writes++;await route.fetch();await route.abort('connectionfailed');});
  await page.locator('#notes-body').fill('Committed but reply lost');await expect(page.locator('#notes-panel > .alert')).toBeVisible();await page.locator('[data-tab=today]').click();await expect(page.locator('#notes-panel')).toBeVisible();await expect(page.locator('#notes-body')).toHaveValue('Committed but reply lost');
  await page.unroute(path);await page.locator('.notes-footer').getByRole('button',{name:'Check saved result',exact:true}).click();await expect(page.locator('.notes-footer [role=status]')).toHaveText('Saved');expect(writes).toBe(1);await page.locator('[data-tab=today]').click();await expect(page.locator('#today-panel')).toBeVisible();
});

test('stale autosaves preserve the draft and keep document navigation in the editor',async({page,request})=>{
  const api=await setup(page,request),entry=await document(page,api);await page.locator('#notes-body').fill('Keep my draft');
  const current=await api('GET','/notes/entries/'+entry.id+'/content');await api('PUT','/notes/entries/'+entry.id+'/content',Buffer.from('Peer version'),{'Content-Type':'application/octet-stream','If-Match':current.headers().etag});
  await page.locator('#notes-body').press('ControlOrMeta+s');await expect(page.locator('#notes-panel > .alert')).toBeVisible();await page.locator('[data-tab=today]').click();await expect(page.locator('#notes-panel')).toBeVisible();await expect(page.locator('#notes-body')).toHaveValue('Keep my draft');expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('Peer version');
});

test('an autosave that never reached the server retries safely after checking the unchanged version',async({page,request})=>{
  const api=await setup(page,request),entry=await document(page,api);let writes=0;const path='**/api/v1/notes/entries/'+entry.id+'/content';
  await page.route(path,async route=>{if(route.request().method()!=='PUT'){await route.continue();return;}if(++writes===1)await route.abort('internetdisconnected');else await route.continue();});
  await page.locator('#notes-body').fill('Recovered after reconnecting');await expect(page.locator('#notes-panel > .alert')).toBeVisible();expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('Original');
  await page.locator('.notes-footer').getByRole('button',{name:'Check saved result',exact:true}).click();await expect.poll(async()=>(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('Recovered after reconnecting');expect(writes).toBe(2);
});

test('checking an unknown daily-note save retains edits made while its first write was pending',async({page,request})=>{
  const api=await setup(page,request),daily=await dailyNote(page);let release,writes=0;const gate=new Promise(resolve=>release=resolve),path='**/api/v1/day-entries/2026-11-02';
  await page.route(path,async route=>{if(route.request().method()!=='POST'){await route.continue();return;}writes++;if(writes===1){await route.fetch();await gate;await route.abort('connectionfailed');}else await route.continue();});
  try{
    await page.locator('#today-journal-body').fill('First body');await expect.poll(()=>writes).toBe(1);await expect(page.locator('#today-journal-body')).toBeEditable();await page.locator('#today-journal-body').fill('Newest body');release();await expect(daily.getByRole('alert')).toContainText('could not be confirmed');
    await daily.getByRole('button',{name:'Check saved result',exact:true}).click();await expect(page.locator('#today-journal-body')).toHaveValue('Newest body');await expect.poll(async()=>(await(await api('GET','/day-entries/2026-11-02')).json()).body).toBe('Newest body');expect(writes).toBe(2);
  }finally{release();await page.unrouteAll({behavior:'wait'});}
});

test('daily and weekly notes autosave on edits and close with Delete note below revisions',async({page,request})=>{
  const api=await setup(page,request);await dailyNote(page);let release,writes=0;const gate=new Promise(resolve=>release=resolve),path='**/api/v1/day-entries/2026-11-02';
  await page.route(path,async route=>{if(route.request().method()!=='POST'){await route.continue();return;}if(++writes===1)await gate;await route.continue();});
  try{
    await page.locator('#today-journal-body').fill('# Daily :rocket:\n');await expect.poll(()=>writes).toBe(1);await page.locator('#today-note-text').fill('Independent label');
    await page.locator('#today-next').click();await expect(page.locator('#today-date')).toHaveValue('2026-11-02');release();await expect(page.locator('#today-date')).toHaveValue('2026-11-03');
    const saved=await(await api('GET','/day-entries/2026-11-02')).json();expect(saved.body).toBe('# Daily :rocket:\n');expect(saved.text).toBe('Independent label');expect(writes).toBe(2);
  }finally{release();await page.unrouteAll({behavior:'wait'});}
  await page.locator('[data-week="2026-W45"]').click();const weekly=page.locator('#today-week-note-editor');await expect(weekly).toBeVisible();await weekly.getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#weekly-journal-body').fill('# Weekly :smile:\n');await page.keyboard.press('Escape');await expect(weekly).toBeHidden();expect((await(await api('GET','/week-notes/2026-W45')).json()).body).toBe('# Weekly :smile:\n');
  await page.locator('[data-week="2026-W45"]').click();await expect(weekly.locator('[data-emoji=smile]')).toBeVisible();const menu=await noteActions(page,weekly),labels=await menu.getByRole('menuitem').allTextContents();expect(labels.findIndex(value=>value.includes('Delete note'))).toBeGreaterThan(labels.findIndex(value=>value.includes('Note revisions')));await page.keyboard.press('Escape');
  page.once('dialog',dialog=>dialog.accept());await clickNoteAction(page,weekly,'Delete note');await expect(weekly).toBeHidden();expect((await(await api('GET','/week-notes/2026-W45')).json()).deleted).toBe(true);
});

test('a body autosave accepts an independent peer label without writing the old label back',async({page,request})=>{
  const api=await setup(page,request),daily=await dailyNote(page);let release,writes=0;const gate=new Promise(resolve=>release=resolve);
  await page.route('**/api/v1/day-entries/2026-11-02',async route=>{if(route.request().method()!=='POST'){await route.continue();return;}writes++;if(writes===1)await gate;await route.continue();});
  try{
    await page.locator('#today-journal-body').fill('My body');await expect.poll(()=>writes).toBe(1);const label=await api('GET','/day-labels/2026-11-02');await api('PUT','/day-labels/2026-11-02',{text:'Peer label'},{'If-Match':label.headers().etag});release();await expect(daily.locator('.dialog-actions > [role=status]')).toHaveText('Saved');await expect(page.locator('#today-note-text')).toHaveValue('Peer label');
    await page.locator('[data-tab=notes]').click();const value=await(await api('GET','/day-entries/2026-11-02')).json();expect(value.text).toBe('Peer label');expect(value.body).toBe('My body');expect(writes).toBe(1);
  }finally{release();await page.unrouteAll({behavior:'wait'});}
});

test('a label autosave cannot adopt a peer body version for newer local body edits',async({page,request})=>{
  const api=await setup(page,request),daily=await dailyNote(page);let release,writes=0;const gate=new Promise(resolve=>release=resolve);
  await page.route('**/api/v1/day-entries/2026-11-02',async route=>{if(route.request().method()!=='POST'){await route.continue();return;}writes++;if(writes===1)await gate;await route.continue();});
  try{
    await page.locator('#today-note-text').fill('My label');await expect.poll(()=>writes).toBe(1);const body=await api('GET','/day-notes/2026-11-02/content');await api('PUT','/day-notes/2026-11-02/content',{body:'Peer body'},{'If-Match':body.headers().etag});await page.locator('#today-journal-body').fill('My newer body draft');release();
    await expect(daily.getByRole('alert')).toBeVisible();await expect(page.locator('#today-journal-body')).toHaveValue('My newer body draft');await page.locator('[data-tab=notes]').click();await expect(page.locator('#today-panel')).toBeVisible();const value=await(await api('GET','/day-entries/2026-11-02')).json();expect(value.body).toBe('Peer body');expect(value.text).toBe('My label');expect(writes).toBe(1);
  }finally{release();await page.unrouteAll({behavior:'wait'});}
});
