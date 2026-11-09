import {saveMarkdown} from './editor-interactions.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {captureScreenshot} from './screenshots.js';
import {noteEntry} from './note-entry.js';

async function setup(page,request){
  const username='navigator-'+randomUUID().slice(0,8),password='Private navigator test passphrase';
  const headers={Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`};
  expect((await request.post('/api/v1/admin/users',{headers,data:{username,password,email:username+'@example.invalid',displayName:username,administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,extra={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...extra}});expect(reply.ok(),method+' '+path+': '+reply.status()).toBe(true);return reply;};
  await api('GET','/notes');const folder=await(await api('POST','/notes/folders',{path:'Notes'})).json();
  await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-title')).toBeVisible();return {api,folder};
}
const put=(api,path,text='Body.')=>api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(text),{'Content-Type':'application/octet-stream','If-None-Match':'*'});

test('a folder remains clickable when a catalog reply arrives between pointer up and click',async({page,request})=>{
  const {api}=await setup(page,request),navigation=page.locator('#notes-navigation');await put(api,'Notes/Pointer.md','Pointer gap body.');await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await expect(navigation.locator('[role=tree]')).toHaveAttribute('aria-busy','false');
  let held=false,release;const pending=new Promise(resolve=>{release=resolve;});await page.route('**/api/v1/notes/entries?*',async route=>{if(held)return route.continue();const reply=await route.fetch();held=true;await pending;await route.fulfill({response:reply});});
  try{
    await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await expect.poll(()=>held).toBe(true);const folder=noteEntry(navigation,'Notes'),original=await folder.elementHandle();
    await folder.dispatchEvent('pointerdown',{button:0,pointerId:1});await folder.dispatchEvent('pointerup',{button:0,pointerId:1});release();await expect(navigation.locator('[role=tree]')).toHaveAttribute('aria-busy','false');expect(await original.evaluate(node=>node.isConnected)).toBe(true);
    await original.evaluate(node=>node.click());await expect(noteEntry(navigation,'Notes/Pointer.md')).toBeVisible();
  }finally{release();await page.evaluate(()=>window.dispatchEvent(new PointerEvent('pointercancel')));await page.unrouteAll({behavior:'wait'});}
});

test('a folder click survives metadata refresh between pointer down and pointer up',async({page,request})=>{
  const {api}=await setup(page,request),navigation=page.locator('#notes-navigation');await put(api,'Notes/Click.md','Folder click body.');
  await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await expect(navigation.locator('[role=tree]')).toHaveAttribute('aria-busy','false');
  const folder=noteEntry(navigation,'Notes');let release;
  const pending=new Promise(resolve=>{release=resolve;});await page.route('**/api/v1/me',async route=>{await pending;await route.continue();});
  try{
    await folder.hover();await page.mouse.down();const original=await folder.elementHandle();await page.evaluate(()=>document.dispatchEvent(new CustomEvent('timekeeper:notes-changed')));
    await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','true');release();await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');await expect(navigation.locator('[role=tree]')).toHaveAttribute('aria-busy','false');
    expect(await original.evaluate(node=>node.isConnected)).toBe(true);await page.mouse.up();await expect(noteEntry(navigation,'Notes/Click.md')).toBeVisible();
  }finally{release();await page.mouse.up();await page.unrouteAll({behavior:'wait'});}
});

test('dragging a document into a folder preserves its draft, identity and content',async({page,request})=>{
  const {api}=await setup(page,request),navigation=page.locator('#notes-navigation');
  await api('POST','/notes/folders',{path:'Notes/Archive'});const entry=await(await put(api,'Notes/Review.md','Original body.\n')).json();
  await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await navigation.getByRole('button',{name:'Notes',exact:true}).click();
  const source=noteEntry(navigation,'Notes/Review.md'),target=noteEntry(navigation,'Notes/Archive');await source.click();
  await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();const draft='Preserved drag-and-drop draft.\n';await page.locator('#notes-body').fill(draft);
  await expect(source).toHaveAttribute('draggable','true');
  const from=await source.boundingBox(),to=await target.boundingBox();await page.mouse.move(from.x+20,from.y+from.height/2);await page.mouse.down();await page.mouse.move(from.x+40,from.y+from.height/2,{steps:5});
  await page.evaluate(()=>document.dispatchEvent(new CustomEvent('timekeeper:notes-changed')));
  await page.mouse.move(to.x+20,to.y+to.height/2,{steps:10});await page.mouse.move(to.x+21,to.y+to.height/2);await page.mouse.up();
  await expect(noteEntry(navigation,'Notes/Archive/Review.md')).toBeVisible();await expect(page.locator('#notes-document-title')).toHaveText('Review');await expect(page.locator('#notes-body')).toHaveValue(draft);
  const moved=await(await api('GET','/notes/entries/'+entry.id)).json();expect(moved.path).toBe('Notes/Archive/Review.md');expect(moved.id).toBe(entry.id);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(draft);
  await expect(page.locator('#notes-panel').getByText('Moved to Notes/Archive/Review.md.',{exact:true})).toHaveAttribute('role','status');
  await navigation.getByRole('button',{name:'Notes',exact:true}).click();await navigation.getByRole('button',{name:'Notes',exact:true}).click();
  await expect(noteEntry(navigation,'Notes/Archive/Review.md')).toBeVisible();
  const movedSource=noteEntry(navigation,'Notes/Archive/Review.md');await movedSource.click();await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();await expect(page.locator('#notes-body')).toHaveValue(draft);
  await expect(movedSource).toHaveAttribute('draggable','true');const box=await navigation.boundingBox();await movedSource.dragTo(navigation,{targetPosition:{x:10,y:box.height-10}});
  await expect(noteEntry(navigation,'Review.md')).toBeVisible();expect((await(await api('GET','/notes/entries/'+entry.id)).json()).path).toBe('Review.md');await expect(page.locator('#notes-body')).toHaveValue(draft);
});

test('a conflicting document drop cannot replace either file',async({page,request})=>{
  const {api}=await setup(page,request),navigation=page.locator('#notes-navigation');await api('POST','/notes/folders',{path:'Notes/Archive'});
  const source=await(await put(api,'Notes/Review.md','Source stays intact.')).json(),target=await(await put(api,'Notes/Archive/Review.md','Destination stays intact.')).json();
  await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await navigation.getByRole('button',{name:'Notes',exact:true}).click();
  await expect(page.locator('#notes-document-title')).toHaveText('Notes');await expect(navigation.locator('[role=tree]')).toHaveAttribute('aria-busy','false');
  await expect(noteEntry(navigation,source.path)).toHaveAttribute('draggable','true');await noteEntry(navigation,source.path).dragTo(noteEntry(navigation,'Notes/Archive'));
  await expect(page.locator('#notes-panel > .alert-danger')).toBeVisible();
  await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await expect(navigation.locator('[role=tree]')).toHaveAttribute('aria-busy','false');await expect(page.locator('#notes-panel > .alert-danger')).toBeVisible();
  expect((await(await api('GET','/notes/entries/'+source.id)).json()).path).toBe(source.path);
  for(const [entry,text] of [[source,'Source stays intact.'],[target,'Destination stays intact.']])expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(text);
});

test('native journal documents retain their folder associations when organizing notes',async({page,request})=>{
  const {api}=await setup(page,request),navigation=page.locator('#notes-navigation');
  for(const name of ['2026-11-02.md','2026-W45.md'])await put(api,'journal/'+name,'Canonical journal body.');
  await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await noteEntry(navigation,'journal').click();
  for(const name of ['2026-11-02.md','2026-W45.md'])await expect(noteEntry(navigation,'journal/'+name)).toHaveAttribute('draggable','false');
});

test('a read-only Notes session cannot drag or drop a document into another folder',async({page,request,browser})=>{
  const {api}=await setup(page,request);await api('POST','/notes/folders',{path:'Notes/Archive'});const entry=await(await put(api,'Notes/Read only.md','Read-only source.')).json();
  const token=await(await api('POST','/auth/tokens',{name:'Read-only document drag','permissions':['read'],'expiresAt':null})).json();
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true});
  try{
    const reader=await context.newPage();let writes=0;reader.on('request',request=>{if(request.method()==='PUT'&&new URL(request.url()).pathname.endsWith('/path'))writes++;});
    await reader.goto('/web/');await reader.locator('#auth-mode').selectOption('token');await reader.locator('#token').fill(token.token);await reader.locator('#login-form button[type=submit]').click();await expect(reader.locator('#workspace')).toBeVisible();await reader.locator('[data-tab=notes]').click();
    const navigation=reader.locator('#notes-navigation');await noteEntry(navigation,'Notes').click();const source=noteEntry(navigation,entry.path),target=noteEntry(navigation,'Notes/Archive');
    await expect(source).toHaveAttribute('draggable','false');await source.dragTo(target);
    const transfer=await reader.evaluateHandle(id=>{const value=new DataTransfer();value.setData('application/x-timekeeper-note',id);return value;},entry.id);await target.dispatchEvent('drop',{dataTransfer:transfer});
    expect(writes).toBe(0);expect((await(await api('GET','/notes/entries/'+entry.id)).json()).path).toBe(entry.path);
  }finally{await context.close();}
});

test('Notes keeps navigation and both editor modes anchored while their contents scroll',async({page,request})=>{
  const {api}=await setup(page,request),navigation=page.locator('#notes-navigation'),folders=navigation.locator('.notes-navigation-browser');
  const body='# Anchored document\n\n'+('A long paragraph that scrolls inside the editor.\n\n'.repeat(140));await put(api,'Notes/Long.md',body);
  for(let index=0;index<28;index++)await put(api,`Notes/File ${index}.md`);
  // Enter from a scrolled, week-based page: the previous toolbar must already
  // be hidden when Notes measures its available viewport.
  await page.locator('[data-tab=activities]').click();await page.evaluate(()=>scrollTo(0,300));await page.locator('[data-tab=notes]').click();
  await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await navigation.getByRole('button',{name:'Notes',exact:true}).click();await noteEntry(navigation,'Notes/Long.md').click();
  for(const {width,height} of [{width:1440,height:900},{width:390,height:900},{width:844,height:600},{width:320,height:568}]){
    await page.setViewportSize({width,height});
    expect(await page.evaluate(()=>document.documentElement.scrollHeight-document.documentElement.clientHeight)).toBeLessThanOrEqual(1);
    if(width<=760)await page.locator('.notes-drawer-toggle').click();
    await folders.hover();await page.mouse.wheel(0,3000);await expect.poll(()=>folders.evaluate(node=>node.scrollTop)).toBeGreaterThan(0);
    const top=await navigation.evaluate(node=>node.getBoundingClientRect().top);await page.mouse.wheel(0,3000);expect(await page.evaluate(()=>scrollY)).toBe(0);expect(await navigation.evaluate(node=>node.getBoundingClientRect().top)).toBe(top);
    if(width<=760)await page.locator('.notes-drawer-toggle').click();
    for(const mode of ['Markdown source','Formatted']){
      await page.locator('#notes-panel').getByRole('button',{name:mode,exact:true}).click();const content=page.locator(mode==='Formatted'?'.notes-markdown .ProseMirror':'#notes-body');
      expect((await content.boundingBox()).height).toBeGreaterThan(24);
      await content.hover();await page.mouse.wheel(0,5000);await expect.poll(()=>content.evaluate(node=>node.scrollTop)).toBeGreaterThan(0);await page.mouse.wheel(0,5000);
      expect(await page.evaluate(()=>scrollY)).toBe(0);await expect(page.locator('.notes-footer')).toBeInViewport();
      expect(await page.evaluate(()=>document.documentElement.scrollHeight-document.documentElement.clientHeight)).toBeLessThanOrEqual(1);
      const bounds=await page.locator('.notes-editor').boundingBox(),nav=await page.locator('#workspace-navigation').boundingBox();expect(bounds.y+bounds.height).toBeLessThanOrEqual(nav.y+1);
      await captureScreenshot(page,{path:`test-results/notes-anchored-${width}-${mode==='Formatted'?'formatted':'source'}.png`});
    }
    if(width===320){const last=page.locator('.notes-markdown [data-command=hr]');await last.focus();await expect(last).toBeInViewport();}
  }
});

test('navigator toolbar sorts complete paginated folders in all six orders without replacing a draft',async({page,request})=>{
  const {api,folder}=await setup(page,request);await api('POST','/notes/folders',{path:'Notes/Nested'});
  for(const name of ['Alpha','Beta','Zulu'])await put(api,'Notes/'+name+'.md');
  const dates={Alpha:['2022','2023'],Beta:['2021','2021'],Zulu:['2020','2022']},offsets=[];
  await page.route(/\/api\/v1\/notes\/entries\?/,async route=>{
    const response=await route.fetch(),body=await response.json(),query=new URL(route.request().url()).searchParams;
    for(const entry of body.items){const name=entry.path.split('/').at(-1).replace('.md','');if(dates[name]){entry.createdAt=dates[name][0]+'-01-01T00:00:00Z';entry.modifiedAt=dates[name][1]+'-01-01T00:00:00Z';}}
    if(query.get('parentId')===folder.id){const offset=Number(query.get('offset'));offsets.push(offset);if(body.items.length>1)body.nextOffset=offset+1;else delete body.nextOffset;body.items=body.items.slice(0,1);}
    await route.fulfill({response,json:body});
  });
  const navigation=page.locator('#notes-navigation');await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await navigation.getByRole('button',{name:'Notes',exact:true}).click();
  const rows=navigation.locator(`[role=treeitem][data-id="${folder.id}"] > [role=group] > [role=treeitem] > button`);
  await expect(rows).toHaveText(['Nested','Alpha','Beta','Zulu']);expect(new Set(offsets)).toEqual(new Set([0,1,2,3]));
  await navigation.getByRole('button',{name:'Notes/Alpha.md',exact:true}).click();await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#notes-body').fill('Unsaved navigator draft.');
  const toolbar=navigation.getByRole('toolbar',{name:'Note navigation actions'});await expect(toolbar.getByRole('group')).toHaveCount(3);
  const refresh=toolbar.getByRole('button',{name:'Refresh',exact:true}),trash=toolbar.getByRole('button',{name:'Recently deleted',exact:true});
  await expect(trash).toHaveCSS('color',await refresh.evaluate(node=>getComputedStyle(node).color));
  for(const [label,names]of [
    ['Name (Z–A)',['Zulu','Beta','Alpha']],['Name (A–Z)',['Alpha','Beta','Zulu']],
    ['Created (oldest first)',['Zulu','Beta','Alpha']],['Created (newest first)',['Alpha','Beta','Zulu']],
    ['Modified (oldest first)',['Beta','Zulu','Alpha']],['Modified (newest first)',['Alpha','Zulu','Beta']]
  ]){
    await toolbar.getByRole('button',{name:'Sort notes',exact:true}).click();await page.getByRole('menuitemradio',{name:label,exact:true}).click();
    await expect(rows).toHaveText(['Nested',...names]);await expect(page.locator('#notes-body')).toHaveValue('Unsaved navigator draft.');
    await expect(page.locator('#notes-panel').getByRole('button',{name:'Save',exact:true})).toHaveCount(0);
  }
  const sort=toolbar.getByRole('button',{name:'Sort notes',exact:true});await sort.focus();await sort.press('ArrowDown');await expect(page.getByRole('menuitemradio',{name:'Modified (newest first)',exact:true})).toBeFocused();await page.keyboard.press('Escape');await expect(sort).toBeFocused();
  await sort.press('ArrowRight');await expect(toolbar.getByRole('button',{name:'Collapse all folders',exact:true})).toBeFocused();await page.keyboard.press('ArrowLeft');await expect(sort).toBeFocused();
  await page.locator('#notes-search').fill('.md');await expect(toolbar.getByRole('button',{name:'Expand all folders',exact:true})).toBeDisabled();
  await expect(navigation.locator('[role=treeitem] > button')).toHaveText(['Alpha','Zulu','Beta']);
});

test('expand and collapse cover nested folders, exclude a hidden journal and cancel stale expansion',async({page,request})=>{
  const {api,folder}=await setup(page,request);
  const nested=await(await api('POST','/notes/folders',{path:'Notes/Nested'})).json();await api('POST','/notes/folders',{path:'Notes/Nested/Deep'});await put(api,'Notes/Nested/Deep/Leaf.md');await put(api,'Notes/Draft.md');
  const settings=await api('GET','/notes/settings');await api('PUT','/notes/settings',{hideJournalFolder:true},{'If-Match':settings.headers().etag});
  const journal=await(await api('GET','/notes/resolve?path=journal')).json(),navigation=page.locator('#notes-navigation');
  await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await navigation.getByRole('button',{name:'Notes',exact:true}).click();await navigation.getByRole('button',{name:'Notes/Draft.md',exact:true}).click();
  await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#notes-body').fill('Retain this draft.');
  let release,blocked=false;const hold=new Promise(resolve=>{release=resolve;}),parents=[];
  await page.route(/\/api\/v1\/notes\/entries\?/,async route=>{const parent=new URL(route.request().url()).searchParams.get('parentId');parents.push(parent);if(parent===nested.id){blocked=true;await hold;}await route.continue();});
  await navigation.getByRole('button',{name:'Expand all folders',exact:true}).click();
  try{await expect.poll(()=>blocked).toBe(true);}catch(error){release();throw error;}
  await navigation.getByRole('button',{name:'Collapse all folders',exact:true}).click();release();
  const root=navigation.locator(`[role=treeitem][data-id="${folder.id}"]`);await expect(root).toHaveAttribute('aria-expanded','false');await expect(navigation.getByRole('button',{name:'Notes/Nested/Deep/Leaf.md',exact:true})).toHaveCount(0);
  await page.unroute(/\/api\/v1\/notes\/entries\?/);await navigation.getByRole('button',{name:'Expand all folders',exact:true}).click();await expect(navigation.getByRole('button',{name:'Notes/Nested/Deep/Leaf.md',exact:true})).toBeVisible();
  for(const row of await navigation.locator('[role=treeitem][aria-expanded]').all())await expect(row).toHaveAttribute('aria-expanded','true');
  expect(parents).not.toContain(journal.id);await expect(page.locator('#notes-body')).toHaveValue('Retain this draft.');
});

test('navigation toggle stays left of Notes while collapse preserves the draft and navigator width',async({page,request})=>{
  await page.setViewportSize({width:1440,height:900});
  const {api}=await setup(page,request);await put(api,'Notes/Review.md','# Project review\n\nNotes for the next release.\n');
  const navigation=page.locator('#notes-navigation'),toggle=page.locator('.notes-drawer-toggle'),editor=page.locator('.notes-editor'),title=page.locator('#notes-document-title'),resize=page.getByRole('separator',{name:'Resize notes navigation'});
  const toggleOffset=async()=>{
    const label=page.locator('#notes-title');await expect(label).toBeVisible();const button=await toggle.boundingBox(),text=await label.boundingBox(),heading=await page.locator('.notes-heading').boundingBox();
    expect(button.x+button.width).toBeLessThanOrEqual(text.x);expect(Math.abs(button.y+button.height/2-text.y-text.height/2)).toBeLessThan(1);return button.x-heading.x;
  };
  const originalOffset=await toggleOffset();
  await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await navigation.getByRole('button',{name:'Notes',exact:true}).click();await noteEntry(navigation,'Notes/Review.md').click();
  await expect(title).toHaveText('Project review');await expect(toggle).toHaveAttribute('aria-expanded','true');
  for(const property of ['font-family','font-size','font-weight'])await expect(title).toHaveCSS(property,await page.locator('#notes-title').evaluate((node,property)=>getComputedStyle(node).getPropertyValue(property),property));
  expect((await title.boundingBox()).y+(await title.boundingBox()).height).toBeLessThan((await editor.boundingBox()).y);
  await expect.poll(()=>navigation.locator('.notes-tree-pick').first().evaluate(node=>parseFloat(getComputedStyle(document.documentElement).fontSize)-parseFloat(getComputedStyle(node).fontSize))).toBeCloseTo(2,1);
  await captureScreenshot(page,{path:'test-results/notes-workspace-expanded.png',fullPage:false});
  await resize.focus();await resize.press('End');await expect(resize).toHaveAttribute('aria-valuenow','480');
  const expandedWidth=(await editor.boundingBox()).width;
  await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#notes-body').fill('Unsaved layout draft.');
  await toggle.focus();await toggle.press('Enter');await expect(toggle).toBeFocused();await expect(toggle).toHaveAttribute('aria-expanded','false');await expect(navigation).toBeHidden();await expect(resize).toBeHidden();
  expect(await toggleOffset()).toBeCloseTo(originalOffset,1);
  await expect.poll(async()=>Math.abs((await editor.boundingBox()).width-(await page.locator('.notes-layout').boundingBox()).width)).toBeLessThan(2);
  expect((await editor.boundingBox()).width).toBeGreaterThan(expandedWidth);
  await expect(page.locator('#notes-body')).toHaveValue('Unsaved layout draft.');await expect(page.locator('#notes-panel').getByRole('button',{name:'Save',exact:true})).toHaveCount(0);
  await captureScreenshot(page,{path:'test-results/notes-workspace-collapsed.png',fullPage:false});
  await toggle.press('Space');await expect(navigation).toBeVisible();await expect(toggle).toHaveAttribute('aria-expanded','true');await expect(resize).toHaveAttribute('aria-valuenow','480');
  expect(await toggleOffset()).toBeCloseTo(originalOffset,1);
  await expect.poll(async()=>Math.abs((await editor.boundingBox()).width-expandedWidth)).toBeLessThan(2);await expect(page.locator('#notes-body')).toHaveValue('Unsaved layout draft.');
  // A viewport change must not leave focus in navigation that is now hidden.
  await page.locator('#notes-search').focus();await page.setViewportSize({width:390,height:844});await expect(navigation).toBeHidden();await expect(toggle).toBeFocused();await expect(toggle).toHaveAttribute('aria-expanded','false');
  expect(await toggleOffset()).toBeCloseTo(originalOffset,1);
  await toggle.press('Enter');await expect(navigation).toBeVisible();await expect(toggle).toHaveAttribute('aria-expanded','true');await expect(resize).toBeHidden();
  expect(await toggleOffset()).toBeCloseTo(originalOffset,1);
  await page.setViewportSize({width:1440,height:900});await expect(navigation).toBeVisible();await expect(toggle).toHaveAttribute('aria-expanded','true');
  await toggle.click();await page.setViewportSize({width:390,height:844});await toggle.click();await expect(navigation).toBeVisible();await page.locator('#notes-search').focus();
  await page.setViewportSize({width:1440,height:900});await expect(navigation).toBeHidden();await expect(toggle).toBeFocused();await expect(page.locator('#notes-body')).toHaveValue('Unsaved layout draft.');
});

test('navigator toolbar and sort menu fit desktop and mobile themes with compact tree text',async({page,request})=>{
  await setup(page,request);const navigation=page.locator('#notes-navigation');
  await expect(page.locator('html')).toHaveCSS('font-size','14px');await expect(page.locator('body')).toHaveCSS('font-size','14px');
  for(const width of [1440,390,320]){
    await page.setViewportSize({width,height:900});if(width<760)await page.locator('.notes-drawer-toggle').click();
    await expect(navigation).toBeVisible();expect(await navigation.evaluate(node=>node.scrollWidth<=node.clientWidth)).toBe(true);
    const tops=await navigation.locator('.notes-navigation-tools > .btn-group').evaluateAll(groups=>groups.map(group=>group.getBoundingClientRect().top));expect(Math.max(...tops)-Math.min(...tops)).toBeLessThan(2);
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=['timekeeper','solarized-light'].includes(theme)?'light':'dark';},theme);
      await navigation.getByRole('button',{name:'Sort notes',exact:true}).click();expect((await new AxeBuilder({page}).include('#notes-panel').analyze()).violations).toEqual([]);
      const box=await page.getByRole('menu',{name:'Sort notes',exact:true}).boundingBox();expect(box.x).toBeGreaterThanOrEqual(0);expect(box.x+box.width).toBeLessThanOrEqual(width);
      await page.keyboard.press('Escape');
    }
    await captureScreenshot(page,{path:`test-results/notes-navigator-${width}.png`,fullPage:false});
    if(width<760)await page.locator('.notes-drawer-toggle').click();
  }
});

test('read-only navigation can sort and expand folders without issuing collection writes',async({page,request})=>{
  const headers={Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},path='Read-only-'+randomUUID().slice(0,8);
  expect((await request.get('/api/v1/notes',{headers})).ok()).toBe(true);
  for(const name of [path,path+'/Nested'])expect((await request.post('/api/v1/notes/folders',{headers,data:{path:name}})).status()).toBe(201);
  expect((await request.put('/api/v1/notes/files?'+new URLSearchParams({path:path+'/Nested/Read.md'}),{headers:{...headers,'If-None-Match':'*','Content-Type':'application/octet-stream'},data:Buffer.from('Read-only note.')})).ok()).toBe(true);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_READ_TOKEN);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=notes]').click();
  const writes=[];page.on('request',request=>{if(request.url().includes('/api/v1/notes')&&request.method()!=='GET')writes.push(request.method());});
  const navigation=page.locator('#notes-navigation');await expect(navigation.getByRole('button',{name:'New note',exact:true})).toBeDisabled();
  await navigation.getByRole('button',{name:'Expand all folders',exact:true}).click();await expect(navigation.getByRole('button',{name:path+'/Nested/Read.md',exact:true})).toBeVisible();
  await navigation.getByRole('button',{name:'Sort notes',exact:true}).click();await page.getByRole('menuitemradio',{name:'Name (Z–A)',exact:true}).click();
  await navigation.getByRole('button',{name:'Collapse all folders',exact:true}).click();await expect(navigation.getByRole('button',{name:path+'/Nested/Read.md',exact:true})).toHaveCount(0);expect(writes).toEqual([]);
});
