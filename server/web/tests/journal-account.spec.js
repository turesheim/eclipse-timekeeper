import {test,expect} from '@playwright/test';
import {randomUUID,generateKeyPairSync,createHash} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {captureScreenshot} from './screenshots.js';
import {setTheme} from './navigation.js';

const base=()=>process.env.TIMEKEEPER_WEB_URL;
const fixture=()=>process.env.TIMEKEEPER_WEB_JOURNAL_FIXTURE;
test.beforeAll(async({request})=>{
  const headers={Origin:new URL(base()).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function fresh(request,provision=true){
  const username='journal-'+randomUUID().slice(0,8),password='Independent journal password';
  const reply=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});expect(reply.status()).toBe(201);
  const {user:account}=await reply.json();let transport=null;
  if(provision){expect(fixture()).toBeTruthy();const binding=await request.post(fixture()+'/accounts',{data:{ownerId:account.ownerId}});expect(binding.status()).toBe(201);transport=await binding.json();}
  return {username,password,transport};
}
async function signIn(page,user){await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(user.username);await page.locator('#password').fill(user.password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#workspace')).toBeVisible();await page.evaluate(()=>location.hash='#account/journal');await expect(page.getByRole('heading',{name:'Journal',exact:true})).toBeVisible();}
const settings=page=>page.locator('.account-card:not(.journal-sync-advanced)').filter({has:page.getByRole('heading',{name:'Synchronization settings',exact:true})});
const enroll=page=>page.locator('.account-card:not(.journal-sync-advanced)').filter({has:page.getByRole('heading',{name:'Add client',exact:true})});
function key(){const der=generateKeyPairSync('ed25519').publicKey.export({type:'spki',format:'der'});return 'ssh-ed25519 '+Buffer.concat([Buffer.from('0000000b7373682d6564323535313900000020','hex'),der.subarray(-32)]).toString('base64')+' timekeeper-journal';}

test('journal synchronization account setup enrolls public keys, downloads actual endpoint profiles and revokes them',async({browser,request})=>{
  const context=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB'}),page=await context.newPage();
  try{
    const user=await fresh(request);await signIn(page,user);
    await expect(enroll(page).locator('input,textarea,select')).toHaveCount(2);await expect(page.getByText('journal.example.invalid:2222')).not.toBeVisible();
    let privateUploads=0;page.on('request',r=>{if(r.postData()?.includes('BEGIN OPENSSH PRIVATE KEY'))privateUploads++;});
    await enroll(page).getByLabel('Public SSH key (.pub)',{exact:true}).fill('-----BEGIN OPENSSH PRIVATE KEY-----\nnever-upload-this\n-----END OPENSSH PRIVATE KEY-----');await expect(enroll(page).getByLabel('Public SSH key (.pub)',{exact:true})).toHaveValue('');expect(privateUploads).toBe(0);
    await enroll(page).getByLabel('Client name').fill('Laptop');await enroll(page).getByLabel('Public SSH key (.pub)',{exact:true}).fill(key());await enroll(page).getByRole('button',{name:'Add client'}).click();await expect(enroll(page)).toContainText('Client added.');
    const download=page.waitForEvent('download');await page.getByRole('button',{name:'Download profile',exact:true}).click();const file=await download,stream=await file.createReadStream();let text='';for await(const chunk of stream)text+=chunk;const profile=JSON.parse(text);expect(profile.direction).toBe('BIDIRECTIONAL');expect(profile.format).toBe(2);expect(profile.hostname).toBe('journal.example.invalid');expect(profile.port).toBe(2222);expect(profile.username).toBe(user.transport.username);expect(text).not.toContain(user.transport.token);expect(text).not.toContain(user.password);
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){await setTheme(page,theme);await page.evaluate(()=>location.hash='#account/journal');await expect(page.getByRole('heading',{name:'Journal',exact:true})).toBeVisible();await page.locator('.journal-account details').evaluateAll(elements=>elements.forEach(element=>element.open=true));await expect(page.locator('.journal-account details').first()).toContainText("trust --profile profile.json --fingerprint '"+profile.hostKeyFingerprint+"'");expect((await new AxeBuilder({page}).include('#account-page').analyze()).violations).toEqual([]);}
    await setTheme(page,'timekeeper');await page.evaluate(()=>location.hash='#account/journal');await expect(page.getByRole('heading',{name:'Journal',exact:true})).toBeVisible();
    await page.setViewportSize({width:320,height:740});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await captureScreenshot(page,{path:'test-results/journal-account-mobile.png',fullPage:true});
    await page.getByRole('button',{name:'Revoke key',exact:true}).click();let revoke=page.locator('.account-card:not(.journal-sync-advanced)').filter({has:page.getByRole('heading',{name:'Revoke Laptop',exact:true})});await revoke.getByLabel('Current password',{exact:true}).fill(user.password);await revoke.getByRole('button',{name:'Revoke key',exact:true}).click();
    await enroll(page).getByLabel('Client name').fill('Replacement laptop');await enroll(page).getByLabel('Public SSH key (.pub)',{exact:true}).fill(key());await enroll(page).getByRole('button',{name:'Add client'}).click();await expect(enroll(page)).toContainText('Client added.');await expect(page.getByText('Revoked',{exact:true})).toHaveCount(1);
    await page.getByRole('button',{name:'Revoke key',exact:true}).click();revoke=page.locator('.account-card:not(.journal-sync-advanced)').filter({has:page.getByRole('heading',{name:'Revoke Replacement laptop',exact:true})});await revoke.getByLabel('Current password',{exact:true}).fill(user.password);await revoke.getByRole('button',{name:'Revoke key',exact:true}).click();await expect(page.getByText('Revoked',{exact:true})).toHaveCount(2);for(const profileButton of await page.getByRole('button',{name:'Download profile',exact:true}).all())await expect(profileButton).toBeDisabled();
  }finally{await context.close();}
});

test('journal synchronization read-only sessions can refresh status repeatedly without gaining credential controls',async({browser})=>{
  const context=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB'}),page=await context.newPage();
  try{
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_READ_TOKEN);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#workspace')).toBeVisible();await page.evaluate(()=>location.hash='#account/journal');await expect(page.getByRole('heading',{name:'Journal',exact:true})).toBeVisible();
    await expect(page.getByText('Sign in with your account password or a passkey to manage journal credentials. An API token or token-derived browser session cannot register, rotate or revoke keys.')).toBeVisible();
    await page.getByText('Connection setup and troubleshooting',{exact:true}).click();
    for(let i=0;i<2;i++){const refresh=page.getByRole('button',{name:'Refresh status',exact:true});await refresh.click();await expect(refresh).toBeEnabled();}
    await expect(settings(page).getByRole('button',{name:'Save synchronization settings'})).toBeDisabled();await expect(enroll(page).getByRole('button',{name:'Add client'})).toBeDisabled();await expect(page.getByRole('button',{name:'Retry pending imports'})).toBeDisabled();
  }finally{await context.close();}
});

test('journal synchronization reports missing prerequisites and keeps credential drafts behind navigation guards',async({browser,request})=>{
  const context=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB'}),page=await context.newPage();
  try{
    const user=await fresh(request,false);await signIn(page,user);await expect(page.getByText('Ask the operator to bind a restricted SSH identity and private incoming directory to this account.')).toBeVisible();await expect(enroll(page).getByRole('button',{name:'Add client'})).toBeDisabled();
    await request.post(fixture()+'/accounts',{data:{ownerId:(await(await browserRequest(page).get('/api/v1/auth/session')).json()).ownerId}});await page.getByText('Connection setup and troubleshooting',{exact:true}).click();await page.getByRole('button',{name:'Refresh status',exact:true}).click();await expect(enroll(page).getByRole('button',{name:'Add client'})).toBeEnabled();
    await enroll(page).getByLabel('Client name').fill('Draft laptop');let pending=page.waitForEvent('dialog'),click=page.locator('#account-navigation').getByRole('link',{name:'Profile',exact:true}).click();await(await pending).dismiss();await click;await expect(page).toHaveURL(/#account\/journal$/);await expect(enroll(page).getByLabel('Client name')).toHaveValue('Draft laptop');
    pending=page.waitForEvent('dialog');click=page.locator('#account-navigation').getByRole('link',{name:'Profile',exact:true}).click();await(await pending).accept();await click;await expect(page.getByRole('heading',{name:'Journal',exact:true})).not.toBeVisible();
  }finally{await context.close();}
});

test('journal synchronization reviews both conflict copies, preserves originals and rejects stale Calendar decisions',async({browser,request,playwright})=>{
  const context=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB'}),page=await context.newPage(),machine=await playwright.request.newContext({baseURL:base(),ignoreHTTPSErrors:true});
  try{
    const user=await fresh(request);await signIn(page,user);await page.getByText('Connection setup and troubleshooting',{exact:true}).click();
    await enroll(page).getByLabel('Client name').fill('Conflict laptop');await enroll(page).getByLabel('Public SSH key (.pub)',{exact:true}).fill(key());await enroll(page).getByRole('button',{name:'Add client'}).click();await expect(enroll(page)).toContainText('Client added.');
    const api=browserRequest(page),identity=await(await api.get('/api/v1/auth/session')).json(),device=(await(await api.get('/api/v1/journal')).json()).devices[0],date='/api/v1/day-notes/2026-10-25';
    let current=await api.get(date);await api.put(date,{headers:{'X-CSRF-Token':identity.csrf,'If-Match':current.headers().etag},data:{body:'Calendar original'}});
    const gateway='/internal/journal/'+user.transport.username,headers={Authorization:'JournalTransport '+user.transport.token};
    const started=await machine.post(gateway+'/transfers',{headers,data:{deviceId:device.id}});expect(started.status()).toBe(200);const id=(await started.json()).id;
    const original='---\r\ntimekeeper-label: Planning\r\nunknown: keep me\r\n---\r\n\r\n# Incoming Søndag  \r\n',bytes=Buffer.from(original);
    expect((await machine.put(`${gateway}/transfers/${id}/files/2026-10-25.md`,{headers,data:{data:bytes.toString('base64')}})).status()).toBe(200);
    expect((await machine.post(`${gateway}/transfers/${id}/complete`,{headers,data:{format:1,completed:true,files:[{name:'2026-10-25.md',bytes:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex')}]}})).status()).toBe(200);
    await expect.poll(async()=>(await(await api.get('/api/v1/journal')).json()).health.conflicts).toBe(1);await page.getByRole('button',{name:'Refresh status',exact:true}).click();await expect(page.getByRole('button',{name:'Review conflict',exact:true})).toBeVisible();
    const download=page.waitForEvent('download');await page.getByRole('button',{name:'Download original copy',exact:true}).click();const stream=await(await download).createReadStream(),chunks=[];for await(const chunk of stream)chunks.push(chunk);expect(Buffer.concat(chunks).toString('utf8')).toBe(original);
    await page.getByRole('button',{name:'Review conflict',exact:true}).click();await expect(page.locator('.journal-sync-preview').first()).toContainText('Calendar original');await expect(page.locator('.journal-sync-preview').last()).toContainText('Incoming Søndag');
    current=await api.get(date);expect((await api.put(date,{headers:{'X-CSRF-Token':identity.csrf,'If-Match':current.headers().etag},data:{body:'Calendar peer edit'}})).status()).toBe(200);
    await page.getByRole('button',{name:'Use incoming copy',exact:true}).click();await expect(page.locator('.form-feedback').filter({hasText:'These records changed or conflict with this change. Your draft is kept. Review current values before saving again.'})).toBeVisible();expect((await(await api.get(date)).json()).body).toBe('Calendar peer edit');
    await page.getByRole('button',{name:'Review conflict',exact:true}).click();await expect(page.locator('.journal-sync-preview').first()).toContainText('Calendar peer edit');await page.getByRole('button',{name:'Use incoming copy',exact:true}).click();await expect(page.getByRole('button',{name:'Review conflict',exact:true})).toHaveCount(0);expect((await(await api.get(date)).json()).body).toBe('\r\n# Incoming Søndag  \r\n');
  }finally{await machine.dispose();await context.close();}
});

test('journal synchronization defaults to two-way synchronization and reviews deletion conflicts before offering exports',async({browser,request,playwright})=>{
  const context=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB'}),page=await context.newPage(),machine=await playwright.request.newContext({baseURL:base(),ignoreHTTPSErrors:true});
  try{
    const user=await fresh(request);await signIn(page,user);
    await page.getByText('Connection setup and troubleshooting',{exact:true}).click();
    await enroll(page).getByLabel('Client name').fill('Two way laptop');await enroll(page).getByLabel('Public SSH key (.pub)',{exact:true}).fill(key());await enroll(page).getByRole('button',{name:'Add client'}).click();await expect(enroll(page)).toContainText('Client added.');await expect(page.getByText('Upload and download',{exact:true})).toBeVisible();
    const download=page.waitForEvent('download');await page.getByRole('button',{name:'Download profile',exact:true}).click();const stream=await(await download).createReadStream();let downloaded='';for await(const chunk of stream)downloaded+=chunk;const profile=JSON.parse(downloaded);expect(profile.format).toBe(2);expect(profile.direction).toBe('BIDIRECTIONAL');expect(profile.uploadPrefix).toBe('incoming/');expect(profile.downloadPrefix).toBe('outgoing/');expect(downloaded).not.toContain(user.transport.token);
    const api=browserRequest(page),identity=await(await api.get('/api/v1/auth/session')).json(),date='/api/v1/day-notes/2044-01-01',gateway='/internal/journal/'+user.transport.username,headers={Authorization:'JournalTransport '+user.transport.token},clientStateId=randomUUID(),filename='2044-01-01.md';
    let current=await api.get(date);expect((await api.put(date,{headers:{'X-CSRF-Token':identity.csrf,'If-Match':current.headers().etag},data:{body:'Initial common note'}})).status()).toBe(200);
    async function exchange(content,baseToken=''){
      const exchangeId=randomUUID(),started=await machine.post(gateway+'/transfers',{headers,data:{deviceId:profile.deviceId,exchangeId}});expect(started.status()).toBe(200);const id=(await started.json()).id,bytes=content===null?null:Buffer.from(content);
      if(bytes)expect((await machine.put(`${gateway}/transfers/${id}/files/${filename}`,{headers,data:{data:bytes.toString('base64')}})).status()).toBe(200);
      expect((await machine.post(`${gateway}/transfers/${id}/complete`,{headers,data:{format:2,completed:true,clientStateId,exchangeId,files:bytes?[{name:filename,baseToken,bytes:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex')}]:[],absent:bytes?[]:[{name:filename,baseToken}],errors:[]}})).status()).toBe(200);
      expect((await machine.post(`${gateway}/transfers/${id}/settle`,{headers,data:{}})).status()).toBe(200);
      const result=await machine.post(`${gateway}/exchanges/${exchangeId}/results`,{headers,data:{deviceId:profile.deviceId}});expect(result.status()).toBe(200);return (await result.json()).offers[0];
    }
    const first=await exchange('Initial common note');expect(first.state).toBe('OFFERED');expect((await machine.post(`${gateway}/offers/${first.id}/acknowledge`,{headers,data:{deviceId:profile.deviceId,token:first.token,exists:first.targetExists,sha256:first.targetHash}})).status()).toBe(200);
    current=await api.get(date);expect((await api.put(date,{headers:{'X-CSRF-Token':identity.csrf,'If-Match':current.headers().etag},data:{body:'New Calendar edit against a deleted file'}})).status()).toBe(200);
    const conflict=await exchange(null,first.token);expect(conflict.state).toBe('CONFLICT');await page.getByRole('button',{name:'Refresh status',exact:true}).click();await expect(page.getByRole('button',{name:'Download Calendar snapshot',exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'Discard retained copies',exact:true})).toBeVisible();await page.getByRole('button',{name:'Review conflict',exact:true}).click();await expect(page.locator('.journal-sync-preview').last()).toContainText('Deleted note (no file)');
    await page.getByRole('button',{name:'Use incoming copy',exact:true}).click();await expect(page.getByRole('button',{name:'Review conflict',exact:true})).toHaveCount(0);expect((await(await api.get(date)).json()).deleted).toBe(true);await expect(page.getByRole('cell',{name:'Awaiting device acknowledgement',exact:true})).toBeVisible();
    await settings(page).getByLabel('Allow bidirectional synchronization',{exact:true}).uncheck();await settings(page).getByLabel('Current password',{exact:true}).fill(user.password);await settings(page).getByRole('button',{name:'Save synchronization settings'}).click();await expect(settings(page)).toContainText('Synchronization settings saved.');await expect(page.getByRole('button',{name:'Download profile',exact:true})).toBeDisabled();await expect(enroll(page).locator('input,textarea,select')).toHaveCount(2);
  }finally{await machine.dispose();await context.close();}
});
