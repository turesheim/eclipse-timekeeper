import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
const base=()=>process.env.TIMEKEEPER_WEB_URL;
async function fresh(request,administrator=false) {
  const username='locale-'+randomUUID().slice(0,8),password='Independent locale preference passphrase';
  const response=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:'Locale tester',email:username+'@example.org',administrator}});
  expect(response.status()).toBe(201);return {username,password};
}
async function context(browser,locale) {return browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale});}
async function login(page,user) {
  await page.goto('/web/');await expect(page.locator('#login-form button[type=submit]')).toBeEnabled();await page.locator('#auth-mode').selectOption('login');
  await page.locator('#username').fill(user.username);await page.locator('#password').fill(user.password);
  await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#workspace')).toBeVisible();
}
async function account(page) {
  await page.locator('#account-menu > summary').click();await page.locator('#account').click();
  await expect(page.locator('#account-page')).toBeVisible();
  await expect(page.getByLabel((await page.locator('html').getAttribute('lang'))==='nb-NO'?'Språk og region':'Language and region',{exact:true})).toBeVisible();
  await expect(page.locator('#account-content')).toHaveAttribute('aria-busy','false');
}
async function saveLanguage(page,label,button,value,locale) {
  const input=page.getByLabel(label,{exact:true});await input.selectOption(value);
  const form=page.locator('form').filter({has:input});await form.getByRole('button',{name:button,exact:true}).click();
  await expect(page.locator('html')).toHaveAttribute('lang',locale);await expect(page.locator('#account-content')).toHaveAttribute('aria-busy','false');
  await expect(page.locator('#account-page')).toBeVisible();
  await expect(page.getByLabel(locale==='nb-NO'?'Språk og region':'Language and region',{exact:true})).toHaveValue(value);
}
test('public language follows browser preference with Norwegian aliases and an English fallback',async({browser})=>{
  for(const [locale,expected,title] of [['nb-NO','nb-NO','La tiden telle.'],['nn-NO','nb-NO','La tiden telle.'],['en-US','en-US','Make time count.'],['fr-FR','en-GB','Make time count.']]) {
    const session=await context(browser,locale);const page=await session.newPage();
    try {await page.goto('/web/');await expect(page.locator('html')).toHaveAttribute('lang',expected);await expect(page.locator('#login h1')).toHaveText(title);}
    finally {await session.close();}
  }
});
test('account language persists across devices and can return to browser defaults without translating user records',async({browser,request})=>{
  const user=await fresh(request),first=await context(browser,'nb-NO'),second=await context(browser,'nb-NO');
  try {
    const page=await first.newPage();await login(page,user);await expect(page.locator('#status')).toHaveText('Oppdatert');
    await page.locator('[data-tab=clients]').click();await page.getByRole('button',{name:'Ny kunde',exact:true}).click();
    await expect(page.locator('#editor-title')).toHaveText('Ny kunde');
    const name='<b>Profile</b>';await page.getByLabel('Navn',{exact:true}).fill(name);await page.getByRole('button',{name:'Lagre',exact:true}).click();
    await expect(page.locator('#editor')).not.toBeVisible();await expect(page.locator('#clients')).toContainText(name);await expect(page.locator('#clients b')).toHaveCount(0);
    await page.locator('[data-tab=projects]').click();await page.getByRole('button',{name:'Nytt prosjekt',exact:true}).click();await expect(page.locator('#editor-title')).toHaveText('Nytt prosjekt');await page.getByRole('button',{name:'Avbryt',exact:true}).click();
    await page.locator('[data-tab=track]').click();await page.locator('#track-month').fill('2025-10');await expect(page.locator('#track-title')).toHaveText('Oktober 2025');
    await account(page);
    const zone=page.getByLabel('Tidssone',{exact:true});await zone.fill('America/Los_Angeles');
    const zoneForm=page.locator('form').filter({has:zone});await zoneForm.getByRole('button',{name:'Lagre tidssone',exact:true}).click();
    await expect(zoneForm.locator('.form-feedback')).toContainText('Tidssonen er lagret');
    await page.getByLabel('Visningsnavn (valgfritt)',{exact:true}).fill('Unsaved display name');
    const language=page.getByLabel('Språk og region',{exact:true});await language.selectOption('en-GB');
    const languageForm=page.locator('form').filter({has:language});
    const dismissed=page.waitForEvent('dialog');page.once('dialog',dialog=>dialog.dismiss());
    await languageForm.getByRole('button',{name:'Lagre språk',exact:true}).click();expect((await dismissed).message()).toContain('forkaste');
    await expect(languageForm.getByRole('button',{name:'Lagre språk',exact:true})).toBeEnabled();
    expect((await(await browserRequest(page).get('/api/v1/me')).json()).locale).toBeNull();
    page.once('dialog',dialog=>dialog.accept());
    await saveLanguage(page,'Språk og region','Lagre språk','en-GB','en-GB');
    await expect(page.getByLabel('Display name (optional)',{exact:true})).toHaveValue('Locale tester');
    await expect(page.locator('#account-heading')).toHaveText('My Account');
    expect((await(await browserRequest(page).get('/api/v1/me')).json()).locale).toBe('en-GB');
    expect((await(await browserRequest(page).get('/api/v1/me')).json()).zoneId).toBe('America/Los_Angeles');
    const other=await second.newPage();await login(other,user);await expect(other.locator('html')).toHaveAttribute('lang','en-GB');
    await other.locator('[data-tab=clients]').click();await expect(other.locator('#clients')).toContainText(name);await expect(other.locator('#clients b')).toHaveCount(0);
    await account(other);await saveLanguage(other,'Language and region','Save language','','nb-NO');
    expect((await(await browserRequest(other).get('/api/v1/me')).json()).locale).toBeNull();
    await page.reload();await expect(page.locator('html')).toHaveAttribute('lang','nb-NO');await expect(page.locator('#account-heading')).toHaveText('Min konto');
    await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    const audit=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
    await page.screenshot({path:'test-results/locale-norwegian-account.png',fullPage:true});
  } finally {await first.close();await second.close();}
});
test('Norwegian administration and workspace navigation retain accessible narrow layouts',async({browser,request})=>{
  const user=await fresh(request,true),session=await context(browser,'nb-NO');
  try {
    const page=await session.newPage();await login(page,user);
    for(const tab of ['track','week','activities','calendar','tasks','clients','projects','labels']) {
      await page.locator(`[data-tab=${tab}]`).click();
      await expect(page.locator(`[data-tab=${tab}]`)).toHaveAttribute('aria-current','page');
      await expect(page.locator('#workspace')).not.toContainText(/\b(?:ui\.|count\.|record\.)[a-z_]+/);
    }
    await page.goto('/admin/');await expect(page.getByRole('heading',{name:'Generelle innstillinger',exact:true})).toBeVisible();
    await expect(page.locator('#account-content')).toContainText('Lagrede innstillinger er aktive.');
    await page.setViewportSize({width:390,height:844});
    for(const name of ['Brukere','Generelle innstillinger']) {
      await page.getByRole('link',{name,exact:true}).click();await expect(page.locator('#account-content')).toHaveAttribute('aria-busy','false');
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      const audit=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
    }
    await page.screenshot({path:'test-results/locale-norwegian-administration.png',fullPage:true});
  } finally {await session.close();}
});
test('a stale language save preserves the choice and reports the conflict in the current language',async({browser,request})=>{
  const user=await fresh(request),session=await context(browser,'nb-NO');
  try {
    const page=await session.newPage();await login(page,user);await account(page);
    const api=browserRequest(page),identity=await(await api.get('/api/v1/auth/session')).json(),profile=await api.get('/api/v1/me');
    const reply=await api.put('/api/v1/me',{headers:{'X-CSRF-Token':identity.csrf,'If-Match':profile.headers().etag},data:{zoneId:(await profile.json()).zoneId,locale:'en-GB'}});expect(reply.status()).toBe(200);
    const input=page.getByLabel('Språk og region',{exact:true});await input.selectOption('en-US');const form=page.locator('form').filter({has:input});
    await form.getByRole('button',{name:'Lagre språk',exact:true}).click();await expect(form.locator('.form-feedback')).toContainText(/utkastet beholdes/i);
    await expect(input).toHaveValue('en-US');await expect(page.locator('html')).toHaveAttribute('lang','nb-NO');
    expect((await(await api.get('/api/v1/me')).json()).locale).toBe('en-GB');
  } finally {await session.close();}
});
