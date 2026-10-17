import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';

const mailbox=process.env.TIMEKEEPER_WEB_MAIL_FIXTURE;
test.skip(!mailbox,'Requires the HTTPS and loopback SMTP acceptance fixtures');

async function delivered(request,address,kind) {
  let link;
  await expect.poll(async()=> {
    const messages=await (await request.get(mailbox)).json();
    link=messages.filter(message=>message.recipients.includes(address)).flatMap(message=>message.links).findLast(value=>value.includes('/#'+kind+'/'));
    return !!link;
  },{message:'Expected recovery message was delivered to the loopback SMTP fixture'}).toBe(true);
  return link;
}
async function signIn(page,username,password) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');
  await page.locator('#login-form').getByLabel('Username',{exact:true}).fill(username);
  await page.locator('#login-form').getByLabel('Password',{exact:true}).fill(password);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
}
async function verify(page,link) {
  await page.goto(link);await expect(page.getByRole('button',{name:'Verify email',exact:true})).toBeEnabled();
  expect(await page.evaluate(()=>location.hash==='')).toBe(true);
  await expect(page.locator('#login-form input:enabled')).toHaveCount(0);
  await page.getByRole('button',{name:'Verify email',exact:true}).click();
  await expect(page.locator('#email-recovery-feedback')).toContainText('Recovery email verified.');
}

test('email recovery verifies independent mailboxes, replaces a primary safely and resets without signing in',async({browser,request})=> {
  const username='mail-'+randomUUID().slice(0,8),password='Initial recovery fixture password',replacement='Replacement recovery fixture password';
  const primary=username+'@example.invalid',backup=username+'-backup@example.invalid',next=username+'-new@example.invalid';
  const created=await request.post('/api/v1/auth/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,email:primary,displayName:username,administrator:false}});
  expect(created.status()).toBe(201);
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true}),page=await context.newPage();
  const actionContext=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true}),action=await actionContext.newPage();
  try {
    await verify(action,await delivered(request,primary,'verify-email'));
    await signIn(page,username,password);
    await page.getByRole('link',{name:'My Account',exact:true}).click();
    await expect(page.locator('#account-content')).toContainText('Verified: '+primary);
    await page.getByLabel('Backup email',{exact:true}).fill(backup);await page.getByLabel('Current password for backup email',{exact:true}).fill(password);
    await page.getByRole('button',{name:'Save backup email',exact:true}).click();await expect(page.locator('#account-content')).toContainText('Awaiting verification: '+backup);
    await verify(action,await delivered(request,backup,'verify-email'));
    await page.reload();await expect(page.locator('#account-content')).toContainText('Verified: '+backup);
    await page.getByLabel('Primary email',{exact:true}).fill(next);await page.getByLabel('Current password for primary email',{exact:true}).fill(password);
    await page.getByRole('button',{name:'Save primary email',exact:true}).click();
    await expect(page.locator('#account-content')).toContainText('Verified: '+primary);await expect(page.locator('#account-content')).toContainText('Awaiting verification: '+next);
    await verify(action,await delivered(request,next,'verify-email'));
    expect((await context.request.get('/api/v1/auth/session')).status(),'Verifying a replacement keeps the existing session valid').toBe(200);
    await page.reload();
    expect((await context.request.get('/api/v1/auth/session')).status(),'Reload retains the secure session cookie').toBe(200);
    await expect(page.locator('#status')).toHaveText('Up to date');
    await expect(page.locator('#account-content')).toContainText('Verified: '+next);

    // A reset invalidates this owner's cookie and API token, but never the
    // administrator or another owner's data. Keep the old page signed in.
    const identity=await (await context.request.get('/api/v1/auth/session')).json();
    const issued=await context.request.post('/api/v1/auth/tokens',{headers:{'X-CSRF-Token':identity.csrf,'X-Timekeeper-Owner':Buffer.from(identity.ownerId).toString('base64url')},data:{name:'Recovery test',permissions:['read','write'],expiresAt:null}});
    expect(issued.status()).toBe(201);const oldToken=(await issued.json()).token;
    expect(typeof oldToken).toBe('string');
    await action.goto('/web/');await action.getByRole('button',{name:'Forgot password?',exact:true}).click();
    await action.locator('#email-recovery-form').getByLabel('Username',{exact:true}).fill(username);await action.getByRole('button',{name:'Request reset link',exact:true}).click();
    await expect(action.locator('#email-recovery-feedback')).toContainText('If this account has verified recovery email');
    const reset=await delivered(request,backup,'reset-password');await delivered(request,next,'reset-password');
    const secret=new URL(reset).hash.split('/')[1];
    for(let scan=0;scan<2;scan++) expect((await request.post('/api/v1/auth/email/preview',{data:{token:secret,purpose:'RESET'}})).status()).toBe(204);
    await action.goto(reset);await expect(action.getByRole('button',{name:'Reset password',exact:true})).toBeEnabled();
    expect(await action.evaluate(()=>location.hash==='')).toBe(true);await expect(action.locator('#login-form input:enabled')).toHaveCount(0);
    await action.getByLabel('New password',{exact:true}).fill(replacement);await action.getByLabel('Confirm new password',{exact:true}).fill(replacement+' mismatch');
    await action.getByRole('button',{name:'Reset password',exact:true}).click();await expect(action.locator('#email-recovery-feedback')).toContainText('Passwords do not match.');
    await action.getByLabel('Confirm new password',{exact:true}).fill(replacement);await action.getByRole('button',{name:'Reset password',exact:true}).click();
    await expect(action.locator('#email-recovery-feedback')).toContainText('Password reset. Sign in with your new password.');
    expect((await actionContext.request.get('/api/v1/auth/session')).status()).toBe(401);
    expect((await context.request.get('/api/v1/auth/session')).status()).toBe(401);
    expect((await request.get('/api/v1/me',{headers:{Authorization:`Bearer ${oldToken}`}})).status()).toBe(401);
    expect((await request.get('/api/v1/me',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`}})).status()).toBe(200);
    expect((await request.post('/api/v1/auth/email/reset',{data:{token:secret,password:replacement}})).status()).toBe(401);
    await signIn(action,username,replacement);await action.getByRole('link',{name:'My Account',exact:true}).click();
    await expect(action.locator('#account-content')).toContainText('Verified: '+next);await expect(action.locator('#account-content')).toContainText('Verified: '+backup);
    await action.getByLabel('Current password for backup email',{exact:true}).fill(replacement);
    await action.getByRole('button',{name:'Remove backup email',exact:true}).click();
    await expect(action.locator('#account-content')).toContainText('Backup address removed.');
    await expect(action.getByRole('button',{name:'Remove backup email',exact:true})).toHaveCount(0);
    const removed=await (await actionContext.request.get('/api/v1/auth/email/addresses/BACKUP')).json();
    expect(removed.verifiedAddress).toBeNull();expect(removed.pendingAddress).toBeNull();
    expect(await action.evaluate(()=>({local:localStorage.length,session:sessionStorage.length}))).toEqual({local:0,session:0});
  } finally {await actionContext.close();await context.close();}
});
