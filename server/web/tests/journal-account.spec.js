import {test,expect} from '@playwright/test';
import {randomUUID,generateKeyPairSync} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {captureScreenshot} from './screenshots.js';

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
const settings=page=>page.locator('.account-card').filter({has:page.getByRole('heading',{name:'Synchronization settings',exact:true})});
const enroll=page=>page.locator('.account-card').filter({has:page.getByRole('heading',{name:'Register or rotate a device key',exact:true})});
function key(){const der=generateKeyPairSync('ed25519').publicKey.export({type:'spki',format:'der'});return 'ssh-ed25519 '+Buffer.concat([Buffer.from('0000000b7373682d6564323535313900000020','hex'),der.subarray(-32)]).toString('base64')+' timekeeper-journal';}

test('journal synchronization account setup enrolls public keys, downloads actual endpoint profiles and revokes them',async({browser,request})=>{
  const context=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB'}),page=await context.newPage();
  try{
    const user=await fresh(request);await signIn(page,user);
    await settings(page).getByLabel('Enable synchronization').check();await settings(page).getByLabel('Current password',{exact:true}).fill(user.password);await settings(page).getByRole('button',{name:'Save synchronization settings'}).click();await expect(settings(page)).toContainText('Synchronization settings saved.');
    await expect(page.getByText('journal.example.invalid:2222')).toBeVisible();
    let privateUploads=0;page.on('request',r=>{if(r.postData()?.includes('BEGIN OPENSSH PRIVATE KEY'))privateUploads++;});
    await enroll(page).getByLabel('Public SSH key (.pub)',{exact:true}).fill('-----BEGIN OPENSSH PRIVATE KEY-----\nnever-upload-this\n-----END OPENSSH PRIVATE KEY-----');await expect(enroll(page).getByLabel('Public SSH key (.pub)',{exact:true})).toHaveValue('');expect(privateUploads).toBe(0);
    await enroll(page).getByLabel('Device name').fill('Laptop');await enroll(page).getByLabel('Public SSH key (.pub)',{exact:true}).fill(key());await enroll(page).getByLabel('Current password',{exact:true}).fill(user.password);await enroll(page).getByRole('button',{name:'Register public key'}).click();await expect(enroll(page)).toContainText('Public key registered.');await expect(enroll(page).getByLabel('Current password',{exact:true})).toHaveValue('');
    const download=page.waitForEvent('download');await page.getByRole('button',{name:'Download profile',exact:true}).click();const file=await download,stream=await file.createReadStream();let text='';for await(const chunk of stream)text+=chunk;const profile=JSON.parse(text);expect(profile.hostname).toBe('journal.example.invalid');expect(profile.port).toBe(2222);expect(profile.username).toBe(user.transport.username);expect(text).not.toContain(user.transport.token);expect(text).not.toContain(user.password);
    expect((await new AxeBuilder({page}).include('#account-page').analyze()).violations).toEqual([]);
    await page.setViewportSize({width:320,height:740});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await captureScreenshot(page,{path:'test-results/journal-account-mobile.png',fullPage:true});
    await enroll(page).getByLabel('Device name').fill('Replacement laptop');await enroll(page).getByLabel('Replace a device key').selectOption(profile.deviceId);await enroll(page).getByLabel('Public SSH key (.pub)',{exact:true}).fill(key());await enroll(page).getByLabel('Current password',{exact:true}).fill(user.password);await enroll(page).getByRole('button',{name:'Register public key'}).click();await expect(enroll(page)).toContainText('Public key registered.');await expect(page.getByText('Revoked',{exact:true})).toHaveCount(1);
    await page.getByRole('button',{name:'Revoke key',exact:true}).click();const revoke=page.locator('.account-card').filter({has:page.getByRole('heading',{name:'Revoke Replacement laptop',exact:true})});await revoke.getByLabel('Current password',{exact:true}).fill(user.password);await revoke.getByRole('button',{name:'Revoke key',exact:true}).click();await expect(page.getByText('Revoked',{exact:true})).toHaveCount(2);for(const profileButton of await page.getByRole('button',{name:'Download profile',exact:true}).all())await expect(profileButton).toBeDisabled();
  }finally{await context.close();}
});

test('journal synchronization read-only sessions can refresh status repeatedly without gaining credential controls',async({browser})=>{
  const context=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB'}),page=await context.newPage();
  try{
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_READ_TOKEN);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#workspace')).toBeVisible();await page.evaluate(()=>location.hash='#account/journal');await expect(page.getByRole('heading',{name:'Journal',exact:true})).toBeVisible();
    await expect(page.getByText('Sign in with your account password or a passkey to manage journal credentials. An API token or token-derived browser session cannot register, rotate or revoke keys.')).toBeVisible();
    for(let i=0;i<2;i++){const refresh=page.getByRole('button',{name:'Refresh status',exact:true});await refresh.click();await expect(refresh).toBeEnabled();}
    await expect(settings(page).getByRole('button',{name:'Save synchronization settings'})).toBeDisabled();await expect(enroll(page).getByRole('button',{name:'Register public key'})).toBeDisabled();await expect(page.getByRole('button',{name:'Retry pending imports'})).toBeDisabled();
  }finally{await context.close();}
});

test('journal synchronization reports missing prerequisites and keeps credential drafts behind navigation guards',async({browser,request})=>{
  const context=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB'}),page=await context.newPage();
  try{
    const user=await fresh(request,false);await signIn(page,user);await expect(page.getByText('Ask the operator to bind a restricted SSH identity and private incoming directory to this account.')).toBeVisible();await expect(enroll(page).getByRole('button',{name:'Register public key'})).toBeDisabled();
    await request.post(fixture()+'/accounts',{data:{ownerId:(await(await browserRequest(page).get('/api/v1/auth/session')).json()).ownerId}});await page.getByRole('button',{name:'Refresh status',exact:true}).click();await expect(enroll(page).getByRole('button',{name:'Register public key'})).toBeEnabled();
    await enroll(page).getByLabel('Device name').fill('Draft laptop');let pending=page.waitForEvent('dialog'),click=page.locator('#account-navigation').getByRole('link',{name:'Profile',exact:true}).click();await(await pending).dismiss();await click;await expect(page).toHaveURL(/#account\/journal$/);await expect(enroll(page).getByLabel('Device name')).toHaveValue('Draft laptop');
    pending=page.waitForEvent('dialog');click=page.locator('#account-navigation').getByRole('link',{name:'Profile',exact:true}).click();await(await pending).accept();await click;await expect(page.getByRole('heading',{name:'Journal',exact:true})).not.toBeVisible();
  }finally{await context.close();}
});
