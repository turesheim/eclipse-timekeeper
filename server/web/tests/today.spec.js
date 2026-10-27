import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {captureScreenshot} from './screenshots.js';

const token=process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request,method,path,data,headers={}){
  const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
  expect(response.ok(),`${method} ${path}: ${response.status()}`).toBe(true);return response;
}

test('Today shows the selected journal, mini calendar, work sessions and imported all-day plans',async({page,request})=>{
  const original=await(await api(request,'GET','/me')).json(),date='2025-05-17',next='2025-05-18';
  let client,session,task;
  try{
    const profile=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag});
    client=await(await api(request,'POST','/clients',{name:'Today client '+randomUUID().slice(0,8),color:'#176653'})).json();
    task=await(await api(request,'POST','/tasks',{summary:'Scheduled review',projectId:null,parentTaskId:null,url:null})).json();
    session=await(await api(request,'POST','/work-sessions',{clientId:client.id,projectId:null,summary:'Today review',start:'2025-05-17T08:00:00Z',end:'2025-05-17T09:30:00Z'})).json();
    await page.route('**/api/v1/tasks?**',async route=>{
      const response=await api(request,'GET','/tasks?limit=500'),body=await response.json();
      await route.fulfill({status:200,headers:{'X-Timekeeper-Owner':response.headers()['x-timekeeper-owner']},contentType:'application/json',json:{...body,items:body.items.map(value=>value.id===task.id?{...value,status:'IN_PROGRESS',scheduledDate:date}:value)}});
    });
    await page.route('**/api/v1/calendar-events?**',async route=>{
      const response=await route.fetch(),body=await response.json(),from=new URL(route.request().url()).searchParams.get('fromDate');
      await route.fulfill({response,json:{...body,items:from===date?[{key:'today-plan',subscriptionId:randomUUID(),eventVersion:1,title:'All-day planning',description:'From an imported calendar',allDay:true,firstDate:date,endDate:next,clientName:client.name,calendarName:'Team plan',clientId:client.id,activityIds:[]}]:[]}});
    });
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);
    await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await page.locator('[data-tab=today]').click();await expect(page.locator('#today-panel')).toBeVisible();
    await page.locator('#today-date').fill(date);await expect(page.locator('#today-note-editor')).toContainText('Saturday');
    await expect(page.locator('#today-sessions')).toContainText('Today review');await expect(page.locator('#today-sessions')).toContainText('1h 30m');
    await expect(page.locator('#today-plans')).toContainText('All-day planning');
    await expect(page.locator('#today-planned-tasks')).toContainText('Scheduled review');
    await expect(page.locator('#today-active-tasks')).toContainText('Scheduled review');
    await expect(page.locator('#today-month-grid [data-date]')).toHaveCount(35);
    await page.locator('#today-note-text').fill('Review day');
    await page.locator('#today-note-editor').getByRole('button',{name:'Markdown source',exact:true}).click();
    await page.locator('#today-journal-body').fill('# Today\n\nPlan the release.\n');
    await page.locator('#today-note-editor').getByRole('button',{name:'Save',exact:true}).click();
    await expect(page.locator('#today-note-editor')).toContainText('Saved');
    const saved=await(await api(request,'GET','/day-notes/'+date)).json();expect(saved.text).toBe('Review day');expect(saved.body).toContain('Plan the release.');
    await page.locator('#today-note-text').fill('Unsaved change');
    page.once('dialog',dialog=>dialog.dismiss());
    await page.locator('#today-next').click();await expect(page.locator('#today-date')).toHaveValue(date);
    await expect(page.locator('#today-note-text')).toHaveValue('Unsaved change');
    page.once('dialog',dialog=>dialog.accept());
    await page.locator('#today-next').click();await expect(page.locator('#today-date')).toHaveValue(next);
    await expect(page.locator('#today-sessions')).toContainText('No work sessions');
    await expect(page.locator('#today-planned-tasks')).toContainText('No tasks planned for this day');
    await expect(page.locator('#today-active-tasks')).toContainText('Scheduled review');
    await page.locator('#today-month-grid [data-date="2025-05-17"]').click();await expect(page.locator('#today-date')).toHaveValue(date);
    await expect(page.locator('#today-note-text')).toHaveValue('Review day');
    await captureScreenshot(page,{path:'test-results/today-desktop.png'});
    await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    expect((await new AxeBuilder({page}).include('#today-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
    await captureScreenshot(page,{path:'test-results/today-mobile.png',fullPage:true});
  }finally{
    const note=await api(request,'GET','/day-notes/'+date);await api(request,'PUT','/day-notes/'+date,{text:'',body:''},{'If-Match':note.headers().etag});
    if(session){const current=await api(request,'GET','/work-sessions/'+session.id);await api(request,'DELETE','/work-sessions/'+session.id,undefined,{'If-Match':current.headers().etag});}
    if(task){const current=await api(request,'GET','/tasks/'+task.id);await api(request,'DELETE','/tasks/'+task.id,undefined,{'If-Match':current.headers().etag});}
    if(client){const current=await api(request,'GET','/clients/'+client.id);await api(request,'DELETE','/clients/'+client.id,undefined,{'If-Match':current.headers().etag});}
    const current=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:original.zoneId},{'If-Match':current.headers().etag});
  }
});
