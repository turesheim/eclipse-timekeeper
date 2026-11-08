import {saveMarkdown} from './editor-interactions.js';
import {clickNoteAction,noteActions,notesSource} from './editor-interactions.js';
import {noteEntry} from './note-entry.js';
import {readFile,writeFile} from 'node:fs/promises';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {captureScreenshot} from './screenshots.js';
import {setTheme} from './navigation.js';
import {browserRequest} from './browser-request.js';
import {appendEditorText} from './editor-interactions.js';

test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin};const status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function setup(page,request){
  const username='notes-'+randomUUID().slice(0,8),password='Private collection test password';
  const created=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});expect(created.status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page);const identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;};
  await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-title')).toBeVisible();return api;
}

test('Notes account imports a vault with review, guarded conflicts and exact file bytes',async({page,request})=>{
  const {mkdtemp,mkdir,writeFile,rm}=await import('node:fs/promises'),{join}=await import('node:path'),{tmpdir}=await import('node:os');
  const api=await setup(page,request),root=await mkdtemp(join(tmpdir(),'timekeeper-notes-import-')),day='2026-11-02';
  const body='\uFEFF---\r\ncustom: keep\r\n---\r\n\r\n# Imported æ\r\n',png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9o9u4AAAAASUVORK5CYII=','base64');
  await api('PUT','/notes/files?path=Notes%2FKeep.md',Buffer.from('Server original'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  const label=await api('GET','/day-labels/'+day);await api('PUT','/day-labels/'+day,{text:'Independent label'},{'If-Match':label.headers().etag});
  try{
    for(const folder of ['journal/assets','Notes','.obsidian'])await mkdir(join(root,folder),{recursive:true});
    for(const [path,bytes]of [['journal/'+day+'.md',body],['journal/2026-W45.md','# Week\r\n'],['Notes/New.md','# New note\n'],['Notes/Race.md','Local race'],['Notes/Keep.md','Local conflict'],['Notes/CON.md','Nonportable filename'],['journal/assets/pixel.png',png],['.obsidian/workspace.json','{}']])await writeFile(join(root,path),bytes);
    await page.evaluate(()=>location.hash='#account/notes');const card=page.locator('#account-content section').filter({has:page.getByRole('heading',{name:'Import notes',exact:true})});
    await expect(page.locator('#notes-panel').getByRole('button',{name:'Notes settings',exact:true})).toHaveCount(0);
    const chooser=page.waitForEvent('filechooser');await card.getByRole('button',{name:'Choose vault folder',exact:true}).click();await(await chooser).setFiles(root);
    await expect(card).toContainText('5 new files ready to import');await expect(card).toContainText('Already exists or conflicts');await expect(card).toContainText('Skipped');
    expect((await(await api('GET','/day-notes/'+day+'/content')).json()).body).toBe('');
    await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    expect((await new AxeBuilder({page}).include('#account-page').analyze()).violations).toEqual([]);await captureScreenshot(page,{path:'test-results/notes-account-import-review.png',fullPage:true});
    await api('PUT','/notes/files?path=Notes%2FRace.md',Buffer.from('Concurrent server file'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
    let failed=false;const uploads=[];await page.route('**/api/v1/notes/files?**',route=>{const path=new URL(route.request().url()).searchParams.get('path');uploads.push(path);if(path==='Notes/New.md'&&!failed){failed=true;return route.abort('internetdisconnected');}return route.continue();});
    await card.getByRole('button',{name:'Import new files',exact:true}).click();await expect(card).toContainText('Imported 3 files.');await expect(card).toContainText('Failed');
    await card.getByRole('button',{name:'Retry',exact:true}).click();await expect(card).toContainText('Imported 4 files.');expect(uploads.filter(path=>path==='journal/'+day+'.md')).toHaveLength(1);await page.unroute('**/api/v1/notes/files?**');
    for(const [path,bytes]of [['journal/'+day+'.md',body],['journal/assets/pixel.png',png],['Notes/Keep.md','Server original'],['Notes/Race.md','Concurrent server file']]){const entry=await(await api('GET','/notes/resolve?'+new URLSearchParams({path}))).json();expect(Buffer.from(await(await api('GET','/notes/entries/'+entry.id+'/content')).body()).equals(Buffer.from(bytes))).toBe(true);}
    expect((await(await api('GET','/day-labels/'+day)).json()).text).toBe('Independent label');expect((await(await api('GET','/week-notes/2026-W45')).json()).body).toBe('# Week\r\n');
    await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await expect(page.locator('#notes-panel')).toBeVisible();await expect(page.locator('#notes-document-title')).toBeVisible();
  }finally{await rm(root,{recursive:true,force:true});}
});

test('Notes shows saved creation and change times in the account zone, retaining them through drafts and rejected saves',async({page,request})=>{
  const api=await setup(page,request),createdAt='2026-07-01T12:34:56Z',modifiedAt='2026-07-02T20:30:00Z';
  const entry=await(await api('PUT','/notes/files?'+new URLSearchParams({path:'Notes/Dates.md',createdAt,modifiedAt}),Buffer.from('# Saved dates\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const me=await api('GET','/me');await api('PUT','/me',{zoneId:'Europe/Oslo',locale:'en-GB'},{'If-Match':me.headers().etag});
  await page.reload();await page.locator('[data-tab=notes]').click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Dates.md').click();
  const times=page.locator('.notes-timestamps time'),status=page.locator('.notes-footer > [role=status]');
  await expect(times.nth(0)).toHaveAttribute('datetime',createdAt);await expect(times.nth(0)).toContainText('14:34:56');await expect(times.nth(1)).toHaveAttribute('datetime',modifiedAt);await expect(times.nth(1)).toContainText('22:30:00');
  await expect(page.locator('.notes-timestamps')).toContainText('Created:');await expect(page.locator('.notes-timestamps')).toContainText('Last changed:');
  await page.route('**/api/v1/notes/entries/'+entry.id+'/content',route=>route.request().method()==='PUT'?route.fulfill({status:412,contentType:'application/json',body:JSON.stringify({error:{code:'STALE_VERSION',message:'Concurrent edit'}})}):route.continue());
  const source=await notesSource(page);await source.fill('# Pending dates\n');await expect(status).toHaveText('Unsaved changes');await expect(times.nth(1)).toHaveAttribute('datetime',modifiedAt);
  await saveMarkdown(page.locator('#notes-panel'),{wait:false});await expect(status).toContainText('changed');await expect(times.nth(1)).toHaveAttribute('datetime',modifiedAt);
  await page.unrouteAll({behavior:'wait'});page.once('dialog',dialog=>dialog.accept());await clickNoteAction(page,page.locator('#notes-panel'),'Reload from server');await expect(status).toHaveText('Saved');
  await source.fill('# Saved again\n');await saveMarkdown(page.locator('#notes-panel'));await expect(times.nth(1)).not.toHaveAttribute('datetime',modifiedAt);await expect(times.nth(0)).toHaveAttribute('datetime',createdAt);
  const saved=await(await api('GET','/notes/entries/'+entry.id)).json();await expect(times.nth(1)).toHaveAttribute('datetime',saved.modifiedAt);
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'UTC',locale:'nb-NO'},{'If-Match':profile.headers().etag});await page.reload();await page.locator('[data-tab=notes]').click();
  await expect(page.locator('.notes-timestamps')).toBeHidden();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Dates.md').click();
  await expect(page.locator('.notes-timestamps')).toContainText('Opprettet:');await expect(page.locator('.notes-timestamps')).toContainText('Sist endret:');await expect(times.nth(0)).toContainText('12:34:56');
  for(const width of [1440,320]){
    await page.setViewportSize({width,height:900});await expect(times.nth(0)).toBeInViewport();await expect(times.nth(1)).toBeInViewport();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=['timekeeper','solarized-light'].includes(theme)?'light':'dark';},theme);expect((await new AxeBuilder({page}).include('#notes-panel').analyze()).violations).toEqual([]);}
    await captureScreenshot(page,{path:`test-results/notes-saved-dates-${width}.png`});
  }
});

test('Notes source and formatted editors stay above the menu at desktop and phone sizes',async({page,request})=>{
  const api=await setup(page,request),body='# Document\n\n'+Array.from({length:150},(_,index)=>'Paragraph '+index+' with enough text for scrolling.\n\n').join('');
  await api('PUT','/notes/files?path=Notes%2FViewport.md',Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Viewport.md').click();
  const save=page.locator('.notes-footer > [role=status]');await expect(save).toHaveText('Saved');
  for(const viewport of [{width:1440,height:900},{width:390,height:844}]){
    await page.setViewportSize(viewport);await page.evaluate(()=>scrollTo(0,0));
    for(const mode of ['Markdown source','Formatted']){
      await page.locator('#notes-panel').getByRole('button',{name:mode,exact:true}).click();
      const content=mode==='Formatted'?page.locator('#notes-formatted .ProseMirror'):page.locator('#notes-body');await expect(content).toBeVisible();
      await expect.poll(()=>page.locator('.notes-editor').evaluate(node=>{const nav=document.querySelector('#workspace-navigation').getBoundingClientRect(),gap=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--workspace-content-gap'))*parseFloat(getComputedStyle(document.documentElement).fontSize);return Math.abs(nav.top-node.getBoundingClientRect().bottom-gap);})).toBeLessThan(2);
      expect(await content.evaluate(node=>node.scrollHeight>node.clientHeight)).toBe(true);await content.evaluate(node=>node.scrollTop=node.scrollHeight);await expect(save).toBeInViewport();await expect(save).toHaveText('Saved');
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      await expect(page.locator('#notes-panel .markdown-source-tools')).toHaveCount(0);
    }
  }
  await captureScreenshot(page,{path:'test-results/notes-editor-phone.png',fullPage:true});
});

test('autosave status follows actual edits and undo across Notes, Today and weekly notes',async({page,request})=>{
  const api=await setup(page,request);await api('PUT','/notes/files?path=Notes%2FChanges.md',Buffer.from('# Saved\r\n\r\nBody.\r\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Changes.md').click();
  const save=page.locator('.notes-footer > [role=status]');await expect(save).toHaveText('Saved');await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();await expect(save).toHaveText('Saved');
  await expect(page.locator('#notes-panel .markdown-outline')).toHaveCount(0);await expect(page.getByText('Formatting may normalize Markdown.',{exact:true})).toHaveCount(0);
  await appendEditorText(page.locator('#notes-formatted p'),' Edited.');await expect(save).toHaveText('Unsaved changes');await page.keyboard.press('ControlOrMeta+z');await expect(save).toHaveText('Saved');
  await appendEditorText(page.locator('#notes-formatted p'),' Saved edit.');await saveMarkdown(page.locator('#notes-panel'));await expect(save).toHaveText('Saved');
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill('2026-11-04');const daily=page.locator('#today-note-editor'),dailySave=daily.locator('.dialog-actions > [role=status]');await expect(daily.locator('.ProseMirror')).toHaveAttribute('contenteditable','true');await expect(dailySave).toHaveText('Saved');
  await appendEditorText(daily.locator('.ProseMirror p').first(),'Day draft');await expect(dailySave).toHaveText('Unsaved changes');await page.keyboard.press('ControlOrMeta+z');await expect(dailySave).toHaveText('Saved');
  await page.locator('#today-note-text').fill('Label only');await expect(dailySave).toHaveText('Unsaved changes');await page.locator('#today-note-text').fill('');await expect(dailySave).toHaveText('Saved');
  await page.locator('#today-month-grid [data-week]').first().click();const weekly=page.locator('#today-week-note-editor'),weeklySave=weekly.locator('.dialog-actions > [role=status]');await expect(weekly.locator('.ProseMirror')).toHaveAttribute('contenteditable','true');await expect(weeklySave).toHaveText('Saved');
  await appendEditorText(weekly.locator('.ProseMirror p').first(),'Week draft');await expect(weeklySave).toHaveText('Unsaved changes');await page.keyboard.press('ControlOrMeta+z');await expect(weeklySave).toHaveText('Saved');
});

test('ordinary typed characters can be backspaced in Notes and Today WYSIWYG',async({page,request})=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));const api=await setup(page,request);
  await api('PUT','/notes/files?path=Notes%2FKeys.md',Buffer.from('Keep this paragraph.\n\n- [ ] Keep this task\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Keys.md').click();await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();
  async function check(rich){
    for(const paragraph of [rich.locator(':scope > p').first(),rich.locator('li p').first()]){
      const before=await paragraph.textContent();await appendEditorText(paragraph,'');await page.keyboard.type('abcd');await expect(paragraph).toHaveText(before+'abcd');
      for(let index=0;index<4;index++)await page.keyboard.press('Backspace');await expect(paragraph).toHaveText(before);
    }
  }
  await check(page.locator('#notes-formatted .ProseMirror'));await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();await expect(page.locator('#notes-panel .markdown-source-tools')).toHaveCount(0);await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();await check(page.locator('#notes-formatted .ProseMirror'));await expect(page.locator('.notes-footer > [role=status]')).toHaveText('Saved');
  const day='2026-11-03',entry=await api('GET','/day-notes/'+day+'/content');await api('PUT','/day-notes/'+day+'/content',{body:'Keep this paragraph.\n\n- [ ] Keep this task\n'},{'If-Match':entry.headers().etag});
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(day);await expect(page.locator('#today-journal-formatted .ProseMirror li p')).toHaveText('Keep this task');await check(page.locator('#today-journal-formatted .ProseMirror'));await expect(page.locator('#today-note-editor').getByRole('button',{name:'Save',exact:true})).toHaveCount(0);expect(errors).toEqual([]);
});
test('Notes preserves exact source, keeps drafts across tabs and rejects stale saves',async({page,request})=>{
  const api=await setup(page,request);const path='Notes/Exact.md',original='---\r\nunknown: [x, true]\r\n---\r\n[[Other note]]  \r\n![[image.png]]\r\n';
  const created=await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(original),{'Content-Type':'application/octet-stream','If-None-Match':'*'});const entry=await created.json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();
  await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),path).click();
  await notesSource(page);await expect(page.locator('#notes-body')).toBeVisible();await expect(page.locator('#notes-body')).toHaveValue('[[Other note]]  \n![[image.png]]\n');
  await expect(page.locator('#notes-panel').getByRole('button',{name:'Save',exact:true})).toHaveCount(0);
  const unedited=await api('GET','/notes/entries/'+entry.id+'/content');expect(Buffer.from(await unedited.body()).equals(Buffer.from(original))).toBe(true);
  await(await notesSource(page)).fill('Draft kept in memory');await page.locator('[data-tab=today]').click();await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-body')).toHaveValue('Draft kept in memory');
  await saveMarkdown(page.locator('#notes-panel'));await expect(page.locator('.notes-footer')).toContainText('Saved');
  await(await notesSource(page)).fill('Concurrent local draft');
  const current=await api('GET','/notes/entries/'+entry.id+'/content');await api('PUT','/notes/entries/'+entry.id+'/content',Buffer.from('Remote edit'),{'Content-Type':'application/octet-stream','If-Match':current.headers().etag});
  await saveMarkdown(page.locator('#notes-panel'),{wait:false});await expect(page.locator('#notes-panel .alert').first()).toBeVisible();await expect(page.locator('#notes-body')).toHaveValue('Concurrent local draft');
  expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('Remote edit');
  const violations=await new AxeBuilder({page}).include('#notes-panel').analyze();expect(violations.violations).toEqual([]);
});
test('Notes supports nested creation, keyboard resize, rename and recovery on mobile',async({page,request})=>{
  await setup(page,request);await page.locator('.notes-resize').focus();const before=await page.locator('.notes-resize').getAttribute('aria-valuenow');await page.keyboard.press('ArrowRight');expect(await page.locator('.notes-resize').getAttribute('aria-valuenow')).not.toBe(before);
  await page.locator('#notes-panel').getByRole('button',{name:'New note',exact:true}).click();await page.locator('#notes-path').fill('Notes/New.md');await page.getByRole('dialog',{name:'New note',exact:true}).getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#notes-document-title')).toHaveText('New');
  await(await notesSource(page)).fill('Body');await saveMarkdown(page.locator('#notes-panel'));await expect(page.locator('.notes-footer')).toContainText('Saved');
  await clickNoteAction(page,page.locator('#notes-panel'),'Rename or move');await page.locator('#notes-path').fill('Notes/Renamed.md');await page.getByRole('dialog',{name:'Rename or move',exact:true}).getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#notes-document-title')).toHaveText('Renamed');
  page.once('dialog',dialog=>dialog.accept());await clickNoteAction(page,page.locator('#notes-panel'),'Delete file or folder');await expect(page.locator('#notes-body')).not.toBeVisible();
  await page.locator('#notes-panel').getByRole('button',{name:'Recently deleted',exact:true}).click();await noteEntry(page.locator('#notes-navigation'),'Notes/Renamed.md').click();await clickNoteAction(page,page.locator('#notes-panel'),'Restore deleted item');await expect(page.locator('#notes-body')).toHaveValue('Body');
  await page.setViewportSize({width:390,height:844});await expect(page.locator('#notes-navigation')).not.toBeVisible();await page.locator('.notes-drawer-toggle').click();await expect(page.locator('#notes-navigation')).toBeVisible();await page.locator('#notes-search').fill('Renamed');await expect(noteEntry(page.locator('#notes-navigation'),'Notes/Renamed.md')).toBeVisible();
  expect(await page.locator('#workspace-navigation [data-tab]').evaluateAll(buttons=>buttons.every(button=>Math.abs(button.getBoundingClientRect().top-buttons[0].getBoundingClientRect().top)<1))).toBe(true);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
test('Notes settings preserve journal associations and relative images use authenticated previews',async({page,request})=>{
  const api=await setup(page,request),day='2026-10-30';
  const original=await api('PUT','/notes/files?path=journal%2F'+day+'.md',Buffer.from('Journal body'),{'Content-Type':'application/octet-stream','If-None-Match':'*'}),identity=await original.json();
  const label=await api('GET','/day-labels/'+day);await api('PUT','/day-labels/'+day,{text:'Independent label'},{'If-Match':label.headers().etag});
  await page.evaluate(()=>location.hash='#account/notes');await expect(page.locator('#account-navigation a[href=\"#account/notes\"]')).toHaveAttribute('aria-current','page');await page.getByLabel('Journal folder',{exact:true}).fill('Private/Journal');await page.locator('#account-content').getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#account-content')).toContainText('Notes settings saved.');await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();
  expect((await(await api('GET','/notes/resolve?path=Private%2FJournal%2F'+day+'.md')).json()).id).toBe(identity.id);expect((await(await api('GET','/day-labels/'+day)).json()).text).toBe('Independent label');
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9o9u4AAAAASUVORK5CYII=','base64');
  await api('PUT','/notes/files?path=Notes%2Fimage.png',png,{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await api('PUT','/notes/files?path=Private%2FJournal%2Fassets%2Flegacy.png',png,{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await api('PUT','/notes/files?path=Notes%2FImage.md',Buffer.from('# Image\n\n![Pixel](image.png)\n\n![Legacy](../assets/legacy.png)\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Image.md').click();
  await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();await expect(page.locator('#notes-formatted img[alt=Pixel]')).toHaveAttribute('src',/^blob:/);await expect.poll(()=>page.locator('#notes-formatted img[alt=Pixel]').evaluate(image=>image.naturalWidth)).toBe(1);
  await expect.poll(()=>page.locator('#notes-formatted img[alt=Legacy]').evaluate(image=>image.naturalWidth)).toBe(1);
  await expect(page.locator('.notes-footer > [role=status]')).toHaveText('Saved');
  const violations=await new AxeBuilder({page}).include('#notes-panel').analyze();expect(violations.violations).toEqual([]);
});

test('Notes supports all themes, Norwegian labels and a read-only client',async({page,request,browser})=>{
  test.setTimeout(150000);const api=await setup(page,request);
  await api('PUT','/notes/files?path=Notes%2FTheme.md',Buffer.from('# Theme note\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
    await setTheme(page,theme);await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-title')).toBeVisible();
    const result=await new AxeBuilder({page}).include('#notes-panel').analyze();expect(result.violations).toEqual([]);
  }
  const me=await api('GET','/me'),profile=await me.json();await api('PUT','/me',{zoneId:profile.zoneId,locale:'nb-NO'},{'If-Match':me.headers().etag});
  await page.reload();await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-title')).toHaveText('Notater');await expect(page.locator('#notes-search')).toHaveAccessibleName('Søk i filnavn');
  const token=await(await api('POST','/auth/tokens',{name:'Read-only Notes browser','permissions':['read'],'expiresAt':null})).json();
  const session=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true,locale:'en-GB'});
  try{const reader=await session.newPage();await reader.goto('/web/');await reader.locator('#auth-mode').selectOption('token');await reader.locator('#token').fill(token.token);await reader.locator('#login-form button[type=submit]').click();await expect(reader.locator('#workspace')).toBeVisible();await reader.locator('[data-tab=notes]').click();await expect(reader.locator('#notes-search')).toBeVisible();
    await reader.locator('#notes-navigation').getByRole('button',{name:'Notes',exact:true}).click();await noteEntry(reader.locator('#notes-navigation'),'Notes/Theme.md').click();await expect(reader.locator('#notes-body')).toHaveValue('# Theme note\n');await expect(reader.locator('#notes-body')).not.toBeEditable();await expect(reader.locator('.notes-footer button')).toBeDisabled();
    await reader.evaluate(()=>location.hash='#account/notes');await expect(reader.locator('#notes-import-folder')).toBeDisabled();await expect(reader.locator('#account-content button[type=submit]')).toBeDisabled();for(const input of await reader.locator('#account-content input[type=text]').all())await expect(input).toBeDisabled();
  }finally{await session.close();}
});

test('uploaded images appear immediately and remain visible after saving and reopening Markdown',async({page,request})=>{
  const api=await setup(page,request);
  await api('PUT','/notes/files?path=Notes%2FUpload.md',Buffer.from('# Uploaded image\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();
  await noteEntry(page.locator('#notes-navigation'),'Notes').click();
  await noteEntry(page.locator('#notes-navigation'),'Notes/Upload.md').click();
  await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();
  await page.locator('#notes-formatted [role=textbox]').click();await page.keyboard.press('End');await page.keyboard.press('Enter');
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9o9u4AAAAASUVORK5CYII=','base64');
  await page.locator('.notes-markdown input[type=file][accept]').setInputFiles({name:'uploaded pixel.png',mimeType:'image/png',buffer:png});
  const image=page.locator('#notes-formatted img[alt="uploaded pixel.png"]');await expect(image).toBeVisible();await expect.poll(()=>image.evaluate(img=>img.naturalWidth)).toBe(1);
  await saveMarkdown(page.locator('#notes-panel'));
  await expect(page.locator('.notes-footer > [role=status]')).toHaveText('Saved');
  const stored=await(await api('GET','/notes/resolve?path=Notes%2FUpload.md')).json();
  const body=await(await api('GET','/notes/entries/'+stored.id+'/content')).text();expect(body).toContain('uploaded%20pixel.png');expect(body).not.toContain('blob:');
  await clickNoteAction(page,page.locator('#notes-panel'),'Reload from server');
  await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();
  await expect.poll(()=>image.evaluate(img=>img.naturalWidth)).toBe(1);
});

test('selecting a raster asset replaces the editor with an authenticated image preview',async({page,request})=>{
  const api=await setup(page,request);
  const png=Buffer.from(await page.evaluate(()=>{const canvas=document.createElement('canvas');canvas.width=1600;canvas.height=1200;const context=canvas.getContext('2d');context.fillStyle='#176b59';context.fillRect(0,0,1600,1200);context.fillStyle='#f1c453';context.fillRect(200,200,1200,800);return canvas.toDataURL('image/png').split(',')[1];}),'base64');
  await api('PUT','/notes/files?path=Notes%2Fpreview.png',png,{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await api('PUT','/notes/files?path=Notes%2FPreview.md',Buffer.from('Preview note'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();
  await noteEntry(page.locator('#notes-navigation'),'Notes').click();
  await noteEntry(page.locator('#notes-navigation'),'Notes/Preview.md').click();
  await(await notesSource(page)).fill('Unsaved draft');await noteEntry(page.locator('#notes-navigation'),'Notes/preview.png').click();
  const image=page.locator('.notes-image-preview img');await expect(image).toBeVisible();await expect.poll(()=>image.evaluate(img=>img.naturalWidth)).toBe(1600);
  await expect(page.locator('.notes-markdown')).toBeHidden();await expect(page.locator('.notes-footer button')).toBeHidden();
  await captureScreenshot(page,{path:'test-results/notes-image-preview.png',fullPage:true});
  const bounds=await image.boundingBox(),pane=await page.locator('.notes-image-preview').boundingBox();expect(bounds.width).toBeLessThanOrEqual(pane.width);expect(bounds.height).toBeLessThanOrEqual(pane.height);
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.setViewportSize({width:1280,height:900});await noteEntry(page.locator('#notes-navigation'),'Notes/Preview.md').click();
  await expect(image).toHaveCount(0);await expect(page.locator('#notes-body')).toHaveValue('Unsaved draft');
});

test('Notes can hide the configured journal without losing drafts or journal access',async({page,request})=>{
  const api=await setup(page,request),day='2026-11-01';
  await api('PUT','/notes/files?path=journal%2F'+day+'.md',Buffer.from('Journal original'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await api('PUT','/notes/files?path=Notes%2FKeep.md',Buffer.from('General note'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();
  await noteEntry(page.locator('#notes-navigation'),'journal').click();
  await noteEntry(page.locator('#notes-navigation'),'journal/'+day+'.md').click();
  await(await notesSource(page)).fill('Private unsaved draft');
  await page.evaluate(()=>location.hash='#account/notes');await expect(page.locator('#account-navigation a[href=\"#account/notes\"]')).toHaveAttribute('aria-current','page');
  await expect(page.getByLabel('Hide journal folder',{exact:true})).not.toBeChecked();
  await page.getByLabel('Hide journal folder',{exact:true}).check();
  await page.getByLabel('Journal folder',{exact:true}).fill('Private/Journal');
  await page.locator('#account-content').getByRole('button',{name:'Save',exact:true}).click();
  await expect(page.locator('#account-content')).toContainText('Notes settings saved.');await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();
  await expect(page.locator('#notes-body')).toHaveValue('Private unsaved draft');
  expect((await(await api('GET','/day-notes/'+day+'/content')).json()).body).toBe('Private unsaved draft');
  await page.locator('#notes-search').fill('2026');await expect(page.locator('.notes-tree [role=treeitem]')).toHaveCount(0);
  await page.locator('#notes-search').fill('Keep');await expect(noteEntry(page.locator('#notes-navigation'),'Notes/Keep.md')).toBeVisible();
  await noteEntry(page.locator('#notes-navigation'),'Notes/Keep.md').click();
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=notes]').click();
  await page.evaluate(()=>location.hash='#account/notes');await expect(page.locator('#account-navigation a[href=\"#account/notes\"]')).toHaveAttribute('aria-current','page');await expect(page.getByLabel('Hide journal folder',{exact:true})).toBeChecked();
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();
  await noteEntry(page.locator('#notes-navigation'),'Private').click();await expect(noteEntry(page.locator('#notes-navigation'),'Private/Journal')).toHaveCount(0);
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(day);await expect(page.locator('#today-journal-formatted')).toContainText('Private unsaved draft');
  await page.locator('[data-tab=notes]').click();await page.evaluate(()=>location.hash='#account/notes');await expect(page.locator('#account-navigation a[href=\"#account/notes\"]')).toHaveAttribute('aria-current','page');await page.getByLabel('Hide journal folder',{exact:true}).uncheck();
  await page.locator('#account-content').getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#account-content')).toContainText('Notes settings saved.');await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();
  await expect(noteEntry(page.locator('#notes-navigation'),'Private/Journal')).toBeVisible();
});

test('saving a rich edit preserves untouched vault Markdown and metadata bytes',async({page,request})=>{
  const api=await setup(page,request),source=(await readFile(new URL('./fixtures/markdown/fidelity/blocks.md',import.meta.url),'utf8')).replaceAll('\n','\r\n');
  const created=await api('PUT','/notes/files?path=Notes%2FFidelity.md',Buffer.from(source),{'Content-Type':'application/octet-stream','If-None-Match':'*'}),entry=await created.json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Fidelity.md').click();
  await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();
  const paragraph=page.locator('#notes-formatted p').filter({hasText:'Edit this paragraph.'});await paragraph.click();await page.keyboard.press('End');await page.keyboard.type(' More.');
  await saveMarkdown(page.locator('#notes-panel'));await expect(page.locator('.notes-footer > [role=status]')).toHaveText('Saved');
  expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(source.replace('Edit this paragraph.','Edit this paragraph. More.'));
});
test('revision comparison restores an unsaved draft and leaves stale-write guards intact',async({page,request})=>{
  const api=await setup(page,request),created=await api('PUT','/notes/files?path=Notes%2FHistory.md',Buffer.from('First version.\r\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'}),entry=await created.json();
  await api('PUT','/notes/entries/'+entry.id+'/content',Buffer.from('Second version.\n'),{'Content-Type':'application/octet-stream','If-Match':entry.etag});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/History.md').click();
  await clickNoteAction(page,page.locator('#notes-panel'),'Note revisions');const dialog=page.getByRole('dialog',{name:'Note revisions',exact:true});await dialog.getByRole('button',{name:/Version 1/}).click();await expect(dialog.locator('.markdown-diff-removed')).toContainText('First version.');await expect(dialog.locator('.markdown-diff-added')).toContainText('Second version.');
  await dialog.getByRole('button',{name:'Use as unsaved draft',exact:true}).click();await expect(page.locator('#notes-body')).toHaveValue('First version.\n');expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('Second version.\n');
  await saveMarkdown(page.locator('#notes-panel'));await expect(page.locator('.notes-footer > [role=status]')).toHaveText('Saved');expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('First version.\r\n');
});
test('vault wikilinks report ambiguity, navigate to headings and preview authenticated image embeds',async({page,request})=>{
  const api=await setup(page,request);await api('POST','/notes/folders',{path:'A'});await api('POST','/notes/folders',{path:'B'});
  for(const [path,text] of [['Notes/Design.md','# Design\n\n## Decisions\n\nKeep it portable.\n'],['A/Plan.md','First'],['B/Plan.md','Second'],['Notes/Links.md','[[Notes/Design#Decisions|Decision]] and [[Plan]].\n\n![[Notes/pixel.png|32]]\n']])await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(text),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await api('PUT','/notes/files?path=Notes%2Fpixel.png',Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jI1sAAAAASUVORK5CYII=','base64'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Links.md').click();await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();
  await expect(page.locator('#notes-formatted [data-wiki-reference="Plan"]')).toHaveAttribute('data-resolved','false');await expect(page.locator('#notes-formatted [data-wiki-reference="Notes/Design#Decisions|Decision"]')).toHaveAttribute('data-resolved','true');await expect.poll(()=>page.locator('#notes-formatted img[data-wiki-embed]').evaluate(node=>node.complete&&node.naturalWidth>0)).toBe(true);
  await page.locator('#notes-formatted [data-wiki-reference="Notes/Design#Decisions|Decision"]').click();await expect(page.locator('#notes-document-title')).toHaveText('Design');await expect(page.locator('#notes-formatted h2')).toHaveText('Decisions');
});

test('existing assets and image properties keep portable paths across Notes and Today',async({page,request})=>{
  const api=await setup(page,request),png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9o9u4AAAAASUVORK5CYII=','base64');
  await api('PUT','/notes/files?path=Notes%2Fpixel.png',png,{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await api('PUT','/notes/files?path=Notes%2FAssets.md',Buffer.from('Assets.\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Assets.md').click();
  await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();await page.locator('#notes-formatted p').click();await page.keyboard.press(process.platform==='darwin'?'Meta+ArrowRight':'End');
  await clickNoteAction(page,page.locator('#notes-panel'),'Existing vault asset');const picker=page.getByRole('dialog',{name:'Existing vault asset',exact:true});await picker.getByRole('button',{name:'Notes/pixel.png',exact:true}).click();
  const image=page.locator('#notes-formatted img[data-wiki-embed]');await expect.poll(()=>image.evaluate(node=>node.naturalWidth)).toBe(1);await image.click();await clickNoteAction(page,page.locator('#notes-panel'),'Image properties');const properties=page.getByRole('dialog',{name:'Image properties',exact:true});await properties.getByLabel('Width in pixels',{exact:true}).fill('48');await properties.getByRole('button',{name:'Save',exact:true}).click();
  await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();expect(await page.locator('#notes-body').inputValue()).toContain('![[Notes/pixel.png|48]]');
  const save=page.locator('.notes-footer > [role=status]');await saveMarkdown(page.locator('#notes-panel'));await expect(save).toHaveText('Saved');
  await api('PUT','/notes/files?path=journal%2F2026-11-03.md',Buffer.from('![Shared image](../Notes/pixel.png)\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  // The fixture day can already be open at sign-in; filling that date is not a reload.
  await page.goto('/web/?day=2026-11-03');await expect(page.locator('#status')).toHaveText('Up to date');
  await expect.poll(()=>page.locator('#today-journal-formatted').getByRole('img',{name:'Shared image',exact:true}).evaluate(node=>node.naturalWidth)).toBe(1);
});

test('an unavailable save retains the draft and leaves the server version intact',async({page,request})=>{
  const api=await setup(page,request),entry=await(await api('PUT','/notes/files?path=Notes%2FOffline.md',Buffer.from('Server original'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Offline.md').click();await(await notesSource(page)).fill('Draft retained through a failed save');
  await page.route('**/api/v1/notes/entries/*/content',route=>route.request().method()==='PUT'?route.abort('internetdisconnected'):route.continue());await saveMarkdown(page.locator('#notes-panel'),{wait:false});await expect(page.locator('#notes-panel > .alert')).toBeVisible();await expect(page.locator('#notes-body')).toHaveValue('Draft retained through a failed save');await page.unroute('**/api/v1/notes/entries/*/content');expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('Server original');
});

test('note images avoid unrelated catalog reads and concurrent journal images share settings',async({page,request})=>{
  const api=await setup(page,request),png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9o9u4AAAAASUVORK5CYII=';
  for(const name of ['shot.png','a.png','b.png','c.png'])await api('PUT','/journal-assets/'+name,{data:png},{'If-None-Match':'*'});
  for(let i=0;i<24;i++)await api('PUT','/notes/files?'+new URLSearchParams({path:'Notes/Unrelated-'+i+'.md'}),Buffer.from('# Unrelated title '+i+'\n\n'+'Unrelated prose. '.repeat(100)),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  const source='# Loading target\n\n![[shot.png]]\n\n![Relative screenshot](../journal/assets/shot.png)\n';
  const entry=await(await api('PUT','/notes/files?path=Notes%2FImages.md',Buffer.from(source),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();
  const queries=[];page.on('request',request=>{const url=new URL(request.url());if(url.pathname==='/api/v1/notes/entries'&&url.searchParams.has('query'))queries.push(url.searchParams.get('query'));});
  const started=Date.now();await noteEntry(page.locator('#notes-navigation'),entry.path).click();const images=page.locator('#notes-formatted .ProseMirror').getByRole('img');await expect(images).toHaveCount(2);
  await expect.poll(()=>images.evaluateAll(nodes=>nodes.every(node=>node.complete&&node.naturalWidth===1))).toBe(true);
  expect(queries.length).toBeGreaterThan(0);expect(queries.every(query=>query==='shot.png')).toBe(true);const noteMillis=Date.now()-started;
  expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(source);
  const body='![A](assets/a.png)\n\n![B](assets/b.png)\n\n![C](assets/c.png)\n';
  await api('PUT','/notes/files?path=journal%2F2026-11-03.md',Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  let settingsReads=0;page.on('request',request=>{if(new URL(request.url()).pathname==='/api/v1/notes')settingsReads++;});
  const journalStarted=Date.now();await page.goto('/web/?day=2026-11-03');await expect(page.locator('#status')).toHaveText('Up to date');
  const journal=page.locator('#today-journal-formatted .ProseMirror').getByRole('img');await expect(journal).toHaveCount(3);await expect.poll(()=>journal.evaluateAll(nodes=>nodes.every(node=>node.complete&&node.naturalWidth===1))).toBe(true);
  expect(settingsReads).toBe(1);
  const metrics=test.info().outputPath('note-image-loading.json');await writeFile(metrics,JSON.stringify({noteMillis,journalMillis:Date.now()-journalStarted,wikiQueries:queries,journalSettingsReads:settingsReads})+'\n');
  await test.info().attach('note-image-loading.json',{path:metrics,contentType:'application/json'});
});

test('opening a weekly note survives a metadata refresh between pointer down and pointer up',async({page,request})=>{
  const api=await setup(page,request);await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill('2026-11-04');
  const weekButton=page.locator('#today-month-grid [data-week]').first(),week=await weekButton.getAttribute('data-week');
  const previous=await api('GET','/week-notes/'+week);let release,fetched;
  const gate=new Promise(resolve=>{release=resolve;}),arrived=new Promise(resolve=>{fetched=resolve;});
  await page.route('**/api/v1/week-notes?**',async route=>{const response=await route.fetch();fetched();await gate;await route.fulfill({response});});
  await api('PUT','/week-notes/'+week,{body:'Saved weekly note'},{'If-Match':previous.headers().etag});
  await page.evaluate(()=>document.getElementById('refresh').click());await arrived;
  await weekButton.hover();await page.mouse.down();release();await expect(weekButton.locator('.today-month-dot')).toHaveCount(1);await page.mouse.up();
  await expect(page.locator('#today-week-note-editor')).toBeVisible();await expect(page.locator('#today-week-note-editor .ProseMirror')).toHaveAttribute('contenteditable','true');
});

test('Notes content search finds body text, keeps relevance order and pages on demand without replacing a draft',async({page,request})=>{
  const api=await setup(page,request),word='sparrow';
  for(let index=0;index<55;index++)await api('PUT','/notes/files?'+new URLSearchParams({path:'Notes/Result-'+String(index).padStart(2,'0')+'.md'}),Buffer.from(index===54?'# Sparrow priority\n\nA body match: sparrow.':'# Ordinary '+index+'\n\nA body match: sparrow.'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await api('PUT','/notes/files?path=Notes%2Fbinary.txt',Buffer.from(word),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await expect.poll(async()=>{const reply=await browserRequest(page).get('/api/v1/notes/search?'+new URLSearchParams({q:word}));return reply.status();},{timeout:30000}).toBe(200);
  const navigation=page.locator('#notes-navigation'),rows=navigation.locator('.notes-tree > [role=treeitem]');
  await page.locator('#notes-search').fill(word);await expect(navigation).toContainText('No matching files or folders.');
  await page.locator('#notes-search-mode').selectOption('content');await expect(rows).toHaveCount(50);await expect(rows.first()).toContainText('Sparrow priority');
  await expect(navigation.getByRole('button',{name:'Sort notes',exact:true})).toBeDisabled();await expect(rows.first().locator('mark').first()).toContainText(/sparrow/i);
  await navigation.getByRole('button',{name:'More results',exact:true}).focus();await navigation.getByRole('button',{name:'More results',exact:true}).press('Enter');await expect(rows).toHaveCount(55);await expect(rows.nth(50)).toBeFocused();await expect(navigation.getByRole('button',{name:'More results',exact:true})).toBeHidden();
  await rows.first().locator('button').click();const source=await notesSource(page);await expect(source).toHaveValue(/Sparrow priority/);
  await page.route('**/api/v1/notes/entries/*/content',route=>route.request().method()==='PUT'?route.abort('internetdisconnected'):route.continue());
  await source.fill('# Sparrow priority\n\nUnsaved private draft.');await page.locator('#notes-search').fill('absentword');await expect(navigation).toContainText('No matching notes.');await expect(source).toHaveValue('# Sparrow priority\n\nUnsaved private draft.');
  await page.locator('#notes-search').fill(word);await expect(rows).toHaveCount(50);await expect(rows.first().locator('mark').first()).toContainText(/sparrow/i);
  for(const width of [1440,390]){
    await page.setViewportSize({width,height:900});if(width===390)await page.locator('#notes-panel').getByRole('button',{name:'Folders and notes',exact:true}).click();
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#notes-panel').analyze()).violations).toEqual([]);
    await captureScreenshot(page,{path:`test-results/notes-content-search-${width}.png`});
  }
});

test('Notes content search rejects delayed old queries and explains unavailable or stale pages',async({page,request})=>{
  const api=await setup(page,request);const entry=await(await api('PUT','/notes/files?path=Notes%2FSearch.md',Buffer.from('# Search\n\nFresh body.'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const routeHeaders={'X-Timekeeper-Owner':Buffer.from(entry.ownerId).toString('base64url')};
  let releaseOld,oldStarted;const held=new Promise(resolve=>releaseOld=resolve),started=new Promise(resolve=>oldStarted=resolve);
  const result=(text,nextCursor)=>({snapshot:'fixture-snapshot',items:[{entry,excerpt:{text,highlights:[{start:0,end:text.length}]}}],...(nextCursor?{nextCursor}:{})});
  await page.route('**/api/v1/notes/search?**',async route=>{
    const url=new URL(route.request().url()),q=url.searchParams.get('q');
    if(q==='old'){oldStarted();await held;return route.fulfill({headers:routeHeaders,json:result('Old body.')});}
    if(q==='unavailable')return route.fulfill({status:503,json:{error:{code:'NOTES_SEARCH_INDEXING',message:'Preparing'}}});
    if(url.searchParams.has('cursor'))return route.fulfill({status:412,json:{error:{code:'NOTES_SEARCH_SNAPSHOT_EXPIRED',message:'Changed'}}});
    return route.fulfill({headers:routeHeaders,json:result('Fresh body.','fixture-cursor')});
  });
  const navigation=page.locator('#notes-navigation');await page.locator('#notes-search-mode').selectOption('content');await page.locator('#notes-search').fill('old');await started;
  await page.locator('#notes-search').fill('fresh');await expect(navigation.locator('.notes-search-excerpt')).toHaveText('Fresh body.');releaseOld();await expect(navigation.locator('.notes-search-excerpt')).toHaveText('Fresh body.');
  await navigation.getByRole('button',{name:'More results',exact:true}).focus();await navigation.getByRole('button',{name:'More results',exact:true}).press('Enter');await expect(navigation.getByRole('button',{name:'Search again',exact:true})).toBeFocused();await expect(navigation).toContainText('Search results changed or expired. Search again');await expect(navigation.locator('.notes-search-excerpt')).toHaveCount(0);
  await page.locator('#notes-search').fill('unavailable');await expect(navigation).toContainText('Content search is being prepared or is unavailable');await expect(navigation.getByRole('button',{name:'Search again',exact:true})).toBeVisible();
  await page.locator('#notes-search-mode').selectOption('filename');await page.locator('#notes-search').fill('Search.md');await expect(noteEntry(navigation,'Notes/Search.md')).toBeVisible();
});


test('Notes content paging leaves focus in the editor when the user moves there while waiting',async({page,request})=>{
  const api=await setup(page,request);const entry=await(await api('PUT','/notes/files?path=Notes%2FFocus.md',Buffer.from('# Focus\n\nBody result.'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const routeHeaders={'X-Timekeeper-Owner':Buffer.from(entry.ownerId).toString('base64url')};
  let releaseMore,moreStarted;const held=new Promise(resolve=>releaseMore=resolve),started=new Promise(resolve=>moreStarted=resolve);
  const hit={entry,excerpt:{text:'Body result.',highlights:[{start:0,end:4}]}};
  await page.route('**/api/v1/notes/search?**',async route=>{
    if(new URL(route.request().url()).searchParams.has('cursor')){moreStarted();await held;return route.fulfill({headers:routeHeaders,json:{snapshot:'fixture',items:[]}});}
    return route.fulfill({headers:routeHeaders,json:{snapshot:'fixture',items:[hit],nextCursor:'fixture-cursor'}});
  });
  const navigation=page.locator('#notes-navigation');await noteEntry(navigation,'Notes').click();await noteEntry(navigation,'Notes/Focus.md').click();const source=await notesSource(page);
  await page.locator('#notes-search-mode').selectOption('content');await page.locator('#notes-search').fill('body');await expect(navigation.locator('.notes-search-excerpt')).toHaveText('Body result.');
  const more=navigation.getByRole('button',{name:'More results',exact:true});await more.focus();await more.press('Enter');await started;await source.focus();releaseMore();
  await expect(more).toBeHidden();await expect(source).toBeFocused();
});

test('Notes folder overwrite is optional, previews replacements and preserves files changed after review',async({page,request})=>{
  const {mkdtemp,mkdir,writeFile,utimes,rm}=await import('node:fs/promises'),{join}=await import('node:path'),{tmpdir}=await import('node:os'),{createHash}=await import('node:crypto');
  const api=await setup(page,request),root=await mkdtemp(join(tmpdir(),'dagsverk-overwrite-')),createdAt='2020-01-02T03:04:05Z',modifiedAt='2021-02-03T04:05:06Z',sourceModified='2025-12-29T07:55:09.000Z';
  const put=async(path,text)=>await(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(text),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const original=await put('Notes/Replace.md','Server original'),race=await put('Notes/Race.md','Server race original');const excluded=await put('Notes/Excluded.md','Keep this file');
  try{
    await mkdir(join(root,'Notes'));for(const [name,body]of [['Replace.md','Imported exact\r\n'],['Race.md','Do not overwrite a concurrent change'],['Excluded.md','Unselected replacement'],['New.md','New local file']]){await writeFile(join(root,'Notes',name),body);await utimes(join(root,'Notes',name),new Date(sourceModified),new Date(sourceModified));}
    const body='Imported exact\r\n';await writeFile(join(root,'timekeeper-notes-metadata.json'),JSON.stringify({format:'timekeeper-notes-v1',entries:[{path:'Notes/Replace.md',kind:'FILE',bytes:Buffer.byteLength(body),sha256:createHash('sha256').update(body).digest('hex'),createdAt,createdAtSource:'ORIGINAL',modifiedAt}]}));
    await page.evaluate(()=>location.hash='#account/notes');const card=page.locator('#account-content section').filter({has:page.getByRole('heading',{name:'Import notes',exact:true})}),overwrite=card.getByRole('checkbox',{name:'Overwrite existing files',exact:true});await expect(overwrite).not.toBeChecked();
    const chooser=page.waitForEvent('filechooser');await card.getByRole('button',{name:'Choose vault folder',exact:true}).click();await(await chooser).setFiles(root);
    await expect(card).toContainText('1 new file ready to import');await expect(card.locator('.notes-import-status').filter({hasText:'Already exists or conflicts'})).toHaveCount(3);
    await card.getByRole('checkbox',{name:'Notes/',exact:true}).uncheck();await overwrite.check();await expect(card.getByRole('button',{name:'Import new files',exact:true})).toBeDisabled();await card.getByRole('checkbox',{name:'Notes/',exact:true}).check();await card.getByRole('checkbox',{name:'Notes/Excluded.md',exact:true}).uncheck();await expect(card).toContainText('3 files selected. Replacements: 2.');await expect(card.locator('.notes-import-status').filter({hasText:'Will replace existing file'})).toHaveCount(2);
    await overwrite.uncheck();await expect(card).toContainText('1 new file ready to import');await overwrite.check();
    for(const width of [390,1440]){await page.setViewportSize({width,height:900});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#account-page').analyze()).violations).toEqual([]);await captureScreenshot(page,{path:`test-results/notes-overwrite-review-${width}.png`});}
    await api('PUT','/notes/entries/'+race.id+'/content',Buffer.from('Concurrent server edit'),{'Content-Type':'application/octet-stream','If-Match':race.etag});
    await card.getByRole('button',{name:'Import selected files',exact:true}).click();await expect(card).toContainText('Imported 2 files.');await expect(card.locator('.notes-import-status').filter({hasText:'Already exists or conflicts'})).toHaveCount(1);
    const replaced=await(await api('GET','/notes/entries/'+original.id)).json(),added=await(await api('GET','/notes/resolve?path=Notes%2FNew.md')).json();
    expect(replaced.id).toBe(original.id);expect(replaced.createdAt).toBe(createdAt);expect(replaced.createdAtSource).toBe('ORIGINAL');expect(replaced.modifiedAt).toBe(modifiedAt);expect(added.createdAtSource).toBe('ADDED');expect(Date.parse(added.modifiedAt)).toBe(Date.parse(sourceModified));
    expect(await(await api('GET','/notes/entries/'+original.id+'/content')).text()).toBe(body);expect(await(await api('GET','/notes/entries/'+race.id+'/content')).text()).toBe('Concurrent server edit');expect(await(await api('GET','/notes/entries/'+excluded.id+'/content')).text()).toBe('Keep this file');
    await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await page.locator('[data-tab=notes]').click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/New.md').click();await expect(page.locator('.notes-timestamps')).toContainText('Added:');
    await noteEntry(page.locator('#notes-navigation'),'Notes/Replace.md').click();await expect(page.locator('.notes-timestamps')).toContainText('Created:');
  }finally{await rm(root,{recursive:true,force:true});}
});

test('Notes identifies unknown creation-date provenance without guessing from an older modification date',async({page,request})=>{
  const api=await setup(page,request),createdAt='2026-11-05T06:20:24Z',modifiedAt='2025-12-29T07:55:09Z';
  for(const [path,createdAtSource]of [['Notes/Legacy.md','UNKNOWN'],['Notes/Original.md','ORIGINAL']])await api('PUT','/notes/files?'+new URLSearchParams({path,createdAt,modifiedAt,createdAtSource}),Buffer.from('# Dates\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Legacy.md').click();await expect(page.locator('.notes-timestamps')).toContainText('Created / added:');
  for(const width of [320,1440]){await page.setViewportSize({width,height:900});await expect(page.locator('.notes-timestamps time').first()).toBeInViewport();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}
  await noteEntry(page.locator('#notes-navigation'),'Notes/Original.md').click();await expect(page.locator('.notes-timestamps')).toContainText('Created:');await expect(page.locator('.notes-timestamps time').first()).toHaveAttribute('datetime',createdAt);
});
