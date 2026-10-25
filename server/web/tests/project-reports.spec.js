import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {randomUUID} from 'node:crypto';
import {browserRequest} from './browser-request.js';
import {expandProjectClient} from './navigation.js';
const token=process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request,method,path,data,headers={}) {
  const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
  expect(response.ok(),`${method} ${path}: ${response.status()}`).toBe(true);return response;
}
async function fixture(request) {
  const records=[],prefix='Report '+randomUUID().slice(0,8);
  async function create(collection,data){const value=await(await api(request,'POST','/'+collection,data)).json();records.unshift([collection,value.id]);return value;}
  const client=await create('clients',{name:prefix+' client',color:'#176653'});
  const project=await create('projects',{name:prefix+' project <review>',clientId:client.id}),other=await create('projects',{name:prefix+' other',clientId:client.id});
  const first=await create('work-sessions',{clientId:client.id,projectId:project.id,summary:'Historical planning <script>not executable</script>',start:'2020-02-03T08:00:00Z',end:'2020-02-03T09:00:00.000000001Z'});
  const second=await create('work-sessions',{clientId:client.id,projectId:project.id,summary:'Delivery review',start:'2025-10-20T08:00:00Z',end:'2025-10-20T10:00:00.999999999Z'});
  await create('work-sessions',{clientId:client.id,projectId:other.id,summary:prefix+' excluded',start:'2025-10-21T08:00:00Z',end:'2025-10-21T12:00:00Z'});
  return {client,project,other,first,second,create,async close(){for(const [collection,id] of records){const current=await api(request,'GET',`/${collection}/${id}`);await api(request,'DELETE',`/${collection}/${id}`,undefined,{'If-Match':current.headers().etag});}}};
}
async function signIn(page,credential=token) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token',{exact:true}).fill(credential);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  await page.getByRole('button',{name:'Projects',exact:true}).click();
}
async function openReport(page,project) {
  await expandProjectClient(page,project.clientId);
  const link=page.locator(`#projects [data-project-id="${project.id}"]`).getByRole('link');
  await expect(link).toHaveAttribute('target','_blank');await expect(link).toHaveAttribute('rel','noopener noreferrer');
  const popup=page.waitForEvent('popup');await link.click();return popup;
}
test('project session reports open a separate tab, include every page and date, and total exact durations',async({page,request,context})=>{
  const f=await fixture(request);let report;
  try {
    await signIn(page);const queries=[];
    await context.route('**/api/v1/work-sessions?**',async route=>{
      const url=new URL(route.request().url());if(url.searchParams.get('projectId')!==f.project.id)return route.continue();
      queries.push(url.searchParams.get('cursor'));
      const next=url.searchParams.has('cursor');url.searchParams.delete('cursor');
      const response=await route.fetch({url:url.href}),data=await response.json();
      await route.fulfill({response,json:{...data,items:data.items.slice(next?1:0,next?undefined:1),nextCursor:next?null:'report-next-page'}});
    });
    report=await openReport(page,f.project);await expect(report.locator('#report-sessions tr')).toHaveCount(2);
    expect(queries).toEqual([null,'report-next-page']);await expect(report.locator('#report-title')).toHaveText(f.project.name);
    await expect(report.locator('#report-sessions tr').first()).toHaveAttribute('data-session-id',f.second.id);
    await expect(report.locator('#report-sessions')).toContainText(f.first.summary);await expect(report.locator('#report-sessions')).not.toContainText('excluded');
    await expect(report.locator('#report-total')).toHaveText('3h 0m 1s');await expect(report.locator('#report-summary')).toContainText('2 sessions');
    expect(new URL(report.url()).searchParams.toString()).toBe('projectId='+f.project.id);
    expect(await report.evaluate(()=>window.opener)).toBeNull();expect(await report.locator('script:not([src])').count()).toBe(0);
    expect((await new AxeBuilder({page:report}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
    await report.setViewportSize({width:1440,height:1000});await report.screenshot({path:'test-results/project-report-desktop.png',fullPage:true});
    await report.setViewportSize({width:390,height:844});expect(await report.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await report.screenshot({path:'test-results/project-report-mobile.png',fullPage:true});
    await expect(page.locator('#workspace')).toHaveAttribute('data-view','projects');await expect(page.locator('#editor')).not.toBeVisible();
  } finally {await report?.close();await f.close();}
});
test('project reports support read-only sessions, running entries and retry without partial results',async({page,request})=>{
  const f=await fixture(request);let report;
  try {
    await f.create('work-sessions',{clientId:f.client.id,projectId:f.project.id,summary:'Ongoing review',start:new Date(Date.now()-120000).toISOString(),end:null});
    await signIn(page,process.env.TIMEKEEPER_WEB_READ_TOKEN);report=await openReport(page,f.project);
    await expect(report.locator('#report-sessions tr')).toHaveCount(3);await expect(report.locator('#report-sessions')).toContainText('Running');
    await report.route('**/api/v1/work-sessions?**',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'UNAVAILABLE'})}));
    await report.locator('#report-refresh').click();await expect(report.locator('#report-error')).not.toBeEmpty();await expect(report.locator('#report-content')).toBeHidden();await expect(report.locator('#report-sessions tr')).toHaveCount(0);
    await report.unroute('**/api/v1/work-sessions?**');await report.locator('#report-refresh').click();await expect(report.locator('#report-sessions tr')).toHaveCount(3);
  } finally {await report?.close();await f.close();}
});
test('project reports show localized empty and unavailable states and clear data after sign-out',async({page,request})=>{
  const f=await fixture(request);let report;
  const profile=await api(request,'GET','/me'),previous=await profile.json();
  try {
    await signIn(page);
    await api(request,'PUT','/me',{zoneId:'Europe/Oslo',locale:'nb-NO'},{'If-Match':profile.headers().etag});
    const empty=await f.create('projects',{name:'Tomt prosjekt',clientId:f.client.id});
    report=await page.context().newPage();await report.goto('/web/project-report.html?projectId='+empty.id);
    await expect(report.locator('#report-empty')).toHaveText('Ingen arbeidsøkter tilhører dette prosjektet.');await expect(report.locator('#report-table')).toBeHidden();
    await report.goto('/web/project-report.html?projectId='+randomUUID());await expect(report.locator('#report-error')).toHaveText('Dette prosjektet er ikke lenger tilgjengelig.');
    await report.goto('/web/project-report.html?projectId='+f.project.id);await expect(report.locator('#report-sessions tr')).toHaveCount(2);
    const identity=await(await browserRequest(report).get('/api/v1/auth/session')).json();
    expect((await browserRequest(report).post('/api/v1/auth/logout',{headers:{'X-CSRF-Token':identity.csrf}})).ok()).toBe(true);
    await report.locator('#report-refresh').click();await expect(report.locator('#report-sessions tr')).toHaveCount(0);await expect(report.locator('#report-content')).toBeHidden();await expect(report.locator('#report-sign-in')).toBeVisible();
  } finally {
    await report?.close();const current=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:previous.zoneId,locale:previous.locale},{'If-Match':current.headers().etag});await f.close();
  }
});
