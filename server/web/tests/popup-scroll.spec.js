import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {browserRequest} from './browser-request.js';
import {openAccountMenu,setTheme} from './navigation.js';
import {captureScreenshot} from './screenshots.js';

async function setup(page,request){
  const username='popup-'+randomUUID().slice(0,8),password='Private popup scrolling password';
  const created=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});expect(created.status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  return async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;};
}
async function fits(page,popup){
  await expect.poll(async()=>{const box=await popup.boundingBox(),viewport=page.viewportSize();return box.x>=0&&box.y>=0&&box.x+box.width<=viewport.width+1&&box.y+box.height<=viewport.height+1;}).toBe(true);
}
async function wheel(page,target,delta){
  const box=await target.boundingBox(),viewport=page.viewportSize();
  await page.mouse.move(Math.min(viewport.width-8,Math.max(8,box.x+box.width/2)),Math.min(viewport.height-8,Math.max(8,box.y+box.height/2)));await page.mouse.wheel(0,delta);
  await settled(target);
}
async function settled(target){
  // Native wheel/PageDown animation continues after the input command returns.
  await target.evaluate(node=>new Promise(resolve=>{
    let last='',quiet=0;
    const frame=()=>{
      const positions=[scrollX,scrollY];for(let current=node;current;current=current.parentElement)positions.push(current.scrollTop,current.scrollLeft);
      const next=positions.join(',');quiet=next===last?quiet+1:0;last=next;
      if(quiet>=4)resolve();else requestAnimationFrame(frame);
    };requestAnimationFrame(frame);
  }));
}

test('note popup content and close controls stay reachable beneath long headings on short screens',async({page,request})=>{
  test.setTimeout(90000);
  const api=await setup(page,request),title=('A long note heading that wraps onto several lines on a narrow screen. ').repeat(3).trim();
  await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const body='# '+title+'\n\n'+('A paragraph in the overflowing popup.\n\n').repeat(50)+'Last paragraph in the popup.';
  const entry=await(await api('PUT','/notes/files?path=Notes%2FPopup.md',Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.reload();const origin=page.locator(`[data-recent-note="${entry.id}"]`);await expect(origin).toBeVisible();
  const popup=page.locator('#note-popup-editor'),content=popup.locator('.journal-document-content'),rich=popup.locator('.ProseMirror');
  for(const theme of ['timekeeper','timekeeper-dark']){
    await page.setViewportSize({width:1440,height:900});await setTheme(page,theme);await origin.click();await expect(rich).toContainText('Last paragraph in the popup.');
    expect(await popup.evaluate(node=>node.scrollHeight-node.clientHeight)).toBeLessThanOrEqual(1);
    for(const viewport of [{width:1440,height:300},{width:390,height:320},{width:320,height:240}]){
      await page.setViewportSize(viewport);await fits(page,popup);
      expect(await content.evaluate(node=>node.clientHeight)).toBeGreaterThan(40);
      await popup.evaluate(node=>{node.scrollTop=0;});await content.evaluate(node=>{node.scrollTop=0;});
      await wheel(page,popup.locator('.week-note-heading'),10000);await rich.focus();await rich.press('PageDown');
      await expect.poll(()=>content.evaluate(node=>node.scrollTop)).toBeGreaterThan(0);
      await settled(content);
      await wheel(page,content,10000);await expect(rich.getByText('Last paragraph in the popup.',{exact:true})).toBeInViewport();
      await captureScreenshot(page,{path:`test-results/popup-note-${theme}-${viewport.width}.png`,fullPage:false});
      await content.evaluate(node=>{node.scrollTop=0;});await wheel(page,content,-10000);await expect.poll(()=>popup.evaluate(node=>node.scrollTop)).toBe(0);
    }
    const close=popup.getByRole('button',{name:'Close',exact:true});await close.focus();await expect(close).toBeInViewport();await close.press('Enter');await expect(popup).toBeHidden();await expect(origin).toBeFocused();
  }
  expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(body);
});

test('account popup and ordinary dialogs scroll to their last action and follow viewport and header resizing',async({page,request,browserName})=>{
  test.setTimeout(90000);await setup(page,request);
  const menu=page.locator('.account-menu-items'),toggle=page.getByRole('button',{name:'Account menu',exact:true});
  for(const theme of ['timekeeper','timekeeper-dark']){
    await page.setViewportSize({width:1440,height:900});await setTheme(page,theme);await openAccountMenu(page);
    for(const viewport of [{width:390,height:240},{width:1440,height:180}]){
      await page.setViewportSize(viewport);await fits(page,menu);await menu.evaluate(node=>{node.scrollTop=0;});
      const background=await page.evaluate(()=>scrollY);await wheel(page,menu,250);await expect.poll(()=>menu.evaluate(node=>node.scrollTop)).toBeGreaterThan(0);
      const last=menu.getByRole('button',{name:'About',exact:true});await expect(last).toBeInViewport();
      expect(await last.evaluate(node=>{const box=node.getBoundingClientRect();return node.contains(document.elementFromPoint(box.x+box.width/2,box.y+box.height/2));})).toBe(true);
      await wheel(page,menu,250);expect(await page.evaluate(()=>scrollY)).toBe(background);
      await expect.poll(()=>last.evaluate(node=>{const box=node.getBoundingClientRect();return node.contains(document.elementFromPoint(box.x+box.width/2,box.y+box.height/2));})).toBe(true);
      await captureScreenshot(page,{path:`test-results/popup-account-${theme}-${viewport.width}.png`,fullPage:false});
    }
    await page.setViewportSize({width:390,height:320});await expect.poll(()=>menu.evaluate(node=>node.scrollHeight-node.clientHeight)).toBeLessThanOrEqual(1);
    await page.locator('.app-header').evaluate(node=>{node.style.paddingBlock='6rem';});await fits(page,menu);
    await expect.poll(()=>menu.evaluate(node=>node.scrollHeight-node.clientHeight)).toBeGreaterThan(1);
    await menu.getByRole('button',{name:'Sign out',exact:true}).focus();await page.keyboard.press(browserName==='webkit'&&process.platform==='darwin'?'Alt+Tab':'Tab');const about=menu.getByRole('button',{name:'About',exact:true});await expect(about).toBeFocused();await expect(about).toBeInViewport();
    await about.press('Enter');const dialog=page.locator('#server-info');await expect(dialog).toBeVisible();await fits(page,dialog);await wheel(page,dialog,10000);await expect(page.locator('#about-close')).toBeInViewport();
    await page.keyboard.press('Escape');await expect(dialog).toBeHidden();await expect(toggle).toBeFocused();await page.locator('.app-header').evaluate(node=>{node.style.paddingBlock='';});
    await openAccountMenu(page);await expect.poll(()=>menu.evaluate(node=>node.scrollTop)).toBe(0);await page.keyboard.press('Escape');await expect(menu).toBeHidden();await expect(toggle).toBeFocused();
  }
});
