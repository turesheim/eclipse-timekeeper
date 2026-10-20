import {captureScreenshot} from './screenshots.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {openAccountMenu} from './navigation.js';
const rootToken=process.env.TIMEKEEPER_WEB_TOKEN;
const origin=()=>new URL(process.env.TIMEKEEPER_WEB_URL).origin;
test.beforeAll(async({request})=>{
  const status=await request.get('/api/v1/auth/status',{headers:{Origin:origin()}});
  if((await status.json()).setupRequired) {
    const setup=await request.post('/api/v1/auth/setup',{headers:{Origin:origin()},data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',displayName:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,email:'browser-admin@example.org'}});expect(setup.status()).toBe(200);
    await request.post('/api/v1/auth/logout',{headers:{Origin:origin(),'X-CSRF-Token':(await setup.json()).csrf}});
  }
});
async function own(page,method,path,data,extra={}) {
  const session=await browserRequest(page).get('/api/v1/auth/session');expect(session.status()).toBe(200);
  return browserRequest(page).fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':(await session.json()).csrf,'Idempotency-Key':randomUUID(),...extra}});
}
async function account(page,request) {
  const username='archive-'+randomUUID().slice(0,8),password='Independent archive account passphrase';
  const created=await request.post('/api/v1/admin/users',{headers:{Authorization:'Bearer '+rootToken},data:{username,password,displayName:'Private archive account',email:username+'@example.invalid',administrator:false}});expect(created.status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=await own(page,'POST','/clients',{name:'Private client',color:'#176653'});expect(client.status()).toBe(201);
  const project=await own(page,'POST','/projects',{name:'Archive project',clientId:(await client.json()).id});expect(project.status()).toBe(201);
  const session=await own(page,'POST','/work-sessions',{projectId:(await project.json()).id,summary:'Exact private notes',start:'2026-10-16T06:00:00.123456789Z',end:'2026-10-16T07:00:00.987654321Z'});expect(session.status()).toBe(201);
  await openAccountMenu(page);await page.getByRole('link',{name:'My Account',exact:true}).click();await page.getByRole('link',{name:'Your data',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Download my data',exact:true})).toBeVisible();return {username,session:await session.json()};
}
async function download(page) {
  const waiting=page.waitForEvent('download');await page.getByRole('button',{name:'Download my data',exact:true}).click();const result=await waiting;
  expect(result.suggestedFilename()).toBe('timekeeper-account.zip');const path=test.info().outputPath('private-account.zip');await result.saveAs(path);return path;
}
async function snapshot(page) {return (await own(page,'GET','/data/export')).json();}
test('portability: private download, accessible review, stale review and duplicate-safe restore preserve account identity',async({page,request})=>{
  const user=await account(page,request),identity=await (await own(page,'GET','/auth/account')).json();
  let profile=await own(page,'GET','/me');expect((await own(page,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag})).status()).toBe(200);
  const file=await download(page);profile=await own(page,'GET','/me');expect((await own(page,'PUT','/me',{zoneId:'UTC'},{'If-Match':profile.headers().etag})).status()).toBe(200);
  await page.reload();await expect(page.getByRole('heading',{name:'Download my data',exact:true})).toBeVisible();
  await page.getByLabel('Timekeeper archive',{exact:true}).setInputFiles(file);await page.getByLabel('Restore the archived time zone',{exact:true}).check();await page.getByRole('button',{name:'Review archive',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Review archive',exact:true})).toBeVisible();await expect(page.getByText('Time zone: UTC → Europe/Oslo',{exact:true})).toBeVisible();
  const links=page.getByRole('table',{name:'Record links',exact:true});await expect(links.locator('tbody tr')).toHaveCount(3);await expect(links).toContainText('Work session projects');await expect(links.locator('tbody tr').filter({hasText:'Work session projects'})).toContainText('1');
  expect((await snapshot(page)).workSessions).toHaveLength(1);
  for(const width of [1440,390,320]) {
    await page.setViewportSize({width,height:900});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    const audit=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations.map(value=>value.id)).toEqual([]);
    await captureScreenshot(page,{path:`test-results/account-data-review-${width}.png`,fullPage:true});
  }
  expect((await own(page,'POST','/clients',{name:'Peer edit retained',color:'#334455'})).status()).toBe(201);
  await page.getByRole('button',{name:'Import reviewed records',exact:true}).click();await expect(page.getByText('Your work changed after review. Select Review archive to review the current records again.',{exact:true})).toBeVisible();expect((await snapshot(page)).workSessions).toHaveLength(1);
  await page.getByRole('button',{name:'Review archive',exact:true}).click();await expect(page.getByRole('button',{name:'Import reviewed records',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'Import reviewed records',exact:true}).click();await expect(page.getByRole('heading',{name:'Archive restored',exact:true})).toBeVisible();
  const restored=await snapshot(page);expect(restored.workSessions).toHaveLength(2);const copy=restored.workSessions.find(value=>value.id!==user.session.id);expect(copy.start).toBe(user.session.start);expect(copy.end).toBe(user.session.end);expect(copy.projectId).not.toBe(user.session.projectId);
  expect(restored.projects.find(value=>value.id===copy.projectId).clientId).toBe(copy.clientId);expect(restored.clients.some(value=>value.name==='Peer edit retained')).toBe(true);expect(restored.zoneId).toBe('Europe/Oslo');
  expect(await (await own(page,'GET','/auth/account')).json()).toEqual(identity);
  await page.reload();await page.getByLabel('Timekeeper archive',{exact:true}).setInputFiles(file);await page.getByRole('button',{name:'Review archive',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Archive already restored',exact:true})).toBeVisible();expect((await snapshot(page)).workSessions).toHaveLength(2);
});
test('portability: a committed but lost restore reply is reconciled without another write',async({page,request})=>{
  await account(page,request);const file=await download(page);await page.getByLabel('Timekeeper archive',{exact:true}).setInputFiles(file);await page.getByRole('button',{name:'Review archive',exact:true}).click();await expect(page.getByRole('button',{name:'Import reviewed records',exact:true})).toBeEnabled();
  let writes=0;await page.route('**/api/v1/data/account/uploads/*/import',async route=>{writes++;expect((await route.fetch()).status()).toBe(201);await route.abort('failed');});
  await page.getByRole('button',{name:'Import reviewed records',exact:true}).click();await expect(page.getByText('The import result is unknown. Check its result before continuing.',{exact:true})).toBeVisible();await expect(page.getByLabel('Timekeeper archive',{exact:true})).toBeDisabled();expect(writes).toBe(1);
  await page.getByRole('button',{name:'Check import result',exact:true}).click();await expect(page.getByRole('heading',{name:'Archive restored',exact:true})).toBeVisible();expect(writes).toBe(1);expect((await snapshot(page)).workSessions).toHaveLength(2);
});
test('portability: upload resumes the next chunk after a lost reply, reload and file reselection',async({page,request})=>{
  await account(page,request);const accountData=await (await own(page,'GET','/auth/account')).json(),work=await snapshot(page);
  const value={format:'timekeeper-account-v1',archiveId:randomUUID(),generatedAt:new Date().toISOString(),account:{...accountData,recoveryAddresses:[],apiTokenMetadata:[],passkeyMetadata:[]},preferences:{zoneId:work.zoneId},work,importHistory:[0,1].map(index=>({entityType:'IMPORT_ITEM',data:{index,note:'a'.repeat(600000)}}))};
  const file={name:'large-account.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(value))};let firstWrites=0,secondWrites=0;
  await page.route('**/api/v1/data/account/uploads/*/chunks/0',async route=>{firstWrites++;expect((await route.fetch()).status()).toBe(200);await route.abort('failed');});
  await page.route('**/api/v1/data/account/uploads/*/chunks/1',async route=>{secondWrites++;await route.continue();});
  await page.getByLabel('Timekeeper archive',{exact:true}).setInputFiles(file);await page.getByRole('button',{name:'Review archive',exact:true}).click();await expect(page.getByText('Upload paused. Select Review archive to resume safely.',{exact:true})).toBeVisible();expect(firstWrites).toBe(1);
  page.once('dialog',dialog=>dialog.accept());await page.reload();await page.getByLabel('Timekeeper archive',{exact:true}).setInputFiles(file);await page.getByRole('button',{name:'Review archive',exact:true}).click();await expect(page.getByRole('button',{name:'Import reviewed records',exact:true})).toBeEnabled();expect(firstWrites).toBe(1);expect(secondWrites).toBe(1);
  await page.getByRole('button',{name:'Import reviewed records',exact:true}).click();await expect(page.getByRole('heading',{name:'Archive restored',exact:true})).toBeVisible();expect((await snapshot(page)).workSessions).toHaveLength(2);
});
test('portability: read-only sessions can download repeatedly while restore stays disabled',async({page})=>{
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_READ_TOKEN);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  await openAccountMenu(page);await page.getByRole('link',{name:'My Account',exact:true}).click();await page.getByRole('link',{name:'Your data',exact:true}).click();await expect(page.getByRole('button',{name:'Review archive',exact:true})).toBeDisabled();
  await download(page);await expect(page.getByRole('button',{name:'Download my data',exact:true})).toBeEnabled();await download(page);
});
test('portability: concurrent session refreshes do not interrupt private archive downloads',async({page,request})=>{
  await account(page,request);
  for(let round=0;round<3;round++) {
    const replies=await page.evaluate(async()=>Promise.all(Array.from({length:12},async(_,index)=>{
      const archive=index%2===0,response=await fetch('/api/v1'+(archive ? '/data/account/export':'/auth/session'),{headers:{Accept:archive ? 'application/zip':'application/json'},referrerPolicy:'origin',credentials:'same-origin',cache:'no-store'});
      const bytes=new Uint8Array(await response.arrayBuffer());
      return {status:response.status,archive,type:response.headers.get('Content-Type'),zip:bytes[0]===0x50&&bytes[1]===0x4b};
    })));
    for(const reply of replies) {expect(reply.status).toBe(200);if(reply.archive){expect(reply.type).toBe('application/zip');expect(reply.zip).toBe(true);}}
  }
});
