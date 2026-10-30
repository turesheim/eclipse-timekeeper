import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {openAccountMenu} from './navigation.js';
import {captureScreenshot} from './screenshots.js';

async function login(page,user){
  await page.goto('/web/#account/focus');await page.locator('#auth-mode').selectOption('login');
  await page.locator('#username').fill(user.username);await page.locator('#password').fill(user.password);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  await expect(page.locator('#focus-settings')).toBeVisible();
}
async function selectFocus(page,label){await openAccountMenu(page);await page.locator('#focus-select').selectOption({label});await page.getByRole('button',{name:'Account menu',exact:true}).click();}

test('focus areas filter every client view, totals and separately loaded calendar events without losing work',async({page,request})=>{
  const username='focus-'+randomUUID().slice(0,8),password='Private focus area test password';
  const created=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.org',administrator:false}});expect(created.status()).toBe(201);
  await login(page,{username,password});
  const identity=await(await browserRequest(page).get('/api/v1/auth/session')).json();
  const headers={'X-CSRF-Token':identity.csrf,'X-Timekeeper-Owner':Buffer.from(identity.ownerId).toString('base64url')};
  const api=async(method,path,data,extra={})=>{const result=await browserRequest(page).fetch('/api/v1'+path,{method,data,headers:{...headers,...extra}});expect(result.ok(),`${method} ${path}: ${result.status()}`).toBeTruthy();return result;};
  const clients=[],projects=[],sessions=[];
  for(const [index,name] of ['Included client','Excluded client'].entries()){
    const client=await(await api('POST','/clients',{name,color:index?'#cc2211':'#118866'})).json();clients.push(client);
    const project=await(await api('POST','/projects',{name:name+' project',clientId:client.id,icon:'code'})).json();projects.push(project);
    const task=await(await api('POST','/tasks',{summary:name+' task',projectId:project.id})).json();
    sessions.push(await(await api('POST','/work-sessions',{clientId:client.id,projectId:project.id,summary:name+' session',start:'2025-05-17T08:00:00Z',end:'2025-05-17T09:00:00Z'})).json());
    await api('POST','/activities',{taskId:task.id,projectId:project.id,start:'2025-05-17T08:00:00Z',end:'2025-05-17T09:00:00Z',summary:name+' activity',labelIds:[]});
  }
  await page.route('**/api/v1/calendar-events?**',async route=>{
    const response=await route.fetch(),body=await response.json();await route.fulfill({response,json:{...body,items:clients.map((client,index)=>({key:'focus-event-'+index,subscriptionId:randomUUID(),eventVersion:1,title:client.name+' meeting',description:'Private description',allDay:false,start:'2025-05-17T12:00:00Z',end:'2025-05-17T13:00:00Z',firstDate:'2025-05-17',endDate:'2025-05-18',clientId:client.id,clientName:client.name,calendarName:'Calendar',activityIds:[]}))}});
  });
  await page.reload();await expect(page.locator('#focus-settings')).toBeVisible();
  const form=page.locator('#focus-settings');await form.getByRole('button',{name:'Add focus area',exact:true}).click();
  await form.getByLabel('Name',{exact:true}).fill('Work');await form.getByLabel(clients[0].name,{exact:true}).check();await form.getByRole('button',{name:'Save',exact:true}).click();await expect(form).toContainText('Saved');
  await form.getByRole('button',{name:'Add focus area',exact:true}).click();await form.getByLabel('Name',{exact:true}).last().fill('Empty');await form.getByRole('button',{name:'Save',exact:true}).click();await expect(form).toContainText('Saved');
  for(const width of [1440,390]){await page.setViewportSize({width,height:900});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();}
  const accessibility=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(accessibility.violations.map(value=>value.id)).toEqual([]);
  await page.setViewportSize({width:1440,height:1000});await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();
  await page.locator('#today-date').fill('2025-05-17');await selectFocus(page,'Work');
  await expect(page.locator('#today-sessions')).toContainText(clients[0].name);await expect(page.locator('#today-sessions')).not.toContainText(clients[1].name);
  await expect(page.locator('#today-events')).toContainText(clients[0].name+' meeting');await expect(page.locator('#today-events')).not.toContainText(clients[1].name);
  for(const [tab,root] of [['clients','#clients'],['projects','#projects']]){await page.locator(`[data-tab=${tab}]`).click();await expect(page.locator(root)).toContainText(clients[0].name);await expect(page.locator(root)).not.toContainText(clients[1].name);}
  await page.locator('[data-tab=activities]').click();await page.locator('#week-date').fill('2025-05-17');
  await expect(page.locator('#week-total')).toHaveText('1h 0m');await expect(page.locator('#activities')).toContainText(clients[0].name+' task');await expect(page.locator('#activities')).not.toContainText(clients[1].name);
  await page.locator('[data-tab=week]').click();await expect(page.locator('#week-sessions-section')).toContainText(clients[0].name);await expect(page.locator('#week-sessions-section')).not.toContainText(clients[1].name);
  await page.locator('[data-tab=calendar]').click();await page.locator('#calendar-date').fill('2025-05-17');
  await expect(page.locator(`[data-session-id="${sessions[0].id}"]`).first()).toBeVisible();await expect(page.locator(`[data-session-id="${sessions[1].id}"]`)).toHaveCount(0);
  await expect(page.locator('.calendar-planned')).toContainText([clients[0].name+' meeting']);
  await page.locator('[data-tab=track]').click();await page.locator('#track-month').fill('2025-05');
  await expect(page.locator('#track-panel')).toContainText(clients[0].name);await expect(page.locator('#track-panel')).not.toContainText(clients[1].name);
  await captureScreenshot(page,{path:'test-results/focus-calendar.png',fullPage:true});
  await selectFocus(page,'Empty');await expect(page.locator('#track-panel')).not.toContainText(clients[0].name);
  await page.locator('[data-tab=clients]').click();await expect(page.locator('#clients tbody tr')).toHaveCount(0);
  await selectFocus(page,'No focus');await expect(page.locator('#clients')).toContainText(clients[1].name);
  expect((await(await api('GET','/work-sessions')).json()).items).toHaveLength(2);
  // Focus is a view preference, never permission to start a second timer.
  const running=await(await api('POST','/work-sessions/start',{clientId:clients[1].id,summary:'Outside focus timer'},{'Idempotency-Key':randomUUID()})).json();
  await page.locator('#refresh').click();await expect(page.locator('#session-stop')).toBeVisible();await selectFocus(page,'Work');
  await expect(page.locator('#work-session-title')).toHaveText('Work outside this focus');await expect(page.locator('#session-start')).toBeHidden();
  await page.locator('#session-stop').click();await expect(page.locator('#session-start')).toBeVisible();
  expect((await(await api('GET','/work-sessions/'+running.id)).json()).end).toBeTruthy();
});
