import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
const token=process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request,method,path,data,headers={}) {
  const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
  expect(response.ok(),method+' '+path).toBe(true);return response;
}
async function signIn(page,credential=token) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(credential);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
}
test('Track is the default month overview with exact client session hours, local dates and independent activities',async({browser,request})=> {
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,timezoneId:'America/Los_Angeles'}),page=await context.newPage();
  const original=await (await api(request,'GET','/me')).json(),records=[];
  async function create(collection,data){const record=await (await api(request,'POST','/'+collection,data)).json();records.unshift([collection,record.id]);return record;}
  try {
    const profile=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag});
    const a=await create('clients',{name:'Monthly client A '+randomUUID().slice(0,6),color:'#6750a4'}),b=await create('clients',{name:'Monthly client B',color:'#176653'});
    const project=await create('projects',{name:'Independent tracked work',clientId:a.id});
    await create('activities',{taskId:null,projectId:project.id,summary:'Activity time must not enter session totals',labelIds:[],start:'2025-10-15T06:00:00Z',end:'2025-10-15T14:00:00Z'});
    const left=await create('work-sessions',{title:'Month boundary',clientId:a.id,summary:'',start:'2025-09-30T21:00:00Z',end:'2025-09-30T23:00:00Z'});
    const fall=await create('work-sessions',{title:'Clock change',clientId:a.id,summary:'',start:'2025-10-25T22:00:00Z',end:'2025-10-26T23:00:00Z'});
    await create('work-sessions',{title:'Next month boundary',clientId:b.id,summary:'',start:'2025-10-31T22:00:00Z',end:'2025-11-01T02:00:00Z'});
    await signIn(page);
    await expect(page.locator('[data-tab]').first()).toHaveText('Track');await expect(page.locator('[data-tab=track]')).toHaveAttribute('aria-current','page');
    await expect(page.locator('#track-panel')).toBeVisible();await expect(page.locator('#week-panel')).not.toBeVisible();
    await page.getByLabel('Month',{exact:true}).fill('2025-10');
    await expect(page.locator('#track-title')).toContainText('2025');await expect(page.locator('#track-zone')).toHaveText('Europe/Oslo');
    await expect(page.locator(`#track-clients [data-track-client="${a.id}"]`)).toContainText('26h 0m');
    await expect(page.locator(`#track-clients [data-track-client="${b.id}"]`)).toContainText('1h 0m');
    await expect(page.locator('.track-month thead th')).toHaveCount(7);await expect(page.locator('.track-month tbody tr')).toHaveCount(5);
    await expect(page.locator('.track-month tbody td').first()).toHaveAttribute('data-track-date','2025-09-29');
    const day=page.locator('[data-track-date="2025-10-26"]');await expect(day).toContainText('Clock change');await expect(day).toContainText('25h 0m');
    await expect(page.locator('[data-track-date="2025-10-01"]')).toContainText('1h 0m');
    expect(await day.locator('button').evaluate(value=>getComputedStyle(value).borderInlineStartColor)).toBe('rgb(103, 80, 164)');
    const audit=await new AxeBuilder({page}).include('#workspace').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
    await page.screenshot({path:'test-results/track-desktop.png',fullPage:true});
    await day.getByRole('button').click();await expect(page.getByLabel('Title',{exact:true})).toHaveValue(fall.title);await page.locator('#cancel').click();
    await page.getByRole('button',{name:'Next month',exact:true}).click();await expect(page.getByLabel('Month',{exact:true})).toHaveValue('2025-11');
    await expect(page.locator(`#track-clients [data-track-client="${b.id}"]`)).toContainText('3h 0m');
    await page.getByRole('button',{name:'Previous month',exact:true}).click();
    await page.locator('[data-tab=week]').click();await page.locator('[data-tab=track]').click();await expect(page.getByLabel('Month',{exact:true})).toHaveValue('2025-10');
    await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    const mobile=await new AxeBuilder({page}).include('#workspace').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(mobile.violations).toEqual([]);
    await expect(page.locator('.track-month thead th')).toHaveCount(7);await page.screenshot({path:'test-results/track-mobile.png',fullPage:true});
    await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#track-clients')).toBeEmpty();await expect(page.locator('#track-total')).toBeEmpty();
    await signIn(page,process.env.TIMEKEEPER_WEB_READ_TOKEN);await page.getByLabel('Month',{exact:true}).fill('2025-10');
    await expect(page.locator(`[data-track-session="${left.id}"]`)).toBeDisabled();
    await expect(page.locator(`#track-clients [data-track-client="${a.id}"]`)).toContainText('26h 0m');
  } finally {
    await context.close();
    for(const [collection,id] of records){const current=await api(request,'GET',`/${collection}/${id}`);await api(request,'DELETE',`/${collection}/${id}`,undefined,{'If-Match':current.headers().etag});}
    const current=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:original.zoneId},{'If-Match':current.headers().etag});
  }
});
