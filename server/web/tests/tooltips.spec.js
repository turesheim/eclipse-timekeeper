import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {randomUUID} from 'node:crypto';
import {tooltipFor} from './tooltips.js';
import {setTheme,openAccountMenu} from './navigation.js';
import {captureScreenshot} from './screenshots.js';

const token=process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request,method,path,data,headers={}) {
  const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
  expect(response.ok(),method+' '+path+' '+response.status()).toBe(true);return response;
}
async function signIn(page){
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
}
test('one rich tooltip covers each composite, preserves literal data and supports hover, keyboard and every theme',async({page,request,browserName})=>{
  const records=[],original=await(await api(request,'GET','/me')).json(),errors=[];page.on('pageerror',error=>errors.push(error.message));
  const create=async(collection,data)=>{const value=await(await api(request,'POST','/'+collection,data)).json();records.unshift([collection,value.id]);return value;};
  try {
    const client=await create('clients',{name:'Tooltip '+randomUUID().slice(0,8)+' <img src=x onerror=alert(1)>',color:'#176653'});
    const project=await create('projects',{clientId:client.id,name:'Review <b>literal project</b>',icon:'code'});
    for(const [summary,start,end] of [['First <script>unsafe()</script>','08:00','09:00'],['Second session','10:00','11:30']])await create('work-sessions',{clientId:client.id,projectId:project.id,summary,start:`2025-10-20T${start}:00Z`,end:`2025-10-20T${end}:00Z`});
    await signIn(page);await page.locator('#today-date').fill('2025-10-20');
    const card=page.locator(`[data-today-client="${client.id}"]`),tip=await tooltipFor(card);
    await expect(tip.locator('.tooltip-session')).toHaveCount(2);await expect(tip).toContainText('2 work sessions');await expect(tip).toContainText('2h 30m');
    await expect(tip).toContainText(client.name);await expect(tip).toContainText(project.name);await expect(tip).toContainText('<script>unsafe()</script>');
    await expect(tip.locator('img,script,b')).toHaveCount(0);await expect(tip.locator('.bi-code')).toHaveCount(2);
    await expect(card.locator('[title],[data-tk-tooltip]')).toHaveCount(0);
    const id=await tip.getAttribute('id');await card.locator('.calendar-summary-time').hover();await expect(tip).toHaveAttribute('id',id);
    const bounds=await tip.boundingBox();await page.mouse.move(bounds.x+bounds.width/2,bounds.y+bounds.height/2);await expect(tip).toBeVisible();await page.keyboard.press('Escape');await expect(page.getByRole('tooltip')).toHaveCount(0);
    const option=browserName==='webkit'&&process.platform==='darwin'?'Alt+':'';
    await card.focus();await page.keyboard.press(option+'Tab');await page.keyboard.press(option+'Shift+Tab');await expect(card).toBeFocused();
    await expect(page.getByRole('tooltip')).toBeVisible();await expect(card).toHaveAttribute('aria-describedby',await page.getByRole('tooltip').getAttribute('id'));
    await page.keyboard.press('Escape');await expect(card).not.toHaveAttribute('aria-describedby');
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
      await setTheme(page,theme);await tooltipFor(card);
      const audit=await new AxeBuilder({page}).include('.timekeeper-tooltip').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
      await captureScreenshot(page,{path:`test-results/tooltip-${theme}.png`,fullPage:false});
    }
    for(const width of [320,390]){await page.setViewportSize({width,height:844});const tip=await tooltipFor(card),box=await tip.boundingBox();expect(box.x).toBeGreaterThanOrEqual(0);expect(box.x+box.width).toBeLessThanOrEqual(width);expect(box.height).toBeLessThan(844);}
    await page.locator('[data-tab=track]').click();await page.locator('#track-month').fill('2025-10');
    const monthly=page.locator(`[data-track-date="2025-10-20"] [data-track-daily-client="${client.id}"]`);
    await expect((await tooltipFor(monthly)).locator('.tooltip-session')).toHaveCount(2);await expect(monthly.locator('[title],[data-tk-tooltip]')).toHaveCount(0);
    await page.locator('[data-tab=clients]').click();await expect(page.getByRole('tooltip')).toHaveCount(0);
    expect(errors).toEqual([]);
  } finally {
    for(const [collection,id] of records)await api(request,'DELETE','/'+collection+'/'+id,undefined,{'If-Match':(await api(request,'GET','/'+collection+'/'+id)).headers().etag});
    await api(request,'PUT','/me',{zoneId:original.zoneId,theme:original.theme},{'If-Match':(await api(request,'GET','/me')).headers().etag});
  }
});

test('shared hints update dynamically and remain visible inside native dialogs, then clear on close and logout',async({page})=>{
  await signIn(page);
  const next=page.locator('#today-next');await expect(await tooltipFor(next)).toContainText('Next day');
  // UI modules update native title properties after rendering. Their values
  // must remain literal text, and long help must scroll without stealing clicks.
  await next.evaluate(node=>{node.title='Updated <em>hint</em>\n'+('A detailed explanation\n').repeat(80);});
  const updated=await tooltipFor(next);await expect(updated).toContainText('Updated <em>hint</em>');await expect(updated.locator('em')).toHaveCount(0);
  const box=await updated.boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.wheel(0,240);
  await expect.poll(()=>updated.locator('.tooltip-inner').evaluate(node=>node.scrollTop)).toBeGreaterThan(0);
  await next.evaluate(node=>{node.title='Next day';});await expect(updated).toContainText('Next day');
  await page.locator('#today-month-grid [data-week]').first().click();const dialog=page.locator('#today-week-note-editor');await expect(dialog).toBeVisible();
  const close=dialog.getByRole('button',{name:'Close',exact:true}),tip=await tooltipFor(close);
  await expect(tip).toContainText('Close');expect(await tip.evaluate(node=>!!node.closest('dialog[open]'))).toBe(true);
  await page.keyboard.press('Escape');await expect(tip).toHaveCount(0);await expect(dialog).toBeVisible();
  await dialog.getByRole('button',{name:'Markdown source',exact:true}).click();
  await page.locator('#weekly-journal-body').fill('[Authored link](https://example.invalid "Authored title")\n\nText');
  await dialog.getByRole('button',{name:'Formatted',exact:true}).click();
  const rich=page.locator('#weekly-journal-formatted .ProseMirror'),authored=rich.locator('a');
  await expect(authored).toHaveAttribute('title','Authored title');await expect(authored).not.toHaveAttribute('data-tk-tooltip');
  await rich.press('ControlOrMeta+End');await rich.press('!');
  await dialog.getByRole('button',{name:'Markdown source',exact:true}).click();await expect(page.locator('#weekly-journal-body')).toHaveValue(/"Authored title"/);
  page.on('dialog',prompt=>prompt.accept());
  await close.click();await expect(dialog).not.toBeVisible();await expect(page.getByRole('tooltip')).toHaveCount(0);
  await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login')).toBeVisible();await expect(page.getByRole('tooltip')).toHaveCount(0);
});
