import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {noteEntry} from './note-entry.js';
import {notesSource,saveMarkdown,appendEditorText} from './editor-interactions.js';
import {captureScreenshot} from './screenshots.js';

async function setup(page,request,locale='en-GB'){
  const username='note-tasks-'+randomUUID().slice(0,8),password='Synthetic task improvement password';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const response=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...(method==='POST'?{'Idempotency-Key':randomUUID()}:{}),...headers}});expect(response.ok(),method+' '+path+': '+response.status()).toBe(true);return response;};
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'Europe/Oslo',locale},{'If-Match':profile.headers().etag});await page.reload();await expect(page.locator('#workspace')).toBeVisible();return api;
}

const put=async(api,path,text)=>(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(text),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
async function openNote(page,path){await page.locator('#refresh').click();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=notes]').click();await noteEntry(page.locator('#notes-navigation'),path).click();const root=page.locator('#notes-panel');await root.getByRole('button',{name:'Formatted',exact:true}).click();await expect(root.locator('.ProseMirror')).toBeVisible();return root;}
async function caret(paragraph){await paragraph.evaluate(node=>{node.closest('[contenteditable=true]').focus();const range=document.createRange();range.selectNodeContents(node);range.collapse(false);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);document.dispatchEvent(new Event('selectionchange'));});}
async function edit(root,paragraph){await caret(paragraph);const action=root.getByRole('button',{name:'Edit task details',exact:true});await expect(action).toBeVisible();await action.click();const dialog=root.locator('.note-task-dialog');await expect(dialog).toBeVisible();await expect(dialog.getByLabel('Priority',{exact:true})).toBeEnabled();return dialog;}
const tasks=async api=>(await(await api('GET','/tasks?limit=500')).json()).items;

test('rich task controls edit the selected duplicate with exact source and deadline precision',async({page,request})=>{
  const api=await setup(page,request),client=await(await api('POST','/clients',{name:'Chosen client',color:'#176653'})).json(),project=await(await api('POST','/projects',{name:'Chosen project',clientId:client.id,icon:null})).json();
  const text='\uFEFF---\r\ncustom: [preserved, metadata]\r\n---\r\n# Work 😀\r\n- [ ] Same **title**\r\n  - [ ] Same **title**\r\n';const entry=await put(api,'Task properties.md',text),before=await api('GET','/notes/entries/'+entry.id+'/content');
  const root=await openNote(page,'Task properties.md'),paragraph=root.locator('li li p').first(),dialog=await edit(root,paragraph);
  await dialog.getByLabel('Client',{exact:true}).selectOption(client.id);await dialog.getByLabel('Project',{exact:true}).selectOption(project.id);await dialog.getByLabel('Scheduled day',{exact:true}).fill('2026-11-20');await dialog.getByLabel('Deadline type',{exact:true}).selectOption('DATE_TIME');await dialog.getByLabel('Due',{exact:true}).fill('2026-11-21');await dialog.getByLabel('Deadline time',{exact:true}).fill('12:13:14');await expect(dialog.getByLabel('Deadline time zone',{exact:true})).toHaveValue('Europe/Oslo');await dialog.getByLabel('Priority',{exact:true}).selectOption('HIGH');await dialog.getByLabel('Notes',{exact:true}).fill('Task notes\nSecond line');await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();
  const values=await tasks(api),chosen=values.find(task=>task.priority==='HIGH');expect(values).toHaveLength(2);expect(chosen).toMatchObject({clientId:client.id,projectId:project.id,scheduledDate:'2026-11-20',due:{kind:'DATE_TIME',date:'2026-11-21',time:'12:13:14',timeZone:'Europe/Oslo'},body:{format:'TEXT',content:'Task notes\nSecond line'}});
  expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(text);expect((await api('GET','/notes/entries/'+entry.id+'/content')).headers().etag).toBe(before.headers().etag);
  const current=await api('GET','/tasks/'+chosen.id),due={...chosen.due,time:'12:13:14.123456789'};await api('PUT','/tasks/'+chosen.id+'/timing',{start:null,due},{'If-Match':current.headers().etag});
  const again=await edit(root,root.locator('li li p').first());await again.getByLabel('Notes',{exact:true}).fill('Only notes changed');await again.getByRole('button',{name:'Save',exact:true}).click();await expect(again).toBeHidden();expect((await(await api('GET','/tasks/'+chosen.id)).json()).due).toEqual(due);
  await appendEditorText(root.locator('.ProseMirror > h1'),' prose');await saveMarkdown(root);expect((await(await api('GET','/tasks/'+chosen.id)).json()).projectId).toBe(project.id);
});

test('source mode ignores code examples, opens real tasks and flushes the current draft',async({page,request})=>{
  const api=await setup(page,request);await put(api,'Source tasks.md','```md\n- [ ] Example\n```\n\n- [ ] Actual\n');const root=await openNote(page,'Source tasks.md'),source=await notesSource(page);
  await source.focus();await source.evaluate(input=>{const at=input.value.indexOf('Example')+2;input.setSelectionRange(at,at);input.dispatchEvent(new Event('select'));});await source.press('ArrowRight');await expect(root.locator('.note-task-action')).toBeHidden();
  await source.fill('```md\n- [ ] Example\n```\n\n- [ ] Actual revised\n');await source.evaluate(input=>{const at=input.value.indexOf('Actual')+3;input.setSelectionRange(at,at);input.dispatchEvent(new Event('select'));});await source.press('ArrowRight');await root.getByRole('button',{name:'Edit task details',exact:true}).click();const dialog=root.locator('.note-task-dialog');await expect(dialog.getByLabel('Priority',{exact:true})).toBeEnabled();await expect(dialog.locator('.note-task-title')).toHaveText('Actual revised');await dialog.getByLabel('Priority',{exact:true}).selectOption('LOW');await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();expect((await tasks(api)).map(task=>[task.summary,task.priority])).toEqual([['Actual revised','LOW']]);await expect(source).toBeFocused();
});

test('a stale task retains dialog changes until explicit reload',async({page,request})=>{
  const api=await setup(page,request);await put(api,'Stale task.md','- [ ] Review\n');const root=await openNote(page,'Stale task.md'),dialog=await edit(root,root.locator('li p'));const task=(await tasks(api))[0],current=await api('GET','/tasks/'+task.id);await api('PUT','/tasks/'+task.id+'/importance',{priority:'HIGH',flagged:true,urgent:false},{'If-Match':current.headers().etag});
  await dialog.getByLabel('Notes',{exact:true}).fill('Keep my draft');await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog.getByRole('alert')).toBeVisible();await expect(dialog.getByLabel('Notes',{exact:true})).toHaveValue('Keep my draft');await expect(dialog.getByRole('button',{name:'Save',exact:true})).toBeDisabled();
  page.once('dialog',dialog=>dialog.accept());await dialog.getByRole('button',{name:'Reload task',exact:true}).click();await expect(dialog.getByLabel('Priority',{exact:true})).toHaveValue('HIGH');await dialog.getByLabel('Notes',{exact:true}).fill('Reviewed draft');await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();expect((await(await api('GET','/tasks/'+task.id)).json()).flagged).toBe(true);
});

test('an uncertain task save is read back without repeating the write',async({page,request})=>{
  const api=await setup(page,request);await put(api,'Uncertain task.md','- [ ] Verify\n');const root=await openNote(page,'Uncertain task.md'),dialog=await edit(root,root.locator('li p'));let writes=0;
  await page.route('**/api/v1/tasks/*',async route=>{if(route.request().method()!=='PUT')return route.continue();writes++;await route.fetch();await route.fulfill({status:503,contentType:'application/problem+json',body:JSON.stringify({type:'urn:timekeeper:problem:unavailable',title:'Unavailable',status:503,code:'UNAVAILABLE'})});});
  await dialog.getByLabel('Notes',{exact:true}).fill('Applied once');await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog.getByLabel('Notes',{exact:true})).toHaveValue('Applied once');await expect(dialog.getByRole('button',{name:'Save',exact:true})).toBeDisabled();await dialog.getByRole('button',{name:'Check saved result',exact:true}).click();await expect(dialog).toBeHidden();expect(writes).toBe(1);expect((await tasks(api))[0].body.content).toBe('Applied once');
});

for(const locale of ['en-GB','nb-NO'])test('daily task dialog fits both themes and narrow screens in '+locale,async({page,request})=>{
  const api=await setup(page,request,locale),nb=locale==='nb-NO';await put(api,'journal/2026-11-02.md','- [ ] Daily task\n');await page.locator('#today-date').fill('2026-11-02');const root=page.locator('#today-note-editor');await expect(root.locator('.ProseMirror li p')).toBeVisible();await caret(root.locator('li p'));await root.getByRole('button',{name:nb?'Rediger oppgavedetaljer':'Edit task details',exact:true}).click();const dialog=root.locator('.note-task-dialog');await expect(dialog.locator('select[name=priority]')).toBeEnabled();
  for(const width of [1200,320])for(const theme of ['timekeeper','timekeeper-dark']){await page.setViewportSize({width,height:900});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('.note-task-dialog[open]').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);}
  await captureScreenshot(page,{path:'test-results/note-task-'+locale+'-320.png'});await dialog.getByRole('button',{name:nb?'Avbryt':'Cancel',exact:true}).click();await expect(dialog).toBeHidden();
});
