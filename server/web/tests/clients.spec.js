import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
const token=process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request,method,path,data,headers={}) {
  const response=await request.fetch(`/api/v1${path}`,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
  expect(response.ok(),`${method} ${path}: ${response.status()}`).toBeTruthy();return response;
}
async function save(page) {await page.locator('#save').click();await expect(page.locator('#editor')).not.toBeVisible();await expect(page.locator('#status')).toHaveText('Up to date');}
test('clients maintain names and colors, own projects and color titled work sessions after reload',async({page,request})=>{
  const prefix='Client '+randomUUID().slice(0,6);let client,session;const projects=[];
  try {
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(token);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await page.getByRole('button',{name:'Clients',exact:true}).click();await page.getByRole('button',{name:'New client',exact:true}).click();
    await page.getByLabel('Name',{exact:true}).fill(prefix);await page.getByLabel('Color',{exact:true}).fill('#ffee00');await save(page);
    client=(await (await api(request,'GET','/clients?q='+encodeURIComponent(prefix))).json()).items[0];
    for(let i=0;i<2;i++) {
      await page.getByRole('button',{name:'Projects',exact:true}).click();await page.getByRole('button',{name:'New project',exact:true}).click();
      await page.getByLabel('Name',{exact:true}).fill(prefix+' project '+i);await page.getByLabel('Client',{exact:true}).selectOption(client.id);await save(page);
    }
    projects.push(...(await (await api(request,'GET','/projects?clientId='+client.id)).json()).items);expect(projects).toHaveLength(2);
    for(const project of projects) await expect(page.locator('#projects tr').filter({hasText:project.name})).toContainText(prefix);
    await page.getByRole('button',{name:'Record work session',exact:true}).click();await page.getByLabel('Title',{exact:true}).fill(prefix+' planning');await page.getByLabel('Client',{exact:true}).selectOption(client.id);
    await page.getByLabel('Summary',{exact:true}).fill('A separate description');await page.getByLabel('Start (local time)',{exact:true}).fill('2025-10-20T08:00:00Z');await page.getByLabel('End (local time, blank while running)',{exact:true}).fill('2025-10-20T16:00:00Z');await save(page);
    session=(await (await api(request,'GET','/work-sessions?clientId='+client.id)).json()).items[0];expect(session.title).toBe(prefix+' planning');
    await page.getByRole('button',{name:'Calendar',exact:true}).click();await page.getByLabel('Calendar date',{exact:true}).fill('2025-10-20');
    const bar=page.locator(`.calendar-session[data-session-id="${session.id}"]`).first();await expect(bar).toHaveCSS('background-color','rgb(255, 238, 0)');await expect(bar).toHaveAttribute('title',new RegExp(prefix));
    await page.getByRole('button',{name:'Clients',exact:true}).click();let row=page.locator('#clients tr').filter({hasText:prefix});await row.getByRole('button',{name:'Edit',exact:true}).click();
    await page.getByLabel('Name',{exact:true}).fill(prefix+' renamed');await page.getByLabel('Color',{exact:true}).fill('#102030');await save(page);
    await page.getByRole('button',{name:'Calendar',exact:true}).click();await expect(bar).toHaveCSS('background-color','rgb(16, 32, 48)');await expect(bar).toHaveCSS('color','rgb(255, 255, 255)');
    await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.getByRole('button',{name:'Calendar',exact:true}).click();await page.getByLabel('Calendar date',{exact:true}).fill('2025-10-20');await expect(bar).toHaveCSS('background-color','rgb(16, 32, 48)');await expect(bar).toHaveAttribute('title',new RegExp(prefix+' renamed'));
    await page.screenshot({path:'test-results/client-calendar.png',fullPage:true});
    await page.getByRole('button',{name:'Clients',exact:true}).click();row=page.locator('#clients tr').filter({hasText:prefix});await row.getByRole('button',{name:'Delete',exact:true}).click();await page.locator('#save').click();await expect(page.locator('#editor-error')).toContainText('conflicts');await page.locator('#cancel').click();
    for(const [collection,values] of [['work-sessions',[session]],['projects',projects]]) for(const value of values) {const current=await api(request,'GET',`/${collection}/${value.id}`);await api(request,'DELETE',`/${collection}/${value.id}`,undefined,{'If-Match':current.headers().etag});}
    session=null;projects.length=0;
    await page.getByRole('button',{name:'Refresh',exact:true}).click();await expect(row).toBeVisible();await row.getByRole('button',{name:'Delete',exact:true}).click();await save(page);await expect(row).toHaveCount(0);client=null;
  } finally {
    for(const [collection,values] of [['work-sessions',session ? [session] : []],['projects',projects],['clients',client ? [client] : []]]) for(const value of values) {const current=await api(request,'GET',`/${collection}/${value.id}`);await api(request,'DELETE',`/${collection}/${value.id}`,undefined,{'If-Match':current.headers().etag});}
  }
});
