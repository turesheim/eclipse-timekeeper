import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {instantNanos} from '../src/model.js';

const token=process.env.TIMEKEEPER_WEB_TOKEN,readToken=process.env.TIMEKEEPER_WEB_READ_TOKEN;
async function api(request,method,path,data,headers={}) {
  const response=await request.fetch(`/api/v1${path}`,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
  expect(response.ok(),`${method} ${path}: ${response.status()}`).toBeTruthy();return response;
}
async function read(request,collection,id) {return (await api(request,'GET',`/${collection}/${id}`)).json();}
async function put(request,collection,value,changes) {
  const response=await api(request,'GET',`/${collection}/${value.id}`);
  return (await api(request,'PUT',`/${collection}/${value.id}`,{summary:value.summary,start:value.start,end:value.end,...changes},{'If-Match':response.headers().etag})).json();
}
async function save(page) {await page.locator('#save').click();await expect(page.locator('#editor')).not.toBeVisible();await expect(page.locator('#status')).toHaveText('Up to date');}
async function calendar(page,credential=token,date='2025-10-20') {
  await page.goto('/web/');await page.getByLabel('Access token').fill(credential);await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.locator('#status')).toHaveText('Up to date');await page.getByRole('button',{name:'Calendar',exact:true}).click();
  await page.getByLabel('Calendar date',{exact:true}).fill(date);
  await expect(page.locator('#calendar-zone')).toContainText(date);await expect(page.locator('.calendar-day')).toHaveCount(7);
}
function bar(page,id,date=null) {return page.locator(`.calendar-session:not(.preview)[data-session-id="${id}"]${date ? `[data-date="${date}"]` : ''}`).first();}
async function fixture(request) {
  const prefix='Calendar '+randomUUID(),profileResponse=await api(request,'GET','/me'),profile=await profileResponse.json();
  await api(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profileResponse.headers().etag});
  const project=await (await api(request,'POST','/projects',{name:prefix+' project'})).json();
  const issue=String(parseInt(randomUUID().slice(0,8),16));
  const url=`https://github.com/example/calendar/issues/${issue}`;
  const task=await (await api(request,'POST','/tasks',{summary:prefix+' task',projectId:project.id,parentTaskId:null,url,externalReferences:[{providerId:'github',repositoryId:'https://github.com/example/calendar',externalId:issue,externalUrl:url}]})).json();
  const empty=await (await api(request,'POST','/tasks',{summary:prefix+' unrecorded',projectId:project.id,parentTaskId:null,url:null})).json();
  const session=await (await api(request,'POST','/work-sessions',{summary:prefix+' session',start:'2025-10-20T06:03:00.123456789Z',end:'2025-10-20T14:03:00.987654321Z'})).json();
  const activities=[];
  for(const [start,end,summary] of [['2025-10-20T06:10:00.123456789Z','2025-10-20T07:00:00Z','first interval'],['2025-10-20T07:00:00Z','2025-10-20T08:00:00.987654321Z','second interval']])
    activities.push(await (await api(request,'POST','/activities',{taskId:task.id,summary:prefix+' '+summary,start,end,labelIds:[],workSessionId:session.id})).json());
  return {prefix,profile,project,task,empty,session,activities,url,issue,
    async close() {
      const all=(await (await api(request,'GET','/activities?from=1900-01-01T00:00:00Z&to=2200-01-01T00:00:00Z&limit=500')).json()).items;
      const sessions=(await (await api(request,'GET','/work-sessions?limit=500')).json()).items.filter(value=>value.summary.startsWith(prefix));
      for(const [collection,values] of [['activities',all.filter(value=>value.summary.startsWith(prefix))],['work-sessions',sessions],['tasks',[empty,task]],['projects',[project]]])
        for(const value of values) {const current=await api(request,'GET',`/${collection}/${value.id}`);await api(request,'DELETE',`/${collection}/${value.id}`,undefined,{'If-Match':current.headers().etag});}
      const current=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:profile.zoneId},{'If-Match':current.headers().etag});
    },
  };
}
async function position(page,element,edge=null) {
  await page.locator('.calendar-scroll').evaluate((scroll,{id,edge})=>{
    const card=[...scroll.querySelectorAll('.calendar-session:not(.preview)')].find(value=>value.dataset.sessionId===id);
    scroll.scrollTop=parseFloat(card.style.top)+(edge==='end' ? parseFloat(card.style.height)-300 : -130);
    window.scrollTo(0,scroll.getBoundingClientRect().top+window.scrollY-80);
  },{id:await element.getAttribute('data-session-id'),edge});
  const rect=await element.boundingBox();expect(rect).toBeTruthy();return rect;
}
async function dragSession(page,id,targetDate,deltaY=0) {
  const source=bar(page,id),rect=await position(page,source),target=await page.locator(`.calendar-day[data-date="${targetDate}"]`).boundingBox();
  await page.mouse.move(rect.x+rect.width/2,rect.y+30);await page.mouse.down();
  await page.mouse.move(target.x+target.width*.13,rect.y+30+deltaY,{steps:8});await page.mouse.up();
  await expect(page.locator('#editor')).not.toBeVisible();await expect(page.locator('#status')).toHaveText('Up to date');
}
async function resize(page,id,edge,deltaY) {
  const handle=bar(page,id).locator(`[data-edge="${edge}"]`),rect=await position(page,handle,edge);
  await page.mouse.move(rect.x+rect.width/2,rect.y+rect.height/2);await page.mouse.down();
  await page.mouse.move(rect.x+rect.width/2,rect.y+rect.height/2+deltaY,{steps:5});await page.mouse.up();
  await expect(page.locator('#editor')).not.toBeVisible();await expect(page.locator('#status')).toHaveText('Up to date');
}
async function unchangedActivities(request,f) {for(const value of f.activities) expect(await read(request,'activities',value.id)).toEqual(value);}

test('calendar shows seven days, task links and activity lists beside multi-day work sessions',async({page,request})=>{
  const f=await fixture(request);
  try {
    const long=await (await api(request,'POST','/work-sessions',{summary:f.prefix+' overnight',start:'2025-10-25T21:30:00.123456789Z',end:'2025-10-27T00:30:00.987654321Z'})).json();
    await calendar(page);
    await expect(page.locator('[data-tab=calendar]')).toHaveAttribute('aria-current','page');
    await expect(bar(page,f.session.id)).toBeAttached();
    await expect(page.locator('.calendar-task').getByRole('link',{name:`#${f.issue}: ${f.task.summary}`,exact:true})).toHaveAttribute('href',f.url);
    for(const activity of f.activities) await expect(page.locator('.calendar-task').getByRole('button',{name:`Edit activity ${activity.summary}`,exact:true})).toBeAttached();
    await expect(page.locator(`.calendar-session[data-session-id="${long.id}"]`)).toHaveCount(2);
    await expect(page.locator('#calendar-dst')).toBeVisible();
    expect(await page.locator('.calendar-day[data-date="2025-10-26"] .unavailable').count()).toBe(0);
    expect(await page.locator('.calendar-day[data-date="2025-10-20"] .unavailable').count()).toBe(4);
    await page.locator('#calendar-unrecorded summary').click();await expect(page.locator('#calendar-unrecorded').getByText(f.empty.summary,{exact:true})).toBeVisible();
    await page.screenshot({path:'test-results/calendar-desktop.png',fullPage:true});
    await page.setViewportSize({width:390,height:844});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
    expect(await page.locator('.calendar-scroll').evaluate(value=>value.scrollWidth>value.clientWidth)).toBeTruthy();
    await page.screenshot({path:'test-results/calendar-mobile.png',fullPage:true});
    await page.locator('#calendar-next').click();await expect(page.locator('#calendar-zone')).toContainText('2025-10-27');
    await expect(page.locator(`.calendar-session[data-session-id="${long.id}"]`)).toHaveCount(1);
    expect(await read(request,'work-sessions',long.id)).toEqual(long);
  } finally {await f.close();}
});

test('calendar pointer moves and both resize handles preserve nanoseconds and activity timers',async({page,request})=>{
  const f=await fixture(request);
  try {
    await calendar(page);await dragSession(page,f.session.id,'2025-10-21',32);
    await expect.poll(async()=> (await read(request,'work-sessions',f.session.id)).start).toBe('2025-10-21T06:33:00.123456789Z');
    let current=await read(request,'work-sessions',f.session.id);expect(current.end).toBe('2025-10-21T14:33:00.987654321Z');expect(current.version).toBe(f.session.version+1);
    await expect(bar(page,f.session.id)).toHaveAttribute('data-version',String(current.version));
    await resize(page,f.session.id,'start',16);
    await expect.poll(async()=> (await read(request,'work-sessions',f.session.id)).start).toBe('2025-10-21T06:48:00.123456789Z');
    current=await read(request,'work-sessions',f.session.id);await expect(bar(page,f.session.id)).toHaveAttribute('data-version',String(current.version));
    await resize(page,f.session.id,'end',16);
    await expect.poll(async()=> (await read(request,'work-sessions',f.session.id)).end).toBe('2025-10-21T14:48:00.987654321Z');
    current=await read(request,'work-sessions',f.session.id);await expect(bar(page,f.session.id)).toHaveAttribute('data-version',String(current.version));
    await bar(page,f.session.id).focus();await bar(page,f.session.id).press('ArrowRight');
    await expect.poll(async()=> (await read(request,'work-sessions',f.session.id)).start).toBe('2025-10-22T06:48:00.123456789Z');
    current=await read(request,'work-sessions',f.session.id);expect(current.end).toBe('2025-10-22T14:48:00.987654321Z');
    expect(instantNanos(current.end)-instantNanos(current.start)).toBe(instantNanos(f.session.end)-instantNanos(f.session.start));
    await unchangedActivities(request,f);
    const report=await (await api(request,'GET',`/work-sessions/${f.session.id}/report`)).json();expect(report.time.outsideSession.sort()).toEqual(f.activities.map(value=>value.id).sort());
  } finally {await f.close();}
});

test('calendar copy and paste create only a new session and clear the clipboard on sign-out',async({page,request})=>{
  const f=await fixture(request);
  try {
    await calendar(page);await bar(page,f.session.id).focus();await bar(page,f.session.id).click();
    await page.keyboard.press('ControlOrMeta+c');
    await page.getByRole('button',{name:'Select 2025-10-21',exact:true}).click();await page.keyboard.press('ControlOrMeta+v');
    await expect(page.locator('#editor-title')).toHaveText('Paste work session');
    await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue('2025-10-21T08:03:00.123456789');await save(page);
    let sessions=(await (await api(request,'GET','/work-sessions')).json()).items.filter(value=>value.summary===f.session.summary);expect(sessions).toHaveLength(2);
    const copy=sessions.find(value=>value.id!==f.session.id);expect(copy.start).toBe('2025-10-21T06:03:00.123456789Z');expect(copy.end).toBe('2025-10-21T14:03:00.987654321Z');
    expect((await (await api(request,'GET',`/work-sessions/${copy.id}/report`)).json()).activities).toEqual([]);
    await page.locator('#calendar-copy').click();await page.getByRole('button',{name:'Select 2025-10-22',exact:true}).click();await page.locator('#calendar-paste').click();await save(page);
    sessions=(await (await api(request,'GET','/work-sessions')).json()).items.filter(value=>value.summary===f.session.summary);expect(sessions).toHaveLength(3);
    expect(await read(request,'work-sessions',f.session.id)).toEqual(f.session);await unchangedActivities(request,f);
    await page.getByRole('button',{name:'Sign out',exact:true}).click();
    await page.getByLabel('Access token').fill(token);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
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
    await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue('2025-10-20T08:18:00.123456789');
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
    await page.getByRole('button',{name:'Sign out',exact:true}).click();await calendar(page,readToken);
    await bar(page,f.session.id).focus();await bar(page,f.session.id).press('ArrowDown');
    await expect(page.locator('#editor-error')).toContainText('permission');expect(await read(request,'work-sessions',f.session.id)).toEqual(current);await unchangedActivities(request,f);
    await page.locator('#cancel').click();
  } finally {await f.close();}
});
