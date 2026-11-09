import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {captureScreenshot} from './screenshots.js';
import {openThemeSettings} from './navigation.js';

const state='s'.repeat(43),code='fixture-provider-authorization-code';
test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'Administrator',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function locale(request,value){
  const headers={Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},before=await request.get('/api/v1/me',{headers});
  expect((await request.put('/api/v1/me',{headers:{...headers,'If-Match':before.headers().etag},data:{zoneId:(await before.json()).zoneId,locale:value}})).status()).toBe(200);
}
async function signIn(page,request,{language='en-GB',readOnly=false}={}){
  await locale(request,language);await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env[readOnly?'TIMEKEEPER_WEB_READ_TOKEN':'TIMEKEEPER_WEB_TOKEN']);await page.locator('#login-form [type="submit"]').click();await expect(page.locator('#workspace')).toBeVisible();
  return (await(await browserRequest(page).get('/api/v1/auth/session')).json()).ownerId;
}
function connection(owner,overrides={}){
  return {id:randomUUID(),ownerId:owner,providerId:'google',remoteAccountId:'fixture-account',name:'Fixture account',enabled:true,version:1,direction:'IMPORT',syncing:false,pendingCount:0,listErrors:{},lastAttempt:null,lastSuccess:null,nextRefresh:'2026-11-09T12:00:00Z',lastError:null,
    selectedLists:['work'],availableLists:[{id:'work',name:'Work',kind:'STANDARD',shared:false,owner:true},{id:'home',name:'Home',kind:'STANDARD',shared:false,owner:true}],...overrides};
}
async function providerFixture(page,owner,{rows=[connection(owner)],providers={google:true,microsoft:true},authorization=null}={}){
  const model={rows,providers,writes:[],reads:0,completed:0,cancelled:0};
  await page.route('**/api/v1/task-providers**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname.slice('/api/v1/task-providers'.length),method=request.method(),input=JSON.parse(request.postData()||'{}');
    const headers={'Content-Type':'application/json','Cache-Control':'no-store','X-Timekeeper-Owner':Buffer.from(owner).toString('base64url')};
    const reply=(body,status=200)=>route.fulfill({status,headers:{...headers,ETag:`"provider-${body?.version??0}"`},...(status===204?{}:{body:JSON.stringify(body)})});
    if(method!=='GET')model.writes.push({path,method,input,headers:request.headers()});else model.reads++;
    if(path==='')return reply({items:model.rows,providers:model.providers,refreshMinutes:15});
    if(path.endsWith('/authorize'))return reply({authorizationUrl:authorization||`https://${path.startsWith('/google')?'accounts.google.com/o/oauth2/v2/auth':'login.microsoftonline.com/common/oauth2/v2.0/authorize'}?state=${state}`,expiresAt:'2026-11-09T12:10:00Z'});
    if(path==='/complete'){model.completed++;expect(input).toEqual({state,code});expect(new URL(page.url()).search).toBe('');expect(request.headers().referer).not.toContain(code);expect(request.headers().referer).not.toContain(state);return reply(model.rows[0]);}
    if(path==='/cancel'){model.cancelled++;expect(input).toEqual({state});return reply(null,204);}
    const row=model.rows.find(value=>path.startsWith('/'+value.id));if(!row)return reply({type:'about:blank',title:'Not found',status:404,code:'NOT_FOUND'},404);
    if(method==='GET')return reply(row);
    if(method==='PUT'&&path.endsWith('/lists')){expect(request.headers()['if-match']).toBe(`"provider-${row.version}"`);row.selectedLists=input.selectedLists;row.version++;row.syncing=true;return reply(row);}
    if(method==='DELETE'){model.rows=model.rows.filter(value=>value!==row);return reply(null,204);}
    if(method==='POST'&&path.endsWith('/refresh')){row.version++;row.lastSuccess='2026-11-09T12:01:00Z';return reply(row,202);}
    throw new Error('Unexpected task provider fixture request');
  });return model;
}
async function open(page){await page.goto('/web/#account/task-providers');await expect(page.locator('#account-content [data-task-provider-connect="google"]')).toBeVisible();}
const card=(page,id)=>page.locator(`[data-task-provider-id="${id}"]`);
test.afterEach(async({request})=>{await locale(request,'en-GB');});

test('provider setup availability and read-only sessions do not acquire write controls',async({page,request})=>{
  const owner=await signIn(page,request,{readOnly:true}),row=connection(owner),model=await providerFixture(page,owner,{rows:[row],providers:{google:true,microsoft:false}});await open(page);
  await expect(page.getByRole('heading',{name:'Task connections',exact:true})).toBeVisible();
  for(const name of ['Connect Google Tasks','Connect Microsoft To Do','Save','Refresh from provider','Reconnect','Disconnect'])await expect(page.getByRole('button',{name,exact:true})).toBeDisabled();
  await expect(card(page,row.id).getByRole('checkbox',{name:'Work',exact:true})).toBeDisabled();await expect(page.getByRole('button',{name:'Refresh status',exact:true})).toBeEnabled();
  await expect(page.getByText('Unavailable task providers must be enabled by the server administrator.',{exact:true})).toBeVisible();expect(model.writes).toEqual([]);
});

test('list drafts survive status updates and stale saves keep the captured selection',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner),model=await providerFixture(page,owner,{rows:[row]});await open(page);
  const panel=card(page,row.id),home=panel.getByRole('checkbox',{name:'Home',exact:true});await home.check();row.version++;row.syncing=true;
  await page.getByRole('button',{name:'Refresh status',exact:true}).click();await expect(home).toBeChecked();await expect(panel.getByText('Refreshing selected lists…',{exact:true})).toBeVisible();
  await panel.getByRole('button',{name:'Save',exact:true}).click();await expect(panel.getByText('Reload current values',{exact:true})).toBeVisible();await expect(home).toBeChecked();expect(model.writes.filter(value=>value.method==='PUT')).toEqual([]);
});

test('saved selections refresh in the background without replacing focused controls',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner),model=await providerFixture(page,owner,{rows:[row]});await open(page);
  const panel=card(page,row.id),home=panel.getByRole('checkbox',{name:'Home',exact:true});await home.check();await panel.getByRole('button',{name:'Save',exact:true}).click();
  await expect(panel.getByText('List selection saved. Imports run in the background.',{exact:true})).toBeVisible();expect(model.writes.find(value=>value.method==='PUT').input).toEqual({selectedLists:['work','home']});
  row.version++;row.syncing=false;row.lastSuccess='2026-11-09T12:01:00Z';await home.focus();await page.getByRole('button',{name:'Refresh status',exact:true}).evaluate(button=>button.click());
  await expect(home).toBeFocused();await expect(home).toBeChecked();await expect(panel.getByText('Refreshing selected lists…',{exact:true})).toHaveCount(0);
  await panel.getByRole('button',{name:'Disconnect',exact:true}).click();await expect(panel).toHaveCount(0);await expect(page.getByText('Disconnected. Imported tasks remain in Dagsverk.',{exact:true})).toBeVisible();expect(model.writes.filter(value=>value.method==='DELETE')).toHaveLength(1);
});

test('provider status errors are actionable and remain text in all themes and narrow screens',async({page,request})=>{
  test.setTimeout(120000);const owner=await signIn(page,request),row=connection(owner,{name:'Account <img src=x onerror=alert(1)> '+ 'LongName'.repeat(24),lastError:'TASK_PROVIDER_RECONNECT_REQUIRED'});await providerFixture(page,owner,{rows:[row]});await open(page);
  await expect(card(page,row.id).getByText('Reconnect this account and grant task access to resume synchronization.',{exact:true})).toBeVisible();await expect(card(page,row.id).locator('img')).toHaveCount(0);
  for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
    await openThemeSettings(page);await page.locator('#theme').selectOption(theme);await page.getByRole('link',{name:'Task connections',exact:true}).click();await expect(card(page,row.id)).toBeVisible();
    for(const width of [1440,390,320]){await page.setViewportSize({width,height:1000});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#account-page').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);}
  }
  await captureScreenshot(page,{path:'test-results/task-providers-320.png',fullPage:true});
});

test('task connections and failure states are localized in Norwegian',async({page,request})=>{
  const owner=await signIn(page,request,{language:'nb-NO'}),row=connection(owner,{lastError:'TASK_PROVIDER_CONFLICTS',pendingCount:2});await providerFixture(page,owner,{rows:[row]});await open(page);
  await expect(page.getByRole('heading',{name:'Oppgavekoblinger',exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'Koble til Google Tasks',exact:true})).toBeVisible();await expect(card(page,row.id).getByText('Noen oppgaver må gjennomgås. Lokale endringer og tidligere importerte oppgaver beholdes. Oppgaver som må gjennomgås: 2.',{exact:true})).toBeVisible();
});

test('authorization responses cannot navigate to an unrelated host',async({page,request})=>{
  const owner=await signIn(page,request);await providerFixture(page,owner,{rows:[],authorization:'https://untrusted.example.invalid/collect'});await open(page);
  await page.getByRole('button',{name:'Connect Google Tasks',exact:true}).click();await expect(page.getByText('Reload current values',{exact:true})).toBeVisible();expect(new URL(page.url()).pathname).toBe('/web/');
});

test('callback removes provider codes from history and referrers before completing the owned connection',async({page,request})=>{
  const owner=await signIn(page,request),model=await providerFixture(page,owner);
  const callback=new URL('/web/task-provider-callback.html?'+new URLSearchParams({state,code}),process.env.TIMEKEEPER_WEB_URL).href;
  await page.route('https://accounts.google.com/o/oauth2/v2/auth*',route=>route.fulfill({status:200,contentType:'text/html',body:'<!doctype html><html lang="en"><head><title>Provider fixture</title></head><body><a href="'+callback.replaceAll('&','&amp;').replaceAll('"','&quot;')+'">Continue</a></body></html>'}));
  await page.goto('https://accounts.google.com/o/oauth2/v2/auth?fixture=1');await page.getByRole('link',{name:'Continue',exact:true}).click();await expect(page).toHaveURL(/\/web\/#account\/task-providers$/);await expect(page.getByRole('heading',{name:'Task connections',exact:true})).toBeVisible();expect(model.completed).toBe(1);
  expect(await page.evaluate(({state,code})=>[...Object.values(localStorage),...Object.values(sessionStorage)].some(value=>value.includes(state)||value.includes(code)),{state,code})).toBe(false);
});

test('cancelled or malformed callback grants are cleared without creating a connection',async({page,request})=>{
  const owner=await signIn(page,request),model=await providerFixture(page,owner);
  await page.goto('/web/task-provider-callback.html?'+new URLSearchParams({state,error:'access_denied',error_description:'private provider detail'}));await expect(page.getByText('Connection cancelled.',{exact:true})).toBeVisible();expect(model.cancelled).toBe(1);expect(new URL(page.url()).search).toBe('');await expect(page.getByText('private provider detail',{exact:true})).toHaveCount(0);
  await page.goto('/web/task-provider-callback.html?state=short&code='+code);await expect(page.locator('#task-provider-result')).toHaveAttribute('role','alert');expect(model.completed).toBe(0);expect(new URL(page.url()).search).toBe('');
});

test('late connection responses cannot paint account data after sign-out',async({page,request})=>{
  const owner=await signIn(page,request);let release,started;const waiting=new Promise(resolve=>{release=resolve;}),observed=new Promise(resolve=>{started=resolve;});
  await page.route('**/api/v1/task-providers',async route=>{started();await waiting;await route.fulfill({status:200,headers:{'Content-Type':'application/json','X-Timekeeper-Owner':Buffer.from(owner).toString('base64url')},body:JSON.stringify({items:[connection(owner,{name:'Private provider account title'})],providers:{google:true,microsoft:true},refreshMinutes:15})}).catch(()=>{});});
  await page.goto('/web/#account/task-providers',{waitUntil:'domcontentloaded'});await observed;await page.locator('#account-menu > summary').click();await page.locator('#sign-out').click();await expect(page.locator('#login-form')).toBeVisible();release();
  await expect(page.getByText('Private provider account title',{exact:false})).toHaveCount(0);await expect(page.locator('#account-content')).toBeEmpty();
});
