import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
const password=process.env.TIMEKEEPER_WEB_PASSWORD;
const token=process.env.TIMEKEEPER_WEB_TOKEN;
async function login(page,username='browser-admin',secret=password) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.getByLabel('Username',{exact:true}).fill(username);await page.getByLabel('Password',{exact:true}).fill(secret);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
}
async function call(request,method,path,data,headers={}) {return request.fetch(`/api/v1${path}`,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});}
test('setup and recovery keep one-time codes separate from password fields',async({page,request})=>{
  await page.goto('/web/');await expect(page.locator('#auth-mode')).toHaveValue('setup');
  const code=page.getByLabel('One-time setup or recovery code');
  await expect(code).toHaveAttribute('type','text');await expect(code).toHaveAttribute('autocomplete','one-time-code');
  await expect(page.locator('#login-form input[type="password"]:enabled')).toHaveCount(2);
  await page.locator('#auth-mode').selectOption('login');await expect(code).toBeDisabled();await expect(page.locator('#password-confirm')).toBeDisabled();
  await expect(page.locator('#password')).toHaveAttribute('autocomplete','current-password');
  await page.locator('#auth-mode').selectOption('token');await expect(page.locator('#token')).toBeEnabled();await expect(page.locator('#password')).toBeDisabled();
  await page.locator('#auth-mode').selectOption('recover');await expect(code).toBeEnabled();await expect(page.locator('#token')).toBeDisabled();
  await expect(page.locator('#login-form input[type="password"]:enabled')).toHaveCount(2);
  await page.locator('#auth-mode').selectOption('setup');await page.getByLabel('Recovery email',{exact:true}).fill('browser-admin@example.org');await expect(page.locator('#password')).toHaveAttribute('autocomplete','new-password');
  await code.fill(process.env.TIMEKEEPER_WEB_SETUP_CODE);
  await page.getByLabel('Username',{exact:true}).fill('browser-admin');await page.getByLabel('Password',{exact:true}).fill(password);await page.getByLabel('Confirm password',{exact:true}).fill(password+' different');
  await expect(code).toHaveValue(process.env.TIMEKEEPER_WEB_SETUP_CODE);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#login-error')).toHaveText('Passwords do not match.');
  expect((await (await request.get('/api/v1/auth/status')).json()).setupRequired).toBe(true);
});
test('rejected setup stays in setup and sign-in explains that no administrator exists yet',async({page,request})=>{
  await page.goto('/web/');await expect(page.locator('#auth-mode')).toHaveValue('setup');
  await expect(page.locator('#login-build')).toHaveText('SPK 1.99.0-146');
  const code=process.env.TIMEKEEPER_WEB_SETUP_CODE;
  await page.getByLabel('One-time setup or recovery code').fill((code[0]==='A' ? 'B' : 'A')+code.slice(1));
  await page.getByLabel('Username',{exact:true}).fill('browser-admin');await page.getByLabel('Recovery email',{exact:true}).fill('browser-admin@example.org');
  await page.getByLabel('Password',{exact:true}).fill(password);await page.getByLabel('Confirm password',{exact:true}).fill(password);
  const rejected=page.waitForResponse(response=>response.url().endsWith('/api/v1/auth/setup') && response.request().method()==='POST');
  await page.getByRole('button',{name:'Sign in',exact:true}).click();expect((await rejected).status()).toBe(401);
  await expect(page.locator('#login-error')).toContainText('The setup code was not accepted.');
  await expect(page.locator('#auth-mode')).toHaveValue('setup');await expect(page.locator('#username')).toHaveValue('browser-admin');
  for(const id of ['setup-code','password','password-confirm']) await expect(page.locator('#'+id)).toHaveValue('');
  expect((await (await request.get('/api/v1/auth/status')).json()).setupRequired).toBe(true);
  await page.locator('#auth-mode').selectOption('login');await page.getByLabel('Password',{exact:true}).fill(password);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.locator('#login-error')).toContainText('First administrator setup has not completed.');
  await expect(page.locator('#auth-mode')).toHaveValue('setup');await expect(page.locator('#username')).toHaveValue('browser-admin');
});
test('first administrator claims the explicit existing owner once and uses a secure browser cookie',async({page,request})=>{
  const status=await (await request.get('/api/v1/auth/status')).json();expect(status.setupRequired).toBe(true);
  await page.goto('/web/');await expect(page.locator('#auth-mode')).toHaveValue('setup');
  await page.screenshot({path:'test-results/account-setup.png',fullPage:true});
  await page.getByLabel('One-time setup or recovery code').fill(' '+process.env.TIMEKEEPER_WEB_SETUP_CODE+' ');
  await expect(page.getByLabel('Display name',{exact:true})).toHaveCount(0);
  await page.getByLabel('Recovery email',{exact:true}).fill('browser-admin@example.org');
  await page.getByLabel('Username',{exact:true}).fill('browser-admin');await page.getByLabel('Password',{exact:true}).fill(password);await page.getByLabel('Confirm password',{exact:true}).fill(password+' different');await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.locator('#login-error')).toHaveText('Passwords do not match.');expect((await (await request.get('/api/v1/auth/status')).json()).setupRequired).toBe(true);
  await page.getByLabel('Confirm password',{exact:true}).fill(password);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  await expect(page.locator('#owner')).toHaveText('browser-admin');
  const cookie=(await page.context().cookies()).find(value=>value.name==='__Host-timekeeper-session');expect(cookie.httpOnly).toBe(true);expect(cookie.secure).toBe(true);expect(cookie.sameSite).toBe('Lax');expect(cookie.expires).toBe(-1);
  expect(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length,cookies:document.cookie}))).toEqual({local:0,session:0,cookies:''});
  const account=await (await call(request,'GET','/auth/account')).json();expect(account.ownerId).toBe('web-owner');expect(account.username).toBe('browser-admin');
  expect((await request.post('/api/v1/auth/setup',{data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'hijacker',password,displayName:'Other',email:'other@example.org'}})).status()).toBe(401);
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');
  await expect(page.locator('#server-build')).toContainText('SPK 1.99.0-146');
  await page.locator('#server-build').click();await expect(page.locator('#server-spk')).toHaveText('1.99.0-146');
  await page.screenshot({path:'test-results/account-signed-in.png',fullPage:true});
  await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login')).toBeVisible();
  await login(page);await expect(page.locator('#owner')).toHaveText('browser-admin');
});
test('incorrect credentials explain the login failure without reporting an expired session',async({page})=>{
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');
  await page.getByLabel('Username',{exact:true}).fill('browser-admin');await page.getByLabel('Password',{exact:true}).fill('an incorrect password for this browser test');
  await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.locator('#login-error')).toHaveText('The username or password was not accepted.');
  await expect(page.locator('#auth-mode')).toHaveValue('login');await expect(page.locator('#username')).toHaveValue('browser-admin');await expect(page.locator('#password')).toHaveValue('');
});
test('account UI creates an isolated user and named Eclipse token shown once',async({page,request,browser})=>{
  const suffix=randomUUID().slice(0,8),username='user-'+suffix,display='Private user '+suffix,otherPassword='private passphrase '+suffix;
  await login(page);await page.locator('#users-link').click();await page.getByRole('link',{name:'Add user',exact:true}).click();
  await page.getByLabel('New user username',{exact:true}).fill(username);await page.getByLabel('New user recovery email',{exact:true}).fill(username+'@example.org');await page.getByLabel('Display name (optional)',{exact:true}).fill(display);await page.getByLabel('Temporary password',{exact:true}).fill(otherPassword);await page.getByLabel('Confirm temporary password',{exact:true}).fill(otherPassword);await page.getByRole('button',{name:'Create user',exact:true}).click();await expect(page.locator('#account-content tr').filter({hasText:display})).toBeVisible();
  const peer=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL}),other=await peer.newPage();
  try {
    await login(other,username,otherPassword);await expect(other.locator('#owner')).toHaveText(display);
    await other.getByRole('button',{name:'Clients',exact:true}).click();await other.getByRole('button',{name:'New client',exact:true}).click();await other.getByLabel('Name',{exact:true}).fill('Private client '+suffix);await other.getByLabel('Color',{exact:true}).fill('#112233');await other.locator('#save').click();await expect(other.locator('#editor')).not.toBeVisible();
    await other.getByRole('link',{name:'My Account',exact:true}).click();await other.getByRole('link',{name:'API tokens',exact:true}).click();await expect(other.getByRole('heading',{name:'User accounts',exact:true})).not.toBeVisible();
    await other.getByLabel('Token name',{exact:true}).fill('Eclipse '+suffix);await other.getByRole('button',{name:'Create token',exact:true}).click();const raw=await other.getByLabel('New API token',{exact:true}).inputValue();expect(raw).toMatch(/^tkp_/);
    const own=await request.get('/api/v1/clients',{headers:{Authorization:`Bearer ${raw}`}});expect(own.status()).toBe(200);const id=(await own.json()).items.find(value=>value.name==='Private client '+suffix).id;
    expect((await call(request,'GET','/clients/'+id)).status()).toBe(404);
    const exported=await (await call(request,'GET','/data/export')).json();expect(JSON.stringify(exported)).not.toContain('Private client '+suffix);
    await other.getByRole('link',{name:'Back to time tracking',exact:true}).click();await other.getByRole('link',{name:'My Account',exact:true}).click();await other.getByRole('link',{name:'API tokens',exact:true}).click();await expect(other.getByLabel('New API token',{exact:true})).not.toBeVisible();
    await other.getByRole('button',{name:'Revoke',exact:true}).click();await other.getByRole('button',{name:'Revoke token',exact:true}).click();await expect(other.locator('#account-content')).toContainText('Revoked');expect((await request.get('/api/v1/me',{headers:{Authorization:`Bearer ${raw}`}})).status()).toBe(401);
    await other.getByRole('link',{name:'Back to time tracking',exact:true}).click();await other.getByRole('button',{name:'Sign out',exact:true}).click();await expect(other.locator('#login')).toBeVisible();await expect(other.locator('#clients')).toBeEmpty();
    await login(other);await other.getByRole('button',{name:'Clients',exact:true}).click();await expect(other.locator('#clients')).not.toContainText('Private client '+suffix);
  } finally {await peer.close();}
  await page.screenshot({path:'test-results/account-administration.png',fullPage:true});
});
test('remember me persists a cookie while local storage holds no password or bearer token',async({page})=>{
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.getByLabel('Username',{exact:true}).fill('browser-admin');await page.getByLabel('Password',{exact:true}).fill(password);await page.getByLabel('Remember me on this browser').check();await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  const cookie=(await page.context().cookies()).find(value=>value.name==='__Host-timekeeper-session');expect(cookie.expires).toBeGreaterThan(Date.now()/1000+28*86400);expect(await page.evaluate(()=>localStorage.length)).toBe(0);
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login')).toBeVisible();expect((await page.context().cookies()).some(value=>value.name==='__Host-timekeeper-session')).toBe(false);
});
test('another tab changing the cookie clears the old account and rejects a late refresh',async({page,request})=>{
  const username='switched-'+randomUUID().slice(0,8),secret='separate account passphrase';
  const created=await call(request,'POST','/auth/users',{username,password:secret,displayName:'Other tab user',email:username+'@example.org',administrator:false});expect(created.status()).toBe(201);
  await login(page);
  let captured,release;const arrived=new Promise(resolve=>{captured=resolve;}),gate=new Promise(resolve=>{release=resolve;});
  await page.route('**/api/v1/clients?*',async route=>{const response=await route.fetch();captured();await gate;await route.fulfill({response});});
  await page.locator('#refresh').click();await arrived;
  const peer=await page.context().newPage();
  try {
    await peer.goto('/web/');await expect(peer.locator('#status')).toHaveText('Up to date');
    expect(await peer.evaluate(async body=>(await fetch('/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})).status,{username,password:secret,remember:false})).toBe(200);
    await peer.reload();await expect(peer.locator('#owner')).toHaveText('Other tab user');release();
    await expect(page.locator('#login')).toBeVisible();await expect(page.locator('#workspace')).not.toBeVisible();await expect(page.locator('#clients')).toBeEmpty();await expect(page.locator('#fields')).toBeEmpty();
    await page.unroute('**/api/v1/clients?*');await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await expect(page.locator('#owner')).toHaveText('Other tab user');
    await page.evaluate(()=>window.dispatchEvent(new PageTransitionEvent('pagehide',{persisted:true})));await expect(page.locator('#workspace')).not.toBeVisible();await expect(page.locator('#clients')).toBeEmpty();
    await page.evaluate(()=>window.dispatchEvent(new PageTransitionEvent('pageshow',{persisted:true})));await expect(page.locator('#owner')).toHaveText('Other tab user');await expect(page.locator('#status')).toHaveText('Up to date');
  } finally {release();await peer.close();}
});
test('API token expiry uses the account time zone even when the browser uses another zone',async({browser,request})=>{
  const profile=await call(request,'GET','/me'),original=await profile.json();
  const year=new Date().getUTCFullYear()+1,wall=`${year}-01-15T12:34`,name='Zone token '+randomUUID().slice(0,8);
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,timezoneId:'America/Los_Angeles',locale:'en-GB'}),page=await context.newPage();
  try {
    expect((await call(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag})).status()).toBe(200);
    await login(page);await page.getByRole('link',{name:'My Account',exact:true}).click();await page.getByRole('link',{name:'API tokens',exact:true}).click();
    await expect(page.locator('#account-content')).toContainText('Expiry times use Europe/Oslo');
    await page.getByLabel('Token name',{exact:true}).fill(name);await page.getByLabel('Token expiry (local time, optional)',{exact:true}).fill(wall);await page.getByRole('button',{name:'Create token',exact:true}).click();await expect(page.getByLabel('New API token',{exact:true})).toBeVisible();
    const list=await (await call(request,'GET','/auth/tokens')).json(),token=list.items.find(value=>value.name===name);expect(token.expiresAt).toBe(`${year}-01-15T11:34:00Z`);
    await expect(page.locator('#account-content tr').filter({hasText:name})).toContainText('12:34');
    expect((await call(request,'DELETE','/auth/tokens/'+token.id)).status()).toBe(204);
  } finally {const current=await call(request,'GET','/me');await call(request,'PUT','/me',{zoneId:original.zoneId},{'If-Match':current.headers().etag});await context.close();}
});
for(const explicitUtc of [false,true]) test(explicitUtc ? 'explicit UTC remains selected when signing in from Oslo':'an untouched account shows running work-session times in local time',async({browser,request})=>{
  const username='zone-'+randomUUID().slice(0,8),secret='local time zone test passphrase';
  expect((await call(request,'POST','/auth/users',{username,password:secret,displayName:username,email:username+'@example.org',administrator:false})).status()).toBe(201);
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,timezoneId:'Europe/Oslo',locale:'en-GB'}),page=await context.newPage();
  try {
    const login=await context.request.post('/api/v1/auth/login',{data:{username,password:secret,remember:false}});expect(login.status()).toBe(200);
    const account=await login.json(),headers={'X-CSRF-Token':account.csrf,'X-Timekeeper-Owner':Buffer.from(account.ownerId).toString('base64url')};
    const before=await context.request.get('/api/v1/me');expect((await before.json()).zoneId).toBe('UTC');
    if(explicitUtc) expect((await context.request.put('/api/v1/me',{data:{zoneId:'UTC'},headers:{...headers,'If-Match':before.headers().etag}})).status()).toBe(200);
    const created=await context.request.post('/api/v1/clients',{data:{name:'Local clock client',color:'#176653'},headers});expect(created.status()).toBe(201);
    const client=await created.json();
    const response=await context.request.post('/api/v1/work-sessions',{data:{title:'At the office',clientId:client.id,summary:'Local clock regression',start:'2025-10-16T07:59:20.123456789Z',end:null},headers});expect(response.status()).toBe(201);
    const session=await response.json();
    await page.goto('/web/');await expect(page.locator('#status')).toHaveText('Up to date');
    const clock=explicitUtc ? '07:59:20':'09:59:20';
    await expect(page.locator('#work-session-current')).toContainText(clock);
    if(!explicitUtc) {await expect(page.locator('#work-session-current')).not.toContainText('UTC');await expect(page.locator('#zone-caption')).toContainText('Europe/Oslo');}
    const profile=await (await context.request.get('/api/v1/me')).json();expect(profile.zoneId).toBe(explicitUtc ? 'UTC':'Europe/Oslo');expect(profile.version).toBe(1);
    expect(await (await context.request.get('/api/v1/work-sessions/'+session.id)).json()).toEqual(session);
    await page.reload();await expect(page.locator('#work-session-current')).toContainText(clock);
    expect((await (await context.request.get('/api/v1/me')).json()).version).toBe(1);
    await page.screenshot({path:`test-results/work-session-${explicitUtc ? 'explicit-utc':'local-time'}.png`,fullPage:true});
  } finally {await context.close();}
});
