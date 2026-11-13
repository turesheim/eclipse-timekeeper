import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {randomUUID} from 'node:crypto';
import {openEditorFixture} from './editor-fixture.js';
import {browserRequest} from './browser-request.js';
import {captureScreenshot} from './screenshots.js';
async function configure(page,values=null){await page.evaluate(async values=>{const {configureSnippets}=await import('/web/snippets.js');configureSnippets({zoneId:'Europe/Oslo',locale:'en-GB',textSnippets:values});},values);}
async function field(page,value){await page.evaluate(value=>globalThis.fixtureField.setValue(value),value);}

test('source and formatted shortcuts expand on typed space with Undo, code and paste guards',async({page})=>{
  await page.clock.setFixedTime(new Date('2026-11-12T15:43:00Z'));await openEditorFixture(page);await configure(page);
  const source=page.locator('#markdown-body');await field(page,'');await source.focus();await page.keyboard.type(';dt ');await expect(source).toHaveValue('2026-11-12 16:43 ');
  await page.keyboard.press('ControlOrMeta+z');await expect(source).toHaveValue(';dt');await page.keyboard.press('ControlOrMeta+Shift+z');await expect(source).toHaveValue('2026-11-12 16:43 ');
  await field(page,'```text\n');await source.focus();await page.keyboard.press('ControlOrMeta+End');await page.keyboard.type(';dd ');await expect(source).toHaveValue('```text\n;dd ');
  await field(page,'');await source.fill(';dd ');await expect(source).toHaveValue(';dd ');
  await field(page,'');await page.locator('#markdown-rich').click();const rich=page.locator('#markdown-formatted [role=textbox]');await expect(rich).toBeVisible();await rich.click();await page.keyboard.type(';dd+7 ');await expect(rich).toHaveText('2026-11-19 ');
  await page.keyboard.press('ControlOrMeta+z');await expect(rich).toHaveText(';dd+7');await page.keyboard.press('ControlOrMeta+Shift+z');await expect(rich).toHaveText('2026-11-19 ');
  await page.evaluate(()=>fixtureField.setReadOnly(true));await rich.focus();await page.keyboard.type(';dt ');await expect(rich).toHaveText('2026-11-19 ');
});

test('multiline snippets insert safe text, position the cursor, and retain one rich Undo step',async({page})=>{
  await openEditorFixture(page);await configure(page,[{keyword:';msg',content:'Hello {cursor}friend\n<script>alert(1)</script>'}]);await field(page,'');
  await page.locator('#markdown-rich').click();const rich=page.locator('#markdown-formatted [role=textbox]');await expect(rich).toBeVisible();await rich.click();await page.keyboard.type(';msg ');
  await expect(rich).toContainText('Hello friend');await expect(rich).toContainText('<script>alert(1)</script>');expect(await rich.locator('script').count()).toBe(0);
  await page.keyboard.type('dear ');await expect(rich).toContainText('Hello dear friend');
  // A new history boundary verifies the expansion itself can be undone independently.
  await page.keyboard.press('ControlOrMeta+z');await expect(rich).toHaveText(';msg');
});

test('account snippets persist, disable and restore defaults at narrow and desktop widths',async({page,request})=>{
  const origin=new URL(process.env.TIMEKEEPER_WEB_URL).origin,headers={Origin:origin};
  const status=await request.get('/api/v1/auth/status',{headers});if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
  const username='snippets-'+randomUUID().slice(0,8),password='Private snippets test passphrase';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  await page.goto('/web/#account/snippets');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();
  const root=page.locator('#account-content');await expect(root.getByRole('heading',{name:'Text snippets',exact:true})).toBeVisible();
  await root.getByLabel('Expansion',{exact:true}).first().fill('Account-owned {isodate:yyyy}');await root.getByRole('button',{name:'Save snippets',exact:true}).click();await expect(root.getByRole('status')).toHaveText('Snippets saved.');
  await page.reload();await expect(root.getByLabel('Expansion',{exact:true}).first()).toHaveValue('Account-owned {isodate:yyyy}');
  for(const width of [1440,320]){await page.setViewportSize({width,height:1000});for(const theme of ['timekeeper','timekeeper-dark']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#account-page').analyze()).violations).toEqual([]);
    await captureScreenshot(page,{path:`test-results/snippets-${theme}-${width}.png`,fullPage:true});
  }}
  for(let i=0;i<3;i++)await root.getByRole('button',{name:'Remove snippet',exact:true}).first().click();await root.getByRole('button',{name:'Save snippets',exact:true}).click();await expect(root.getByRole('status')).toHaveText('Snippets saved.');
  const api=browserRequest(page);expect((await(await api.get('/api/v1/me')).json()).textSnippets).toEqual([]);
  await root.getByRole('button',{name:'Restore defaults',exact:true}).click();await expect(root.getByLabel('Keyword',{exact:true})).toHaveCount(3);expect((await(await api.get('/api/v1/me')).json()).textSnippets).toBeNull();
});
