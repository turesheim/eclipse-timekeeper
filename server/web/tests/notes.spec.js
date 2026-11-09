import {searchNotes} from './notes-search.js';
import {saveMarkdown} from './editor-interactions.js';
import {clickNoteAction,noteActions,notesSource} from './editor-interactions.js';
import {noteEntry} from './note-entry.js';
import {readFile,writeFile} from 'node:fs/promises';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {captureScreenshot} from './screenshots.js';
import {setTheme,openAccountMenu} from './navigation.js';
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

test('pasted URLs survive saving and reopening a private Notes document without escapes',async({page,request})=>{
  const api=await setup(page,request),url='https://example.org/a_b/one_(two)?name[0]=value&next=a_b#part_one',path='Notes/Pasted URL.md';
  const entry=await(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from('Before.\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const open=async()=>{await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),path).click();await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();};
  await open();await appendEditorText(page.locator('#notes-formatted p'),' ');
  await page.evaluate(text=>{const data=new DataTransfer();data.setData('text/plain',text);document.querySelector('#notes-formatted .ProseMirror').dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));},url);
  await expect(page.locator('#notes-formatted a')).toHaveAttribute('href',url);await saveMarkdown(page.locator('#notes-panel'));
  const stored=await(await api('GET','/notes/entries/'+entry.id+'/content')).text();expect(stored).toContain('<'+url+'>');expect(stored).not.toContain('\\_');
  await notesSource(page);await expect(page.locator('#notes-body')).toHaveValue(stored);await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();await expect(page.locator('#notes-formatted a')).toHaveText(url);
  await page.reload();await page.locator('[data-tab=notes]').click();await open();await expect(page.locator('#notes-formatted a')).toHaveAttribute('href',url);
  await appendEditorText(page.locator('#notes-formatted p'),' Later.');await expect(page.locator('#notes-formatted a')).toHaveText(url);await saveMarkdown(page.locator('#notes-panel'));expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toContain('<'+url+'>');
});

test('saved private PlantUML notes retain frontmatter and untouched fence bytes after a rich prose edit',async({page,request})=>{
  const api=await setup(page,request),source='\uFEFF---\r\nprivate: retained\r\n---\r\n\r\n~~~puml\r\n@startuml\r\nAlice -> Bob: Private plan\r\n@enduml\r\n~~~\r\n\r\nAfter the diagram.\r\n',entry=await(await api('PUT','/notes/files?path=Notes%2FDiagram.md',Buffer.from(source),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Diagram.md').click();await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();
  const image=page.locator('#notes-formatted .markdown-plantuml-block img');await expect(image).toBeVisible();await expect.poll(()=>image.evaluate(node=>node.complete?node.naturalWidth:0)).toBeGreaterThan(0);
  await appendEditorText(page.locator('#notes-formatted .ProseMirror > p').last(),' Saved.');await saveMarkdown(page.locator('#notes-panel'));expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(source.replace('After the diagram.','After the diagram. Saved.'));
  await page.reload();await page.locator('[data-tab=notes]').click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Diagram.md').click();await expect(page.locator('#notes-formatted .markdown-plantuml-block img')).toBeVisible();
});

test('clicking a hashtag searches exact private tags and retains the current Notes draft',async({page,request})=>{
  const api=await setup(page,request),put=async(path,value)=>api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(value),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  const current=await(await put('Notes/A-current.md','# Current\n\nA #OpenShift deployment.\n')).json();await put('Notes/B-tagged.md','# Another\n\nAlso #openshift.\n');await put('Notes/C-plain.md','OpenShift #OpenShiftTools `#OpenShift`');
  await expect.poll(async()=>{const reply=await browserRequest(page).get('/api/v1/notes/search?'+new URLSearchParams({q:'tag:#openshift'}));return reply.status();},{timeout:30000}).toBe(200);
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/A-current.md').click();await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();
  await page.route('**/api/v1/notes/entries/*/content',route=>route.request().method()==='PUT'?route.abort('internetdisconnected'):route.continue());
  await appendEditorText(page.locator('#notes-formatted p'),' Keep this private draft.');await page.locator('#notes-formatted [data-hashtag="#openshift"]').click();
  await expect(page.locator('#notes-search-mode')).toHaveCount(0);await expect(page.locator('#notes-search-query')).toHaveValue('tag:#openshift');
  const rows=page.locator('#notes-search-results [data-search-note]');await expect(rows).toHaveCount(2);await expect(rows.first()).toContainText('A-current.md');await expect(rows.last()).toContainText('B-tagged.md');await expect(page.locator('#notes-formatted')).toContainText('Keep this private draft.');
  const read=await api('GET','/notes/entries/'+current.id+'/content');expect(await read.text()).not.toContain('Keep this private draft.');
  await page.locator('#notes-search-panel').getByRole('button',{name:'Close',exact:true}).click();await page.setViewportSize({width:390,height:844});await expect(page.locator('#notes-panel').getByRole('button',{name:'Folders and notes',exact:true})).toHaveAttribute('aria-expanded','false');
  await page.locator('#notes-formatted [data-hashtag="#openshift"]').focus();await page.locator('#notes-formatted [data-hashtag="#openshift"]').press('Enter');await expect(page.locator('#notes-search-query')).toBeVisible();await expect(rows).toHaveCount(2);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});

test('a Today hashtag opens Notes tag search using the current private journal',async({page,request})=>{
  const api=await setup(page,request),day='2026-11-03',entry=await api('GET','/day-notes/'+day+'/content');await api('PUT','/day-notes/'+day+'/content',{body:'A #OpenShift journal entry.'},{'If-Match':entry.headers().etag});
  await expect.poll(async()=>{const response=await browserRequest(page).get('/api/v1/notes/search?'+new URLSearchParams({q:'tag:#openshift'}));return response.status();},{timeout:30000}).toBe(200);
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(day);const tag=page.locator('#today-journal-formatted [data-hashtag="#openshift"]');await expect(tag).toHaveText('#OpenShift');await tag.focus();await tag.press('Enter');
  await expect(page.locator('#workspace')).toHaveAttribute('data-view','today');await expect(page.locator('#notes-search-query')).toHaveValue('tag:#openshift');
  await expect(page.locator('#notes-search-results [data-search-note]')).toHaveCount(1);await page.locator('#notes-search-panel').getByRole('button',{name:'Close',exact:true}).click();
  await page.locator('[data-tab=track]').click();await page.locator('#track-month').fill('2026-11');await page.locator('[data-track-note-date="'+day+'"]').click();const dialog=page.locator('#track-note-editor');await expect(dialog).toBeVisible();
  await dialog.locator('[data-hashtag="#openshift"]').click();await expect(dialog).toBeHidden();await expect(page.locator('#workspace')).toHaveAttribute('data-view','track');await expect(page.locator('#notes-search-query')).toHaveValue('tag:#openshift');await expect(page.locator('#notes-search-results [data-search-note]')).toHaveCount(1);
});

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
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await expect(page.locator('#notes-navigation [role=tree]')).toHaveAttribute('aria-busy','false');await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Viewport.md').click();
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
  await page.setViewportSize({width:390,height:844});await expect(page.locator('#notes-navigation')).not.toBeVisible();await page.locator('.notes-drawer-toggle').click();await expect(page.locator('#notes-navigation')).toBeVisible();const results=await searchNotes(page,'file:Renamed');await expect(results.locator('[data-search-note]')).toContainText('Notes/Renamed.md');
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
  const themeEntry=await(await api('PUT','/notes/files?path=Notes%2FTheme.md',Buffer.from('# Theme note\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const bookmarkPath='/notes/bookmark?'+new URLSearchParams({sourceType:themeEntry.sourceType,sourceKey:themeEntry.sourceKey});const bookmark=await api('GET',bookmarkPath);await api('PUT',bookmarkPath,{bookmarked:true},{'If-Match':bookmark.headers().etag});
  for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
    await setTheme(page,theme);await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-title')).toBeVisible();
    if(!await noteEntry(page.locator('#notes-navigation'),'Notes/Theme.md').isVisible())await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Theme.md').click();const bookmarkButton=page.locator('#notes-panel .note-bookmark');await expect(bookmarkButton).toHaveAttribute('aria-pressed','true');
    expect(await bookmarkButton.evaluate(button=>{const box=button.getBoundingClientRect();return box.right<=innerWidth&&box.width>=44&&box.height>=44;})).toBe(true);await captureScreenshot(page,{path:test.info().outputPath('bookmark-'+theme+'.png')});
    const result=await new AxeBuilder({page}).include('#notes-panel').analyze();expect(result.violations).toEqual([]);
  }
  const me=await api('GET','/me'),profile=await me.json();await api('PUT','/me',{zoneId:profile.zoneId,locale:'nb-NO'},{'If-Match':me.headers().etag});
  await page.reload();await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-title')).toHaveText('Notater');await expect(page.locator('#titlebar-notes-query')).toHaveAccessibleName('Søk i notater');
  const token=await(await api('POST','/auth/tokens',{name:'Read-only Notes browser','permissions':['read'],'expiresAt':null})).json();
  const session=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true,locale:'en-GB'});
  try{const reader=await session.newPage();await reader.goto('/web/');await reader.locator('#auth-mode').selectOption('token');await reader.locator('#token').fill(token.token);await reader.locator('#login-form button[type=submit]').click();await expect(reader.locator('#workspace')).toBeVisible();await reader.locator('[data-tab=notes]').click();await expect(reader.locator('#titlebar-notes-query')).toBeVisible();
    await reader.locator('#notes-navigation').getByRole('button',{name:'Notes',exact:true}).click();await noteEntry(reader.locator('#notes-navigation'),'Notes/Theme.md').click();await expect(reader.locator('#notes-body')).toHaveValue('# Theme note\n');await expect(reader.locator('#notes-body')).not.toBeEditable();await expect(reader.locator('.notes-title-input')).not.toBeEditable();await expect(reader.locator('.notes-footer button')).toBeDisabled();await expect(reader.locator('#notes-panel .note-bookmark')).toHaveAttribute('aria-pressed','true');await expect(reader.locator('#notes-panel .note-bookmark')).toBeDisabled();
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
  const results=await searchNotes(page,'file:2026');await expect(results.locator('[data-search-note]')).toHaveCount(0);await expect(results).toContainText('No matching notes.');
  await searchNotes(page,'file:Keep');await expect(results.locator('[data-search-note]')).toContainText('Notes/Keep.md');await results.locator('[data-search-note]').click();const popup=page.locator('#note-popup-editor');await expect(popup.locator('.ProseMirror')).toBeVisible();await popup.getByRole('button',{name:'Open in workspace',exact:true}).click();await expect(results).toBeHidden();
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
test('editing the title above a document renames its file and preserves the body and dates',async({page,request})=>{
  const api=await setup(page,request),path='Notes/Imported title.md',body='# Original heading\r\n\r\nExact body.\r\n';
  const entry=await(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),path).click();
  const title=page.locator('#notes-panel').getByRole('textbox',{name:'Document title',exact:true});await expect(title).toHaveValue('Original heading');await title.fill('CVE-2026-111943');await title.press('Enter');
  await expect(page.locator('.notes-document-path')).toHaveText('Notes/CVE-2026-111943.md');await expect(page.locator('.notes-footer > [role=status]')).toHaveText('Saved');
  const saved=await(await api('GET','/notes/entries/'+entry.id)).json();for(const field of ['id','sourceId','etag','createdAt','modifiedAt','contentVersion'])expect(saved[field]).toEqual(entry[field]);
  expect(saved.title).toBe('CVE-2026-111943');expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(body);
  await page.reload();await page.locator('[data-tab=notes]').click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/CVE-2026-111943.md').click();await expect(title).toHaveValue('CVE-2026-111943');
  await title.fill('Cancelled title');await title.press('Escape');await expect(title).toHaveValue('CVE-2026-111943');await expect(page.locator('.notes-document-path')).toHaveText('Notes/CVE-2026-111943.md');
});

test('a conflicting or stale title rename retains the draft and never overwrites another file',async({page,request})=>{
  const api=await setup(page,request),path='Notes/Title guard.md';
  const entry=await(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from('# Title guard\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await api('PUT','/notes/files?path=Notes%2FExisting.md',Buffer.from('Existing exact bytes'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),path).click();
  const title=page.locator('#notes-panel').getByRole('textbox',{name:'Document title',exact:true});await title.fill('Existing');await title.press('Enter');await expect(page.locator('#notes-panel > [role=alert]')).toBeVisible();await expect(title).toHaveValue('Existing');await expect(page.locator('.notes-document-path')).toHaveText(path);
  const existing=await(await api('GET','/notes/resolve?path=Notes%2FExisting.md')).json();expect(await(await api('GET','/notes/entries/'+existing.id+'/content')).text()).toBe('Existing exact bytes');
  await title.press('Escape');await title.fill('My pending title');const current=await api('GET','/notes/entries/'+entry.id);await api('PUT','/notes/entries/'+entry.id+'/title',{title:'Peer title'},{'If-Match':current.headers().etag});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().evaluate(button=>button.click());await expect(page.locator('.notes-document-path')).toHaveText('Notes/Peer title.md');await expect(title).toHaveValue('My pending title');
  await title.press('Enter');await expect(page.locator('#notes-panel > [role=alert]')).toBeVisible();await expect(title).toHaveValue('My pending title');expect((await(await api('GET','/notes/entries/'+entry.id)).json()).title).toBe('Peer title');
  page.once('dialog',dialog=>dialog.accept());await clickNoteAction(page,page.locator('#notes-panel'),'Reload from server');await expect(title).toHaveValue('Peer title');await expect(page.locator('.notes-document-path')).toHaveText('Notes/Peer title.md');
});

test('an uncertain title rename keeps the draft and is checked by reload without another write',async({page,request})=>{
  const api=await setup(page,request),path='Notes/Uncertain title.md';
  const entry=await(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from('# Uncertain title\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),path).click();
  let writes=0;await page.route('**/api/v1/notes/entries/'+entry.id+'/title',async route=>{writes++;await route.fetch();return route.abort('internetdisconnected');});
  const title=page.locator('#notes-panel').getByRole('textbox',{name:'Document title',exact:true});await title.fill('Applied uncertain title');await title.press('Enter');await expect(page.locator('#notes-panel > [role=alert]')).toBeVisible();await expect(title).toHaveValue('Applied uncertain title');await title.press('Enter');expect(writes).toBe(1);
  expect((await(await api('GET','/notes/entries/'+entry.id)).json()).path).toBe('Notes/Applied uncertain title.md');await page.unrouteAll({behavior:'wait'});
  page.once('dialog',dialog=>dialog.accept());await clickNoteAction(page,page.locator('#notes-panel'),'Reload from server');await expect(title).toHaveValue('Applied uncertain title');await expect(page.locator('.notes-document-path')).toHaveText('Notes/Applied uncertain title.md');expect(writes).toBe(1);
});

test('a late title rename response cannot restore private metadata after page suspension',async({page,request})=>{
  const api=await setup(page,request),path='Notes/Private title.md';
  const entry=await(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from('# Private title\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),path).click();
  let release,arrive,deliver;const gate=new Promise(resolve=>release=resolve),arrived=new Promise(resolve=>arrive=resolve),delivered=new Promise(resolve=>deliver=resolve);
  await page.route('**/api/v1/notes/entries/'+entry.id+'/title',async route=>{const response=await route.fetch();arrive();await gate;try{await route.fulfill({response});}catch{}finally{deliver();}});
  try{
    const title=page.locator('#notes-panel').getByRole('textbox',{name:'Document title',exact:true});await title.fill('Private renamed title');await title.press('Enter');await arrived;
    await page.evaluate(()=>window.dispatchEvent(new PageTransitionEvent('pagehide',{persisted:true})));await expect(page.locator('#workspace')).not.toBeVisible();release();await delivered;
    await expect(page.locator('.notes-title-input')).toHaveValue('');await expect(page.locator('.notes-document-path')).toBeEmpty();await expect(page.locator('#notes-document-title')).not.toContainText('Private');
  }finally{release();await page.unrouteAll({behavior:'wait'});}
});

test('journal wikilinks resolve CVE filenames and show the target document path',async({page,request})=>{
  const api=await setup(page,request),path='Notes/CVE-2026-111943.md',day='2026-11-09';
  await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from('# CVE-2026-111943\n\nPrivate investigation.\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await api('PUT','/notes/files?'+new URLSearchParams({path:'journal/'+day+'.md'}),Buffer.from('Follow up [[CVE-2026-111943]].\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.goto('/web/?day='+day);await expect(page.locator('#status')).toHaveText('Up to date');
  const link=page.locator('#today-journal-formatted [data-wiki-reference="CVE-2026-111943"]');
  await expect(link).toHaveAttribute('data-resolved','true');await link.click();
  await expect(page.locator('#notes-document-title')).toHaveText('CVE-2026-111943');await expect(page.locator('#notes-formatted')).toContainText('Private investigation.');
  const storedPath=page.locator('.notes-footer .notes-document-path');await expect(storedPath).toHaveText(path);
  expect(await storedPath.evaluate(node=>!!node.previousElementSibling?.querySelector('time'))).toBe(true);
  await clickNoteAction(page,page.locator('#notes-panel'),'Rename or move');const rename=page.getByRole('dialog',{name:'Rename or move',exact:true});
  const movedPath='Notes/Renamed investigation '+('CVE-2026-111943-'.repeat(10))+'.md';
  await rename.getByLabel('Path from collection root',{exact:true}).fill(movedPath);await rename.getByRole('button',{name:'Save',exact:true}).click();
  await expect(storedPath).toHaveText(movedPath);
  for(const width of [1440,320]){
    await page.setViewportSize({width,height:900});await expect(storedPath).toBeInViewport();
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await captureScreenshot(page,{path:`test-results/notes-document-path-${width}.png`});
  }
  await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('.notes-footer .notes-document-path')).toBeEmpty();
});

test('journal wikilinks open unique document titles and keep duplicate titles ambiguous',async({page,request})=>{
  const api=await setup(page,request),day='2026-11-09',path='Notes/Imported investigation.md';
  await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from('# CVE-2026-111943\n\n## Analysis\n\nMatched by title.\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await api('PUT','/notes/files?'+new URLSearchParams({path:'journal/'+day+'.md'}),Buffer.from('[[CVE-2026-111943#Analysis|Investigation]]\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.goto('/web/?day='+day);await expect(page.locator('#status')).toHaveText('Up to date');
  const link=page.locator('#today-journal-formatted [data-wiki-reference="CVE-2026-111943#Analysis|Investigation"]');
  await expect(link).toHaveAttribute('data-resolved','true');await link.click();
  await expect(page.locator('#notes-document-title')).toHaveText('CVE-2026-111943');await expect(page.locator('.notes-document-path')).toHaveText(path);await expect(page.locator('#notes-formatted h2')).toHaveText('Analysis');
  await api('PUT','/notes/files?path=Notes%2FDuplicate.md',Buffer.from('# CVE-2026-111943\n\nDifferent investigation.\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.goto('/web/?day='+day);await expect(page.locator('#status')).toHaveText('Up to date');await expect(link).toHaveAttribute('data-resolved','false');
  const dialogs=[];page.on('dialog',dialog=>{dialogs.push(dialog.message());void dialog.dismiss();});await link.click();
  await expect(link).toHaveAttribute('title','Several documents match this link. Use the full path from the collection root.');expect(dialogs).toEqual([]);await expect(page.locator('#today-panel')).toBeVisible();
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

test('title-bar search filters note types, finds folders and keeps results behind its popup',async({page,request})=>{
  const api=await setup(page,request),upload=async(path,text)=>await(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(text),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const ordinary=await upload('Notes/Plain.md','# Plain title\n\nSundial #work.\n');await upload('journal/2020-01-02.md','Sundial #work.\n');await upload('journal/2020-W01.md','Sundial #work.\n');
  const subscription=randomUUID(),eventKey='ab'.repeat(32);await upload(`events/event-${subscription}-${eventKey}.md`,'---\ntimekeeper-event: '+JSON.stringify({subscriptionId:subscription,eventKey,event:{uid:'search-type',recurrenceId:'',title:'Sundial meeting'}})+'\n---\n\nSundial #work.\n');
  const folder=await(await api('POST','/notes/folders',{path:'Notes/Sundial folder'})).json();await page.locator('[data-tab=today]').click();
  await expect.poll(async()=>{const reply=await browserRequest(page).get('/api/v1/notes/search?'+new URLSearchParams({q:'Sundial'}));return reply.status();},{timeout:30000}).toBe(200);
  const panel=await searchNotes(page,'Sundial'),rows=panel.locator('[data-search-note]');await expect(rows).toHaveCount(4);
  for(const [query,count]of [['note:Sundial',1],['journal:Sundial',2],['event:Sundial',1],['event:',1],['tag:#work',4],['file:Notes note:Sundial',1]]){await searchNotes(page,query);await expect(rows).toHaveCount(count);}
  let heldRoot=false,captured,releaseRoot;const rootStarted=new Promise(resolve=>{captured=resolve;}),rootGate=new Promise(resolve=>{releaseRoot=resolve;});
  await page.route('**/api/v1/notes/entries?*',async route=>{if(!new URL(route.request().url()).searchParams.has('parentId')||heldRoot)return route.continue();const reply=await route.fetch();heldRoot=true;captured();await rootGate;await route.fulfill({response:reply});});
  await page.route('**/api/v1/notes/resolve?*',async route=>{if(new URL(route.request().url()).searchParams.get('path')!==folder.path)return route.continue();await rootStarted;releaseRoot();await expect(page.locator('#notes-navigation [role=tree]')).toHaveAttribute('aria-busy','false');await route.continue();});
  try{await searchNotes(page,'file:Sundial');await expect(rows).toHaveCount(1);await expect(rows).toContainText('Notes/Sundial folder');await rows.click();await expect(page.locator('#workspace')).toHaveAttribute('data-view','notes');await expect(page.locator('#notes-navigation [data-id="'+folder.id+'"]')).toHaveAttribute('aria-expanded','true');}
  finally{releaseRoot();await page.unrouteAll({behavior:'wait'});}
  await searchNotes(page,'note:Sundial');const pick=panel.locator('[data-search-note="'+ordinary.id+'"]');await pick.click();const popup=page.locator('#note-popup-editor');await expect(popup.locator('.ProseMirror')).toContainText('Sundial');await expect(panel).toBeVisible();await popup.getByRole('button',{name:'Close',exact:true}).click();await expect(pick).toBeFocused();
  await pick.press('Enter');await expect(popup.locator('.ProseMirror')).toBeVisible();await popup.getByRole('button',{name:'Open in workspace',exact:true}).click();await expect(panel).toBeHidden();await expect(page.locator('#notes-formatted')).toContainText('Sundial');await expect(page.locator('#notes-formatted .ProseMirror')).toBeFocused();
  for(const width of [320,390]){await page.setViewportSize({width,height:568});await expect(page.locator('#titlebar-notes-query')).toBeHidden();const toggle=page.locator('#titlebar-notes-search button');await toggle.focus();await toggle.press('Enter');await expect(page.locator('#notes-search-query')).toBeFocused();await searchNotes(page,'file:Plain.md');await expect(rows).toHaveCount(1);await page.locator('#notes-search-query').press('Escape');await expect(panel).toBeHidden();await expect(toggle).toBeFocused();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}
});

test('a delayed search cannot display the signed-out account in a new session',async({page,request})=>{
  const api=await setup(page,request),entry=await(await api('PUT','/notes/files?path=Notes%2FPrivate.md',Buffer.from('Private owner result.'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  let release,arrive;const held=new Promise(resolve=>{release=resolve;}),started=new Promise(resolve=>{arrive=resolve;});
  await page.route('**/api/v1/notes/search?*',async route=>{if(new URL(route.request().url()).searchParams.get('q')!=='private')return route.continue();arrive();await held;try{await route.fulfill({headers:{'X-Timekeeper-Owner':Buffer.from(entry.ownerId).toString('base64url')},json:{snapshot:'old',items:[{entry,excerpt:{text:'Private owner result.',highlights:[]}}]}});}catch{}});
  try{const panel=await searchNotes(page,'private');await started;await panel.getByRole('button',{name:'Close',exact:true}).click();await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login')).toBeVisible();await setup(page,request);release();await expect.poll(async()=>{const reply=await browserRequest(page).get('/api/v1/notes/search?q=different');return reply.status();},{timeout:30000}).toBe(200);await searchNotes(page,'different');await expect(panel).toContainText('No matching notes.');await expect(panel).not.toContainText('Private owner result.');await expect(page.locator('#owner')).toBeVisible();}
  finally{release();await page.unrouteAll({behavior:'wait'});}
});

test('Notes content search finds body text, keeps relevance order and pages on demand without replacing a draft',async({page,request})=>{
  test.setTimeout(150000);const api=await setup(page,request),word='sparrow';
  for(let index=0;index<55;index++)await api('PUT','/notes/files?'+new URLSearchParams({path:'Notes/Result-'+String(index).padStart(2,'0')+'.md'}),Buffer.from(index===54?'# Sparrow priority\n\nA body match: sparrow.':'# Ordinary '+index+'\n\nA body match: sparrow.'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await api('PUT','/notes/files?path=Notes%2Fbinary.txt',Buffer.from(word),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await expect.poll(async()=>{const reply=await browserRequest(page).get('/api/v1/notes/search?'+new URLSearchParams({q:word}));return reply.status();},{timeout:30000}).toBe(200);
  const navigation=await searchNotes(page,word),rows=navigation.locator('[data-search-note]');
  await expect(page.locator('#notes-search-mode')).toHaveCount(0);await expect(rows).toHaveCount(50);await expect(rows.first()).toContainText('Sparrow priority');
  await expect(page.locator('#notes-navigation').getByRole('button',{name:'Sort notes',exact:true})).toBeEnabled();await expect(rows.first().locator('mark').first()).toContainText(/sparrow/i);
  await navigation.getByRole('button',{name:'More results',exact:true}).focus();await navigation.getByRole('button',{name:'More results',exact:true}).press('Enter');await expect(rows).toHaveCount(55);await expect(rows.nth(50)).toBeFocused();await expect(navigation.getByRole('button',{name:'More results',exact:true})).toBeHidden();
  await page.locator('#refresh').evaluate(button=>button.click());await expect(page.locator('#status')).toHaveText('Up to date');await expect(navigation.locator('#notes-search-results')).toHaveAttribute('aria-busy','false');await expect(rows).toHaveCount(55);await expect(rows.nth(50)).toBeFocused();
  for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
    await navigation.getByRole('button',{name:'Close',exact:true}).click();await page.setViewportSize({width:1440,height:900});await setTheme(page,theme);await page.locator('[data-tab=notes]').click();await searchNotes(page,word);await expect(rows).toHaveCount(50);
    for(const width of [1440,390]){
      await page.setViewportSize({width,height:900});
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#notes-search-panel').analyze()).violations).toEqual([]);
      await captureScreenshot(page,{path:`test-results/notes-content-search-${theme}-${width}.png`});
    }
  }
  await page.setViewportSize({width:1440,height:900});
  await rows.first().click();const popup=page.locator('#note-popup-editor');await expect(popup.locator('.ProseMirror')).toBeVisible();await expect(navigation).toBeVisible();await popup.getByRole('button',{name:'Open in workspace',exact:true}).click();await expect(navigation).toBeHidden();const source=await notesSource(page);await searchNotes(page,word);await expect(source).toHaveValue(/Sparrow priority/);
  await page.route('**/api/v1/notes/entries/*/content',route=>route.request().method()==='PUT'?route.abort('internetdisconnected'):route.continue());
  await source.fill('# Sparrow priority\n\nUnsaved private draft.');await searchNotes(page,'absentword');await expect(navigation).toContainText('No matching notes.');await expect(source).toHaveValue('# Sparrow priority\n\nUnsaved private draft.');
  await searchNotes(page,word);await expect(rows).toHaveCount(50);await expect(rows.first().locator('mark').first()).toContainText(/sparrow/i);
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
  const navigation=await searchNotes(page,'old');await started;
  await searchNotes(page,'fresh');await expect(navigation.locator('.notes-search-excerpt')).toHaveText('Fresh body.');releaseOld();await expect(navigation.locator('.notes-search-excerpt')).toHaveText('Fresh body.');
  await navigation.getByRole('button',{name:'More results',exact:true}).focus();await navigation.getByRole('button',{name:'More results',exact:true}).press('Enter');await expect(navigation.getByRole('button',{name:'Search again',exact:true})).toBeFocused();await expect(navigation).toContainText('Search results changed or expired. Search again');await expect(navigation.locator('.notes-search-excerpt')).toHaveCount(0);
  await searchNotes(page,'unavailable');await expect(navigation).toContainText('Content search is being prepared or is unavailable');await expect(navigation.getByRole('button',{name:'Search again',exact:true})).toBeVisible();
  await searchNotes(page,'file:Search.md');await expect(navigation.locator('[data-search-note]')).toContainText('Notes/Search.md');
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
  const results=await searchNotes(page,'body');await expect(results.locator('.notes-search-excerpt')).toHaveText('Body result.');
  const more=results.getByRole('button',{name:'More results',exact:true});await more.focus();await more.press('Enter');await started;await source.focus();releaseMore();
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


test('Notes completes and follows an internal document link with actual private collection data',async({page,request})=>{
  const api=await setup(page,request);for(const [path,body]of [['Notes/Target.md','# Target\n\nPrivate target body.'],['Notes/Links.md','']])await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  const navigation=page.locator('#notes-navigation');await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(navigation,'Notes').click();await noteEntry(navigation,'Notes/Links.md').click();
  const rich=page.locator('#notes-formatted .ProseMirror');await expect(rich).toBeVisible();await rich.click();await page.keyboard.type('[[Tar');const options=page.locator('#notes-panel .markdown-suggestions').getByRole('option');await expect(options).toHaveCount(1);await expect(options.first()).toContainText('Notes/Target.md');await options.first().click();
  await saveMarkdown(page.locator('#notes-panel'));await expect(page.locator('.notes-footer > [role=status]')).toHaveText('Saved');const link=await(await api('GET','/notes/resolve?path=Notes%2FLinks.md')).json();expect((await(await api('GET','/notes/entries/'+link.id+'/content')).text()).trim()).toBe('[[Notes/Target]]');
  await rich.locator('[data-wiki-reference="Notes/Target"]').click();await expect(page.locator('#notes-document-title')).toHaveText('Target');await expect(page.locator('#notes-formatted')).toContainText('Private target body.');
});

test('bookmarks persist in Notes without changing a rejected body draft, bytes or saved dates',async({page,request})=>{
  const api=await setup(page,request),path='Notes/Bookmark.md',body='# Bookmark\r\n\r\nExact spaces  \r\n';
  const entry=await(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),path).click();
  const button=page.locator('#notes-panel .note-bookmark');await expect(button).toBeEnabled();await page.mouse.move(0,0);await expect(button).toHaveCSS('opacity','0');await button.hover();await expect(button.locator('.bi-bookmark-plus')).toBeVisible();await button.click();await expect(button).toHaveAttribute('aria-pressed','true');await page.mouse.move(0,0);await expect(button.locator('.bi-bookmark')).toBeVisible();await expect(noteEntry(page.locator('#notes-navigation'),path).locator('.bi-bookmark')).toBeVisible();
  await page.reload();await page.locator('[data-tab=notes]').click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),path).click();await expect(button).toHaveAttribute('aria-pressed','true');
  await page.route('**/api/v1/notes/entries/'+entry.id+'/content',route=>route.request().method()==='PUT'?route.fulfill({status:412,contentType:'application/json',body:JSON.stringify({error:{code:'STALE_VERSION',message:'Concurrent edit'}})}):route.continue());
  const source=await notesSource(page);await source.fill('# Unsaved bookmark draft\n');await saveMarkdown(page.locator('#notes-panel'),{wait:false});await expect(page.locator('.notes-footer > [role=status]')).toContainText('changed');
  await expect(button).toBeEnabled();await button.hover();await expect(button.locator('.bi-bookmark-x')).toBeVisible();await button.press('Enter');await expect(button).toHaveAttribute('aria-pressed','false');await expect(source).toHaveValue('# Unsaved bookmark draft\n');
  const metadata=await(await api('GET','/notes/entries/'+entry.id)).json();expect(metadata.createdAt).toBe(entry.createdAt);expect(metadata.modifiedAt).toBe(entry.modifiedAt);expect(metadata.etag).toBe(entry.etag);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(body);expect(metadata.bookmarked).toBe(false);
});

test('Today bookmarks an empty day and Calendar shows its bookmark without creating a note',async({page,request})=>{
  const api=await setup(page,request),day='2026-11-08';await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(day);
  const button=page.locator('#today-note-editor .note-bookmark');await expect(button).toBeEnabled();await button.focus();await button.press('Enter');await expect(button).toHaveAttribute('aria-pressed','true');
  const original=await(await api('GET','/day-notes/'+day+'/content')).json();expect(original.body).toBe('');expect(original.version).toBe(0);const summary=await(await api('GET','/day-notes?'+new URLSearchParams({fromDate:day,toDate:day}))).json();expect(summary.items.find(item=>item.date===day).bookmarked).toBe(true);
  await page.locator('[data-tab=calendar]').click();await page.locator('#calendar-date').fill(day);const calendarButton=page.locator('[data-week-note-date="'+day+'"]');await expect(calendarButton.locator('.bi-journal-bookmark')).toBeVisible();
  await page.locator('[data-tab=today]').click();await expect(button).toHaveAttribute('aria-pressed','true');await page.setViewportSize({width:390,height:844});await button.focus();await expect(button).toBeInViewport();await captureScreenshot(page,{path:test.info().outputPath('bookmark-today-phone.png')});await button.press('Space');await expect(button).toHaveAttribute('aria-pressed','false');expect((await(await api('GET','/day-notes/'+day+'/content')).json()).version).toBe(0);
});

test('a stale bookmark write reloads metadata and preserves the note draft',async({page,request})=>{
  const api=await setup(page,request),path='Notes/Stale bookmark.md';const entry=await(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from('Saved body'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),path).click();const button=page.locator('#notes-panel .note-bookmark');await expect(button).toBeEnabled();
  const markPath='/notes/bookmark?'+new URLSearchParams({sourceType:entry.sourceType,sourceKey:entry.sourceKey});let raced=false;
  await page.route('**/api/v1/notes/bookmark?**',async route=>{if(route.request().method()==='PUT'&&!raced){raced=true;const before=await api('GET',markPath);await api('PUT',markPath,{bookmarked:true},{'If-Match':before.headers().etag});}return route.continue();});
  const source=await notesSource(page);await source.fill('Draft remains private');const rejected=page.waitForResponse(reply=>reply.request().method()==='PUT'&&reply.url().includes('/notes/bookmark?'));await button.click();expect((await rejected).status()).toBe(412);await expect(button).toHaveAttribute('aria-pressed','true');await expect(source).toHaveValue('Draft remains private');expect((await(await api('GET',markPath)).json()).bookmarked).toBe(true);
});

test('touch users can add a bookmark on a narrow Today editor without hover',async({page,request,browser})=>{
  await setup(page,request);const phone=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true,storageState:await page.context().storageState(),viewport:{width:390,height:844},hasTouch:true,isMobile:true});
  try{
    const mobile=await phone.newPage();await mobile.goto('/web/');await expect(mobile.locator('#status')).toHaveText('Up to date');await mobile.locator('[data-tab=today]').click();await mobile.locator('#today-date').fill('2026-11-10');const button=mobile.locator('#today-note-editor .note-bookmark');await expect(button).toBeEnabled();await expect(button).toHaveCSS('opacity','1');await button.tap();await expect(button).toHaveAttribute('aria-pressed','true');await captureScreenshot(mobile,{path:test.info().outputPath('bookmark-touch-phone.png')});
  }finally{await phone.close();}
});

test('a delayed bookmark response cannot paint the next selected note',async({page,request})=>{
  const api=await setup(page,request);const first=await(await api('PUT','/notes/files?path=Notes%2FFirst.md',Buffer.from('First note'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();await api('PUT','/notes/files?path=Notes%2FSecond.md',Buffer.from('Second note'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  const markPath='/notes/bookmark?'+new URLSearchParams({sourceType:first.sourceType,sourceKey:first.sourceKey}),before=await api('GET',markPath);await api('PUT',markPath,{bookmarked:true},{'If-Match':before.headers().etag});
  let release,arrive,deliver;const gate=new Promise(resolve=>release=resolve),arrived=new Promise(resolve=>arrive=resolve),delivered=new Promise(resolve=>deliver=resolve);let delayed=false;
  await page.route('**/api/v1/notes/bookmark?**',async route=>{
    if(new URL(route.request().url()).searchParams.get('sourceKey')!==first.sourceKey||delayed)return route.continue();delayed=true;const response=await route.fetch();arrive();await gate;try{await route.fulfill({response});}finally{deliver();}
  });
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/First.md').click();await arrived;await noteEntry(page.locator('#notes-navigation'),'Notes/Second.md').click();const button=page.locator('#notes-panel .note-bookmark');await expect(button).toBeEnabled();await expect(button).toHaveAttribute('aria-pressed','false');release();await delivered;await expect(button).toHaveAttribute('aria-pressed','false');await expect(page.locator('#notes-document-title')).toHaveText('Second');
});

test('refresh updates a bookmark changed in another client while preserving an unsaved draft',async({page,request})=>{
  const api=await setup(page,request),path='Notes/Refreshed bookmark.md';const entry=await(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from('Saved body'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),path).click();const button=page.locator('#notes-panel .note-bookmark');await expect(button).toBeEnabled();await expect(button).toHaveAttribute('aria-pressed','false');
  await page.route('**/api/v1/notes/entries/'+entry.id+'/content',route=>route.request().method()==='PUT'?route.fulfill({status:412,contentType:'application/json',body:JSON.stringify({error:{code:'STALE_VERSION',message:'Concurrent edit'}})}):route.continue());
  const source=await notesSource(page);await source.fill('Keep my unsaved draft');const markPath='/notes/bookmark?'+new URLSearchParams({sourceType:entry.sourceType,sourceKey:entry.sourceKey}),before=await api('GET',markPath);await api('PUT',markPath,{bookmarked:true},{'If-Match':before.headers().etag});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await expect(button).toHaveAttribute('aria-pressed','true');await expect(noteEntry(page.locator('#notes-navigation'),path).locator('.bi-bookmark')).toBeVisible();await expect(source).toHaveValue('Keep my unsaved draft');expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('Saved body');
});

test('missing wikilinks confirm creation and open a popup without overwriting existing notes',async({page,request})=>{
  const api=await setup(page,request),body='# Source\n\n[[A new note|New document]] and [[Other/New note#Heading|Nested]] and [[New folder/Sub/New page]].\n';
  await api('PUT','/notes/files?path=Notes%2FSource.md',Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();await noteEntry(page.locator('#notes-navigation'),'Notes/Source.md').click();
  const link=page.locator('#notes-formatted [data-wiki-reference="A new note|New document"]');await expect(link).toHaveAttribute('data-resolved','false');
  const declined=page.waitForEvent('dialog').then(async dialog=>{expect(dialog.message()).toContain('Notes/A new note.md');await dialog.dismiss();});await link.click();await declined;await expect(page.locator('#note-popup-editor')).not.toBeVisible();
  expect((await api('GET','/notes/entries?query=A+new+note')).ok()).toBe(true);expect((await(await api('GET','/notes/entries?query=A+new+note')).json()).items).toEqual([]);
  const accepted=page.waitForEvent('dialog').then(dialog=>dialog.accept());await link.click();await accepted;const popup=page.locator('#note-popup-editor');await expect(popup).toBeVisible();await expect(page.locator('#note-popup-title')).toHaveText('A new note');await expect(page.locator('#notes-document-title')).toHaveText('Source');
  await expect(popup.locator('#note-popup-formatted .ProseMirror')).toBeVisible();
  await popup.getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#note-popup-body').fill('# New document\n\nCreated through the wikilink.\n');await saveMarkdown(popup,{close:true});
  const created=await(await api('GET','/notes/resolve?path=Notes%2FA%20new%20note.md')).json();expect(await(await api('GET','/notes/entries/'+created.id+'/content')).text()).toBe('# New document\n\nCreated through the wikilink.\n');
  const source=await(await api('GET','/notes/resolve?path=Notes%2FSource.md')).json();expect(await(await api('GET','/notes/entries/'+source.id+'/content')).text()).toBe(body);
  // Another client can create a target after the unresolved link was displayed.
  await api('POST','/notes/folders',{path:'Other'});await api('PUT','/notes/files?path=Other%2FNew%20note.md',Buffer.from('# Heading\n\nConcurrent content.\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-formatted [data-wiki-reference="Other/New note#Heading|Nested"]').click();await expect(page.locator('#notes-document-title')).toHaveText('Heading');
  await noteEntry(page.locator('#notes-navigation'),'Notes/Source.md').click();const nested=page.locator('#notes-formatted [data-wiki-reference="New folder/Sub/New page"]');
  const creation=page.waitForEvent('dialog').then(async dialog=>{expect(dialog.message()).toContain('New folder/Sub/New page.md');await dialog.accept();});await nested.click();await creation;await expect(popup).toBeVisible();await expect(page.locator('#note-popup-title')).toHaveText('New page');
  expect((await(await api('GET','/notes/resolve?path=New%20folder%2FSub%2FNew%20page.md')).json()).kind).toBe('MARKDOWN');
});

test('read-only clients can open and maximize note popups without creating missing links',async({page,request,browser})=>{
  const api=await setup(page,request),entry=await(await api('PUT','/notes/files?path=Notes%2FReadonly.md',Buffer.from('# Read only\n\n[[Never create this]].\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const token=await(await api('POST','/auth/tokens',{name:'Read-only popup','permissions':['read'],'expiresAt':null})).json();
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true,locale:'en-GB'});
  try{
    const reader=await context.newPage(),dialogs=[],writes=[];reader.on('dialog',dialog=>{dialogs.push(dialog.message());void dialog.dismiss();});reader.on('request',request=>{if(request.method()!=='GET'&&new URL(request.url()).pathname.startsWith('/api/v1/notes/'))writes.push(request.method());});
    await reader.goto('/web/');await reader.locator('#auth-mode').selectOption('token');await reader.locator('#token').fill(token.token);await reader.locator('#login-form button[type=submit]').click();await expect(reader.locator('#workspace')).toBeVisible();
    await reader.locator(`[data-recent-note="${entry.id}"]`).click();const popup=reader.locator('#note-popup-editor');await expect(popup).toBeVisible();await expect(reader.locator('#note-popup-formatted .ProseMirror')).toHaveAttribute('aria-readonly','true');
    await reader.locator('#note-popup-formatted [data-wiki-reference="Never create this"]').click();await popup.getByRole('button',{name:'Open in workspace',exact:true}).click();await expect(reader.locator('#notes-document-title')).toHaveText('Read only');
    expect(dialogs).toEqual([]);expect(writes).toEqual([]);expect((await(await api('GET','/notes/entries?query=Never+create+this')).json()).items).toEqual([]);
  }finally{await context.close();}
});
