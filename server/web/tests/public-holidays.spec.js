import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';

const base=()=>process.env.TIMEKEEPER_WEB_URL;
test.beforeAll(async({request})=>{
  const headers={Origin:new URL(base()).origin};
  const status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired) {
    const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.org'}});
    expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});
  }
});
async function fresh(request) {
  const username='holidays-'+randomUUID().slice(0,8),password='Independent holiday calendar password';
  const reply=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});
  expect(reply.status()).toBe(201);return {username,password};
}
const context=(browser,locale='nb-NO')=>browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale,timezoneId:'Pacific/Kiritimati'});
async function trackingReady(page) {
  await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.locator('#status')).toHaveText((await page.locator('html').getAttribute('lang'))==='nb-NO'?'Oppdatert':'Up to date');
}
async function signIn(page,user) {
  await page.goto('/web/');await expect(page.locator('#login-form button[type=submit]')).toBeEnabled();
  await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(user.username);await page.locator('#password').fill(user.password);
  await page.locator('#login-form button[type=submit]').click();await trackingReady(page);
}
async function ownApi(page) {
  const api=browserRequest(page),identity=await(await api.get('/api/v1/auth/session')).json();
  return async(method,path,data,extra={})=>{
    const reply=await api.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...extra}});
    expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;
  };
}
async function preference(api,values) {
  const profile=await api('GET','/me');return api('PUT','/me',{zoneId:(await profile.json()).zoneId,...values},{'If-Match':profile.headers().etag});
}
async function seed(api) {
  await preference(api,{zoneId:'America/Los_Angeles'});
  const client=await(await api('POST','/clients',{name:'Holiday work client',color:'#176653'})).json();
  return(await api('POST','/work-sessions',{clientId:client.id,projectId:null,summary:'Work on a public holiday',labelIds:[],start:'2026-05-17T12:00:00.123456789Z',end:'2026-05-17T13:00:00.123456789Z'})).json();
}
const holiday=(page,date)=>page.locator(`[data-track-holiday-date="${date}"]`);

test('public holidays follow Norway civil dates and keep recorded work editable and unchanged',async({browser,request})=>{
  const user=await fresh(request),session=await context(browser);const page=await session.newPage();
  const policy=[];await page.addInitScript(()=>{window.holidayPolicyViolations=[];document.addEventListener('securitypolicyviolation',event=>window.holidayPolicyViolations.push(event.violatedDirective));});
  try {
    let engineRequests=0;const outside=[];page.on('request',request=>{const url=new URL(request.url());if(url.pathname.endsWith('/date-holidays/engine.js'))engineRequests++;if(url.origin!==new URL(base()).origin)outside.push(url.origin);});
    await page.goto('/web/');await expect(page.locator('#login-form button[type=submit]')).toBeEnabled();expect(engineRequests).toBe(0);
    await signIn(page,user);const api=await ownApi(page);const work=await seed(api);
    await page.reload();await trackingReady(page);await expect(page.locator('.track-holiday-status')).toContainText('Nasjonale helligdager · Norge');
    const loadedEngineRequests=engineRequests;await page.locator('#track-month').fill('2026-05');
    await expect(page.locator('.track-holiday-status')).toContainText('Nasjonale helligdager · Norge');
    const constitution=holiday(page,'2026-05-17');await expect(constitution).toContainText('Grunnlovsdagen');await expect(holiday(page,'2026-05-08')).toHaveCount(0);
    const total=await page.locator('#track-total').innerText(),record=await api('GET','/work-sessions/'+work.id),writes=[];
    page.on('request',request=>{if(!['GET','HEAD','OPTIONS'].includes(request.method()))writes.push(request.method()+' '+new URL(request.url()).pathname);});
    await constitution.click();const details=page.locator('#track-holiday-details');await expect(details).toBeVisible();await expect(details).toContainText('Grunnlovsdagen');await expect(details).toContainText('Norge');
    await details.getByRole('button',{name:'Lukk',exact:true}).click();await expect(constitution).toBeFocused();expect(writes).toEqual([]);
    await expect(page.locator('#track-total')).toHaveText(total);expect((await api('GET','/work-sessions/'+work.id)).headers().etag).toBe(record.headers().etag);
    await page.locator('[data-track-date="2026-05-17"] button[data-track-daily-client]').click();await expect(page.locator('#editor')).toBeVisible();await expect(page.locator('#field-summary')).toHaveValue('Work on a public holiday');await page.locator('#cancel').click();
    expect(engineRequests).toBe(loadedEngineRequests);expect(outside).toEqual([]);expect(await page.evaluate(()=>window.holidayPolicyViolations)).toEqual(policy);
    const license=await request.get('/web/assets/date-holidays/LICENSE');expect(license.status()).toBe(200);expect(await license.text()).toContain('CC BY-SA 3.0');
  } finally {await session.close();}
});
test('public holidays use the saved account region instead of the browser language',async({browser,request})=>{
  const user=await fresh(request),session=await context(browser),page=await session.newPage();
  try {
    await signIn(page,user);const api=await ownApi(page);await preference(api,{locale:'en-US'});await page.reload();await trackingReady(page);
    await expect(page.locator('html')).toHaveAttribute('lang','en-US');expect(await page.evaluate(()=>navigator.language)).toBe('nb-NO');
    await page.locator('#track-month').fill('2026-07');await expect(page.locator('.track-holiday-status')).toContainText('United States');
    await expect(holiday(page,'2026-07-03')).toContainText('Independence Day');await expect(holiday(page,'2026-07-04')).toContainText('Independence Day');
    await preference(api,{locale:'en-GB'});await page.reload();await trackingReady(page);await expect(page.locator('html')).toHaveAttribute('lang','en-GB');await page.locator('#track-month').fill('2026-05');
    await expect(page.locator('.track-holiday-status')).toContainText('United Kingdom');await expect(holiday(page,'2026-05-04')).toContainText('Early May bank holiday');await expect(holiday(page,'2026-05-17')).toHaveCount(0);
  } finally {await session.close();}
});
test('public holiday details remain available to read-only sessions',async({browser,request})=>{
  const user=await fresh(request),owner=await context(browser),viewer=await context(browser);
  try {
    const source=await owner.newPage();await signIn(source,user);const api=await ownApi(source);await seed(api);
    const issued=await(await api('POST','/auth/tokens',{name:'Public calendar viewer',permissions:['read'],expiresAt:null})).json();
    const page=await viewer.newPage();await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(issued.token);await page.locator('#login-form button[type=submit]').click();await trackingReady(page);
    await page.locator('#track-month').fill('2026-05');await expect(holiday(page,'2026-05-17')).toBeEnabled();await expect(page.locator('#session-new')).toBeDisabled();
    await expect(page.locator('[data-track-date="2026-05-17"] button[data-track-daily-client]')).toHaveCount(0);
    await holiday(page,'2026-05-17').click();await expect(page.locator('#track-holiday-details')).toContainText('Grunnlovsdagen');await page.keyboard.press('Escape');await expect(holiday(page,'2026-05-17')).toBeFocused();
  } finally {await owner.close();await viewer.close();}
});
test('public holiday loading failures can be retried without disturbing recorded time or drafts',async({browser,request})=>{
  const user=await fresh(request),session=await context(browser),page=await session.newPage();let blocked=true,release;
  const ready=new Promise(resolve=>{release=resolve;});
  await page.route('**/assets/date-holidays/engine.js*',async route=>{if(blocked)await route.fulfill({status:503,body:'Temporarily unavailable'});else {await ready;await route.continue();}});
  try {
    await signIn(page,user);const api=await ownApi(page);await seed(api);await page.locator('#refresh').click();await page.locator('#track-month').fill('2026-05');
    await expect(page.locator('.track-holiday-status')).toContainText('Helligdagene kunne ikke lastes');
    const work=page.locator('[data-track-date="2026-05-17"] [data-track-daily-client]');await expect(work).toBeVisible();const total=await page.locator('#track-total').innerText();blocked=false;
    await page.locator('.track-holiday-status button').click();await expect(page.locator('.track-holiday-status')).toContainText('Laster helligdager');
    await work.click();await page.locator('#field-summary').fill('Keep this holiday work draft');release();
    await expect(holiday(page,'2026-05-17')).toContainText('Grunnlovsdagen');await expect(page.locator('#track-total')).toHaveText(total);await expect(page.locator('#field-summary')).toHaveValue('Keep this holiday work draft');
    await page.locator('#cancel').click();
    expect((await api('GET','/work-sessions')).status()).toBe(200);
  } finally {release();await session.close();}
});
test('public holiday details fit narrow screens in every theme and delayed data cannot repopulate sign-out',async({browser,request})=>{
  test.setTimeout(90000);
  const user=await fresh(request),session=await context(browser),page=await session.newPage();
  try {
    await signIn(page,user);await page.locator('#track-month').fill('2026-05');await expect(holiday(page,'2026-05-17')).toBeVisible();
    await page.setViewportSize({width:1440,height:1000});await page.screenshot({path:'test-results/public-holidays-desktop.png',fullPage:true});
    await page.setViewportSize({width:390,height:844});
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']) {
      await page.locator('#account-menu > summary').click();await page.locator('#theme').selectOption(theme);await page.locator('#account-menu > summary').click();
      const monthAudit=await new AxeBuilder({page}).include('#track-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(monthAudit.violations.map(value=>({id:value.id,nodes:value.nodes.map(node=>node.target)}))).toEqual([]);
      await holiday(page,'2026-05-17').click();await expect(page.locator('#track-holiday-details')).toContainText('Grunnlovsdagen');
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      const audit=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations.map(value=>({id:value.id,nodes:value.nodes.map(node=>node.target)}))).toEqual([]);
      await page.keyboard.press('Escape');
    }
    await page.locator('#account-menu > summary').click();await page.locator('#theme').selectOption('timekeeper');await page.locator('#account-menu > summary').click();
    await page.screenshot({path:'test-results/public-holidays-mobile.png',fullPage:true});await holiday(page,'2026-05-17').click();await page.screenshot({path:'test-results/public-holidays-details-mobile.png',fullPage:false});await page.keyboard.press('Escape');
    await page.locator('#account-menu > summary').click();await page.locator('#sign-out').click();await expect(page.locator('#workspace')).toBeHidden();
    let release;const ready=new Promise(resolve=>{release=resolve;});
    await page.route('**/assets/date-holidays/engine.js*',async route=>{await ready;await route.continue();});
    try {
      await signIn(page,user);await page.locator('#track-month').fill('2026-05');await expect(page.locator('.track-holiday-status')).toContainText('Laster helligdager');
      await page.locator('#account-menu > summary').click();await page.locator('#sign-out').click();await expect(page.locator('#workspace')).toBeHidden();release();
      await page.evaluate(async()=>{await import('/web/assets/date-holidays/engine.js');await new Promise(resolve=>requestAnimationFrame(resolve));});
      await expect(page.locator('.track-holiday-status')).toBeHidden();await expect(page.locator('.track-holiday-status span').first()).toHaveText('');await expect(page.locator('#track-calendar')).toBeEmpty();
    } finally {release();}
  } finally {await session.close();}
});
