import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {browserRequest} from './browser-request.js';
import {appendEditorText} from './editor-interactions.js';

const date='2026-05-04';
const body='# Working journal\n\n'+Array.from({length:400},(_,i)=>`Paragraph ${i}: observations, decisions and follow-up work for the current project.\n\n`).join('');
test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function setup(page,request,large=false){
  const username='today-sync-'+randomUUID().slice(0,8),password='Private Today synchronization password';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,extra={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...extra}});expect(reply.ok(),method+' '+path+': '+reply.status()).toBe(true);return reply;};
  const clients=[];
  for(let i=0;i<(large?20:1);i++)clients.push(await(await api('POST','/clients',{name:'Client '+i,color:'#305070'})).json());
  if(large)for(let i=0;i<40;i++){
    const project=await(await api('POST','/projects',{name:'Project '+i,clientId:clients[i%clients.length].id})).json();
    for(let j=0;j<2;j++)await api('POST','/tasks',{summary:`Task ${i}/${j}`,projectId:project.id,parentTaskId:null,url:null});
  }
  const entry=await(await api('GET','/day-entries/'+date)).json();
  await api('POST','/day-entries/'+date,{text:'Original label',labelVersion:entry.labelVersion,body,noteVersion:entry.version},{'Idempotency-Key':randomUUID()});
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('#today-date').fill(date);
  await expect(page.locator('#today-note-text')).toHaveValue('Original label');await expect(page.locator('#today-journal-formatted .ProseMirror')).toContainText('Paragraph 399:');
  return {api,client:clients[0]};
}
async function editor(page,mode){
  if(mode==='source')await page.locator('#today-note-editor').getByRole('button',{name:'Markdown source',exact:true}).click();
  return page.locator(mode==='source'?'#today-journal-body':'#today-journal-formatted .ProseMirror');
}
function holdSnapshots(page){
  let held=false,release;const pending=new Promise(resolve=>release=resolve);
  const install=()=>page.route('**/api/v1/projects?*',async route=>{held=true;await pending;await route.continue();});
  return {install,held:()=>held,release};
}
async function recover(page,gate){
  await page.route('**/api/v1/me',async route=>{await page.unroute('**/api/v1/me');await route.abort('failed');});
  await page.locator('#refresh').click();await expect(page.locator('#status')).toHaveAttribute('data-state','error');
  await expect.poll(gate.held).toBe(true);
}
async function rememberPosition(field,mode){
  return field.evaluate((node,mode)=>{
    window.todaySyncEditor=node;
    if(mode==='source'){node.setSelectionRange(18,25);node.scrollTop=360;return {start:node.selectionStart,end:node.selectionEnd,scroll:node.scrollTop};}
    const text=node.querySelector('p').firstChild,range=document.createRange();range.setStart(text,3);range.setEnd(text,9);getSelection().removeAllRanges();getSelection().addRange(range);node.parentElement.scrollTop=360;
    return {start:getSelection().anchorOffset,end:getSelection().focusOffset,scroll:node.parentElement.scrollTop};
  },mode);
}
async function expectPosition(field,mode,position){
  expect(await field.evaluate((node,mode)=>({same:node===window.todaySyncEditor,start:mode==='source'?node.selectionStart:getSelection().anchorOffset,end:mode==='source'?node.selectionEnd:getSelection().focusOffset,scroll:mode==='source'?node.scrollTop:node.parentElement.scrollTop}),mode)).toEqual({same:true,...position});
  await expect(field).toBeFocused();
}
for(const mode of ['source','formatted'])for(const transition of ['post-save','recovery'])test(`Today ${mode} stays editable through ${transition} synchronization`,async({page,request},info)=>{
  const large=mode==='source'&&transition==='post-save',{api,client}=await setup(page,request,large),field=await editor(page,mode),gate=holdSnapshots(page);
  let noteWrites=0;page.on('request',request=>{if(request.method()==='POST'&&new URL(request.url()).pathname==='/api/v1/day-entries/'+date)noteWrites++;});
  await gate.install();const started=performance.now();
  try{
    if(transition==='post-save'){
      await page.locator('#session-start').click();await page.getByLabel('Client',{exact:true}).selectOption(client.id);await page.locator('#save').click();await expect(page.locator('#editor')).not.toBeVisible();
    }else{
      // Fail a refresh, then hold the automatic recovery read.
      await recover(page,gate);
    }
    await expect.poll(gate.held).toBe(true);
    await info.attach('draft-readiness',{body:JSON.stringify({mode,transition,clients:large?20:1,projects:large?40:0,tasks:large?80:0,documentBytes:Buffer.byteLength(body),editable:await field.evaluate(node=>node.matches('textarea')?!node.readOnly:node.isContentEditable),elapsedMs:Math.round(performance.now()-started)}),contentType:'application/json'});
    await expect(field).toBeEditable({timeout:1500});await expect(page.locator('#today-note-text')).toBeEditable();
    await expect(page.locator('#today-note-editor')).toContainText('Waiting for synchronization');
    await page.locator('#today-note-text').fill('Label drafted during synchronization');
    const inputStarted=performance.now();
    if(mode==='source'){await field.focus();await field.press('ControlOrMeta+End');await page.keyboard.insertText(' Drafted while synchronizing.');}
    else await appendEditorText(field.locator('p').last(),' Drafted while synchronizing.');
    await field.press('ControlOrMeta+s');
    const position=await rememberPosition(field,mode);expect(noteWrites).toBe(0);
    const metrics={mode,transition,inputMs:Math.round(performance.now()-inputStarted),documentBytes:Buffer.byteLength(body),clients:large?20:1,projects:large?40:0,tasks:large?80:0};
    console.log('Today synchronization input',JSON.stringify(metrics));
    await info.attach('input-latency',{body:JSON.stringify(metrics),contentType:'application/json'});
    gate.release();await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');
    await expectPosition(field,mode,position);await expect(page.locator('#today-note-text')).toHaveValue('Label drafted during synchronization');
    // The refresh must preserve the native/ProseMirror undo history as well as the DOM.
    await field.press('ControlOrMeta+z');
    if(mode==='source')await expect(field).not.toHaveValue(/Drafted while synchronizing/);else await expect(field).not.toContainText('Drafted while synchronizing');
    if(mode==='source'){await field.press('ControlOrMeta+End');await page.keyboard.insertText(' Final local draft.');}
    else await appendEditorText(field.locator('p').last(),' Final local draft.');
    await field.press('ControlOrMeta+s');await expect.poll(async()=>(await(await api('GET','/day-entries/'+date)).json()).body).toContain('Final local draft.');
    expect((await(await api('GET','/day-entries/'+date)).json()).text).toBe('Label drafted during synchronization');
  }finally{gate.release();await page.unrouteAll({behavior:'wait'});}
});

for(const mode of ['source','formatted'])test(`Today ${mode} retains a recovery draft when a peer changes the same note`,async({page,request})=>{
  const {api}=await setup(page,request),field=await editor(page,mode),gate=holdSnapshots(page),base=await(await api('GET','/day-entries/'+date)).json();
  const writes=[];page.on('request',request=>{if(request.method()==='POST'&&new URL(request.url()).pathname==='/api/v1/day-entries/'+date)writes.push(request.postDataJSON());});
  await gate.install();
  try{
    await recover(page,gate);await expect(field).toBeEditable();await page.locator('#today-note-text').fill('Local conflict label');
    if(mode==='source')await field.fill(body+' Local conflict draft.');else await appendEditorText(field.locator('p').last(),' Local conflict draft.');
    await api('POST','/day-entries/'+date,{text:'Peer label',labelVersion:base.labelVersion,body:'# Peer body\n',noteVersion:base.version},{'Idempotency-Key':randomUUID()});
    const position=await rememberPosition(field,mode);gate.release();
    await expect(page.locator('#today-note-editor [role=alert]')).toContainText('This record changed');
    await expectPosition(field,mode,position);await expect(page.locator('#today-note-text')).toHaveValue('Local conflict label');
    if(mode==='source')await expect(field).toHaveValue(body+' Local conflict draft.');else await expect(field).toContainText('Local conflict draft.');
    expect(writes).toHaveLength(1);expect(writes[0].noteVersion).toBe(base.version);expect(writes[0].labelVersion).toBe(base.labelVersion);
    const current=await(await api('GET','/day-entries/'+date)).json();expect(current.body).toBe('# Peer body\n');expect(current.text).toBe('Peer label');
    const reload=page.locator('#today-note-editor').getByRole('button',{name:'Reload saved note',exact:true});await expect(reload).toBeEnabled();
    page.once('dialog',dialog=>dialog.dismiss());await reload.click();await expect(page.locator('#today-note-text')).toHaveValue('Local conflict label');
    page.once('dialog',dialog=>dialog.accept());await reload.click();await expect(page.locator('#today-note-text')).toHaveValue('Peer label');
  }finally{gate.release();await page.unrouteAll({behavior:'wait'});}
});

test('a superseded account read is cancelled without replacing the Today draft',async({page,request})=>{
  await setup(page,request);const field=await editor(page,'source');
  let held=false,release,obsolete;const pending=new Promise(resolve=>release=resolve),cancelled=[];
  page.on('requestfailed',request=>{if(request===obsolete)cancelled.push(request);});
  await page.route('**/api/v1/projects?*',async route=>{if(!held){held=true;obsolete=route.request();await pending;}await route.continue();});
  try{
    await page.locator('#refresh').click();await expect.poll(()=>held).toBe(true);await field.fill(body+' Survives supersession.');
    // Acknowledging the note supersedes the older account snapshot immediately.
    await field.press('ControlOrMeta+s');
    await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');expect(cancelled).toHaveLength(1);
    await expect(field).toHaveValue(body+' Survives supersession.');await expect(field).toBeEditable();
    await expect(page.locator('#status')).toHaveAttribute('data-state','ready');
  }finally{release();await page.unrouteAll({behavior:'wait'});}
});

for(const failure of ['unauthorized','owner changed'])test(`Today clears the recovery draft when ${failure}`,async({page,request})=>{
  await setup(page,request);const field=await editor(page,'source'),gate=holdSnapshots(page);
  await gate.install();
  try{
    await recover(page,gate);await field.fill('Private recovery draft');await page.locator('#today-note-text').fill('Private recovery label');
    // Override the held snapshot response without allowing a draft write.
    await page.route('**/api/v1/me',route=>route.fulfill(failure==='unauthorized'?{status:401,json:{code:'UNAUTHENTICATED'}}:{json:{ownerId:'another-owner',zoneId:'UTC',version:1},headers:{'X-Timekeeper-Owner':'YW5vdGhlci1vd25lcg'}}));
    gate.release();await expect(page.locator('#workspace')).not.toBeVisible();await expect(page.locator('#login-form')).toBeVisible();
    await expect(page.locator('#today-journal-body')).toHaveValue('');await expect(page.locator('#today-note-text')).toHaveValue('');await expect(page.locator('#today-journal-formatted .ProseMirror')).toHaveCount(0);
  }finally{gate.release();await page.unrouteAll({behavior:'wait'});}
});
