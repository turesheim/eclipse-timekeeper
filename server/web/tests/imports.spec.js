import {test,expect} from '@playwright/test';
import {fileURLToPath} from 'node:url';
import AxeBuilder from '@axe-core/playwright';

const fixture = fileURLToPath(new URL('./fixtures/toggl/detailed-member-stop.csv',import.meta.url));
const token = process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request,path) {
  const response = await request.get('/api/v1'+path,{headers:{Authorization:`Bearer ${token}`}});expect(response.status()).toBe(200);return response.json();
}
async function login(page) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  await page.getByRole('link',{name:'My Account',exact:true}).click();await page.getByRole('link',{name:'Import',exact:true}).click();
}
async function review(page) {
  await page.getByLabel('Source time zone',{exact:true}).fill('Europe/Oslo');await page.getByLabel('Detailed Report CSV',{exact:true}).setInputFiles(fixture);
  await page.getByRole('button',{name:'Read CSV',exact:true}).click();await expect(page.getByRole('heading',{name:'Review import',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Review mappings',exact:true}).click();await expect(page.getByRole('heading',{name:'Review mappings',exact:true})).toBeVisible();
  await page.getByLabel('New client name',{exact:true}).fill('Browser imported client');
  await page.getByRole('button',{name:'Review server matches',exact:true}).click();await expect(page.getByRole('heading',{name:'Confirm import',exact:true})).toBeVisible();
}
async function undo(page,index) {
  const history = page.getByRole('heading',{name:'Import history',exact:true}).locator('..');
  await history.getByRole('button',{name:'Undo import',exact:true}).nth(index).click();
  await page.getByRole('button',{name:'Confirm Undo',exact:true}).click();
  await expect(history).toContainText('Undo complete');
}
test('Toggl CSV worker, reviewed mappings, repeat import and whole-job Undo preserve exact time',async({page,request})=>{
  await login(page);const before = await api(request,'/data/export');
  await review(page);await expect(page.locator('#account-content')).toContainText('7 selected entries');
  const accessibility = await new AxeBuilder({page}).include('#account-page').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(accessibility.violations).toEqual([]);
  await page.screenshot({path:'test-results/toggl-import-review.png',fullPage:true});
  await page.getByRole('button',{name:'Import selected entries',exact:true}).click();await expect(page.getByRole('heading',{name:'Import complete',exact:true})).toBeVisible();
  const first = await api(request,'/data/export');expect(first.activities.length-before.activities.length).toBe(7);expect(first.workSessions.length).toBe(before.workSessions.length);
  const jobs = await api(request,'/imports');expect(jobs.items).toHaveLength(1);expect(jobs.items[0].importedNanos).toBe('151462000000000');expect(jobs.items[0].state).toBe('IMPORTED');
  const originalId = jobs.items[0].id;
  await page.getByRole('button',{name:'Import another file',exact:true}).click();await review(page);
  await expect(page.locator('#account-content')).toContainText('7 already imported');await page.getByRole('button',{name:'Import selected entries',exact:true}).click();await expect(page.getByRole('heading',{name:'Import complete',exact:true})).toBeVisible();
  const repeated = await api(request,'/data/export');expect(repeated.activities.length).toBe(first.activities.length);expect(repeated.clients.length).toBe(first.clients.length);expect(repeated.projects.length).toBe(first.projects.length);
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:'test-results/toggl-import-history-mobile.png',fullPage:true});
  const ordered = (await api(request,'/imports')).items.sort((a,b)=>b.createdAt.localeCompare(a.createdAt));
  await undo(page,ordered.findIndex(job=>job.id!==originalId));
  expect((await api(request,'/data/export')).activities.length).toBe(first.activities.length);
  await undo(page,0);
  const after = await api(request,'/data/export');expect(after.activities.length).toBe(before.activities.length);expect(after.clients.length).toBe(before.clients.length);expect(after.projects.length).toBe(before.projects.length);
  expect((await api(request,`/imports/jobs/${originalId}`)).state).toBe('UNDONE');
  await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#account-content')).toBeEmpty();
  expect(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length}))).toEqual({local:0,session:0});
});
