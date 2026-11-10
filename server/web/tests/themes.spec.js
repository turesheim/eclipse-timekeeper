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

test('Dagsverk style guide keeps local fonts, journal typography and Bootstrap controls usable in both modes',async({page,request})=>{
  test.setTimeout(90000);
  const headers={Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},path='/api/v1/day-notes/2026-11-10/content';
  const original=await request.get(path,{headers}),body=await original.json();
  const markdown='# Tanker fra dagen\n\nDet som ble gjort i dag, er mer enn timer og oppgaver. ÆØÅ æøå. **Et godt dagsverk.**\n\n`07:30`';
  expect((await request.put(path,{headers:{...headers,'If-Match':original.headers().etag},data:{body:markdown}})).ok()).toBe(true);
  const external=[];page.on('request',request=>{if(new URL(request.url()).origin!==new URL(process.env.TIMEKEEPER_WEB_URL).origin)external.push(request.url());});
  try {
    await signIn(page);await page.locator('#today-date').fill('2026-11-10');
    const rich=page.locator('#today-note-editor .ProseMirror'),source=page.locator('#today-journal-body');
    for(const id of ['timekeeper','timekeeper-dark']) {
      await setTheme(page,id);await page.locator('[data-tab=today]').click();
      await page.locator('#today-note-editor').getByRole('button',{name:'Formatted',exact:true}).click();
      await expect(rich).toContainText('ÆØÅ æøå');
      const fonts=await page.evaluate(async()=>{
        const inter=await document.fonts.load('400 14px "Inter Variable"','ÆØÅ æøå');
        const serif=await document.fonts.load('600 18px "Source Serif 4 Variable"','ÆØÅ æøå');
        return [...inter,...serif].map(font=>font.status);
      });
      expect(fonts.length).toBeGreaterThanOrEqual(2);expect(fonts.every(status=>status==='loaded')).toBe(true);
      await expect(page.locator('body')).toHaveCSS('font-family',/Inter Variable/);
      await expect(page.locator('#workspace-navigation')).toHaveCSS('background-color',id==='timekeeper'?'rgb(255, 255, 255)':'rgb(28, 48, 41)');
      await expect(rich).toHaveCSS('font-family',/Source Serif 4 Variable/);
      expect(parseFloat(await rich.evaluate(node=>getComputedStyle(node).fontSize))).toBeCloseTo(18,2);
      expect(parseFloat(await rich.evaluate(node=>getComputedStyle(node).lineHeight))).toBeCloseTo(29,1);
      await expect(rich.locator('strong')).toHaveCSS('font-weight','600');
      await expect(rich.locator('code')).toHaveCSS('font-family',/monospace/);
      await expect(page.locator('link[rel=icon]')).toHaveAttribute('href',new RegExp(`dagsverk-symbol${id.endsWith('dark')?'-dark':''}\\.svg$`));
      const symbol=page.locator('.brand-symbol:visible');await expect(symbol).toHaveAccessibleName('Dagsverk');
      expect(parseFloat(await symbol.evaluate(node=>getComputedStyle(node).height))).toBeCloseTo(32,2);
      expect(parseFloat(await symbol.evaluate(node=>getComputedStyle(node).paddingTop))).toBeCloseTo(8,2);
      await expect(page.locator('.brand-logo:visible')).toHaveCount(0);
      for(const width of [1440,390]) {
        await page.setViewportSize({width,height:1000});await audit(page);
        expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
        await captureScreenshot(page,{path:`test-results/style-guide-${id}-${width}.png`});
      }
      await page.locator('#today-note-editor').getByRole('button',{name:'Markdown source',exact:true}).click();
      await expect(source).toHaveValue(markdown);await expect(source).toHaveCSS('font-family',/monospace/);
      await page.getByRole('button',{name:'Record work session',exact:true}).click();
      const save=page.locator('#save'),field=page.getByLabel('Summary',{exact:true});
      expect(Math.abs((await save.boundingBox()).height-34)).toBeLessThanOrEqual(1);
      await expect(field).toHaveCSS('border-top-color','rgb(123, 145, 135)');
      await field.focus();await expect(field).toHaveCSS('border-top-color',id==='timekeeper'?'rgb(23, 102, 83)':'rgb(121, 208, 173)');
      expect(await field.evaluate(node=>getComputedStyle(node).boxShadow)).not.toBe('none');
      await page.locator('#cancel').click();
    }
    // Account overrides still take precedence over theme defaults and scale the symbol.
    await page.evaluate(()=>window.timekeeperTypography.receive({data:{ownerId:window.timekeeperTypography.profile().data.ownerId,version:window.timekeeperTypography.profile().data.version+1,typographyCss:':root { --font-base: 18px; --font-markdown-body: 1.5rem; }'}}));
    await expect(rich).toHaveCSS('font-size','27px');
    await page.reload();await expect(page.locator('html')).toHaveCSS('font-size','14px');
    await setTheme(page,'nord');await expect(page.locator('body')).not.toHaveCSS('font-family',/Inter Variable/);
    await expect(page.locator('.brand-logo-dark')).toBeVisible();await expect(page.locator('.brand-symbol:visible')).toHaveCount(0);
    await expect(page.locator('link[rel=icon]')).toHaveAttribute('href',/favicon\.svg$/);
    expect(external).toEqual([]);
  } finally {
    const current=await request.get(path,{headers});
    expect((await request.put(path,{headers:{...headers,'If-Match':current.headers().etag},data:{body:body.body}})).ok()).toBe(true);
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
