import {workSessionAction} from './workweek-session-actions.js';
import {browserRequest} from './browser-request.js';
import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {openAccountMenu} from './navigation.js';
import {localDateTime} from '../src/model.js';
import {randomUUID} from 'node:crypto';

test('iOS home-screen metadata serves the bundled Timekeeper icon without sign-in',async({page})=>{
  await page.goto('/web/');
  const icon=page.locator('link[rel="apple-touch-icon"]');await expect(icon).toHaveCount(1);await expect(icon).toHaveAttribute('sizes','256x256');
  await expect(page.locator('meta[name="apple-mobile-web-app-title"]')).toHaveAttribute('content','Timekeeper');
  const url=await icon.evaluate(node=>node.href);expect(new URL(url).pathname).toBe('/web/apple-touch-icon.png');
  const response=await browserRequest(page).get(url);expect(response.status()).toBe(200);expect(response.headers()['content-type']).toContain('image/png');
  const bytes=await response.body();expect(bytes.subarray(0,8).toString('hex')).toBe('89504e470d0a1a0a');
  expect([bytes.readUInt32BE(16),bytes.readUInt32BE(20)]).toEqual([256,256]);
});

test('saved account zone applies to new editors while hidden reports refresh',async({page,request})=>{
  async function api(method,path,data,extra={}) {
    const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`,...extra}});
    expect(response.ok()).toBe(true);return response;
  }
  const original=await api('GET','/me'),zone=(await original.json()).zoneId;let client,session,release;
  try {
    await api('PUT','/me',{zoneId:'America/New_York'},{'If-Match':original.headers().etag});
    client=await (await api('POST','/clients',{name:'Zone refresh '+randomUUID(),color:'#176653'})).json();
    const now=Date.now();session=await (await api('POST','/work-sessions',{clientId:client.id,summary:'Confirmed time zone',start:new Date(now-7200000).toISOString(),end:new Date(now-3600000).toISOString()})).json();
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_TOKEN);
    await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await page.locator('[data-tab=week]').click();await openAccountMenu(page);await page.getByRole('link',{name:'My Account',exact:true}).click();
    await expect(page.getByLabel('Time zone',{exact:true})).toHaveValue('America/New_York');
    let written=false,waiting=false;const blocked=new Promise(resolve=>{release=resolve;});
    await page.route('**/api/v1/me',async route=>{
      if(route.request().method()==='PUT') written=true;
      else if(written) {waiting=true;await blocked;}
      await route.continue();
    });
    await page.getByLabel('Time zone',{exact:true}).fill('Europe/Oslo');await page.getByRole('button',{name:'Save time zone',exact:true}).click();
    await expect(page.locator('#account-content')).toContainText('Time zone saved.');await expect.poll(()=>waiting).toBe(true);
    await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();
    await workSessionAction(page,session.id,'Edit');
    await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveValue(localDateTime(session.start,'Europe/Oslo').slice(0,19).replace(/:00$/, ''));
    await page.locator('#cancel').click();
  } finally {
    release?.();await page.unrouteAll({behavior:'wait'});
    if(session) {const current=await api('GET','/work-sessions/'+session.id);await api('DELETE','/work-sessions/'+session.id,undefined,{'If-Match':current.headers().etag});}
    if(client) {const current=await api('GET','/clients/'+client.id);await api('DELETE','/clients/'+client.id,undefined,{'If-Match':current.headers().etag});}
    const current=await api('GET','/me');await api('PUT','/me',{zoneId:zone},{'If-Match':current.headers().etag});
  }
});

test('compact account navigation supports touch, keyboard, account settings and sign-out',async({page})=>{
  await page.setViewportSize({width:390,height:844});await page.goto('/web/');
  await expect(page.locator('#account-menu')).not.toBeVisible();
  await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_TOKEN);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  const toggle=page.getByRole('button',{name:'Account menu',exact:true}),menu=page.locator('#account-menu');
  const account=(await (await browserRequest(page).get('/api/v1/auth/account')).json());
  for(const width of [320,390,1280]) {
    await page.setViewportSize({width,height:844});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    const header=await page.locator('.app-header').boundingBox();expect(header.height).toBeLessThanOrEqual(80);
    await toggle.focus();await page.keyboard.press('Enter');await expect(menu).toHaveAttribute('open','');
    await page.keyboard.press('Tab');await expect(page.getByRole('link',{name:'My Account',exact:true})).toBeFocused();
    if(account.administrator) await expect(page.getByRole('link',{name:'Administration',exact:true})).toBeVisible();
    else await expect(page.getByRole('link',{name:'Administration',exact:true})).not.toBeVisible();
    await page.keyboard.press('Escape');await expect(menu).not.toHaveAttribute('open','');await expect(toggle).toBeFocused();
    await toggle.click();await page.locator('#workspace-navigation').click();await expect(menu).not.toHaveAttribute('open','');
  }
  await page.setViewportSize({width:390,height:844});await openAccountMenu(page);
  const audit=await new AxeBuilder({page}).include('.app-header').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
  await page.screenshot({path:'test-results/account-menu-mobile.png',fullPage:true});
  await page.getByRole('link',{name:'My Account',exact:true}).click();await expect(menu).not.toHaveAttribute('open','');
  await expect(page.getByRole('heading',{name:'Time zone',exact:true})).toBeVisible();await expect(page.getByLabel('Time zone',{exact:true})).toBeVisible();
  await expect(page.locator('.app-header')).not.toContainText('Time zone');
  await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login')).toBeVisible();
  await expect(menu).not.toBeVisible();await expect(menu).not.toHaveAttribute('open','');
});
