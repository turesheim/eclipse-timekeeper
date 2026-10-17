import {openAccountMenu} from './navigation.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {instantNanos,monday,dateInZone,localDateTime} from '../src/model.js';
import {calendarWeek} from '../src/calendar-model.js';

const token=process.env.TIMEKEEPER_WEB_TOKEN,readToken=process.env.TIMEKEEPER_WEB_READ_TOKEN;
async function api(request,method,path,data,headers={}) {
  const response=await request.fetch(`/api/v1${path}`,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
  expect(response.ok(),`${method} ${path}: ${response.status()}`).toBeTruthy();return response;
}
async function read(request,collection,id) {return (await api(request,'GET',`/${collection}/${id}`)).json();}
async function put(request,collection,value,changes) {
  const response=await api(request,'GET',`/${collection}/${value.id}`);
  return (await api(request,'PUT',`/${collection}/${value.id}`,{clientId:value.clientId,title:value.title,summary:value.summary,start:value.start,end:value.end,...changes},{'If-Match':response.headers().etag})).json();
}
async function save(page) {await page.locator('#save').click();await expect(page.locator('#editor')).not.toBeVisible();await expect(page.locator('#status')).toHaveText('Up to date');}
async function calendar(page,credential=token,date='2025-10-20') {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(credential);await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.locator('#status')).toHaveText('Up to date');await page.getByRole('button',{name:'Calendar',exact:true}).click();
  await page.getByLabel('Calendar date',{exact:true}).fill(date);
  await expect(page.locator('#calendar-zone')).toContainText(monday(date));await expect(page.locator('.calendar-day')).toHaveCount(7);
}
function bar(page,id,date=null) {return page.locator(`.calendar-session:not(.preview)[data-session-id="${id}"]${date ? `[data-date="${date}"]` : ''}`).first();}
async function fixture(request) {
  const prefix='Calendar '+randomUUID().slice(0,6),profileResponse=await api(request,'GET','/me'),profile=await profileResponse.json();
  await api(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profileResponse.headers().etag});
  const client=await (await api(request,'POST','/clients',{name:prefix+' client',color:'#ffdd88'})).json();
  const project=await (await api(request,'POST','/projects',{name:prefix+' project',clientId:client.id})).json();
  const issue=String(100+parseInt(randomUUID().slice(0,4),16));
  const url=`https://github.com/example/calendar/issues/${issue}`;
  const task=await (await api(request,'POST','/tasks',{summary:prefix+' task',projectId:project.id,parentTaskId:null,url,externalReferences:[{providerId:'github',repositoryId:'https://github.com/example/calendar',externalId:issue,externalUrl:url}]})).json();
  const empty=await (await api(request,'POST','/tasks',{summary:prefix+' unrecorded',projectId:project.id,parentTaskId:null,url:null})).json();
  const label=await (await api(request,'POST','/labels',{name:prefix+' label',color:'#176653'})).json();
  const session=await (await api(request,'POST','/work-sessions',{labelIds:[label.id],clientId:client.id,title:prefix+' planning',summary:prefix+' session',start:'2025-10-20T06:03:00.123456789Z',end:'2025-10-20T14:03:00.987654321Z'})).json();
  const activities=[];
  for(const [start,end,summary] of [['2025-10-20T06:10:00.123456789Z','2025-10-20T07:00:00Z','first interval'],['2025-10-20T07:00:00Z','2025-10-20T08:00:00.987654321Z','second interval']])
    activities.push(await (await api(request,'POST','/activities',{taskId:task.id,summary:prefix+' '+summary,start,end,labelIds:[],workSessionId:session.id})).json());
  return {prefix,profile,client,project,task,empty,session,activities,url,issue,label,
    async close() {
      const active=await api(request,'GET','/activities/active');
      if(active.status()!==204) {const value=await active.json();if(value.summary.startsWith(prefix)) await api(request,'POST',`/activities/${value.id}/stop`,undefined,{'If-Match':active.headers().etag,'Idempotency-Key':randomUUID()});}
      const all=(await (await api(request,'GET','/activities?from=1900-01-01T00:00:00Z&to=2200-01-01T00:00:00Z&limit=500')).json()).items;
      const sessions=(await (await api(request,'GET','/work-sessions?limit=500')).json()).items.filter(value=>value.summary.startsWith(prefix));
      for(const [collection,values] of [['activities',all.filter(value=>value.summary.startsWith(prefix))],['work-sessions',sessions],['tasks',[empty,task]],['projects',[project]],['labels',[label]],['clients',[client]]])
        for(const value of values) {const current=await api(request,'GET',`/${collection}/${value.id}`);await api(request,'DELETE',`/${collection}/${value.id}`,undefined,{'If-Match':current.headers().etag});}
      const current=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:profile.zoneId},{'If-Match':current.headers().etag});
    },
  };
}
async function position(page,element,edge=null) {
  await page.locator('.calendar-scroll').evaluate((scroll,{id,edge})=>{
    const card=[...scroll.querySelectorAll('.calendar-session:not(.preview)')].find(value=>value.dataset.sessionId===id);
    scroll.scrollTop=parseFloat(card.style.top)+(edge==='end' ? parseFloat(card.style.height)-300 : -130);
    // Bootstrap enables smooth root scrolling; gesture coordinates require the
    // fixture's page positioning to have finished before the pointer is placed.
    window.scrollTo({top:scroll.getBoundingClientRect().top+window.scrollY-80,behavior:'instant'});
  },{id:await element.getAttribute('data-session-id'),edge});
  // Manual mouse/touch coordinates need the same settled layout that locator
  // actions wait for, including fonts and the positioned scroll containers.
  await page.evaluate(async()=>{await document.fonts.ready;await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));});
  // A live activity poll can replace the handle between layout settling and
  // this read. Resolve its locator again until a visible box is available.
  let rect;await expect.poll(async()=>{rect=await element.boundingBox();return rect;}).not.toBeNull();return rect;
}
async function dragSession(page,id,targetDate,deltaY=0) {
  const source=bar(page,id),rect=await position(page,source),target=await page.locator(`.calendar-day[data-date="${targetDate}"]`).boundingBox();
  await page.mouse.move(rect.x+rect.width/2,rect.y+30);await page.mouse.down();
  await page.mouse.move(target.x+target.width*.13,rect.y+30+deltaY,{steps:8});await page.mouse.up();
  await expect(page.locator('#editor')).not.toBeVisible();await expect(page.locator('#status')).toHaveText('Up to date');
}
async function resize(page,id,edge,deltaY) {
  const handle=bar(page,id).locator(`[data-edge="${edge}"]`),rect=await position(page,handle,edge);
  // Resolve the current handle and wait for hover/actionability before pressing:
  // a live render can replace it between positioning and the pointer gesture.
  await handle.hover();await expect(handle).toHaveCSS('opacity','1');await page.mouse.down();
  await page.mouse.move(rect.x+rect.width/2,rect.y+rect.height/2+deltaY,{steps:5});await page.mouse.up();
  await expect(page.locator('#editor')).not.toBeVisible();await expect(page.locator('#status')).toHaveText('Up to date');
}
async function unchangedActivities(request,f) {for(const value of f.activities) expect(await read(request,'activities',value.id)).toEqual(value);}

async function rangePoints(page,from,to) {
  const first=await page.locator('#calendar-date').inputValue(),week=calendarWeek(first,'Europe/Oslo');
  const row=clock=>week.rows.indexOf(clock);
  await page.locator('.calendar-scroll').evaluate((scroll,start)=>{
    scroll.scrollTop=Math.max(0,start*16-130);scroll.scrollLeft=0;
    window.scrollTo({top:scroll.getBoundingClientRect().top+window.scrollY-80,behavior:'instant'});
  },row(from.clock));
  await page.evaluate(async()=>{await document.fonts.ready;await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));});
  await expect(page.locator(`.calendar-day[data-date="${from.date}"]`)).toBeVisible();
  await expect(page.locator(`.calendar-day[data-date="${to.date}"]`)).toBeVisible();
  // A live snapshot can replace all day nodes. Read both rectangles atomically
  // instead of retaining a node handle across separate protocol calls.
  return page.locator('#calendar-grid').evaluate((grid,{from,to,first,last})=>{
    const source=grid.querySelector(`.calendar-day[data-date="${from}"]`).getBoundingClientRect();
    const target=grid.querySelector(`.calendar-day[data-date="${to}"]`).getBoundingClientRect();
    // The anchor uses the slot interior; the endpoint snaps to its nearest
    // boundary. Keep touch rounding away from the preceding anchor slot.
    return {start:{x:source.x+source.width*.6,y:source.y+first*16+8},end:{x:target.x+target.width*.6,y:target.y+last*16+1}};
  },{from:from.date,to:to.date,first:row(from.clock),last:row(to.clock)});
}
async function dragRange(page,from,to) {
  const points=await rangePoints(page,from,to);
  await page.mouse.move(points.start.x,points.start.y);await page.mouse.down();
  await page.mouse.move(points.end.x,points.end.y,{steps:8});
}

test('dragging empty calendar space previews a new session and saves only after review',async({page,request})=>{
  const f=await fixture(request);let creates=0,otherClient;
  try {
    otherClient=await (await api(request,'POST','/clients',{name:f.prefix+' other client',color:'#123456'})).json();
    await page.setViewportSize({width:1440,height:1000});await calendar(page);
    page.on('request',value=>{if(value.method()==='POST' && new URL(value.url()).pathname==='/api/v1/work-sessions') creates++;});
    await dragRange(page,{date:'2025-10-21',clock:'09:00/0'},{date:'2025-10-21',clock:'10:30/0'});
    await expect(page.locator('.calendar-create-preview')).toHaveCount(1);expect(creates).toBe(0);
    await page.screenshot({path:'test-results/calendar-range-preview-desktop.png'});
    await page.mouse.up();await expect(page.locator('#editor')).toBeVisible();
    expect(creates).toBe(0);await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue('2025-10-21T09:00');
    await expect(page.getByLabel('End (local time, blank while running)',{exact:true})).toHaveValue('2025-10-21T10:30');
    await page.screenshot({path:'test-results/calendar-range-review-desktop.png'});
    await page.getByLabel('Title',{exact:true}).fill(f.prefix+' dragged');await page.getByLabel('Client',{exact:true}).selectOption(otherClient.id);
    await page.getByLabel('Summary',{exact:true}).fill(f.prefix+' dragged interval');await save(page);expect(creates).toBe(1);
    const created=(await (await api(request,'GET','/work-sessions?clientId='+otherClient.id)).json()).items.find(value=>value.title===f.prefix+' dragged');
    expect(created.start).toBe('2025-10-21T07:00:00Z');expect(created.end).toBe('2025-10-21T08:30:00Z');
    expect((await (await api(request,'GET',`/work-sessions/${created.id}/report`)).json()).activities).toEqual([]);
    await unchangedActivities(request,f);
    // Selecting an older session must not replace the last client actually used.
    await bar(page,f.session.id).locator('.calendar-session-select').click();
    await dragRange(page,{date:'2025-10-22',clock:'10:30/0'},{date:'2025-10-22',clock:'09:00/0'});await page.mouse.up();
    await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue('2025-10-22T09:00');
    await expect(page.getByLabel('End (local time, blank while running)',{exact:true})).toHaveValue('2025-10-22T10:30');
    await expect(page.getByLabel('Client',{exact:true})).toHaveValue(otherClient.id);
    await page.getByLabel('Client',{exact:true}).selectOption(f.client.id);await page.locator('#cancel').click();expect(creates).toBe(1);
    await dragRange(page,{date:'2025-10-22',clock:'11:00/0'},{date:'2025-10-22',clock:'11:30/0'});await page.mouse.up();
    await expect(page.getByLabel('Client',{exact:true})).toHaveValue(otherClient.id);await page.locator('#cancel').click();
    await dragRange(page,{date:'2025-10-23',clock:'09:00/0'},{date:'2025-10-23',clock:'10:30/0'});
    await page.keyboard.press('Escape');await page.mouse.up();
    await expect(page.locator('.calendar-create-preview')).toHaveCount(0);await expect(page.locator('#editor')).not.toBeVisible();expect(creates).toBe(1);
  } finally {await f.close();if(otherClient) {const current=await api(request,'GET','/clients/'+otherClient.id);await api(request,'DELETE','/clients/'+otherClient.id,undefined,{'If-Match':current.headers().etag});}}
});

test('drag-created intervals distinguish repeated local times and reject shaded endpoints',async({page,request})=>{
  const f=await fixture(request);
  try {
    await page.setViewportSize({width:1440,height:1000});await calendar(page);
    await dragRange(page,{date:'2025-10-26',clock:'02:15/0'},{date:'2025-10-26',clock:'02:15/1'});await page.mouse.up();
    await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue('2025-10-26T02:15');
    await expect(page.getByLabel('End (local time, blank while running)',{exact:true})).toHaveValue('2025-10-26T02:15');
    await expect(page.getByLabel('Start time occurrence',{exact:true})).toHaveValue('+02:00');
    await expect(page.getByLabel('End time occurrence',{exact:true})).toHaveValue('+01:00');
    await page.getByLabel('Title',{exact:true}).fill(f.prefix+' repeated hour');await page.getByLabel('Client',{exact:true}).selectOption(f.client.id);
    await page.getByLabel('Summary',{exact:true}).fill(f.prefix+' repeated');await save(page);
    const created=(await (await api(request,'GET','/work-sessions?clientId='+f.client.id)).json()).items.find(value=>value.title===f.prefix+' repeated hour');
    expect(instantNanos(created.end)-instantNanos(created.start)).toBe(3600000000000n);
    await dragRange(page,{date:'2025-10-26',clock:'01:45/0'},{date:'2025-10-20',clock:'02:15/1'});await page.mouse.up();
    await expect(page.locator('#editor')).not.toBeVisible();await expect(page.locator('#calendar-message')).toContainText('does not occur');
    await unchangedActivities(request,f);
  } finally {await f.close();}
});

test('touch range selection is explicit and leaves normal calendar scrolling available',async({browser,request,browserName})=>{
  test.skip(browserName!=='chromium','Native touch injection uses Chromium CDP. Other calendar interactions run in every browser.');
  const f=await fixture(request),context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,viewport:{width:390,height:844},isMobile:true,hasTouch:true});
  const page=await context.newPage();
  try {
    await calendar(page);const client=await context.newCDPSession(page);
    const from={date:'2025-10-20',clock:'16:30/0'},to={date:'2025-10-20',clock:'17:00/0'};
    async function touch(points) {
      await client.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...points.start,id:1}]});
      for(let step=1;step<=6;step++) await client.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:points.start.x+(points.end.x-points.start.x)*step/6,y:points.start.y+(points.end.y-points.start.y)*step/6,id:1}]});
      await client.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
    }
    await expect(page.locator('#calendar-range')).toHaveAttribute('aria-pressed','false');
    const points=await rangePoints(page,from,to),before=await page.locator('.calendar-scroll').evaluate(value=>value.scrollTop);
    await touch(points);await expect(page.locator('#editor')).not.toBeVisible();
    await expect.poll(()=>page.locator('.calendar-scroll').evaluate(value=>value.scrollTop)).not.toBe(before);
    await page.getByRole('button',{name:'Select time range',exact:true}).click();await expect(page.locator('#calendar-range')).toHaveAttribute('aria-pressed','true');
    await touch(await rangePoints(page,from,to));await expect(page.locator('#editor')).toBeVisible();
    await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue('2025-10-20T16:30');
    await expect(page.getByLabel('End (local time, blank while running)',{exact:true})).toHaveValue('2025-10-20T17:00');
    await expect(page.locator('#calendar-range')).toHaveAttribute('aria-pressed','false');await page.locator('#cancel').click();
  } finally {await context.close();await f.close();}
});

test('calendar shows seven days, task links and activity lists beside multi-day work sessions',async({page,request})=>{
  const f=await fixture(request);
  try {
    const long=await (await api(request,'POST','/work-sessions',{clientId:f.client.id,title:'Overnight work',summary:f.prefix+' overnight',start:'2025-10-25T21:30:00.123456789Z',end:'2025-10-27T00:30:00.987654321Z'})).json();
    await page.setViewportSize({width:1440,height:1100});
    await calendar(page);
    await expect(page.locator('[data-tab=calendar]')).toHaveAttribute('aria-current','page');
    await expect(bar(page,f.session.id)).toBeAttached();
    await expect(bar(page,f.session.id)).toHaveCSS('background-color','rgb(255, 221, 136)');
    await expect(bar(page,f.session.id)).toHaveCSS('color','rgb(0, 0, 0)');
    await expect(bar(page,f.session.id).locator('.calendar-session-title')).toHaveText(f.session.title);
    await expect(bar(page,f.session.id).locator('.calendar-session-duration')).toHaveText('8h 0m');
    await expect(bar(page,f.session.id)).toHaveCSS('border-radius','6px');
    const handles=bar(page,f.session.id).locator('.calendar-resize');
    await position(page,bar(page,f.session.id));await page.locator('#calendar-today').hover();
    await expect(handles.first()).toHaveCSS('opacity','0');
    await bar(page,f.session.id).hover();await expect(handles.first()).toHaveCSS('opacity','1');
    await page.screenshot({path:'test-results/calendar-session-hover.png',fullPage:true});
    const select=bar(page,f.session.id).locator('.calendar-session-select');
    await select.click();await expect(select).toHaveAttribute('aria-pressed','true');
    // Browsers differ in whether a pointer click retains :focus-visible after
    // a native date input. Explicitly leave focus before checking hidden handles,
    // then enter them through the keyboard rather than relying on that heuristic.
    await page.locator('#calendar-today').focus();await page.locator('#calendar-today').hover();
    await expect(handles.first()).toHaveCSS('opacity','0');
    await select.focus();await page.keyboard.press('Tab');await expect(handles.first()).toBeFocused();
    await expect(handles.first()).toHaveCSS('opacity','1');
    await page.locator('#calendar-today').focus();await expect(handles.first()).toHaveCSS('opacity','0');
    await expect(page.locator('.calendar-task').getByRole('link',{name:`#${f.issue}: ${f.task.summary}`,exact:true})).toHaveAttribute('href',f.url);
    for(const activity of f.activities) await expect(page.locator('.calendar-task').getByRole('button',{name:`Edit activity ${activity.summary}`,exact:true})).toBeAttached();
    await expect(page.locator(`.calendar-session[data-session-id="${long.id}"]`)).toHaveCount(2);
    await expect(page.locator('#calendar-dst')).toBeVisible();
    expect(await page.locator('.calendar-day[data-date="2025-10-26"] .unavailable').count()).toBe(0);
    expect(await page.locator('.calendar-day[data-date="2025-10-20"] .unavailable').count()).toBe(4);
    await expect(page.locator('#calendar-panel')).not.toContainText(f.empty.summary);
    await page.screenshot({path:'test-results/calendar-desktop.png',fullPage:true});
    await page.setViewportSize({width:390,height:844});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
    expect(await page.locator('.calendar-scroll').evaluate(value=>value.scrollWidth>value.clientWidth)).toBeTruthy();
    await page.screenshot({path:'test-results/calendar-mobile.png',fullPage:true});
    await page.locator('[data-tab=tasks]').click();await expect(page.locator('#tasks')).toContainText(f.empty.summary);
    await page.locator('[data-tab=calendar]').click();
    await page.locator('#calendar-next').click();await expect(page.locator('#calendar-zone')).toContainText('2025-10-27');
    await expect(page.locator(`.calendar-session[data-session-id="${long.id}"]`)).toHaveCount(1);
    expect(await read(request,'work-sessions',long.id)).toEqual(long);
  } finally {await f.close();}
});

test('calendar pointer moves and both resize handles preserve nanoseconds and activity timers',async({page,request})=>{
  const f=await fixture(request);
  try {
    const active=await (await api(request,'POST','/activities/start',{taskId:f.task.id,summary:f.prefix+' running timer',labelIds:[],workSessionId:f.session.id,expectedActiveId:null,expectedActiveVersion:null},{'Idempotency-Key':randomUUID()})).json();
    await calendar(page);
    // Count actual openings: checking the final closed state misses a brief flash.
    await page.evaluate(()=>{window.calendarDialogOpens=0;const show=HTMLDialogElement.prototype.showModal;HTMLDialogElement.prototype.showModal=function(){if(this.id==='editor') window.calendarDialogOpens++;return show.call(this);};});
    await dragSession(page,f.session.id,'2025-10-21',32);
    await expect.poll(async()=> (await read(request,'work-sessions',f.session.id)).start).toBe('2025-10-21T06:33:00.123456789Z');
    let current=await read(request,'work-sessions',f.session.id);expect(current.end).toBe('2025-10-21T14:33:00.987654321Z');expect(current.version).toBe(f.session.version+1);
    await expect(bar(page,f.session.id)).toHaveAttribute('data-version',String(current.version));
    await resize(page,f.session.id,'start',16);
    await expect.poll(async()=> (await read(request,'work-sessions',f.session.id)).start).toBe('2025-10-21T06:48:00.123456789Z');
    current=await read(request,'work-sessions',f.session.id);expect(current.end).toBe('2025-10-21T14:33:00.987654321Z');
    await expect(bar(page,f.session.id)).toHaveAttribute('data-version',String(current.version));
    await resize(page,f.session.id,'end',16);
    await expect.poll(async()=> (await read(request,'work-sessions',f.session.id)).end).toBe('2025-10-21T14:48:00.987654321Z');
    current=await read(request,'work-sessions',f.session.id);expect(current.start).toBe('2025-10-21T06:48:00.123456789Z');
    await expect(bar(page,f.session.id)).toHaveAttribute('data-version',String(current.version));
    await bar(page,f.session.id).locator('.calendar-session-select').focus();await page.keyboard.press('ArrowRight');
    await expect.poll(async()=> (await read(request,'work-sessions',f.session.id)).start).toBe('2025-10-22T06:48:00.123456789Z');
    current=await read(request,'work-sessions',f.session.id);expect(current.end).toBe('2025-10-22T14:48:00.987654321Z');
    await expect(bar(page,f.session.id).locator('.calendar-session-select')).toBeFocused();
    expect(instantNanos(current.end)-instantNanos(current.start)).toBe(instantNanos(f.session.end)-instantNanos(f.session.start));
    await unchangedActivities(request,f);
    expect(await (await api(request,'GET','/activities/active')).json()).toEqual(active);
    expect(await page.evaluate(()=>window.calendarDialogOpens)).toBe(0);
    const report=await (await api(request,'GET',`/work-sessions/${f.session.id}/report`)).json();expect(report.activities).toEqual([]);expect(report.time.outsideSession).toEqual([]);
  } finally {await f.close();}
});

test('calendar lost paste replies cannot create duplicates and future pastes remain rejected',async({page,request})=>{
  const f=await fixture(request);let creates=0;
  try {
    await calendar(page);await bar(page,f.session.id).click();await page.locator('#calendar-copy').click();
    await page.getByRole('button',{name:'Select 2025-10-21',exact:true}).click();await page.locator('#calendar-paste').click();
    await page.route('**/api/v1/work-sessions',async route=>{
      if(route.request().method()==='POST') {creates++;await route.fetch();await route.abort('connectionfailed');}else await route.continue();
    });
    await page.locator('#save').click();await expect(page.locator('#editor-notice')).toContainText('outcome is unknown');await expect(page.locator('#save')).toBeDisabled();
    expect(creates).toBe(1);
    let copies=(await (await api(request,'GET','/work-sessions')).json()).items.filter(value=>value.summary===f.session.summary);expect(copies).toHaveLength(2);
    for(const copy of copies.filter(value=>value.id!==f.session.id)) expect((await (await api(request,'GET',`/work-sessions/${copy.id}/report`)).json()).activities).toEqual([]);
    await page.locator('#cancel').click();await page.unroute('**/api/v1/work-sessions');
    const future=monday('2099-01-15');await page.getByLabel('Calendar date',{exact:true}).fill(future);await expect(page.locator('#calendar-zone')).toContainText(future);
    await page.getByRole('button',{name:`Select ${future}`,exact:true}).click();await page.locator('#calendar-paste').click();await page.locator('#save').click();
    await expect(page.locator('#editor-error')).toContainText('Check');await expect(page.locator('#editor')).toBeVisible();
    copies=(await (await api(request,'GET','/work-sessions')).json()).items.filter(value=>value.summary===f.session.summary);expect(copies).toHaveLength(2);
    expect(await read(request,'work-sessions',f.session.id)).toEqual(f.session);await unchangedActivities(request,f);await page.locator('#cancel').click();
  } finally {await f.close();}
});

test('calendar copying a running session leaves it running and lets the copy receive an end',async({page,request})=>{
  const f=await fixture(request);let running;
  try {
    running=await (await api(request,'POST','/work-sessions/start',{clientId:f.client.id,title:'Running work',summary:f.prefix+' running session'},{'Idempotency-Key':randomUUID()})).json();
    const today=dateInZone(running.start,'Europe/Oslo');await calendar(page,token,today);
    await expect(bar(page,running.id)).toBeAttached();await bar(page,running.id).focus();await bar(page,running.id).press('ControlOrMeta+c');
    await page.getByRole('button',{name:`Select ${today}`,exact:true}).click();await page.locator('#calendar-paste').click();
    await expect(page.getByLabel('End (local time, blank while running)',{exact:true})).toHaveValue('');
    await page.locator('#save').click();await expect(page.locator('#editor-error')).toContainText('conflicts');
    expect(await (await api(request,'GET','/work-sessions/active')).json()).toEqual(running);
    // Whole-second native input must be after the preserved fractional start.
    await expect.poll(()=>Date.now()-Date.parse(running.start)).toBeGreaterThan(1100);
    // WebKit normalizes zero seconds away; fill expects the canonical native value.
    const end=localDateTime(new Date().toISOString(),'Europe/Oslo').slice(0,19).replace(/:00$/,'');
    await page.getByLabel('End (local time, blank while running)',{exact:true}).fill(end);await save(page);
    expect(await (await api(request,'GET','/work-sessions/active')).json()).toEqual(running);
    const copies=(await (await api(request,'GET','/work-sessions')).json()).items.filter(value=>value.summary===running.summary);expect(copies).toHaveLength(2);
    expect(copies.filter(value=>value.end===null)).toHaveLength(1);await unchangedActivities(request,f);
  } finally {
    if(running) {const current=await api(request,'GET',`/work-sessions/${running.id}`);await api(request,'POST',`/work-sessions/${running.id}/stop`,undefined,{'If-Match':current.headers().etag,'Idempotency-Key':randomUUID()});}
    await f.close();
  }
});

test('calendar touch gestures move a work session between days on a narrow screen',async({browser,request,browserName})=>{
  test.skip(browserName!=='chromium','Native touch injection uses Chromium CDP. Other calendar interactions run in every browser.');
  const f=await fixture(request),context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,viewport:{width:390,height:844},isMobile:true,hasTouch:true});
  const page=await context.newPage();
  try {
    await calendar(page);const source=bar(page,f.session.id),rect=await position(page,source),target=await page.locator('.calendar-day[data-date="2025-10-21"]').boundingBox();
    const client=await context.newCDPSession(page),start={x:rect.x+rect.width/2,y:rect.y+30},end={x:target.x+target.width*.13,y:rect.y+46};
    await client.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...start,id:1}]});
    for(let step=1;step<=6;step++) await client.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:start.x+(end.x-start.x)*step/6,y:start.y+(end.y-start.y)*step/6,id:1}]});
    await client.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
    await expect.poll(async()=> (await read(request,'work-sessions',f.session.id)).start).toBe('2025-10-21T06:18:00.123456789Z');await unchangedActivities(request,f);
  } finally {await context.close();await f.close();}
});

test('calendar copy and paste create only a new session and clear the clipboard on sign-out',async({page,request})=>{
  const f=await fixture(request);
  try {
    await calendar(page);await bar(page,f.session.id).click();
    const select=bar(page,f.session.id).locator('.calendar-session-select');await select.focus();await expect(select).toBeFocused();
    await page.keyboard.press('ControlOrMeta+c');
    await expect(page.locator('#calendar-message')).toHaveText('Work session copied.');
    const targetDay=page.getByRole('button',{name:'Select 2025-10-21',exact:true});
    await targetDay.click();await expect(targetDay).toBeFocused();await page.keyboard.press('ControlOrMeta+v');
    await expect(page.locator('#editor-title')).toHaveText('Paste work session');
    await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue('2025-10-21T08:03');await save(page);
    let sessions=(await (await api(request,'GET','/work-sessions')).json()).items.filter(value=>value.summary===f.session.summary);expect(sessions).toHaveLength(2);
    const copy=sessions.find(value=>value.id!==f.session.id);expect(copy.labelIds).toEqual([f.label.id]);expect(copy.clientId).toBe(f.client.id);expect(copy.title).toBe(f.session.title);expect(copy.start).toBe('2025-10-21T06:03:00.123456789Z');expect(copy.end).toBe('2025-10-21T14:03:00.987654321Z');
    expect((await (await api(request,'GET',`/work-sessions/${copy.id}/report`)).json()).activities).toEqual([]);
    await page.locator('#calendar-copy').click();await page.getByRole('button',{name:'Select 2025-10-22',exact:true}).click();await page.locator('#calendar-paste').click();await save(page);
    sessions=(await (await api(request,'GET','/work-sessions')).json()).items.filter(value=>value.summary===f.session.summary);expect(sessions).toHaveLength(3);
    expect(await read(request,'work-sessions',f.session.id)).toEqual(f.session);await unchangedActivities(request,f);
    await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();
    await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(token);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await page.getByRole('button',{name:'Calendar',exact:true}).click();await expect(page.locator('#calendar-paste')).toBeDisabled();
  } finally {await f.close();}
});

test('calendar stale gestures keep the proposal and never adopt a newer snapshot for the write',async({page,request})=>{
  const f=await fixture(request);let peer;
  try {
    await calendar(page);
    let once=true;
    await page.route(`**/api/v1/work-sessions/${f.session.id}`,async route=>{
      if(once && route.request().method()==='GET') {once=false;peer=await put(request,'work-sessions',f.session,{summary:f.prefix+' peer change'});}
      await route.continue();
    });
    await bar(page,f.session.id).focus();await bar(page,f.session.id).press('ArrowDown');
    await expect(page.locator('#editor-error')).toContainText('changed');await expect(page.locator('#save')).toBeDisabled();
    await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue('2025-10-20T08:18');
    expect(await read(request,'work-sessions',f.session.id)).toEqual(peer);await unchangedActivities(request,f);
    await page.locator('#cancel').click();await page.unroute(`**/api/v1/work-sessions/${f.session.id}`);
    await page.getByRole('button',{name:'Refresh',exact:true}).click();await expect(bar(page,f.session.id)).toHaveAttribute('title',new RegExp(peer.summary));
    once=true;
    await page.route(`**/api/v1/work-sessions/${f.session.id}`,async route=>{
      if(once && route.request().method()==='PUT') {once=false;peer=await put(request,'work-sessions',peer,{summary:f.prefix+' later peer change'});}
      await route.continue();
    });
    await bar(page,f.session.id).focus();await bar(page,f.session.id).press('ArrowDown');
    await expect(page.locator('#editor-error')).toContainText('changed');
    expect(await read(request,'work-sessions',f.session.id)).toEqual(peer);await unchangedActivities(request,f);
    await page.locator('#cancel').click();
  } finally {await f.close();}
});

test('calendar committed-but-lost moves require review and read-only moves leave records unchanged',async({page,request})=>{
  const f=await fixture(request);let writes=0;
  try {
    await calendar(page);
    await page.route(`**/api/v1/work-sessions/${f.session.id}`,async route=>{
      if(route.request().method()==='PUT') {writes++;await route.fetch();await route.abort('connectionfailed');}else await route.continue();
    });
    await bar(page,f.session.id).focus();await bar(page,f.session.id).press('ArrowDown');
    await expect(page.locator('#editor-notice')).toContainText('outcome is unknown');await expect(page.locator('#save')).toBeDisabled();
    let current=await read(request,'work-sessions',f.session.id);expect(current.start).toBe('2025-10-20T06:18:00.123456789Z');expect(current.version).toBe(f.session.version+1);expect(writes).toBe(1);
    await unchangedActivities(request,f);await page.locator('#cancel').click();await page.unroute(`**/api/v1/work-sessions/${f.session.id}`);
    await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login')).toBeVisible();await calendar(page,readToken);
    await bar(page,f.session.id).focus();await bar(page,f.session.id).press('ArrowDown');
    await expect(page.locator('#editor-error')).toContainText('permission');expect(await read(request,'work-sessions',f.session.id)).toEqual(current);await unchangedActivities(request,f);
    await page.locator('#cancel').click();
  } finally {await f.close();}
});
