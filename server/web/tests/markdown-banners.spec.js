import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {noteEntry} from './note-entry.js';
import {appendEditorText,saveMarkdown} from './editor-interactions.js';
import {openAccountMenu} from './navigation.js';
import {captureScreenshot} from './screenshots.js';

const image=await readFile(new URL('./fixtures/banner.png',import.meta.url));
const pixel=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jI1sAAAAASUVORK5CYII=','base64');
const header='\uFEFF---\r\n# Keep the banner and unrelated metadata exactly\r\nbanner: "![[Images/Project cover.png]]"\r\nbanner_x: 0.25\r\nbanner_y: 0.75\r\ncustom: [one, two]\r\n---\r\n';
const banner=root=>root.locator('.markdown-banner:not(.banner-image-preview)');
async function fresh(page,request){
  const username='banner-'+randomUUID().slice(0,8),password='Private banner acceptance passphrase';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:'Bearer '+process.env.TIMEKEEPER_WEB_TOKEN},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const http=browserRequest(page),session=await(await http.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await http.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':session.csrf,...headers}});expect(reply.ok(),method+' '+path+' '+reply.status()).toBe(true);return reply;};
  await api('GET','/notes');for(const path of ['Notes','Images','Other'])await api('POST','/notes/folders',{path});
  const put=async(path,body)=>(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.isBuffer(body)?body:Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  return {api,put};
}
async function openNote(page,path){
  await page.locator('[data-tab=notes]').click();const root=page.locator('#notes-panel'),expand=root.getByRole('button',{name:'Expand left sidebar',exact:true});if(await expand.isVisible())await expand.click();await root.getByRole('button',{name:'Refresh',exact:true}).first().click();
  const tree=page.locator('#notes-navigation [role=tree]');await expect(tree).toHaveAttribute('aria-busy','false');
  const entry=noteEntry(page.locator('#notes-navigation'),path);if(!await entry.isVisible()){await noteEntry(page.locator('#notes-navigation'),path.split('/')[0]).click();await expect(tree).toHaveAttribute('aria-busy','false');}
  await entry.click();await root.getByRole('button',{name:'Formatted',exact:true}).click();await expect(root.locator('.ProseMirror')).toBeVisible();return root;
}
async function expectBanner(root,position='25% 75%'){
  await expect(banner(root)).toBeVisible();await expect(banner(root)).toHaveCSS('height','112px');await expect(banner(root).locator('img')).toHaveCSS('object-position',position);await expect(banner(root).locator('img')).toHaveCSS('object-fit','cover');await expect(banner(root).locator('img')).toHaveAttribute('alt','');await expect(banner(root)).toHaveAttribute('aria-hidden','true');
  await expect.poll(()=>banner(root).locator('img').evaluate(img=>img.naturalWidth)).toBe(800);expect(await root.locator('.ProseMirror .markdown-banner').count()).toBe(0);
}

test('Obsidian banners stay outside prose and preserve YAML through mode changes, saving and narrow themes',async({page,request})=>{
  const {api,put}=await fresh(page,request);await put('Images/Project cover.png',image);await put('Other/Project cover.png',pixel);
  const source=header+'\r\n# A banner note\r\n\r\nKeep this prose.\r\n',entry=await put('Notes/Banner.md',source),root=await openNote(page,'Notes/Banner.md');await expectBanner(root);
  for(const width of [1440,320])for(const theme of ['timekeeper','timekeeper-dark']){
    await page.setViewportSize({width,height:900});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);
    await expectBanner(root);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await expect.poll(async()=>(await root.locator('.ProseMirror').boundingBox()).height).toBeGreaterThan(150);
    expect((await new AxeBuilder({page}).include('#notes-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
    await captureScreenshot(page,{path:'test-results/obsidian-banner-'+theme+'-'+width+'.png',fullPage:true});
  }
  await root.getByRole('button',{name:'Markdown source',exact:true}).click();await expect(banner(root)).toBeHidden();expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(source);
  await root.getByRole('button',{name:'Formatted',exact:true}).click();await expectBanner(root);await appendEditorText(root.locator('.ProseMirror > p').last(),' Reviewed.');await saveMarkdown(root);
  const expected=source.replace('Keep this prose.','Keep this prose. Reviewed.');expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(expected);
  await page.setViewportSize({width:1440,height:900});await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await openNote(page,'Notes/Banner.md');await expectBanner(root);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(expected);
});

test('missing, ambiguous, invalid and remote banner sources leave notes usable without empty headers',async({page,request})=>{
  const {put}=await fresh(page,request);await put('Images/Project cover.png',image);await put('Other/Project cover.png',pixel);await put('Images/broken.png',Buffer.concat([image.subarray(0,12),Buffer.from('Not a complete PNG')]));
  const cases=[
    ['Missing','banner: "[[missing.png]]"','lookup'],
    ['Ambiguous','banner: "[[Project cover.png]]"','lookup'],
    ['Remote','banner: "[[https://example.invalid/track.png]]"','none'],
    ['Remote URL','banner: "https://example.invalid/track.png"','none'],
    ['Unsupported','banner: "[[Images/file.svg]]"','none'],
    ['Duplicate','banner: "[[Images/Project cover.png]]"\n"banner": "[[Other/Project cover.png]]"','none'],
    ['Broken image','banner: "[[Images/broken.png]]"','decode'],
    ['No banner','custom: retained','none']
  ];
  for(const [name,metadata]of cases)await put('Notes/'+name+'.md','---\n'+metadata+'\n---\n\n# '+name+'\n\nUsable prose.\n');
  const external=[];page.on('request',r=>{if(!r.url().startsWith(new URL(process.env.TIMEKEEPER_WEB_URL).origin)&&!r.url().startsWith('blob:'))external.push(r.url());});
  for(const [name,metadata,kind]of cases){
    const root=await openNote(page,'Notes/'+name+'.md');
    if(kind==='lookup')await expect(banner(root).locator('img')).toHaveAttribute('alt',/ · .+/);
    else await expect(banner(root).locator('img')).toHaveCount(0);
    await expect(banner(root)).toBeHidden();await expect(root.locator('.ProseMirror')).toContainText('Usable prose.');
  }
  expect(external).toEqual([]);
});

for(const destination of ['another note','another account'])test('a late banner image cannot survive navigation to '+destination,async({page,request})=>{
  const {put}=await fresh(page,request),asset=await put('Images/Project cover.png',image);await put('Notes/A.md',header+'\n# First account\n');await put('Notes/B.md','# Another note\n');
  let release,arrived,completed;const started=new Promise(resolve=>{arrived=resolve;}),done=new Promise(resolve=>{completed=resolve;});
  await page.route('**/api/v1/notes/entries/'+asset.id+'/content',async route=>{const response=await route.fetch();expect(response.headers()['x-timekeeper-owner']).toBeTruthy();arrived();await new Promise(resolve=>{release=resolve;});try{await route.fulfill({response});}catch{}finally{completed();}});
  try{
    await openNote(page,'Notes/A.md');await started;await expect(banner(page.locator('#notes-panel'))).toBeHidden();
    if(destination==='another note')await openNote(page,'Notes/B.md');
    else{
      await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login-form')).toBeVisible();
      const second=await fresh(page,request);await second.put('Notes/B.md','# Second account\n');await openNote(page,'Notes/B.md');
    }
    release();await done;await expect(page.locator('#notes-formatted')).toContainText(destination==='another note'?'Another note':'Second account');await expect(banner(page.locator('#notes-panel'))).toBeHidden();await expect(banner(page.locator('#notes-panel')).locator('img')).toHaveCount(0);
  }finally{release?.();await page.unrouteAll({behavior:'wait'});}
});

test('shared daily and weekly journals show banners in writable and read-only sessions',async({page,request,browser})=>{
  const {api,put}=await fresh(page,request);await put('Images/Project cover.png',image);await put('journal/2026-11-02.md',header+'\n# Daily banner\n');await put('journal/2026-W45.md',header+'\n# Weekly banner\n');
  const check=async(target,readOnly=false)=>{
    await target.locator('[data-tab=today]').click();await target.locator('#today-date').fill('2026-11-02');const daily=target.locator('#today-note-editor');await expectBanner(daily);await expect(daily.locator('.ProseMirror')).toHaveAttribute('aria-readonly',String(readOnly));
    await target.locator('#today-month-grid [data-week="2026-W45"]').click();const weekly=target.locator('#today-week-note-editor');await expectBanner(weekly);await expect(weekly.locator('.ProseMirror')).toHaveAttribute('aria-readonly',String(readOnly));await weekly.getByRole('button',{name:'Close',exact:true}).click();
  };
  await check(page);
  // A native body can start with YAML-looking prose. Only the actual outer
  // file header defines its Obsidian banner, not this embedded body fragment.
  const body=header+'\n# Body fragment\n',before=await api('GET','/day-notes/2026-11-03/content');
  await api('PUT','/day-notes/2026-11-03/content',{body},{'If-Match':before.headers().etag});
  const entry=await(await api('GET','/notes/resolve?path=journal%2F2026-11-03.md')).json(),headerRead=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/notes/entries/'+entry.id+'/content');
  await page.locator('#today-date').fill('2026-11-03');await headerRead;await expect(page.locator('#today-journal-formatted')).toContainText('Body fragment');await expect(banner(page.locator('#today-note-editor'))).toBeHidden();
  expect((await(await api('GET','/day-notes/2026-11-03/content')).json()).body).toBe(body);
  // Existing files may consist of metadata alone; an empty body still has a banner.
  await put('journal/2026-11-04.md',header);await page.locator('#today-date').fill('2026-11-04');await expectBanner(page.locator('#today-note-editor'));
  const token=(await(await api('POST','/auth/tokens',{name:'Read-only banners',permissions:['read'],expiresAt:null})).json()).token;
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:process.env.TIMEKEEPER_WEB_URL.startsWith('https:')});
  try{
    const read=await context.newPage();await read.goto('/web/');await read.locator('#auth-mode').selectOption('token');await read.locator('#token').fill(token);await read.locator('#login-form button[type=submit]').click();await expect(read.locator('#status')).toHaveText('Up to date');
    const writes=[];read.on('request',r=>{if(['POST','PUT','PATCH','DELETE'].includes(r.method())&&new URL(r.url()).pathname.startsWith('/api/v1/notes'))writes.push(r.url());});
    await check(read,true);await expect(read.locator('#today-note-editor .markdown-actions-group').filter({hasText:'Change banner image'})).toHaveAttribute('hidden','');const root=await openNote(read,'journal/2026-11-02.md');await expectBanner(root);await expect(root.locator('.ProseMirror')).toHaveAttribute('aria-readonly','true');expect(writes).toEqual([]);
  }finally{await context.close();}
});

async function dailyTool(root,name){await root.getByRole('button',{name:'Note tools',exact:true}).click();await root.getByRole('menuitem',{name,exact:true}).click();}

test('daily seasonal snapshots keep their forecast across custom upload, reload, location changes and restore',async({page,request})=>{
  test.setTimeout(90000);
  const {api,put}=await fresh(page,request);await put('Images/Project cover.png',image);
  const owner=(await api('GET','/me')).headers()['x-timekeeper-owner'];let previews=0;await page.route('**/api/v1/daily-banners/*/preview',route=>{previews++;return route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{version:1,date:'2026-11-16',zoneId:'Europe/Oslo',season:'late-autumn',weather:'snow',provider:'MET Norway',capturedAt:'2026-11-15T10:00:00Z',forecast:[9,12,15].map((hour,index)=>({time:String(hour).padStart(2,'0')+':00',instant:'2026-11-16T'+String(hour-1).padStart(2,'0')+':00:00Z',symbol:'lightsnow',condition:'snow',temperatureCelsius:index-2}))}});});
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill('2026-11-16');const root=page.locator('#today-note-editor');await expect(root.locator('.ProseMirror')).toBeVisible();
  await dailyTool(root,'Add seasonal banner');await expect(banner(root)).toBeVisible();await expect(root.locator('.banner-forecast li')).toHaveCount(3);
  await expect(root.locator('.banner-forecast time')).toHaveText(['09:00','12:00','15:00']);await expect(root.locator('.banner-weather-label')).toHaveText(['Snow','Snow','Snow']);const forecast=await root.locator('.banner-forecast').textContent();
  const entry=await(await api('GET','/notes/resolve?path=journal%2F2026-11-16.md')).json(),original=await(await api('GET','/notes/entries/'+entry.id+'/content')).text();
  await dailyTool(root,'Change banner image');const dialog=page.locator('.banner-image-dialog');await expect(dialog).toBeVisible();await dialog.getByLabel('Upload a local image').setInputFiles({name:'custom.png',mimeType:'image/png',buffer:image});await dialog.getByLabel('Horizontal crop').fill('0.1');await dialog.getByLabel('Vertical crop').fill('0.8');await dialog.getByRole('button',{name:'Save banner',exact:true}).click();
  await expect(banner(root).locator('img')).toHaveCSS('object-position','10% 80%');await expect(root.locator('.banner-forecast')).toHaveText(forecast);
  const custom=await(await api('GET','/notes/entries/'+entry.id+'/content')).text();expect(custom).not.toBe(original);expect(custom.match(/^dagsverk_seasonal_banner:.*$/m)[0]).toBe(original.match(/^dagsverk_seasonal_banner:.*$/m)[0]);
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'Pacific/Auckland',weatherLocation:{latitude:-36.8,longitude:174.7}},{'If-Match':profile.headers().etag});
  await page.unroute('**/api/v1/daily-banners/*/preview');await page.route('**/api/v1/daily-banners/*/preview',()=>{throw new Error('Saved banners must not refetch weather');});
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill('2026-11-16');await expect(banner(root).locator('img')).toHaveCSS('object-position','10% 80%');await expect(root.locator('.banner-forecast')).toHaveText(forecast);
  await page.locator('#today-date').fill('2026-11-17');await expect(banner(root)).toBeHidden();await expect(root.locator('.banner-forecast')).toHaveCount(0);await page.locator('#today-date').fill('2026-11-16');await expect(banner(root)).toBeVisible();
  for(const theme of ['timekeeper','timekeeper-dark']){await page.setViewportSize({width:320,height:900});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);await expect(banner(root)).toHaveCSS('height','112px');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#today-note-editor').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);await captureScreenshot(page,{path:'test-results/seasonal-banner-'+theme+'-320.png',fullPage:true});}
  await dailyTool(root,'Restore seasonal image');await expect(banner(root).locator('img')).toHaveCSS('object-position','50% 50%');await expect(root.locator('.banner-forecast')).toHaveText(forecast);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(original);expect(previews).toBe(1);
});

test('a daily image can come from the vault without changing another day',async({page,request})=>{
  const {api,put}=await fresh(page,request);await put('Images/Project cover.png',image);const other=await put('journal/2026-11-17.md','# Other day\n');await put('journal/2026-11-16.md','# This day\n');
  const root=await openNote(page,'journal/2026-11-16.md');await dailyTool(root,'Change banner image');const dialog=page.locator('.banner-image-dialog');await dialog.getByLabel('Existing vault image').selectOption('Images/Project cover.png');await dialog.getByRole('button',{name:'Save banner',exact:true}).click();await expect(banner(root)).toBeVisible();expect(await(await api('GET','/notes/entries/'+other.id+'/content')).text()).toBe('# Other day\n');
});

test('a failed custom image leaves the saved seasonal restore action available',async({page,request})=>{
  const {put}=await fresh(page,request);await put('Images/Project cover.png',image);await put('Images/broken.png',Buffer.concat([image.subarray(0,12),Buffer.from('Incomplete PNG')]));
  const snapshot={version:1,date:'2026-11-16',zoneId:'Europe/Oslo',season:'late-autumn',seasonal:{reference:'Images/Project cover.png',x:.25,y:.75},forecast:[]};
  await put('journal/2026-11-16.md','---\nbanner: "[[Images/broken.png]]"\ndagsverk_seasonal_banner: '+JSON.stringify(JSON.stringify(snapshot))+'\n---\n\n# Restore me\n');
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill('2026-11-16');const root=page.locator('#today-note-editor');await expect(root.locator('.ProseMirror')).toContainText('Restore me');await expect(banner(root).locator('img')).toHaveCount(0);await expect(banner(root)).toBeHidden();
  await dailyTool(root,'Restore seasonal image');await expectBanner(root);
});
