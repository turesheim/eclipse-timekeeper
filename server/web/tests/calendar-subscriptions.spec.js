import {setTheme} from './navigation.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';

const fixture=()=>process.env.TIMEKEEPER_WEB_CALENDAR_FIXTURE;
const base=()=>process.env.TIMEKEEPER_WEB_URL;
const activitiesPath='/activities?from=2026-05-03T00:00:00Z&to=2026-05-12T00:00:00Z';
const reportPath='/reports/activities?from=2026-05-03T00:00:00Z&to=2026-05-12T00:00:00Z&zoneId=Europe%2FOslo';
const ics=(events)=>'BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Timekeeper//Browser calendar test//EN\r\n'+events.map(value=>'BEGIN:VEVENT\r\nDTSTAMP:20260101T000000Z\r\n'+value.replaceAll('\n','\r\n')+'\r\nEND:VEVENT\r\n').join('')+'END:VCALENDAR\r\n';
const meeting='UID:browser-meeting\nDTSTART:20260504T083000Z\nDTEND:20260504T093000Z\nSUMMARY:Planned meeting\nDESCRIPTION:Discuss the release\\nReview open items\nLOCATION:Meeting room';
test.beforeAll(async({request})=>{
  expect(fixture(),'Calendar browser tests require the loopback iCalendar fixture').toBeTruthy();
  const headers={Origin:new URL(base()).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.org'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function fresh(request){
  const username='calendar-'+randomUUID().slice(0,8),password='Independent calendar subscription password';
  const result=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});expect(result.status()).toBe(201);return {username,password};
}
async function ready(page){await expect(page.locator('#workspace')).toBeVisible();await expect(page.locator('#status')).toHaveText((await page.locator('html').getAttribute('lang'))==='nb-NO'?'Oppdatert':'Up to date');}
async function login(page,user,navigate=true){if(navigate)await page.goto('/web/');await expect(page.locator('#login-form button[type=submit]')).toBeEnabled();await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(user.username);await page.locator('#password').fill(user.password);await page.locator('#login-form button[type=submit]').click();await ready(page);}
async function apiFor(page){
  const request=browserRequest(page),identity=await(await request.get('/api/v1/auth/session')).json();
  return async(method,path,data,extra={})=>{const reply=await request.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...extra}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;};
}
async function provider(request,events=[meeting],options={}){const reply=await request.post(fixture()+'/feeds',{data:{body:ics(events),...options}});expect(reply.status()).toBe(201);return reply.json();}
async function setup(browser,request){
  const user=await fresh(request),context=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB',timezoneId:'America/Los_Angeles'}),page=await context.newPage();await login(page,user);let api=await apiFor(page);
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag});
  const client=await(await api('POST','/clients',{name:'Meeting client',color:'#176653'})).json();await page.reload();await ready(page);api=await apiFor(page);
  return {user,context,page,api,client};
}
async function calendar(page){await page.locator('[data-tab=calendar]').click();await page.locator('#calendar-date').fill('2026-05-04');await expect(page.locator('#calendar-zone')).toContainText('2026-05-04');await expect(page.locator('#calendar-subscription-add')).toBeEnabled();}
async function subscribe(page,client,feed,name='Team planning'){
  await page.locator('#calendar-subscription-add').click();const dialog=page.locator('#calendar-subscription-editor');await dialog.getByLabel('Name',{exact:true}).fill(name);await dialog.locator('select[name=clientId]').selectOption(client.id);await dialog.getByLabel('Calendar feed URL',{exact:true}).fill(feed.url);
  const response=page.waitForResponse(reply=>reply.request().method()==='POST'&&new URL(reply.url()).pathname==='/api/v1/calendar-subscriptions');await dialog.getByRole('button',{name:'Save',exact:true}).click();const reply=await response;expect(reply.status()).toBe(201);await expect(dialog).toBeHidden();return reply.json();
}
const planned=(page,subscription)=>page.locator(`.calendar-planned[data-calendar-subscription-id="${subscription.id}"]`);
async function expand(page){const toggle=page.locator('#calendar-subscriptions-toggle');if(await toggle.getAttribute('aria-expanded')==='false')await toggle.click();}
async function convert(page,subscription){await planned(page,subscription).click();await page.locator('#calendar-event-details').getByRole('button',{name:'Create activity',exact:true}).click();}
async function saveActivity(page){const reply=page.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname.endsWith('/activity'));await page.locator('#save').click();const response=await reply;expect(response.status()).toBe(201);await expect(page.locator('#editor')).toBeHidden();return response.json();}

test('calendar subscriptions show client-coloured plans and create reviewed activities at their actual times',async({browser,request})=>{
  const f=await setup(browser,request);const {page,api,context,client}=f;
  try{
    const work=await(await api('POST','/work-sessions',{clientId:client.id,projectId:null,summary:'Actual work',labelIds:[],start:'2026-05-04T08:00:00Z',end:'2026-05-04T12:00:00Z'})).json();
    const independent=await(await api('POST','/activities',{taskId:null,projectId:null,summary:'Independent research',labelIds:[],start:'2026-05-04T07:00:00Z',end:'2026-05-04T07:30:00Z'})).json();
    await page.reload();await ready(page);await calendar(page);const beforeReport=await(await api('GET',reportPath)).json();const feed=await provider(request),subscription=await subscribe(page,client,feed);
    await expect(planned(page,subscription)).toHaveCount(1);await expect(planned(page,subscription)).toContainText('Planned meeting');await expect(planned(page,subscription)).toContainText('Meeting client');await expect(planned(page,subscription)).toContainText('Team planning');
    expect((await(await api('GET',reportPath)).json()).total).toBe(beforeReport.total);
    await expect(planned(page,subscription)).toHaveCSS('background-color','rgb(23, 102, 83)');await expect(planned(page,subscription)).toHaveCSS('border-top-style','dashed');
    const before=await api('GET','/work-sessions/'+work.id),activities=(await(await api('GET',activitiesPath)).json()).items;expect(activities).toHaveLength(1);
    await expect(page.locator(`.calendar-task[data-calendar-activity-id="${independent.id}"]`)).toContainText('No overlapping work session');
    await planned(page,subscription).focus();await page.keyboard.press('Enter');const details=page.locator('#calendar-event-details');await expect(details).toBeVisible();await expect(details).toContainText('Discuss the release');await expect(details).toContainText('Review open items');await expect(details).toContainText('Meeting room');await expect(page.locator('#editor')).toBeHidden();
    await details.getByRole('button',{name:'Create activity',exact:true}).click();await expect(page.locator('#editor')).toBeVisible();await expect(page.locator('#field-summary')).toHaveValue('Planned meeting\n\nDiscuss the release\nReview open items');await expect(page.locator('#field-start')).toHaveValue('2026-05-04T10:30:00');
    await page.locator('#cancel').click();expect((await(await api('GET',activitiesPath)).json()).items).toHaveLength(1);
    await planned(page,subscription).click();await details.getByRole('button',{name:'Create activity',exact:true}).click();
    const response=page.waitForResponse(reply=>reply.request().method()==='POST'&&new URL(reply.url()).pathname.endsWith('/activity'));await page.locator('#save').click();const created=await response;expect(created.status()).toBe(201);const ids=(await created.json()).activityIds;expect(ids).toHaveLength(1);await expect(page.locator('#editor')).toBeHidden();
    expect((await(await api('GET',reportPath)).json()).total).toBe('PT1H30M');
    const recorded=page.locator(`.calendar-task[data-calendar-activity-id="${ids[0]}"]`);await expect(recorded).toContainText('Recorded');await expect(recorded).toContainText('From Team planning');await expect(recorded).toContainText('Overlaps work session: Actual work');
    expect((await api('GET','/work-sessions/'+work.id)).headers().etag).toBe(before.headers().etag);expect((await(await api('GET',activitiesPath)).json()).items).toHaveLength(2);
    await planned(page,subscription).click();await expect(details.getByRole('button',{name:'Create activity',exact:true})).toBeDisabled();await expect(details).toContainText('already been created');await page.keyboard.press('Escape');
    const observed=await(await request.get(feed.control)).json();expect(observed.headers.Authorization).toBeUndefined();expect(observed.headers.Cookie).toBeUndefined();
    await page.setViewportSize({width:1440,height:1000});await page.screenshot({path:'test-results/calendar-subscriptions-desktop.png',fullPage:true});
  }finally{await context.close();}
});

test('calendar manual refresh applies cancellations while removing subscriptions preserves converted activities',async({browser,request})=>{
  test.setTimeout(90000);const {page,api,context,client}=await setup(browser,request);
  try{
    await calendar(page);const feed=await provider(request),subscription=await subscribe(page,client,feed);await expect(planned(page,subscription)).toHaveCount(1);
    await convert(page,subscription);const saved=await saveActivity(page),id=saved.activityIds[0],before=await api('GET','/activities/'+id);
    const current=await(await api('GET','/calendar-subscriptions/'+subscription.id)).json();await request.put(feed.control,{data:{body:ics([])}});
    // The server's documented manual-refresh cooldown starts at lastAttempt.
    // Wait for that explicit deadline, rather than retrying a write repeatedly.
    await page.waitForTimeout(Math.max(0,Date.parse(current.lastAttempt)+30100-Date.now()));
    await expand(page);await page.getByRole('button',{name:'Refresh calendar Team planning',exact:true}).click();await expect(planned(page,subscription)).toHaveCount(0);expect((await api('GET','/activities/'+id)).headers().etag).toBe(before.headers().etag);
    await page.getByRole('button',{name:'Remove calendar Team planning',exact:true}).click();const dialog=page.locator('#calendar-subscription-editor');await expect(dialog).toContainText('Activities already created from this calendar will be kept');await dialog.getByRole('button',{name:'Delete',exact:true}).click();await expect(dialog).toBeHidden();
    await expect(page.locator('#calendar-subscription-list')).toContainText('No calendar subscriptions yet');await expect(page.locator(`.calendar-task[data-calendar-activity-id="${id}"]`)).toContainText('From Team planning');expect((await api('GET','/activities/'+id)).headers().etag).toBe(before.headers().etag);
  }finally{await context.close();}
});

test('calendar recurring meetings spanning midnight convert only the selected occurrence and restore its focused day',async({browser,request})=>{
  const {page,api,context,client}=await setup(browser,request);
  try{
    await calendar(page);const feed=await provider(request,['UID:night-series\nDTSTART:20260504T215900Z\nDTEND:20260504T220100Z\nRRULE:FREQ=WEEKLY;COUNT=2\nSUMMARY:Late recurring meeting']),subscription=await subscribe(page,client,feed);await expect(planned(page,subscription)).toHaveCount(2);
    const secondDay=planned(page,subscription).filter({has:page.locator('strong',{hasText:'Late recurring meeting'})}).and(page.locator('[data-date="2026-05-05"]'));
    await secondDay.click();await page.keyboard.press('Escape');await expect(secondDay).toBeFocused();await page.keyboard.press('Enter');await page.locator('#calendar-event-details').getByRole('button',{name:'Create activity',exact:true}).click();
    await expect(page.locator('#field-start')).toHaveValue('2026-05-04T23:59:00');await expect(page.locator('#field-end')).toHaveValue('2026-05-05T00:01:00');const saved=await saveActivity(page);expect(saved.activityIds).toHaveLength(2);expect((await(await api('GET',activitiesPath)).json()).items).toHaveLength(2);
    await secondDay.click();await expect(page.locator('#calendar-event-details').getByRole('button',{name:'Create activity',exact:true})).toBeDisabled();await page.keyboard.press('Escape');
    await page.locator('#calendar-date').fill('2026-05-11');await expect(page.locator('#calendar-zone')).toContainText('2026-05-11');const next=planned(page,subscription).and(page.locator('[data-date="2026-05-12"]'));await expect(next).toBeAttached();await next.click();await expect(page.locator('#calendar-event-details').getByRole('button',{name:'Create activity',exact:true})).toBeEnabled();await page.keyboard.press('Escape');
  }finally{await context.close();}
});

test('calendar all-day conversions require actual intervals and future plans keep their rejected drafts',async({browser,request})=>{
  const {page,api,context,client}=await setup(browser,request);
  try{
    const futureDate=new Date(Date.now()+14*86400000).toISOString().slice(0,10),day=futureDate.replaceAll('-','');
    const feed=await provider(request,['UID:all-day\nDTSTART;VALUE=DATE:20260504\nDTEND;VALUE=DATE:20260506\nSUMMARY:Conference','UID:milestone\nDTSTART:20260504T120000Z\nSUMMARY:Milestone',`UID:future\nDTSTART:${day}T090000Z\nDTEND:${day}T100000Z\nSUMMARY:Future appointment`]);
    await calendar(page);const subscription=await subscribe(page,client,feed);const allDay=page.locator('.calendar-all-day-event');await expect(allDay).toContainText('4 May 2026 – 5 May 2026');await expect(planned(page,subscription)).toContainText('Milestone');await allDay.click();await page.locator('#calendar-event-details').getByRole('button',{name:'Create activity',exact:true}).click();
    await expect(page.locator('#editor-notice')).toContainText('Choose the actual start and end');await expect(page.locator('#field-start')).toHaveValue('');await expect(page.locator('#field-end')).toHaveValue('');await page.locator('#save').click();await expect(page.locator('#editor')).toBeVisible();expect((await(await api('GET',activitiesPath)).json()).items).toHaveLength(0);
    await page.locator('#field-start').fill('2026-05-04T09:00:00');await page.locator('#field-end').fill('2026-05-04T10:00:00');await saveActivity(page);
    await page.locator('#calendar-date').fill(futureDate);await expect(planned(page,subscription)).toContainText('Future appointment');await convert(page,subscription);const draft=await page.locator('#field-start').inputValue();
    const rejected=page.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname.endsWith('/activity'));await page.locator('#save').click();expect((await rejected).status()).toBe(422);await expect(page.locator('#editor-error')).toContainText('cannot start in the future');await expect(page.locator('#field-start')).toHaveValue(draft);await expect(page.locator('#editor')).toBeVisible();await page.locator('#cancel').click();
  }finally{await context.close();}
});

test('calendar event details remain available with read-only access while subscription and conversion controls are disabled',async({browser,request})=>{
  const f=await setup(browser,request);let viewer;
  try{
    await calendar(f.page);const feed=await provider(request),subscription=await subscribe(f.page,f.client,feed);await expect(planned(f.page,subscription)).toHaveCount(1);
    const issued=await(await f.api('POST','/auth/tokens',{name:'Read-only calendar',permissions:['read'],expiresAt:null})).json();
    viewer=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB'});const page=await viewer.newPage();await page.goto('/web/');await expect(page.locator('#login-form button[type=submit]')).toBeEnabled();await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(issued.token);await page.locator('#login-form button[type=submit]').click();await ready(page);
    await page.locator('[data-tab=calendar]').click();await page.locator('#calendar-date').fill('2026-05-04');await expect(planned(page,subscription)).toHaveCount(1);await expect(page.locator('#calendar-subscription-add')).toBeDisabled();await expand(page);
    await expect(page.getByRole('button',{name:'Refresh calendar Team planning',exact:true})).toBeDisabled();await expect(page.getByRole('checkbox',{name:'Show calendar Team planning',exact:true})).toBeDisabled();
    await planned(page,subscription).click();await expect(page.locator('#calendar-event-details')).toContainText('Read-only access');await expect(page.locator('#calendar-event-details').getByRole('button',{name:'Create activity',exact:true})).toBeDisabled();await page.keyboard.press('Escape');await expect(planned(page,subscription)).toBeFocused();
  }finally{if(viewer)await viewer.close();await f.context.close();}
});

test('calendar conversion retries the exact committed command after a lost response without duplicating an activity',async({browser,request})=>{
  const {page,api,context,client}=await setup(browser,request);const pattern='**/api/v1/calendar-subscriptions/*/events/*/activity';
  try{
    await calendar(page);const feed=await provider(request),subscription=await subscribe(page,client,feed);await expect(planned(page,subscription)).toHaveCount(1);await convert(page,subscription);await page.locator('#field-summary').fill('Reviewed precise meeting notes');
    let original,saved;await page.route(pattern,async route=>{original={body:route.request().postDataJSON(),key:route.request().headers()['idempotency-key']};const response=await route.fetch();expect(response.status()).toBe(201);saved=await response.json();await route.abort('connectionfailed');});
    await page.locator('#save').click();await expect(page.locator('#save')).toHaveText('Retry same command');await expect(page.locator('#field-summary')).toBeDisabled();expect((await(await api('GET',activitiesPath)).json()).items).toHaveLength(1);
    await page.unroute(pattern);let retried;await page.route(pattern,async route=>{retried={body:route.request().postDataJSON(),key:route.request().headers()['idempotency-key']};await route.continue();});await saveActivity(page);expect(retried).toEqual(original);expect((await(await api('GET',activitiesPath)).json()).items).toHaveLength(1);
    await expect(page.locator(`.calendar-task[data-calendar-activity-id="${saved.activityIds[0]}"]`)).toContainText('Reviewed precise meeting notes');await planned(page,subscription).click();await expect(page.locator('#calendar-event-details').getByRole('button',{name:'Create activity',exact:true})).toBeDisabled();
  }finally{await context.close();}
});

test('calendar subscription creation retries its original command after a lost response and clears the secret input',async({browser,request})=>{
  const {page,api,context,client}=await setup(browser,request),pattern='**/api/v1/calendar-subscriptions';
  try{
    await calendar(page);const feed=await provider(request);await page.locator('#calendar-subscription-add').click();const dialog=page.locator('#calendar-subscription-editor');await dialog.getByLabel('Name',{exact:true}).fill('One calendar only');await dialog.locator('select[name=clientId]').selectOption(client.id);await dialog.getByLabel('Calendar feed URL',{exact:true}).fill(feed.url);
    let original;await page.route(pattern,async route=>{if(route.request().method()!=='POST'){await route.continue();return;}original={key:route.request().headers()['idempotency-key'],body:route.request().postDataJSON()};const response=await route.fetch();expect(response.status()).toBe(201);await route.abort('connectionfailed');});
    await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog.getByRole('button',{name:'Retry same command',exact:true})).toBeEnabled();await expect(dialog.getByLabel('Calendar feed URL',{exact:true})).toBeDisabled();await page.unroute(pattern);
    let retried;await page.route(pattern,async route=>{if(route.request().method()==='POST')retried={key:route.request().headers()['idempotency-key'],body:route.request().postDataJSON()};await route.continue();});await dialog.getByRole('button',{name:'Retry same command',exact:true}).click();await expect(dialog).toBeHidden();expect(retried).toEqual(original);
    const subscriptions=(await(await api('GET','/calendar-subscriptions')).json()).items;expect(subscriptions).toHaveLength(1);expect(JSON.stringify(subscriptions)).not.toContain(feed.url);await expect(dialog.locator('input[name=url]')).toHaveCount(0);
  }finally{await context.close();}
});

test('calendar refresh errors explain the cause and delayed event changes preserve an open conversion draft',async({browser,request})=>{
  const {page,api,context,client}=await setup(browser,request);let release=()=>{};
  try{
    await calendar(page);const feed=await provider(request,[meeting],{status:403}),subscription=await subscribe(page,client,feed);await expect(page.locator('#calendar-subscriptions-message')).toContainText('could not refresh');await expand(page);await expect(page.locator('#calendar-subscription-list')).toContainText('denied access');
    await request.put(feed.control,{data:{status:200}});await page.getByRole('button',{name:'Edit calendar Team planning',exact:true}).click();const dialog=page.locator('#calendar-subscription-editor');await expect(dialog.locator('input[name=url]')).toHaveValue('');await dialog.locator('input[name=url]').fill(feed.url+'?repaired=1');await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();await expect(planned(page,subscription)).toHaveCount(1);
    let requested;const arrived=new Promise(resolve=>{requested=resolve;}),gate=new Promise(resolve=>{release=resolve;});
    await page.route('**/api/v1/calendar-events?*',async route=>{requested();await gate;await route.continue();});await page.locator('[data-tab=calendar]').click();await arrived;
    await convert(page,subscription);await page.locator('#field-summary').fill('Keep this reviewed meeting draft');
    await request.put(feed.control,{data:{body:ics([])}});const current=await api('GET','/calendar-subscriptions/'+subscription.id);await api('PUT','/calendar-subscriptions/'+subscription.id,{name:'Team planning',clientId:client.id,visible:true,url:feed.url+'?cancelled=2'},{'If-Match':current.headers().etag});
    await expect.poll(async()=>Boolean((await(await api('GET','/calendar-subscriptions/'+subscription.id)).json()).lastSuccess)).toBe(true);release();await expect(planned(page,subscription)).toHaveCount(0);await expect(page.locator('#field-summary')).toHaveValue('Keep this reviewed meeting draft');
    await page.locator('#save').click();await expect(page.locator('#editor-error')).toContainText('changed or was cancelled');await expect(page.locator('#field-summary')).toHaveValue('Keep this reviewed meeting draft');expect((await(await api('GET',activitiesPath)).json()).items).toHaveLength(0);await page.locator('#cancel').click();
  }finally{release();await context.close();}
});

test('calendar subscriptions and event dialogs fit narrow screens and remain accessible in Norwegian across every theme',async({browser,request})=>{
  test.setTimeout(90000);const {page,api,context,client}=await setup(browser,request);
  try{
    await calendar(page);const feed=await provider(request,[meeting,'UID:all-day-layout\nDTSTART;VALUE=DATE:20260504\nDTEND;VALUE=DATE:20260506\nSUMMARY:Conference']),subscription=await subscribe(page,client,feed);await expect(planned(page,subscription)).toHaveCount(1);
    const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'Europe/Oslo',locale:'nb-NO'},{'If-Match':profile.headers().etag});await page.reload();await ready(page);await calendar(page);await expect(planned(page,subscription)).toContainText('Planlagt');await page.setViewportSize({width:390,height:844});await expand(page);
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
      await setTheme(page,theme);
      const month=await new AxeBuilder({page}).include('#calendar-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(month.violations.map(value=>({id:value.id,nodes:value.nodes.map(node=>node.target)}))).toEqual([]);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      await planned(page,subscription).click();const event=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(event.violations.map(value=>({id:value.id,nodes:value.nodes.map(node=>node.target)}))).toEqual([]);await page.keyboard.press('Escape');
      await page.locator(`[data-calendar-action="edit:${subscription.id}"]`).click();await expect(page.locator('#calendar-subscription-editor input[name=url]')).toHaveValue('');const edit=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(edit.violations.map(value=>({id:value.id,nodes:value.nodes.map(node=>node.target)}))).toEqual([]);await page.locator('#calendar-subscription-editor button[type=button]').click();
    }
    await setTheme(page,'timekeeper');await page.screenshot({path:'test-results/calendar-subscriptions-mobile.png',fullPage:true});await page.locator('.calendar-all-day-event').click();await expect(page.locator('#calendar-event-details')).toContainText('Hele dagen');await page.screenshot({path:'test-results/calendar-subscriptions-details-mobile.png',fullPage:false});
  }finally{await context.close();}
});

test('calendar responses from a previous account cannot reopen dialogs or sign out the next account',async({browser,request})=>{
  const {page,context,client}=await setup(browser,request);let release=()=>{};
  try{
    await calendar(page);const feed=await provider(request),subscription=await subscribe(page,client,feed);await expect(planned(page,subscription)).toHaveCount(1);await expand(page);const next=await fresh(request);
    let requested;const arrived=new Promise(resolve=>{requested=resolve;}),gate=new Promise(resolve=>{release=resolve;});
    await page.route('**/api/v1/calendar-subscriptions/'+subscription.id,async route=>{requested();await gate;try{await route.fulfill({status:401,contentType:'application/json',body:JSON.stringify({code:'UNAUTHENTICATED'})});}catch{/* The retired API context may already have aborted this request. */}});
    await page.locator(`[data-calendar-action="edit:${subscription.id}"]`).click();await arrived;await page.locator('#account-menu > summary').click();await page.locator('#sign-out').click();await expect(page.locator('#workspace')).toBeHidden();await login(page,next,false);release();await page.unrouteAll({behavior:'wait'});
    await expect(page.locator('#workspace')).toBeVisible();await expect(page.locator('#owner')).toHaveText(next.username);await expect(page.locator('#calendar-subscription-editor')).toBeHidden();await expect(page.locator('#calendar-subscription-editor input')).toHaveCount(0);await page.locator('[data-tab=calendar]').click();await expect(page.locator('#calendar-subscription-list')).toContainText('Create a client');await expect(page.locator('.calendar-planned')).toHaveCount(0);
  }finally{release();await context.close();}
});
