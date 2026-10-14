import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
const password=process.env.TIMEKEEPER_WEB_PASSWORD;
const token=process.env.TIMEKEEPER_WEB_TOKEN;
async function login(page,username='browser-admin',secret=password) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.getByLabel('Username',{exact:true}).fill(username);await page.getByLabel('Password',{exact:true}).fill(secret);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
}
async function call(request,method,path,data,headers={}) {return request.fetch(`/api/v1${path}`,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});}
test('first administrator claims the explicit existing owner once and uses a secure browser cookie',async({page,request})=>{
  const status=await (await request.get('/api/v1/auth/status')).json();expect(status.setupRequired).toBe(true);
  await page.goto('/web/');await expect(page.locator('#auth-mode')).toHaveValue('setup');
  await page.getByLabel('One-time setup or recovery code').fill(process.env.TIMEKEEPER_WEB_SETUP_CODE);
  await page.getByLabel('Username',{exact:true}).fill('browser-admin');await page.getByLabel('Password',{exact:true}).fill(password);await page.getByLabel('Display name',{exact:true}).fill('Browser administrator');await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  await expect(page.locator('#owner')).toHaveText('Browser administrator');
  const cookie=(await page.context().cookies()).find(value=>value.name==='__Host-timekeeper-session');expect(cookie.httpOnly).toBe(true);expect(cookie.secure).toBe(true);expect(cookie.sameSite).toBe('Lax');expect(cookie.expires).toBe(-1);
  expect(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length,cookies:document.cookie}))).toEqual({local:0,session:0,cookies:''});
  const account=await (await call(request,'GET','/auth/account')).json();expect(account.ownerId).toBe('web-owner');expect(account.username).toBe('browser-admin');
  expect((await request.post('/api/v1/auth/setup',{data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'hijacker',password,displayName:'Other'}})).status()).toBe(401);
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');
  await page.screenshot({path:'test-results/account-signed-in.png',fullPage:true});
});
test('account UI creates an isolated user and named Eclipse token shown once',async({page,request,browser})=>{
  const suffix=randomUUID().slice(0,8),username='user-'+suffix,display='Private user '+suffix,otherPassword='private passphrase '+suffix;
  await login(page);await page.getByRole('button',{name:'Account',exact:true}).click();
  await page.getByLabel('New user username',{exact:true}).fill(username);await page.getByLabel('New user display name',{exact:true}).fill(display);await page.getByLabel('Temporary password',{exact:true}).fill(otherPassword);await page.getByRole('button',{name:'Create user',exact:true}).click();await expect(page.locator('#account-content summary').filter({hasText:display})).toBeVisible();
  const peer=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL}),other=await peer.newPage();
  try {
    await login(other,username,otherPassword);await expect(other.locator('#owner')).toHaveText(display);
    await other.getByRole('button',{name:'Clients',exact:true}).click();await other.getByRole('button',{name:'New client',exact:true}).click();await other.getByLabel('Name',{exact:true}).fill('Private client '+suffix);await other.getByLabel('Color',{exact:true}).fill('#112233');await other.locator('#save').click();await expect(other.locator('#editor')).not.toBeVisible();
    await other.getByRole('button',{name:'Account',exact:true}).click();await expect(other.getByRole('heading',{name:'User accounts',exact:true})).not.toBeVisible();
    await other.getByLabel('Token name',{exact:true}).fill('Eclipse '+suffix);await other.getByRole('button',{name:'Create token',exact:true}).click();const raw=await other.getByLabel('New API token',{exact:true}).inputValue();expect(raw).toMatch(/^tkp_/);
    const own=await request.get('/api/v1/clients',{headers:{Authorization:`Bearer ${raw}`}});expect(own.status()).toBe(200);const id=(await own.json()).items.find(value=>value.name==='Private client '+suffix).id;
    expect((await call(request,'GET','/clients/'+id)).status()).toBe(404);
    const exported=await (await call(request,'GET','/data/export')).json();expect(JSON.stringify(exported)).not.toContain('Private client '+suffix);
    await other.getByRole('button',{name:'Close',exact:true}).click();await other.getByRole('button',{name:'Account',exact:true}).click();await expect(other.getByLabel('New API token',{exact:true})).not.toBeVisible();
    await other.getByRole('button',{name:'Revoke',exact:true}).click();await expect(other.locator('#account-content')).toContainText('Revoked');expect((await request.get('/api/v1/me',{headers:{Authorization:`Bearer ${raw}`}})).status()).toBe(401);
    await other.getByRole('button',{name:'Close',exact:true}).click();await other.getByRole('button',{name:'Sign out',exact:true}).click();await expect(other.locator('#login')).toBeVisible();await expect(other.locator('#clients')).toBeEmpty();
    await login(other);await other.getByRole('button',{name:'Clients',exact:true}).click();await expect(other.locator('#clients')).not.toContainText('Private client '+suffix);
  } finally {await peer.close();}
  await page.screenshot({path:'test-results/account-administration.png',fullPage:true});
});
test('remember me persists a cookie while local storage holds no password or bearer token',async({page})=>{
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.getByLabel('Username',{exact:true}).fill('browser-admin');await page.getByLabel('Password',{exact:true}).fill(password);await page.getByLabel('Remember me on this browser').check();await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  const cookie=(await page.context().cookies()).find(value=>value.name==='__Host-timekeeper-session');expect(cookie.expires).toBeGreaterThan(Date.now()/1000+28*86400);expect(await page.evaluate(()=>localStorage.length)).toBe(0);
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login')).toBeVisible();expect((await page.context().cookies()).some(value=>value.name==='__Host-timekeeper-session')).toBe(false);
});
