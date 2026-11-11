import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {randomUUID} from 'node:crypto';
import {captureScreenshot} from './screenshots.js';
import {navigateView,setTheme} from './navigation.js';

async function api(request,method,path,data,extra={}){
  const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`,...extra}});
  expect(response.ok(),method+' '+path+': '+response.status()).toBe(true);return response;
}
async function signIn(page,token=process.env.TIMEKEEPER_WEB_TOKEN){
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveAttribute('data-state','ready');
}

test('Clients and Projects are independent My Account pages with keyboard navigation and read-only controls',async({page,browser})=>{
  await signIn(page);
  await expect(page.locator('#workspace-navigation [data-tab=clients],#workspace-navigation [data-tab=projects]')).toHaveCount(0);
  for(const part of ['clients','projects']){
    await navigateView(page,part);await expect(page).toHaveURL(new RegExp('#account/'+part+'$'));
    const link=page.locator(`#account-navigation a[href="#account/${part}"]`);await expect(link).toHaveAttribute('aria-current','page');await link.focus();await link.press('Enter');
    await expect(page.locator('#account-content > #'+part+'-panel')).toBeVisible();await expect(page.locator('#workspace-navigation')).toBeHidden();
    await page.reload();await expect(page.locator('#account-content > #'+part+'-panel')).toBeVisible();
    expect((await new AxeBuilder({page}).include('#account-page').analyze()).violations).toEqual([]);
    await captureScreenshot(page,{path:`test-results/account-${part}-desktop.png`});
    await page.setViewportSize({width:320,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await captureScreenshot(page,{path:`test-results/account-${part}-mobile.png`});await page.setViewportSize({width:1440,height:1000});
  }
  await navigateView(page,'today');await expect(page.locator('#today-note-editor')).toBeVisible();
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true});
  try{
    const reader=await context.newPage();await signIn(reader,process.env.TIMEKEEPER_WEB_READ_TOKEN);
    for(const part of ['clients','projects']){await navigateView(reader,part);await expect(reader.locator('#add-'+part.slice(0,-1))).toBeDisabled();}
  }finally{await context.close();}
});

for(const locale of ['en-GB','nb-NO'])test(`Today places its overview left and a live, non-mutating contents list right in ${locale}`,async({browser,request})=>{
  test.setTimeout(90000);
  const date='2026-07-09',path='/day-notes/'+date+'/content',original=await api(request,'GET',path),body=(await original.json()).body;
  const markdown='# Plan\n\n'+('A paragraph about the work.\n\n'.repeat(30))+'## Next steps\n\n### Decisions\n\n'+('More context.\n\n'.repeat(30))+'## Next steps\n';
  await api(request,'PUT',path,{body:markdown},{'If-Match':original.headers().etag});
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true,locale,viewport:{width:1440,height:1000}}),page=await context.newPage();
  try{
    await signIn(page);await page.locator('#today-date').fill(date);
    const contents=page.locator('#today-contents'),choices=contents.locator('ol button'),field=page.locator('#today-note-editor');
    await expect(contents.getByRole('heading',{name:locale==='nb-NO'?'Innholdsfortegnelse':'Table of contents',exact:true})).toBeVisible();
    await expect(choices).toHaveText(['Plan','Next steps','Decisions','Next steps']);
    const saved=await api(request,'GET',path);
    await choices.last().click();
    await expect(field.locator('.ProseMirror h2').last()).toBeVisible();
    expect(await field.locator('.ProseMirror h2').last().evaluate(node=>node.contains(getSelection().anchorNode))).toBe(true);
    expect((await api(request,'GET',path)).headers().etag).toBe(saved.headers().etag);
    await field.getByRole('button',{name:locale==='nb-NO'?'Markdown-kilde':'Markdown source',exact:true}).click();
    const source=page.locator('#today-journal-body');await choices.first().click();await expect(source).toBeFocused();await expect.poll(()=>source.evaluate(node=>node.selectionStart)).toBe(0);await choices.last().click();
    expect(await source.evaluate(node=>node.selectionStart)).toBe((await source.inputValue()).lastIndexOf('## Next steps'));
    expect(await source.evaluate(node=>node.scrollTop)).toBeGreaterThan(0);
    expect((await api(request,'GET',path)).headers().etag).toBe(saved.headers().etag);
    await source.fill(markdown+'\n## Live update\n');await expect(choices.last()).toHaveText('Live update');
    await field.getByRole('button',{name:locale==='nb-NO'?'Formatert':'Formatted',exact:true}).click();await choices.first().click();
    for(const theme of ['timekeeper','timekeeper-dark']){
      await setTheme(page,theme);
      for(const width of [1440,390,320]){
        await page.setViewportSize({width,height:1000});
        if(width===1440){
          const left=await page.locator('.today-sidebar').boundingBox(),main=await page.locator('.today-main').boundingBox(),right=await contents.boundingBox(),toolbar=await page.locator('.today-toolbar').boundingBox();
          expect(left.x+left.width).toBeLessThan(main.x);expect(right.x).toBeGreaterThanOrEqual(main.x+main.width);expect(Math.abs(left.y-main.y)).toBeLessThan(1);expect(Math.abs(right.y-toolbar.y)).toBeLessThan(1);
        }else{
          await expect(contents.locator('details')).not.toHaveAttribute('open','');
          await contents.locator('summary').click();await expect(choices.last()).toBeVisible();
          await expect.poll(async()=>{const editor=await field.boundingBox(),nav=await page.locator('#workspace-navigation').boundingBox();return editor.y+editor.height<=nav.y+1;}).toBe(true);
          await contents.locator('summary').click();
        }
        expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
        expect((await new AxeBuilder({page}).include('#today-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
        await captureScreenshot(page,{path:`test-results/today-three-columns-${locale}-${theme}-${width}.png`});
      }
    }
  }finally{await context.close();const current=await api(request,'GET',path);await api(request,'PUT',path,{body},{'If-Match':current.headers().etag});}
});

test('Today main actions reuse revisions, client association and confirmed deletion while other editors keep Note tools',async({page,request,browser})=>{
  const date='2026-07-10',path='/day-notes/'+date+'/content',original=await api(request,'GET',path),body=(await original.json()).body;
  await api(request,'PUT',path,{body:'# Action note\n\nKeep this content.'},{'If-Match':original.headers().etag});
  const client=await(await api(request,'POST','/clients',{name:'Main actions '+randomUUID().slice(0,6),color:'#176653'})).json();
  try{
    await signIn(page);await page.locator('#today-date').fill(date);const actions=page.getByRole('group',{name:'Main actions',exact:true});
    await expect(actions.getByRole('button')).toHaveCount(3);
    for(const name of ['Note revisions','Associate with client','Delete note'])await expect(page.locator('#today-note-editor .markdown-actions-menu').getByRole('menuitem',{name,exact:true,includeHidden:true})).toHaveCount(0);
    const history=actions.getByRole('button',{name:'Note revisions',exact:true});await history.focus();await history.press('Enter');await expect(page.getByRole('dialog',{name:'Note revisions',exact:true})).toBeVisible();await page.keyboard.press('Escape');await expect(history).toBeFocused();
    const associate=actions.getByRole('button',{name:'Associate with client',exact:true});await expect(associate).toBeEnabled();await associate.click();const dialog=page.getByRole('dialog',{name:'Associate with client',exact:true});await dialog.locator('select').selectOption(client.id);await dialog.getByRole('button',{name:'Save',exact:true}).click();await expect(dialog).toBeHidden();await expect(associate).toBeFocused();await expect(page.locator('#today-note-editor .note-client-label')).toHaveText(client.name);
    const readerContext=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true});
    try{
      const reader=await readerContext.newPage();await signIn(reader,process.env.TIMEKEEPER_WEB_READ_TOKEN);await reader.locator('#today-date').fill(date);
      await reader.locator('#today-contents-list button').click();await expect(reader.locator('#today-note-editor .ProseMirror')).toHaveAttribute('contenteditable','false');
      for(const name of ['Associate with client','Delete note'])await expect(reader.locator('#today-main-actions').getByRole('button',{name,exact:true})).toBeDisabled();
      await expect(reader.locator('#today-main-actions').getByRole('button',{name:'Note revisions',exact:true})).toBeEnabled();
    }finally{await readerContext.close();}
    await navigateView(page,'track');await page.locator('#track-month').fill('2026-07');await page.locator('[data-track-note-date="'+date+'"]').click();
    await page.locator('#track-note-editor').getByRole('button',{name:'Note tools',exact:true}).click();
    for(const name of ['Note revisions','Associate with client','Delete note'])await expect(page.getByRole('menuitem',{name,exact:true})).toBeVisible();
    await page.keyboard.press('Escape');await page.keyboard.press('Escape');await navigateView(page,'today');
    page.once('dialog',dialog=>dialog.accept());await actions.getByRole('button',{name:'Delete note',exact:true}).click();await expect(page.locator('#today-contents-list button')).toHaveCount(0);await expect(page.locator('#today-contents-empty')).toBeVisible();expect((await(await api(request,'GET',path)).json()).body).toBe('');
  }finally{
    const current=await api(request,'GET',path);await api(request,'PUT',path,{body},{'If-Match':current.headers().etag});
    const association='/notes/client?'+new URLSearchParams({sourceType:'DAY_NOTE',sourceKey:date}),linked=await api(request,'GET',association);await api(request,'PUT',association,{clientId:null},{'If-Match':linked.headers().etag});
    const currentClient=await api(request,'GET','/clients/'+client.id);await api(request,'DELETE','/clients/'+client.id,undefined,{'If-Match':currentClient.headers().etag});
  }
});
