import {captureScreenshot} from './screenshots.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {openAccountMenu} from './navigation.js';
const token=process.env.TIMEKEEPER_WEB_TOKEN,password=process.env.TIMEKEEPER_WEB_PASSWORD;
const origin=()=>new URL(process.env.TIMEKEEPER_WEB_URL).origin;
const key=user=>Buffer.from(user.ownerId).toString('base64url');
async function api(request,method,path,data,extra={}) {return request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${token}`,...extra}});}
async function login(page,username='browser-admin',secret=password) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');
  await page.locator('#username').fill(username);await page.locator('#password').fill(secret);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
}
test.beforeAll(async({request})=>{
  const status=await request.get('/api/v1/auth/status',{headers:{Origin:origin()}});
  if((await status.json()).setupRequired) {
    const setup=await request.post('/api/v1/auth/setup',{headers:{Origin:origin()},data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',displayName:'browser-admin',password,email:'browser-admin@example.org'}});expect(setup.status()).toBe(200);
    const account=await setup.json();await request.post('/api/v1/auth/logout',{headers:{Origin:origin(),'X-CSRF-Token':account.csrf}});
  }
});
test('administration has its own protected API namespace and sign-in return page',async({page,request})=>{
  const response=await page.goto('/admin/');expect(response.status()).toBe(200);
  expect(response.headers()['content-security-policy']).toContain("script-src 'self'");expect(response.headers()['x-content-type-options']).toBe('nosniff');
  await expect(page.getByRole('link',{name:'Sign in to administration',exact:true})).toBeVisible();await expect(page.locator('#account-navigation')).toBeHidden();
  expect((await request.get('/api/v1/admin/settings',{headers:{Origin:origin()}})).status()).toBe(401);expect((await request.get('/web/admin/index.html')).status()).toBe(404);
  await page.getByRole('link',{name:'Sign in to administration',exact:true}).click();await page.locator('#username').fill('browser-admin');await page.locator('#password').fill(password);await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page).toHaveURL(/\/admin\/$/);await expect(page.getByRole('heading',{name:'General settings',exact:true})).toBeVisible();
  const settings=await browserRequest(page).get('/api/v1/admin/settings');expect(settings.status()).toBe(200);expect(settings.headers()['cache-control']).toBe('no-store');
  expect(Object.keys(await settings.json()).sort()).toEqual(['canEdit','configurationManagedBy','databaseType','effective','effectiveSmtpPasswordConfigured','effectiveVersion','emailRecoveryEnabled','originEpoch','passkeyRpId','passkeysEnabled','publicOrigin','restartRequired','saved','schemaVersion','smtpPasswordConfigured','version'].sort());
  expect((await browserRequest(page).get('/api/v1/auth/users')).status()).toBe(404);
});
test('administration settings and user management share accessible desktop and narrow layouts',async({page})=>{
  await login(page);await openAccountMenu(page);await page.getByRole('link',{name:'Administration',exact:true}).click();
  await expect(page.getByRole('heading',{name:'General settings',exact:true})).toBeVisible();await expect(page.locator('#account-content')).toContainText('Saved settings are active.');
  for(const width of [1440,390,320]) {
    await page.setViewportSize({width,height:900});
    for(const name of ['General settings','Users']) {
      await page.getByRole('link',{name,exact:true}).click();await expect(page.locator('#account-content')).toHaveAttribute('aria-busy','false');
      await expect(page.getByRole('link',{name,exact:true})).toHaveAttribute('aria-current','page');
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      const audit=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations.map(value=>value.id)).toEqual([]);
    }
    await captureScreenshot(page,{path:`test-results/administration-users-${width}.png`,fullPage:true});
    await page.getByRole('link',{name:'General settings',exact:true}).click();await expect(page.locator('#account-content')).toHaveAttribute('aria-busy','false');
    await captureScreenshot(page,{path:`test-results/administration-settings-${width}.png`,fullPage:true});
  }
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await expect(page).toHaveURL(/\/web\/$/);await expect(page.locator('#status')).toHaveText('Up to date');
});
test('ordinary and read-only accounts cannot obtain administration settings or user data',async({page,request,browser})=>{
  const username='admin-denied-'+randomUUID().slice(0,8),secret='Independent restricted account password';
  expect((await api(request,'POST','/admin/users',{username,password:secret,email:username+'@example.org',displayName:username,administrator:false})).status()).toBe(201);
  await login(page,username,secret);await openAccountMenu(page);await expect(page.getByRole('link',{name:'Administration',exact:true})).toBeHidden();
  await page.goto('/admin/#users');await expect(page.locator('#account-content')).toContainText('Administrator access is required');await expect(page.getByRole('link',{name:'Add user',exact:true})).toHaveCount(0);
  for(const path of ['/admin/settings','/admin/users','/admin/internet-accounts']) expect((await browserRequest(page).get('/api/v1'+path)).status()).toBe(403);
  const peer=await browser.newContext(),other=await peer.newPage();
  try {
    await other.goto('/web/');await other.locator('#auth-mode').selectOption('token');await other.locator('#token').fill(process.env.TIMEKEEPER_WEB_READ_TOKEN);await other.getByRole('button',{name:'Sign in',exact:true}).click();await expect(other.locator('#status')).toHaveText('Up to date');
    await other.goto('/admin/');await expect(other.locator('#account-content')).toContainText('Administrator access is required');
    expect((await browserRequest(other).get('/api/v1/admin/settings')).status()).toBe(403);
  } finally {await peer.close();}
});
test('administration preserves dirty drafts and refuses to repeat an uncertain user creation',async({page,request})=>{
  await login(page);await page.goto('/admin/#users/new');await expect(page.getByLabel('New user username',{exact:true})).toBeVisible();
  const username='uncertain-admin-'+randomUUID().slice(0,8);await page.getByLabel('New user username',{exact:true}).fill(username);await page.getByLabel('New user recovery email',{exact:true}).fill(username+'@example.org');await page.getByLabel('Temporary password',{exact:true}).fill('Private newly created account password');await page.getByLabel('Confirm temporary password',{exact:true}).fill('Private newly created account password');
  page.once('dialog',dialog=>dialog.dismiss());await page.getByRole('link',{name:'General settings',exact:true}).click();await expect(page.getByLabel('New user username',{exact:true})).toHaveValue(username);
  let writes=0;await page.route('**/api/v1/admin/users',async route=>{if(route.request().method()!=='POST') {await route.continue();return;}writes++;expect((await route.fetch()).status()).toBe(201);await route.abort('failed');});
  await page.getByRole('button',{name:'Create user',exact:true}).click();await expect(page.locator('#account-content')).toContainText('outcome is unknown');await expect(page.getByRole('button',{name:'Create user',exact:true})).toBeDisabled();expect(writes).toBe(1);
  page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Reload current values',exact:true}).click();await expect(page.locator('#account-content tr').filter({hasText:username})).toHaveCount(1);expect(writes).toBe(1);
  expect((await (await api(request,'GET','/admin/users')).json()).users.filter(user=>user.username===username)).toHaveLength(1);
});
test('a late administration response cannot repopulate the page after sign-out',async({page})=>{
  await login(page);await page.goto('/admin/');await expect(page.getByRole('heading',{name:'General settings',exact:true})).toBeVisible();
  await page.evaluate(()=>{const original=window.fetch;window.fetch=async(...args)=>{const response=await original(...args);if(args[0]==='/api/v1/admin/users') {window.adminReplyCaptured=true;await new Promise(resolve=>{window.releaseAdminReply=resolve;});}return response;};});
  await page.getByRole('link',{name:'Users',exact:true}).click();await expect.poll(()=>page.evaluate(()=>window.adminReplyCaptured)).toBe(true);
  await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.getByRole('link',{name:'Sign in to administration',exact:true})).toBeVisible();
  await page.evaluate(()=>window.releaseAdminReply());await expect(page.getByRole('heading',{name:'Independent accounts',exact:true})).toHaveCount(0);await expect(page.locator('#account-navigation')).toBeHidden();
});

async function settingsAdministrator(page,request) {
  const username='settings-'+randomUUID().slice(0,8),secret='Independent settings administrator password';
  const response=await api(request,'POST','/admin/users',{username,password:secret,displayName:'Settings operator',email:username+'@example.invalid',administrator:true});expect(response.status()).toBe(201);
  const user=(await response.json()).user;
  await login(page,username,secret);await page.goto('/admin/');await expect(page.getByRole('heading',{name:'General settings',exact:true})).toBeVisible();
  return {user,secret,async close() {
    const current=await api(request,'GET','/admin/users/'+key(user));expect(current.status()).toBe(200);const value=(await current.json()).user;
    const disabled=await api(request,'PUT','/admin/users/'+key(user),{username:value.username,displayName:value.displayName,enabled:false,administrator:false},{'If-Match':current.headers().etag});expect(disabled.status()).toBe(200);
  }};
}
function configurationInput(data,secret,changes={}) {
  const {originEpoch,...saved}=data.saved;return {...saved,currentPassword:secret,smtpPasswordAction:'PRESERVE',...changes};
}
async function settingsWrite(page,response,data) {
  const auth=await browserRequest(page).get('/api/v1/auth/session');expect(auth.status()).toBe(200);
  return browserRequest(page).put('/api/v1/admin/settings',{headers:{'If-Match':response.headers().etag,'X-CSRF-Token':(await auth.json()).csrf},data});
}
test('administration saves shared settings for restart and keeps SMTP credentials write-only with independent autocomplete',async({page,request})=>{
  const admin=await settingsAdministrator(page,request);
  try {
    const before=await browserRequest(page).get('/api/v1/admin/settings'),initial=await before.json(),secret='Synthetic SMTP secret '+randomUUID();
    const host='mail-'+randomUUID().slice(0,8)+'.example.invalid';
    await expect(page.getByLabel('SMTP username',{exact:true})).toHaveAttribute('autocomplete','section-smtp username');
    await expect(page.getByLabel('New SMTP password',{exact:true})).toHaveAttribute('autocomplete','section-smtp new-password');
    await expect(page.getByLabel('Your password',{exact:true})).toHaveAttribute('autocomplete','section-administrator current-password');
    await page.getByLabel('SMTP host',{exact:true}).fill(host);await page.getByLabel('New SMTP password',{exact:true}).fill(secret);await page.getByLabel('Your password',{exact:true}).fill('wrong password');
    await page.getByRole('button',{name:'Save settings',exact:true}).click();await expect(page.locator('.form-feedback')).toContainText('Confirm your password');
    await expect(page.getByLabel('SMTP host',{exact:true})).toHaveValue(host);await expect(page.getByLabel('New SMTP password',{exact:true})).toHaveValue(secret);
    await page.getByLabel('Your password',{exact:true}).fill(admin.secret);await page.getByRole('button',{name:'Save settings',exact:true}).click();
    await expect(page.locator('.form-feedback')).toContainText('Settings saved. Restart');await expect(page.getByLabel('New SMTP password',{exact:true})).toHaveValue('');await expect(page.getByLabel('Your password',{exact:true})).toHaveValue('');
    const after=await browserRequest(page).get('/api/v1/admin/settings'),saved=await after.json();expect(saved.saved.smtpHost).toBe(host);expect(saved.effective).toEqual(initial.effective);expect(saved.restartRequired).toBe(true);expect(saved.smtpPasswordConfigured).toBe(true);expect(JSON.stringify(saved)).not.toContain(secret);
    await page.getByText('Saved and effective settings',{exact:true}).click();await expect(page.getByRole('row').filter({hasText:'SMTP host'})).toContainText(host);await expect(page.getByRole('row').filter({hasText:'SMTP host'})).toContainText(initial.effective.smtpHost);
    await page.getByLabel('Clear saved SMTP password',{exact:true}).check();await page.getByLabel('Your password',{exact:true}).fill(admin.secret);await page.getByRole('button',{name:'Save settings',exact:true}).click();await expect(page.getByLabel('Your password',{exact:true})).toHaveValue('');
    const cleared=await browserRequest(page).get('/api/v1/admin/settings');expect((await cleared.json()).smtpPasswordConfigured).toBe(false);
    await page.setViewportSize({width:1440,height:1000});await captureScreenshot(page,{path:'test-results/administration-editable-desktop.png',fullPage:true});
    await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    const audit=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations.map(value=>value.id)).toEqual([]);await captureScreenshot(page,{path:'test-results/administration-editable-narrow.png',fullPage:true});
  } finally {await admin.close();}
});
test('administration preserves service-setting drafts after a peer save and reloads only on request',async({page,request})=>{
  const admin=await settingsAdministrator(page,request);
  try {
    const current=await browserRequest(page).get('/api/v1/admin/settings'),data=await current.json();
    await page.getByLabel('SMTP host',{exact:true}).fill('unsaved-draft.example.invalid');await page.getByLabel('Your password',{exact:true}).fill(admin.secret);
    page.once('dialog',dialog=>dialog.dismiss());await page.getByRole('link',{name:'Users',exact:true}).click();await expect(page.getByLabel('SMTP host',{exact:true})).toHaveValue('unsaved-draft.example.invalid');
    const peer=await settingsWrite(page,current,configurationInput(data,admin.secret,{smtpHost:'peer-change.example.invalid'}));expect(peer.status()).toBe(200);
    await page.getByRole('button',{name:'Save settings',exact:true}).click();await expect(page.locator('.form-feedback')).toContainText('Your draft is kept');await expect(page.getByLabel('SMTP host',{exact:true})).toHaveValue('unsaved-draft.example.invalid');
    page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Reload current values',exact:true}).click();await expect(page.getByLabel('SMTP host',{exact:true})).toHaveValue('peer-change.example.invalid');await expect(page.getByLabel('Your password',{exact:true})).toHaveValue('');
  } finally {await admin.close();}
});
test('administration never repeats a service-setting write after a lost reply',async({page,request})=>{
  const admin=await settingsAdministrator(page,request);let writes=0;
  try {
    await page.route('**/api/v1/admin/settings',async route=>{
      if(route.request().method()==='PUT') {writes++;await route.fetch();await route.abort('connectionfailed');}else await route.continue();
    });
    await page.getByLabel('SMTP host',{exact:true}).fill('committed-once.example.invalid');await page.getByLabel('Your password',{exact:true}).fill(admin.secret);await page.getByRole('button',{name:'Save settings',exact:true}).click();
    await expect(page.locator('.form-feedback')).toContainText('outcome is unknown');await expect(page.getByRole('button',{name:'Save settings',exact:true})).toBeDisabled();expect(writes).toBe(1);
    const actual=await browserRequest(page).get('/api/v1/admin/settings');expect((await actual.json()).saved.smtpHost).toBe('committed-once.example.invalid');expect(writes).toBe(1);
  } finally {await admin.close();}
});


test('Internet accounts administration checks registrations, links provider setup and fits all themes',async({page,request})=>{
  test.setTimeout(120000);const admin=await settingsAdministrator(page,request),client=browserRequest(page);
  const actual=await client.get('/api/v1/admin/internet-accounts');expect(actual.status()).toBe(200);expect(actual.headers()['cache-control']).toBe('no-store');
  const local=await actual.json();expect(Object.keys(local).sort()).toEqual(['mode','status','publicOrigin','callbackUrl','serviceUrl','discoveryUrl','googleRedirectUrl','googleConfigured','microsoftConfigured','version','effectiveVersion','restartRequired','canEdit','saved','effective'].sort());
  const sample={...local,publicOrigin:'https://nas.example-tailnet.ts.net:8443',callbackUrl:'https://nas.example-tailnet.ts.net:8443/web/task-provider-callback.html',mode:'SHARED',status:'NOT_CHECKED',serviceUrl:'https://connect.example.test',googleRedirectUrl:'https://connect.example.test/oauth/google/callback',googleConfigured:null,microsoftConfigured:null};
  let checks=0,unavailable=false;const external=[];page.on('request',request=>{if(new URL(request.url()).origin!==origin())external.push(request.url());});
  await page.route('**/api/v1/admin/internet-accounts*',route=>{
    const check=new URL(route.request().url()).searchParams.get('check')==='true';if(check)checks++;
    return route.fulfill({headers:{'X-Timekeeper-Owner':actual.headers()['x-timekeeper-owner'],ETag:actual.headers().etag},json:check?{...sample,status:unavailable?'UNAVAILABLE':'READY',googleConfigured:unavailable?null:true,microsoftConfigured:unavailable?null:false}:sample});
  });
  try{
    await page.getByRole('link',{name:'Internet accounts',exact:true}).click();await expect(page.getByRole('heading',{name:'OAuth setup guidance',exact:true})).toBeVisible();expect(checks).toBe(0);
    const check=page.getByRole('button',{name:'Check setup',exact:true});await check.focus();await check.press('Enter');await expect(page.locator('#account-content')).toContainText('Setup checks passed.');expect(checks).toBe(1);
    await expect(page.locator('#account-content tr').filter({hasText:'Google registration'})).toContainText('Configured');await expect(page.locator('#account-content tr').filter({hasText:'Microsoft registration'})).toContainText('Not configured');
    await expect(page.locator('#account-content')).toContainText('https://connect.example.test/oauth/google/callback');await expect(page.locator('#account-content')).toContainText('Dagsverk sign-in is separate');await expect(page.getByLabel('New Google client secret',{exact:true})).toHaveValue('');
    for(const [name,url]of [
      ['Google Auth Platform → Clients','https://console.cloud.google.com/auth/clients'],
      ['Google Calendar API','https://console.cloud.google.com/apis/library/calendar-json.googleapis.com'],
      ['Google Tasks API','https://console.cloud.google.com/apis/library/tasks.googleapis.com'],
      ['Microsoft Entra → App registrations','https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade']
    ])await expect(page.getByRole('link',{name,exact:true})).toHaveAttribute('href',url);
    for(const width of [1440,390]){
      await page.setViewportSize({width,height:900});for(const theme of ['timekeeper','timekeeper-dark']){
        await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=(theme==='timekeeper')?'light':'dark';},theme);
        expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
        await captureScreenshot(page,{path:`test-results/admin-internet-${theme}-${width}.png`,fullPage:true});
      }
    }
    unavailable=true;await check.click();await expect(page.locator('#account-content [role=status]').filter({hasText:'Managed OAuth discovery'})).toBeVisible();await expect(check).toBeEnabled();
    const identity=await(await client.get('/api/v1/auth/session')).json(),profile=await client.get('/api/v1/me');const me=await profile.json();expect((await client.put('/api/v1/me',{headers:{'If-Match':profile.headers().etag,'X-CSRF-Token':identity.csrf},data:{zoneId:me.zoneId,locale:'nb-NO'}})).ok()).toBe(true);
    await page.reload();await expect(page.getByRole('heading',{name:'Veiledning for OAuth-oppsett',exact:true})).toBeVisible();await page.setViewportSize({width:320,height:900});await expect(page.getByRole('button',{name:'Kontroller oppsett',exact:true})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await captureScreenshot(page,{path:'test-results/admin-internet-nb-320.png',fullPage:true});expect(external).toEqual([]);
  }finally{await admin.close();}
});


test('OAuth application settings preserve, replace and clear a write-only secret for restart',async({page,request})=>{
  const admin=await settingsAdministrator(page,request),client=browserRequest(page),path='/api/v1/admin/internet-accounts';
  const initial=await client.get(path),before=await initial.json();const secret='Private synthetic Google client secret';
  try{
    await page.getByRole('link',{name:'Internet accounts',exact:true}).click();await expect(page.getByRole('heading',{name:'OAuth application settings',exact:true})).toBeVisible();
    await page.getByLabel('OAuth mode',{exact:true}).selectOption('DIRECT');await page.getByLabel('Google client ID',{exact:true}).fill('synthetic-google-client');await page.getByLabel('New Google client secret',{exact:true}).fill(secret);await page.getByLabel('Your password',{exact:true}).fill(admin.secret);await page.getByRole('button',{name:'Save settings',exact:true}).click();
    await expect(page.getByLabel('New Google client secret',{exact:true})).toHaveValue('');await expect(page.getByLabel('Your password',{exact:true})).toHaveValue('');await expect(page.locator('#account-content')).toContainText('Restart every Dagsverk server instance');
    let response=await client.get(path),saved=await response.json();expect(saved.saved.googleClientId).toBe('synthetic-google-client');expect(saved.saved.googleClientSecretConfigured).toBe(true);expect(saved.effective).toEqual(before.effective);expect(JSON.stringify(saved)).not.toContain(secret);expect(await(await client.get('/api/v1/admin/settings')).text()).not.toContain(secret);
    await page.getByLabel('Google client ID',{exact:true}).fill('updated-synthetic-google-client');await page.getByLabel('Your password',{exact:true}).fill(admin.secret);await page.getByRole('button',{name:'Save settings',exact:true}).click();await expect(page.getByLabel('Your password',{exact:true})).toHaveValue('');saved=await(await client.get(path)).json();expect(saved.saved.googleClientSecretConfigured).toBe(true);
    await page.getByLabel('Clear saved Google client secret',{exact:true}).check();await page.getByLabel('Your password',{exact:true}).fill(admin.secret);await page.getByRole('button',{name:'Save settings',exact:true}).click();await expect(page.getByLabel('Clear saved Google client secret',{exact:true})).not.toBeChecked();saved=await(await client.get(path)).json();expect(saved.saved.googleClientSecretConfigured).toBe(false);await captureScreenshot(page,{path:'test-results/admin-google-edit.png',fullPage:true});
  }finally{
    const response=await client.get(path),identity=await(await client.get('/api/v1/auth/session')).json();const restored=await client.put(path,{headers:{'If-Match':response.headers().etag,'X-CSRF-Token':identity.csrf},data:{mode:before.saved.mode,serviceUrl:before.saved.serviceUrl,googleClientId:before.saved.googleClientId,googleSecretAction:'CLEAR',currentPassword:admin.secret}});expect(restored.status()).toBe(200);await admin.close();
  }
});


test('checking Google setup cannot authorize overwriting a concurrently changed application registration',async({page,request})=>{
  const admin=await settingsAdministrator(page,request),client=browserRequest(page),path='/api/v1/admin/internet-accounts';
  const initial=await client.get(path),data=await initial.json(),tag=initial.headers().etag,headers={'X-Timekeeper-Owner':initial.headers()['x-timekeeper-owner']};let writes=0;
  await page.route('**/api/v1/admin/internet-accounts*',async route=>{
    if(route.request().method()==='PUT'){
      writes++;expect(route.request().headers()['if-match']).toBe(tag);return route.fulfill({status:412,headers,json:{code:'STALE_VERSION'}});
    }
    const checked=new URL(route.request().url()).searchParams.has('check');return route.fulfill({headers:{...headers,ETag:checked?'"service-settings-999999"':tag},json:{...data,status:checked?'READY':data.status,version:checked?999999:data.version,saved:{...data.saved,googleClientId:checked?'peer-registration':data.saved.googleClientId}}});
  });
  try{
    await page.getByRole('link',{name:'Internet accounts',exact:true}).click();await page.getByLabel('OAuth mode',{exact:true}).selectOption('DIRECT');await page.getByLabel('Google client ID',{exact:true}).fill('my-registration-draft');await page.getByLabel('New Google client secret',{exact:true}).fill('Private replacement draft');await page.getByLabel('Your password',{exact:true}).fill(admin.secret);
    await page.getByRole('button',{name:'Check setup',exact:true}).click();await expect(page.locator('#account-content')).toContainText('Setup checks passed.');await page.getByRole('button',{name:'Save settings',exact:true}).click();await expect(page.locator('#account-content')).toContainText('Your draft is kept');expect(writes).toBe(1);await expect(page.getByLabel('Google client ID',{exact:true})).toHaveValue('my-registration-draft');await expect(page.getByLabel('New Google client secret',{exact:true})).toHaveValue('Private replacement draft');
  }finally{await admin.close();}
});
