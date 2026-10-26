import {captureScreenshot} from './screenshots.js';
import {setTheme,openThemeSettings} from './navigation.js';
import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {randomUUID} from 'node:crypto';
import {openAccountMenu} from './navigation.js';

async function signIn(page) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_TOKEN);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
}
async function audit(page) {
  const result=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();
  expect(result.violations.map(({id,nodes})=>({id,nodes:nodes.map(({target,any})=>({target,checks:any.map(({message,data})=>({message,data}))}))}))).toEqual([]);
}
test('themes cover tracking, records, editors and account settings with accessible contrast',async({page,request,browserName})=>{
  test.setTimeout(150000);
  const records=[],headers={Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`};
  async function api(method,path,data,extra={}) {const response=await request.fetch('/api/v1'+path,{method,data,headers:{...headers,...extra}});expect(response.ok(),method+' '+path+': '+response.status()).toBe(true);return response;}
  async function create(collection,data) {const value=await (await api('POST','/'+collection,data)).json();records.unshift([collection,value.id]);return value;}
  const external=[],violations=[];page.on('request',request=>{if(new URL(request.url()).origin!==new URL(process.env.TIMEKEEPER_WEB_URL).origin)external.push(request.url());});
  await page.addInitScript(()=>{document.addEventListener('securitypolicyviolation',event=>{window.themePolicyViolations??=[];window.themePolicyViolations.push({directive:event.violatedDirective,blocked:event.blockedURI});});});
  try {
    const client=await create('clients',{name:'Theme studio '+randomUUID().slice(0,6),color:'#6750a4'});
    const label=await create('labels',{name:'Theme preview label',color:'#6750a4'});
    const project=await create('projects',{name:'Theme preview',clientId:client.id,icon:'code'}),task=await create('tasks',{projectId:project.id,parentTaskId:null,url:'https://example.invalid/theme-preview',summary:'Review palette'});
    await create('work-sessions',{clientId:client.id,projectId:project.id,summary:'Palette review',start:'2025-10-20T06:00:00Z',end:'2025-10-20T14:00:00Z'});
    await create('activities',{taskId:task.id,summary:'Review contrast',start:'2025-10-20T06:00:00Z',end:'2025-10-20T07:00:00Z',labelIds:[]});
    await signIn(page);await expect(page.locator('html')).toHaveAttribute('data-theme','timekeeper');
    await page.locator('[data-tab=week]').click();await page.locator('#week-date').fill('2025-10-20');await expect(page.locator('#zone-caption')).toContainText('2025-10-20');
    await page.locator('[data-tab=track]').click();await page.locator('#track-month').fill('2025-10');
    for(const id of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']) {
      await page.setViewportSize({width:1440,height:1000});await setTheme(page,id);
      await expect(page.locator('html')).toHaveAttribute('data-theme',id);await audit(page);
      await page.locator('[data-tab=track]').click();await captureScreenshot(page,{path:`test-results/theme-${id}-desktop.png`,fullPage:false});
      for(const view of ['track','week','calendar','activities','clients','projects']) {
        await page.locator(`[data-tab=${view}]`).click();
        if(view==='activities') {
          const activity=page.locator('#activities tr[data-kind=activity]').first();
          if(await activity.getAttribute('aria-selected')!=='true')await activity.locator('.activity-title').click();
        }
        await audit(page);
      }
      await page.getByRole('button',{name:'Record work session',exact:true}).click();await expect(page.locator('#editor-labels')).toHaveCount(0);await audit(page);await page.locator('#cancel').click();
      await page.locator('[data-tab=projects]').click();await page.getByRole('button',{name:'New project',exact:true}).click();await page.getByRole('radio',{name:'Code',exact:true}).check();await audit(page);await page.locator('#cancel').click();
      await openAccountMenu(page);await page.getByRole('link',{name:'My Account',exact:true}).click();await expect(page.locator('#account-content')).toHaveAttribute('aria-busy','false');await audit(page);await page.locator('#account-navigation').getByRole('link',{name:'Labels',exact:true}).click();await expect(page.locator('#labels-panel')).toBeVisible();await audit(page);
      await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await page.locator('[data-tab=track]').click();
      await page.setViewportSize({width:390,height:844});await audit(page);await captureScreenshot(page,{path:`test-results/theme-${id}-mobile.png`,fullPage:false});
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    }
    violations.push(...await page.evaluate(()=>window.themePolicyViolations || []));expect(external).toEqual([]);
    expect(browserName==='webkit' ? violations.filter(value=>value.blocked!=='inline'):violations).toEqual([]);
  } finally {
    for(const [collection,id] of records) {const response=await api('GET','/'+collection+'/'+id);await api('DELETE','/'+collection+'/'+id,undefined,{'If-Match':response.headers().etag});}
  }
});

test('theme choice persists before rendering, follows other tabs and tolerates unavailable browser storage',async({page,context})=>{
  await signIn(page);await setTheme(page,'solarized-dark');
  await page.reload();await expect(page.locator('html')).toHaveAttribute('data-theme','solarized-dark');
  await expect(page.locator('html')).toHaveAttribute('data-bs-theme','dark');await openThemeSettings(page);await expect(page.getByLabel('Theme',{exact:true})).toHaveValue('solarized-dark');
  const other=await context.newPage();await other.goto('/admin/');await expect(other.locator('html')).toHaveAttribute('data-theme','solarized-dark');
  await page.getByLabel('Theme',{exact:true}).selectOption('nord');await expect(other.locator('html')).toHaveAttribute('data-theme','nord');
  await page.evaluate(()=>localStorage.removeItem('timekeeper.theme'));await expect(other.locator('html')).toHaveAttribute('data-theme','timekeeper');await other.close();
  await page.evaluate(()=>localStorage.setItem('timekeeper.theme','invalid-theme'));await page.reload();await expect(page.locator('html')).toHaveAttribute('data-theme','timekeeper');
  await page.addInitScript(()=>{Storage.prototype.getItem=()=>{throw new DOMException('Storage unavailable','SecurityError');};Storage.prototype.setItem=()=>{throw new DOMException('Storage unavailable','SecurityError');};});
  await page.reload();await expect(page.locator('html')).toHaveAttribute('data-theme','timekeeper');await setTheme(page,'dracula');
  await expect(page.locator('html')).toHaveAttribute('data-theme','dracula');await expect(page.locator('#status')).toHaveText('Up to date');
});
