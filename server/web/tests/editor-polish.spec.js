import {saveMarkdown} from './editor-interactions.js';
import {clickNoteAction,noteActions} from './editor-interactions.js';
import {noteEntry} from './note-entry.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {captureScreenshot} from './screenshots.js';

const token=process.env.TIMEKEEPER_WEB_TOKEN;
test.beforeAll(async({request})=>{
 const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin},status=await request.get('/api/v1/auth/status',{headers});
 if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function setup(page,request){
 const username='editor-'+randomUUID().slice(0,8),password='Private editor preferences password';
 const created=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${token}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});expect(created.status()).toBe(201);
 await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
 const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
 const api=async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;};
 await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});return api;
}
async function open(page,path){await page.locator('[data-tab=notes]').click();const tree=page.locator('#notes-navigation');await noteEntry(tree,'Notes').click();await noteEntry(tree,path).click();await expect(noteEntry(tree,path).locator('..')).toHaveAttribute('aria-selected','true');}
const upload=(api,path,body)=>api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'});

test('Notes opens formatted with main document actions and a compact keyboard-accessible resource menu',async({page,request})=>{
 const api=await setup(page,request);await upload(api,'Notes/Toolbar.md','# Toolbar\n\nText.\n');await upload(api,'Notes/asset.bin','binary');await open(page,'Notes/Toolbar.md');
 const panel=page.locator('#notes-panel'),toolbar=panel.locator('.markdown-toolbar'),trigger=toolbar.getByRole('button',{name:'Note tools',exact:true});
 await expect(panel.locator('.ProseMirror')).toBeVisible();await expect(toolbar.getByRole('button',{name:'Formatted',exact:true})).toHaveAttribute('aria-pressed','true');await expect(panel.locator('#notes-body')).toBeHidden();await expect(panel.getByRole('button',{name:'Save',exact:true})).toHaveCount(0);
 await expect(toolbar.getByRole('button',{name:'Undo',exact:true})).toHaveCount(0);await expect(toolbar.getByRole('button',{name:'Redo',exact:true})).toHaveCount(0);await expect(toolbar.getByRole('menuitem')).toHaveCount(0);
 await expect(trigger).toBeEnabled();
 for(const [width,buttonSize]of [[1440,14],[1440,18],[320,14]]){
  await test.step(`Resource menu at ${width}px with ${buttonSize}px buttons`,async()=>{
  await page.setViewportSize({width,height:900});await page.evaluate(size=>document.documentElement.style.setProperty('--font-button',size+'px'),buttonSize);await page.mouse.move(0,0);await trigger.focus();await trigger.press('ArrowDown');const menu=page.getByRole('menu',{name:'Note tools',exact:true});await expect(menu.getByRole('menuitem').first()).toBeFocused();await expect(page.locator('#notes-main-actions').getByRole('button',{name:'Rename or move',exact:true})).toBeInViewport();await expect(page.locator('#notes-main-actions').getByRole('button',{name:'Note revisions',exact:true})).toBeInViewport();await expect(menu.getByRole('menuitem',{name:'Rename or move',exact:true})).toHaveCount(0);
  expect((await new AxeBuilder({page}).include('#notes-panel').analyze()).violations).toEqual([]);
  if(width===1440)await test.step('Tooltip over the exposed note icon',async()=>{
   // A wrapped toolbar's popover can cover the row center with Linux fonts.
   await noteEntry(page.locator('#notes-navigation'),'Notes/Toolbar.md').hover({position:{x:8,y:8}});await expect(page.getByRole('tooltip')).toBeVisible();
  },{timeout:10000});
  await menu.getByRole('menuitem').first().press('Escape');await expect(trigger).toBeFocused();await expect(menu).toBeHidden();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  });
 }
 await captureScreenshot(page,{path:'test-results/notes-compact-toolbar.png'});
 await toolbar.getByRole('button',{name:'Markdown source',exact:true}).click();await expect(page.locator('#notes-body')).toHaveAccessibleName('Note body');await expect(page.locator('#notes-body').locator('..').locator('label')).toHaveClass(/visually-hidden/);await page.locator('#notes-body').focus();await expect(page.locator('#notes-body')).toHaveCSS('box-shadow','none');
 await toolbar.getByRole('button',{name:'Formatted',exact:true}).click();await page.locator('#notes-formatted .ProseMirror').click();await expect(page.locator('#notes-formatted')).toHaveCSS('outline-style','none');
 await page.setViewportSize({width:1440,height:900});await noteEntry(page.locator('#notes-navigation'),'Notes/asset.bin').click();await expect(page.locator('#notes-panel .notes-file-tools').getByRole('button',{name:'Note tools',exact:true})).toBeVisible();await clickNoteAction(page,panel,'Rename or move');await page.getByRole('dialog',{name:'Rename or move',exact:true}).getByRole('button',{name:'Cancel',exact:true}).click();
});

test('coloured revision hunks are literal, and deleting a selected revision preserves the live note and draft',async({page,request})=>{
 const api=await setup(page,request),before=Array.from({length:22},(_,n)=>'Line '+n),after=[...before];before[2]='Old first';before[18]='Old last';after[2]='New first';after[18]='New last';
 let entry=await(await upload(api,'Notes/Revisions.md',before.join('\n'))).json();const current=await api('PUT','/notes/entries/'+entry.id+'/content',Buffer.from(after.join('\n')),{'Content-Type':'application/octet-stream','If-Match':entry.etag});const guard=current.headers().etag;
 await open(page,'Notes/Revisions.md');await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();const draft=after.join('\n')+'\n<img src=x onerror="window.diffExecuted=true">';await page.locator('#notes-body').fill(draft);
 await clickNoteAction(page,page.locator('#notes-panel'),'Note revisions');const dialog=page.getByRole('dialog',{name:'Note revisions',exact:true});await dialog.getByRole('button',{name:/Version 1/}).click();await expect(dialog.locator('.markdown-diff-hunk')).toHaveCount(2);await expect(dialog.locator('.markdown-diff-added')).toContainText(['New first','New last']);await expect(dialog.locator('.markdown-diff-removed')).toContainText(['Old first','Old last']);
 await expect(dialog.locator('.markdown-revision-diff :is(img,script)')).toHaveCount(0);expect(await page.evaluate(()=>window.diffExecuted)).toBeUndefined();const colors=await dialog.locator('[data-diff]').evaluateAll(nodes=>Object.fromEntries(nodes.filter(node=>node.dataset.diff!=='context').map(node=>[node.dataset.diff,getComputedStyle(node).backgroundColor])));expect(colors.added).not.toBe(colors.removed);
 await captureScreenshot(page,{path:'test-results/revision-coloured-diff.png'});expect((await new AxeBuilder({page}).include('#notes-panel .markdown-history-dialog[open]').analyze()).violations).toEqual([]);const comparison=dialog.getByRole('region',{name:'Revision comparison',exact:true});await comparison.focus();await comparison.press('PageDown');await expect.poll(()=>comparison.evaluate(node=>node.scrollTop)).toBeGreaterThan(0);await dialog.evaluate(node=>node.close());await clickNoteAction(page,page.locator('#notes-panel'),'Note revisions');await expect(dialog).toBeVisible();const deletedRevisionId=await dialog.getByRole('button',{name:/Version 1/}).getAttribute('data-revision-id');await dialog.getByRole('button',{name:/Version 1/}).click();page.once('dialog',dialog=>dialog.accept());await dialog.getByRole('button',{name:'Delete selected revision',exact:true}).click();await expect(dialog).toContainText('Revision deleted.');await expect(dialog.getByRole('button',{name:'Delete selected revision',exact:true})).toBeDisabled();await dialog.getByRole('button',{name:'Close',exact:true}).click();
 await expect(page.locator('#notes-body')).toHaveValue(draft);await saveMarkdown(page.locator('#notes-panel'));const live=await api('GET','/notes/entries/'+entry.id+'/content');expect(await live.text()).toBe(draft);expect(live.headers().etag).not.toBe(guard);const query=new URLSearchParams({sourceType:entry.sourceType,sourceId:entry.sourceId});expect((await(await api('GET','/notes/revisions?'+query)).json()).items.some(item=>item.id===deletedRevisionId)).toBe(false);
});

test('cursor preferences preserve drafts and ordinary Backspace in source and formatted modes',async({page,request})=>{
 const api=await setup(page,request);await upload(api,'Notes/Cursor.md','# Saved\n');await page.evaluate(()=>location.hash='#account/notes');const settings=page.locator('#account-content section').filter({has:page.getByRole('heading',{name:'Notes settings',exact:true})});await settings.getByLabel('Editor cursor',{exact:true}).selectOption('solid');await settings.getByRole('button',{name:'Save',exact:true}).click();await expect(settings).toContainText('Notes settings saved.');await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await open(page,'Notes/Cursor.md');
 const panel=page.locator('#notes-panel');await panel.getByRole('button',{name:'Markdown source',exact:true}).click();const source=page.locator('#notes-body'),draft='Draft one\n\nDraft two across several words.';await source.fill(draft);
 const change=async shape=>{const reply=await api('GET','/notes/settings');await api('PUT','/notes/settings',{editorCursor:shape},{'If-Match':reply.headers().etag});await page.evaluate(()=>document.dispatchEvent(new Event('timekeeper:notes-changed')));await expect(page.locator('html')).toHaveAttribute('data-editor-cursor',shape);};
 for(const shape of ['default','line','underscore','solid']){
  await change(shape);await source.focus();await source.press('End');await source.press('z');await source.press('Backspace');await expect(source).toHaveValue(draft);
  const native=await page.evaluate(shape=>CSS.supports('caret-shape',({default:'auto',line:'bar',underscore:'underscore',solid:'block'})[shape]),shape),caret=panel.locator('.markdown-caret');
  if(native)await expect(source).toHaveCSS('caret-shape',({default:'auto',line:'bar',underscore:'underscore',solid:'block'})[shape]);else if(['underscore','solid'].includes(shape)){
   await expect(caret).toBeVisible();expect(await caret.evaluate(node=>{const a=node.getBoundingClientRect(),b=document.querySelector('#notes-body').getBoundingClientRect();return a.width>0&&a.height>0&&a.top>=b.top&&a.bottom<=b.bottom;})).toBe(true);
   await source.press('ControlOrMeta+A');await expect(caret).toBeHidden();await source.press('ArrowRight');await expect(caret).toBeVisible();await source.evaluate(node=>node.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true})));await expect(caret).toBeHidden();await source.evaluate(node=>node.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true})));await expect(caret).toBeVisible();
  }else await expect(caret).toBeHidden();
  await expect(panel.getByRole('button',{name:'Save',exact:true})).toHaveCount(0);
 }
 await panel.getByRole('button',{name:'Formatted',exact:true}).click();const rich=page.locator('#notes-formatted .ProseMirror');await rich.locator('p').last().click();await rich.press('End');const original=await rich.innerText();await rich.press('z');await rich.press('Backspace');await expect(rich).toHaveText(original.replace(/\s+/g,' '),{useInnerText:true});
 if(!await page.evaluate(()=>CSS.supports('caret-shape','block')))await expect(panel.locator('.markdown-caret')).toBeVisible();
 await panel.locator('[data-command=tasks]').click();await rich.press('End');await rich.press('Enter');await expect(rich.locator('input[type=checkbox]')).toHaveCount(2);await page.keyboard.type('cursor');await page.keyboard.press('Backspace');await expect(rich.locator('li').last()).toContainText('curso');
 if(!await page.evaluate(()=>CSS.supports('caret-shape','block')))await expect(panel.locator('.markdown-caret')).toBeVisible();await page.setViewportSize({width:1440,height:900});await page.evaluate(()=>scrollTo(0,0));await panel.locator('.markdown-caret').evaluate(node=>{for(const animation of node.getAnimations()){animation.pause();animation.currentTime=0;}});await captureScreenshot(page,{path:'test-results/editor-cursor-block.png',caret:'initial'});
 expect((await new AxeBuilder({page}).include('#notes-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
});

test('late cursor preferences cannot cross an account boundary or logout',async({page})=>{
 await page.goto('/web/');await page.evaluate(async()=>{const {editorPreferences}=await import('/web/editor-preferences.js');let finish;editorPreferences.connect({owner:'old',request:()=>new Promise(resolve=>finish=resolve)});editorPreferences.connect({owner:'current',request:async()=>({data:{ownerId:'current',version:2,editorCursor:'underscore'}})});await Promise.resolve();finish({data:{ownerId:'old',version:9,editorCursor:'solid'}});await Promise.resolve();window.preferenceTest=editorPreferences;});await expect(page.locator('html')).toHaveAttribute('data-editor-cursor','underscore');await page.evaluate(()=>window.preferenceTest.reset());await expect(page.locator('html')).not.toHaveAttribute('data-editor-cursor');
});

test('read-only accounts can inspect revisions but cannot delete them or change cursor preferences',async({page,request})=>{
 const path='Read-only-history-'+randomUUID()+'.md',headers={Authorization:`Bearer ${token}`};expect((await request.get('/api/v1/notes',{headers})).status()).toBe(200);
 const first=await request.put('/api/v1/notes/files?'+new URLSearchParams({path}),{headers:{...headers,'Content-Type':'application/octet-stream','If-None-Match':'*'},data:Buffer.from('Before')});expect(first.status()).toBe(201);const entry=await first.json();expect((await request.put('/api/v1/notes/entries/'+entry.id+'/content',{headers:{...headers,'Content-Type':'application/octet-stream','If-Match':entry.etag},data:Buffer.from('After')})).status()).toBe(200);
 await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_READ_TOKEN);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=notes]').click();await noteEntry(page.locator('#notes-navigation'),path).click();
 await clickNoteAction(page,page.locator('#notes-panel'),'Note revisions');const dialog=page.getByRole('dialog',{name:'Note revisions',exact:true});await dialog.getByRole('button',{name:/Version 1/}).click();await expect(dialog.locator('.markdown-diff-removed')).toContainText('Before');await expect(dialog.getByRole('button',{name:'Delete selected revision',exact:true})).toBeDisabled();await expect(dialog.getByRole('button',{name:'Use as unsaved draft',exact:true})).toBeDisabled();await dialog.getByRole('button',{name:'Close',exact:true}).click();
 await page.evaluate(()=>location.hash='#account/notes');await expect(page.getByLabel('Editor cursor',{exact:true})).toBeDisabled();
});
