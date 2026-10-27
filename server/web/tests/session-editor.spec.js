import {captureScreenshot} from './screenshots.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';

async function fixture(page,request) {
  const username='session-editor-'+randomUUID().slice(0,8),password='Independent session editor password';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  const api=browserRequest(page);await api.get('/api/v1/auth/status');
  const login=await api.post('/api/v1/auth/login',{data:{username,password,remember:false}});expect(login.status()).toBe(200);const headers={'X-CSRF-Token':(await login.json()).csrf};
  const call=(method,path,data,extra={})=>api.fetch('/api/v1'+path,{method,data,headers:{...headers,...extra}});
  const client=await(await call('POST','/clients',{name:'Editor deletion client',color:'#176653'})).json();
  const work=await(await call('POST','/work-sessions',{clientId:client.id,summary:'Saved work session',start:'2026-05-17T08:00:00.123456789Z',end:'2026-05-17T09:00:00.987654321Z',labelIds:[]})).json();
  await page.goto('/web/');await expect(page.locator('#status')).toHaveText('Up to date');
  await page.locator('[data-tab=track]').click();
  await page.locator('#track-month').fill('2026-05');await page.locator('[data-track-date="2026-05-17"] button[data-track-daily-client]').click();
  await expect(page.locator('#editor')).toBeVisible();return {call,work,path:'/work-sessions/'+work.id};
}

test('editing a work session offers a cancellable deletion with Undo and preserves the draft',async({page,request})=>{
  const {call,work,path}=await fixture(page,request),editor=page.locator('#editor'),confirm=page.locator('#editor-delete-confirm');
  await page.locator('#field-summary').fill('Unsaved editor draft');
  const writes=[];page.on('request',request=>{if(request.method()==='DELETE')writes.push(request.headers());});
  await page.locator('#editor-delete').click();await expect(confirm).toBeVisible();await expect(confirm).toContainText('Saved work session');
  await expect(confirm.getByRole('button',{name:'Cancel',exact:true})).toBeFocused();
  expect((await new AxeBuilder({page}).include('#editor-delete-confirm').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
  await page.setViewportSize({width:390,height:844});const bounds=await confirm.boundingBox();expect(bounds.y).toBeGreaterThanOrEqual(0);expect(bounds.y+bounds.height).toBeLessThanOrEqual(844);
  await captureScreenshot(page,{path:'test-results/work-session-editor-delete-confirm.png',fullPage:false});
  await confirm.getByRole('button',{name:'Cancel',exact:true}).click();await expect(confirm).toBeHidden();await expect(page.locator('#field-summary')).toHaveValue('Unsaved editor draft');await expect(page.locator('#editor-delete')).toBeFocused();expect(writes).toEqual([]);
  await page.locator('#editor-delete').click();await page.keyboard.press('Escape');await expect(confirm).toBeHidden();await expect(editor).toBeVisible();await expect(page.locator('#field-summary')).toHaveValue('Unsaved editor draft');expect(writes).toEqual([]);
  const before=await call('GET',path);await page.locator('#editor-delete').click();await confirm.getByRole('button',{name:'Delete',exact:true}).click();
  await expect(editor).toBeHidden();await expect(page.locator('#undo-notice')).toContainText('Work session deleted');expect(writes).toHaveLength(1);expect(writes[0]['if-match']).toBe(before.headers().etag);
  expect((await call('GET',path)).status()).toBe(404);
  await page.locator('#undo-notice').getByRole('button',{name:'Undo',exact:true}).click();await expect(page.locator('#undo-notice')).toContainText('change undone');
  const restored=await(await call('GET',path)).json();expect(restored.summary).toBe(work.summary);expect(restored.start).toBe(work.start);expect(restored.end).toBe(work.end);
  await page.locator('#session-new').click();await expect(page.locator('#editor-delete')).toBeHidden();await page.locator('#cancel').click();
  await page.locator('[data-tab=clients]').click();await page.locator('#add-client').click();await expect(page.locator('#editor-delete')).toBeHidden();
});

test('deletion from an editor rejects a newer server version and does not discard the draft',async({page,request})=>{
  const {call,work,path}=await fixture(page,request);await page.locator('#field-summary').fill('Local draft');
  const current=await call('GET',path);expect((await call('PUT',path,{clientId:work.clientId,summary:'Peer changed the session',start:work.start,end:work.end,labelIds:[]},{'If-Match':current.headers().etag})).status()).toBe(200);
  await page.locator('#editor-delete').click();await page.locator('#editor-delete-confirm').getByRole('button',{name:'Delete',exact:true}).click();
  await expect(page.locator('#editor-error')).not.toBeEmpty();await expect(page.locator('#editor')).toBeVisible();await expect(page.locator('#field-summary')).toHaveValue('Local draft');
  expect((await(await call('GET',path)).json()).summary).toBe('Peer changed the session');await page.locator('#cancel').click();
});

test('an unconfirmed deletion cannot be retried or changed into another write',async({page,request})=>{
  const {call,path}=await fixture(page,request);let deletes=0;
  await page.route('**/api/v1'+path,async route=>{if(route.request().method()==='DELETE'){deletes++;expect((await route.fetch()).status()).toBe(204);await route.abort('failed');}else await route.continue();});
  await page.locator('#editor-delete').click();await page.locator('#editor-delete-confirm').getByRole('button',{name:'Delete',exact:true}).click();
  await expect(page.locator('#editor-notice')).toContainText('outcome is unknown');await expect(page.locator('#save')).toBeDisabled();await expect(page.locator('#editor-delete')).toBeDisabled();await expect(page.locator('#field-summary')).toBeDisabled();expect(deletes).toBe(1);
  expect((await call('GET',path)).status()).toBe(404);await page.locator('#cancel').click();await expect(page.locator('#editor')).toBeHidden();
});
