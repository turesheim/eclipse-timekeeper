import {randomUUID} from 'node:crypto';
import {test,expect} from '@playwright/test';
import {browserRequest} from './browser-request.js';
import {calendarDay,selectedJournal} from './today-calendar.js';
import {captureScreenshot} from './screenshots.js';

test.use({hasTouch:true,viewport:{width:1440,height:1000}});

test('Today browses months independently and opens journals with keyboard and touch',async({page,request})=>{
  const username='mini-calendar-'+randomUUID().slice(0,8),password='Synthetic mini calendar password';
  const headers={Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`};
  expect((await request.post('/api/v1/admin/users',{headers,data:{username,password,email:username+'@example.invalid',displayName:'Mini calendar test',administrator:false}})).status()).toBe(201);
  await page.clock.setFixedTime(new Date('2027-01-01T00:30:00Z'));
  await page.goto('/web/?day=2026-12-10');
  await page.locator('#auth-mode').selectOption('login');
  await page.locator('#username').fill(username);await page.locator('#password').fill(password);
  await page.locator('#login-form button[type=submit]').click();
  await expect(page.locator('#status')).toHaveText('Up to date');
  const api=browserRequest(page),identity=await(await api.get('/api/v1/auth/session')).json(),profile=await api.get('/api/v1/me');
  expect((await api.put('/api/v1/me',{headers:{'X-CSRF-Token':identity.csrf,'If-Match':profile.headers().etag},data:{zoneId:'America/Los_Angeles',locale:'en-GB'}})).ok()).toBe(true);
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');

  const calendar=page.locator('.today-month'),grid=calendar.locator('#today-month-grid');
  const previous=calendar.getByRole('button',{name:'Previous month'}),next=calendar.getByRole('button',{name:'Next month'});
  const today=calendar.getByRole('button',{name:'Today',exact:true}),selected=selectedJournal(page);
  await expect(page.locator('.today-toolbar input[type=date],#today-date-choose,#today-previous,#today-next,.today-toolbar #today-current')).toHaveCount(0);
  await expect(selected).toHaveText('Thursday, 10 December 2026');
  await expect(calendar.getByRole('heading',{level:3})).toHaveText('December 2026');
  expect((await today.boundingBox()).y).toBeGreaterThanOrEqual((await grid.boundingBox()).y+(await grid.boundingBox()).height);
  await expect(calendarDay(page,'2026-12-31')).toHaveAttribute('aria-current','date');
  await previous.focus();await page.keyboard.press('Enter');
  await expect(grid).toHaveAttribute('data-month','2026-11');await expect(selected).toHaveAttribute('data-date','2026-12-10');
  await next.focus();await page.keyboard.press('Space');
  await expect(grid).toHaveAttribute('data-month','2026-12');await expect(calendarDay(page,'2026-12-10')).toHaveAttribute('aria-pressed','true');
  await calendarDay(page,'2026-12-11').focus();await page.keyboard.press('Enter');
  await expect(selected).toHaveText('Friday, 11 December 2026');await expect(calendarDay(page,'2026-12-11')).toHaveAttribute('aria-pressed','true');
  await today.focus();await page.keyboard.press('Enter');
  await expect(selected).toHaveAttribute('data-date','2026-12-31');await expect(calendarDay(page,'2026-12-31')).toHaveAttribute('aria-pressed','true');
  await captureScreenshot(page,{path:'test-results/today-mini-calendar-desktop.png'});

  await page.setViewportSize({width:320,height:844});
  await expect(page.locator('.today-sidebar').locator('.today-month')).toBeVisible();
  await next.tap();await expect(grid).toHaveAttribute('data-month','2027-01');
  await expect(selected).toHaveAttribute('data-date','2026-12-31');
  await today.tap();await expect(grid).toHaveAttribute('data-month','2026-12');
  await expect(calendarDay(page,'2026-12-31')).toHaveAttribute('aria-pressed','true');
  await calendarDay(page,'2026-12-30').tap();await expect(calendarDay(page,'2026-12-30')).toHaveAttribute('aria-pressed','true');
  await expect(selected).toHaveText('Wednesday, 30 December 2026');
  await expect(selected).toBeFocused();await expect(selected).toBeInViewport();
  const week=calendar.locator('[data-week="2026-W53"]');
  await week.tap();await expect(selected).toHaveText('Week note 2026-W53');await expect(selected).toBeInViewport();
  await today.tap();await expect(selected).toHaveText('Thursday, 31 December 2026');
  await expect(today).toBeVisible();
  await calendar.scrollIntoViewIfNeeded();
  await captureScreenshot(page,{path:'test-results/today-mini-calendar-phone.png'});
});
