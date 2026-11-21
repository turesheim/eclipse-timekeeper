import {test,expect} from '@playwright/test';
import {randomUUID,createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {noteEntry} from './note-entry.js';
import {notesSource,clickNoteAction,saveMarkdown} from './editor-interactions.js';
import {searchNotes} from './notes-search.js';
import {captureScreenshot} from './screenshots.js';

const original=await readFile(new URL('./fixtures/drawio/two-pages.drawio.png',import.meta.url)),revised=await readFile(new URL('./fixtures/drawio/revised.drawio.png',import.meta.url));
const digest=bytes=>createHash('sha256').update(bytes).digest('hex'),execute=promisify(execFile),dialog=page=>page.locator('.drawio-dialog[open]');
test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function fresh(page,request){
  const previous=await browserRequest(page).get('/api/v1/auth/session');if(previous.status()===200)await browserRequest(page).post('/api/v1/auth/logout',{headers:{'X-CSRF-Token':(await previous.json()).csrf}});
  const username='diagram-'+randomUUID().slice(0,8),password='Synthetic diagram account passphrase';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:'Bearer '+process.env.TIMEKEEPER_WEB_TOKEN},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const http=browserRequest(page),session=await(await http.get('/api/v1/auth/session')).json();
  const raw=(method,path,data,headers={})=>http.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':session.csrf,...(method==='POST'?{'Idempotency-Key':randomUUID()}:{}),...headers}});
  const api=async(...args)=>{const reply=await raw(...args);expect(reply.ok(),args[0]+' '+args[1]+' '+reply.status()).toBe(true);return reply;};
  await api('GET','/notes');const put=async(path,bytes)=>(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.isBuffer(bytes)?bytes:Buffer.from(bytes),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();return {api,raw,put};
}
async function open(page,path){
  await page.locator('[data-tab=notes]').click();const root=page.locator('#notes-panel'),expand=root.getByRole('button',{name:'Expand left sidebar',exact:true});if(await expand.isVisible())await expand.click();await root.getByRole('button',{name:'Refresh',exact:true}).first().click();await expect(page.locator('#notes-navigation [role=tree]')).toHaveAttribute('aria-busy','false');await noteEntry(page.locator('#notes-navigation'),path).click();await root.getByRole('button',{name:'Formatted',exact:true}).click();await expect(root.locator('.ProseMirror')).toBeVisible();return root;
}
async function insert(page,root,label='System overview'){
  await clickNoteAction(page,root,'Insert draw.io diagram');const box=dialog(page);await expect(box).toBeVisible();await box.getByLabel('Draw.io PNG file',{exact:true}).setInputFiles({name:'system.drawio.png',mimeType:'image/png',buffer:original});await box.getByLabel('Diagram description',{exact:true}).fill(label);await box.getByRole('button',{name:'Insert draw.io diagram',exact:true}).click();await expect(box).toBeHidden();await expect.poll(()=>root.locator('.ProseMirror img[alt="'+label+'"]').evaluate(img=>img.naturalWidth)).toBe(186);
}
async function manage(page,root,label='System overview'){
  await clickNoteAction(page,root,'Draw.io diagrams');const box=dialog(page);await box.getByRole('button',{name:label,exact:true}).click();await expect(box.getByRole('button',{name:'Download original PNG',exact:true})).toBeEnabled();return box;
}
async function choose(box,bytes=revised){await box.getByLabel('Draw.io PNG file',{exact:true}).setInputFiles({name:'revised.drawio.png',mimeType:'image/png',buffer:bytes});await expect(box.getByRole('button',{name:'Replace PNG',exact:true})).toBeEnabled();}
async function downloaded(page,box,name){const waiting=page.waitForEvent('download');await box.getByRole('button',{name:'Download original PNG',exact:true}).click();const file=await waiting,path=test.info().outputPath(name);await file.saveAs(path);return readFile(path);}
const bytes=async(api,id)=>Buffer.from(await(await api('GET','/notes/entries/'+id+'/content')).body());

test('editable PNG insertion, original download and replacement preserve portable source and use no remote preview',async({page,request})=>{
  const {api,put}=await fresh(page,request),entry=await put('Diagram.md','\uFEFF---\r\ncustom: exact\r\n---\r\n# Diagram\r\n\r\n'),remote=[];
  page.on('request',r=>{const url=new URL(r.url());if(['http:','https:'].includes(url.protocol)&&url.origin!==new URL(process.env.TIMEKEEPER_WEB_URL).origin)remote.push(r.url());});const root=await open(page,'Diagram.md');
  await root.locator('.ProseMirror').click();await page.keyboard.press('ControlOrMeta+End');await insert(page,root);await saveMarkdown(root);const source=(await bytes(api,entry.id)).toString('utf8');expect(source).toContain('![System overview](system.drawio.png)');expect(source.startsWith('\uFEFF---\r\ncustom: exact\r\n---\r\n')).toBe(true);
  const asset=await(await api('GET','/notes/resolve?path=system.drawio.png')).json();expect(await bytes(api,asset.id)).toEqual(original);
  await notesSource(page);await root.getByRole('button',{name:'Formatted',exact:true}).click();await expect(root.locator('.ProseMirror img[alt="System overview"]')).toBeVisible();const previous=await root.locator('.ProseMirror img[alt="System overview"]').getAttribute('src');
  let box=await manage(page,root);expect(await downloaded(page,box,'downloaded-original.png')).toEqual(original);await choose(box);
  for(const theme of ['timekeeper','timekeeper-dark'])for(const width of [1200,320]){await page.setViewportSize({width,height:900});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);expect((await new AxeBuilder({page}).include('.drawio-dialog[open]').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);expect(await box.evaluate(node=>node.scrollWidth<=node.clientWidth)).toBe(true);await captureScreenshot(page,{path:`test-results/drawio-${theme}-${width}.png`});}
  await box.getByRole('button',{name:'Replace PNG',exact:true}).click();await expect(box).toBeHidden();await expect(root.locator('.ProseMirror img[alt="System overview"]')).not.toHaveAttribute('src',previous);expect(await bytes(api,asset.id)).toEqual(revised);expect((await bytes(api,entry.id)).toString('utf8')).toBe(source);
  await page.reload();const reopened=await open(page,'Diagram.md');box=await manage(page,reopened);expect(await downloaded(page,box,'downloaded-revised.png')).toEqual(revised);await box.getByRole('button',{name:'Cancel',exact:true}).press('Escape');await expect(box).toBeHidden();expect(remote).toEqual([]);
});

test('stale, rejected and lost replacement replies retain the file and never replay an uncertain write',async({page,request})=>{
  const {api,put}=await fresh(page,request);const asset=await put('system.drawio.png',original),note=await put('Diagram.md','![System overview](system.drawio.png)\n'),root=await open(page,'Diagram.md');let box=await manage(page,root);await choose(box);
  const endpoint='/notes/entries/'+asset.id+'/content',old=await api('GET',endpoint);await api('PUT',endpoint,revised,{'If-Match':old.headers().etag,'Content-Type':'application/octet-stream'});
  await box.getByRole('button',{name:'Replace PNG',exact:true}).click();await expect(box.getByRole('alert')).toContainText('changed elsewhere');expect(await bytes(api,asset.id)).toEqual(revised);expect(await box.getByLabel('Draw.io PNG file',{exact:true}).evaluate(input=>input.files[0].name)).toBe('revised.drawio.png');
  page.once('dialog',prompt=>prompt.accept());await box.getByRole('button',{name:'Reload saved file',exact:true}).click();await expect(box.getByRole('button',{name:'Replace PNG',exact:true})).toBeEnabled();await choose(box,original);
  // WebKit does not expose intercepted File bodies to route.fetch(). Let the
  // browser send its real PNG, then lose only the successful response.
  await page.evaluate(endpoint=>{const fetch=window.fetch;let dropped=false;window.fetch=async(...args)=>{const reply=await fetch(...args);if(!dropped&&args[1]?.method==='PUT'&&new URL(args[0],location.href).pathname==='/api/v1'+endpoint&&reply.ok){dropped=true;throw new TypeError('Synthetic lost PNG reply');}return reply;};},endpoint);
  let attempts=0;await page.route('**/api/v1'+endpoint,async route=>{if(route.request().method()!=='PUT')return route.continue();attempts++;if(attempts===1)return route.fulfill({status:422,json:{code:'VALIDATION_FAILED',status:422}});await route.continue();});
  await box.getByRole('button',{name:'Replace PNG',exact:true}).click();await expect(box.getByRole('alert')).toContainText('not saved');await expect(box.getByRole('button',{name:'Replace PNG',exact:true})).toBeEnabled();expect(await bytes(api,asset.id)).toEqual(revised);
  await box.getByRole('button',{name:'Replace PNG',exact:true}).click();await expect(box.getByRole('alert')).toContainText('could not be confirmed');await expect(box.getByRole('button',{name:'Replace PNG',exact:true})).toBeDisabled();expect(attempts).toBe(2);expect(await bytes(api,asset.id)).toEqual(original);await box.getByRole('button',{name:'Check saved result',exact:true}).click();await expect(box).toBeHidden();expect(attempts).toBe(2);expect(await bytes(api,asset.id)).toEqual(original);expect((await bytes(api,note.id)).toString()).toBe('![System overview](system.drawio.png)\n');
});

test('read-only sessions download while foreign accounts cannot read or replace PNGs',async({page,request,browser})=>{
  const owner=await fresh(page,request),asset=await owner.put('system.drawio.png',original);await owner.put('Diagram.md','![System overview](system.drawio.png)\n');const token=(await(await owner.api('POST','/auth/tokens',{name:'Read-only diagrams',permissions:['read'],expiresAt:null})).json()).token;
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true});try{
    const read=await context.newPage();await read.goto('/web/');await read.locator('#auth-mode').selectOption('token');await read.locator('#token').fill(token);await read.locator('#login-form button[type=submit]').click();await expect(read.locator('#status')).toHaveText('Up to date');const root=await open(read,'Diagram.md'),box=await manage(read,root);expect(await downloaded(read,box,'readonly-original.png')).toEqual(original);await expect(box.getByLabel('Draw.io PNG file',{exact:true})).toBeHidden();await expect(box.getByRole('button',{name:'Replace PNG',exact:true})).toBeHidden();await expect(box.getByRole('button',{name:'Edit diagram',exact:true})).toBeHidden();await box.getByRole('button',{name:'Cancel',exact:true}).click();await clickNoteAction(read,root,'Draw.io diagrams');await dialog(read).getByRole('button',{name:'Cancel',exact:true}).click();
  }finally{await context.close();}
  const other=await fresh(page,request);expect((await other.raw('GET','/notes/entries/'+asset.id+'/content')).status()).toBe(404);expect((await other.raw('PUT','/notes/entries/'+asset.id+'/content',revised,{'If-Match':'*','Content-Type':'application/octet-stream'})).status()).toBe(404);
});

test('shared daily, weekly and saved event notes insert and replace the same editable PNG flow',async({page,request})=>{
  test.setTimeout(120000);const {api,raw,put}=await fresh(page,request),day='2026-11-16';await put('journal/'+day+'.md','---\nbanner: "Keep imported setting"\n---\nDaily diagram\n');await put('journal/2026-W47.md','Weekly diagram\n');
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(day);const daily=page.locator('#today-note-editor');await expect(daily.locator('.ProseMirror')).toBeVisible();await insert(page,daily,'Daily overview');await saveMarkdown(daily);expect((await(await api('GET','/day-entries/'+day)).json()).body).toContain('![Daily overview](assets/');
  await page.locator('#today-month-grid [data-week="2026-W47"]').click();const weekly=page.locator('#today-week-note-editor');await expect(weekly.locator('.ProseMirror')).toBeVisible();await insert(page,weekly,'Weekly overview');await saveMarkdown(weekly);let box=await manage(page,weekly,'Weekly overview');const beforePreview=await weekly.locator('.ProseMirror img[alt="Weekly overview"]').getAttribute('src');await choose(box);await box.getByRole('button',{name:'Replace PNG',exact:true}).click();await expect(box).toBeHidden();await expect(weekly.locator('.ProseMirror img[alt="Weekly overview"]')).not.toHaveAttribute('src',beforePreview);await expect(weekly).toBeVisible();await weekly.getByRole('button',{name:'Close',exact:true}).click();
  const title='Diagram planning',body=['BEGIN:VCALENDAR','VERSION:2.0','PRODID:-//Dagsverk//Diagram fixture//EN','BEGIN:VEVENT','UID:diagram-event','DTSTAMP:20260101T000000Z','DTSTART:20261116T100000Z','DTEND:20261116T110000Z','SUMMARY:'+title,'END:VEVENT','END:VCALENDAR',''].join('\r\n'),feed=await request.post(process.env.TIMEKEEPER_WEB_CALENDAR_FIXTURE+'/feeds',{data:{body}}),client=await(await api('POST','/clients',{name:'Diagram calendar',color:'#305070'})).json(),subscription=await(await api('POST','/calendar-subscriptions',{name:'Diagram calendar',clientId:client.id,url:(await feed.json()).url,visible:true,includeEvents:true,includeTodos:false})).json();
  let event;await expect.poll(async()=>{event=(await(await api('GET','/calendar-events?fromDate='+day+'&toDate=2026-11-17')).json()).items.find(item=>item.subscriptionId===subscription.id);return event?.title;},{timeout:30000}).toBe(title);const path='/event-notes/'+subscription.id+'/'+event.key,before=await api('GET',path);await api('PUT',path,{fromDate:day,toDate:'2026-11-17',body:'Event diagram bridge\n'},{'If-Match':before.headers().etag});
  await expect.poll(async()=>{const r=await raw('GET','/notes/search?q='+encodeURIComponent('event:"Event diagram bridge"'));return r.status()===200?(await r.json()).items.length:0;},{timeout:30000}).toBe(1);const panel=await searchNotes(page,'event:"Event diagram bridge"');await panel.locator('[data-search-note]').click();const popup=page.locator('#calendar-search-popup-editor');await insert(page,popup,'Event overview');await saveMarkdown(popup);box=await manage(page,popup,'Event overview');expect(await downloaded(page,box,'event-original.png')).toEqual(original);await box.getByRole('button',{name:'Cancel',exact:true}).press('Escape');await expect(popup).toBeVisible();await popup.getByRole('button',{name:'Open in workspace',exact:true}).click();await expect(page.locator('#calendar-search-workspace-editor .ProseMirror img[alt="Event overview"]')).toBeVisible();expect((await(await api('GET',path)).json()).body).toContain('![Event overview](');
});

test('WebDAV and full account ZIP restore retain exact diagram bytes and Markdown references',async({page,request})=>{
  test.setTimeout(90000);const {api,put}=await fresh(page,request),source='\uFEFF---\r\ncustom: keep\r\n---\r\n![System overview](system.drawio.png)\r\n';await put('system.drawio.png',revised);await put('Diagram.md',source);const token=(await(await api('POST','/auth/tokens',{name:'Diagram DAV',permissions:['read'],expiresAt:null})).json()).token;
  for(const [path,expected]of [['system.drawio.png',revised],['Diagram.md',Buffer.from(source)]]){const reply=await request.get('/dav/notes/'+path,{headers:{Authorization:'Basic '+Buffer.from('webdav:'+token).toString('base64')}});expect(reply.status()).toBe(200);expect(Buffer.from(await reply.body())).toEqual(expected);}
  const zip=Buffer.from(await(await api('GET','/data/account/export',undefined,{Accept:'application/zip'})).body()),archive=test.info().outputPath('diagram-account.zip');await (await import('node:fs/promises')).writeFile(archive,zip);
  const inspected=await execute('python3',['-c','import zipfile,json,hashlib,sys\nz=zipfile.ZipFile(sys.argv[1]);print(json.dumps([hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith("vault/content/")]))',archive]);expect(JSON.parse(inspected.stdout)).toEqual(expect.arrayContaining([digest(revised),digest(Buffer.from(source))]));
  const destination=await fresh(page,request),upload=(await(await destination.api('POST','/data/account/uploads',{fileName:'diagram-account.zip',mediaType:'application/zip',bytes:zip.length,sha256:digest(zip)})).json()).id,path='/data/account/uploads/'+upload;await destination.api('PUT',path+'/chunks/0',{data:zip.toString('base64')});const review=await destination.api('POST',path+'/preview',{restoreTimeZone:false});await destination.api('POST',path+'/import',{restoreTimeZone:false},{'If-Match':review.headers().etag});
  for(const [name,expected]of [['system.drawio.png',revised],['Diagram.md',Buffer.from(source)]]){const entry=(await(await destination.api('GET','/notes/resolve?'+new URLSearchParams({path:name}))).json());expect(await bytes(destination.api,entry.id)).toEqual(expected);}
  const root=await open(page,'Diagram.md');await expect(root.locator('.ProseMirror img[alt="System overview"]')).toBeVisible();
});

test('missing and unsupported PNGs stay readable without rewriting the note',async({page,request})=>{
  const {api,put}=await fresh(page,request),source='![Missing diagram](missing.png)\n\n![Unsupported diagram](broken.png)\n\n[Native file](native.drawio)\n';await put('broken.png',Buffer.from('not a PNG'));await put('native.drawio','<mxfile/>');const entry=await put('Fallback.md',source),root=await open(page,'Fallback.md');await expect(root.locator('.ProseMirror img[alt^="Missing diagram"]')).toHaveAttribute('title',/./);await expect(root.locator('.ProseMirror img[alt^="Unsupported diagram"]')).toHaveAttribute('title',/./);await expect(root.getByRole('link',{name:'Native file',exact:true})).toBeVisible();const box=await manage(page,root,'Unsupported diagram');await expect(box.getByRole('alert')).toContainText('valid PNG');await box.getByLabel('Draw.io PNG file',{exact:true}).setInputFiles({name:'wrong.png',mimeType:'image/png',buffer:Buffer.from('<svg/>')});await expect(box.getByRole('button',{name:'Replace PNG',exact:true})).toBeDisabled();await box.getByRole('button',{name:'Cancel',exact:true}).click();expect((await bytes(api,entry.id)).toString()).toBe(source);
});

test('shared image previews refresh in place and a late initial read cannot restore old bytes',async({page,request})=>{
  await fresh(page,request);
  const result=await page.evaluate(async({original,revised})=>{
    const objects=new Map(),create=URL.createObjectURL;URL.createObjectURL=blob=>{const url=create(blob);objects.set(url,blob);return url;};
    const {imagePreview,updateImagePreviews}=await import('/web/image-preview.js'),blob=bytes=>new Blob([new Uint8Array(bytes)]),owner={},old=blob(original),next=blob(revised);let release,reads=0,active=true;
    const waiting=new Promise(resolve=>release=resolve),load=async()=>({blob:++reads===1?await waiting:next,session:owner,id:'shared'}),preview=imagePreview({alt:'Late preview',load,active:()=>active});document.body.append(preview.dom);
    updateImagePreviews(owner,'shared',next);release(old);
    for(let i=0;i<50&&!preview.dom.src;i++)await new Promise(resolve=>setTimeout(resolve,10));
    const late=Array.from(new Uint8Array(await objects.get(preview.dom.src).arrayBuffer()));
    const second=imagePreview({alt:'Second preview',load:async()=>({blob:old,session:owner,id:'shared'}),active:()=>active});document.body.append(second.dom);
    for(let i=0;i<50&&!second.dom.src;i++)await new Promise(resolve=>setTimeout(resolve,10));
    const node=second.dom,before=node.src;updateImagePreviews(owner,'shared',next);
    for(let i=0;i<50&&node.src===before;i++)await new Promise(resolve=>setTimeout(resolve,10));
    const shared=Array.from(new Uint8Array(await objects.get(node.src).arrayBuffer()));active=false;preview.destroy();second.destroy();URL.createObjectURL=create;return {reads,late,shared,sameNode:node===second.dom,cleared:!node.hasAttribute('src')};
  },{original:[...original],revised:[...revised]});
  expect(result.reads).toBe(2);expect(Buffer.from(result.late)).toEqual(revised);expect(Buffer.from(result.shared)).toEqual(revised);expect(result.sameNode).toBe(true);expect(result.cleared).toBe(true);
});

test.describe('self-hosted draw.io editor',()=>{
  const origin=process.env.TIMEKEEPER_WEB_DRAWIO_ORIGIN;
  test.skip(!origin,'Requires the optional official draw.io fixture');

  test.beforeEach(async({page})=>{
    const allowed=new Set([origin,new URL(process.env.TIMEKEEPER_WEB_URL).origin]);
    await page.route('**/*',route=>allowed.has(new URL(route.request().url()).origin)?route.continue():route.abort());
  });

  async function edit(box){
    await box.getByRole('button',{name:'Edit diagram',exact:true}).click();
    await expect(box.getByRole('button',{name:'Save diagram',exact:true})).toBeEnabled();
    return box.frameLocator('iframe[title="Draw.io editor"]');
  }

  async function change(frame,oldLabel,newLabel){
    await frame.getByText(oldLabel,{exact:true}).dblclick();
    const editor=frame.locator('.mxCellEditor');
    await editor.fill(newLabel);
    await editor.press('ControlOrMeta+Enter');
  }

  test('edits both pages, retains the preview and Markdown path, and reopens from saved bytes',async({page,request})=>{
    const {api,put}=await fresh(page,request);
    const source='![System overview](system.drawio.png)\n';
    const asset=await put('system.drawio.png',original),note=await put('Diagram.md',source);
    const root=await open(page,'Diagram.md');
    const preview=root.locator('.ProseMirror img[alt="System overview"]');
    await expect.poll(()=>preview.evaluate(img=>img.naturalWidth)).toBe(186);
    const previous=await preview.getAttribute('src');
    const web=await request.get('/web/'),admin=await request.get('/admin/');
    expect(web.headers()['content-security-policy']).toContain('; frame-src '+origin);
    expect(admin.headers()['content-security-policy']).toContain("frame-ancestors 'none'");
    expect(admin.headers()['content-security-policy']).not.toContain(origin);

    let box=await manage(page,root),frame=await edit(box);
    await expect(box.locator('iframe')).toBeFocused();
    await frame.getByText('Details',{exact:true}).click();
    await change(frame,'Stage B','Saved detail');
    await frame.getByText('Overview',{exact:true}).press('Enter');
    await change(frame,'Stage A','Saved overview');
    await box.getByRole('button',{name:'Save diagram',exact:true}).click();
    await expect(box).toBeHidden();
    await expect(preview).not.toHaveAttribute('src',previous);
    const saved=await bytes(api,asset.id);
    expect(saved).not.toEqual(original);
    expect((await bytes(api,note.id)).toString()).toBe(source);

    box=await manage(page,root);frame=await edit(box);
    await expect(frame.getByText('Saved overview',{exact:true})).toBeVisible();
    await frame.getByText('Details',{exact:true}).click();
    await expect(frame.getByText('Saved detail',{exact:true})).toBeVisible();
    await page.setViewportSize({width:390,height:844});
    const viewport=await box.boundingBox();
    expect(viewport.width).toBe(390);
    expect(viewport.height).toBe(844);
    expect(viewport.x).toBe(0);
    expect(viewport.y).toBe(0);
    await change(frame,'Saved detail','Discard this detail');
    const cancel=box.getByRole('button',{name:'Cancel',exact:true});
    page.once('dialog',prompt=>prompt.dismiss());
    await cancel.press('Escape');
    await expect(frame.getByText('Discard this detail',{exact:true})).toBeVisible();
    page.once('dialog',prompt=>prompt.accept());
    await cancel.press('Escape');
    await expect(box.locator('iframe')).toHaveCount(0);
    await expect(box.getByRole('button',{name:'Edit diagram',exact:true})).toBeFocused();
    expect(await bytes(api,asset.id)).toEqual(saved);
  });

  test('keeps the embedded draft through a conflict and a lost save reply',async({page,request})=>{
    const {api,put}=await fresh(page,request);
    const asset=await put('system.drawio.png',original);
    await put('Diagram.md','![System overview](system.drawio.png)\n');
    const root=await open(page,'Diagram.md'),box=await manage(page,root),frame=await edit(box);
    await change(frame,'Stage A','Draft overview');
    const endpoint='/notes/entries/'+asset.id+'/content',before=await api('GET',endpoint);
    await api('PUT',endpoint,revised,{'If-Match':before.headers().etag,'Content-Type':'application/octet-stream'});
    const save=box.getByRole('button',{name:'Save diagram',exact:true});
    await save.click();
    await expect(box.getByRole('alert')).toContainText('changed elsewhere');
    await expect(frame.getByText('Draft overview',{exact:true})).toBeVisible();
    expect(await bytes(api,asset.id)).toEqual(revised);

    page.once('dialog',prompt=>prompt.accept());
    await box.getByRole('button',{name:'Reload saved file',exact:true}).click();
    await expect(box.getByAltText('Saved diagram preview')).toBeVisible();
    await expect(frame.getByText('Draft overview',{exact:true})).toBeVisible();
    await page.evaluate(endpoint=>{
      const fetch=window.fetch;
      window.fetch=async(...args)=>{
        const response=await fetch(...args);
        if(args[1]?.method==='PUT'&&new URL(args[0],location.href).pathname==='/api/v1'+endpoint&&response.ok){
          throw new TypeError('Synthetic lost PNG reply');
        }
        return response;
      };
    },endpoint);
    let writes=0;
    await page.route('**/api/v1'+endpoint,route=>{
      if(route.request().method()==='PUT')writes++;
      return route.continue();
    });
    await save.click();
    await expect(box.getByRole('alert')).toContainText('could not be confirmed');
    await expect(save).toBeDisabled();
    await expect(frame.getByText('Draft overview',{exact:true})).toBeVisible();
    await box.getByRole('button',{name:'Check saved result',exact:true}).click();
    await expect(box).toBeHidden();
    expect(writes).toBe(1);
  });

  test('creates a portable editable PNG and inserts it at the captured source cursor',async({page,request})=>{
    const {api,put}=await fresh(page,request);
    const note=await put('Blank.md','Before\n\nAfter\n');
    const root=await open(page,'Blank.md'),source=await notesSource(page);
    // Use the cursor between the two paragraphs.
    await source.evaluate(node=>{node.focus();node.setSelectionRange(7,7);});
    await clickNoteAction(page,root,'New diagram');
    const box=dialog(page),save=box.getByRole('button',{name:'Save diagram',exact:true});
    await expect(save).toBeEnabled();
    await expect(box.getByLabel('Filename')).toBeFocused();
    await box.getByLabel('Filename').fill('concept.drawio.png');
    await box.getByLabel('Diagram description').fill('Concept map');
    await save.click();
    await expect(box).toBeHidden();
    await saveMarkdown(root);
    const entry=(await(await api('GET','/notes/resolve?path=concept.drawio.png')).json());
    const saved=await bytes(api,entry.id);
    const {drawioPngInfo}=await import('../src/drawio-png.js');
    expect(drawioPngInfo(new Uint8Array(saved))?.editable).toBe(true);
    expect((await bytes(api,note.id)).toString()).toBe('Before\n![Concept map](concept.drawio.png)\nAfter\n');
  });
});
