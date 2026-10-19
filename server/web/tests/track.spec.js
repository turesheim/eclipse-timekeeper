import {openAccountMenu} from './navigation.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
const token=process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request,method,path,data,headers={}) {
  const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
  expect(response.ok(),method+' '+path).toBe(true);return response;
}
async function signIn(page,credential=token) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(credential);
  await page.locator('#login-form button[type=submit]').click();
  await expect(page.locator('#status')).toHaveText((await page.locator('html').getAttribute('lang'))==='nb-NO'?'Oppdatert':'Up to date');
}
test('Track is the default month overview with exact client session hours, local dates and independent activities',async({browser,request})=> {
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,timezoneId:'America/Los_Angeles',locale:'en-GB',ignoreHTTPSErrors:true}),page=await context.newPage();
  const original=await (await api(request,'GET','/me')).json(),records=[];
  async function create(collection,data){const record=await (await api(request,'POST','/'+collection,data)).json();records.unshift([collection,record.id]);return record;}
  try {
    const profile=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag});
    const a=await create('clients',{name:'Monthly client A '+randomUUID().slice(0,6),color:'#6750a4'}),b=await create('clients',{name:'Monthly client B',color:'#176653'});
    const project=await create('projects',{name:'Independent tracked work',clientId:a.id});
    await create('activities',{taskId:null,projectId:project.id,summary:'Activity time must not enter session totals',labelIds:[],start:'2025-10-15T06:00:00Z',end:'2025-10-15T14:00:00Z'});
    const left=await create('work-sessions',{clientId:a.id,summary:'Month boundary',start:'2025-09-30T21:00:00Z',end:'2025-09-30T23:00:00Z'});
    const fall=await create('work-sessions',{clientId:a.id,summary:'Clock change',start:'2025-10-25T22:00:00Z',end:'2025-10-26T23:00:00Z'});
    const extra=await create('work-sessions',{clientId:a.id,projectId:project.id,summary:'Another session for the same client',start:'2025-10-26T12:00:00.123456789Z',end:'2025-10-26T13:00:00.123456789Z'});
    await create('work-sessions',{clientId:b.id,summary:'Other client session',start:'2025-10-26T15:00:00Z',end:'2025-10-26T17:00:00Z'});
    await create('work-sessions',{clientId:b.id,summary:'Next month boundary',start:'2025-10-31T22:00:00Z',end:'2025-11-01T02:00:00Z'});
    await signIn(page);
    await expect(page.locator('[data-tab]').first()).toHaveAccessibleName('Track');await expect(page.locator('[data-tab=track]')).toHaveAttribute('aria-current','page');
    await expect(page.locator('#track-panel')).toBeVisible();await expect(page.locator('#week-panel')).not.toBeVisible();
    await page.getByLabel('Month',{exact:true}).fill('2025-10');
    await expect(page.locator('#track-title')).toContainText('2025');await expect(page.locator('#track-zone')).toHaveCount(0);
    await expect(page.locator(`#track-clients [data-track-client="${a.id}"]`)).toContainText('27h 0m');
    await expect(page.locator(`#track-clients [data-track-client="${b.id}"]`)).toContainText('3h 0m');
    await expect(page.locator('#track-clients [data-track-client]')).toHaveCount(2);
    await expect(page.locator('.track-month thead th')).toHaveCount(7);await expect(page.locator('.track-month tbody tr')).toHaveCount(5);
    await expect(page.locator('.track-month tbody td').first()).toHaveAttribute('data-track-date','2025-09-29');
    const day=page.locator('[data-track-date="2025-10-26"]');await expect(day.locator('[data-track-daily-client]')).toHaveCount(2);await expect(day.locator(`[data-track-daily-client="${a.id}"]`)).toContainText('26h 0m');await expect(day.locator(`[data-track-daily-client="${b.id}"]`)).toContainText('2h 0m');await expect(day).not.toContainText('Clock change');
    await expect(page.locator('[data-track-date="2025-10-01"]')).toContainText('1h 0m');
    expect(await day.locator(`[data-track-daily-client="${a.id}"]`).evaluate(value=>getComputedStyle(value).borderInlineStartColor)).toBe('rgb(103, 80, 164)');
    const tooltip=day.locator(`[data-track-daily-client="${a.id}"]`);await tooltip.hover();
    await expect(tooltip).toHaveAttribute('title',/2 work sessions/);await expect(tooltip).toHaveAttribute('title',/Clock change/);await expect(tooltip).toHaveAttribute('title',/Another session for the same client/);await expect(tooltip).toHaveAttribute('title',/13:00:00/);await expect(tooltip).toHaveAttribute('title',new RegExp(project.name));await expect(tooltip).not.toHaveAttribute('title',/Other client session|Month boundary/);
    const audit=await new AxeBuilder({page}).include('#workspace').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
    await page.screenshot({path:'test-results/track-desktop.png',fullPage:true});
    await expect(day.locator('button[data-track-daily-client]')).toHaveCount(2);await expect(page.locator('[data-track-session]')).toHaveCount(0);
    const single=page.locator(`[data-track-date="2025-10-01"] [data-track-daily-client="${a.id}"]`);
    await single.click();await expect(page.getByRole('dialog',{name:'Edit work session',exact:true})).toBeVisible();
    await expect(page.getByLabel('Title',{exact:true})).toHaveCount(0);await expect(page.getByLabel('Summary',{exact:true})).toHaveValue('Month boundary');
    await page.getByLabel('Summary',{exact:true}).fill('Edited boundary\n\nPreserved notes');await page.getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#editor')).not.toBeVisible();
    const saved=await (await api(request,'GET','/work-sessions/'+left.id)).json();expect(saved.summary).toBe('Edited boundary\n\nPreserved notes');expect(saved.start).toBe(left.start);expect(saved.end).toBe(left.end);expect(saved).not.toHaveProperty('title');
    const multiple=day.locator(`[data-track-daily-client="${a.id}"]`),picker=page.getByRole('dialog',{name:'Choose work session',exact:true});
    await expect(multiple).toBeEnabled();await multiple.focus();await multiple.press('Enter');await expect(picker).toBeVisible();await expect(picker.locator('[data-track-session-choice]')).toHaveCount(2);
    await expect(picker).toContainText('Clock change');await expect(picker).toContainText('Another session for the same client');await expect(picker).not.toContainText('Other client session');await expect(picker).not.toContainText('Month boundary');
    await expect(picker).toContainText('13:00:00');await expect(picker).toContainText(project.name);
    const pickerAudit=await new AxeBuilder({page}).include('#track-session-picker').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(pickerAudit.violations).toEqual([]);
    await page.keyboard.press('Escape');await expect(picker).not.toBeVisible();await expect(multiple).toBeFocused();await expect(page.locator('#editor')).not.toBeVisible();
    await multiple.click();await picker.locator(`[data-track-session-choice="${extra.id}"]`).focus();
    const peer=await api(request,'GET','/work-sessions/'+extra.id);
    await api(request,'PUT','/work-sessions/'+extra.id,{clientId:a.id,projectId:project.id,summary:'Peer session update',start:extra.start,end:extra.end},{'If-Match':peer.headers().etag});
    await expect(picker).toContainText('Peer session update');await expect(picker.locator(`[data-track-session-choice="${extra.id}"]`)).toBeFocused();
    await page.setViewportSize({width:390,height:844});expect(await picker.evaluate(value=>value.scrollWidth<=value.clientWidth)).toBe(true);
    await page.screenshot({path:'test-results/track-session-picker-mobile.png',fullPage:true});
    await picker.getByRole('button',{name:/Peer session update/}).click();await expect(picker).not.toBeVisible();await expect(page.getByLabel('Summary',{exact:true})).toHaveValue('Peer session update');await expect(page.getByLabel('Project (optional)',{exact:true})).toHaveValue(project.id);
    await page.getByLabel('Summary',{exact:true}).fill('Edited monthly session\n\nMeeting notes');await page.getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#editor')).not.toBeVisible();
    const edited=await (await api(request,'GET','/work-sessions/'+extra.id)).json();expect(edited.summary).toBe('Edited monthly session\n\nMeeting notes');expect(edited.start).toBe(extra.start);expect(edited.end).toBe(extra.end);expect(edited.projectId).toBe(project.id);expect(edited).not.toHaveProperty('title');
    await expect(page.getByLabel('Month',{exact:true})).toHaveValue('2025-10');await page.setViewportSize({width:1280,height:720});
    await page.getByRole('button',{name:'Next month',exact:true}).click();await expect(page.getByLabel('Month',{exact:true})).toHaveValue('2025-11');
    await expect(page.locator(`#track-clients [data-track-client="${b.id}"]`)).toContainText('3h 0m');
    await expect(page.locator(`#track-clients [data-track-client="${a.id}"]`)).toHaveCount(0);
    await expect(page.locator('#track-clients [data-track-client]')).toHaveCount(1);
    await page.getByRole('button',{name:'Next month',exact:true}).click();await expect(page.getByLabel('Month',{exact:true})).toHaveValue('2025-12');
    await expect(page.locator('#track-clients [data-track-client]')).toHaveCount(0);await expect(page.locator('#track-clients')).toHaveText('No work sessions this month.');
    await page.getByRole('button',{name:'Previous month',exact:true}).click();
    await page.getByRole('button',{name:'Previous month',exact:true}).click();
    await page.locator('[data-tab=week]').click();await page.locator('[data-tab=track]').click();await expect(page.getByLabel('Month',{exact:true})).toHaveValue('2025-10');
    await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await expect(day.locator(`[data-track-daily-client="${a.id}"] .track-client-day-time-short`)).toHaveText('26h');await expect(day.locator(`[data-track-daily-client="${a.id}"]`)).toHaveAccessibleName(new RegExp('26h 0m'));await expect(day.locator('.track-client-day-time-short').first()).toBeVisible();
    const mobile=await new AxeBuilder({page}).include('#workspace').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(mobile.violations).toEqual([]);
    await expect(page.locator('.track-month thead th')).toHaveCount(7);await page.screenshot({path:'test-results/track-mobile.png',fullPage:true});
    await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#track-clients')).toBeEmpty();await expect(page.locator('#track-total')).toBeEmpty();
    await signIn(page,process.env.TIMEKEEPER_WEB_READ_TOKEN);await page.getByLabel('Month',{exact:true}).fill('2025-10');
    await expect(page.locator('#track-calendar button[data-track-daily-client]')).toHaveCount(0);await expect(page.locator(`[data-track-date="2025-10-01"] [data-track-daily-client="${a.id}"]`)).toContainText('1h 0m');
    await expect(page.locator(`[data-track-date="2025-10-01"] [data-track-daily-client="${a.id}"]`)).toHaveAttribute('title',/Edited boundary\n\nPreserved notes/);
    await expect(page.locator(`#track-clients [data-track-client="${a.id}"]`)).toContainText('27h 0m');
  } finally {
    await context.close();
    for(const [collection,id] of records){const current=await api(request,'GET',`/${collection}/${id}`);await api(request,'DELETE',`/${collection}/${id}`,undefined,{'If-Match':current.headers().etag});}
    const current=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:original.zoneId},{'If-Match':current.headers().etag});
  }
});
test('Track month picker supports year selection, keyboard dismissal and a centered localized heading',async({browser})=>{
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,locale:'nb-NO',viewport:{width:1280,height:900},ignoreHTTPSErrors:true}),page=await context.newPage();
  try {
    await signIn(page);
    const month=page.getByLabel('Måned',{exact:true}),choose=page.getByRole('button',{name:'Velg måned og år',exact:true}),popup=page.getByRole('dialog',{name:'Velg måned og år',exact:true});
    await month.fill('2025-10');await expect(page.locator('#track-title')).toHaveText('Oktober 2025');
    const toolbar=await page.locator('.track-toolbar').boundingBox(),heading=await page.locator('.track-heading').boundingBox(),controls=await page.locator('.track-controls').boundingBox();
    expect(Math.abs(heading.x+heading.width/2-toolbar.x-toolbar.width/2)).toBeLessThan(1);expect(controls.x+controls.width).toBeLessThanOrEqual(heading.x);
    await choose.focus();await choose.press('Enter');await expect(popup).toBeVisible();await expect(choose).toHaveAttribute('aria-expanded','true');
    await expect(popup.getByLabel('År',{exact:true})).toHaveValue('2025');await expect(popup.getByRole('button',{name:'oktober',exact:true})).toHaveAttribute('aria-pressed','true');
    await expect(popup.getByRole('button',{name:'oktober',exact:true})).toBeFocused();
    expect(await popup.getByRole('button',{name:'oktober',exact:true}).evaluate(value=>getComputedStyle(value).outlineStyle)).toBe('none');
    expect(await popup.getByRole('button',{name:'oktober',exact:true}).evaluate(value=>getComputedStyle(value).boxShadow)).not.toBe('none');
    const audit=await new AxeBuilder({page}).include('#track-month-picker').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
    await page.screenshot({path:'test-results/track-month-picker-desktop.png',fullPage:true});
    await page.keyboard.press('Escape');await expect(popup).not.toBeVisible();await expect(choose).toBeFocused();await expect(month).toHaveValue('2025-10');
    await choose.click();await popup.getByRole('button',{name:'Neste år',exact:true}).click();await expect(popup.getByLabel('År',{exact:true})).toHaveValue('2026');
    await popup.getByRole('button',{name:'Forrige år',exact:true}).click();await popup.getByLabel('År',{exact:true}).fill('2024');
    await popup.getByRole('button',{name:'februar',exact:true}).click();await expect(popup).not.toBeVisible();await expect(month).toHaveValue('2024-02');await expect(page.locator('#track-title')).toHaveText('Februar 2024');await expect(choose).toBeFocused();
    await choose.click();await popup.getByLabel('År',{exact:true}).fill('0');await expect(popup.getByRole('button',{name:'januar',exact:true})).toBeDisabled();
    await popup.getByLabel('År',{exact:true}).fill('1');await expect(popup.getByRole('button',{name:'Forrige år',exact:true})).toBeDisabled();
    await popup.getByLabel('År',{exact:true}).fill('9998');await expect(popup.getByRole('button',{name:'Neste år',exact:true})).toBeDisabled();
    await page.keyboard.press('Escape');await expect(month).toHaveValue('2024-02');
    await page.setViewportSize({width:390,height:844});await choose.click();await expect(popup).toBeVisible();
    await expect.poll(()=>popup.evaluate(value=>{const rect=value.getBoundingClientRect();return rect.left>=0&&rect.right<=innerWidth&&rect.bottom<=innerHeight;})).toBe(true);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    const mobile=await new AxeBuilder({page}).include('#track-panel').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(mobile.violations).toEqual([]);
    await page.screenshot({path:'test-results/track-month-picker-mobile.png',fullPage:true});
    await page.locator('[data-tab=week]').click();await expect(popup).not.toBeVisible();await page.locator('[data-tab=track]').click();await expect(month).toHaveValue('2024-02');
    await choose.click();await popup.getByRole('button',{name:'desember',exact:true}).click();await page.getByRole('button',{name:'Neste måned',exact:true}).click();await expect(month).toHaveValue('2025-01');
    await page.getByRole('button',{name:'Gjeldende måned',exact:true}).click();await expect(month).not.toHaveValue('2025-01');
    await choose.click();await page.locator('#account-menu > summary').click();await page.getByRole('button',{name:'Logg ut',exact:true}).click();await expect(popup).not.toBeVisible();
  } finally {await context.close();}
});
test('Track updates running session hours without stealing focus and reconciles peer edits',async({page,request})=> {
  const client=await (await api(request,'POST','/clients',{name:'Live monthly client',color:'#176653'})).json();let session;
  try {
    expect((await api(request,'GET','/work-sessions/active')).status()).toBe(204);
    session=await (await api(request,'POST','/work-sessions',{clientId:client.id,summary:'Live month overview',start:new Date(Date.now()-120000).toISOString(),end:null})).json();
    await signIn(page);await expect(page.locator('[data-tab=track]')).toHaveAttribute('aria-current','page');
    const card=page.locator(`[data-track-daily-client="${client.id}"]`).last(),duration=card.locator('.track-client-day-time'),focus=page.getByLabel('Month',{exact:true});
    const total=page.locator(`[data-track-client="${client.id}"] strong`);
    await focus.focus();const before=await duration.textContent(),monthBefore=await total.textContent();
    await expect.poll(()=>duration.textContent()).not.toBe(before);await expect(focus).toBeFocused();await expect.poll(()=>total.textContent()).not.toBe(monthBefore);
    const current=await api(request,'GET','/work-sessions/'+session.id);
    await api(request,'PUT','/work-sessions/'+session.id,{clientId:client.id,summary:'Updated live month',start:new Date(Date.parse(session.start)-60000).toISOString(),end:null},{'If-Match':current.headers().etag});
    const clientVersion=await api(request,'GET','/clients/'+client.id);await api(request,'PUT','/clients/'+client.id,{name:'Updated monthly client',color:'#176653'},{'If-Match':clientVersion.headers().etag});await expect(card).toContainText('Updated monthly client');await expect(card).not.toContainText('Updated live month');await expect(focus).toBeFocused();
    const latest=await api(request,'GET','/work-sessions/'+session.id);
    await api(request,'POST','/work-sessions/'+session.id+'/stop',undefined,{'If-Match':latest.headers().etag,'Idempotency-Key':randomUUID()});
    await expect(page.locator('#work-session-state')).toHaveText('Stopped');await expect(card).not.toContainText('Running');
  } finally {
    if(session){const current=await api(request,'GET','/work-sessions/'+session.id);await api(request,'DELETE','/work-sessions/'+session.id,undefined,{'If-Match':current.headers().etag});}
    const current=await api(request,'GET','/clients/'+client.id);await api(request,'DELETE','/clients/'+client.id,undefined,{'If-Match':current.headers().etag});
  }
});
