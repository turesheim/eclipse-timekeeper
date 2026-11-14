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
  return {id:randomUUID(),ownerId:owner,providerId:'google',remoteAccountId:'fixture-account',name:'Fixture account',enabled:true,version:1,configurationVersion:1,direction:'IMPORT',paused:false,imports:[],identityReviews:[],reviewAdditions:true,projectMappings:{},syncing:false,pendingCount:0,listErrors:{},lastAttempt:null,lastSuccess:null,nextRefresh:'2026-11-09T12:00:00Z',lastError:null,
    selectedLists:['work'],availableLists:[{id:'work',name:'Work',kind:'STANDARD',shared:false,owner:true},{id:'home',name:'Home',kind:'STANDARD',shared:false,owner:true}],...overrides};
}
async function providerFixture(page,owner,{rows=[connection(owner)],providers={google:true,microsoft:true},calendarSynchronization=true,readiness={},authorizationFailure=null,authorization=null,authorizationOrigin=null,clients=[],projects=[],tasks=[],lists=[],jobs=[],candidates=[],reviews=[],identities=[]}={}){
  const model={rows,providers,readiness,authorizationFailure,writes:[],reads:0,completed:0,cancelled:0,previewVersion:3,jobs,candidates,candidateVersion:1,providerWrites:0,reviews,reviewVersion:1,identities,identityVersion:1,nativeVersion:1,tasks};
  for(const [path,items]of [['clients',clients],['projects',projects],['tasks',tasks],['task-lists',lists]])if(items.length)await page.route('**/api/v1/'+path+'?*',async route=>{
    const response=await route.fetch();if(!response.ok())return route.fulfill({response});
    const current=await response.json();return route.fulfill({response,body:JSON.stringify({...current,items:items.map(item=>({ownerId:owner,version:0,...item})),nextCursor:null})});
  });
  if(tasks.length)await page.route('**/api/v1/tasks/*',async route=>{
    const id=new URL(route.request().url()).pathname.split('/').at(-1),task=model.tasks.find(item=>item.id===id);if(!task)return route.continue();return route.fulfill({status:200,headers:{'Content-Type':'application/json','Cache-Control':'no-store','X-Timekeeper-Owner':Buffer.from(owner).toString('base64url'),ETag:`"native-${model.nativeVersion}"`},body:JSON.stringify({ownerId:owner,...task,version:model.nativeVersion})});
  });
  await page.route('**/api/v1/task-providers**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname.slice('/api/v1/task-providers'.length),method=request.method(),input=JSON.parse(request.postData()||'{}');
    const headers={'Content-Type':'application/json','Cache-Control':'no-store','X-Timekeeper-Owner':Buffer.from(owner).toString('base64url')};
    const reply=(body,status=200)=>route.fulfill({status,headers:{...headers,ETag:`"provider-${body?.version??0}"`},...(status===204?{}:{body:JSON.stringify(body)})});
    if(method!=='GET')model.writes.push({path,method,input,headers:request.headers()});else model.reads++;
    if(path==='')return reply({items:model.rows,providers:model.providers,readiness:model.readiness,refreshMinutes:15,calendarSynchronization});
    if(path.endsWith('/authorize')&&model.authorizationFailure)return reply({code:model.authorizationFailure.code,status:model.authorizationFailure.status},model.authorizationFailure.status);
    if(path.endsWith('/authorize'))return reply({authorizationUrl:authorization||`https://${path.startsWith('/google')?'accounts.google.com/o/oauth2/v2/auth':'login.microsoftonline.com/common/oauth2/v2.0/authorize'}?state=${state}`,expiresAt:'2026-11-09T12:10:00Z',...(authorizationOrigin?{authorizationOrigin}:{})});
    if(path==='/complete'){model.completed++;expect(input).toEqual({state,code});expect(new URL(page.url()).search).toBe('');expect(request.headers().referer).not.toContain(code);expect(request.headers().referer).not.toContain(state);return reply(model.rows[0]);}
    if(path==='/cancel'){model.cancelled++;expect(input).toEqual({state});return reply(null,204);}
    const row=model.rows.find(value=>path.startsWith('/'+value.id));if(!row)return reply({type:'about:blank',title:'Not found',status:404,code:'NOT_FOUND'},404);
    if(path.includes('/identities/')){
      if(method==='GET'){const q=(new URL(request.url()).searchParams.get('q')||'').toLowerCase(),items=model.identities.filter(item=>(item.externalId+' '+item.fields.summary).toLowerCase().includes(q));return reply({observedAt:'2026-11-10T00:00:00Z',count:items.length,items:items.slice(0,100).map(item=>({...item,reviewEtag:`"identity-${model.identityVersion}"`}))});}
      if(request.headers()['if-match']!==`"identity-${model.identityVersion}"`||input.decision==='REPLACE'&&input.taskEtag!==`"native-${model.nativeVersion}"`)return reply({code:'STALE_VERSION',status:412},412);
      const item=model.identities.find(item=>item.externalId===input.externalId);if(!item)return reply({code:'NOT_FOUND',status:404},404);model.identities=model.identities.filter(item=>item!==item);if(!model.identities.length)row.identityReviews=[];return reply({id:input.taskId||randomUUID()});
    }
    if(path.includes('/reviews/')){
      if(method==='GET'){const q=(new URL(request.url()).searchParams.get('q')||'').toLowerCase(),items=model.reviews.filter(item=>(item.externalId+' '+item.localFields?.summary+' '+item.remoteFields?.summary).toLowerCase().includes(q));return reply({observedAt:'2026-11-10T00:00:00Z',count:items.length,items:items.slice(0,100).map(item=>({...item,reviewEtag:`"incoming-${model.reviewVersion}"`}))});}
      const review=model.reviews.find(item=>path.endsWith('/'+item.taskId));if(!review)return reply({code:'NOT_FOUND',status:404},404);
      if(request.headers()['if-match']!==`"incoming-${model.reviewVersion}"`||input.taskEtag!==review.taskEtag)return reply({code:'STALE_VERSION',status:412},412);
      model.reviews=model.reviews.filter(item=>item!==review);row.reviews=[];return reply({taskId:review.taskId,deleted:input.decision==='DELETE'});
    }
    if(path.includes('/writes')){
      if(path.endsWith('/writes')){
        if(method==='GET')return reply({items:model.jobs.map(({id,title,action,state,createdAt,lastError})=>({id,title,action,state,createdAt,lastError}))});
        if(method==='POST'){const value=writeJob({connectionId:row.id,taskId:input.taskId,listId:input.listId,action:input.action,title:tasks.find(task=>task.id===input.taskId)?.summary||'Imported Google task'});model.jobs.push(value);row.pendingWrites=model.jobs.length;return reply(value,201);}
      }
      const job=model.jobs.find(job=>path.includes('/writes/'+job.id));if(!job)return reply({type:'about:blank',title:'Not found',status:404,code:'NOT_FOUND'},404);
      if(method==='GET'&&path.endsWith('/candidates'))return reply({scanEtag:`"scan-${model.candidateVersion}"`,observedAt:'2026-11-10T00:00:00Z',count:model.candidates.length,items:model.candidates});
      if(method==='GET')return reply(job);
      if(request.headers()['if-match']!==`"provider-${job.version}"`)return reply({type:'about:blank',title:'Stale version',status:412,code:'STALE_VERSION'},412);
      if(path.endsWith('/leave-unresolved')){expect(input).toEqual({acknowledgePossibleSeparateImport:true});expect(job.canLeaveUnresolved).toBe(true);job.version++;job.state='UNRESOLVED';job.canLeaveUnresolved=false;row.pendingWrites=0;return reply(job);}
      if(path.endsWith('/identity')){expect(input.taskEtag).toBe(job.currentTaskEtag);if(input.scanEtag!==`"scan-${model.candidateVersion}"`)return reply({type:'about:blank',title:'Stale scan',status:412,code:'STALE_VERSION'},412);job.version++;job.state='RECONCILED';job.externalId=input.externalId;return reply(job);}
      if(path.endsWith('/confirm')){model.providerWrites++;job.version++;job.state='DONE';job.canConfirm=false;return reply(job,202);}
      if(path.endsWith('/check')){job.version++;job.remoteFields={summary:'Changed at Google'};job.conflictFields=['summary'];job.canResolve=true;return reply(job,202);}
      if(path.endsWith('/resolve')){expect(input.taskEtag).toBe(job.currentTaskEtag);job.version++;job.state='RECONCILED';job.canResolve=false;return reply(job);}
      if(method==='DELETE'){job.version++;job.state='CANCELLED';return reply(null,204);}
    }
    if(path.includes('/imports/')){
      if(method==='GET')return reply({id:path.split('/').at(-1),version:model.previewVersion,remoteListId:'work',projectId:row.projectMappings.work||null,observedAt:'2026-11-09T12:00:00Z',count:3,createCount:2,existingCount:1,deletedCount:0,revision:'a'.repeat(64),samples:[{title:'Source <img src=x onerror=alert(1)>',deleted:false},{title:'Undated task',deleted:false}]});
      if(method==='POST'){
        if(request.headers()['if-match']!==`"provider-${model.previewVersion}"`)return reply({type:'about:blank',title:'Stale version',status:412,code:'STALE_VERSION'},412);
        row.imports=[];row.version++;return reply(row);
      }
    }
    if(method==='GET')return reply(row);
    if(method==='PUT'&&path.endsWith('/calendars')){expect(request.headers()['if-match']).toBe(`"provider-${row.version}"`);row.selectedCalendars=input.selectedCalendars;row.calendarMappings=input.calendarMappings;row.version++;row.calendarConfigurationVersion=(row.calendarConfigurationVersion??0)+1;return reply(row);}
    if(method==='PUT'&&path.endsWith('/lists')){expect(request.headers()['if-match']).toBe(`"provider-${row.version}"`);row.selectedLists=input.selectedLists;row.projectMappings=input.projectMappings;if(Object.hasOwn(input,'reviewAdditions'))row.reviewAdditions=input.reviewAdditions;row.version++;row.configurationVersion++;row.syncing=true;return reply(row);}
    if(method==='PUT'&&path.endsWith('/state')){expect(request.headers()['if-match']).toBe(`"provider-${row.version}"`);row.paused=input.paused;row.version++;return reply(row);}
    if(method==='DELETE'){model.rows=model.rows.filter(value=>value!==row);return reply(null,204);}
    if(method==='POST'&&path.endsWith('/refresh')){row.version++;row.lastSuccess='2026-11-09T12:01:00Z';return reply(row,202);}
    throw new Error('Unexpected task provider fixture request');
  });return model;
}
function writeJob(overrides={}){
  return {id:randomUUID(),version:1,connectionId:randomUUID(),listId:randomUUID(),taskId:randomUUID(),title:'Imported Google task',action:'UPDATE',state:'PREVIEW',createdAt:'2026-11-10T00:00:00Z',expiresAt:'2026-11-10T00:15:00Z',lastError:null,writeFields:{summary:'Changed locally'},warnings:[{field:'body',reason:'TEXT_ONLY'}],canConfirm:true,currentTaskEtag:'"task-current"',localFields:{summary:'Changed locally',body:{format:'HTML',content:'<strong>Local notes</strong>'}},remoteFields:null,remoteObservedAt:null,resolution:null,canResolve:false,conflictFields:[],...overrides};
}
async function open(page,{expanded=true}={}){
  await page.goto('/web/#account/task-providers');await expect(page.locator('#account-content [data-task-provider-connect="google"]')).toBeAttached();await expect(page.locator('#account-content')).toHaveAttribute('aria-busy','false');
  if(expanded)for(const details of await page.locator('[data-provider-connect],[data-connection-settings]').all())if(!await details.evaluate(value=>value.open))await details.locator('summary').first().click();
}
const card=(page,id)=>page.locator(`[data-task-provider-id="${id}"]`);
test.afterEach(async({page,request})=>{await page.unrouteAll({behavior:'wait'});await locale(request,'en-GB');});

test('provider accounts and unauthenticated subscriptions remain separate choices without administrator setup',async({page,request})=>{
  const owner=await signIn(page,request),model=await providerFixture(page,owner,{rows:[],providers:{google:false,microsoft:false}});await open(page);
  await expect(page.getByRole('heading',{name:'Internet accounts',exact:true})).toHaveCount(0);await expect(page.getByRole('link',{name:'Open Internet account administration',exact:true})).toHaveCount(0);
  const google=page.locator('[data-internet-provider=google]'),microsoft=page.locator('[data-internet-provider=microsoft]');
  await expect(google.getByRole('heading',{name:'Google',exact:true})).toBeVisible();await expect(microsoft.getByRole('heading',{name:'Microsoft',exact:true})).toBeVisible();
  await expect(google.getByRole('button',{name:'Connect Google',exact:true})).toBeDisabled();await expect(microsoft.getByRole('button',{name:'Connect Microsoft',exact:true})).toBeDisabled();
  await expect(page.getByText(/enabled by the server administrator/)).toHaveCount(0);
  await page.getByRole('link',{name:'Add a calendar subscription',exact:true}).click();await expect(page).toHaveURL(/#account\/calendars$/);await expect(page.locator('#calendar-subscription-add')).toBeVisible();expect(model.writes).toEqual([]);
});

test('calendar-only sign-in uses the shared service and does not request task writes',async({page,request})=>{
  const owner=await signIn(page,request),origin='https://connect.example.test',target=origin+'/connect/'+'c'.repeat(43),model=await providerFixture(page,owner,{rows:[],authorization:target,authorizationOrigin:origin});
  await page.route(origin+'/**',route=>route.fulfill({status:200,contentType:'text/html',body:'<!doctype html><title>Dagsverk shared sign-in</title><h1>Shared sign-in</h1>'}));await open(page);
  const google=page.locator('[data-internet-provider=google]');await google.getByRole('checkbox',{name:'Tasks',exact:true}).uncheck();await expect(google.getByRole('combobox',{name:'Task synchronization',exact:true})).toBeDisabled();
  await google.getByRole('button',{name:'Connect Google',exact:true}).click();await expect(page).toHaveURL(target);expect(model.writes.find(item=>item.path==='/google/authorize').input).toEqual({services:{tasks:false,calendars:true},language:'en',direction:'IMPORT'});
});

test('calendar-only connection actions keep errors visible outside hidden task controls',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner,{services:{calendars:true,tasks:false},selectedLists:[],availableCalendars:[],selectedCalendars:[]});await providerFixture(page,owner,{rows:[row]});
  await page.route('**/api/v1/task-providers/'+row.id+'/refresh',route=>route.fulfill({status:503,headers:{'Content-Type':'application/json','X-Timekeeper-Owner':Buffer.from(owner).toString('base64url')},body:JSON.stringify({code:'TASK_PROVIDER_UNAVAILABLE',status:503})}));await open(page,{expanded:false});const panel=card(page,row.id);
  await expect(panel.getByRole('heading',{name:'Tasks',exact:true})).toBeHidden();const refresh=panel.getByRole('button',{name:'Refresh from provider',exact:true});await refresh.focus();await refresh.press('Enter');
  await expect(panel.locator(':scope > .form-feedback')).toBeVisible();await expect(panel.getByRole('button',{name:'Reload current values',exact:true})).toBeVisible();await expect(refresh).toBeDisabled();
});

test('direct connections offer calendar and task choices without infrastructure controls',async({page,request})=>{
  const owner=await signIn(page,request),model=await providerFixture(page,owner,{rows:[]});await page.route('https://accounts.google.com/**',route=>route.fulfill({status:200,contentType:'text/html',body:'<!doctype html><title>Google consent fixture</title>'}));await open(page);
  const google=page.locator('[data-internet-provider=google]');await expect(google.getByRole('checkbox',{name:'Calendars',exact:true})).toBeChecked();await expect(google.getByRole('checkbox',{name:'Tasks',exact:true})).toBeChecked();await expect(page.getByRole('link',{name:'Add a calendar subscription',exact:true})).toBeVisible();await google.getByRole('button',{name:'Connect Google',exact:true}).click();await expect(page).toHaveURL(/^https:\/\/accounts\.google\.com\//);expect(model.writes.find(item=>item.path==='/google/authorize').input).toEqual({services:{tasks:true,calendars:true},language:'en',direction:'IMPORT'});
});

test('calendar and task drafts save independently on one connected account',async({page,request})=>{
  const owner=await signIn(page,request),client={id:randomUUID(),name:'Client'},row=connection(owner,{services:{tasks:true,calendars:true,writableTasks:false},availableCalendars:[{id:'team',name:'Team calendar',readable:true},{id:'personal',name:'Personal calendar',readable:true}],selectedCalendars:['team'],calendarMappings:{team:client.id},calendarConfigurationVersion:1});
  const model=await providerFixture(page,owner,{rows:[row],clients:[client]});await open(page);const panel=card(page,row.id),home=panel.getByRole('checkbox',{name:'Home',exact:true}),personal=panel.getByRole('checkbox',{name:'Personal calendar',exact:true});
  await home.check();await personal.check();await page.getByRole('button',{name:'Refresh Google status',exact:true}).click();await expect(home).toBeChecked();await expect(personal).toBeChecked();
  await panel.getByRole('button',{name:'Save calendars',exact:true}).click();await expect(panel.getByText('Calendar selection saved. Synchronization will start shortly.',{exact:true})).toBeVisible();await expect(home).toBeChecked();expect(row.selectedLists).toEqual(['work']);expect(row.selectedCalendars).toEqual(['team','personal']);
  await panel.getByRole('button',{name:'Save task lists',exact:true}).click();await expect(panel.getByText('List selection saved.',{exact:true})).toBeVisible();await expect(personal).toBeChecked();expect(row.selectedLists).toEqual(['work','home']);
  expect(model.writes.find(item=>item.path.endsWith('/calendars')).input).toEqual({selectedCalendars:['team','personal'],calendarMappings:{team:client.id,personal:client.id}});expect(model.writes.find(item=>item.path.endsWith('/lists')).input).not.toHaveProperty('selectedCalendars');
});

for(const language of ['en-GB','nb-NO'])test('Internet accounts and both synchronization controls fit every theme in '+language,async({page,request})=>{
  test.setTimeout(120000);const owner=await signIn(page,request,{language}),client={id:randomUUID(),name:'Client'},row=connection(owner,{services:{tasks:true,calendars:true,writableTasks:false},availableCalendars:[{id:'team',name:'Team calendar',readable:true}],selectedCalendars:['team'],calendarMappings:{team:client.id},calendarConfigurationVersion:1});await providerFixture(page,owner,{rows:[row],clients:[client]});await open(page);const nb=language==='nb-NO';
  for(const theme of ['timekeeper','timekeeper-dark']){
    await openThemeSettings(page);await page.locator('#theme').selectOption(theme);await page.getByRole('link',{name:nb?'Internettkontoer':'Internet accounts',exact:true}).click();
    for(const width of [1440,390,320]){await page.setViewportSize({width,height:1000});await expect(card(page,row.id).getByRole('button',{name:nb?'Lagre kalendere':'Save calendars',exact:true})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);if(width!==320)await captureScreenshot(page,{path:`test-results/internet-accounts-${language}-${theme}-${width}.png`,fullPage:true});}
  }
  const accessibility=await new AxeBuilder({page}).include('#account-content').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(accessibility.violations).toEqual([]);await captureScreenshot(page,{path:'test-results/internet-accounts-'+language+'-320.png',fullPage:true});
});

test('provider setup availability and read-only sessions do not acquire write controls',async({page,request})=>{
  const owner=await signIn(page,request,{readOnly:true}),row=connection(owner),model=await providerFixture(page,owner,{rows:[row],providers:{google:true,microsoft:false}});await open(page);
  await expect(page.getByRole('heading',{name:'Google',exact:true})).toBeVisible();
  for(const name of ['Connect Google','Connect Microsoft','Save task lists','Refresh from provider','Pause synchronization','Reconnect','Disconnect'])await expect(page.getByRole('button',{name,exact:true})).toBeDisabled();
  await expect(card(page,row.id).getByRole('checkbox',{name:'Work',exact:true})).toBeDisabled();await expect(page.getByRole('button',{name:'Refresh Google status',exact:true})).toBeEnabled();
  await expect(page.getByText('Some connections are not ready. Check the status below, or use a calendar subscription. Refresh status to check again.',{exact:true})).toHaveCount(0);expect(model.writes).toEqual([]);
});

test('list drafts survive status updates and stale saves keep the captured selection',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner),model=await providerFixture(page,owner,{rows:[row]});await open(page);
  const panel=card(page,row.id),home=panel.getByRole('checkbox',{name:'Home',exact:true});await home.check();row.version++;row.configurationVersion++;row.syncing=true;
  await page.getByRole('button',{name:'Refresh Google status',exact:true}).click();await expect(home).toBeChecked();await expect(panel.getByText('Refreshing selected lists…',{exact:true})).toBeVisible();
  await panel.getByRole('button',{name:'Save task lists',exact:true}).click();await expect(panel.getByText('Reload current values',{exact:true})).toBeVisible();await expect(home).toBeChecked();expect(model.writes.filter(value=>value.method==='PUT')).toEqual([]);
});

test('saved selections refresh in the background without replacing focused controls',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner),model=await providerFixture(page,owner,{rows:[row]});await open(page);
  const panel=card(page,row.id),home=panel.getByRole('checkbox',{name:'Home',exact:true});await home.check();await panel.getByRole('button',{name:'Save task lists',exact:true}).click();
  await expect(panel.getByText('List selection saved.',{exact:true})).toBeVisible();expect(model.writes.find(value=>value.method==='PUT').input).toEqual({selectedLists:['work','home'],projectMappings:{work:null,home:null},reviewAdditions:true});
  row.version++;row.syncing=false;row.lastSuccess='2026-11-09T12:01:00Z';await home.focus();await page.getByRole('button',{name:'Refresh Google status',exact:true}).evaluate(button=>button.click());
  await expect(home).toBeFocused();await expect(home).toBeChecked();await expect(panel.getByText('Refreshing selected lists…',{exact:true})).toHaveCount(0);
  await panel.getByRole('button',{name:'Disconnect',exact:true}).click();await expect(panel).toHaveCount(0);await expect(page.getByText('Disconnected. Imported tasks remain in Dagsverk.',{exact:true})).toBeVisible();expect(model.writes.filter(value=>value.method==='DELETE')).toHaveLength(1);
});

test('provider status errors are actionable and remain text in all themes and narrow screens',async({page,request})=>{
  test.setTimeout(120000);const owner=await signIn(page,request),row=connection(owner,{name:'Account <img src=x onerror=alert(1)> '+ 'LongName'.repeat(24),lastError:'TASK_PROVIDER_RECONNECT_REQUIRED'});await providerFixture(page,owner,{rows:[row]});await open(page);
  await expect(card(page,row.id).getByText('Reconnect this account and approve its selected calendar and task permissions to resume synchronization.',{exact:true})).toBeVisible();await expect(card(page,row.id).locator('img')).toHaveCount(0);
  for(const theme of ['timekeeper','timekeeper-dark']){
    await openThemeSettings(page);await page.locator('#theme').selectOption(theme);await page.getByRole('link',{name:'Internet accounts',exact:true}).click();await expect(card(page,row.id)).toBeVisible();
    for(const width of [1440,390,320]){await page.setViewportSize({width,height:1000});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#account-page').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);}
  }
  await captureScreenshot(page,{path:'test-results/task-providers-320.png',fullPage:true});
});

test('initial import is reviewed before approval and stale previews retain the displayed comparison',async({page,request})=>{
  const owner=await signIn(page,request),list=randomUUID(),row=connection(owner,{imports:[{id:list,remoteListId:'work',state:'READY',count:3}]});
  const model=await providerFixture(page,owner,{rows:[row]});await open(page);const panel=card(page,row.id);
  await panel.getByRole('button',{name:'Review import for Work',exact:true}).click();
  await expect(panel.getByRole('heading',{name:'Import preview · Work',exact:true})).toBeFocused();
  await expect(panel.getByText('2 new tasks, 1 existing matches, 0 deleted records.',{exact:true})).toBeVisible();await expect(panel.getByText('Source <img src=x onerror=alert(1)>',{exact:true})).toBeVisible();await expect(panel.locator('img')).toHaveCount(0);
  expect(model.writes).toEqual([]);model.previewVersion++;
  await panel.getByRole('button',{name:'Import this list',exact:true}).click();await expect(panel.getByText('Reload current values',{exact:true})).toBeVisible();await expect(panel.getByText('Source <img src=x onerror=alert(1)>',{exact:true})).toBeVisible();expect(row.imports).toHaveLength(1);
  await panel.getByRole('button',{name:'Review import for Work',exact:true}).click();await panel.getByRole('button',{name:'Import this list',exact:true}).click();
  await expect(panel.getByRole('button',{name:'Review import for Work',exact:true})).toHaveCount(0);await expect(panel.getByRole('heading',{name:'Google · Fixture account',exact:true})).toBeFocused();
  expect(model.writes.filter(value=>value.method==='POST')).toHaveLength(2);expect(row.imports).toEqual([]);
});

test('project mappings and list drafts survive pause, resume and status polling',async({page,request})=>{
  const owner=await signIn(page,request),project=randomUUID(),row=connection(owner),model=await providerFixture(page,owner,{rows:[row],projects:[{id:project,name:'Imported work'}]});await open(page);const panel=card(page,row.id);
  const mapping=panel.getByRole('combobox',{name:'Project for Work',exact:true});await mapping.selectOption(project);await panel.getByRole('checkbox',{name:'Home',exact:true}).check();
  await panel.getByRole('button',{name:'Pause synchronization',exact:true}).click();await expect(panel.getByRole('button',{name:'Resume synchronization',exact:true})).toBeVisible();await expect(panel.getByRole('button',{name:'Refresh from provider',exact:true})).toBeDisabled();await expect(mapping).toHaveValue(project);await expect(panel.getByRole('checkbox',{name:'Home',exact:true})).toBeChecked();
  await panel.getByRole('button',{name:'Resume synchronization',exact:true}).click();await expect(panel.getByRole('button',{name:'Pause synchronization',exact:true})).toBeVisible();await expect(mapping).toHaveValue(project);expect(model.writes.map(value=>value.input)).toEqual([{paused:true},{paused:false}]);
  await panel.getByRole('button',{name:'Save task lists',exact:true}).click();await expect(panel.getByText('List selection saved.',{exact:true})).toBeVisible();await expect(mapping).toHaveValue(project);
  expect(model.writes.find(value=>value.path.endsWith('/lists')).input).toEqual({selectedLists:['work','home'],projectMappings:{work:project,home:null},reviewAdditions:true});
});

test('project choice is sent with selected lists',async({page,request})=>{
  const owner=await signIn(page,request),project=randomUUID(),row=connection(owner),model=await providerFixture(page,owner,{rows:[row],projects:[{id:project,name:'Imported work'}]});await open(page);const panel=card(page,row.id);
  await panel.getByRole('combobox',{name:'Project for Work',exact:true}).selectOption(project);await panel.getByRole('button',{name:'Save task lists',exact:true}).click();
  await expect(panel.getByText('List selection saved.',{exact:true})).toBeVisible();
  expect(model.writes.find(value=>value.path.endsWith('/lists')).input).toEqual({selectedLists:['work'],projectMappings:{work:project},reviewAdditions:true});
});

test('read-only sessions can inspect but cannot approve imports',async({page,request})=>{
  const readOwner=await signIn(page,request,{readOnly:true}),readRow=connection(readOwner,{imports:[{id:randomUUID(),remoteListId:'work',state:'READY',count:3}]});const readModel=await providerFixture(page,readOwner,{rows:[readRow]});await open(page);const readonly=card(page,readRow.id);
  await readonly.getByRole('button',{name:'Review import for Work',exact:true}).click();await expect(readonly.getByRole('button',{name:'Import this list',exact:true})).toBeDisabled();expect(readModel.writes).toEqual([]);
});

test('task connections and failure states are localized in Norwegian',async({page,request})=>{
  const owner=await signIn(page,request,{language:'nb-NO'}),row=connection(owner,{lastError:'TASK_PROVIDER_CONFLICTS',pendingCount:2});await providerFixture(page,owner,{rows:[row]});await open(page);
  await expect(page.getByRole('heading',{name:'Google',exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'Koble til Google',exact:true})).toBeVisible();await expect(card(page,row.id).getByText('Noen oppgaver må gjennomgås. Lokale endringer og tidligere importerte oppgaver beholdes. Oppgaver som må gjennomgås: 2.',{exact:true})).toBeVisible();
});

test('Google access defaults to import-only and write consent is an explicit choice',async({page,request})=>{
  const owner=await signIn(page,request),model=await providerFixture(page,owner,{rows:[],authorization:'https://untrusted.example.invalid/collect'});await open(page);
  const access=page.getByRole('combobox',{name:'Task synchronization',exact:true});await expect(access).toHaveValue('IMPORT');
  await access.selectOption('TWO_WAY');await page.getByRole('button',{name:'Connect Google',exact:true}).click();
  await expect(page.getByText('Reload current values',{exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'Connect Google',exact:true})).toBeEnabled();expect(model.writes.find(write=>write.path==='/google/authorize').input).toEqual({direction:'TWO_WAY',services:{tasks:true,calendars:true},language:'en'});
});

test('preparing a change selects a real owned task and retains the selection during status polling',async({page,request})=>{
  const owner=await signIn(page,request),api=browserRequest(page),identity=await(await api.get('/api/v1/auth/session')).json();
  const headers={'X-CSRF-Token':identity.csrf,'Idempotency-Key':randomUUID()};
  const source=await api.post('/api/v1/task-connections',{headers,data:{providerId:'google',remoteAccountId:'fixture-account',name:'Browser Google',readable:true,writable:true,enabled:true}});expect(source.status()).toBe(201);const sourceValue=await source.json();
  const listReply=await api.post('/api/v1/task-lists',{headers:{...headers,'Idempotency-Key':randomUUID()},data:{connectionId:sourceValue.id,remoteListId:'work',name:'Work',kind:'STANDARD',readable:true,writable:true,shared:false,owner:true,present:true,position:0}});expect(listReply.status()).toBe(201);const list=await listReply.json();
  const created=await api.post('/api/v1/tasks',{headers:{...headers,'Idempotency-Key':randomUUID()},data:{summary:'Changed locally',projectId:null,parentTaskId:null,url:null}});expect(created.status()).toBe(201);const task=await created.json();
  const linked=await api.put('/api/v1/tasks/'+task.id+'/external-references',{headers:{...headers,'If-Match':created.headers().etag},data:{providerId:'google',repositoryId:'fixture-account/work',externalId:'task-id',externalUrl:null,taskListId:list.id}});expect(linked.status()).toBe(200);
  const row=connection(owner,{id:sourceValue.id,direction:'TWO_WAY'}),model=await providerFixture(page,owner,{rows:[row],tasks:[await linked.json()],lists:[list]});await open(page);const panel=card(page,row.id);
  await panel.getByText('Prepare a Google change',{exact:true}).click();const taskChoice=panel.getByRole('combobox',{name:'Dagsverk task and Google list',exact:true});await taskChoice.selectOption({label:'Work / Changed locally'});const captured=await taskChoice.inputValue();
  await page.getByRole('button',{name:'Refresh Google status',exact:true}).click();await expect(taskChoice).toHaveValue(captured);await panel.getByRole('button',{name:'Review change',exact:true}).click();
  await expect(panel.getByRole('heading',{name:'Review Google change · Changed locally',exact:true})).toBeVisible();expect(model.writes.find(write=>write.path.endsWith('/writes')).input).toEqual({taskId:task.id,listId:list.id,action:'UPDATE'});expect(model.providerWrites).toBe(0);
});

test('reviewed Google changes retain their comparison during polling and reject stale approval',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner,{direction:'TWO_WAY',pendingWrites:1}),job=writeJob({connectionId:row.id}),model=await providerFixture(page,owner,{rows:[row],jobs:[job]});await open(page);const panel=card(page,row.id);
  await panel.getByRole('button',{name:'Review result',exact:true}).click();await expect(panel.getByRole('heading',{name:'Review Google change · Imported Google task',exact:true})).toBeFocused();
  await expect(panel.getByText('Changed locally',{exact:true})).toBeVisible();await expect(panel.getByText('Notes: Only plain text notes can be sent. These notes stay in Dagsverk.',{exact:true})).toBeVisible();expect(model.providerWrites).toBe(0);
  job.version++;job.writeFields={summary:'A newer proposal'};await page.getByRole('button',{name:'Refresh Google status',exact:true}).click();
  await expect(panel.getByText('Changed locally',{exact:true})).toBeVisible();await expect(panel.getByText('A newer proposal',{exact:true})).toHaveCount(0);
  await panel.getByRole('button',{name:'Send to Google',exact:true}).click();await expect(panel.getByText('Reload current values',{exact:true})).toBeVisible();expect(model.providerWrites).toBe(0);
  await panel.getByRole('button',{name:'Review result',exact:true}).click();await expect(panel.getByText('A newer proposal',{exact:true})).toBeVisible();await panel.getByRole('button',{name:'Send to Google',exact:true}).click();
  await expect(panel.getByText('Imported Google task · Confirmed',{exact:true})).toBeVisible();expect(model.providerWrites).toBe(1);
});

test('read-only Google grants can check and reconcile an uncertain result without resending it',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner,{direction:'IMPORT',pendingWrites:1,services:{tasks:false,calendars:true,writableTasks:false},selectedLists:[]}),job=writeJob({connectionId:row.id,state:'UNKNOWN',canConfirm:false}),model=await providerFixture(page,owner,{rows:[row],jobs:[job]});await open(page);const panel=card(page,row.id);
  await expect(panel.getByText('Prepare a Google change',{exact:true})).toBeHidden();await panel.getByRole('button',{name:'Review result',exact:true}).click();
  await expect(panel.getByRole('button',{name:'Send to Google',exact:true})).toHaveCount(0);await panel.getByRole('button',{name:'Check Google result',exact:true}).click();
  await expect.poll(()=>model.writes.filter(write=>write.path.endsWith('/check')).length).toBe(1);expect(model.providerWrites).toBe(0);
  await panel.getByRole('button',{name:'Review result',exact:true}).click();await expect(panel.getByText('Changed at Google',{exact:true})).toBeVisible();await expect(panel.getByText('<strong>Local notes</strong>',{exact:true})).toBeVisible();await expect(panel.locator('strong')).toHaveCount(0);
  await panel.getByRole('button',{name:'Keep Dagsverk for conflicts',exact:true}).click();await expect(panel.getByText('Imported Google task · Observed values reconciled',{exact:true})).toBeVisible();expect(model.providerWrites).toBe(0);
  const writes=model.writes.filter(write=>write.path.includes('/writes/'));expect(writes.map(write=>write.path.split('/').at(-1))).toEqual(['check','resolve']);
});

test('read-only Dagsverk sessions can review Google results without confirming or reconciling them',async({page,request})=>{
  const owner=await signIn(page,request,{readOnly:true}),row=connection(owner,{direction:'TWO_WAY',pendingWrites:1}),job=writeJob({connectionId:row.id}),model=await providerFixture(page,owner,{rows:[row],jobs:[job]});await open(page);const panel=card(page,row.id);
  await panel.getByRole('button',{name:'Review result',exact:true}).click();await expect(panel.getByRole('button',{name:'Send to Google',exact:true})).toBeDisabled();expect(model.writes).toEqual([]);expect(model.providerWrites).toBe(0);
});

for(const language of ['en-GB','nb-NO'])test('Google write comparisons stay readable and accessible across themes in '+language,async({page,request})=>{
  test.setTimeout(120000);const owner=await signIn(page,request,{language}),row=connection(owner,{direction:'TWO_WAY',pendingWrites:1}),job=writeJob({connectionId:row.id,state:'ACK_REVIEW',canConfirm:false,canResolve:true,conflictFields:['summary'],title:'Imported '+ 'Long task title '.repeat(15),remoteFields:{summary:'Changed at Google',body:{format:'TEXT',content:'Provider notes\n'+ 'LongUnbrokenProviderText'.repeat(40)}},localFields:{summary:'Changed locally',body:{format:'HTML',content:'<img src=x onerror=alert(1)>\nLocal notes stay here'}}});
  const model=await providerFixture(page,owner,{rows:[row],jobs:[job]});await open(page);
  for(const theme of ['timekeeper','timekeeper-dark']){
    await openThemeSettings(page);await page.locator('#theme').selectOption(theme);await page.getByRole('link',{name:language==='nb-NO'?'Internettkontoer':'Internet accounts',exact:true}).click();
    const panel=card(page,row.id);await panel.getByRole('button',{name:language==='nb-NO'?'Gjennomgå resultat':'Review result',exact:true}).click();await expect(panel.locator('img')).toHaveCount(0);
    for(const width of [1440,390,320]){await page.setViewportSize({width,height:1000});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#account-page').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);}
  }
  expect(model.providerWrites).toBe(0);await captureScreenshot(page,{path:'test-results/task-writes-320-'+language+'.png',fullPage:true});
});

test('creating a Google task reviews an existing native task and target list before sending',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner,{direction:'TWO_WAY'}),taskId=randomUUID(),listId=randomUUID();
  const model=await providerFixture(page,owner,{rows:[row],tasks:[{id:taskId,summary:'Native root',parentTaskId:null,externalReferences:[]}],lists:[{id:listId,connectionId:row.id,remoteListId:'work',name:'Work',present:true}]});await open(page);const panel=card(page,row.id);
  await panel.getByText('Prepare a Google change',{exact:true}).click();const action=panel.getByRole('combobox',{name:'Change to review',exact:true});await expect(action).toBeEnabled();await action.selectOption('CREATE');
  await panel.getByRole('combobox',{name:'Dagsverk task and Google list',exact:true}).selectOption({label:'Work / Native root'});await panel.getByRole('button',{name:'Review change',exact:true}).click();
  await expect(panel.getByRole('heading',{name:'Review Google change · Native root',exact:true})).toBeVisible();await expect(panel.getByText('Create a Google task linked to this existing Dagsverk task.',{exact:false})).toBeVisible();
  expect(model.writes.find(write=>write.path.endsWith('/writes')).input).toEqual({taskId,listId,action:'CREATE'});expect(model.providerWrites).toBe(0);
});

test('uncertain creation identity remains an explicit choice and a stale scan preserves the comparison',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner,{direction:'IMPORT',pendingWrites:1}),job=writeJob({connectionId:row.id,action:'CREATE',state:'UNKNOWN',externalId:null,canConfirm:false}),candidates=[{externalId:'first-id',fields:{summary:'Same title',body:{format:'TEXT',content:'First Google task'}}},{externalId:'second-id',fields:{summary:'Same title',body:{format:'TEXT',content:'Second Google task'}}}];
  const model=await providerFixture(page,owner,{rows:[row],jobs:[job],candidates});await open(page);const panel=card(page,row.id);await panel.getByRole('button',{name:'Review result',exact:true}).click();await panel.getByRole('button',{name:'Find unlinked Google tasks',exact:true}).click();
  const choice=panel.getByRole('combobox',{name:'Google identity to link',exact:true}),bind=panel.getByRole('button',{name:'Link this identity; keep Dagsverk fields',exact:true});await expect(choice).toHaveValue('');await expect(bind).toBeDisabled();
  await choice.selectOption('second-id');await expect(panel.getByText('Second Google task',{exact:true})).toBeVisible();model.candidateVersion++;await bind.click();await expect(panel.getByText('Reload current values',{exact:true})).toBeVisible();await expect(choice).toHaveValue('second-id');await expect(panel.getByText('Second Google task',{exact:true})).toBeVisible();expect(job.state).toBe('UNKNOWN');expect(model.providerWrites).toBe(0);
  await panel.getByRole('button',{name:'Find unlinked Google tasks',exact:true}).click();await expect(choice).toHaveValue('');await choice.selectOption('second-id');await bind.click();await expect(panel.getByText('Imported Google task · Observed values reconciled',{exact:true})).toBeVisible();
  expect(model.writes.filter(write=>write.path.endsWith('/identity')).at(-1).input).toEqual({externalId:'second-id',scanEtag:'"scan-2"',taskEtag:'"task-current"'});expect(model.providerWrites).toBe(0);
});

for(const language of ['en-GB','nb-NO'])test('creation identity review fits every theme and remains accessible in '+language,async({page,request})=>{
  test.setTimeout(120000);const owner=await signIn(page,request,{language}),row=connection(owner,{direction:'IMPORT',pendingWrites:1}),job=writeJob({connectionId:row.id,action:'CREATE',state:'UNKNOWN',externalId:null,canConfirm:false,canLeaveUnresolved:true}),remoteId='google-identity-'.repeat(60);
  await providerFixture(page,owner,{rows:[row],jobs:[job],candidates:[{externalId:remoteId,fields:{summary:'A very long task title '.repeat(30),body:{format:'TEXT',content:'Google source notes '.repeat(10)}}}]});await open(page);
  const nb=language==='nb-NO',nav=nb?'Internettkontoer':'Internet accounts',review=nb?'Gjennomgå resultat':'Review result',find=nb?'Finn Google-oppgaver uten kobling':'Find unlinked Google tasks',label=nb?'Google-identitet som skal kobles':'Google identity to link';
  for(const theme of ['timekeeper','timekeeper-dark']){
    await openThemeSettings(page);await page.locator('#theme').selectOption(theme);await page.getByRole('link',{name:nav,exact:true}).click();const panel=card(page,row.id);await panel.getByRole('button',{name:review,exact:true}).click();await panel.getByRole('button',{name:find,exact:true}).click();await panel.getByRole('combobox',{name:label,exact:true}).selectOption(remoteId);await expect(panel.getByText(remoteId,{exact:true})).toBeVisible();
    for(const width of [1440,390,320]){await page.setViewportSize({width,height:1100});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#account-page').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);}
  }
  await captureScreenshot(page,{path:'test-results/task-create-320-'+language+'.png',fullPage:true});
});

test('authorization responses cannot navigate to an unrelated host',async({page,request})=>{
  const owner=await signIn(page,request);await providerFixture(page,owner,{rows:[],authorization:'https://untrusted.example.invalid/collect'});await open(page);
  await page.getByRole('button',{name:'Connect Google',exact:true}).click();await expect(page.getByText('Reload current values',{exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'Connect Google',exact:true})).toBeEnabled();expect(new URL(page.url()).pathname).toBe('/web/');
});

test('callback removes provider codes from history and referrers before completing the owned connection',async({page,request})=>{
  const owner=await signIn(page,request),model=await providerFixture(page,owner);
  const callback=new URL('/web/task-provider-callback.html?'+new URLSearchParams({state,code}),process.env.TIMEKEEPER_WEB_URL).href;
  await page.route('https://accounts.google.com/o/oauth2/v2/auth*',route=>route.fulfill({status:200,contentType:'text/html',body:'<!doctype html><html lang="en"><head><title>Provider fixture</title></head><body><a href="'+callback.replaceAll('&','&amp;').replaceAll('"','&quot;')+'">Continue</a></body></html>'}));
  await page.goto('https://accounts.google.com/o/oauth2/v2/auth?fixture=1');await page.getByRole('link',{name:'Continue',exact:true}).click();await expect(page).toHaveURL(/\/web\/#account\/task-providers$/);await expect(page.getByRole('heading',{name:'Google',exact:true})).toBeVisible();expect(model.completed).toBe(1);
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

function incomingReview(overrides={}){
  return {taskId:randomUUID(),taskEtag:'"task-4"',externalId:'google-task-id',status:'CONFLICT',deleted:false,canResolve:true,canDelete:false,decisions:[{field:'summary',outcome:'CONFLICT'},{field:'body',outcome:'REMOTE_ONLY'}],localFields:{summary:'Local title',body:{format:'HTML',content:'<img src=x onerror=alert(1)> Local draft'}},remoteFields:{summary:'Google title',body:{format:'TEXT',content:'Google notes'}},...overrides};
}
test('incoming comparisons survive polling and stale decisions cannot replace the captured task',async({page,request})=>{
  const owner=await signIn(page,request),listId=randomUUID(),row=connection(owner,{reviews:[{listId,remoteListId:'work'}],pendingCount:1,lastError:'TASK_PROVIDER_CONFLICTS'}),review=incomingReview(),model=await providerFixture(page,owner,{rows:[row],reviews:[review]});await open(page);const panel=card(page,row.id);
  await panel.getByRole('button',{name:'Review incoming changes: Work',exact:true}).click();await expect(panel.getByText('Local title',{exact:true}).first()).toBeVisible();await expect(panel.getByText('Google title',{exact:true})).toBeVisible();await expect(panel.locator('img')).toHaveCount(0);
  model.reviews[0].remoteFields.summary='New Google observation';model.reviewVersion++;await page.getByRole('button',{name:'Refresh Google status',exact:true}).click();await expect(panel.getByText('Google title',{exact:true})).toBeVisible();
  await panel.getByRole('button',{name:'Use Google Tasks for conflicts',exact:true}).click();await expect(panel.getByText(/These records changed or conflict/)).toBeVisible();expect(model.reviews).toHaveLength(1);
  await panel.getByRole('button',{name:'Review incoming changes: Work',exact:true}).click();await expect(panel.getByText('New Google observation',{exact:true})).toBeVisible();await panel.getByRole('button',{name:'Keep Dagsverk for conflicts',exact:true}).click();await expect(panel.getByRole('button',{name:'Keep Dagsverk for conflicts',exact:true})).toHaveCount(0);expect(model.providerWrites).toBe(0);expect(model.writes.at(-1).input).toEqual({decision:'LOCAL',taskEtag:'"task-4"'});
});
test('incoming deletion reviews offer explicit native deletion without a provider write',async({page,request})=>{
  const owner=await signIn(page,request),listId=randomUUID(),row=connection(owner,{reviews:[{listId,remoteListId:'work'}]}),review=incomingReview({deleted:true,status:'DELETE_REVIEW',canDelete:true,decisions:[],remoteFields:{}}),model=await providerFixture(page,owner,{rows:[row],reviews:[review]});await open(page);const panel=card(page,row.id);
  await panel.getByRole('button',{name:'Review incoming changes: Work',exact:true}).click();await expect(panel.getByRole('button',{name:'Keep the Dagsverk task',exact:true})).toBeEnabled();await panel.getByRole('button',{name:'Delete the Dagsverk task',exact:true}).click();expect(model.writes.at(-1).input.decision).toBe('DELETE');expect(model.providerWrites).toBe(0);
});
test('read-only Dagsverk sessions can inspect incoming conflicts without resolving them',async({page,request})=>{
  const owner=await signIn(page,request,{readOnly:true}),listId=randomUUID(),row=connection(owner,{reviews:[{listId,remoteListId:'work'}]});await providerFixture(page,owner,{rows:[row],reviews:[incomingReview()]});await open(page);const panel=card(page,row.id);
  await panel.getByRole('button',{name:'Review incoming changes: Work',exact:true}).click();await expect(panel.getByRole('button',{name:'Keep Dagsverk for conflicts',exact:true})).toBeDisabled();await expect(panel.getByRole('button',{name:'Use Google Tasks for conflicts',exact:true})).toBeDisabled();
});
for(const language of ['en-GB','nb-NO'])test('incoming review remains readable in every theme and narrow layout in '+language,async({page,request})=>{
  test.setTimeout(120000);const owner=await signIn(page,request,{language}),listId=randomUUID(),row=connection(owner,{reviews:[{listId,remoteListId:'work'}]}),review=incomingReview({externalId:'google-id-'.repeat(90),localFields:{summary:'Local '+ 'Task title '.repeat(20),body:{format:'HTML',content:'<b>Local draft</b>'+ 'unbroken'.repeat(80)}},remoteFields:{summary:'Google '+ 'Task title '.repeat(20),body:{format:'TEXT',content:'Google notes '+ 'unbroken'.repeat(80)}}});await providerFixture(page,owner,{rows:[row],reviews:[review]});await open(page);
  const nb=language==='nb-NO';for(const theme of ['timekeeper','timekeeper-dark']){
    await openThemeSettings(page);await page.locator('#theme').selectOption(theme);await page.getByRole('link',{name:nb?'Internettkontoer':'Internet accounts',exact:true}).click();const panel=card(page,row.id);await panel.getByRole('button',{name:nb?'Gjennomgå innkommende endringer: Work':'Review incoming changes: Work',exact:true}).click();
    for(const width of [1440,390,320]){await page.setViewportSize({width,height:1000});await expect(panel.getByRole('button',{name:nb?'Behold Dagsverk ved konflikter':'Keep Dagsverk for conflicts',exact:true})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}
  }
  const accessibility=await new AxeBuilder({page}).include('[data-task-provider-id="'+row.id+'"]').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(accessibility.violations).toEqual([]);await captureScreenshot(page,{path:'test-results/task-incoming-review-'+language+'.png',fullPage:true});
});

test('unidentified Google creation needs explicit acknowledgement before releasing imports',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner,{direction:'TWO_WAY',pendingWrites:1}),job=writeJob({connectionId:row.id,action:'CREATE',state:'UNKNOWN',externalId:null,canConfirm:false,canLeaveUnresolved:true}),model=await providerFixture(page,owner,{rows:[row],jobs:[job]});await open(page);const panel=card(page,row.id);await panel.getByRole('button',{name:'Review result',exact:true}).click();
  const release=panel.getByRole('button',{name:'Allow imports without linking',exact:true});await expect(release).toBeDisabled();await expect(panel.getByText(/does not cancel the original Google request/)).toBeVisible();
  await panel.getByRole('checkbox',{name:'I understand that a Google result may appear as a separate imported task.',exact:true}).check();await expect(release).toBeEnabled();await release.click();expect(model.providerWrites).toBe(0);expect(job.state).toBe('UNRESOLVED');await panel.getByRole('button',{name:'Review result',exact:true}).click();await expect(panel.getByText(/Its outcome remains unknown/)).toBeVisible();await expect(panel.getByRole('button',{name:'Allow imports without linking',exact:true})).toHaveCount(0);
});
test('a paused or disconnected task account can settle unknown creation without provider access',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner,{direction:'IMPORT',pendingWrites:1,enabled:false,paused:true,selectedLists:[],availableLists:[]}),job=writeJob({connectionId:row.id,action:'CREATE',state:'UNKNOWN',externalId:null,canConfirm:false,canLeaveUnresolved:true}),model=await providerFixture(page,owner,{rows:[row],jobs:[job]});await open(page);const panel=card(page,row.id);await panel.getByRole('button',{name:'Review result',exact:true}).click();await expect(panel.getByRole('button',{name:'Check Google result',exact:true})).toBeDisabled();await panel.getByRole('checkbox',{name:'I understand that a Google result may appear as a separate imported task.',exact:true}).check();await panel.getByRole('button',{name:'Allow imports without linking',exact:true}).click();expect(job.state).toBe('UNRESOLVED');expect(model.providerWrites).toBe(0);
});
test('read-only Dagsverk sessions cannot acknowledge or release unidentified creation',async({page,request})=>{
  const owner=await signIn(page,request,{readOnly:true}),row=connection(owner,{direction:'IMPORT',pendingWrites:1}),job=writeJob({connectionId:row.id,action:'CREATE',state:'UNKNOWN',externalId:null,canConfirm:false,canLeaveUnresolved:true});await providerFixture(page,owner,{rows:[row],jobs:[job]});await open(page);const panel=card(page,row.id);await panel.getByRole('button',{name:'Review result',exact:true}).click();await expect(panel.getByRole('checkbox',{name:'I understand that a Google result may appear as a separate imported task.',exact:true})).toBeDisabled();await expect(panel.getByRole('button',{name:'Allow imports without linking',exact:true})).toBeDisabled();
});

function identityReview(overrides={}){return {externalId:'new-source-id',taskId:null,fields:{summary:'Same title',body:{format:'TEXT',content:'Provider notes'},status:'NOT_STARTED',scheduledDate:null,completedAt:null},canImport:true,canReplace:true,blockedReason:'',...overrides};}
function identityTasks(connectionId){const oldList=randomUUID(),newList=randomUUID(),project=randomUUID(),task={id:randomUUID(),projectId:project,summary:'Same title',body:{format:'HTML',content:'<p>Native notes stay</p>'},status:'NOT_STARTED',externalReferences:[{providerId:'google',repositoryId:'fixture-account/work',externalId:'old-source-id',taskListId:oldList}]};return {task,oldList,newList,project,lists:[{id:oldList,connectionId,remoteListId:'work',name:'Work',present:true,readable:true},{id:newList,connectionId,remoteListId:'home',name:'Home',present:true,readable:true}]};}
test('new Google identity review never chooses a same-title native task without an explicit selection',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner,{selectedLists:['work','home']}),fixture=identityTasks(row.id),other={...fixture.task,id:randomUUID(),externalReferences:[{...fixture.task.externalReferences[0],externalId:'different-old-id'}]};row.identityReviews=[{listId:fixture.newList,remoteListId:'home',version:1}];const model=await providerFixture(page,owner,{rows:[row],tasks:[fixture.task,other],lists:fixture.lists,projects:[{id:fixture.project,name:'Original project'}],identities:[identityReview()]});await open(page);const panel=card(page,row.id);
  await panel.getByRole('button',{name:'Review new tasks: Home',exact:true}).click();const choose=panel.getByRole('combobox',{name:'Existing Dagsverk task and source link',exact:true}),replace=panel.getByRole('button',{name:'Use the existing Dagsverk task',exact:true});await expect(choose).toHaveValue('');await expect(replace).toBeDisabled();
  await choose.selectOption(JSON.stringify({taskId:fixture.task.id,previous:{providerId:'google',repositoryId:'fixture-account/work',externalId:'old-source-id'}}));await expect(panel.getByText('Retained project: Original project',{exact:true})).toBeVisible();await expect(replace).toBeDisabled();await panel.getByRole('checkbox',{name:'Retain this Dagsverk task and replace only the selected source link.',exact:true}).check();await expect(replace).toBeEnabled();await choose.selectOption(JSON.stringify({taskId:other.id,previous:{providerId:'google',repositoryId:'fixture-account/work',externalId:'different-old-id'}}));await expect(panel.getByRole('checkbox',{name:'Retain this Dagsverk task and replace only the selected source link.',exact:true})).not.toBeChecked();await expect(replace).toBeDisabled();await choose.selectOption(JSON.stringify({taskId:fixture.task.id,previous:{providerId:'google',repositoryId:'fixture-account/work',externalId:'old-source-id'}}));await expect(panel.getByText('Retained project: Original project',{exact:true})).toBeVisible();await expect(replace).toBeDisabled();await panel.getByRole('checkbox',{name:'Retain this Dagsverk task and replace only the selected source link.',exact:true}).check();await replace.click();expect(model.writes.at(-1).input).toEqual({decision:'REPLACE',externalId:'new-source-id',taskId:fixture.task.id,taskEtag:'"native-1"',previous:{providerId:'google',repositoryId:'fixture-account/work',externalId:'old-source-id'}});expect(model.providerWrites).toBe(0);
});
test('stale identity choices keep the captured native comparison and require a fresh selection',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner,{selectedLists:['work','home']}),fixture=identityTasks(row.id);row.identityReviews=[{listId:fixture.newList,remoteListId:'home',version:1}];const model=await providerFixture(page,owner,{rows:[row],tasks:[fixture.task],lists:fixture.lists,identities:[identityReview()]});await open(page);const panel=card(page,row.id);await panel.getByRole('button',{name:'Review new tasks: Home',exact:true}).click();await panel.getByRole('combobox',{name:'Existing Dagsverk task and source link',exact:true}).selectOption(JSON.stringify({taskId:fixture.task.id,previous:{providerId:'google',repositoryId:'fixture-account/work',externalId:'old-source-id'}}));await expect(panel.getByText('<p>Native notes stay</p>',{exact:true})).toBeVisible();await panel.getByRole('checkbox',{name:'Retain this Dagsverk task and replace only the selected source link.',exact:true}).check();model.nativeVersion++;await panel.getByRole('button',{name:'Use the existing Dagsverk task',exact:true}).click();await expect(panel.getByText(/These records changed or conflict/)).toBeVisible();await expect(panel.getByText('<p>Native notes stay</p>',{exact:true})).toBeVisible();expect(model.identities).toHaveLength(1);expect(model.providerWrites).toBe(0);
});
test('new identities can be imported separately and review mode is an explicit saved setting',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner),listId=randomUUID();row.identityReviews=[{listId,remoteListId:'work',version:1}];const model=await providerFixture(page,owner,{rows:[row],identities:[identityReview()]});await open(page);const panel=card(page,row.id);const mode=panel.getByRole('checkbox',{name:'Review newly found Google tasks before importing (recommended)',exact:true});await expect(mode).toBeChecked();await panel.getByRole('button',{name:'Review new tasks: Work',exact:true}).click();await panel.getByRole('button',{name:'Import as a new task',exact:true}).click();expect(model.writes.at(-1).input).toEqual({decision:'IMPORT',externalId:'new-source-id'});await expect(panel.getByRole('button',{name:'Review new tasks: Work',exact:true})).not.toBeVisible();await mode.uncheck();await panel.getByRole('button',{name:'Save task lists',exact:true}).click();await expect(panel.getByText('List selection saved.',{exact:true})).toBeVisible();await expect(mode).not.toBeChecked();expect(model.writes.find(item=>item.path.endsWith('/lists')).input).toEqual({selectedLists:['work'],projectMappings:{work:null},reviewAdditions:false});expect(model.providerWrites).toBe(0);
});
test('read-only native sessions can inspect new identities but cannot import or replace them',async({page,request})=>{
  const owner=await signIn(page,request,{readOnly:true}),row=connection(owner,{selectedLists:['work','home']}),fixture=identityTasks(row.id);row.identityReviews=[{listId:fixture.newList,remoteListId:'home',version:1}];await providerFixture(page,owner,{rows:[row],tasks:[fixture.task],lists:fixture.lists,identities:[identityReview()]});await open(page);const panel=card(page,row.id);await panel.getByRole('button',{name:'Review new tasks: Home',exact:true}).click();await expect(panel.getByRole('button',{name:'Import as a new task',exact:true})).toBeDisabled();await expect(panel.getByRole('button',{name:'Use the existing Dagsverk task',exact:true})).toBeDisabled();await expect(panel.getByRole('checkbox',{name:'Retain this Dagsverk task and replace only the selected source link.',exact:true})).toBeDisabled();
});
for(const language of ['en-GB','nb-NO'])test('identity reconciliation fits all themes and narrow layouts in '+language,async({page,request})=>{
  test.setTimeout(120000);const owner=await signIn(page,request,{language}),row=connection(owner,{selectedLists:['work','home']}),fixture=identityTasks(row.id);fixture.task.summary='Native '+ 'Long task title '.repeat(12);row.identityReviews=[{listId:fixture.newList,remoteListId:'home',version:1}];await providerFixture(page,owner,{rows:[row],tasks:[fixture.task],lists:fixture.lists,identities:[identityReview({externalId:'observed-long-id-'.repeat(50),fields:{summary:'Google '+ 'Long task title '.repeat(12),body:{format:'TEXT',content:'Source notes '+ 'unbroken'.repeat(80)}}})]});await open(page);const nb=language==='nb-NO';
  for(const theme of ['timekeeper','timekeeper-dark']){await openThemeSettings(page);await page.locator('#theme').selectOption(theme);await page.getByRole('link',{name:nb?'Internettkontoer':'Internet accounts',exact:true}).click();const panel=card(page,row.id);await panel.getByRole('button',{name:nb?'Gjennomgå nye oppgaver: Home':'Review new tasks: Home',exact:true}).click();await panel.getByRole('combobox',{name:nb?'Eksisterende Dagsverk-oppgave og kildekobling':'Existing Dagsverk task and source link',exact:true}).selectOption(JSON.stringify({taskId:fixture.task.id,previous:{providerId:'google',repositoryId:'fixture-account/work',externalId:'old-source-id'}}));await expect(panel.getByText('<p>Native notes stay</p>',{exact:true})).toBeVisible();await expect(panel.getByText(nb?'Ikke startet':'Not started',{exact:true})).toBeVisible();for(const width of [1440,390,320]){await page.setViewportSize({width,height:1000});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}}
  const accessibility=await new AxeBuilder({page}).include('[data-task-provider-id="'+row.id+'"]').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(accessibility.violations).toEqual([]);await captureScreenshot(page,{path:'test-results/task-identities-'+language+'.png',fullPage:true});
});


test('connection readiness disables only new authorization and explicit recheck recovers',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner),model=await providerFixture(page,owner,{rows:[row],providers:{google:false,microsoft:false},readiness:{google:'TASK_PROVIDER_CONNECTION_UNAVAILABLE',microsoft:'TASK_PROVIDER_NOT_CONFIGURED'}});await open(page);
  await expect(page.getByRole('button',{name:'Connect Google',exact:true})).toBeDisabled();await expect(page.getByRole('button',{name:'Connect Microsoft',exact:true})).toBeDisabled();
  await expect(card(page,row.id).getByRole('button',{name:'Save task lists',exact:true})).toBeEnabled();await expect(card(page,row.id).getByRole('button',{name:'Refresh from provider',exact:true})).toBeEnabled();
  await expect(page.locator('[data-internet-provider=google]')).toContainText('connection service is unavailable');
  await expect(page.locator('#account-content')).not.toContainText('client ID');await expect(page.locator('#account-content')).not.toContainText('registration (');
  model.providers.google=true;model.readiness.google='READY';await page.getByRole('button',{name:'Refresh Google status',exact:true}).click();await expect(page.getByRole('button',{name:'Connect Google',exact:true})).toBeEnabled();
  await expect(page.getByRole('button',{name:'Connect Microsoft',exact:true})).toBeDisabled();expect(model.writes).toEqual([]);
});

test('OAuth initialization failures keep service choices and connected account writes usable',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner),model=await providerFixture(page,owner,{rows:[row],authorizationFailure:{status:503,code:'TASK_PROVIDER_CONNECTION_UNAVAILABLE'}});await open(page);
  const google=page.locator('[data-internet-provider=google]'),connect=page.getByRole('button',{name:'Connect Google',exact:true});
  for(const code of ['TASK_PROVIDER_CONNECTION_UNAVAILABLE','TASK_PROVIDER_NOT_CONFIGURED','TASK_PROVIDER_PUBLIC_ORIGIN_REQUIRED']){
    model.authorizationFailure.code=code;await connect.click();await expect(connect).toBeEnabled();await expect(google.locator('fieldset').first().getByRole('checkbox',{name:'Calendars',exact:true})).toBeEnabled();
    await expect(page.getByRole('button',{name:'Reload current values',exact:true})).toHaveCount(0);await expect(page.locator('#account-content')).not.toContainText('The outcome is unknown');await expect(card(page,row.id).getByRole('button',{name:'Save task lists',exact:true})).toBeEnabled();
  }
  await card(page,row.id).getByRole('checkbox',{name:'Home',exact:true}).check();await card(page,row.id).getByRole('button',{name:'Save task lists',exact:true}).click();await expect(card(page,row.id)).toContainText('List selection saved.');
});

for(const language of ['en-GB','nb-NO'])test(`connected Internet accounts show compact selections and optional connection settings in ${language}`,async({page,request})=>{
  test.setTimeout(120000);const owner=await signIn(page,request,{language}),clients=[{id:randomUUID(),name:'First client'},{id:randomUUID(),name:'Second client'}],project={id:randomUUID(),name:'Imported work'},row=connection(owner,{direction:'TWO_WAY',services:{tasks:true,calendars:true,writableTasks:true},selectedLists:[],availableCalendars:[{id:'team',name:'Team calendar',readable:true},{id:'personal',name:'Personal calendar',readable:true}],selectedCalendars:[],calendarMappings:{},calendarConfigurationVersion:1});
  const model=await providerFixture(page,owner,{rows:[row],providers:{google:true,microsoft:false},clients,projects:[project]});await open(page,{expanded:false});const panel=card(page,row.id),nb=language==='nb-NO';
  await expect(page.getByRole('heading',{name:nb?'Internettkontoer':'Internet accounts',exact:true})).toHaveCount(0);
  await expect(page.getByRole('link',{name:nb?'Åpne administrasjon av internettkontoer':'Open Internet account administration',exact:true})).toHaveCount(0);
  expect(await page.locator('[data-provider-connect=google]').evaluate(value=>value.open)).toBe(false);expect(await panel.locator('[data-connection-settings]').evaluate(value=>value.open)).toBe(false);
  await expect(panel.getByRole('heading',{name:nb?'Endringer i Google Tasks':'Changes to Google Tasks',exact:true})).toBeHidden();
  await page.setViewportSize({width:1440,height:1000});expect((await panel.boundingBox()).height).toBeLessThan(620);
  const calendar=panel.getByRole('checkbox',{name:'Team calendar',exact:true}),task=panel.getByRole('checkbox',{name:'Work',exact:true}),clientChoice=panel.getByRole('combobox',{name:nb?'Kunde for Team calendar':'Client for Team calendar',exact:true}),projectChoice=panel.getByRole('combobox',{name:nb?'Prosjekt for Work':'Project for Work',exact:true});
  await expect(clientChoice).toBeHidden();await expect(projectChoice).toBeHidden();await calendar.check();await task.check();await clientChoice.selectOption(clients[1].id);await projectChoice.selectOption(project.id);
  await calendar.uncheck();await task.uncheck();await expect(clientChoice).toBeHidden();await expect(projectChoice).toBeHidden();await calendar.check();await task.check();await expect(clientChoice).toHaveValue(clients[1].id);await expect(projectChoice).toHaveValue(project.id);
  await page.locator('[data-provider-connect=google] > summary').click();await page.getByRole('button',{name:nb?'Oppdater status for Google':'Refresh Google status',exact:true}).click();await expect(clientChoice).toHaveValue(clients[1].id);await expect(projectChoice).toHaveValue(project.id);await page.locator('[data-provider-connect=google] > summary').click();
  for(const width of [1440,390,320]){await page.setViewportSize({width,height:1000});for(const theme of ['timekeeper','timekeeper-dark']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);await captureScreenshot(page,{path:`test-results/internet-accounts-compact-${language}-${theme}-${width}.png`,fullPage:true});
  }}
  await panel.getByRole('button',{name:nb?'Lagre kalendere':'Save calendars',exact:true}).click();await expect.poll(()=>row.selectedCalendars).toEqual(['team']);await expect(projectChoice).toHaveValue(project.id);
  await panel.getByRole('button',{name:nb?'Lagre oppgavelister':'Save task lists',exact:true}).click();await expect.poll(()=>row.selectedLists).toEqual(['work']);expect(row.calendarMappings.team).toBe(clients[1].id);expect(row.projectMappings.work).toBe(project.id);expect(model.providerWrites).toBe(0);
});
