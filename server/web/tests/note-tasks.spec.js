import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {browserRequest} from './browser-request.js';
import {noteEntry} from './note-entry.js';
import {notesSource,saveMarkdown,clickNoteAction} from './editor-interactions.js';
import {searchNotes} from './notes-search.js';
import {captureScreenshot} from './screenshots.js';

test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function setup(page,request){
  const username='note-tasks-'+randomUUID().slice(0,8),password='Private checklist task browser password';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...(method==='POST'?{'Idempotency-Key':randomUUID()}:{}),...headers}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;};
  await api('GET','/notes');return api;
}
async function create(api,path,text){return(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(text),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();}
async function open(page,path){
  await page.locator('[data-tab=notes]').click();await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).click();await noteEntry(page.locator('#notes-navigation'),path).click();await expect(page.locator('#notes-formatted')).toBeVisible();
}
async function tasks(api){return(await(await api('GET','/tasks?limit=500')).json()).items;}
async function complete(api,id,checked){const previous=await api('GET','/tasks/'+id);return api('PUT','/tasks/'+id+'/planning',{status:checked?'COMPLETED':'NOT_STARTED',scheduledDate:null,completedAt:checked?new Date().toISOString():null},{'If-Match':previous.headers().etag});}
const checkbox=(page,title)=>page.locator('#notes-panel').getByRole('checkbox',{name:'Task: '+title,exact:true});
async function indexed(page,query){
  await expect.poll(async()=>{const reply=await browserRequest(page).get('/api/v1/notes/search?'+new URLSearchParams({q:query}));return reply.status()===200?(await reply.json()).items.length:0;},{timeout:30000}).toBe(1);
  return searchNotes(page,query);
}

test('saving and checking note items creates ordinary tasks with separate stable identities',async({page,request})=>{
  const api=await setup(page,request),entry=await create(api,'Checklist.md','Plan\n'),client=await(await api('POST','/clients',{name:'Checklist client',color:'#285ca8'})).json(),link='/notes/client?'+new URLSearchParams({sourceType:entry.sourceType,sourceKey:entry.sourceKey}),before=await api('GET',link);await api('PUT',link,{clientId:client.id},{'If-Match':before.headers().etag});await open(page,'Checklist.md');await expect(page.locator('#notes-panel .note-client-label')).toHaveText(client.name);
  await(await notesSource(page)).fill('- [ ] Follow up\n- [x] Already finished\n\n```md\n- [ ] Example only\n```\n');await saveMarkdown(page.locator('#notes-panel'));
  let values=await tasks(api);expect(values).toHaveLength(2);const first=values.find(task=>task.summary==='Follow up');expect(first.status).toBe('NOT_STARTED');expect(values.find(task=>task.summary==='Already finished').status).toBe('COMPLETED');
  await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();await checkbox(page,'Follow up').check();await saveMarkdown(page.locator('#notes-panel'));values=await tasks(api);expect(values).toHaveLength(2);expect(values.find(task=>task.id===first.id).status).toBe('COMPLETED');await expect(page.locator('#notes-panel .note-client-label')).toHaveText(client.name);expect((await(await api('GET',link)).json()).clientId).toBe(client.id);
  await page.locator('[data-tab=tasks]').click();const row=page.locator('#tasks-list').getByRole('button',{name:'Open task Follow up',exact:true});await expect(row).toBeVisible();await expect(row).toContainText('Completed');await row.click();await expect(page.locator('#task-details')).toContainText('Dagsverk');
});

test('normal task planning preserves exact source bytes and reload shows its linked checkbox',async({page,request})=>{
  const api=await setup(page,request),original='\uFEFF---\r\ncustom: exact\r\n---\r\n- [ ] Keep **formatting** 😀\r\n';const entry=await create(api,'Exact.md',original);await open(page,'Exact.md');const task=(await tasks(api))[0];
  await complete(api,task.id,true);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(original.replace('[ ]','[x]'));await expect(page.locator('#notes-panel > .alert')).toBeVisible();await clickNoteAction(page,page.locator('#notes-panel'),'Reload from server');await expect(checkbox(page,'Keep formatting 😀')).toBeChecked();
  await complete(api,task.id,false);await expect(page.locator('#notes-panel > .alert')).toBeVisible();await clickNoteAction(page,page.locator('#notes-panel'),'Reload from server');await expect(checkbox(page,'Keep formatting 😀')).not.toBeChecked();expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(original);expect((await tasks(api)).map(value=>value.id)).toEqual([task.id]);
});

test('a task completed in another client retains an unsaved note draft for explicit review',async({page,request})=>{
  const api=await setup(page,request),original='- [ ] Shared task\n';const entry=await create(api,'Draft.md',original);await open(page,'Draft.md');const task=(await tasks(api))[0],source=await notesSource(page),draft=original+'\nPrivate unsaved paragraph\n';
  const endpoint='**/api/v1/notes/entries/'+entry.id+'/content';await page.route(endpoint,route=>route.request().method()==='PUT'?route.abort('internetdisconnected'):route.continue());
  await source.fill(draft);await complete(api,task.id,true);await expect(source).toHaveValue(draft);await page.unroute(endpoint);await saveMarkdown(page.locator('#notes-panel'),{wait:false});await expect(page.locator('#notes-panel .alert').first()).toBeVisible();await expect(source).toHaveValue(draft);
  expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(original.replace('[ ]','[x]'));expect((await tasks(api)).find(value=>value.id===task.id).status).toBe('COMPLETED');
});

test('an uncertain note save is reconciled without creating duplicate ordinary tasks',async({page,request})=>{
  const api=await setup(page,request);const entry=await create(api,'Uncertain.md','Before\n');await open(page,'Uncertain.md');const endpoint='**/api/v1/notes/entries/'+entry.id+'/content';let dropped=false,writes=0;
  await page.route(endpoint,async route=>{if(route.request().method()==='PUT')writes++;if(route.request().method()==='PUT'&&!dropped){dropped=true;await route.fetch();return route.abort('internetdisconnected');}return route.continue();});
  await(await notesSource(page)).fill('- [ ] Created once\n');await saveMarkdown(page.locator('#notes-panel'),{wait:false});await expect.poll(async()=>await tasks(api)).toHaveLength(1);const task=(await tasks(api))[0];await page.unroute(endpoint);await page.locator('.notes-footer').getByRole('button',{name:'Check saved result',exact:true}).click();await expect(page.locator('.notes-footer > [role=status]')).toHaveText('Saved');expect(writes).toBe(1);expect((await tasks(api)).map(value=>value.id)).toEqual([task.id]);
  await page.reload();await open(page,'Uncertain.md');await expect(checkbox(page,'Created once')).not.toBeChecked();expect((await tasks(api)).map(value=>value.id)).toEqual([task.id]);
});

test('Today and weekly Markdown writers create the same ordinary tasks and reopen their checkboxes',async({page,request})=>{
  const api=await setup(page,request),day='2026-11-09',daily=await api('GET','/day-notes/'+day+'/content'),week='2026-W46',weekly=await api('GET','/week-notes/'+week);
  await api('PUT','/day-notes/'+day+'/content',{body:'- [ ] Daily follow up\n'},{'If-Match':daily.headers().etag});await api('PUT','/week-notes/'+week,{body:'- [x] Weekly follow up\n'},{'If-Match':weekly.headers().etag});expect(await tasks(api)).toHaveLength(2);
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(day);const dailyCheckbox=page.locator('#today-journal-formatted').getByRole('checkbox',{name:'Task: Daily follow up',exact:true});await expect(dailyCheckbox).not.toBeChecked();await dailyCheckbox.check();await saveMarkdown(page.locator('#today-note-editor'));await expect.poll(async()=>(await tasks(api)).find(value=>value.summary==='Daily follow up').status).toBe('COMPLETED');
  const task=(await tasks(api)).find(value=>value.summary==='Daily follow up');await complete(api,task.id,false);await page.locator('#today-date').fill('2026-11-10');await page.locator('#today-date').fill(day);await expect(dailyCheckbox).not.toBeChecked();
});

test('search popups and maximized event notes synchronize their ordinary checklist tasks',async({page,request})=>{
  test.setTimeout(120000);const api=await setup(page,request);
  await create(api,'Search.md','- [ ] Search popup follow up\n');
  let panel=await indexed(page,'note:"Search popup follow up"');await panel.locator('[data-search-note]').click();
  const note=page.locator('#note-popup-editor');await note.getByRole('checkbox',{name:'Task: Search popup follow up',exact:true}).check();await saveMarkdown(note);
  const ordinary=(await tasks(api)).find(value=>value.summary==='Search popup follow up');expect(ordinary).toMatchObject({status:'COMPLETED'});
  await note.getByRole('button',{name:'Open in workspace',exact:true}).click();await expect(note).toBeHidden();await expect(panel).toBeHidden();await expect(checkbox(page,'Search popup follow up')).toBeChecked();
  await page.locator('[data-tab=today]').click();

  const title='Event checklist result',body=['BEGIN:VCALENDAR','VERSION:2.0','PRODID:-//Dagsverk//Checklist search fixture//EN','BEGIN:VEVENT','UID:checklist-search-event','DTSTAMP:20260101T000000Z','DTSTART:20260504T100000Z','DTEND:20260504T110000Z','SUMMARY:'+title,'DESCRIPTION:Telescope checklist bridge','END:VEVENT','END:VCALENDAR',''].join('\r\n');
  const feed=await request.post(process.env.TIMEKEEPER_WEB_CALENDAR_FIXTURE+'/feeds',{data:{body}});expect(feed.status()).toBe(201);
  const client=await(await api('POST','/clients',{name:'Checklist calendar client',color:'#305070'})).json();
  const subscription=await(await api('POST','/calendar-subscriptions',{name:'Checklist calendar',clientId:client.id,url:(await feed.json()).url,visible:true,includeEvents:true,includeTodos:false})).json();
  let event;await expect.poll(async()=>{event=(await(await api('GET','/calendar-events?fromDate=2026-05-04&toDate=2026-05-05')).json()).items.find(value=>value.subscriptionId===subscription.id);return event?.title;},{timeout:30000}).toBe(title);
  const path='/event-notes/'+subscription.id+'/'+event.key,original=await api('GET',path);
  await api('PUT',path,{fromDate:'2026-05-04',toDate:'2026-05-05',body:'- [ ] Event workspace follow up\n'},{'If-Match':original.headers().etag});
  panel=await indexed(page,'event:"Event workspace follow up"');await panel.locator('[data-search-note]').click();
  const popup=page.locator('#calendar-search-popup-editor');await popup.getByRole('checkbox',{name:'Task: Event workspace follow up',exact:true}).check();
  // Maximize must flush the checkbox edit, task update and resulting index change together.
  await popup.getByRole('button',{name:'Open in workspace',exact:true}).click();await expect(popup).toBeHidden();await expect(panel).toBeHidden();
  const workspace=page.locator('#calendar-search-workspace-editor');await expect(workspace.getByRole('checkbox',{name:'Task: Event workspace follow up',exact:true})).toBeChecked();
  const linked=(await tasks(api)).find(value=>value.summary==='Event workspace follow up');expect(linked).toMatchObject({status:'COMPLETED'});expect(await tasks(api)).toHaveLength(2);
  await captureScreenshot(page,{path:'test-results/note-task-event-workspace.png'});
  await complete(api,linked.id,false);expect((await(await api('GET',path)).json()).body).toBe('- [ ] Event workspace follow up\n');
  await page.locator('[data-tab=today]').click();panel=await indexed(page,'event:"Event workspace follow up"');await panel.locator('[data-search-note]').click();
  await expect(popup.getByRole('checkbox',{name:'Task: Event workspace follow up',exact:true})).not.toBeChecked();await popup.getByRole('button',{name:'Close',exact:true}).click();await panel.getByRole('button',{name:'Close',exact:true}).click();
  await page.locator('[data-tab=tasks]').click();await expect(page.locator('#tasks-list').getByRole('button',{name:'Open task Event workspace follow up',exact:true})).toBeVisible();
  expect((await tasks(api)).map(value=>value.id).sort()).toEqual([ordinary.id,linked.id].sort());await captureScreenshot(page,{path:'test-results/note-task-search-tasks.png'});
});
