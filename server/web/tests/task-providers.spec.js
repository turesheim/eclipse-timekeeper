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
  return {id:randomUUID(),ownerId:owner,providerId:'google',remoteAccountId:'fixture-account',name:'Fixture account',enabled:true,version:1,configurationVersion:1,direction:'IMPORT',paused:false,imports:[],projectMappings:{},syncing:false,pendingCount:0,listErrors:{},lastAttempt:null,lastSuccess:null,nextRefresh:'2026-11-09T12:00:00Z',lastError:null,
    selectedLists:['work'],availableLists:[{id:'work',name:'Work',kind:'STANDARD',shared:false,owner:true},{id:'home',name:'Home',kind:'STANDARD',shared:false,owner:true}],...overrides};
}
async function providerFixture(page,owner,{rows=[connection(owner)],providers={google:true,microsoft:true},authorization=null,projects=[],tasks=[],lists=[],jobs=[]}={}){
  const model={rows,providers,writes:[],reads:0,completed:0,cancelled:0,previewVersion:3,jobs,providerWrites:0};
  for(const [path,items]of [['projects',projects],['tasks',tasks],['task-lists',lists]])if(items.length)await page.route('**/api/v1/'+path+'?*',async route=>{
    const response=await route.fetch();if(!response.ok())return route.fulfill({response});
    const current=await response.json();return route.fulfill({response,body:JSON.stringify({...current,items:items.map(item=>({ownerId:owner,version:0,...item})),nextCursor:null})});
  });
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
    if(path.includes('/writes')){
      if(path.endsWith('/writes')){
        if(method==='GET')return reply({items:model.jobs.map(({id,title,action,state,createdAt,lastError})=>({id,title,action,state,createdAt,lastError}))});
        if(method==='POST'){const value=writeJob({connectionId:row.id,taskId:input.taskId,listId:input.listId,action:input.action,title:tasks.find(task=>task.id===input.taskId)?.summary||'Imported Google task'});model.jobs.push(value);row.pendingWrites=model.jobs.length;return reply(value,201);}
      }
      const job=model.jobs.find(job=>path.includes('/writes/'+job.id));if(!job)return reply({type:'about:blank',title:'Not found',status:404,code:'NOT_FOUND'},404);
      if(method==='GET')return reply(job);
      if(request.headers()['if-match']!==`"provider-${job.version}"`)return reply({type:'about:blank',title:'Stale version',status:412,code:'STALE_VERSION'},412);
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
    if(method==='PUT'&&path.endsWith('/lists')){expect(request.headers()['if-match']).toBe(`"provider-${row.version}"`);row.selectedLists=input.selectedLists;row.projectMappings=input.projectMappings;row.version++;row.configurationVersion++;row.syncing=true;return reply(row);}
    if(method==='PUT'&&path.endsWith('/state')){expect(request.headers()['if-match']).toBe(`"provider-${row.version}"`);row.paused=input.paused;row.version++;return reply(row);}
    if(method==='DELETE'){model.rows=model.rows.filter(value=>value!==row);return reply(null,204);}
    if(method==='POST'&&path.endsWith('/refresh')){row.version++;row.lastSuccess='2026-11-09T12:01:00Z';return reply(row,202);}
    throw new Error('Unexpected task provider fixture request');
  });return model;
}
function writeJob(overrides={}){
  return {id:randomUUID(),version:1,connectionId:randomUUID(),listId:randomUUID(),taskId:randomUUID(),title:'Imported Google task',action:'UPDATE',state:'PREVIEW',createdAt:'2026-11-10T00:00:00Z',expiresAt:'2026-11-10T00:15:00Z',lastError:null,writeFields:{summary:'Changed locally'},warnings:[{field:'body',reason:'TEXT_ONLY'}],canConfirm:true,currentTaskEtag:'"task-current"',localFields:{summary:'Changed locally',body:{format:'HTML',content:'<strong>Local notes</strong>'}},remoteFields:null,remoteObservedAt:null,resolution:null,canResolve:false,conflictFields:[],...overrides};
}
async function open(page){await page.goto('/web/#account/task-providers');await expect(page.locator('#account-content [data-task-provider-connect="google"]')).toBeVisible();}
const card=(page,id)=>page.locator(`[data-task-provider-id="${id}"]`);
test.afterEach(async({request})=>{await locale(request,'en-GB');});

test('provider setup availability and read-only sessions do not acquire write controls',async({page,request})=>{
  const owner=await signIn(page,request,{readOnly:true}),row=connection(owner),model=await providerFixture(page,owner,{rows:[row],providers:{google:true,microsoft:false}});await open(page);
  await expect(page.getByRole('heading',{name:'Task connections',exact:true})).toBeVisible();
  for(const name of ['Connect Google Tasks','Connect Microsoft To Do','Save','Refresh from provider','Pause synchronization','Reconnect','Disconnect'])await expect(page.getByRole('button',{name,exact:true})).toBeDisabled();
  await expect(card(page,row.id).getByRole('checkbox',{name:'Work',exact:true})).toBeDisabled();await expect(page.getByRole('button',{name:'Refresh status',exact:true})).toBeEnabled();
  await expect(page.getByText('Unavailable task providers must be enabled by the server administrator.',{exact:true})).toBeVisible();expect(model.writes).toEqual([]);
});

test('list drafts survive status updates and stale saves keep the captured selection',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner),model=await providerFixture(page,owner,{rows:[row]});await open(page);
  const panel=card(page,row.id),home=panel.getByRole('checkbox',{name:'Home',exact:true});await home.check();row.version++;row.configurationVersion++;row.syncing=true;
  await page.getByRole('button',{name:'Refresh status',exact:true}).click();await expect(home).toBeChecked();await expect(panel.getByText('Refreshing selected lists…',{exact:true})).toBeVisible();
  await panel.getByRole('button',{name:'Save',exact:true}).click();await expect(panel.getByText('Reload current values',{exact:true})).toBeVisible();await expect(home).toBeChecked();expect(model.writes.filter(value=>value.method==='PUT')).toEqual([]);
});

test('saved selections refresh in the background without replacing focused controls',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner),model=await providerFixture(page,owner,{rows:[row]});await open(page);
  const panel=card(page,row.id),home=panel.getByRole('checkbox',{name:'Home',exact:true});await home.check();await panel.getByRole('button',{name:'Save',exact:true}).click();
  await expect(panel.getByText('List selection saved.',{exact:true})).toBeVisible();expect(model.writes.find(value=>value.method==='PUT').input).toEqual({selectedLists:['work','home'],projectMappings:{work:null,home:null}});
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

test('initial import is reviewed before approval and stale previews retain the displayed comparison',async({page,request})=>{
  const owner=await signIn(page,request),list=randomUUID(),row=connection(owner,{imports:[{id:list,remoteListId:'work',state:'READY',count:3}]});
  const model=await providerFixture(page,owner,{rows:[row]});await open(page);const panel=card(page,row.id);
  await panel.getByRole('button',{name:'Review import for Work',exact:true}).click();
  await expect(panel.getByRole('heading',{name:'Import preview · Work',exact:true})).toBeFocused();
  await expect(panel.getByText('2 new tasks, 1 existing matches, 0 deleted records.',{exact:true})).toBeVisible();await expect(panel.getByText('Source <img src=x onerror=alert(1)>',{exact:true})).toBeVisible();await expect(panel.locator('img')).toHaveCount(0);
  expect(model.writes).toEqual([]);model.previewVersion++;
  await panel.getByRole('button',{name:'Import this list',exact:true}).click();await expect(panel.getByText('Reload current values',{exact:true})).toBeVisible();await expect(panel.getByText('Source <img src=x onerror=alert(1)>',{exact:true})).toBeVisible();expect(row.imports).toHaveLength(1);
  await panel.getByRole('button',{name:'Review import for Work',exact:true}).click();await panel.getByRole('button',{name:'Import this list',exact:true}).click();
  await expect(panel.getByRole('button',{name:'Review import for Work',exact:true})).toHaveCount(0);await expect(panel.getByRole('heading',{name:'Google Tasks · Fixture account',exact:true})).toBeFocused();
  expect(model.writes.filter(value=>value.method==='POST')).toHaveLength(2);expect(row.imports).toEqual([]);
});

test('project mappings and list drafts survive pause, resume and status polling',async({page,request})=>{
  const owner=await signIn(page,request),project=randomUUID(),row=connection(owner),model=await providerFixture(page,owner,{rows:[row],projects:[{id:project,name:'Imported work'}]});await open(page);const panel=card(page,row.id);
  const mapping=panel.getByRole('combobox',{name:'Project for Work',exact:true});await mapping.selectOption(project);await panel.getByRole('checkbox',{name:'Home',exact:true}).check();
  await panel.getByRole('button',{name:'Pause synchronization',exact:true}).click();await expect(panel.getByRole('button',{name:'Resume synchronization',exact:true})).toBeVisible();await expect(panel.getByRole('button',{name:'Refresh from provider',exact:true})).toBeDisabled();await expect(mapping).toHaveValue(project);await expect(panel.getByRole('checkbox',{name:'Home',exact:true})).toBeChecked();
  await panel.getByRole('button',{name:'Resume synchronization',exact:true}).click();await expect(panel.getByRole('button',{name:'Pause synchronization',exact:true})).toBeVisible();await expect(mapping).toHaveValue(project);expect(model.writes.map(value=>value.input)).toEqual([{paused:true},{paused:false}]);
  await panel.getByRole('button',{name:'Save',exact:true}).click();await expect(panel.getByText('List selection saved.',{exact:true})).toBeVisible();await expect(mapping).toHaveValue(project);
  expect(model.writes.find(value=>value.path.endsWith('/lists')).input).toEqual({selectedLists:['work','home'],projectMappings:{work:project,home:null}});
});

test('project choice is sent with selected lists',async({page,request})=>{
  const owner=await signIn(page,request),project=randomUUID(),row=connection(owner),model=await providerFixture(page,owner,{rows:[row],projects:[{id:project,name:'Imported work'}]});await open(page);const panel=card(page,row.id);
  await panel.getByRole('combobox',{name:'Project for Work',exact:true}).selectOption(project);await panel.getByRole('button',{name:'Save',exact:true}).click();
  await expect(panel.getByText('List selection saved.',{exact:true})).toBeVisible();
  expect(model.writes.find(value=>value.path.endsWith('/lists')).input).toEqual({selectedLists:['work'],projectMappings:{work:project}});
});

test('read-only sessions can inspect but cannot approve imports',async({page,request})=>{
  const readOwner=await signIn(page,request,{readOnly:true}),readRow=connection(readOwner,{imports:[{id:randomUUID(),remoteListId:'work',state:'READY',count:3}]});const readModel=await providerFixture(page,readOwner,{rows:[readRow]});await open(page);const readonly=card(page,readRow.id);
  await readonly.getByRole('button',{name:'Review import for Work',exact:true}).click();await expect(readonly.getByRole('button',{name:'Import this list',exact:true})).toBeDisabled();expect(readModel.writes).toEqual([]);
});

test('task connections and failure states are localized in Norwegian',async({page,request})=>{
  const owner=await signIn(page,request,{language:'nb-NO'}),row=connection(owner,{lastError:'TASK_PROVIDER_CONFLICTS',pendingCount:2});await providerFixture(page,owner,{rows:[row]});await open(page);
  await expect(page.getByRole('heading',{name:'Oppgavekoblinger',exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'Koble til Google Tasks',exact:true})).toBeVisible();await expect(card(page,row.id).getByText('Noen oppgaver må gjennomgås. Lokale endringer og tidligere importerte oppgaver beholdes. Oppgaver som må gjennomgås: 2.',{exact:true})).toBeVisible();
});

test('Google access defaults to import-only and write consent is an explicit choice',async({page,request})=>{
  const owner=await signIn(page,request),model=await providerFixture(page,owner,{rows:[],authorization:'https://untrusted.example.invalid/collect'});await open(page);
  const access=page.getByRole('combobox',{name:'Google access',exact:true});await expect(access).toHaveValue('IMPORT');
  await access.selectOption('TWO_WAY');await page.getByRole('button',{name:'Connect Google Tasks',exact:true}).click();
  await expect(page.getByText('Reload current values',{exact:true})).toBeVisible();expect(model.writes.find(write=>write.path==='/google/authorize').input).toEqual({direction:'TWO_WAY'});
});

test('preparing a change selects a real owned task and retains the selection during status polling',async({page,request})=>{
  const owner=await signIn(page,request),api=browserRequest(page),identity=await(await api.get('/api/v1/auth/session')).json();
  const headers={'X-CSRF-Token':identity.csrf,'Idempotency-Key':randomUUID()};
  const source=await api.post('/api/v1/task-connections',{headers,data:{providerId:'google',remoteAccountId:'fixture-account',name:'Browser Google',readable:true,writable:true,enabled:true}});expect(source.status()).toBe(201);const sourceValue=await source.json();
  const listReply=await api.post('/api/v1/task-lists',{headers:{...headers,'Idempotency-Key':randomUUID()},data:{connectionId:sourceValue.id,remoteListId:'work',name:'Work',kind:'STANDARD',readable:true,writable:true,shared:false,owner:true,present:true,position:0}});expect(listReply.status()).toBe(201);const list=await listReply.json();
  const created=await api.post('/api/v1/tasks',{headers:{...headers,'Idempotency-Key':randomUUID()},data:{summary:'Changed locally',projectId:null,parentTaskId:null,url:null}});expect(created.status()).toBe(201);const task=await created.json();
  const linked=await api.put('/api/v1/tasks/'+task.id+'/external-references',{headers:{...headers,'If-Match':created.headers().etag},data:{providerId:'google',repositoryId:'fixture-account/work',externalId:'task-id',externalUrl:null,taskListId:list.id}});expect(linked.status()).toBe(200);
  const row=connection(owner,{id:sourceValue.id,direction:'TWO_WAY'}),model=await providerFixture(page,owner,{rows:[row],tasks:[await linked.json()],lists:[list]});await open(page);const panel=card(page,row.id);
  await panel.getByText('Prepare a Google change',{exact:true}).click();const taskChoice=panel.getByRole('combobox',{name:'Imported Google task',exact:true});await taskChoice.selectOption({label:'Work / Changed locally'});const captured=await taskChoice.inputValue();
  await page.getByRole('button',{name:'Refresh status',exact:true}).click();await expect(taskChoice).toHaveValue(captured);await panel.getByRole('button',{name:'Review change',exact:true}).click();
  await expect(panel.getByRole('heading',{name:'Review Google change · Changed locally',exact:true})).toBeVisible();expect(model.writes.find(write=>write.path.endsWith('/writes')).input).toEqual({taskId:task.id,listId:list.id,action:'UPDATE'});expect(model.providerWrites).toBe(0);
});

test('reviewed Google changes retain their comparison during polling and reject stale approval',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner,{direction:'TWO_WAY',pendingWrites:1}),job=writeJob({connectionId:row.id}),model=await providerFixture(page,owner,{rows:[row],jobs:[job]});await open(page);const panel=card(page,row.id);
  await panel.getByRole('button',{name:'Review result',exact:true}).click();await expect(panel.getByRole('heading',{name:'Review Google change · Imported Google task',exact:true})).toBeFocused();
  await expect(panel.getByText('Changed locally',{exact:true})).toBeVisible();await expect(panel.getByText('Notes: Only plain text notes can be sent. These notes stay in Dagsverk.',{exact:true})).toBeVisible();expect(model.providerWrites).toBe(0);
  job.version++;job.writeFields={summary:'A newer proposal'};await page.getByRole('button',{name:'Refresh status',exact:true}).click();
  await expect(panel.getByText('Changed locally',{exact:true})).toBeVisible();await expect(panel.getByText('A newer proposal',{exact:true})).toHaveCount(0);
  await panel.getByRole('button',{name:'Send to Google',exact:true}).click();await expect(panel.getByText('Reload current values',{exact:true})).toBeVisible();expect(model.providerWrites).toBe(0);
  await panel.getByRole('button',{name:'Review result',exact:true}).click();await expect(panel.getByText('A newer proposal',{exact:true})).toBeVisible();await panel.getByRole('button',{name:'Send to Google',exact:true}).click();
  await expect(panel.getByText('Imported Google task · Confirmed',{exact:true})).toBeVisible();expect(model.providerWrites).toBe(1);
});

test('read-only Google grants can check and reconcile an uncertain result without resending it',async({page,request})=>{
  const owner=await signIn(page,request),row=connection(owner,{direction:'IMPORT',pendingWrites:1}),job=writeJob({connectionId:row.id,state:'UNKNOWN',canConfirm:false}),model=await providerFixture(page,owner,{rows:[row],jobs:[job]});await open(page);const panel=card(page,row.id);
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
  for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
    await openThemeSettings(page);await page.locator('#theme').selectOption(theme);await page.getByRole('link',{name:language==='nb-NO'?'Oppgavekoblinger':'Task connections',exact:true}).click();
    const panel=card(page,row.id);await panel.getByRole('button',{name:language==='nb-NO'?'Gjennomgå resultat':'Review result',exact:true}).click();await expect(panel.locator('img')).toHaveCount(0);
    for(const width of [1440,390,320]){await page.setViewportSize({width,height:1000});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#account-page').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);}
  }
  expect(model.providerWrites).toBe(0);await captureScreenshot(page,{path:'test-results/task-writes-320-'+language+'.png',fullPage:true});
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
