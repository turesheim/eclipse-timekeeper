import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {captureScreenshot} from './screenshots.js';
import {resolvedLength} from './style-metrics.js';

const date='2026-11-16',image=await readFile(new URL('./fixtures/banner.png',import.meta.url));
async function setup(page,request){
  const username='planned-'+randomUUID().slice(0,8),password='Synthetic planned task password';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:'Bearer '+process.env.TIMEKEEPER_WEB_TOKEN},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...(method==='POST'?{'Idempotency-Key':randomUUID()}:{}),...headers}});expect(reply.ok(),method+' '+path+': '+reply.status()).toBe(true);return reply;};
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'UTC',locale:'en-GB'},{'If-Match':profile.headers().etag});
  const owner=await(await api('POST','/clients',{name:'Planning client',color:'#285ca8'})).json(),project=await(await api('POST','/projects',{name:'Website',clientId:owner.id,icon:null})).json();
  await api('GET','/notes');await api('POST','/notes/folders',{path:'Images'});const asset=await(await api('PUT','/notes/files?path=Images%2Foverview.png',image,{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const create=async(summary,format='TEXT',content='')=>{
    const task=await(await api('POST','/tasks',{summary,clientId:owner.id,projectId:project.id,parentTaskId:null,url:'https://example.invalid/work',scheduledDate:date})).json();
    if(content){const current=await api('GET','/tasks/'+task.id);await api('PUT','/tasks/'+task.id+'/body',{format,content},{'If-Match':current.headers().etag});}
    return task;
  };
  return {api,asset,create};
}
async function today(page){await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('#today-date').fill(date);}
const card=(page,id)=>page.locator('[data-today-task="'+id+'"]');

for(const format of ['TEXT','HTML'])test('planned task details show safe links and private images from '+format,async({page,request})=>{
  const {api,create}=await setup(page,request),body=format==='TEXT'?'[Specification](https://example.invalid/spec)\n\n![Overview](Images/overview.png)\n\n![Remote](https://example.invalid/tracker.png)':'<p><a href="https://example.invalid/spec">Specification</a></p><img src="Images/overview.png" alt="Overview" onerror="window.taskImageUnsafe=true"><img src="https://example.invalid/tracker.png" alt="Remote"><script>window.taskImageUnsafe=true</script>';
  const task=await create('Review website',format,body);let external=0;page.on('request',request=>{if(request.url().startsWith('https://example.invalid/'))external++;});await today(page);
  const action=card(page,task.id);await expect(action).toContainText('Website');await expect(action).toHaveCSS('border-left-color','rgb(40, 92, 168)');await action.focus();await action.press('Enter');
  const popup=page.locator('#task-details');await expect(popup).toBeVisible();await expect(page.locator('#today-panel')).toBeVisible();await expect(page.locator('#tasks-panel')).toBeHidden();await expect(popup).toContainText('Planning client');
  await expect(popup.getByRole('link',{name:'Specification',exact:true})).toHaveAttribute('rel','noopener noreferrer');await expect(popup.getByRole('link',{name:'Open task URL',exact:true})).toHaveAttribute('href','https://example.invalid/work');
  const preview=popup.getByRole('img',{name:'Overview',exact:true});await expect(preview).toHaveAttribute('src',/^blob:/);await preview.scrollIntoViewIfNeeded();await expect.poll(()=>preview.evaluate(image=>image.naturalWidth)).toBeGreaterThan(0);await expect(popup.locator('script,img[src^="https:"]')).toHaveCount(0);expect(await page.evaluate(()=>window.taskImageUnsafe)).toBeUndefined();expect(external).toBe(0);
  expect((await(await api('GET','/tasks/'+task.id)).json()).body).toEqual({format,content:body});
  expect((await new AxeBuilder({page}).include('#task-details').analyze()).violations).toEqual([]);
  // A delayed Close hint consumes Escape before the containing native dialog.
  // Wait for it explicitly rather than racing accessibility-analysis duration.
  await popup.getByRole('button',{name:'Close',exact:true}).focus();const hint=page.getByRole('tooltip');await expect(hint).toBeVisible();await page.keyboard.press('Escape');await expect(hint).toBeHidden();await expect(popup).toBeVisible();
  await page.keyboard.press('Escape');await expect(popup).toBeHidden();await expect(action).toBeFocused();
  await page.locator('[data-tab=tasks]').click();await page.locator('#tasks-list').getByRole('button',{name:'Open task Review website',exact:true}).click();await expect(popup.getByRole('img',{name:'Overview',exact:true})).toHaveAttribute('src',/^blob:/);
});

test('planned cards match summary typography, retain keyboard focus and fit narrow themes',async({page,request})=>{
  const {create}=await setup(page,request),task=await create('A long planned review title that must fit the sidebar');await today(page);
  const action=card(page,task.id),heading=page.locator('#today-sidebar .today-summary h3').first();
  for(const width of [1440,390,320]){
    await page.setViewportSize({width,height:1000});
    for(const theme of ['timekeeper','timekeeper-dark']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);
      await expect(action).toHaveCSS('font-size',await resolvedLength(page,'--calendar-card-font-size'));await expect(action.locator('strong')).toHaveCSS('font-size',await action.evaluate(node=>getComputedStyle(node).fontSize));await expect(action).toHaveCSS('border-left-color','rgb(40, 92, 168)');await expect(heading).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      await action.click();const popup=page.locator('#task-details');await expect(popup).toBeVisible();expect((await new AxeBuilder({page}).include('#task-details').analyze()).violations).toEqual([]);await popup.getByRole('button',{name:'Close',exact:true}).click();await expect(action).toBeFocused();await captureScreenshot(page,{path:`test-results/planned-tasks-${theme}-${width}.png`});
    }
  }
  await page.setViewportSize({width:1440,height:1000});await action.hover();await page.evaluate(()=>window.plannedAction=document.querySelector('[data-today-task]'));
  await page.mouse.down();await page.evaluate(()=>document.querySelector('#refresh').click());await expect(page.locator('#status')).toHaveText('Up to date');await page.mouse.up();
  const popup=page.locator('#task-details');await expect(popup).toBeVisible();await popup.getByRole('button',{name:'Close',exact:true}).click();await expect(action).toBeFocused();expect(await action.evaluate(node=>node===window.plannedAction)).toBe(true);
});

test('opening planned task details preserves an editable daily draft',async({page,request})=>{
  const {api,create}=await setup(page,request),task=await create('Review without leaving Today');await today(page);
  const journal=page.locator('#today-note-editor');await journal.getByRole('button',{name:'Markdown source',exact:true}).click();const draft=page.locator('#today-journal-body');await draft.fill('# Keep this daily draft\n\nMy work stays here.');
  await card(page,task.id).click();await expect(page.locator('#task-details')).toBeVisible();await expect(page.locator('#today-panel')).toBeVisible();await page.keyboard.press('Escape');await expect(draft).toHaveValue('# Keep this daily draft\n\nMy work stays here.');
  await expect.poll(async()=>(await(await api('GET','/day-entries/'+date)).json()).body).toBe('# Keep this daily draft\n\nMy work stays here.');
});

test('read-only planned details allow private images while write actions stay disabled',async({page,request,browser})=>{
  const {api,create}=await setup(page,request),task=await create('Read-only review','TEXT','![Overview](Images/overview.png)'),token=await(await api('POST','/auth/tokens',{name:'Planned viewer',permissions:['read'],expiresAt:null})).json();
  const viewer=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true}),read=await viewer.newPage();
  try{
    await read.goto('/web/');await read.locator('#auth-mode').selectOption('token');await read.locator('#token').fill(token.token);await read.locator('#login-form button[type=submit]').click();await expect(read.locator('#status')).toHaveText('Up to date');await read.locator('#today-date').fill(date);await card(read,task.id).click();
    const popup=read.locator('#task-details');await expect(popup.getByRole('img',{name:'Overview',exact:true})).toHaveAttribute('src',/^blob:/);for(const name of ['Edit task','Delete task','Start tracking'])await expect(popup.getByRole('button',{name,exact:true})).toBeDisabled();
  }finally{await viewer.close();}
});

test('closing or signing out cancels a late private task image',async({page,request})=>{
  const {api,asset,create}=await setup(page,request),task=await create('Delayed private review','TEXT','![Private overview](Images/overview.png)');let release,arrived;
  const gate=new Promise(resolve=>release=resolve),started=new Promise(resolve=>arrived=resolve);
  await page.route('**/api/v1/notes/entries/'+asset.id+'/content',async route=>{const response=await route.fetch();arrived();await gate;await route.fulfill({response}).catch(()=>{});});
  try{
    await today(page);await card(page,task.id).click();await started;
    // Model session revocation in another tab, then the next background refresh.
    await api('POST','/auth/logout');await page.evaluate(()=>document.querySelector('#refresh').click());await expect(page.locator('#workspace')).toBeHidden();release();await page.unrouteAll({behavior:'wait'});await expect(page.locator('#task-details')).toBeHidden();await expect(page.locator('#task-details img')).toHaveCount(0);await expect(page.locator('#task-details-title')).toBeEmpty();
  }finally{release();}
});
