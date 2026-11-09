import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {browserRequest} from './browser-request.js';
import {noteEntry} from './note-entry.js';
import {appendEditorText} from './editor-interactions.js';

test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function setup(page,request){
  const username='sync-'+randomUUID().slice(0,8),password='Private synchronization test password';
  const created=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});expect(created.status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,extra={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...extra}});expect(reply.ok(),method+' '+path+': '+reply.status()).toBe(true);return reply;};
  await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});await api('PUT','/notes/files?path=Notes%2FDraft.md',Buffer.from('# Draft\n\nBefore.\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  return api;
}
async function openNote(page){
  await page.locator('[data-tab=notes]').click();const navigation=page.locator('#notes-navigation');await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await expect(navigation.locator('[role=tree]')).toHaveAttribute('aria-busy','false');await noteEntry(navigation,'Notes').click();await noteEntry(navigation,'Notes/Draft.md').click();await expect(page.locator('#notes-formatted .ProseMirror')).toBeVisible();await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');
}

test('background synchronization preserves typing and defers inactive work views until they are opened',async({page,request})=>{
  const api=await setup(page,request);await api('POST','/clients',{name:'Seed client',color:'#305070'});await page.locator('[data-tab=clients]').click();await expect(page.locator('#clients')).toContainText('Seed client');await page.locator('[data-tab=track]').click();await expect(page.locator('#track-calendar')).not.toBeEmpty();await openNote(page);
  await page.evaluate(()=>{
    const ids=['clients','projects','labels','activities','days','track-calendar','week-sessions'];globalThis.syncMutations={};globalThis.syncObservers=ids.map(id=>{globalThis.syncMutations[id]=0;const observer=new MutationObserver(records=>{globalThis.syncMutations[id]+=records.length;});observer.observe(document.getElementById(id),{childList:true,subtree:true});return observer;});
  });
  const paragraph=page.locator('#notes-formatted .ProseMirror p');await appendEditorText(paragraph,' ');const text='Continuous typing remains in this private note.';const typing=page.keyboard.type(text,{delay:30});
  for(let index=0;index<3;index++){
    const name='Synchronized client '+index,published=page.waitForResponse(async reply=>{if(reply.request().method()!=='GET'||new URL(reply.url()).pathname!=='/api/v1/clients')return false;const data=await reply.json();return data.items?.some(row=>row.name===name);});
    await api('POST','/clients',{name,color:'#507030'});await published;await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');
  }
  await typing;await expect(paragraph).toHaveText('Before. '+text);await expect(page.locator('#notes-formatted .ProseMirror')).toBeFocused();await expect(page.locator('.notes-footer > [role=status]')).toHaveText('Saved');
  const changes=await page.evaluate(()=>{globalThis.syncObservers.forEach(observer=>observer.disconnect());return globalThis.syncMutations;});expect(changes).toEqual(Object.fromEntries(Object.keys(changes).map(key=>[key,0])));
  await page.locator('[data-tab=clients]').click();for(let index=0;index<3;index++)await expect(page.locator('#clients')).toContainText('Synchronized client '+index);
  await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-formatted .ProseMirror p')).toHaveText('Before. '+text);
});

test('unchanged account refreshes retain the Notes catalog while note changes and explicit refresh still reload it',async({page,request})=>{
  const api=await setup(page,request);await openNote(page);let catalogReads=0;await page.route('**/api/v1/notes',async route=>{catalogReads++;await route.continue();});
  for(let index=0;index<3;index++){await page.locator('#refresh').click();await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');}
  expect(catalogReads).toBe(0);
  await api('PUT','/notes/files?path=Notes%2FIncoming.md',Buffer.from('# Incoming\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});await expect(noteEntry(page.locator('#notes-navigation'),'Notes/Incoming.md')).toBeVisible();expect(catalogReads).toBeGreaterThan(0);
  await expect(page.locator('#notes-navigation [role=tree]')).toHaveAttribute('aria-busy','false');catalogReads=0;await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).click();await expect(page.locator('#notes-navigation [role=tree]')).toHaveAttribute('aria-busy','false');expect(catalogReads).toBeGreaterThan(0);await expect(page.locator('#notes-formatted .ProseMirror p')).toHaveText('Before.');
});

test('a local note save and its change notification share one consistent account refresh',async({page,request})=>{
  const api=await setup(page,request),entry=await(await api('GET','/notes/resolve?path=Notes%2FDraft.md')).json();
  let changesHeld=false,releaseChanges,profileHeld=false,releaseProfile,profileReads=0;const changesPending=new Promise(resolve=>{releaseChanges=resolve;}),profilePending=new Promise(resolve=>{releaseProfile=resolve;});
  await page.route('**/api/v1/changes?*',async route=>{const reply=await route.fetch(),data=await reply.json();if(data.items.length){changesHeld=true;await changesPending;}await route.fulfill({response:reply});});
  try{
    await page.reload();await page.locator(`#today-recent-notes [data-recent-note="${entry.id}"]`).click();const popup=page.locator('#note-popup-editor');await expect(popup.locator('.ProseMirror')).toBeVisible();await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');
    await page.route('**/api/v1/me',async route=>{profileReads++;if(!profileHeld){profileHeld=true;await profilePending;}await route.continue();});
    await popup.getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#note-popup-body').fill('# Draft\n\nOne saved edit.\n');await popup.getByRole('button',{name:'Formatted',exact:true}).press('ControlOrMeta+s');
    await expect.poll(()=>profileHeld).toBe(true);await expect.poll(()=>changesHeld).toBe(true);const delivered=page.waitForResponse(reply=>new URL(reply.url()).pathname==='/api/v1/changes');releaseChanges();await delivered;releaseProfile();
    await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');expect(profileReads).toBe(2);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('# Draft\n\nOne saved edit.\n');
  }finally{releaseChanges();releaseProfile();await page.unrouteAll({behavior:'wait'});}
});

test('a change newer than an in-flight snapshot still triggers the required second refresh',async({page,request})=>{
  const api=await setup(page,request);await page.locator('[data-tab=clients]').click();await page.locator('#refresh').click();await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');
  let reads=0,held=false,revision,release;const pending=new Promise(resolve=>{release=resolve;});
  await page.route('**/api/v1/projects?*',async route=>{const number=++reads,reply=await route.fetch();if(number===2){revision=(await reply.json()).revision;held=true;await pending;}await route.fulfill({response:reply});});
  try{
    await page.locator('#refresh').click();await expect.poll(()=>held).toBe(true);const delivered=page.waitForResponse(async reply=>new URL(reply.url()).pathname==='/api/v1/changes'&&(await reply.json()).nextSequence>revision);
    await api('POST','/clients',{name:'Arrived after the captured snapshot',color:'#305070'});await delivered;release();
    await expect(page.locator('#clients')).toContainText('Arrived after the captured snapshot');await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');expect(reads).toBe(4);
  }finally{release();await page.unrouteAll({behavior:'wait'});}
});
