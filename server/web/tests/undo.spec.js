import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {workSessionAction} from './workweek-session-actions.js';
const token=process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request,method,path,data,headers={}) {
  const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
  expect(response.ok(),`${method} ${path}: ${response.status()}`).toBeTruthy();return response;
}
test('work-session notifications fade and pause for interaction or an uncertain Undo',async({page})=>{
  await page.goto('/web/');await page.clock.install({time:new Date('2030-01-01T00:00:00Z')});await page.clock.pauseAt(new Date('2030-01-01T00:00:01Z'));
  await page.evaluate(async()=>{
    const {UndoNotice}=await import('/web/undo.js');
    const root=document.querySelector('#undo-notice').cloneNode(true);root.id='notice-fixture';document.body.append(root);
    const api={request:async()=>{
      if(window.undoPending)await new Promise(resolve=>window.finishUndo=resolve);
      if(window.undoUncertain)throw {uncertain:true};
    }};
    window.testNotice=new UndoNotice(root,{available:()=>true,changed:async()=>{},unauthorized:()=>{}});
    window.offerNotice=()=>window.testNotice.offer({id:'test',seconds:600},api);
    window.offerNotice();
  });
  const notice=page.locator('#notice-fixture');
  await expect(notice).toContainText('Work session updated');await page.clock.runFor(10000);await expect(notice).toHaveClass(/is-fading/);
  await page.clock.runFor(500);await expect(notice).toBeHidden();
  await page.evaluate(()=>window.offerNotice());await notice.hover();await page.clock.runFor(15000);await expect(notice).toBeVisible();await expect(notice).not.toHaveClass(/is-fading/);
  await page.mouse.move(0,0);await notice.getByRole('button',{name:'Undo',exact:true}).focus();await page.clock.runFor(15000);await expect(notice).toBeVisible();
  await page.evaluate(()=>document.activeElement.blur());await page.clock.runFor(11000);await expect(notice).toBeHidden();
  await page.evaluate(()=>{window.undoPending=true;window.offerNotice();});await notice.getByRole('button',{name:'Undo',exact:true}).click();await page.mouse.move(0,0);await page.evaluate(()=>document.activeElement.blur());
  await page.clock.runFor(20000);await expect(notice).toContainText('Undoing work-session change');await expect(notice.getByRole('button',{name:'Undo',exact:true})).toBeDisabled();
  await page.evaluate(()=>{window.undoPending=false;window.finishUndo();});await expect(notice).toContainText('change undone');await page.clock.runFor(6000);await expect(notice).toBeHidden();
  await page.evaluate(()=>{window.undoUncertain=true;window.offerNotice();});await notice.getByRole('button',{name:'Undo',exact:true}).click();await page.mouse.move(0,0);await page.evaluate(()=>document.activeElement.blur());
  await expect(notice.getByRole('button',{name:'Retry undo',exact:true})).toBeVisible();await page.clock.runFor(60000);await expect(notice).toBeVisible();
  await page.evaluate(()=>{window.undoUncertain=false;});await notice.getByRole('button',{name:'Retry undo',exact:true}).click();await page.mouse.move(0,0);await page.evaluate(()=>document.activeElement.blur());
  await expect(notice).toContainText('change undone');await page.clock.runFor(6000);await expect(notice).toBeHidden();
  await page.emulateMedia({reducedMotion:'reduce'});await page.evaluate(()=>window.offerNotice());await expect(notice).toHaveCSS('transition-duration','0s');
});

test('Undo restores inline edits and deletions exactly; lost replies retry without repeating a write',async({page,request})=>{
  const profile=await api(request,'GET','/me'),previous=await profile.json();await api(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag});
  const client=await(await api(request,'POST','/clients',{name:'Undo '+randomUUID(),color:'#6750a4'})).json();
  const original=await(await api(request,'POST','/work-sessions',{clientId:client.id,summary:'Undo original',start:'2025-10-20T06:00:00.123456789Z',end:'2025-10-20T14:00:00.987654321Z'})).json();
  const path='/work-sessions/'+original.id;
  try {
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(token);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await page.locator('[data-tab=week]').click();await page.getByLabel('Week containing',{exact:true}).fill('2025-10-20');
    const end=page.locator(`#week-sessions [data-work-session-id="${original.id}"] [data-endpoint=end]`),notice=page.locator('#undo-notice');
    await end.fill('17:00');await end.press('Enter');await expect(notice).toContainText('Work session updated');await expect(end).toHaveValue('17:00');
    expect((await new AxeBuilder({page}).include('#undo-notice').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
    await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.screenshot({path:'test-results/work-session-undo-mobile.png',fullPage:true});
    await notice.getByRole('button',{name:'Undo',exact:true}).click();await expect(notice).toContainText('change undone');await expect(end).toHaveValue('16:00');
    let saved=await(await api(request,'GET',path)).json();expect(saved.start).toBe(original.start);expect(saved.end).toBe(original.end);expect(saved.version).toBe(2);
    await workSessionAction(page,original.id,'Delete');await page.locator('#save').click();await expect(page.locator('#editor')).not.toBeVisible();await expect(notice).toContainText('Work session deleted');
    let first=true,keys=[];await page.route('**/api/v1/undo/*',async route=>{keys.push(route.request().headers()['idempotency-key']);if(first){first=false;expect((await route.fetch()).status()).toBe(200);await route.abort('failed');}else await route.continue();});
    await notice.getByRole('button',{name:'Undo',exact:true}).click();await expect(notice.getByRole('button',{name:'Retry undo',exact:true})).toBeVisible();
    await notice.getByRole('button',{name:'Retry undo',exact:true}).click();await expect(notice).toContainText('change undone');
    expect(keys).toHaveLength(2);expect(keys[0]).toBe(keys[1]);saved=await(await api(request,'GET',path)).json();expect(saved.id).toBe(original.id);expect(saved.start).toBe(original.start);expect(saved.end).toBe(original.end);expect(saved.version).toBe(3);
    await page.unroute('**/api/v1/undo/*');
    await end.fill('17:00');await end.press('Enter');await expect(notice).toContainText('Work session updated');
    const current=await api(request,'GET',path);await api(request,'PUT',path,{clientId:client.id,summary:'Peer change',start:original.start,end:original.end},{'If-Match':current.headers().etag});
    await notice.getByRole('button',{name:'Undo',exact:true}).click();await expect(notice).toContainText('references changed');expect((await(await api(request,'GET',path)).json()).summary).toBe('Peer change');
  } finally {
    const session=await api(request,'GET',path);await api(request,'DELETE',path,undefined,{'If-Match':session.headers().etag});
    const current=await api(request,'GET','/clients/'+client.id);await api(request,'DELETE','/clients/'+client.id,undefined,{'If-Match':current.headers().etag});
    const profile=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:previous.zoneId},{'If-Match':profile.headers().etag});
  }
});
