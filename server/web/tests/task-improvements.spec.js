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

test('Markdown assignments, source note links and removals use the same owned tasks',async({page,request})=>{
  const api=await setup(page,request),client=await(await api('POST','/clients',{name:'Client With Spaces',color:'#285ca8'})).json(),project=await(await api('POST','/projects',{name:'Project With Spaces',clientId:client.id,icon:null})).json();
  const text='- [ ] Direct :smiley: @Client_With_Spaces\n- [x] Project @Project_With_Spaces\n';
  const entry=await(await api('PUT','/notes/files?path=Assignments.md',Buffer.from(text),{'If-None-Match':'*','Content-Type':'application/octet-stream'})).json();
  await page.locator('#refresh').click();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=tasks]').click();
  const values=await allTasks(api);expect(values.find(task=>task.summary.startsWith('Direct'))).toMatchObject({clientId:client.id,projectId:null});expect(values.find(task=>task.summary.startsWith('Project'))).toMatchObject({projectId:project.id});
  await expect(taskButton(page,'Direct 😃 @Client_With_Spaces')).toContainText(client.name);await taskButton(page,'Direct 😃 @Client_With_Spaces').click();
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
  task=(await allTasks(api))[0];expect(task.due).toMatchObject({kind:'DATE_TIME',date:'2026-11-20',time:'14:35',timeZone:'Europe/Oslo'});const deadline=task.due;
  await taskButton(page,'Plan 😃').click();await page.locator('#task-details').getByRole('button',{name:'Edit task',exact:true}).click();await editor.getByLabel('Summary',{exact:true}).fill('Revised :smiley:');await editor.getByRole('button',{name:'Save',exact:true}).click();await expect(editor).toBeHidden();task=(await allTasks(api))[0];expect(task.due).toEqual(deadline);expect(task.clientId).toBe(client.id);
  await taskButton(page,'Revised 😃').click();await page.locator('#task-details').getByRole('button',{name:'Delete task',exact:true}).click();await expect(editor).toBeVisible();await editor.getByRole('button',{name:'Delete',exact:true}).click();await expect(editor).toBeHidden();expect(await allTasks(api)).toHaveLength(0);
});

for(const locale of ['en-GB','nb-NO'])test('task controls fit mobile, desktop and all themes in '+locale,async({page,request})=>{
  const api=await setup(page,request,locale),nb=locale==='nb-NO';await api('POST','/tasks',{summary:'Task controls :smiley:',projectId:null,parentTaskId:null,url:null,priority:'HIGH',due:{kind:'DATE_TIME',date:'2026-11-22',time:'09:30',timeZone:'Europe/Oslo'}});await page.locator('#refresh').click();await page.locator('[data-tab=tasks]').click();await expect(page.locator('#tasks-hide-completed')).toBeVisible();await page.locator('#tasks-list button').filter({hasText:'Task controls 😃'}).click();await page.locator('#task-details').getByRole('button',{name:nb?'Rediger oppgave':'Edit task',exact:true}).click();
  for(const width of [1440,320]){await page.setViewportSize({width,height:1000});for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula','newspaper']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=['timekeeper','solarized-light','newspaper'].includes(theme)?'light':'dark';},theme);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#editor').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
  }}await captureScreenshot(page,{path:'test-results/task-editor-'+locale+'-320.png'});await page.locator('#editor').getByRole('button',{name:nb?'Avbryt':'Cancel',exact:true}).click();
});
