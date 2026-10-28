import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {captureScreenshot} from './screenshots.js';

const token=process.env.TIMEKEEPER_WEB_TOKEN;
const CARD_STYLE_PROPERTIES=['fontFamily','fontSize','fontWeight','lineHeight','paddingTop','paddingRight','paddingBottom','paddingLeft','borderRadius','borderInlineStartWidth'];
const CARD_TITLE_STYLE_PROPERTIES=['fontSize','fontWeight','lineHeight','whiteSpace','textOverflow','overflow'];
async function api(request,method,path,data,headers={}){
  const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
  expect(response.ok(),`${method} ${path}: ${response.status()}`).toBe(true);return response;
}

test('Today shows the selected journal, mini calendar, work sessions and imported all-day plans',async({page,request})=>{
  const original=await(await api(request,'GET','/me')).json(),date='2025-05-17',next='2025-05-18',meetingTitle='Team meeting with a deliberately long calendar event title for truncation';
  let client,project,session,secondSession,task;
  try{
    const profile=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag});
    client=await(await api(request,'POST','/clients',{name:'Domstoladministrasjonen with a deliberately long client name '+randomUUID().slice(0,8),color:'#176653'})).json();
    project=await(await api(request,'POST','/projects',{name:'Today project',clientId:client.id,icon:'code'})).json();
    task=await(await api(request,'POST','/tasks',{summary:'Scheduled review',projectId:null,parentTaskId:null,url:null})).json();
    session=await(await api(request,'POST','/work-sessions',{clientId:client.id,projectId:null,summary:'Today review',start:'2025-05-17T08:00:00Z',end:'2025-05-17T09:30:00Z'})).json();
    secondSession=await(await api(request,'POST','/work-sessions',{clientId:client.id,projectId:project.id,summary:'Today follow-up',start:'2025-05-17T09:45:00Z',end:'2025-05-17T10:30:00Z'})).json();
    await page.route('**/api/v1/tasks?**',async route=>{
      const response=await api(request,'GET','/tasks?limit=500'),body=await response.json();
      await route.fulfill({status:200,headers:{'X-Timekeeper-Owner':response.headers()['x-timekeeper-owner']},contentType:'application/json',json:{...body,items:body.items.map(value=>value.id===task.id?{...value,status:'IN_PROGRESS',scheduledDate:date}:value)}});
    });
    await page.route('**/api/v1/calendar-events?**',async route=>{
      const response=await route.fetch(),body=await response.json(),from=new URL(route.request().url()).searchParams.get('fromDate');
      await route.fulfill({response,json:{...body,items:from===date?[{key:'today-plan',subscriptionId:randomUUID(),eventVersion:1,title:'All-day planning',description:'From an imported calendar',allDay:true,firstDate:date,endDate:next,clientName:client.name,calendarName:'Team plan',clientId:client.id,activityIds:[]},{key:'today-meeting',subscriptionId:randomUUID(),eventVersion:1,title:meetingTitle,description:'Timed calendar event',location:'Meeting room A',allDay:false,start:'2025-05-17T12:00:00Z',end:'2025-05-17T13:00:00Z',firstDate:date,endDate:next,clientName:client.name,calendarName:'Team plan',clientId:client.id,activityIds:[]}]:[]}});
    });
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);
    await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await expect(page.locator('[data-tab=today]')).toHaveAttribute('aria-current','page');await expect(page.locator('#today-panel')).toBeVisible();
    await page.locator('#today-date').fill(date);await expect(page.locator('#today-note-editor')).toBeVisible();
    await expect(page.locator('#today-title')).toContainText('Saturday');await expect(page.locator('#today-title')).toContainText('2025');
    await expect(page.locator('#today-zone')).toHaveCount(0);
    expect((await page.locator('.today-date-field').boundingBox()).width).toBeLessThan(180);
    expect(await page.locator('.today-toolbar').evaluate(toolbar=>{const heading=toolbar.querySelector('.track-heading').getBoundingClientRect(),controls=toolbar.querySelector('.track-controls').getBoundingClientRect();return Math.abs(heading.y+heading.height/2-controls.y-controls.height/2);})).toBeLessThan(2);
    await expect(page.locator('#today-note-title')).toHaveClass(/visually-hidden/);
    const dayLabel=page.locator('#today-day-label');
    await expect(dayLabel.getByLabel('Day label',{exact:true})).toBeEnabled();
    await expect(page.locator('#today-note-editor #today-note-text')).toHaveCount(0);
    expect(await dayLabel.locator('input').evaluate(input=>input.form?.id)).toBe('today-note-form');
    const toolbar=page.locator('#today-note-editor').getByRole('toolbar',{name:'Formatting',exact:true});
    await expect(toolbar).toHaveClass(/btn-toolbar/);await expect(toolbar.locator('.btn-group')).toHaveCount(2);
    await expect(page.locator('#today-journal-formatted')).toHaveCSS('border-top-width','0px');
    await expect(page.locator('#today-date-choose')).toHaveAccessibleName('Choose date');
    await expect(page.locator('#today-previous')).toHaveAccessibleName('Previous day');
    await expect(page.locator('#today-current')).toHaveAccessibleName('Today');
    await expect(page.locator('#today-next')).toHaveAccessibleName('Next day');
    await expect(page.locator('#today-sessions [data-today-client]')).toHaveCount(1);
    await expect(page.locator('#today-sessions')).toContainText(client.name);await expect(page.locator('#today-sessions')).toContainText('2h 15m');
    await page.locator('#today-sessions [data-today-client]').click();
    const picker=page.locator('#today-session-picker');await expect(picker).toBeVisible();await expect(picker.locator('[data-today-session-choice]')).toHaveCount(2);
    await expect(picker).toContainText('Today review');await expect(picker).toContainText('Today follow-up');
    await expect(picker.locator(`[data-today-session-choice="${secondSession.id}"] .bi-code`)).toBeVisible();
    await picker.locator(`[data-today-session-choice="${secondSession.id}"]`).click();await expect(page.locator('#editor')).toBeVisible();
    await expect(page.getByLabel('Summary',{exact:true})).toHaveValue('Today follow-up');await page.locator('#cancel').click();
    await expect(page.locator('#today-events')).toContainText('All-day planning');
    await expect(page.locator('#today-events')).toContainText('Team meeting');
    await expect(page.locator('#today-events')).toContainText(/14:00|2:00\s*PM/);
    await expect(page.locator('#today-events')).not.toContainText('Team plan');
    await expect(page.locator('#today-events')).not.toContainText('Timed calendar event');
    const meeting=page.locator('[data-today-event="today-meeting"]');
    await expect(meeting).toContainText(client.name);
    await expect(meeting).toHaveCSS('border-left-color','rgb(23, 102, 83)');
    await meeting.focus();await page.keyboard.press('Enter');
    const eventDetails=page.getByRole('dialog',{name:meetingTitle,exact:true});
    await expect(eventDetails).toBeVisible();await expect(eventDetails).toContainText('Team plan');
    await expect(eventDetails).toContainText('Meeting room A');await expect(eventDetails).toContainText('Timed calendar event');
    await page.keyboard.press('Escape');await expect(eventDetails).not.toBeVisible();await expect(meeting).toBeFocused();
    await page.locator('[data-today-event="today-plan"]').click();
    await expect(page.locator('#today-event-details')).toContainText('From an imported calendar');
    await expect(page.locator('#today-event-details')).toContainText('All day');
    await page.locator('#today-event-details').getByRole('button',{name:'Close',exact:true}).click();
    const cardStyle=locator=>locator.evaluate((card,{cardProperties,titleProperties})=>{
      const styles=getComputedStyle(card),title=getComputedStyle(card.querySelector('strong'));
      return Object.fromEntries(cardProperties.map(key=>[key,styles[key]]).concat(titleProperties.map(key=>['title.'+key,title[key]])));
    },{cardProperties:CARD_STYLE_PROPERTIES,titleProperties:CARD_TITLE_STYLE_PROPERTIES});
    for(const width of [1440,390]){
      await page.setViewportSize({width,height:1000});
      const sessionCard=page.locator('#today-sessions [data-today-client]'),eventCard=page.locator('[data-today-event="today-meeting"]');
      const sessionStyle=await cardStyle(sessionCard),eventStyle=await cardStyle(eventCard);
      for(const card of [sessionCard,eventCard]){
        await expect(card.locator('strong')).toHaveCSS('white-space','nowrap');await expect(card.locator('strong')).toHaveCSS('text-overflow','ellipsis');
        expect(await card.locator('strong').evaluate(title=>title.scrollWidth>title.clientWidth)).toBe(true);
        expect(await card.locator('strong').evaluate(title=>title.getBoundingClientRect().height<=parseFloat(getComputedStyle(title).lineHeight)+1)).toBe(true);
      }
      await expect(sessionCard).toHaveAttribute('title',new RegExp(client.name));await expect(eventCard).toHaveAttribute('title',new RegExp(meetingTitle));
      await expect(eventCard.locator('.calendar-summary-time')).toBeVisible();
      await page.locator('[data-tab=track]').click();await page.getByLabel('Month',{exact:true}).fill('2025-05');
      const calendarCard=page.locator(`[data-track-date="${date}"] [data-track-daily-client="${client.id}"]`);await expect(calendarCard).toBeAttached();
      const calendarStyle=await cardStyle(calendarCard);expect(sessionStyle).toEqual(calendarStyle);expect(eventStyle).toEqual(calendarStyle);
      await page.locator('[data-tab=today]').click();await expect(page.locator('#today-date')).toHaveValue(date);
    }
    for(const width of [1440,768,390]){
      await page.setViewportSize({width,height:1200});
      const position=await page.locator('.today-calendar').evaluate(root=>{const calendar=root.querySelector('.today-month').getBoundingClientRect(),label=root.querySelector('#today-day-label').getBoundingClientRect();return {gap:label.top-calendar.bottom,offset:label.left-calendar.left};});
      expect(position.gap).toBeGreaterThan(0);expect(Math.abs(position.offset)).toBeLessThan(1);
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    }
    await page.setViewportSize({width:1440,height:1200});
    await expect(page.locator('#today-sessions [data-today-client]')).toHaveCSS('font-size','12.8px');
    await expect(page.locator('#today-planned-tasks')).toContainText('Scheduled review');
    await expect(page.locator('#today-active-tasks')).toHaveCount(0);
    await expect(page.locator('#today-month-grid [data-date]')).toHaveCount(35);
    await expect(page.locator('#today-month-grid [data-week]')).toHaveCount(5);
    await expect(page.locator('#today-month-grid [data-week="2025-W20"] .today-month-dot')).toHaveCount(0);
    await page.locator('#today-month-grid [data-week="2025-W20"]').click();
    const weekly=page.locator('#today-week-note-editor');await expect(weekly).toBeVisible();
    await weekly.getByRole('button',{name:'Markdown source',exact:true}).click();
    await page.locator('#weekly-journal-body').fill('# Weekly review\n');
    await weekly.getByRole('button',{name:'Save',exact:true}).click();await expect(weekly).toContainText('Saved');
    const weekSaved=await(await api(request,'GET','/week-notes/2025-W20')).json();expect(weekSaved.body).toBe('# Weekly review\n');
    await expect(weekly.getByRole('button',{name:'Download Markdown'})).toHaveCount(0);
    await weekly.getByRole('button',{name:'Close',exact:true}).click();
    await expect(weekly).not.toBeVisible();
    await expect(page.locator('#today-month-grid [data-week="2025-W20"] .today-month-dot')).toHaveCount(1);
    await expect(page.locator('#today-month-grid [data-week="2025-W19"] .today-month-dot')).toHaveCount(0);
    await expect(page.locator('#today-month-grid [data-date="2025-05-17"] .today-month-dot')).toHaveCount(0);
    await page.locator('#today-note-text').fill('Review day');
    await page.locator('#today-note-editor').getByRole('button',{name:'Markdown source',exact:true}).click();
    await page.locator('#today-journal-body').fill('# Today\n\nPlan the release.\n');
    await page.locator('#today-note-editor').getByRole('button',{name:'Save',exact:true}).click();
    await expect(page.locator('#today-note-editor')).toContainText('Saved');
    const saved=await(await api(request,'GET','/day-notes/'+date)).json();expect(saved.text).toBe('Review day');expect(saved.body).toContain('Plan the release.');
    await expect(page.locator('#today-month-grid [data-date="2025-05-17"] .today-month-dot')).toHaveCount(1);
    await dayLabel.getByLabel('Day label',{exact:true}).fill('Sidebar label');await page.locator('#today-note-text').press('Enter');
    await expect(page.locator('#today-note-editor')).toContainText('Saved');
    expect((await(await api(request,'GET','/day-labels/'+date)).json()).text).toBe('Sidebar label');
    expect((await(await api(request,'GET','/day-notes/'+date+'/content')).json()).body).toContain('Plan the release.');
    await page.locator('#today-note-text').fill('Unsaved change');
    page.once('dialog',dialog=>dialog.dismiss());
    await page.locator('#today-next').click();await expect(page.locator('#today-date')).toHaveValue(date);
    await expect(page.locator('#today-note-text')).toHaveValue('Unsaved change');
    page.once('dialog',dialog=>dialog.accept());
    await page.locator('#today-next').click();await expect(page.locator('#today-date')).toHaveValue(next);
    await expect(page.locator('#today-title')).toContainText('Sunday');
    await page.locator('#today-previous').click();await expect(page.locator('#today-date')).toHaveValue(date);
    await page.locator('#today-next').click();await expect(page.locator('#today-date')).toHaveValue(next);
    await expect(page.locator('#today-sessions')).toContainText('No work sessions');
    await expect(page.locator('#today-planned-tasks')).toContainText('No tasks planned for this day');
    await expect(page.locator('#today-events')).toContainText('No calendar events');
    await page.locator('#today-month-grid [data-date="2025-05-17"]').click();await expect(page.locator('#today-date')).toHaveValue(date);
    await expect(page.locator('#today-note-text')).toHaveValue('Sidebar label');
    await page.evaluate(()=>window.scrollTo(0,50));
    await page.setViewportSize({width:1440,height:1200});await page.evaluate(()=>window.scrollTo(0,0));
    await expect.poll(()=>page.evaluate(()=>document.querySelector('#workspace-navigation').getBoundingClientRect().top-document.querySelector('#today-note-editor').getBoundingClientRect().bottom)).toBeLessThan(24);
    expect(await page.evaluate(()=>document.querySelector('#workspace-navigation').getBoundingClientRect().top-document.querySelector('#today-note-editor').getBoundingClientRect().bottom)).toBeGreaterThanOrEqual(0);
    expect(await page.locator('.today-month-day').evaluateAll(days=>days.every(day=>day.scrollHeight<=day.clientHeight+1))).toBe(true);
    await expect(page.locator('#today-note-editor .dialog-actions')).toHaveCSS('border-top-width','0px');
    await captureScreenshot(page,{path:'test-results/today-desktop.png'});
    await page.setViewportSize({width:390,height:844});await expect(page.locator('#today-sessions [data-today-client]')).toHaveCSS('font-size','12px');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    expect((await new AxeBuilder({page}).include('#today-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
    await captureScreenshot(page,{path:'test-results/today-mobile.png',fullPage:true});
    await page.locator('#today-month-grid [data-week="2025-W20"]').click();
    await expect(weekly).toContainText('Saved');
    page.once('dialog',dialog=>dialog.accept());await weekly.getByRole('button',{name:'Delete note',exact:true}).click();
    await expect(weekly).not.toBeVisible();
    await expect(page.locator('#today-month-grid [data-week="2025-W20"] .today-month-dot')).toHaveCount(0);
  }finally{
    await page.unrouteAll({behavior:'wait'});
    const week=await api(request,'GET','/week-notes/2025-W20');if(week.ok()&&Number((await week.json()).version)>0)await api(request,'DELETE','/week-notes/2025-W20',undefined,{'If-Match':week.headers().etag});
    const note=await api(request,'GET','/day-notes/'+date);await api(request,'PUT','/day-notes/'+date,{text:'',body:''},{'If-Match':note.headers().etag});
    if(session){const current=await api(request,'GET','/work-sessions/'+session.id);await api(request,'DELETE','/work-sessions/'+session.id,undefined,{'If-Match':current.headers().etag});}
    if(secondSession){const current=await api(request,'GET','/work-sessions/'+secondSession.id);await api(request,'DELETE','/work-sessions/'+secondSession.id,undefined,{'If-Match':current.headers().etag});}
    if(task){const current=await api(request,'GET','/tasks/'+task.id);await api(request,'DELETE','/tasks/'+task.id,undefined,{'If-Match':current.headers().etag});}
    if(project){const current=await api(request,'GET','/projects/'+project.id);await api(request,'DELETE','/projects/'+project.id,undefined,{'If-Match':current.headers().etag});}
    if(client){const current=await api(request,'GET','/clients/'+client.id);await api(request,'DELETE','/clients/'+client.id,undefined,{'If-Match':current.headers().etag});}
    const current=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:original.zoneId},{'If-Match':current.headers().etag});
  }
});
