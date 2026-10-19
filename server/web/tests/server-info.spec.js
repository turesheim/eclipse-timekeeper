import {captureScreenshot} from './screenshots.js';
import {setTheme} from './navigation.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {openAbout} from './navigation.js';

async function signIn(page,request) {
  const username='notices-'+randomUUID().slice(0,8),password='Independent notices browser password';
  const user=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});
  expect(user.status()).toBe(201);
  await browserRequest(page).get('/api/v1/auth/status');
  expect((await browserRequest(page).post('/api/v1/auth/login',{data:{username,password,remember:false}})).status()).toBe(200);
  await page.goto('/web/');await expect(page.locator('#status')).toHaveText('Up to date');
}

test('About identifies the project and bundled libraries with complete local licenses',async({page,request})=>{
  await signIn(page,request);const outside=[];
  page.on('request',request=>{if(new URL(request.url()).origin!==new URL(process.env.TIMEKEEPER_WEB_URL).origin)outside.push(request.url());});
  const api=browserRequest(page),identity=await(await api.get('/api/v1/auth/session')).json(),profile=await api.get('/api/v1/me');
  expect((await api.put('/api/v1/me',{data:{zoneId:(await profile.json()).zoneId,holidayCountry:'GB'},headers:{'If-Match':profile.headers().etag,'X-CSRF-Token':identity.csrf}})).status()).toBe(200);
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');
  await expect(page.locator('#track-zone')).toHaveCount(0);await expect(page.locator('#account-menu #theme')).toHaveCount(0);await expect(page.locator('#account-menu-items > :last-child')).toHaveAttribute('id','about-open');
  await openAbout(page);const dialog=page.locator('#server-info');await expect(page.locator('#about-holidays')).toContainText('United Kingdom');await expect(dialog.getByRole('link',{name:'date-holidays data and license',exact:true})).toHaveAttribute('href','/web/assets/date-holidays/LICENSE');
  await expect(page.locator('#about-title')).toBeFocused();
  await expect(dialog).toContainText('Copyright © 2014–2026 Torkild U. Resheim');
  await expect(dialog.getByRole('link',{name:'Source code',exact:true})).toHaveAttribute('href','https://github.com/turesheim/eclipse-timekeeper');
  await expect(dialog.getByRole('link',{name:'Project website',exact:true})).toHaveAttribute('href','https://turesheim.github.io/eclipse-timekeeper/');
  await expect(dialog.getByRole('link',{name:'Issues and suggestions',exact:true})).toHaveAttribute('href','https://github.com/turesheim/eclipse-timekeeper/issues');
  const server=await(await browserRequest(page).get('/api/v1/server')).json();
  await expect(page.locator('#server-version')).toHaveText(server.version);
  await page.locator('#about-notices summary').click();
  const manifest=JSON.parse(await readFile(new URL('../package.json',import.meta.url),'utf8'));
  for(const [name,pkg] of [['Bootstrap','bootstrap'],['Bootstrap Icons','bootstrap-icons'],['i18next','i18next'],['date-holidays','date-holidays']]) {
    const row=page.locator('#about-components li').filter({has:page.getByRole('link',{name,exact:true})});
    await expect(row).toContainText(manifest.devDependencies[pkg]);await expect(row).toContainText('Copyright');
    const response=await request.get(await row.getByRole('link',{name:new RegExp('^'+name+' license:','i')}).getAttribute('href'));
    expect(response.status()).toBe(200);expect(response.headers()['content-type']).toMatch(/^text\/plain/);
    expect(await response.text()).toBe(await readFile(new URL('../src/assets/'+pkg+'/LICENSE',import.meta.url),'utf8'));
  }
  const projectLicense=await request.get(await dialog.getByRole('link',{name:'Eclipse Public License 2.0',exact:true}).getAttribute('href'));
  expect(projectLicense.status()).toBe(200);expect(await projectLicense.text()).toBe(await readFile(new URL('../../../LICENSE',import.meta.url),'utf8'));
  const dependencies=await request.get(await dialog.getByRole('link',{name:'Dependency licenses and notices',exact:true}).getAttribute('href'));
  expect(dependencies.status()).toBe(200);expect(await dependencies.text()).toContain('MIT');
  for(const link of await dialog.locator('a').all()) {await expect(link).toHaveAttribute('target','_blank');await expect(link).toHaveAttribute('rel','noopener noreferrer');}
  expect(outside).toEqual([]);
  await page.setViewportSize({width:1440,height:1400});await captureScreenshot(page,{path:'test-results/about-references-desktop.png',fullPage:true});
  await page.keyboard.press('Escape');await expect(dialog).toBeHidden();await expect(page.locator('#account-menu summary')).toBeFocused();
});

test('About follows the saved Norwegian language and remains accessible in every theme on mobile',async({page,request})=>{
  await signIn(page,request);const api=browserRequest(page),identity=await(await api.get('/api/v1/auth/session')).json(),profile=await api.get('/api/v1/me');
  expect((await api.put('/api/v1/me',{data:{zoneId:(await profile.json()).zoneId,locale:'nb-NO'},headers:{'If-Match':profile.headers().etag,'X-CSRF-Token':identity.csrf}})).status()).toBe(200);
  await page.reload();await expect(page.locator('#status')).toHaveText('Oppdatert');await page.setViewportSize({width:390,height:844});
  for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']) {
    await setTheme(page,theme);await page.locator('#account-menu > summary').click();
    await page.locator('#about-open').click();const dialog=page.locator('#server-info');
    await expect(dialog.getByRole('heading',{name:'Om Timekeeper',exact:true})).toBeVisible();
    await expect(dialog.getByRole('link',{name:'Kildekode',exact:true})).toBeVisible();
    const details=page.locator('#about-notices');if(await details.getAttribute('open')===null)await details.locator('summary').click();
    expect((await new AxeBuilder({page}).include('#server-info').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations,theme).toEqual([]);
    expect(await dialog.evaluate(node=>node.scrollWidth<=node.clientWidth)).toBe(true);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    if(theme==='timekeeper')await captureScreenshot(page,{path:'test-results/about-references-mobile.png',fullPage:false});
    await page.keyboard.press('Escape');await expect(dialog).toBeHidden();await expect(page.locator('#account-menu summary')).toBeFocused();
  }
});
