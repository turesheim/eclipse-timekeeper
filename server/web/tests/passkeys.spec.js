import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {openAccountMenu} from './navigation.js';
import {browserRequest} from './browser-request.js';

test.skip(({browserName})=>browserName!=='chromium' || process.env.TIMEKEEPER_WEB_PASSKEY_FIXTURE!=='true','Requires the HTTPS localhost RP and Chromium virtual authenticators');
const password='a private virtual passkey fixture password';
async function login(page,user) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');
  await page.locator('#username').fill(user.username);await page.locator('#password').fill(user.password);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
}
async function account(page) {await openAccountMenu(page);await page.getByRole('link',{name:'My Account',exact:true}).click();await page.getByRole('link',{name:'Passkeys',exact:true}).click();await expect(page.locator('#account-content')).toHaveAttribute('aria-busy','false');}
async function signOut(page) {await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login')).toBeVisible();}
const addForm=page=>page.getByRole('heading',{name:'Add passkey',exact:true}).locator('..');
async function fresh(request) {
  const username='key-'+randomUUID().slice(0,8);
  const reply=await request.post('/api/v1/auth/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});
  expect(reply.status()).toBe(201);return {...(await reply.json()).user,password};
}
async function virtual(page,automatic=true) {
  const cdp=await page.context().newCDPSession(page);await cdp.send('WebAuthn.enable',{enableUI:false});
  const {authenticatorId}=await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:automatic}});
  return {cdp,id:authenticatorId,presence:enabled=>cdp.send('WebAuthn.setAutomaticPresenceSimulation',{authenticatorId,enabled})};
}
test.beforeAll(async({browser,request})=>{
  if(!(await (await request.get('/api/v1/auth/status')).json()).setupRequired) return;
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true}),page=await context.newPage();
  try {
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('setup');
    await page.locator('#setup-code').fill(process.env.TIMEKEEPER_WEB_SETUP_CODE);await page.locator('#username').fill('browser-admin');
    await page.locator('#setup-email').fill('browser-admin@example.invalid');await page.locator('#password').fill(process.env.TIMEKEEPER_WEB_PASSWORD);await page.locator('#password-confirm').fill(process.env.TIMEKEEPER_WEB_PASSWORD);
    await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  } finally {await context.close();}
});

test('passkeys: add with password and fresh passkey proof, rename, Remember me and remove the current key',async({page,request})=>{
  const user=await fresh(request),first=await virtual(page);await login(page,user);await account(page);
  const add=addForm(page);await add.getByLabel('Passkey name',{exact:true}).fill('Laptop key');await add.getByLabel('Current password',{exact:true}).fill(password);await add.getByRole('button',{name:'Add passkey',exact:true}).click();
  await expect(add).toContainText('Passkey added.');await expect(page.locator('#account-content tr').filter({hasText:'Laptop key'})).toHaveCount(1);
  const {authenticatorId:second}=await first.cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'usb',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:false}});
  await page.route('**/api/v1/auth/passkeys/register/options',async route=>{
    const response=await route.fetch();await first.presence(false);await first.cdp.send('WebAuthn.setAutomaticPresenceSimulation',{authenticatorId:second,enabled:true});await route.fulfill({response});
  },{times:1});
  await add.getByLabel('Passkey name',{exact:true}).fill('Security key');await add.getByLabel('Confirm with',{exact:true}).selectOption('passkey');await add.getByRole('button',{name:'Add passkey',exact:true}).click();
  await expect(page.locator('#account-content tr').filter({hasText:'Security key'})).toHaveCount(1);
  await page.getByRole('button',{name:'Rename Laptop key',exact:true}).click();
  const rename=page.getByRole('heading',{name:'Rename “Laptop key”',exact:true}).locator('..');await rename.getByLabel('Passkey name',{exact:true}).fill('Personal passkey');await rename.getByRole('button',{name:'Save name',exact:true}).click();
  await expect(page.locator('#account-content')).toContainText('Passkey renamed.');await expect(page.locator('#account-content tr').filter({hasText:'Personal passkey'})).toHaveCount(1);
  await signOut(page);await page.getByLabel('Remember me on this browser').check();await page.getByRole('button',{name:'Sign in with a passkey',exact:true}).click();await expect(page.locator('#owner')).toHaveText(user.username);
  const cookie=(await page.context().cookies()).find(value=>value.name==='__Host-timekeeper-session');expect(cookie.httpOnly).toBe(true);expect(cookie.secure).toBe(true);expect(cookie.expires).toBeGreaterThan(Date.now()/1000+28*86400);
  await page.reload();await expect(page.locator('#owner')).toHaveText(user.username);await account(page);
  await first.cdp.send('WebAuthn.setAutomaticPresenceSimulation',{authenticatorId:second,enabled:false});await first.presence(true);
  await page.getByRole('button',{name:'Remove Security key',exact:true}).click();const remove=page.getByRole('heading',{name:'Remove “Security key”?',exact:true}).locator('..');
  await remove.getByLabel('Confirm with',{exact:true}).selectOption('passkey');await remove.getByRole('button',{name:'Remove passkey',exact:true}).click();
  await expect(page.locator('#login')).toBeVisible();await expect(page.locator('#login-error')).toContainText('Its sessions were signed out');
  expect((await page.context().cookies()).some(value=>['__Host-timekeeper-session','__Host-timekeeper-browser'].includes(value.name))).toBe(false);
  await page.getByRole('button',{name:'Sign in with a passkey',exact:true}).click();await expect(page.locator('#owner')).toHaveText(user.username);await account(page);
  await page.getByRole('button',{name:'Remove Personal passkey',exact:true}).click();const last=page.getByRole('heading',{name:'Remove “Personal passkey”?',exact:true}).locator('..');await last.getByLabel('Current password',{exact:true}).fill(password);await last.getByRole('button',{name:'Remove passkey',exact:true}).click();
  await expect(page.locator('#login')).toBeVisible();
  expect((await first.cdp.send('WebAuthn.getCredentials',{authenticatorId:first.id})).credentials).toHaveLength(1);
  await page.getByRole('button',{name:'Sign in with a passkey',exact:true}).click();await expect(page.locator('#login-error')).toContainText('could not be verified');
  await login(page,user);await account(page);await expect(page.locator('#account-content')).toContainText('No passkeys added.');
  expect(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length,cookies:document.cookie}))).toEqual({local:0,session:0,cookies:''});
  await page.screenshot({path:'test-results/passkeys-empty-after-removal.png',fullPage:true});
});

test('passkeys: wrong fresh proof keeps the session, cancellation is normal and a new request still works',async({page,request})=>{
  const user=await fresh(request),key=await virtual(page,false);await login(page,user);await account(page);const add=addForm(page);
  await add.getByLabel('Passkey name',{exact:true}).fill('Canceled key');await add.getByLabel('Current password',{exact:true}).fill('an incorrect password');await add.getByRole('button',{name:'Add passkey',exact:true}).click();
  await expect(add).toContainText('Confirm your current password');expect((await browserRequest(page).get('/api/v1/auth/session')).status()).toBe(200);
  await add.getByLabel('Current password',{exact:true}).fill(password);await add.getByRole('button',{name:'Add passkey',exact:true}).click();
  const cancel=add.getByRole('button',{name:'Cancel passkey request',exact:true});await expect(cancel).toBeEnabled();await cancel.click();await expect(add).toContainText('Passkey request canceled.');
  await expect(page.locator('#account-content')).toHaveAttribute('aria-busy','false');await expect(page.locator('#account-content')).toContainText('No passkeys added.');
  await key.presence(true);await add.getByLabel('Passkey name',{exact:true}).fill('Working key');await add.getByLabel('Current password',{exact:true}).fill(password);await add.getByRole('button',{name:'Add passkey',exact:true}).click();await expect(add).toContainText('Passkey added.');
  await signOut(page);await key.presence(false);await page.getByRole('button',{name:'Sign in with a passkey',exact:true}).click();await expect(page.locator('#passkey-cancel')).toBeEnabled();
  const canceled=page.waitForResponse(response=>response.url().endsWith('/auth/passkeys/cancel'));await page.locator('#passkey-cancel').click();expect((await canceled).status()).toBe(204);
  await expect(page.getByRole('button',{name:'Sign in',exact:true})).toBeEnabled();await expect(page.locator('#login-error')).toContainText('canceled');
  await key.presence(true);await page.getByRole('button',{name:'Sign in with a passkey',exact:true}).click();await expect(page.locator('#owner')).toHaveText(user.username);
});

test('passkeys: a late assertion cannot replace another account signed in by a second tab',async({page,request})=>{
  const alice=await fresh(request),bob=await fresh(request),key=await virtual(page);await login(page,alice);await account(page);const add=addForm(page);
  await add.getByLabel('Passkey name',{exact:true}).fill('Alice only');await add.getByLabel('Current password',{exact:true}).fill(password);await add.getByRole('button',{name:'Add passkey',exact:true}).click();await expect(add).toContainText('Passkey added.');
  await signOut(page);await key.presence(false);await page.getByRole('button',{name:'Sign in with a passkey',exact:true}).click();await expect(page.locator('#passkey-cancel')).toBeEnabled();
  const peer=await page.context().newPage();
  try {
    await login(peer,bob);await key.presence(true);await expect(page.locator('#login-error')).toContainText('could not be verified');
    await expect(peer.locator('#owner')).toHaveText(bob.username);expect((await (await browserRequest(peer).get('/api/v1/auth/session')).json()).ownerId).toBe(bob.ownerId);
    await page.reload();await expect(page.locator('#owner')).toHaveText(bob.username);await account(page);await expect(page.locator('#account-content')).toContainText('No passkeys added.');
  } finally {await peer.close();}
});

test('passkeys: unsupported browsers keep password fallback and token-derived sessions cannot enroll',async({page,request,browser})=>{
  const user=await fresh(request);await page.addInitScript(()=>Object.defineProperty(window,'PublicKeyCredential',{value:undefined,configurable:true}));
  await page.goto('/web/');await expect(page.getByRole('button',{name:'Sign in with a passkey',exact:true})).toBeDisabled();await expect(page.locator('#passkey-support')).toContainText('unavailable in this browser');
  await login(page,user);await account(page);await expect(addForm(page).getByRole('button',{name:'Add passkey',exact:true})).toBeDisabled();
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true}),tokenPage=await context.newPage();
  try {
    await tokenPage.goto('/web/');await tokenPage.locator('#auth-mode').selectOption('token');await tokenPage.locator('#token').fill(process.env.TIMEKEEPER_WEB_TOKEN);await tokenPage.getByRole('button',{name:'Sign in',exact:true}).click();await expect(tokenPage.locator('#status')).toHaveText('Up to date');await account(tokenPage);
    await expect(tokenPage.locator('#account-content')).toContainText('An API token cannot authorize this change');await expect(tokenPage.getByRole('heading',{name:'Add passkey',exact:true})).toHaveCount(0);
    await tokenPage.getByRole('button',{name:'Sign in with your account',exact:true}).click();await expect(tokenPage.locator('#login')).toBeVisible();
  } finally {await context.close();}
});
