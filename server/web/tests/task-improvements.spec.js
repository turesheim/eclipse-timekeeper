import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {noteEntry} from './note-entry.js';
import {notesSource,saveMarkdown} from './editor-interactions.js';
import {captureScreenshot} from './screenshots.js';

async function setup(page,request,locale='en-GB'){
  const username='task-improvements-'+randomUUID().slice(0,8),password='Synthetic task improvement password';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const response=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...(method==='POST'?{'Idempotency-Key':randomUUID()}:{}),...headers}});expect(response.ok(),method+' '+path+': '+response.status()).toBe(true);return response;};
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'Europe/Oslo',locale},{'If-Match':profile.headers().etag});await page.reload();await expect(page.locator('#workspace')).toBeVisible();return api;
}
const allTasks=async api=>(await(await api('GET','/tasks?limit=500')).json()).items;
const taskButton=(page,title)=>page.locator('#tasks-list').getByRole('button',{name:'Open task '+title,exact:true});

test('parent task choices include only open tasks in the selected client and project',async({page,request})=>{
  const api=await setup(page,request),clients=[];await page.goto('about:blank');
  for(const name of ['First client','Second client'])clients.push(await(await api('POST','/clients',{name,color:'#285ca8'})).json());
  const projects=[];for(const [name,client]of [['First project',clients[0]],['Other project',clients[0]],['Second client project',clients[1]]])projects.push(await(await api('POST','/projects',{name,clientId:client.id,icon:null})).json());
  const tasks=[];for(const [summary,client,project,status]of [['Unassigned open',null,null,'NOT_STARTED'],['First client open',clients[0],null,'NOT_STARTED'],['First client completed',clients[0],null,'COMPLETED'],['Second client open',clients[1],null,'NOT_STARTED'],['First project open',clients[0],projects[0],'NOT_STARTED'],['First project waiting',clients[0],projects[0],'WAITING'],['First project completed',clients[0],projects[0],'COMPLETED'],['Other project open',clients[0],projects[1],'NOT_STARTED'],['Second client project open',clients[1],projects[2],'NOT_STARTED']])tasks.push(await(await api('POST','/tasks',{summary,clientId:client?.id||null,projectId:project?.id||null,parentTaskId:null,url:null,status})).json());
  await page.goto('/web/');await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=tasks]').click();await page.locator('#tasks-panel').getByRole('button',{name:'New task',exact:true}).click();
  const editor=page.locator('#editor'),parent=editor.getByLabel('Parent task',{exact:true}),client=editor.getByLabel('Client',{exact:true}),project=editor.getByLabel('Project',{exact:true});
  await expect(parent.locator('option')).toHaveText(['No parent','Unassigned open']);
  await client.selectOption(clients[0].id);await expect(parent.locator('option')).toHaveText(['No parent','First client open']);await parent.selectOption(tasks.find(task=>task.summary==='First client open').id);
  await client.selectOption(clients[1].id);await expect(parent).toHaveValue('');await expect(parent.locator('option')).toHaveText(['No parent','Second client open']);
  await client.selectOption(clients[0].id);await project.selectOption(projects[0].id);expect((await parent.locator('option').allTextContents()).sort()).toEqual(['First project open','First project waiting','No parent']);
  await project.selectOption(projects[1].id);await expect(parent.locator('option')).toHaveText(['No parent','Other project open']);await editor.getByRole('button',{name:'Cancel',exact:true}).click();
});

test('editing a child retains its completed parent without offering it as a new choice',async({page,request})=>{
  const api=await setup(page,request);await page.goto('about:blank');const parentTask=await(await api('POST','/tasks',{summary:'Completed parent',projectId:null,parentTaskId:null,url:null})).json(),child=await(await api('POST','/tasks',{summary:'Existing child',projectId:null,parentTaskId:parentTask.id,url:null})).json();
  const current=await api('GET','/tasks/'+parentTask.id);await api('PUT','/tasks/'+parentTask.id+'/planning',{status:'COMPLETED',scheduledDate:null,completedAt:'2026-11-15T12:00:00Z'},{'If-Match':current.headers().etag});
  await page.goto('/web/');await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=tasks]').click();await taskButton(page,'Existing child').click();await page.locator('#task-details').getByRole('button',{name:'Edit task',exact:true}).click();
  const editor=page.locator('#editor'),parent=editor.getByLabel('Parent task',{exact:true});await expect(parent).toHaveValue(parentTask.id);await expect(parent.locator('option:checked')).toHaveJSProperty('disabled',true);
  await editor.getByLabel('Summary',{exact:true}).fill('Retained child');await editor.getByRole('button',{name:'Save',exact:true}).click();await expect(editor).toBeHidden();expect((await(await api('GET','/tasks/'+child.id)).json()).parentTaskId).toBe(parentTask.id);
  await taskButton(page,'Retained child').click();await page.locator('#task-details').getByRole('button',{name:'Edit task',exact:true}).click();await parent.selectOption('');await editor.getByRole('button',{name:'Save',exact:true}).click();await expect(editor).toBeHidden();expect((await(await api('GET','/tasks/'+child.id)).json()).parentTaskId).toBeNull();
});

test('Markdown assignments, source note links and removals use the same owned tasks',async({page,request})=>{
  const api=await setup(page,request),client=await(await api('POST','/clients',{name:'Client With Spaces',color:'#285ca8'})).json(),project=await(await api('POST','/projects',{name:'Project With Spaces',clientId:client.id,icon:null})).json();
  const text='- [ ] Direct :smiley: @Client_With_Spaces\n- [x] Project @Project_With_Spaces\n';
  const entry=await(await api('PUT','/notes/files?path=Assignments.md',Buffer.from(text),{'If-None-Match':'*','Content-Type':'application/octet-stream'})).json();
  await page.locator('#refresh').click();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=tasks]').click();
  const values=await allTasks(api);expect(values.find(task=>task.summary.startsWith('Direct'))).toMatchObject({clientId:client.id,projectId:null});expect(values.find(task=>task.summary.startsWith('Project'))).toMatchObject({projectId:project.id});
  await expect(taskButton(page,'Direct 😃 @Client_With_Spaces')).toContainText(client.name);
  const overview=page.locator('#tasks-list > li').filter({has:page.getByRole('button',{name:'Open task Direct 😃 @Client_With_Spaces',exact:true})}),directSource=overview.getByRole('link',{name:'Open source note: Assignments',exact:true});await expect(directSource.locator('.bi-file-earmark-text')).toBeVisible();await directSource.click();await expect(page.locator('#note-popup-editor')).toBeVisible();await page.locator('#note-popup-editor').getByRole('button',{name:'Close',exact:true}).click();
  await taskButton(page,'Direct 😃 @Client_With_Spaces').click();
  const source=page.locator('#task-details').getByRole('link',{name:'Open source note: Assignments',exact:true});await expect(source.locator('.bi-file-earmark-text')).toBeVisible();await source.click();
  const popup=page.locator('#note-popup-editor');await expect(popup).toBeVisible();await expect(popup.getByRole('checkbox',{name:'Task: Direct 😃 @Client_With_Spaces',exact:true})).toBeVisible();await popup.getByRole('button',{name:'Close',exact:true}).click();
  await page.locator('#tasks-hide-completed').check();await expect(taskButton(page,'Project @Project_With_Spaces')).toHaveCount(0);await page.locator('#tasks-hide-completed').uncheck();await expect(taskButton(page,'Project @Project_With_Spaces')).toBeVisible();
  await page.locator('[data-tab=notes]').click();await noteEntry(page.locator('#notes-navigation'),'Assignments.md').click();await(await notesSource(page)).fill('- [ ] Direct :smiley: @Client_With_Spaces\n');await saveMarkdown(page.locator('#notes-panel'));
  const remaining=await allTasks(api);expect(remaining).toHaveLength(1);expect(remaining[0].id).toBe(values.find(task=>task.summary.startsWith('Direct')).id);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('- [ ] Direct :smiley: @Client_With_Spaces\n');
});

test('task editor supports client-only assignment, priority and exact date or timed deadlines',async({page,request})=>{
  const api=await setup(page,request),client=await(await api('POST','/clients',{name:'Independent client',color:'#123456'})).json();
  await page.locator('#refresh').click();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=tasks]').click();await page.locator('#tasks-panel').getByRole('button',{name:'New task',exact:true}).click();
  const editor=page.locator('#editor');await editor.getByLabel('Summary',{exact:true}).fill('Plan :smiley:');await editor.getByLabel('Client',{exact:true}).selectOption(client.id);await editor.getByLabel('Priority',{exact:true}).selectOption('HIGH');await editor.getByLabel('Deadline type',{exact:true}).selectOption('DATE');await editor.getByLabel('Due',{exact:true}).fill('2026-11-20');await editor.getByRole('button',{name:'Save',exact:true}).click();await expect(editor).toBeHidden();
  let task=(await allTasks(api))[0];expect(task).toMatchObject({clientId:client.id,projectId:null,priority:'HIGH',due:{kind:'DATE',date:'2026-11-20'}});await taskButton(page,'Plan 😃').click();await expect(page.locator('#task-details')).toContainText('High');await page.locator('#task-details').getByRole('button',{name:'Edit task',exact:true}).click();
  await editor.getByLabel('Deadline type',{exact:true}).selectOption('DATE_TIME');await editor.getByLabel('Deadline time',{exact:true}).fill('14:35');await editor.getByLabel('Deadline time zone',{exact:true}).fill('Europe/Oslo');await editor.getByRole('button',{name:'Save',exact:true}).click();await expect(editor).toBeHidden();
  task=(await allTasks(api))[0];expect(task.due).toMatchObject({kind:'DATE_TIME',date:'2026-11-20',time:'14:35',timeZone:'Europe/Oslo'});
  const deadline={...task.due,time:'14:35:42.123456789'},start={kind:'DATE_TIME',date:'2026-10-25',time:'02:30:00',timeZone:'Europe/Oslo'};
  let current=await api('GET','/tasks/'+task.id);await api('PUT','/tasks/'+task.id+'/timing',{start,due:deadline},{'If-Match':current.headers().etag});current=await api('GET','/tasks/'+task.id);await api('PUT','/tasks/'+task.id+'/importance',{priority:'HIGH',flagged:true,urgent:true},{'If-Match':current.headers().etag});await page.locator('#refresh').click();await expect(page.locator('#status')).toHaveText('Up to date');
  await taskButton(page,'Plan 😃').click();await page.locator('#task-details').getByRole('button',{name:'Edit task',exact:true}).click();await editor.getByLabel('Summary',{exact:true}).fill('Revised :smiley:');await editor.getByRole('button',{name:'Save',exact:true}).click();await expect(editor).toBeHidden();task=(await allTasks(api))[0];expect(task.due).toEqual(deadline);expect(task.clientId).toBe(client.id);expect(task.flagged&&task.urgent).toBe(true);expect(task.start).toMatchObject({date:start.date,timeZone:start.timeZone});
  await taskButton(page,'Revised 😃').click();await page.locator('#task-details').getByRole('button',{name:'Delete task',exact:true}).click();await expect(editor).toBeVisible();await editor.getByRole('button',{name:'Delete',exact:true}).click();await expect(editor).toBeHidden();expect(await allTasks(api)).toHaveLength(0);
});

for(const locale of ['en-GB','nb-NO'])test('task controls fit mobile, desktop and all themes in '+locale,async({page,request})=>{
  const api=await setup(page,request,locale),nb=locale==='nb-NO';await api('POST','/tasks',{summary:'Task controls :smiley:',projectId:null,parentTaskId:null,url:null,priority:'HIGH',due:{kind:'DATE_TIME',date:'2026-11-22',time:'09:30',timeZone:'Europe/Oslo'}});await page.locator('#refresh').click();await page.locator('[data-tab=tasks]').click();await expect(page.locator('#tasks-hide-completed')).toBeVisible();await page.locator('#tasks-list button').filter({hasText:'Task controls 😃'}).click();await page.locator('#task-details').getByRole('button',{name:nb?'Rediger oppgave':'Edit task',exact:true}).click();
  for(const width of [1440,320]){await page.setViewportSize({width,height:1000});for(const theme of ['timekeeper','timekeeper-dark']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=(theme==='timekeeper')?'light':'dark';},theme);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#editor').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
  }}await captureScreenshot(page,{path:'test-results/task-editor-'+locale+'-320.png'});await page.locator('#editor').getByRole('button',{name:nb?'Avbryt':'Cancel',exact:true}).click();
});
