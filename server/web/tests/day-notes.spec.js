import {captureScreenshot} from './screenshots.js';
import {setTheme} from './navigation.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';

const base=()=>process.env.TIMEKEEPER_WEB_URL;
test.beforeAll(async({request})=>{
  const headers={Origin:new URL(base()).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){
    const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});
    expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});
  }
});
async function fresh(request){
  const username='day-notes-'+randomUUID().slice(0,8),password='Independent day note password';
  const reply=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});
  expect(reply.status()).toBe(201);return {username,password};
}
const context=(browser,options={})=>browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB',timezoneId:'Pacific/Kiritimati',...options});
async function signIn(page,user){
  await page.goto('/web/');await expect(page.locator('#login-form button[type=submit]')).toBeEnabled();
  await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(user.username);await page.locator('#password').fill(user.password);await page.locator('#login-form button[type=submit]').click();
  await expect(page.locator('#workspace')).toBeVisible();await expect(page.locator('#status')).toHaveText('Up to date');
}
async function apiFor(page){
  const api=browserRequest(page),identity=await(await api.get('/api/v1/auth/session')).json();
  return async(method,path,data,extra={})=>{
    const reply=await api.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...extra}});
    expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;
  };
}
const note=(page,date='2026-05-17')=>page.locator(`[data-track-note-date="${date}"]`);
const day=(page,date='2026-05-17')=>page.locator(`[data-track-date="${date}"]`);
const dialog=page=>page.locator('#track-note-editor');
async function month(page){await expect(page.locator('#workspace')).toBeVisible();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('#track-month').fill('2026-05');await expect(note(page)).toBeEnabled();}
async function save(page,text){await page.locator('#track-note-text').fill(text);await dialog(page).getByRole('button',{name:'Save',exact:true}).click();await expect(dialog(page)).not.toBeVisible();}
async function menu(page,date='2026-05-17'){
  await expect(note(page,date)).toBeEnabled();await day(page,date).click({button:'right',position:{x:8,y:65}});await expect(page.getByRole('menu')).toBeVisible();
  await page.getByRole('menuitem').click();await expect(dialog(page)).toBeVisible();await expect(page.locator('#track-note-text')).toBeEnabled();
}
async function put(api,date,text){const current=await api('GET','/day-notes/'+date);return api('PUT','/day-notes/'+date,{text},{'If-Match':current.headers().etag});}
async function expectMenuAt(page,{x,y}){
  const menu=page.getByRole('menu');await expect(menu).toBeVisible();const box=await menu.boundingBox(),viewport=page.viewportSize();
  expect(Math.abs(box.x-Math.max(8,Math.min(x,viewport.width-box.width-8)))).toBeLessThan(1);
  expect(Math.abs(box.y-Math.max(8,Math.min(y,viewport.height-box.height-8)))).toBeLessThan(1);
}

test('day note menus stay beside the press after scrolling and fit the viewport edges',async({browser,request})=>{
  const owner=await context(browser,{viewport:{width:1000,height:740}}),page=await owner.newPage();
  try{
    await signIn(page,await fresh(request));await month(page);
    for(const date of ['2026-05-11','2026-05-31']){
      const cell=day(page,date);await cell.evaluate(node=>node.scrollIntoView({block:'end',behavior:'instant'}));
      const point=await cell.evaluate(node=>{const r=node.getBoundingClientRect();return{x:r.right-8,y:r.bottom-8};});
      expect(await page.evaluate(()=>scrollY)).toBeGreaterThan(0);
      await page.mouse.click(point.x,point.y,{button:'right'});await expectMenuAt(page,point);await page.keyboard.press('Escape');
    }
  }finally{await owner.close();}
});

test('pressing and dragging calendar text leaves it unselected while day notes remain editable',async({browser,request})=>{
  const owner=await context(browser),page=await owner.newPage();
  try{
    await signIn(page,await fresh(request));await month(page);
    const headers=page.locator('.track-month th');await headers.first().dblclick();expect(await page.evaluate(()=>getSelection().toString())).toBe('');
    const first=await headers.first().boundingBox(),last=await headers.last().boundingBox();
    await page.mouse.move(first.x+first.width/2,first.y+first.height/2);await page.mouse.down();
    await page.mouse.move(last.x+last.width/2,last.y+last.height/2,{steps:20});await page.mouse.up();
    expect(await page.evaluate(()=>getSelection().toString())).toBe('');
    await day(page).locator('.track-date').dblclick();expect(await page.evaluate(()=>getSelection().toString())).toBe('');
    await menu(page);const text=page.locator('#track-note-text');await text.fill('Editable note text');await text.dblclick();
    expect(await text.evaluate(node=>node.value.slice(node.selectionStart,node.selectionEnd))).not.toBe('');
  }finally{await owner.close();}
});

test('day notes can be added from the day menu, edited, canceled and removed without changing work',async({browser,request})=>{
  const owner=await context(browser,{viewport:{width:1440,height:1000}}),page=await owner.newPage();
  try{
    await signIn(page,await fresh(request));const api=await apiFor(page),profile=await api('GET','/me');await api('PUT','/me',{zoneId:'America/Los_Angeles',holidayCountry:'NO'},{'If-Match':profile.headers().etag});
    const client=await(await api('POST','/clients',{name:'Day note work',color:'#176653'})).json();
    const work=await(await api('POST','/work-sessions',{clientId:client.id,summary:'Unchanged work',start:'2026-05-17T12:00:00Z',end:'2026-05-17T13:00:00Z'})).json();
    await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await month(page);
    await expect(day(page).locator('.track-holiday-label')).toContainText('Constitution Day');const total=await page.locator('#track-total').innerText(),original=await api('GET','/work-sessions/'+work.id);
    await menu(page);await save(page,'Ferie');await expect(note(page)).toHaveText('Ferie');await expect(day(page).locator('.track-holiday-label')).toContainText('Constitution Day');
    await menu(page);await expect(page.locator('#track-note-text')).toHaveValue('Ferie');await page.locator('#track-note-text').fill('Canceled draft');await page.keyboard.press('Escape');await expect(note(page)).toBeFocused();await expect(note(page)).toHaveText('Ferie');
    await note(page).focus();await page.keyboard.press('Shift+F10');await expect(page.getByRole('menuitem')).toHaveText('Edit day note');await page.keyboard.press('Escape');await expect(note(page)).toBeFocused();
    await menu(page);await save(page,'Avspasering');await expect(note(page)).toHaveText('Avspasering');
    await note(page,'2026-05-18').click();await save(page,'Ferie');await expect(note(page,'2026-05-18')).toHaveText('Ferie');expect(await day(page,'2026-05-18').locator('[data-track-daily-client]').count()).toBe(0);
    await captureScreenshot(page,{path:'test-results/day-notes-desktop.png',fullPage:true});
    await menu(page);await captureScreenshot(page,{path:'test-results/day-notes-editor.png'});await save(page,'');await expect(note(page)).toHaveClass(/is-empty/);
    await expect(page.locator('#track-total')).toHaveText(total);const unchanged=await api('GET','/work-sessions/'+work.id);expect(unchanged.headers().etag).toBe(original.headers().etag);expect(await unchanged.json()).toEqual(work);
    await page.reload();await month(page);await expect(note(page,'2026-05-18')).toHaveText('Ferie');await expect(note(page)).toHaveClass(/is-empty/);
    const changed=await api('GET','/me');await api('PUT','/me',{zoneId:'Pacific/Honolulu'},{'If-Match':changed.headers().etag});await page.reload();await month(page);await expect(note(page,'2026-05-18')).toHaveText('Ferie');
  }finally{await owner.close();}
});

test('Escape immediately releases the day-note editor before its queued close event',async({browser,request})=>{
  const owner=await context(browser),page=await owner.newPage();
  try{
    // Native dialog focus restoration precedes the queued close event. Hold that
    // event to exercise keyboard input during this real lifecycle boundary.
    await page.addInitScript(()=>{
      window.holdDayNoteClose=true;
      document.addEventListener('close',event=>{if(window.holdDayNoteClose&&event.target.id==='track-note-editor')event.stopImmediatePropagation();},true);
    });
    await signIn(page,await fresh(request));await month(page);await menu(page);await page.locator('#track-note-text').fill('Canceled draft');
    await page.keyboard.press('Escape');await expect(dialog(page)).toBeHidden();await expect(note(page)).toBeFocused();
    await page.keyboard.press('Shift+F10');await expect(page.getByRole('menuitem')).toHaveText('Add day note');
    await page.getByRole('menuitem').click();await expect(dialog(page)).toBeVisible();await expect(page.locator('#track-note-text')).toHaveValue('');
    await page.evaluate(()=>{window.holdDayNoteClose=false;document.getElementById('track-note-editor').dispatchEvent(new Event('close'));});
    await expect(page.locator('#track-note-text')).toBeFocused();await expect(dialog(page)).toBeVisible();
  }finally{await owner.close();}
});

test('calendar summaries expose body presence while label-only edits preserve private Markdown',async({browser,request})=>{
  const owner=await context(browser),page=await owner.newPage();
  try{
    await signIn(page,await fresh(request));const api=await apiFor(page),date='2026-05-17',body='\r\n# Private Markdown marker\r\n  keep spaces  \r\n';
    const empty=await api('GET','/day-notes/'+date);await api('PUT','/day-notes/'+date,{body},{'If-Match':empty.headers().etag});await month(page);
    await expect(note(page)).not.toHaveClass(/is-empty/);await expect(note(page)).toHaveAttribute('title','Markdown note');
    const summaries=await api('GET','/day-notes?fromDate='+date+'&toDate='+date),summary=await summaries.json();
    expect(summary.items[0].hasBody).toBe(true);expect(summary.items[0]).not.toHaveProperty('body');expect(await summaries.text()).not.toContain('Private Markdown marker');
    await menu(page);await expect(page.locator('#track-note-text')).toHaveValue('');await save(page,'Planning');
    expect((await(await api('GET','/day-notes/'+date)).json()).body).toBe(body);
    await menu(page);await save(page,'');await expect(note(page)).not.toHaveClass(/is-empty/);expect((await(await api('GET','/day-notes/'+date)).json()).body).toBe(body);
  }finally{await owner.close();}
});

test('day notes keep a stale draft and require review after an unconfirmed save',async({browser,request})=>{
  const owner=await context(browser),page=await owner.newPage();
  try{
    await signIn(page,await fresh(request));const api=await apiFor(page);await month(page);await test.step('Open initial editor',()=>menu(page));await page.locator('#track-note-text').fill('My draft');await test.step('Save from another client',()=>put(api,'2026-05-17','Peer update'));
    await dialog(page).getByRole('button',{name:'Save',exact:true}).click();await expect(dialog(page).getByRole('alert')).toContainText('changed');await expect(page.locator('#track-note-text')).toHaveValue('My draft');await expect(dialog(page).getByRole('button',{name:'Save',exact:true})).toBeDisabled();
    await dialog(page).getByRole('button',{name:'Cancel',exact:true}).click();await menu(page);await expect(page.locator('#track-note-text')).toHaveValue('Peer update');
    let writes=0;await page.route('**/api/v1/day-notes/2026-05-17',async route=>{if(route.request().method()!=='PUT'){await route.continue();return;}writes++;await route.fetch();await route.abort('failed');});
    await page.locator('#track-note-text').fill('Committed once');await dialog(page).getByRole('button',{name:'Save',exact:true}).click();await expect(dialog(page).getByRole('alert')).toContainText('could not be confirmed');await expect(dialog(page).getByRole('button',{name:'Save',exact:true})).toBeDisabled();expect(writes).toBe(1);
    await dialog(page).getByRole('button',{name:'Cancel',exact:true}).click();await menu(page);await expect(page.locator('#track-note-text')).toHaveValue('Committed once');expect(writes).toBe(1);
  }finally{await owner.close();}
});

test('day notes are private, persist across sessions and remain viewable without write permission',async({browser,request})=>{
  const user=await fresh(request),owner=await context(browser),viewer=await context(browser),other=await context(browser);
  try{
    const page=await owner.newPage();await signIn(page,user);const api=await apiFor(page);await put(api,'2026-05-17','Ferie');
    const token=await(await api('POST','/auth/tokens',{name:'Day notes viewer',permissions:['read'],expiresAt:null})).json();
    const read=await viewer.newPage();await read.goto('/web/');await read.locator('#auth-mode').selectOption('token');await read.locator('#token').fill(token.token);await read.locator('#login-form button[type=submit]').click();await expect(read.locator('#workspace')).toBeVisible();await expect(read.locator('#status')).toHaveText('Up to date');await read.locator('#track-month').fill('2026-05');await expect(note(read)).toHaveText('Ferie');expect(await note(read).evaluate(node=>node.tagName)).toBe('SPAN');expect(await read.locator('button[data-track-note-date]').count()).toBe(0);
    await note(read).dblclick();expect(await read.evaluate(()=>getSelection().toString())).toBe('');
    await day(read).dispatchEvent('contextmenu',{bubbles:true,cancelable:true,clientX:100,clientY:100});await expect(read.getByRole('menu')).not.toBeVisible();
    const isolated=await other.newPage();await signIn(isolated,await fresh(request));await month(isolated);await expect(note(isolated)).toHaveClass(/is-empty/);
    const second=await owner.newPage();await second.goto('/web/');await month(second);await expect(note(second)).toHaveText('Ferie');await put(api,'2026-05-18','Avspasering');await expect(note(second,'2026-05-18')).toHaveText('Avspasering');await expect(note(read,'2026-05-18')).toHaveText('Avspasering');
  }finally{await owner.close();await viewer.close();await other.close();}
});

test('day notes fit all themes and narrow screens, and failed reads leave work available',async({browser,request})=>{
  const owner=await context(browser),page=await owner.newPage();
  try{
    await signIn(page,await fresh(request));const api=await apiFor(page);await put(api,'2026-05-17','<Ferie> '+ 'Avspasering '.repeat(6));await month(page);await expect(note(page)).toContainText('<Ferie>');expect(await note(page).locator('ferie').count()).toBe(0);
    await page.setViewportSize({width:390,height:844});
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
      await setTheme(page,theme);
      const audit=await new AxeBuilder({page}).include('#track-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations.map(value=>({id:value.id,nodes:value.nodes.map(node=>node.target)}))).toEqual([]);
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await note(page).click();await expect(page.locator('#track-note-text')).toBeEnabled();const edit=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(edit.violations.map(value=>value.id)).toEqual([]);await page.keyboard.press('Escape');
    }
    await page.setViewportSize({width:320,height:740});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await captureScreenshot(page,{path:'test-results/day-notes-mobile.png',fullPage:true});
    let failed=true;await page.route('**/api/v1/day-notes?*',route=>failed?route.fulfill({status:503,contentType:'application/problem+json',body:JSON.stringify({code:'UNAVAILABLE'})}):route.continue());await page.reload();await expect(page.locator('#workspace')).toBeVisible();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('#track-month').fill('2026-05');await expect(page.locator('.track-note-status')).toContainText('could not be loaded');await expect(page.locator('#session-new')).toBeEnabled();await expect(note(page)).toBeDisabled();
    failed=false;await page.locator('.track-note-status').getByRole('button',{name:'Retry'}).click();await expect(note(page)).toContainText('<Ferie>');await expect(note(page)).toBeEnabled();
  }finally{await owner.close();}
});

test('a long press opens the day note menu while movement, release and multiple touches cancel it',async({browser,browserName,request})=>{
  const owner=await context(browser,{viewport:{width:390,height:844},hasTouch:true,isMobile:true}),page=await owner.newPage();
  try{
    await signIn(page,await fresh(request));await month(page);await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');const cell=day(page,'2026-05-10');await cell.evaluate(node=>node.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'}));
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    await expect(note(page,'2026-05-10')).toHaveCSS('opacity','0');
    await captureScreenshot(page,{path:'test-results/day-notes-touch.png'});
    const point=await cell.evaluate(node=>{const r=node.getBoundingClientRect();return{clientX:r.left+r.width/2,clientY:r.top+55};});
    const start=()=>cell.dispatchEvent('pointerdown',{...point,pointerType:'touch',pointerId:7,isPrimary:true,bubbles:true});
    await start();await cell.dispatchEvent('pointermove',{...point,clientX:point.clientX+20,pointerType:'touch',pointerId:7,bubbles:true});await page.waitForTimeout(650);await expect(page.getByRole('menu')).not.toBeVisible();
    await start();await cell.dispatchEvent('pointerup',{pointerType:'touch',pointerId:7,bubbles:true});await page.waitForTimeout(650);await expect(page.getByRole('menu')).not.toBeVisible();
    await start();await cell.evaluate(node=>{const event=new Event('touchstart',{bubbles:true});Object.assign(event,{touches:[{},{}]});node.dispatchEvent(event);});await page.waitForTimeout(650);await expect(page.getByRole('menu')).not.toBeVisible();
    await start();await page.waitForTimeout(500);await cell.dispatchEvent('pointerup',{pointerType:'touch',pointerId:7,bubbles:true});await expectMenuAt(page,{x:point.clientX,y:point.clientY});expect(await page.evaluate(()=>getSelection().toString())).toBe('');await page.keyboard.press('Escape');
    await start();await expect(page.getByRole('menuitem')).toBeVisible();await cell.dispatchEvent('pointerup',{pointerType:'touch',pointerId:7,bubbles:true});await expect(page.locator('#track-month')).toHaveValue('2026-05');await expect(dialog(page)).not.toBeVisible();await page.getByRole('menuitem').tap();await expect(dialog(page)).toBeVisible();await expect(page.locator('#track-note-text')).toBeEnabled();await save(page,'Ferie');await expect(note(page,'2026-05-10')).toHaveText('Ferie');
    await expect(note(page,'2026-05-10')).toHaveCSS('opacity','1');
    if(browserName==='chromium'){
      await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');await expect(note(page,'2026-05-11')).toBeEnabled();const target=day(page,'2026-05-11');await target.evaluate(node=>node.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'}));await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));const point=await target.locator('.track-date').evaluate(node=>{const r=node.getBoundingClientRect();return{x:r.left+r.width/2,y:r.top+r.height/2};});const cdp=await owner.newCDPSession(page);
      await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[point]});await expectMenuAt(page,point);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await expectMenuAt(page,point);expect(await page.evaluate(()=>getSelection().toString())).toBe('');await expect(dialog(page)).not.toBeVisible();await captureScreenshot(page,{path:'test-results/day-notes-touch-menu.png'});await page.getByRole('menuitem').tap();await expect(page.locator('#track-note-text')).toBeEnabled();await save(page,'Avspasering');await expect(note(page,'2026-05-11')).toHaveText('Avspasering');await cdp.detach();
    }
  }finally{await owner.close();}
});

test('day notes from a delayed month or a previous sign-in cannot replace the current view',async({browser,request})=>{
  const owner=await context(browser),page=await owner.newPage();let release=()=>{};
  try{
    const user=await fresh(request);await signIn(page,user);const api=await apiFor(page);await put(api,'2026-05-17','Private day note');await month(page);await expect(note(page)).toHaveText('Private day note');
    let seen=false;const waiting=new Promise(resolve=>{release=resolve;});await page.route('**/api/v1/day-notes?*',async route=>{const reply=await route.fetch();seen=true;await waiting;await route.fulfill({response:reply}).catch(()=>{});});
    await page.locator('#track-month').fill('2026-06');await expect.poll(()=>seen).toBe(true);
    await page.locator('#account-menu > summary').click();await page.locator('#sign-out').click();await expect(page.locator('#workspace')).toBeHidden();release();await page.unroute('**/api/v1/day-notes?*');
    await signIn(page,await fresh(request));await month(page);await expect(note(page)).toHaveClass(/is-empty/);await expect(page.locator('#track-panel')).not.toContainText('Private day note');
  }finally{release();await owner.close();}
});
