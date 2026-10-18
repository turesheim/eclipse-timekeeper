import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {addDays} from '../src/model.js';

async function signIn(page,token=process.env.TIMEKEEPER_WEB_TOKEN) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
}
// WebKit and Chromium both exercise the DOM integration. The second test also
// uses native browser input to cover scrolling and compatibility-click behavior.
async function swipe(target,{dx=-140,dy=0,cancel=false,multiple=false}={}) {
  await target.evaluate((node,{dx,dy,cancel,multiple})=>{
    const touch=(x,y,id=1)=>({identifier:id,target:node,clientX:x,clientY:y});
    const start=touch(200,300),end=touch(200+dx,300+dy);
    const fire=(type,touches,changedTouches)=>{
      // Desktop WebKit exposes TouchEvent but disallows constructing Touch objects.
      const event=new Event(type,{bubbles:true,cancelable:true});Object.assign(event,{touches,changedTouches});node.dispatchEvent(event);
    };
    fire('touchstart',[start],[start]);
    if(multiple)fire('touchstart',[start,touch(220,300,2)],[touch(220,300,2)]);
    fire('touchmove',multiple ? [end,touch(220,300,2)]:[end],[end]);
    fire(cancel ? 'touchcancel':'touchend',[],[end]);
  },{dx,dy,cancel,multiple});
}

test('mobile period gestures navigate months and weeks while preserving editors, zoom and table panning',async({browser})=>{
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,viewport:{width:390,height:844},hasTouch:true,ignoreHTTPSErrors:true}),page=await context.newPage();
  try {
    await signIn(page);
    const month=page.locator('#track-month'),grid=page.locator('#track-calendar');await month.fill('2025-12');
    await swipe(grid);await expect(month).toHaveValue('2026-01');await expect(page.locator('#period-announcement')).toContainText('2026');
    await swipe(grid,{dx:140});await expect(month).toHaveValue('2025-12');
    for(const options of [{dx:30},{dx:100,dy:130},{cancel:true},{multiple:true}]) {
      await swipe(grid,options);await expect(month).toHaveValue('2025-12');
    }
    await swipe(month);await expect(month).toHaveValue('2025-12');
    await page.locator('#track-month-choose').click();await expect(page.locator('#track-month-picker')).toBeVisible();
    await swipe(grid);await expect(month).toHaveValue('2025-12');await page.keyboard.press('Escape');
    await page.getByRole('button',{name:'Record work session',exact:true}).click();await expect(page.locator('#editor')).toBeVisible();
    await swipe(grid);await expect(month).toHaveValue('2025-12');await page.locator('#cancel').click();
    for(const view of ['week','activities','calendar']) {
      await page.locator(`[data-tab=${view}]`).click();
      const heading=page.locator(view==='calendar' ? '#calendar-title':'#week-report-title');
      const first=(await page.locator('#zone-caption').textContent()).match(/\d{4}-\d{2}-\d{2}/)?.[0] || await page.locator('#calendar-date').inputValue();
      // Calendar and both weekly tables use the same selected report week.
      const monday=await page.locator('#calendar-date').inputValue() || first;
      await swipe(heading);await expect(page.locator('#week-date')).toHaveValue(addDays(monday,7));
      await expect(page.locator('#status')).toHaveText('Up to date');await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');
      await swipe(heading,{dx:140});await expect(page.locator('#week-date')).toHaveValue(monday);
      await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');
      if(view==='calendar') {
        await swipe(page.locator('.calendar-day').first());await expect(page.locator('#week-date')).toHaveValue(monday);
        await expect(page.locator('#editor')).not.toBeVisible();
      }
    }
    await page.locator('[data-tab=track]').click();await page.setViewportSize({width:1280,height:900});
    await swipe(grid);await expect(month).toHaveValue('2025-12');
  } finally {await context.close();}
  const readContext=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,viewport:{width:390,height:844},hasTouch:true,ignoreHTTPSErrors:true});
  try {
    const readPage=await readContext.newPage();await signIn(readPage,process.env.TIMEKEEPER_WEB_READ_TOKEN);
    await readPage.locator('#track-month').fill('2025-12');await swipe(readPage.locator('#track-calendar'));
    await expect(readPage.locator('#track-month')).toHaveValue('2026-01');
  } finally {await readContext.close();}
});

test('native mobile swipes preserve taps, vertical scrolling and horizontal table scrolling',async({browser,browserName,request})=>{
  test.skip(browserName!=='chromium','Native touch sequences use Chromium DevTools; WebKit runs the shared DOM integration above.');
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,viewport:{width:390,height:844},isMobile:true,hasTouch:true,ignoreHTTPSErrors:true}),page=await context.newPage();
  const headers={Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`};
  async function api(method,path,data,extra={}) {const response=await request.fetch('/api/v1'+path,{method,headers:{...headers,...extra},data});expect(response.ok()).toBe(true);return response;}
  const client=await (await api('POST','/clients',{name:'Mobile gestures '+randomUUID(),color:'#176653'})).json();let session;
  try {
    session=await (await api('POST','/work-sessions',{clientId:client.id,summary:'Swipe must not edit',start:'2025-12-10T09:00:00Z',end:'2025-12-10T15:00:00Z'})).json();
    await signIn(page);const month=page.locator('#track-month');await month.fill('2025-12');
    const cdp=await context.newCDPSession(page);
    async function nativeSwipe(locator,dx,dy=0) {
      await locator.scrollIntoViewIfNeeded();
      const {x,y}=await locator.evaluate(node=>{
        const box=node.getBoundingClientRect();
        return {x:(Math.max(24,box.left)+Math.min(innerWidth-24,box.right))/2,y:(Math.max(32,box.top)+Math.min(innerHeight-32,box.bottom))/2};
      });
      await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});
      for(let step=1;step<=8;step++)await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x+dx*step/8,y:y+dy*step/8}]});
      await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
    }
    const entry=page.locator(`[data-track-date="2025-12-10"] [data-track-daily-client="${client.id}"]`);
    await nativeSwipe(entry,-100);await expect(month).toHaveValue('2026-01');await expect(page.locator('#editor')).not.toBeVisible();
    await month.fill('2025-12');await entry.tap();await expect(page.locator('#editor')).toBeVisible();await expect(page.getByLabel('Summary',{exact:true})).toHaveValue(session.summary);await page.locator('#cancel').tap();
    await page.evaluate(()=>scrollTo(0,0));
    await nativeSwipe(page.locator('#track-calendar'),0,-160);
    await expect.poll(()=>page.evaluate(()=>scrollY)).toBeGreaterThan(0);await expect(month).toHaveValue('2025-12');
    await page.locator('[data-tab=week]').tap();await page.locator('#week-date').fill('2025-12-10');await expect(page.locator('.week-session-table')).toBeVisible();
    const table=page.locator('#week-sessions'),before=await page.locator('#week-date').inputValue();
    await nativeSwipe(table,-140);
    await expect.poll(()=>table.evaluate(node=>node.scrollLeft)).toBeGreaterThan(0);await expect(page.locator('#week-date')).toHaveValue(before);
    await expect(page.locator('#editor')).not.toBeVisible();
    expect(await (await api('GET','/work-sessions/'+session.id)).json()).toEqual(session);
  } finally {
    await context.close();
    for(const [collection,record] of [['work-sessions',session],['clients',client]])if(record) {
      const response=await api('GET','/'+collection+'/'+record.id);await api('DELETE','/'+collection+'/'+record.id,undefined,{'If-Match':response.headers().etag});
    }
  }
});
