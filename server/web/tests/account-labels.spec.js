import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {openLabelSettings,openAccountMenu} from './navigation.js';
import {captureScreenshot} from './screenshots.js';
const token=process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request,method,path,data,etag){const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${token}`,...(etag?{'If-Match':etag}:{})}});expect(response.ok()).toBe(true);return response;}
async function login(page,credential=token){await page.goto('/web/#account/labels');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(credential);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#labels-panel')).toBeVisible();}
async function save(page){await page.locator('#save').click();await expect(page.locator('#editor')).not.toBeVisible();}

test('account labels support deep links, Activity labels, guarded edits and deletion without a workspace tab',async({page,request})=>{
  const name='Account label '+randomUUID().slice(0,8);let label,activity;
  try {
    await login(page);await expect(page.locator('#workspace-navigation')).not.toBeVisible();await expect(page.locator('[data-tab=labels]')).toHaveCount(0);
    await page.getByRole('button',{name:'New label',exact:true}).click();await page.getByLabel('Name',{exact:true}).fill(name);await page.locator('#fields [name=color]').fill('#6750a4');await save(page);
    label=(await(await api(request,'GET','/labels')).json()).items.find(value=>value.name===name);expect(label).toBeTruthy();
    await page.reload();await expect(page.locator('#labels-panel')).toBeVisible();await expect(page.locator('#labels tr').filter({hasText:name})).toHaveCount(1);
    await page.locator('#account-navigation').getByRole('link',{name:'Profile',exact:true}).click();await expect(page.locator('#labels-panel')).not.toBeVisible();
    await page.locator('#account-navigation').getByRole('link',{name:'Labels',exact:true}).click();await expect(page.locator('#labels-panel')).toHaveCount(1);await expect(page.locator('#labels-panel')).toBeVisible();
    const row=page.locator('#labels tr').filter({hasText:name});await row.getByRole('button',{name:'Edit',exact:true}).click();await page.getByLabel('Name',{exact:true}).fill(name+' draft');
    await page.evaluate(()=>location.hash='#account/profile');await expect(page).toHaveURL(/#account\/labels$/);await expect(page.getByLabel('Name',{exact:true})).toHaveValue(name+' draft');
    const current=await api(request,'GET','/labels/'+label.id);await api(request,'PUT','/labels/'+label.id,{name:name+' peer',color:'#176653'},current.headers().etag);
    await page.locator('#save').click();await expect(page.locator('#editor-error')).toContainText(/changed|conflict/);await expect(page.getByLabel('Name',{exact:true})).toHaveValue(name+' draft');await page.locator('#cancel').click();
    await expect(page.locator('#labels tr').filter({hasText:name+' peer'})).toHaveCount(1);await page.locator('#labels tr').filter({hasText:name+' peer'}).getByRole('button',{name:'Edit',exact:true}).click();await page.getByLabel('Name',{exact:true}).fill(name+' renamed');await save(page);
    activity=await(await api(request,'POST','/activities',{taskId:null,projectId:null,summary:name+' activity',start:'2025-10-20T08:00:00Z',end:'2025-10-20T09:00:00Z',labelIds:[label.id]})).json();
    await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await page.locator('[data-tab=activities]').click();await page.locator('#week-date').fill('2025-10-20');
    const entry=page.locator('#activities tr[data-kind=activity]').filter({hasText:activity.summary});await expect(entry).toContainText(name+' renamed');await entry.getByRole('button',{name:'Edit',exact:true}).click();await expect(page.getByLabel(name+' renamed',{exact:true})).toBeChecked();await page.locator('#cancel').click();
    await openLabelSettings(page);await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#account-page').analyze()).violations).toEqual([]);await captureScreenshot(page,{path:'test-results/account-labels-mobile.png',fullPage:true});
    await page.locator('#labels tr').filter({hasText:name+' renamed'}).getByRole('button',{name:'Delete',exact:true}).click();await page.locator('#save').click();await expect(page.locator('#editor-error')).toContainText(/conflict/);await page.locator('#cancel').click();
    const recorded=await api(request,'GET','/activities/'+activity.id);await api(request,'DELETE','/activities/'+activity.id,undefined,recorded.headers().etag);activity=null;
    await page.locator('#labels tr').filter({hasText:name+' renamed'}).getByRole('button',{name:'Delete',exact:true}).click();await save(page);await expect(page.locator('#labels tr').filter({hasText:name})).toHaveCount(0);label=null;
  } finally {
    if(activity){const value=await api(request,'GET','/activities/'+activity.id);await api(request,'DELETE','/activities/'+activity.id,undefined,value.headers().etag);}
    if(label){const value=await api(request,'GET','/labels/'+label.id);await api(request,'DELETE','/labels/'+label.id,undefined,value.headers().etag);}
  }
});

test('account labels stay read-only for a read-only session and clear on sign-out',async({page})=>{
  await login(page,process.env.TIMEKEEPER_WEB_READ_TOKEN);await expect(page.getByRole('button',{name:'New label',exact:true})).toBeDisabled();
  for(const button of await page.locator('#labels button').all())await expect(button).toBeDisabled();
  await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login')).toBeVisible();await expect(page.locator('#labels-panel')).not.toBeVisible();await expect(page.locator('#labels tr')).toHaveCount(0);
  await login(page);await expect(page.locator('#labels-panel')).toHaveCount(1);await expect(page.getByRole('button',{name:'New label',exact:true})).toBeEnabled();
});
