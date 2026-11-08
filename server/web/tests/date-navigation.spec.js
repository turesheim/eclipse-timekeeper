import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';

for(const locale of ['en-GB','nb-NO'])test(`date controls share selection, navigation and accessible layout in ${locale}`,async({page,request})=>{
  test.slow();
  const username='dates-'+randomUUID().slice(0,8),password='Independent date controls password';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,email:username+'@example.invalid',displayName:username,administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const api=browserRequest(page),identity=await(await api.get('/api/v1/auth/session')).json(),profile=await api.get('/api/v1/me');
  expect((await api.put('/api/v1/me',{headers:{'X-CSRF-Token':identity.csrf,'If-Match':profile.headers().etag},data:{zoneId:'Europe/Oslo',locale}})).ok()).toBe(true);await page.reload();await expect(page.locator('#today-date')).toHaveValue(/^\d{4}-\d{2}-\d{2}$/);
  const cases=[
    ['today','#today-panel','today-date','today-previous','today-next','2026-12-31','2026-12-30','2027-01-01'],
    ['track','#track-panel','track-month','track-previous','track-next','2026-12','2026-11','2027-01'],
    ['calendar','#calendar-panel','calendar-date','calendar-previous','calendar-next','2026-12-31','2026-12-21','2027-01-04'],
    ['week','#week-toolbar','week-date','week-previous','week-next','2026-12-31','2026-12-21','2027-01-04'],
    ['activities','#week-toolbar','week-date','week-previous','week-next','2026-12-31','2026-12-21','2027-01-04']
  ];
  for(const [tab,selector,inputId,previous,next,date,before,after] of cases){
    await page.locator(`[data-tab=${tab}]`).click();const root=page.locator(selector),controls=root.locator('.date-navigation'),input=page.locator('#'+inputId);
    await expect(controls).toBeVisible();await expect(controls.locator('.input-group > button')).toHaveCount(1);await expect(controls.getByRole('group').getByRole('button')).toHaveCount(3);
    await expect(controls.locator('label')).toHaveClass('visually-hidden');
    // Native date fields may commit on blur. Navigation must use that newly
    // entered date even while the older report request is still pending.
    let release;const report='**/api/v1/reports/activities?**';
    if(tab==='calendar'){const pending=new Promise(resolve=>{release=resolve;});await page.route(report,async route=>{await pending;await route.continue();});}
    try{
      await input.fill(date);await page.locator('#'+previous).click();await expect(input).toHaveValue(before);
      await input.fill(date);await page.locator('#'+next).click();await expect(input).toHaveValue(after);
    }finally{if(release){release();await page.unrouteAll({behavior:'wait'});}}
    if(tab!=='track'){
      await input.evaluate(node=>{node.showPicker=()=>{node.dataset.opened='true';};});await controls.locator('.input-group > button').click();await expect(input).toHaveAttribute('data-opened','true');
      await input.evaluate(node=>{node.showPicker=()=>{throw new Error('Native picker unavailable');};});await controls.locator('.input-group > button').click();await expect(input).toBeFocused();
    }
    for(const width of [1440,320]){
      await page.setViewportSize({width,height:900});
      for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
        await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=['timekeeper','solarized-light'].includes(theme)?'light':'dark';},theme);
        expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`${tab}, ${theme}, ${width}px`).toBe(true);
        expect((await new AxeBuilder({page}).include(selector+' .date-navigation').analyze()).violations,`${tab}, ${theme}, ${width}px`).toEqual([]);
      }
      await controls.screenshot({path:`test-results/date-controls-${locale}-${tab}-${width}.png`});
    }
    if(['calendar','week','activities'].includes(tab))await expect(root.locator('#zone-caption,#calendar-zone')).not.toBeVisible();
  }
  await page.locator('[data-tab=track]').click();await expect(page.locator('#track-month')).toHaveAccessibleName(locale==='en-GB'?'Month':'Måned');await expect(page.locator('.track-total > span')).toHaveClass('visually-hidden');
});
