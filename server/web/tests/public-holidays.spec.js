import {setTheme} from './navigation.js';
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

test('public holiday highlights stay subtle and consistent in Track and Calendar in every theme',async({browser,request})=>{
  test.setTimeout(90000);
  const user=await fresh(request),session=await context(browser),page=await session.newPage();
  try {
    await signIn(page,user);const api=await ownApi(page),record=await seed(api);
    await page.reload();await trackingReady(page);await page.locator('#track-month').fill('2026-05');
    const track=page.locator('[data-track-date="2026-05-17"]'),ordinary=page.locator('[data-track-date="2026-05-18"]');
    const column=page.locator('.calendar-day[data-date="2026-05-17"]'),header=page.locator('.calendar-heading[data-date="2026-05-17"]');
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']) {
      await page.locator('[data-tab=track]').click();await expect(track).toHaveClass(/track-public-holiday/);
      await setTheme(page,theme);
      const tint=await track.evaluate(node=>getComputedStyle(node).backgroundColor);
      expect(tint).not.toBe(await ordinary.evaluate(node=>getComputedStyle(node).backgroundColor));
      await page.locator('[data-tab=calendar]').click();await page.locator('#calendar-date').fill('2026-05-17');
      await expect(column).toHaveClass(/public-holiday/);await expect(header).toContainText('Grunnlovsdagen');
      await expect.poll(()=>header.evaluate(node=>{
        const label=node.querySelector('.calendar-holiday-name');if(!node.isConnected||!label)return false;
        const text=label.getBoundingClientRect(),heading=node.getBoundingClientRect();return text.y>=heading.y&&text.bottom<=heading.bottom;
      })).toBe(true);
      await expect(column).toHaveAttribute('aria-label',/Grunnlovsdagen/);await expect(header).toHaveAttribute('title','Grunnlovsdagen');
      await expect.poll(()=>column.evaluate(node=>getComputedStyle(node).backgroundColor)).toBe(tint);
      await expect.poll(()=>header.evaluate(node=>getComputedStyle(node).backgroundColor)).toBe(tint);
      expect(await page.locator('.calendar-day[data-date="2026-05-16"]').evaluate(node=>getComputedStyle(node).backgroundColor)).not.toBe(tint);
      const audit=await new AxeBuilder({page}).include('#calendar-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations.map(value=>({id:value.id,nodes:value.nodes.map(node=>node.target)}))).toEqual([]);
      if(theme==='solarized-dark'||theme==='timekeeper') {
        await page.setViewportSize({width:1440,height:1000});await page.screenshot({path:`test-results/holiday-calendar-${theme}.png`,fullPage:true});
        await page.locator('[data-tab=track]').click();await page.screenshot({path:`test-results/holiday-track-${theme}.png`,fullPage:true});
      }
    }
    await page.locator('[data-tab=calendar]').click();await page.locator('#calendar-date').fill('2026-05-04');
    await expect(page.locator('.calendar-day.public-holiday')).toHaveCount(0);
    await page.locator('#calendar-date').fill('2026-05-17');await expect(column).toHaveClass(/public-holiday/);
    await page.locator('#calendar-date').fill('2025-12-29');await expect(page.locator('.calendar-heading[data-date="2026-01-01"]')).toContainText('Første nyttårsdag');
    expect((await(await api('GET','/work-sessions/'+record.id)).json())).toEqual(record);
    await page.locator('#account-menu > summary').click();await page.locator('#sign-out').click();
    await expect(page.locator('#calendar-grid')).toBeEmpty();await expect(page.locator('.calendar-day.public-holiday')).toHaveCount(0);
  } finally {await session.close();}
});

test('Calendar respects a separate holiday country, browser default and disabled holidays',async({browser,request})=>{
  const user=await fresh(request),session=await context(browser),page=await session.newPage();let country='NO';
  try {
    await signIn(page,user);const api=await ownApi(page),work=await seed(api);await preference(api,{locale:'en-US'});
    // Exercise the additive profile field from #380 without requiring its API
    // changes in this independent highlighting PR.
    await page.route('**/api/v1/me',async route=>{
      if(route.request().method()!=='GET'){await route.continue();return;}
      const response=await route.fetch();await route.fulfill({response,json:{...await response.json(),holidayCountry:country}});
    });
    await page.reload();await trackingReady(page);await page.locator('[data-tab=calendar]').click();await page.locator('#calendar-date').fill('2026-05-17');
    const header=page.locator('.calendar-heading[data-date="2026-05-17"]');await expect(header).toContainText('Constitution Day');await expect(page.locator('html')).toHaveAttribute('lang','en-US');
    country='US';await page.locator('#refresh').click();await trackingReady(page);await expect(header).not.toHaveClass(/public-holiday/);
    await page.locator('#calendar-date').fill('2026-07-04');await expect(page.locator('.calendar-heading[data-date="2026-07-04"]')).toContainText('Independence Day');
    country='none';await page.locator('#refresh').click();await trackingReady(page);await expect(page.locator('.calendar-day.public-holiday')).toHaveCount(0);await expect(page.locator('.calendar-holiday-name')).toHaveCount(0);
    country=null;await page.locator('#refresh').click();await trackingReady(page);await page.locator('#calendar-date').fill('2026-05-17');await expect(header).toContainText('Constitution Day');
    expect(await(await api('GET','/work-sessions/'+work.id)).json()).toEqual(work);
  } finally {await session.close();}
});

test('public holidays follow Norway civil dates and keep recorded work editable and unchanged',async({browser,request})=>{
  const user=await fresh(request),session=await context(browser);const page=await session.newPage();
  let releaseEngine=()=>{};await page.addInitScript(()=>{window.holidayPolicyViolations=[];document.addEventListener('securitypolicyviolation',event=>window.holidayPolicyViolations.push({directive:event.violatedDirective,source:event.sourceFile,line:event.lineNumber,sample:event.sample,blocked:event.blockedURI}));});
  try {
    let engineRequests=0;const outside=[];page.on('request',request=>{const url=new URL(request.url());if(url.pathname.endsWith('/date-holidays/engine.js'))engineRequests++;if(url.origin!==new URL(base()).origin)outside.push(url.origin);});
    await page.goto('/web/');await expect(page.locator('#login-form button[type=submit]')).toBeEnabled();expect(engineRequests).toBe(0);
    await signIn(page,user);const api=await ownApi(page);const work=await seed(api);
    const engineReady=new Promise(resolve=>{releaseEngine=resolve;});
    await page.route('**/assets/date-holidays/engine.js*',async route=>{await engineReady;await route.continue();});
    await page.reload();await trackingReady(page);
    // Isolate the new module from WebKit style-attribute reports generated by
    // existing forms and browser automation before/after calendar loading.
    await page.evaluate(async()=>{await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));window.holidayPolicyViolations=[];});
    releaseEngine();await expect(page.locator('.track-holiday-status')).toBeHidden();
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    expect(await page.evaluate(()=>window.holidayPolicyViolations)).toEqual([]);
    const loadedEngineRequests=engineRequests;await page.locator('#track-month').fill('2026-05');
    await expect(page.locator('.track-holiday-status')).toBeHidden();
    const constitution=holiday(page,'2026-05-17');await expect(constitution).toContainText('Grunnlovsdagen');await expect(holiday(page,'2026-05-08')).toHaveCount(0);
    const total=await page.locator('#track-total').innerText(),record=await api('GET','/work-sessions/'+work.id),writes=[];
    page.on('request',request=>{if(!['GET','HEAD','OPTIONS'].includes(request.method()))writes.push(request.method()+' '+new URL(request.url()).pathname);});
    await constitution.click();const details=page.locator('#track-holiday-details');await expect(details).toBeVisible();await expect(details).toContainText('Grunnlovsdagen');await expect(details).toContainText('Norge');
    await details.getByRole('button',{name:'Lukk',exact:true}).click();await expect(constitution).toBeFocused();expect(writes).toEqual([]);
    await expect(page.locator('#track-total')).toHaveText(total);expect((await api('GET','/work-sessions/'+work.id)).headers().etag).toBe(record.headers().etag);
    await page.locator('[data-track-date="2026-05-17"] button[data-track-daily-client]').click();await expect(page.locator('#editor')).toBeVisible();await expect(page.locator('#field-summary')).toHaveValue('Work on a public holiday');await page.locator('#cancel').click();
    expect(engineRequests).toBe(loadedEngineRequests);expect(outside).toEqual([]);expect(await page.evaluate(()=>window.holidayPolicyViolations.filter(event=>event.directive.startsWith('script-src')))).toEqual([]);
    const license=await request.get('/web/assets/date-holidays/LICENSE');expect(license.status()).toBe(200);expect(await license.text()).toContain('CC BY-SA 3.0');
  } finally {releaseEngine();await session.close();}
});
test('public holidays use their own country preference independently of the interface language',async({browser,request})=>{
  const user=await fresh(request),session=await context(browser),page=await session.newPage();
  try {
    await signIn(page,user);const api=await ownApi(page);await preference(api,{locale:'en-US',holidayCountry:'US'});await page.reload();await trackingReady(page);
    await expect(page.locator('html')).toHaveAttribute('lang','en-US');expect(await page.evaluate(()=>navigator.language)).toBe('nb-NO');
    await page.locator('#track-month').fill('2026-07');await expect(page.locator('.track-holiday-status')).toBeHidden();
    await expect(holiday(page,'2026-07-03')).toContainText('Independence Day');await expect(holiday(page,'2026-07-04')).toContainText('Independence Day');
    await preference(api,{locale:'en-GB',holidayCountry:'GB'});await page.reload();await trackingReady(page);await expect(page.locator('html')).toHaveAttribute('lang','en-GB');await page.locator('#track-month').fill('2026-05');
    await expect(page.locator('.track-holiday-status')).toBeHidden();await expect(holiday(page,'2026-05-04')).toContainText('Early May bank holiday');await expect(holiday(page,'2026-05-17')).toHaveCount(0);
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
      await setTheme(page,theme);
      const monthAudit=await new AxeBuilder({page}).include('#track-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(monthAudit.violations.map(value=>({id:value.id,nodes:value.nodes.map(node=>node.target)}))).toEqual([]);
      await holiday(page,'2026-05-17').click();await expect(page.locator('#track-holiday-details')).toContainText('Grunnlovsdagen');
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      const audit=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations.map(value=>({id:value.id,nodes:value.nodes.map(node=>node.target)}))).toEqual([]);
      await page.keyboard.press('Escape');
    }
    await setTheme(page,'timekeeper');
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

test('the independent holiday setting persists across languages and devices and can hide holidays',async({browser,request})=>{
  const user=await fresh(request),first=await context(browser,'en-US'),second=await context(browser,'nb-NO');
  async function account(page) {await page.locator('#account-menu summary').click();await page.locator('#account').click();await expect(page.locator('#account-content')).toHaveAttribute('aria-busy','false');}
  async function choose(page,value) {await page.getByLabel('Holiday country',{exact:true}).selectOption(value);await page.getByRole('button',{name:'Save holidays',exact:true}).click();await expect(page.locator('#account-content')).toContainText('Holiday settings saved.');}
  async function track(page,month) {await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await page.locator('#track-month').fill(month);await trackingReady(page);}
  try {
    const page=await first.newPage();await signIn(page,user);const api=await ownApi(page),work=await seed(api),before=await api('GET','/work-sessions/'+work.id);
    await account(page);await choose(page,'NO');await expect(page.locator('html')).toHaveAttribute('lang','en-US');
    await page.getByLabel('Language and region',{exact:true}).selectOption('en-GB');await page.getByRole('button',{name:'Save language',exact:true}).click();
    await expect(page.locator('html')).toHaveAttribute('lang','en-GB');await expect(page.getByLabel('Holiday country',{exact:true})).toHaveValue('NO');
    await page.setViewportSize({width:390,height:844});await page.locator('.account-card').filter({has:page.getByRole('heading',{name:'National holidays',exact:true})}).screenshot({path:'test-results/holiday-preferences-mobile.png'});
    await track(page,'2026-05');await expect(holiday(page,'2026-05-17')).toContainText('Constitution Day');await expect(page.locator('.track-holiday-status')).toBeHidden();
    const peer=await second.newPage();await signIn(peer,user);await peer.locator('#track-month').fill('2026-05');await expect(holiday(peer,'2026-05-17')).toContainText('Constitution Day');expect(await peer.evaluate(()=>navigator.language)).toBe('nb-NO');
    await account(page);await choose(page,'US');await track(page,'2026-07');await expect(holiday(page,'2026-07-04')).toContainText('Independence Day');
    await account(page);await choose(page,'none');await track(page,'2026-05');await expect(page.locator('[data-track-holiday-date]')).toHaveCount(0);await expect(page.locator('.track-holiday-status')).toBeHidden();
    let engines=0;page.on('request',request=>{if(request.url().includes('/date-holidays/engine.js'))engines++;});await page.reload();await trackingReady(page);await expect(page.locator('[data-track-holiday-date]')).toHaveCount(0);expect(engines).toBe(0);
    const after=await api('GET','/work-sessions/'+work.id);expect(after.headers().etag).toBe(before.headers().etag);expect(await after.json()).toEqual(await before.json());
    await account(page);await page.getByLabel('Holiday country',{exact:true}).selectOption('NO');
    await preference(api,{holidayCountry:'GB'});await page.getByRole('button',{name:'Save holidays',exact:true}).click();await expect(page.locator('#account-content')).toContainText('changed');await expect(page.getByLabel('Holiday country',{exact:true})).toHaveValue('NO');
    expect((await(await api('GET','/me')).json()).holidayCountry).toBe('GB');
  } finally {await first.close();await second.close();}
});
