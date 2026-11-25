import {browserRequest} from './browser-request.js';
import {workspaceDockSelector} from './navigation.js';
import {clickNoteAction} from './editor-interactions.js';
import {saveMarkdown} from './editor-interactions.js';
import {tooltipFor} from './tooltips.js';
import {resolvedLength} from './style-metrics.js';
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

test('Today matches Calendar spacing while tracking and keeps its normal stopped spacing',async({page,request})=>{
  const username='tracking-spacing-'+randomUUID().slice(0,8),password='Synthetic tracking spacing test password';
  await api(request,'POST','/admin/users',{username,password,displayName:'Tracking spacing test',email:username+'@example.invalid',administrator:false});
  await page.setViewportSize({width:1440,height:1000});
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');
  await page.locator('#username').fill(username);await page.locator('#password').fill(password);
  await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const own=async(method,path,data,headers={})=>{
    const res=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});
    expect(res.ok(),method+' '+path).toBe(true);
    return res;
  };
  const title='Review the long tracking task title in Today on desktop and on a narrow screen before switching to Calendar';
  const task=await(await own('POST','/tasks',{summary:title,projectId:null,parentTaskId:null,url:null})).json();
  const tracking=page.locator('.tracking'),today=page.locator('.today-toolbar'),calendar=page.locator('.track-toolbar');
  const toolbarGap=(card,toolbar)=>page.evaluate(({card,toolbar})=>{
    const bottom=document.querySelector(card).getBoundingClientRect().bottom;
    return document.querySelector(toolbar).getBoundingClientRect().top-bottom;
  },{card,toolbar});
  const stopped=[];
  for(const width of [1440,390]){
    await page.setViewportSize({width,height:1000});
    stopped.push(await toolbarGap('#work-session','.today-toolbar'));
  }
  const active=await own('POST','/activities/start',{taskId:task.id,summary:'Tracking spacing',labelIds:[],workSessionId:null,
    expectedActiveId:null,expectedActiveVersion:null},{'Idempotency-Key':randomUUID()});
  await page.locator('#refresh').click();await expect(tracking).toBeVisible();
  await page.setViewportSize({width:1440,height:1000});
  const wideTitle=await page.locator('#active-title').evaluate(node=>node.getBoundingClientRect().height);
  for(const width of [1440,390]){
    await page.setViewportSize({width,height:1000});
    await expect(today).toBeVisible();
    if(width===390)await expect.poll(()=>page.locator('#active-title').evaluate(node=>node.getBoundingClientRect().height)).toBeGreaterThan(wideTitle);
    const todayGap=await toolbarGap('.tracking','.today-toolbar');
    if(width===1440)await expect.poll(()=>page.evaluate(selector=>{
      const dock=document.querySelector(selector),editor=document.querySelector('#today-note-editor');
      return editor.getBoundingClientRect().bottom-dock.getBoundingClientRect().top;
    },workspaceDockSelector)).toBeLessThanOrEqual(1);
    await page.locator('[data-tab=track]').click();await expect(calendar).toBeVisible();
    expect(todayGap).toBeCloseTo(await toolbarGap('.tracking','.track-toolbar'),0);
    await page.locator('[data-tab=today]').click();
  }
  const activity=await active.json();
  await own('POST','/activities/'+activity.id+'/stop',undefined,{'If-Match':active.headers().etag,'Idempotency-Key':randomUUID()});
  await page.locator('#refresh').click();await expect(tracking).toBeHidden();
  for(const [index,width] of [1440,390].entries()){
    await page.setViewportSize({width,height:1000});
    expect(await toolbarGap('#work-session','.today-toolbar')).toBeCloseTo(stopped[index],0);
  }
});

test('weekly notes show events by day and tasks by deadline, then restore the daily sidebar',async({page,request})=>{
  const first='2026-12-28',last='2027-01-03',end='2027-01-04';
  const profile=await api(request,'GET','/me');
  await api(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag});
  const source=await(await api(request,'POST','/clients',{name:'Weekly agenda '+randomUUID().slice(0,8),color:'#176653'})).json();
  const subscriptionId=randomUUID();
  const event=(key,title,firstDate,endDate)=>({key,subscriptionId,title,allDay:true,firstDate,endDate,clientId:source.id,clientName:source.name,activityIds:[]});
  const events=[event('weekly-monday','Monday meeting',first,'2026-12-29'),event('weekly-sunday','Sunday review',last,end)];
  await page.route('**/api/v1/calendar-events?**',async route=>{
    const response=await route.fetch(),body=await response.json(),query=new URL(route.request().url()).searchParams;
    await route.fulfill({response,json:{...body,items:events.filter(event=>event.firstDate<query.get('toDate')&&event.endDate>query.get('fromDate'))}});
  });
  for(const [summary,date] of [['Monday deadline',first],['Sunday deadline',last],['Next week deadline',end]]){
    const created=await api(request,'POST','/tasks',{summary,projectId:null,parentTaskId:null,url:null});
    const {id}=await created.json();
    await api(request,'PUT','/tasks/'+id+'/timing',{start:null,due:{kind:'DATE',date}},{'If-Match':created.headers().etag});
  }
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);
  await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  await page.locator('#today-date').fill(first);
  const eventsRoot=page.locator('#today-events'),tasks=page.locator('#today-planned-tasks'),sessions=page.locator('#today-sessions');
  await expect(eventsRoot).toContainText('Monday meeting');
  await expect(eventsRoot).not.toContainText('Sunday review');
  await expect(sessions).toBeVisible();

  await page.locator('#today-month-grid [data-week="2026-W53"]').click();
  await expect(page.locator('#today-week-note-editor')).toBeVisible();
  await expect(sessions).toBeHidden();
  await expect(eventsRoot.getByRole('heading',{level:4})).toHaveText([/Monday.*28/,/Sunday.*3/]);
  await expect(eventsRoot.locator('ul')).toHaveText([/Monday meeting/,/Sunday review/]);
  await expect(tasks.getByRole('heading',{level:4})).toHaveText([/Monday.*28/,/Sunday.*3/]);
  await expect(tasks.locator('ul')).toHaveText([/Monday deadline/,/Sunday deadline/]);
  await expect(tasks).not.toContainText('Next week deadline');
  await page.locator('#today-week-note-editor').getByRole('button',{name:'Close',exact:true}).click();
  await expect(sessions).toBeVisible();
  await expect(eventsRoot).toContainText('Monday meeting');
  await expect(eventsRoot).not.toContainText('Sunday review');
  await expect(tasks).not.toContainText('Sunday deadline');
});

test('Today sidebars resize with pointer and keyboard while preserving the journal and mobile layout',async({page,request})=>{
  const date='2026-09-14',original=(await(await api(request,'GET','/day-notes/'+date+'/content')).json()).body,draft='# Sidebar layout\n\nSidebar resize draft '+randomUUID();
  try{
    await page.setViewportSize({width:1440,height:1000});await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('#today-date').fill(date);
    const resize=page.getByRole('separator',{name:'Resize Today sidebar',exact:true}),sidebar=page.locator('#today-sidebar'),journal=page.locator('#today-note-editor');await expect(resize).toBeVisible();await expect(resize).toHaveAttribute('aria-orientation','vertical');
    await journal.getByRole('button',{name:'Markdown source',exact:true}).click();const body=page.locator('#today-journal-body');await body.fill(draft);
    const start=await resize.boundingBox(),initial=(await sidebar.boundingBox()).width;await page.mouse.move(start.x+start.width/2,start.y+start.height/2);await page.mouse.down();await page.mouse.move(start.x+start.width/2+100,start.y+start.height/2,{steps:4});await page.mouse.up();
    await expect.poll(async()=>(await sidebar.boundingBox()).width).toBeCloseTo(initial+100,0);await expect(body).toHaveValue(draft);
    await resize.focus();await resize.press('End');await expect(resize).toHaveAttribute('aria-valuenow','480');await expect.poll(async()=>(await sidebar.boundingBox()).width).toBeCloseTo(480,0);
    await resize.press('ArrowLeft');await expect(resize).toHaveAttribute('aria-valuenow','460');await resize.press('ArrowRight');await expect(resize).toHaveAttribute('aria-valuenow','480');await resize.press('ArrowRight');await expect(resize).toHaveAttribute('aria-valuenow','480');
    await resize.press('Home');await expect(resize).toHaveAttribute('aria-valuenow','180');await resize.press('ArrowLeft');await expect(resize).toHaveAttribute('aria-valuenow','180');await resize.press('End');
    const right=page.getByRole('separator',{name:'Resize Today table of contents',exact:true}),contents=page.locator('#today-contents'),rightToggle=page.locator('#today-main-actions-toggle-right-sidebar');await expect(right).toHaveAttribute('aria-controls','today-contents');
    await expect(contents.getByRole('button',{name:'Sidebar layout',exact:true})).toBeVisible();await expect(contents.getByRole('button').first()).toHaveCSS('font-family',await page.locator('#today-panel').evaluate(node=>getComputedStyle(node).fontFamily));
    const rightStart=await right.boundingBox(),rightWidth=(await contents.boundingBox()).width;await page.mouse.move(rightStart.x+rightStart.width/2,rightStart.y+rightStart.height/2);await page.mouse.down();await page.mouse.move(rightStart.x+rightStart.width/2-80,rightStart.y+rightStart.height/2,{steps:4});await page.mouse.up();
    await expect.poll(async()=>(await contents.boundingBox()).width).toBeCloseTo(rightWidth+80,0);await right.press('ArrowLeft');await expect(right).toHaveAttribute('aria-valuenow',String(rightWidth+100));await right.press('Home');await expect(right).toHaveAttribute('aria-valuenow','180');await right.press('End');await expect(right).toHaveAttribute('aria-valuenow','480');
    await rightToggle.click();await expect(right).toBeHidden();await rightToggle.click();await expect(right).toHaveAttribute('aria-valuenow','480');
    await expect(body).toHaveValue(draft);await expect.poll(async()=>(await(await api(request,'GET','/day-notes/'+date+'/content')).json()).body).toBe(draft);
    expect((await new AxeBuilder({page}).include('#today-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);await captureScreenshot(page,{path:'test-results/today-sidebar-wide.png'});
    await right.focus();await page.setViewportSize({width:1000,height:844});await expect(right).toBeHidden();await expect(rightToggle).toBeFocused();await page.setViewportSize({width:1440,height:1000});await expect(right).toBeVisible();
    await resize.focus();await page.setViewportSize({width:1000,height:844});await expect(resize).toBeHidden();await expect(page.locator('#today-date')).toBeFocused();
    const leftToggle=page.locator('#today-main-actions-toggle-left-sidebar');
    await leftToggle.click();await expect(sidebar).toBeHidden();await leftToggle.focus();
    await page.setViewportSize({width:390,height:844});
    await expect(resize).toBeHidden();await expect(leftToggle).toBeHidden();await expect(page.locator('#today-date')).toBeFocused();
    await expect(sidebar).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await expect(body).toHaveValue(draft);await captureScreenshot(page,{path:'test-results/today-sidebar-mobile.png',fullPage:true});
    await page.setViewportSize({width:1000,height:844});await expect(leftToggle).toBeVisible();await expect(sidebar).toBeHidden();
    await page.setViewportSize({width:1440,height:1000});await expect(resize).toBeVisible();await expect.poll(async()=>(await sidebar.boundingBox()).width).toBeCloseTo(480,0);
    await page.locator('[data-tab=calendar]').click();await page.locator('[data-tab=today]').click();await expect(resize).toHaveAttribute('aria-valuenow','480');await expect(body).toHaveValue(draft);
  }finally{const current=await api(request,'GET','/day-notes/'+date+'/content');await api(request,'PUT','/day-notes/'+date+'/content',{body:original},{'If-Match':current.headers().etag});}
});

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
    expect((await page.locator('.today-date-navigation .date-navigation-field').boundingBox()).width).toBeLessThan(180);
    expect(await page.locator('.today-toolbar').evaluate(toolbar=>{const heading=toolbar.querySelector('.track-heading').getBoundingClientRect(),controls=toolbar.querySelector('.track-controls').getBoundingClientRect();return Math.abs(heading.y+heading.height/2-controls.y-controls.height/2);})).toBeLessThan(2);
    await expect(page.locator('#today-note-title')).toHaveClass(/visually-hidden/);
    const gridSizes=await page.locator('#today-month-grid').evaluate(grid=>[...new Set([...grid.children].map(child=>getComputedStyle(child).fontSize))]);
    expect(gridSizes).toEqual([await resolvedLength(page,'--font-today-calendar')]);
    const headerSizes=await page.locator('.today-sidebar h3').evaluateAll(headers=>[...new Set(headers.map(header=>getComputedStyle(header).fontSize))]);
    expect(headerSizes).toEqual([await resolvedLength(page,'--font-today-sidebar-heading')]);
    expect(await page.locator('#today-month-grid').evaluate(grid=>getComputedStyle(grid,'::before').borderInlineStartWidth)).toBe('1px');
    const dayLabel=page.locator('#today-day-label');
    await expect(dayLabel.getByLabel('Day label',{exact:true})).toBeEnabled();
    await expect(dayLabel).not.toHaveClass(/card/);await expect(dayLabel).toHaveCSS('border-top-width','0px');
    await expect(dayLabel.locator('label')).toHaveClass(/visually-hidden/);await expect(await tooltipFor(dayLabel.locator('.bi-tag'))).toContainText('Day label');
    const labelBox=await dayLabel.locator('input').boundingBox(),labelIcon=await dayLabel.locator('.bi-tag').boundingBox();expect(labelIcon.x).toBeGreaterThan(labelBox.x+labelBox.width);
    await expect(page.locator('#today-note-editor #today-note-text')).toHaveCount(0);
    expect(await dayLabel.locator('input').evaluate(input=>input.form?.id)).toBe('today-note-form');
    const toolbar=page.locator('#today-note-editor').getByRole('toolbar',{name:'Formatting',exact:true});
    await expect(toolbar).toHaveClass(/btn-toolbar/);for(const name of ['Editing mode','Formatting'])await expect(toolbar.getByRole('group',{name,exact:true})).toHaveCount(1);await expect(toolbar.getByRole('button',{name:'Note tools',exact:true})).toHaveCount(1);await expect(toolbar.getByRole('separator')).toHaveCount(0);
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
      await expect(await tooltipFor(sessionCard)).toContainText(new RegExp(client.name));await expect(await tooltipFor(eventCard)).toContainText(new RegExp(meetingTitle));
      await expect(eventCard.locator('.calendar-summary-time')).toBeVisible();
      await page.locator('[data-tab=track]').click();await page.getByLabel('Month',{exact:true}).fill('2025-05');
      const calendarCard=page.locator(`[data-track-date="${date}"] [data-track-daily-client="${client.id}"]`);await expect(calendarCard).toBeAttached();
      const calendarStyle=await cardStyle(calendarCard);expect(sessionStyle).toEqual(calendarStyle);expect(eventStyle).toEqual(calendarStyle);
      await page.locator('[data-tab=today]').click();await expect(page.locator('#today-date')).toHaveValue(date);
    }
    for(const width of [1440,768]){
      await page.setViewportSize({width,height:1200});
      await expect(page.locator('.today-calendar #today-day-label')).toBeVisible();
      const position=await page.locator('.today-calendar').evaluate(root=>{const calendar=root.querySelector('.today-month').getBoundingClientRect(),label=root.querySelector('#today-day-label').getBoundingClientRect();return {gap:label.top-calendar.bottom,offset:label.left-calendar.left};});
      expect(position.gap).toBeGreaterThan(0);expect(Math.abs(position.offset)).toBeLessThan(1);
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    }
    await page.setViewportSize({width:1440,height:1200});
    await expect(page.locator('#today-sessions [data-today-client]')).toHaveCSS('font-size',await resolvedLength(page,'--font-calendar-card'));
    await expect(page.locator('#today-planned-tasks')).toContainText('Scheduled review');
    await expect(page.locator('#today-active-tasks')).toHaveCount(0);
    await expect(page.locator('#today-month-grid [data-date]')).toHaveCount(35);
    await expect(page.locator('#today-month-grid [data-week]')).toHaveCount(5);
    await expect(page.locator('#today-month-grid [data-week="2025-W20"] .today-month-dot')).toHaveCount(0);
    await page.locator('#today-month-grid [data-week="2025-W20"]').click();
    const weekly=page.locator('#today-journal > section#today-week-note-editor'),daily=page.locator('#today-note-editor');
    await expect(weekly).toBeVisible();await expect(daily).toBeHidden();
    await expect(page.locator('#today-title')).toHaveText('Week note 2025-W20');
    await expect(page.locator('#today-month-grid [data-week="2025-W20"]')).toHaveAttribute('aria-pressed','true');
    await weekly.getByRole('button',{name:'Markdown source',exact:true}).click();
    await page.locator('#weekly-journal-body').fill('# Weekly review\n');
    await saveMarkdown(weekly);await expect(weekly).toContainText('Saved');
    const weekSaved=await(await api(request,'GET','/week-notes/2025-W20')).json();expect(weekSaved.body).toBe('# Weekly review\n');
    await expect(weekly.getByRole('button',{name:'Download Markdown'})).toHaveCount(0);
    await expect(page.locator('#today-outline').getByRole('button',{name:'Weekly review',exact:true})).toBeVisible();
    await page.setViewportSize({width:390,height:844});
    await expect(weekly).toBeVisible();
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await captureScreenshot(page,{path:'test-results/today-weekly-inline-phone.png'});
    await weekly.getByRole('button',{name:'Close',exact:true}).click();
    await expect(weekly).toBeHidden();await expect(daily).toBeVisible();
    const shortcut=page.locator('.today-week-shortcut');
    await expect(shortcut).toBeFocused();await shortcut.press('Enter');
    await expect(weekly).toBeVisible();await expect(daily).toBeHidden();
    await page.setViewportSize({width:1440,height:1200});
    // Switching to the already-selected date leaves the weekly editor and flushes its draft.
    const weekBody=page.locator('#weekly-journal-body');
    await weekly.getByRole('button',{name:'Markdown source',exact:true}).click();
    await weekBody.fill('# Weekly review\n\nSaved on return to the day.\n');
    await page.locator(`#today-month-grid [data-date="${date}"]`).click();
    await expect(weekly).toBeHidden();await expect(daily).toBeVisible();
    expect((await(await api(request,'GET','/week-notes/2025-W20')).json()).body).toContain('Saved on return to the day.');
    await expect(page.locator('#today-month-grid [data-week="2025-W20"] .today-month-dot')).toHaveCount(1);
    await expect(page.locator('#today-month-grid [data-week="2025-W19"] .today-month-dot')).toHaveCount(0);
    await expect(page.locator('#today-month-grid [data-date="2025-05-17"] .today-month-dot')).toHaveCount(0);
    await page.locator('#today-note-text').fill('Review day');
    await page.locator('#today-note-editor').getByRole('button',{name:'Markdown source',exact:true}).click();
    await page.locator('#today-journal-body').fill('# Today\n\nPlan the release.\n');
    await saveMarkdown(page.locator('#today-note-editor'));
    await expect(page.locator('#today-note-editor')).toContainText('Saved');
    const saved=await(await api(request,'GET','/day-notes/'+date)).json();expect(saved.text).toBe('Review day');expect(saved.body).toContain('Plan the release.');
    await expect(page.locator('#today-month-grid [data-date="2025-05-17"] .today-month-dot')).toHaveCount(1);
    await dayLabel.getByLabel('Day label',{exact:true}).fill('Sidebar label');await page.locator('#today-note-text').press('Enter');
    await expect(page.locator('#today-note-editor')).toContainText('Saved');
    expect((await(await api(request,'GET','/day-labels/'+date)).json()).text).toBe('Sidebar label');
    expect((await(await api(request,'GET','/day-notes/'+date+'/content')).json()).body).toContain('Plan the release.');
    await page.locator('#today-note-text').fill('Unsaved change');
    await page.locator('#today-next').click();await expect(page.locator('#today-date')).toHaveValue(next);
    expect((await(await api(request,'GET','/day-labels/'+date)).json()).text).toBe('Unsaved change');
    await expect(page.locator('#today-title')).toContainText('Sunday');
    await page.locator('#today-previous').click();await expect(page.locator('#today-date')).toHaveValue(date);
    await page.locator('#today-next').click();await expect(page.locator('#today-date')).toHaveValue(next);
    await expect(page.locator('#today-sessions')).toContainText('No work sessions');
    await expect(page.locator('#today-planned-tasks')).toContainText('No tasks planned for this day');
    await expect(page.locator('#today-events')).toContainText('No calendar events');
    await page.locator('#today-month-grid [data-date="2025-05-17"]').click();await expect(page.locator('#today-date')).toHaveValue(date);
    await expect(page.locator('#today-note-text')).toHaveValue('Unsaved change');
    await page.evaluate(()=>window.scrollTo(0,50));
    await page.setViewportSize({width:1440,height:1200});await page.evaluate(()=>window.scrollTo(0,0));
    await expect.poll(()=>page.evaluate(dock=>document.querySelector(dock).getBoundingClientRect().top-document.querySelector('#today-note-editor').getBoundingClientRect().bottom,workspaceDockSelector)).toBeLessThan(24);
    expect(await page.evaluate(dock=>document.querySelector(dock).getBoundingClientRect().top-document.querySelector('#today-note-editor').getBoundingClientRect().bottom,workspaceDockSelector)).toBeGreaterThanOrEqual(0);
    expect(await page.locator('.today-month-day').evaluateAll(days=>days.every(day=>day.scrollHeight<=day.clientHeight+1))).toBe(true);
    await expect(page.locator('#today-note-form > .dialog-actions')).toHaveCSS('border-top-width','0px');
    await captureScreenshot(page,{path:'test-results/today-desktop.png'});
    await page.setViewportSize({width:390,height:844});await expect(page.locator('#today-sessions [data-today-client]')).toHaveCSS('font-size',await resolvedLength(page,'--font-calendar-card-mobile'));expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    expect((await new AxeBuilder({page}).include('#today-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
    await captureScreenshot(page,{path:'test-results/today-mobile.png',fullPage:true});
    const datePicker=page.getByLabel('Date',{exact:true});
    await datePicker.fill(next);
    await expect(page.locator('#today-title')).toContainText('Sunday');
    await expect(page.locator('#today-note-text')).toHaveValue('');
    await expect(page.locator('#today-sessions')).toContainText('No work sessions');
    await expect(page.locator('#today-events')).toContainText('No calendar events');
    await expect(page.locator('#today-planned-tasks')).toContainText('No tasks planned for this day');
    await datePicker.fill(date);
    await expect(page.locator('#today-note-text')).toHaveValue('Unsaved change');
    await page.setViewportSize({width:1440,height:1200});
    await page.locator('#today-month-grid [data-week="2025-W20"]').click();
    await expect(weekly).toContainText('Saved');
    page.once('dialog',dialog=>dialog.accept());await clickNoteAction(page,weekly,'Delete note');
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

test('Today phone journal applies native and menu formatting and reopens in rich text',async({browser,request})=>{
  const date='2025-04-14',path='/day-notes/'+date+'/content',original=await api(request,'GET',path);
  const originalBody=(await original.json()).body;
  await api(request,'PUT',path,{body:'Alpha Beta Gamma\n'},{'If-Match':original.headers().etag});
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true,viewport:{width:440,height:956},isMobile:true,hasTouch:true});
  try{
    const page=await context.newPage();
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);await page.locator('#login-form button[type=submit]').click();
    await expect(page.locator('#status')).toHaveText('Up to date');
    const datePicker=page.getByLabel('Date',{exact:true});
    await datePicker.fill(date);
    const journal=page.locator('#today-note-editor'),rich=journal.locator('.ProseMirror'),paragraph=rich.locator('p');
    const trigger=page.locator('#today-main-actions').getByRole('button',{name:'Note tools',exact:true});
    await expect(paragraph).toHaveText('Alpha Beta Gamma');await expect(rich).toBeEditable();
    await expect(journal.getByRole('toolbar',{name:'Formatting',exact:true})).toBeHidden();
    await expect(journal.locator('#today-journal-body')).toBeHidden();
    // Let automatic banner metadata settle before making the first edit.
    await expect(journal.locator('.markdown-banner img')).toHaveAttribute('src',/^blob:/);
    const select=(word,inputType)=>paragraph.evaluate((node,{word,inputType})=>{
      const editor=node.closest('.ProseMirror');editor.focus();
      const walker=document.createTreeWalker(node,NodeFilter.SHOW_TEXT);let leaf;
      while((leaf=walker.nextNode())&&!leaf.textContent.includes(word)){}
      if(!leaf)throw new Error('Text to format was missing');
      const start=leaf.textContent.indexOf(word);
      getSelection().setBaseAndExtent(leaf,start,leaf,start+word.length);
      if(inputType)return editor.dispatchEvent(new InputEvent('beforeinput',{inputType,bubbles:true,cancelable:true}));
      return true;
    },{word,inputType});
    expect(await select('Alpha','formatBold')).toBe(false);
    expect(await select('Beta','formatItalic')).toBe(false);
    await expect(rich.locator('strong')).toHaveText('Alpha');await expect(rich.locator('em')).toHaveText('Beta');
    expect(await select('Alpha','formatBold')).toBe(false);
    await expect(rich.locator('strong')).toHaveCount(0);
    await select('Alpha');
    await trigger.tap();await expect(journal.getByRole('menuitem',{name:'Bold',exact:true})).toBeFocused();
    await journal.getByRole('menuitem',{name:'Bold',exact:true}).tap();
    await expect(rich.locator('strong')).toHaveText('Alpha');
    await select('Gamma');
    // Touch selection can move between pressing the toolbar and opening its menu.
    await trigger.evaluate(button=>button.addEventListener('pointerup',()=>{
      const alpha=document.querySelector('#today-note-editor .ProseMirror strong').firstChild;
      getSelection().setBaseAndExtent(alpha,0,alpha,alpha.length);
    },{once:true}));
    // A click synthesized after a touch press can have a zero click count.
    await trigger.evaluate(button=>button.addEventListener('click',event=>{
      Object.defineProperty(event,'detail',{value:0});
    },{capture:true,once:true}));
    await trigger.tap();await journal.getByRole('menuitem',{name:'Italic',exact:true}).tap();
    await expect(rich.locator('em')).toHaveText(['Beta','Gamma']);
    await expect.poll(async()=>(await(await api(request,'GET',path)).json()).body).toMatch(/\*\*Alpha\*\* [*_]Beta[*_] [*_]Gamma[*_]/);
    await page.evaluate(()=>{document.documentElement.dataset.theme='timekeeper-dark';document.documentElement.dataset.bsTheme='dark';});
    await trigger.tap();
    await captureScreenshot(page,{path:'test-results/today-phone-note-tools.png'});
    await journal.getByRole('menuitem',{name:'Markdown source',exact:true}).tap();
    await expect(journal.locator('#today-journal-body')).toHaveValue(/\*\*Alpha\*\*/);
    await datePicker.fill('2025-04-15');await expect(rich).toBeEditable();await expect(rich).not.toContainText('Alpha');
    await datePicker.fill(date);await expect(rich.locator('strong')).toHaveText('Alpha');await expect(rich.locator('em')).toHaveText(['Beta','Gamma']);
  }finally{
    await context.close();
    const current=await api(request,'GET',path);await api(request,'PUT',path,{body:originalBody},{'If-Match':current.headers().etag});
  }
});

test('Today phone journal keeps source accessible when the rich editor fails to load',async({page})=>{
  await page.setViewportSize({width:390,height:844});
  const editorModule='**/assets/milkdown/milkdown.js*';
  await page.route(editorModule,route=>route.abort('failed'));
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);await page.locator('#login-form button[type=submit]').click();
  await expect(page.locator('#status')).toHaveText('Up to date');
  const journal=page.locator('#today-note-editor'),source=journal.locator('#today-journal-body');
  await expect(journal.getByRole('toolbar',{name:'Formatting',exact:true})).toBeHidden();
  await expect(journal.locator('.markdown-editor > [role=status]')).toContainText(/could not|failed/i);
  await expect(source).toBeEditable();
  await page.unroute(editorModule);
  const trigger=page.locator('#today-main-actions').getByRole('button',{name:'Note tools',exact:true});
  await trigger.press('ArrowDown');
  await expect(journal.getByRole('menuitem',{name:'Bold',exact:true})).toBeHidden();
  await journal.getByRole('menuitem',{name:'Formatted',exact:true}).press('Enter');
  await expect(journal.locator('.ProseMirror')).toBeEditable();
  await expect(source).toBeHidden();
});

test('Today phone keeps the journal caret and actions above a keyboard-sized viewport',async({page,request})=>{
  const date='2025-04-16',path='/day-notes/'+date+'/content',original=await api(request,'GET',path);
  const originalBody=(await original.json()).body;
  const body=Array.from({length:100},(_,i)=>`Paragraph ${i+1}: a long daily journal.`).join('\n\n')+'\n';
  await api(request,'PUT',path,{body},{'If-Match':original.headers().etag});
  try{
    await page.setViewportSize({width:390,height:844});
    // Desktop browser engines do not display the iPhone keyboard. Drive the
    // visual viewport separately from the layout viewport, as it moves on iOS.
    await page.addInitScript(()=>{
      const actual=window.visualViewport,viewport=new EventTarget();
      for(const property of ['width','height','scale','offsetTop','offsetLeft']){
        Object.defineProperty(viewport,property,{get:()=>values[property]??actual[property]});
      }
      let values={};
      window.setTestViewport=(next,event='resize')=>{values=next;viewport.dispatchEvent(new Event(event));};
      Object.defineProperty(window,'visualViewport',{value:viewport});
    });
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);await page.locator('#login-form button[type=submit]').click();
    await expect(page.locator('#status')).toHaveText('Up to date');
    await page.getByLabel('Date',{exact:true}).fill(date);
    const journal=page.locator('#today-note-editor'),rich=journal.locator('.ProseMirror');
    const navigation=page.locator('#workspace-navigation'),lastSummary=page.locator('#today-planned-tasks').locator('..');
    await expect(rich.locator('p').last()).toHaveText('Paragraph 100: a long daily journal.');
    await expect(journal.locator('.markdown-banner img')).toHaveAttribute('src',/^blob:/);
    await page.evaluate(()=>document.documentElement.style.setProperty('--workspace-nav-bottom-inset','34px'));
    await rich.evaluate(editor=>{
      editor.focus();
      const text=editor.querySelector('p:last-of-type').lastChild,range=document.createRange();
      range.setStart(text,text.length);range.collapse(true);
      const selection=getSelection();selection.removeAllRanges();selection.addRange(range);
      editor.scrollTop=editor.scrollHeight;
      window.scrollTo(0,0);
    });
    const caretVisible=()=>rich.evaluate(editor=>{
      const selection=getSelection(),caret=selection.getRangeAt(0).getBoundingClientRect(),box=editor.getBoundingClientRect(),viewport=visualViewport;
      const dock=document.querySelector('#workspace-navigation'),dockTop=getComputedStyle(dock).visibility==='hidden'?Infinity:dock.getBoundingClientRect().top;
      return selection.isCollapsed&&caret.height>0&&caret.top>=Math.max(box.top,viewport.offsetTop)&&caret.bottom<=Math.min(box.bottom,viewport.offsetTop+viewport.height,dockTop);
    });
    // A zoomed viewport alone should leave the dock available.
    await page.evaluate(async()=>{
      window.setTestViewport({height:422,scale:2});
      await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    });
    await expect(navigation).toBeVisible();
    await page.evaluate(()=>window.setTestViewport({height:390,offsetTop:24}));
    await expect(navigation).toBeHidden();
    await expect.poll(caretVisible).toBe(true);
    await expect(rich).toBeFocused();
    await page.evaluate(()=>window.setTestViewport({height:390,offsetTop:96},'scroll'));
    await expect.poll(caretVisible).toBe(true);
    await page.keyboard.insertText(' Still visible.');
    await expect(rich.locator('p').last()).toHaveText('Paragraph 100: a long daily journal. Still visible.');
    await expect.poll(caretVisible).toBe(true);
    await captureScreenshot(page,{path:'test-results/today-keyboard-viewport.png'});
    // While the layout viewport stays tall, the bottom of Today must still scroll
    // above the obscured area.
    await page.evaluate(()=>window.scrollTo(0,document.documentElement.scrollHeight));
    await expect.poll(()=>lastSummary.evaluate(node=>node.getBoundingClientRect().bottom<=visualViewport.offsetTop+visualViewport.height)).toBe(true);
    await page.evaluate(()=>window.setTestViewport({}));
    await expect(navigation).toBeVisible();
    await expect.poll(caretVisible).toBe(true);
    await expect.poll(()=>navigation.evaluate(dock=>[...dock.querySelectorAll('button')].every(button=>button.getBoundingClientRect().bottom<=dock.getBoundingClientRect().bottom-34))).toBe(true);
    await page.evaluate(()=>window.scrollTo(0,document.documentElement.scrollHeight));
    await expect.poll(()=>lastSummary.evaluate(node=>node.getBoundingClientRect().bottom<=document.querySelector('#workspace-navigation').getBoundingClientRect().top)).toBe(true);
    await expect.poll(async()=>(await(await api(request,'GET',path)).json()).body).toContain('Still visible.');
  }finally{
    const current=await api(request,'GET',path);await api(request,'PUT',path,{body:originalBody},{'If-Match':current.headers().etag});
  }
});

test('Today editor stays above navigation with long Markdown in either mode',async({page,request})=>{
  const date='2025-04-13',body=Array.from({length:100},(_,i)=>`Paragraph ${i}: long journal content.`).join('\n\n');
  const existing=await api(request,'GET','/day-notes/'+date);
  await api(request,'PUT','/day-notes/'+date,{text:'',body},{'If-Match':existing.headers().etag});
  try {
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);await page.locator('#login-form button[type=submit]').click();
    await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=today]').click();await page.getByLabel('Date',{exact:true}).fill(date);
    for(const [width,height] of [[1440,1000],[1440,720],[390,844]]) {
      await page.setViewportSize({width,height});await page.evaluate(()=>window.scrollTo(0,0));
      if(width===1440)await expect.poll(()=>page.evaluate(()=>Math.max(0,document.documentElement.scrollHeight-innerHeight)),{message:'Today fits after the viewport resize is laid out'}).toBeLessThanOrEqual(1);
      const editor=page.locator('#today-note-editor');
      for(const mode of ['Formatted','Markdown source']) {
        if(width===390){
          await page.locator('#today-main-actions').getByRole('button',{name:'Note tools',exact:true}).click();
          await editor.getByRole('menuitem',{name:mode,exact:true}).click();
        }else await editor.getByRole('button',{name:mode,exact:true}).click();
        await expect.poll(async()=>{const a=await editor.boundingBox(),nav=await page.locator(workspaceDockSelector).boundingBox(),gap=parseFloat(await resolvedLength(page,'--workspace-content-gap'));return Math.abs(nav.y-(a.y+a.height)-gap);}).toBeLessThan(2);
        const content=mode==='Formatted'?editor.locator('.ProseMirror'):editor.locator('textarea');
        await expect(content).toBeVisible();expect(await content.evaluate(value=>value.scrollHeight>value.clientHeight)).toBe(true);
        const actions=await editor.locator('#today-note-form > .dialog-actions').boundingBox(),nav=await page.locator(workspaceDockSelector).boundingBox();expect(actions.y+actions.height).toBeLessThanOrEqual(nav.y);
      }
    }
    await captureScreenshot(page,{path:'test-results/today-bounded-editor-mobile.png'});
  } finally {
    const note=await api(request,'GET','/day-notes/'+date);await api(request,'PUT','/day-notes/'+date,{text:'',body:''},{'If-Match':note.headers().etag});
  }
});

for(const locale of ['en-GB','nb-NO'])test(`Today layout aligns its sidebar and grouped date controls in ${locale}`,async({browser,request})=>{
  const original=await(await api(request,'GET','/me')).json();
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true,locale}),page=await context.newPage();
  let running;
  try{
    if(original.locale!==null){const profile=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:original.zoneId,locale:null},{'If-Match':profile.headers().etag});}
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);await page.locator('#login-form button[type=submit]').click();
    await expect(page.locator('#today-note-editor')).toBeVisible();await expect(page.locator('html')).toHaveAttribute('lang',locale);
    // The journal appears before the initial account snapshot finishes docking
    // the work-session card. Measure layout after that publication settles.
    await expect(page.locator('#status')).toHaveAttribute('data-state','ready');await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');
    await page.locator('#today-date').fill('2026-10-26');
    await expect(page.locator('#today-date')).toHaveAccessibleName(locale==='nb-NO'?'Dato':'Date');await expect(page.locator('label[for="today-date"]')).toHaveClass(/visually-hidden/);
    const navigator=page.getByRole('toolbar',{name:locale==='nb-NO'?'Datonavigasjon':'Date navigation',exact:true});
    await expect(navigator.getByRole('group')).toHaveCount(1);await expect(navigator.getByRole('button')).toHaveCount(3);await expect(navigator.locator('.btn-group')).toHaveCount(1);
    const originalBrand=await page.evaluate(()=>document.documentElement.style.getPropertyValue('--font-brand'));
    const check=async(width,font,heading=null,brand=originalBrand)=>{
      if(page.viewportSize()?.width!==width||page.viewportSize()?.height!==1000)await page.setViewportSize({width,height:1000});
      await page.evaluate(({font,heading,brand})=>{const root=document.documentElement;root.style.setProperty('--font-base',font+'px');heading?root.style.setProperty('--font-section-heading',heading):root.style.removeProperty('--font-section-heading');brand?root.style.setProperty('--font-brand',brand):root.style.removeProperty('--font-brand');scrollTo(0,0);},{font,heading,brand});
      await expect(page.locator('#today-main-actions-toggle-right-sidebar')).toHaveAttribute('aria-expanded',String(width>1050));
      const gap=parseFloat(await resolvedLength(page,'--workspace-content-gap'));
      // Font-only changes must redock both columns without a synthetic resize.
      await expect.poll(()=>page.evaluate(({gap,dockSelector})=>{const editor=document.querySelector('#today-note-editor').getBoundingClientRect(),dock=document.querySelector(dockSelector).getBoundingClientRect();return Math.abs(dock.top-editor.bottom-gap);},{gap,dockSelector:workspaceDockSelector})).toBeLessThan(2);
      const geometry=await page.evaluate(dockSelector=>{
        const rect=selector=>document.querySelector(selector).getBoundingClientRect(),toolbar=rect('.today-toolbar'),controls=rect('.today-date-navigation'),title=rect('#today-title'),sidebar=rect('.today-sidebar'),editor=rect('#today-note-editor'),dock=rect(dockSelector),main=rect('.today-main'),session=rect('#work-session');
        return {sidebarTop:sidebar.top,sidebarBottom:sidebar.bottom,headerTop:toolbar.top,headerBottom:toolbar.bottom,headerRight:toolbar.right,actionsLeft:rect('#today-main-actions').left,contentsTop:rect('#today-contents').top,editorTop:editor.top,sidebarRight:sidebar.right,controlsRight:controls.right,controlsTop:controls.top,controlsCenter:controls.top+controls.height/2,titleTop:title.top,titleBottom:title.bottom,titleCenterY:title.top+title.height/2,titleCenterX:title.left+title.width/2,mainLeft:main.left,mainRight:main.right,editorBottom:editor.bottom,dockTop:dock.top,sessionBottom:session.bottom,columnGap:parseFloat(getComputedStyle(document.querySelector('.today-toolbar')).columnGap),overflow:document.documentElement.scrollWidth>innerWidth};
      },workspaceDockSelector);
      expect(geometry.overflow,`${locale}: viewport ${width}px, base font ${font}px, heading ${heading||'default'}, brand ${brand||'default'}`).toBe(false);
      if(width>650){
        expect(Math.abs(geometry.titleCenterX-(geometry.controlsRight+geometry.actionsLeft)/2)).toBeLessThan(2);
        if(width>767)expect(Math.abs(geometry.titleCenterY-geometry.controlsCenter)).toBeLessThan(2);
        else expect(Math.abs(geometry.titleTop-geometry.controlsTop)).toBeLessThan(2);
      }else{
        expect(geometry.titleBottom).toBeLessThanOrEqual(geometry.controlsTop+1);
        expect(Math.abs(geometry.titleCenterX-(geometry.mainLeft+geometry.mainRight)/2)).toBeLessThan(2);
        await expect(navigator).toBeHidden();
        await expect(page.locator('#today-date')).toHaveAttribute('type','date');
        await expect(page.locator('label[for="today-date"]')).not.toHaveClass(/visually-hidden/);
        expect((await page.locator('#today-date').boundingBox()).height).toBeGreaterThanOrEqual(44);
        for(const selector of ['#today-date-choose','.today-month','#today-contents','.today-main-actions .sidebar-actions'])await expect(page.locator(selector)).toBeHidden();
        const label=await page.locator('#today-day-label').boundingBox();
        expect(label.y).toBeGreaterThan(geometry.controlsTop);
        expect(label.y+label.height).toBeLessThanOrEqual(geometry.editorTop);
        for(const id of ['today-sessions','today-events','today-planned-tasks'])await expect(page.locator('#'+id).locator('..')).toBeVisible();
      }
      if(width>1050){
        expect(Math.abs(geometry.sidebarTop-geometry.editorTop)).toBeLessThan(1);
        expect(Math.abs(geometry.contentsTop-geometry.headerTop)).toBeLessThan(1);
        expect(geometry.sidebarRight).toBeLessThanOrEqual(geometry.mainLeft);
        expect(geometry.sidebarTop).toBeGreaterThanOrEqual(geometry.headerBottom);
        expect(geometry.headerTop-geometry.sessionBottom).toBeGreaterThanOrEqual(0);expect(geometry.headerTop-geometry.sessionBottom).toBeLessThanOrEqual(gap+1);
        expect(geometry.sidebarBottom).toBeLessThanOrEqual(geometry.dockTop-gap+1);
      }else expect(geometry.sidebarTop).toBeGreaterThanOrEqual(geometry.editorBottom);
      for(const button of await navigator.getByRole('button').all()){const box=await button.boundingBox();expect(box.width).toBeGreaterThanOrEqual(24);expect(box.height).toBeGreaterThanOrEqual(24);}
    };
    for(const [width,font,heading,brand]of [[1440,14],[1146,14],[1051,14],[1050,14],[768,18],[651,14],[650,14],[440,14],[390,14],[320,14],[1051,14],[1051,14,'3rem'],[320,20],[320,20,null,'2rem']])await check(width,font,heading,brand);
    running=await(await api(request,'POST','/work-sessions',{summary:'Running session with a deliberately long title to exercise wrapping',start:new Date(Date.now()-3600000).toISOString(),end:null})).json();
    await page.locator('#refresh').click();await expect(page.locator('#session-stop')).toBeVisible();
    for(const [width,font]of [[1440,14],[1051,20],[320,20],[390,14]])await check(width,font);
    await captureScreenshot(page,{path:`test-results/today-layout-${locale}-mobile.png`});
    await check(1440,14);await captureScreenshot(page,{path:`test-results/today-layout-${locale}-desktop.png`});
    expect((await new AxeBuilder({page}).include('#today-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
  }finally{
    await context.close();
    if(running){const current=await api(request,'GET','/work-sessions/'+running.id);await api(request,'DELETE','/work-sessions/'+running.id,undefined,{'If-Match':current.headers().etag});}
    const current=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:original.zoneId,locale:original.locale??null},{'If-Match':current.headers().etag});
  }
});
