import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {noteEntry} from './note-entry.js';
import {appendEditorText,saveMarkdown} from './editor-interactions.js';
import {openAccountMenu} from './navigation.js';
import {captureScreenshot} from './screenshots.js';
import {monthCalendar} from '../src/month-model.js';

const image=await readFile(new URL('./fixtures/banner.png',import.meta.url));
const pixel=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jI1sAAAAASUVORK5CYII=','base64');
const header='\uFEFF---\r\n# Keep the banner and unrelated metadata exactly\r\nbanner: "![[Images/Project cover.png]]"\r\nbanner_x: 0.25\r\nbanner_y: 0.75\r\ncustom: [one, two]\r\n---\r\n';
const banner=root=>root.locator('.markdown-banner:not(.banner-image-preview)');
const followingMonth=date=>{const [year,month]=date.split('-').map(Number);return new Date(Date.UTC(year,month,20)).toISOString().slice(0,10);};
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
  await expect(banner(root)).toBeVisible();await expect(banner(root)).toHaveCSS('height','112px');await expect(banner(root).locator('img')).toHaveCSS('object-position',position);await expect(banner(root).locator('img')).toHaveCSS('object-fit','cover');await expect(banner(root).locator('img')).toHaveAttribute('alt','');if(!await root.locator('.banner-forecast-status,.banner-forecast').count())await expect(banner(root)).toHaveAttribute('aria-hidden','true');
  await expect.poll(()=>banner(root).locator('img').evaluate(img=>img.naturalWidth)).toBe(800);expect(await root.locator('.ProseMirror .markdown-banner').count()).toBe(0);
}

test('daily artwork appears automatically at full proportions without creating an empty note',async({page,request})=>{
  const {api}=await fresh(page,request),writes=[];
  page.on('request',r=>{if(['POST','PUT','PATCH','DELETE'].includes(r.method())&&new URL(r.url()).pathname.startsWith('/api/v1/notes'))writes.push(r.url());});
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill('2026-12-01');
  const root=page.locator('#today-note-editor');await expect(root.locator('.ProseMirror')).toBeVisible();await expect(banner(root)).toBeVisible();
  await expect(banner(root).locator('img')).toHaveAttribute('src','/web/assets/seasonal/v1/winter-snow.png');
  await expect(root.locator('.banner-forecast-status')).toContainText('Add a weather location');await expect(root.getByRole('link',{name:'Set weather location',exact:true})).toHaveAttribute('href','#account/profile');
  for(const width of [1200,320])for(const theme of ['timekeeper','timekeeper-dark']){
    await page.setViewportSize({width,height:900});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);
    const image=banner(root).locator('img');await expect.poll(()=>image.evaluate(img=>img.naturalWidth)).toBe(1800);
    const box=await image.boundingBox();expect(Math.abs(box.height-box.width*400/1800)).toBeLessThan(1);await expect(image).toHaveCSS('object-fit','contain');
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    expect((await new AxeBuilder({page}).include('#today-note-editor').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
    await captureScreenshot(page,{path:`test-results/automatic-banner-${theme}-${width}.png`,fullPage:true});
  }
  expect((await(await api('GET','/day-entries/2026-12-01')).json()).version).toBe(0);expect(writes).toEqual([]);
});

test('slow weather and banner writes keep typing editable and preserve newer prose',async({page,request})=>{
  test.setTimeout(90000);const {api,put}=await fresh(page,request),date='2026-11-16';
  let releaseWeather,releaseMetadata,enteredMetadata;const weatherGate=new Promise(resolve=>releaseWeather=resolve),metadataGate=new Promise(resolve=>releaseMetadata=resolve),metadataStarted=new Promise(resolve=>enteredMetadata=resolve);
  const owner=(await api('GET','/me')).headers()['x-timekeeper-owner'];
  await page.route('**/api/v1/daily-banners/*/preview',async route=>{
    const requested=new URL(route.request().url()).pathname.split('/').at(-2);await weatherGate;
    await route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{version:1,date:requested,zoneId:'Europe/Oslo',season:'late-autumn',weather:'snow',provider:'MET Norway',capturedAt:'2026-11-16T08:00:00Z',forecast:[9,12,15].map(hour=>({time:String(hour).padStart(2,'0')+':00',condition:'snow',temperatureCelsius:-2}))}}).catch(()=>{});
  });
  const entry=await put('journal/'+date+'.md','# Weather wait\n\nKeep this prose.\n');
  await page.route('**/api/v1/notes/entries/'+entry.id+'/content',async route=>{
    if(route.request().method()==='PUT'&&route.request().postData()?.includes('dagsverk_seasonal_banner:')){enteredMetadata();await metadataGate;}await route.continue();
  });
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'Europe/Oslo',weatherLocation:{latitude:63.43,longitude:10.39}},{'If-Match':profile.headers().etag});
  try{
    await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(date);
    const root=page.locator('#today-note-editor'),rich=root.locator('.ProseMirror');await expect(rich).toContainText('Keep this prose.');await expect(banner(root)).toBeVisible();
    await expect(root.locator('.banner-forecast-status')).toContainText('Loading forecast');await expect(rich).toHaveAttribute('contenteditable','true');
    await rich.evaluate(node=>node.bannerIdentity='keep-this-editor');await appendEditorText(root.locator('.ProseMirror > p').last(),' Typed while weather waits.');
    await expect.poll(async()=>(await(await api('GET','/day-entries/'+date)).json()).body).toContain('Typed while weather waits.');
    releaseWeather();await metadataStarted;await expect(root.locator('.banner-forecast li')).toHaveCount(3);await expect(rich).toHaveAttribute('contenteditable','true');
    await appendEditorText(root.locator('.ProseMirror > p').last(),' More typing during banner save.');await page.locator('#today-date').click();releaseMetadata();
    await expect.poll(async()=>(await(await api('GET','/day-entries/'+date)).json()).body).toContain('More typing during banner save.');
    const saved=await(await api('GET','/notes/entries/'+entry.id+'/content')).text();expect(saved).toContain('dagsverk_seasonal_banner:');expect(saved).toContain('Typed while weather waits.');expect(saved).toContain('More typing during banner save.');
    expect(await rich.evaluate(node=>node.bannerIdentity)).toBe('keep-this-editor');await expect(root.locator('.banner-forecast li')).toHaveCount(3);
  }finally{releaseWeather();releaseMetadata();}
});



for(const destination of ['linked note','another day','another account'])test('a catalog invalidated by capture retries only for the same note: '+destination,async({page,request})=>{
  test.setTimeout(90000);const {api,put}=await fresh(page,request),date='2026-11-16',target='Capture target';
  await put('Notes/'+target+'.md','# '+target+'\n\nResolved after metadata capture.\n');const entry=await put('journal/'+date+'.md','Follow [['+target+']].\n');
  const owner=(await api('GET','/me')).headers()['x-timekeeper-owner'];let releaseWeather,releaseMetadata,releaseLookup,metadataStarted=false,lookupStarted=false,lookups=0;
  const weatherGate=new Promise(resolve=>releaseWeather=resolve),metadataGate=new Promise(resolve=>releaseMetadata=resolve),lookupGate=new Promise(resolve=>releaseLookup=resolve);
  await page.route('**/api/v1/daily-banners/*/preview',async route=>{await weatherGate;const day=new URL(route.request().url()).pathname.split('/').at(-2);await route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{version:1,date:day,zoneId:'UTC',season:'late-autumn',weather:'cloudy',forecast:[]}}).catch(()=>{});});
  await page.route('**/api/v1/notes/entries/'+entry.id+'/content',async route=>{if(route.request().method()==='PUT'){metadataStarted=true;await metadataGate;}await route.continue();});
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'UTC',weatherLocation:{latitude:63.43,longitude:10.39}},{'If-Match':profile.headers().etag});
  try{
    await page.goto('/web/?day='+date);await expect(page.locator('#status')).toHaveText('Up to date');const root=page.locator('#today-note-editor'),link=root.locator('[data-wiki-reference="'+target+'"]');await expect(link).toHaveAttribute('data-resolved','true');
    releaseWeather();await expect.poll(()=>metadataStarted).toBe(true);
    await page.route('**/api/v1/notes/entries?*',async route=>{
      if(new URL(route.request().url()).searchParams.get('query')!==target)return route.continue();
      lookups++;if(lookups!==1)return route.continue();const response=await route.fetch();lookupStarted=true;await lookupGate;await route.fulfill({response}).catch(()=>{});
    });
    await link.click();await link.dispatchEvent('mouseleave');await link.evaluate(node=>node.blur());await expect.poll(()=>lookupStarted).toBe(true);releaseMetadata();await expect(banner(root).locator('img')).toHaveAttribute('src',/^blob:/);
    if(destination==='another day')await page.locator('#today-date').fill('2026-11-17');else if(destination==='another account'){await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login-form')).toBeVisible();await fresh(page,request);await page.locator('#today-date').fill(date);}releaseLookup();
    if(destination==='linked note'){await expect(page.locator('#notes-document-title')).toHaveText(target);await expect(page.locator('#notes-formatted')).toContainText('Resolved after metadata capture.');expect(lookups).toBeGreaterThan(1);}
    else{await expect(page.locator('#today-date')).toHaveValue(destination==='another account'?date:'2026-11-17');await expect(page.locator('#today-journal-formatted .ProseMirror')).toHaveText('');expect(lookups).toBe(1);await expect(page.locator('#notes-panel')).toBeHidden();}
  }finally{releaseWeather();releaseMetadata();releaseLookup();await page.unrouteAll({behavior:'wait'});}
});

test('automatic presentation leaves imported banner properties untouched',async({page,request})=>{
  const {api,put}=await fresh(page,request);await put('Images/Imported.png',image);const sources=[
    'banner: "[[Images/Imported.png]]"\nbanner_x: 0.25\nbanner_y: 0.75',
    'banner: "https://example.invalid/private-image.png"',
    'dagsverk_seasonal_banner: '+JSON.stringify(JSON.stringify({version:2,private:'future metadata'}))
  ],external=[],writes=[];
  page.on('request',r=>{if(r.url().startsWith('https://example.invalid/'))external.push(r.url());if(r.method()==='PUT'&&new URL(r.url()).pathname.startsWith('/api/v1/notes'))writes.push(r.url());});
  for(let i=0;i<sources.length;i++){
    const date='2026-11-'+(20+i),source='\uFEFF---\r\n'+sources[i].replaceAll('\n','\r\n')+'\r\ncustom: keep exactly\r\n---\r\n\r\nImported daily prose.\r\n',entry=await put('journal/'+date+'.md',source);writes.length=0;
    const read=page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/v1/notes/entries/'+entry.id+'/content');
    await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(date);await read;
    const root=page.locator('#today-note-editor');await expect(root.locator('.ProseMirror')).toContainText('Imported daily prose.');await root.getByRole('button',{name:'Formatted',exact:true}).press('ControlOrMeta+s');
    expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(source);expect(writes).toEqual([]);
  }
  expect(external).toEqual([]);
});

test('a saved seasonal picture appears before its private image finishes loading',async({page,request})=>{
  const {api,put}=await fresh(page,request),date='2026-11-16';
  const artwork=await readFile(new URL('../src/assets/seasonal/v1/late-autumn-snow.png',import.meta.url)),asset=await put('Images/Saved season.png',artwork);
  const snapshot={version:1,date,zoneId:'Europe/Oslo',season:'late-autumn',seasonal:{reference:asset.path,id:'late-autumn-snow',version:1,x:.5,y:.5},forecast:[]};
  await put('journal/'+date+'.md','---\nbanner: '+JSON.stringify('![['+asset.path+']]')+'\ndagsverk_seasonal_banner: '+JSON.stringify(JSON.stringify(snapshot))+'\n---\n\nKeep writing while the picture loads.\n');
  let release,arrived;const gate=new Promise(resolve=>release=resolve),started=new Promise(resolve=>arrived=resolve);
  await page.route('**/api/v1/notes/entries/'+asset.id+'/content',async route=>{arrived();await gate;await route.continue();});
  try{
    await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(date);const root=page.locator('#today-note-editor');await started;
    await expect(banner(root).locator('img')).toHaveAttribute('src','/web/assets/seasonal/v1/late-autumn-snow.png');await expect(root.locator('.ProseMirror')).toContainText('Keep writing');await expect(root.locator('.ProseMirror')).toHaveAttribute('contenteditable','true');
    release();await expect(banner(root).locator('img')).toHaveAttribute('src',/^blob:/);await expect(banner(root).locator('img')).toHaveCount(1);await expect(banner(root).locator('img')).toHaveCSS('object-fit','contain');
    expect((await(await api('GET','/day-entries/'+date)).json()).body).toBe('\nKeep writing while the picture loads.\n');
  }finally{release();}
});

test('an existing empty forecast fills after location setup while keeping its custom image and metadata',async({page,request})=>{
  // Keep the runtime's current day different from the saved note across reloads.
  await page.clock.setFixedTime(new Date('2026-11-17T12:00:00Z'));
  const {api,put}=await fresh(page,request),date='2026-11-16';await put('Images/Project cover.png',image);
  const snapshot={version:1,date,zoneId:'Europe/Oslo',season:'late-autumn',seasonal:{reference:'Images/Project cover.png',x:.25,y:.75},forecast:[]};
  const source=header.replace('custom: [one, two]','custom: [one, two]\r\ndagsverk_seasonal_banner: '+JSON.stringify(JSON.stringify(snapshot)))+'\r\nKeep the imported prose.\r\n',entry=await put('journal/'+date+'.md',source);
  const owner=(await api('GET','/me')).headers()['x-timekeeper-owner'];const previews=[];
  await page.route('**/api/v1/daily-banners/*/preview',route=>{const requested=new URL(route.request().url()).pathname.split('/').at(-2);previews.push(requested);return route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{version:1,date:requested,zoneId:'Europe/Oslo',season:'late-autumn',weather:'rain',forecast:[9,12,15].map(hour=>({time:String(hour).padStart(2,'0')+':00',condition:'rain',temperatureCelsius:4}))}});});
  await page.goto('/web/?day='+date);await expect(page.locator('#status')).toHaveText('Up to date');await expect(page.locator('#today-date')).toHaveValue(date);const root=page.locator('#today-note-editor');await expect(root.locator('.banner-forecast-status')).toContainText('Add a weather location');expect(previews).toEqual([]);
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'Europe/Oslo',weatherLocation:{latitude:63.43,longitude:10.39}},{'If-Match':profile.headers().etag});
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await expect(page.locator('#today-date')).toHaveValue(date);await expect(root.locator('.banner-forecast li')).toHaveCount(3);
  await expect.poll(async()=>(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).includes('temperatureCelsius')).toBe(true);
  const saved=await(await api('GET','/notes/entries/'+entry.id+'/content')).text();expect(saved).toContain('# Keep the banner and unrelated metadata exactly\r\n');expect(saved).toContain('custom: [one, two]\r\n');expect(saved.endsWith('\r\nKeep the imported prose.\r\n')).toBe(true);await expectBanner(root);
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await expect(page.locator('#today-date')).toHaveValue(date);await expect(root.locator('.banner-forecast li')).toHaveCount(3);expect(previews).toEqual([date]);
});

for(const host of ['Today','Notes'])test('automatic capture preserves a BOM and newer prose in '+host,async({page,request})=>{
  const {api,put}=await fresh(page,request),date='2026-11-16',source='\uFEFF# Original title\r\n\r\nKeep this prose.\r\n',entry=await put('journal/'+date+'.md',source);
  let release,arrived,captured;const gate=new Promise(resolve=>release=resolve),started=new Promise(resolve=>arrived=resolve);
  await page.route('**/api/v1/notes/entries/'+entry.id+'/content',async route=>{if(!captured&&route.request().method()==='PUT'&&route.request().postData()?.includes('dagsverk_seasonal_banner:')){captured=route.request().postData();arrived();await gate;}await route.continue();});
  try{
    let root;if(host==='Notes')root=await openNote(page,'journal/'+date+'.md');else{await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(date);root=page.locator('#today-note-editor');}
    await started;await appendEditorText(root.locator('.ProseMirror > p').last(),' New draft.');release();
    await expect.poll(async()=>(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).includes('New draft.')).toBe(true);
    const saved=await(await api('GET','/notes/entries/'+entry.id+'/content')).text();expect(saved.startsWith('\uFEFF---\r\n')).toBe(true);expect(saved.match(/\uFEFF/g)).toHaveLength(1);expect(captured.endsWith(source.slice(1))).toBe(true);expect(saved).toContain('# Original title');expect(saved).toContain('Keep this prose. New draft.');await expect(root.locator('.ProseMirror')).toContainText('New draft.');
    await root.getByRole('button',{name:'Formatted',exact:true}).press('ControlOrMeta+z');await expect(root.locator('.ProseMirror')).not.toContainText('New draft.');await saveMarkdown(root);const undone=await(await api('GET','/notes/entries/'+entry.id+'/content')).text();expect(undone).toContain('dagsverk_seasonal_banner:');expect(undone.endsWith(source.slice(1))).toBe(true);
  }finally{release();}
});

for(const outcome of ['stale','uncertain'])test((outcome==='uncertain'?'an ':'a ')+outcome+' automatic banner save keeps newer prose without replaying the write',async({page,request})=>{
  const {api,put}=await fresh(page,request),date='2026-11-16',entry=await put('journal/'+date+'.md','# Saved version\n\nKeep this prose.\n');
  let release,arrived,writes=0;const gate=new Promise(resolve=>release=resolve),started=new Promise(resolve=>arrived=resolve);
  await page.route('**/api/v1/notes/entries/'+entry.id+'/content',async route=>{
    if(route.request().method()!=='PUT')return route.continue();writes++;arrived();await gate;
    if(outcome==='uncertain'){await route.fetch();await route.abort('connectionreset');}else await route.continue();
  });
  try{
    await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(date);const root=page.locator('#today-note-editor');await started;await appendEditorText(root.locator('.ProseMirror > p').last(),' Unsaved newer prose.');
    if(outcome==='stale'){const current=await api('GET','/notes/entries/'+entry.id+'/content');await api('PUT','/notes/entries/'+entry.id+'/content',Buffer.from('# Peer version\n'),{'Content-Type':'application/octet-stream','If-Match':current.headers().etag});}
    release();await expect(root.getByRole('button',{name:outcome==='uncertain'?'Check saved result':'Reload saved note',exact:true})).toBeVisible();await expect(root.locator('.ProseMirror')).toContainText('Unsaved newer prose.');
    await root.getByRole('button',{name:'Formatted',exact:true}).press('ControlOrMeta+s');expect(writes).toBe(1);
    if(outcome==='stale')expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('# Peer version\n');
    else{await root.getByRole('button',{name:'Check saved result',exact:true}).click();await expect.poll(async()=>(await(await api('GET','/day-entries/'+date)).json()).body).toContain('Unsaved newer prose.');expect(writes).toBe(1);}
  }finally{release();}
});

for(const destination of ['another day','another account'])test('late automatic weather cannot populate '+destination,async({page,request})=>{
  const {api}=await fresh(page,request),date='2026-11-16',owner=(await api('GET','/me')).headers()['x-timekeeper-owner'];
  let release,arrived;const gate=new Promise(resolve=>release=resolve),started=new Promise(resolve=>arrived=resolve);
  await page.route('**/api/v1/daily-banners/*/preview',async route=>{const requested=new URL(route.request().url()).pathname.split('/').at(-2);if(requested===date){arrived();await gate;}await route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{version:1,date:requested,zoneId:'Europe/Oslo',season:requested==='2026-12-01'?'winter':'late-autumn',weather:'snow',forecast:requested===date?[9,12,15].map(hour=>({time:String(hour).padStart(2,'0')+':00',condition:'snow',temperatureCelsius:-99})):[]}}).catch(()=>{});});
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'Europe/Oslo',weatherLocation:{latitude:63.43,longitude:10.39}},{'If-Match':profile.headers().etag});
  try{
    await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('#today-date').fill(date);await started;
    if(destination==='another day')await page.locator('#today-date').fill('2026-12-01');else{await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#login-form')).toBeVisible();await fresh(page,request);await page.locator('#today-date').fill('2026-12-01');}
    release();const root=page.locator('#today-note-editor');await expect(banner(root).locator('img')).toHaveAttribute('src','/web/assets/seasonal/v1/winter-snow.png');await expect(root.locator('.banner-forecast li')).toHaveCount(0);expect((await(await api('GET','/day-entries/'+date)).json()).version).toBe(0);
  }finally{release();}
});

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
  const {api,put}=await fresh(page,request);await put('Images/Project cover.png',image);await put('journal/2026-11-02.md',header+'\n# Daily banner\n');await put('journal/2026-W45.md',header+'\n# Weekly banner\n');await put('journal/2026-11-04.md',header);
  const check=async(target,readOnly=false)=>{
    await target.locator('[data-tab=today]').click();await target.locator('#today-date').fill('2026-11-02');const daily=target.locator('#today-note-editor');await expectBanner(daily);await expect(daily.locator('.ProseMirror')).toHaveAttribute('aria-readonly',String(readOnly));
    await target.locator('#today-month-grid [data-week="2026-W45"]').click();const weekly=target.locator('#today-week-note-editor');await expectBanner(weekly);await expect(weekly.locator('.ProseMirror')).toHaveAttribute('aria-readonly',String(readOnly));await weekly.getByRole('button',{name:'Close',exact:true}).click();
  };
  await check(page);
  // A native body can start with YAML-looking prose. Only the actual outer
  // file header defines its Obsidian banner, not this embedded body fragment.
  const body=header+'\n# Body fragment\n',before=await api('GET','/day-notes/2026-11-03/content');
  await api('PUT','/day-notes/2026-11-03/content',{body},{'If-Match':before.headers().etag});
  const entryRead=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/day-entries/2026-11-03');
  await page.locator('#today-date').fill('2026-11-03');expect((await(await entryRead).json()).frontmatter).not.toContain('banner:');await expect(page.locator('#today-journal-formatted')).toContainText('Body fragment');await expect(banner(page.locator('#today-note-editor'))).toBeVisible();await expect(banner(page.locator('#today-note-editor')).locator('img')).toHaveAttribute('src',/^(?:\/web\/assets\/seasonal|blob:)/);
  expect((await(await api('GET','/day-notes/2026-11-03/content')).json()).body).toBe(body);
  // Native ambiguous prose uses an empty outer YAML map. Capture must produce
  // valid metadata before navigation can drain its background write.
  await expect.poll(async()=>(await(await api('GET','/day-entries/2026-11-03')).json()).frontmatter).toContain('dagsverk_seasonal_banner:');
  // Existing files may consist of metadata alone; an empty body still has a banner.
  const metadataRead=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/day-entries/2026-11-04');await page.locator('#today-date').fill('2026-11-04');expect((await(await metadataRead).json()).frontmatter).toContain('banner:');await expectBanner(page.locator('#today-note-editor'));
  const token=(await(await api('POST','/auth/tokens',{name:'Read-only banners',permissions:['read'],expiresAt:null})).json()).token;
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:process.env.TIMEKEEPER_WEB_URL.startsWith('https:')});
  try{
    const read=await context.newPage();await read.goto('/web/');await read.locator('#auth-mode').selectOption('token');await read.locator('#token').fill(token);await read.locator('#login-form button[type=submit]').click();await expect(read.locator('#status')).toHaveText('Up to date');
    const writes=[];read.on('request',r=>{if(['POST','PUT','PATCH','DELETE'].includes(r.method())&&new URL(r.url()).pathname.startsWith('/api/v1/notes'))writes.push(r.url());});
    await check(read,true);await read.locator('#today-date').fill('2026-12-01');await expect(banner(read.locator('#today-note-editor'))).toBeVisible();expect((await(await api('GET','/day-entries/2026-12-01')).json()).version).toBe(0);await read.locator('#today-date').fill('2026-11-02');await expect(read.locator('#today-note-editor .markdown-actions-group').filter({hasText:'Change banner image'})).toHaveAttribute('hidden','');const root=await openNote(read,'journal/2026-11-02.md');await expectBanner(root);await expect(root.locator('.ProseMirror')).toHaveAttribute('aria-readonly','true');expect(writes).toEqual([]);
  }finally{await context.close();}
});

async function dailyTool(root,name){await root.getByRole('button',{name:'Note tools',exact:true}).click();await root.getByRole('menuitem',{name,exact:true}).click();}

test('daily seasonal snapshots keep their forecast across custom upload, reload, location changes and restore',async({page,request})=>{
  test.setTimeout(90000);
  const {api,put}=await fresh(page,request);await put('Images/Project cover.png',image);
  let allowPreview;const previewGate=new Promise(resolve=>allowPreview=resolve);
  const owner=(await api('GET','/me')).headers()['x-timekeeper-owner'];let previews=0;await page.route('**/api/v1/daily-banners/*/preview',async route=>{previews++;await previewGate;return route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{version:1,date:'2026-11-16',zoneId:'Europe/Oslo',season:'late-autumn',weather:'snow',provider:'MET Norway',capturedAt:'2026-11-15T10:00:00Z',forecast:[9,12,15].map((hour,index)=>({time:String(hour).padStart(2,'0')+':00',instant:'2026-11-16T'+String(hour-1).padStart(2,'0')+':00:00Z',symbol:'lightsnow',condition:'snow',temperatureCelsius:index-2}))}});});
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill('2026-11-16');const root=page.locator('#today-note-editor');await expect(root.locator('.ProseMirror')).toBeVisible();
  const previewRequest=page.waitForRequest('**/api/v1/daily-banners/*/preview');await dailyTool(root,'Add seasonal banner');await previewRequest;
  try{await expect(root.locator('.ProseMirror')).toHaveAttribute('aria-readonly','true');}finally{allowPreview();}
  await expect(banner(root)).toBeVisible();await expect(root.locator('.banner-forecast li')).toHaveCount(3);await expect(root.locator('.ProseMirror')).toHaveAttribute('aria-readonly','false');
  await expect(root.locator('.banner-forecast time')).toHaveText(['09:00','12:00','15:00']);await expect(root.locator('.banner-weather-label')).toHaveText(['Snow','Snow','Snow']);const forecast=await root.locator('.banner-forecast').textContent();
  const entry=await(await api('GET','/notes/resolve?path=journal%2F2026-11-16.md')).json(),original=await(await api('GET','/notes/entries/'+entry.id+'/content')).text();
  await dailyTool(root,'Change banner image');const dialog=page.locator('.banner-image-dialog');await expect(dialog).toBeVisible();await dialog.getByLabel('Upload a local image').setInputFiles({name:'custom.png',mimeType:'image/png',buffer:image});await dialog.getByLabel('Horizontal crop').fill('0.1');await dialog.getByLabel('Vertical crop').fill('0.8');await dialog.getByRole('button',{name:'Save banner',exact:true}).click();
  await expect(banner(root).locator('img')).toHaveCSS('object-position','10% 80%');await expect(root.locator('.banner-forecast')).toHaveText(forecast);
  const custom=await(await api('GET','/notes/entries/'+entry.id+'/content')).text();expect(custom).not.toBe(original);expect(custom.match(/^dagsverk_seasonal_banner:.*$/m)[0]).toBe(original.match(/^dagsverk_seasonal_banner:.*$/m)[0]);
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'Pacific/Auckland',weatherLocation:{latitude:-36.8,longitude:174.7}},{'If-Match':profile.headers().etag});
  await page.unroute('**/api/v1/daily-banners/*/preview');await page.route('**/api/v1/daily-banners/*/preview',route=>{const date=new URL(route.request().url()).pathname.split('/').at(-2);expect(date).not.toBe('2026-11-16');return route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{version:1,date,zoneId:'Pacific/Auckland',season:'spring',weather:'cloudy',forecast:[]}});});
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill('2026-11-16');await expect(banner(root).locator('img')).toHaveCSS('object-position','10% 80%');await expect(root.locator('.banner-forecast')).toHaveText(forecast);
  await page.locator('#today-date').fill('2026-11-17');await expect(banner(root)).toBeVisible();await expect(root.locator('.banner-forecast')).toHaveCount(0);await page.locator('#today-date').fill('2026-11-16');await expect(banner(root)).toBeVisible();
  for(const theme of ['timekeeper','timekeeper-dark']){await page.setViewportSize({width:320,height:900});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);await expect(banner(root)).toHaveCSS('height','112px');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect((await new AxeBuilder({page}).include('#today-note-editor').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);await captureScreenshot(page,{path:'test-results/seasonal-banner-'+theme+'-320.png',fullPage:true});}
  await dailyTool(root,'Restore seasonal image');await expect(banner(root).locator('img')).toHaveCSS('object-position','50% 50%');await expect(root.locator('.banner-forecast')).toHaveText(forecast);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(original);expect(previews).toBe(1);
});

test('a daily image can come from the vault without changing another day',async({page,request})=>{
  const {api,put}=await fresh(page,request);await put('Images/Project cover.png',image);const other=await put('journal/2026-11-17.md','# Other day\n');await put('journal/2026-11-16.md','# This day\n');
  const root=await openNote(page,'journal/2026-11-16.md');await dailyTool(root,'Change banner image');const dialog=page.locator('.banner-image-dialog');await dialog.getByLabel('Existing vault image').selectOption('Images/Project cover.png');await dialog.getByRole('button',{name:'Save banner',exact:true}).click();await expect(banner(root)).toBeVisible();expect(await(await api('GET','/notes/entries/'+other.id+'/content')).text()).toBe('# Other day\n');
});

test('a failed custom image leaves the saved seasonal restore action available',async({page,request})=>{
  const {put}=await fresh(page,request);await put('Images/Project cover.png',image);await put('Images/broken.png',Buffer.concat([image.subarray(0,12),Buffer.from('Incomplete PNG')]));
  const date=await page.locator('#today-date').inputValue();await expect(page.locator('#today-note-editor .ProseMirror')).toHaveText('');
  const snapshot={version:1,date,zoneId:'Europe/Oslo',season:'late-autumn',seasonal:{reference:'Images/Project cover.png',x:.25,y:.75},forecast:[]};
  await put('journal/'+date+'.md','---\nbanner: "[[Images/broken.png]]"\ndagsverk_seasonal_banner: '+JSON.stringify(JSON.stringify(snapshot))+'\n---\n\n# Restore me\n');
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(date);const root=page.locator('#today-note-editor');await expect(root.locator('.ProseMirror')).toContainText('Restore me');await expect(banner(root).locator('img')).toHaveCount(0);await expect(banner(root)).toBeHidden();
  await dailyTool(root,'Restore seasonal image');await expectBanner(root);
});

test('daily banners render from the note snapshot without another full-document read',async({page,request})=>{
  const {put}=await fresh(page,request);const date=followingMonth(await page.locator('#today-date').inputValue());await put('Images/Project cover.png',image);const saved={version:1,date,zoneId:'UTC',season:'winter',seasonal:{reference:'Images/Project cover.png',x:.25,y:.75},forecast:[]};const entry=await put('journal/'+date+'.md',header.replace('custom: [one, two]','custom: [one, two]\r\ndagsverk_seasonal_banner: '+JSON.stringify(JSON.stringify(saved)))+'\n# Immediate banner\n');
  const redundant=[];await page.route('**/api/v1/notes/entries/'+entry.id+'/content',route=>{redundant.push(route.request().url());return route.abort('failed');});
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(date);const root=page.locator('#today-note-editor');await expect(root.locator('.ProseMirror')).toContainText('Immediate banner');await expectBanner(root);expect(redundant).toEqual([]);
});

test('the selected daily note loads before serialized month metadata reads',async({page,request})=>{
  const {put}=await fresh(page,request);const date=followingMonth(await page.locator('#today-date').inputValue());await put('journal/'+date+'.md','# Selected note first\n');
  const first=monthCalendar(date.slice(0,7),'UTC').days[0].date,pattern=/\/api\/v1\/(?:day-entries\/|day-notes\?)/;
  let allowMetadata;const gate=new Promise(resolve=>allowMetadata=resolve);let tail=Promise.resolve();const calls=[],pending=[];
  const handle=route=>{
    const url=new URL(route.request().url()),selected=url.pathname==='/api/v1/day-entries/'+date;
    if(!selected&&url.searchParams.get('fromDate')!==first)return route.continue();
    const previous=tail;let release;tail=new Promise(resolve=>release=resolve);
    const work=(async()=>{await previous;try{calls.push(url.pathname);if(!selected)await gate;const response=await route.fetch();await route.fulfill({response});}finally{release();}})();pending.push(work);return work;
  };
  await page.route(pattern,handle);
  try{await page.locator('#today-date').fill(date);await expect(page.locator('#today-note-editor .ProseMirror')).toContainText('Selected note first');expect(calls[0]).toBe('/api/v1/day-entries/'+date);}
  finally{allowMetadata();await Promise.all(pending);await page.unroute(pattern,handle);}
});

test('returning to a clean daily note refreshes peer edits without changing source mode',async({page,request})=>{
  const {api,put}=await fresh(page,request);const date=followingMonth(await page.locator('#today-date').inputValue()),entry=await put('journal/'+date+'.md','# Before peer edit\n');
  await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(date);const root=page.locator('#today-note-editor');await expect(root.locator('.ProseMirror')).toContainText('Before peer edit');
  // Read the peer validator after the automatic metadata write has settled.
  await expect(banner(root).locator('img')).toHaveAttribute('src',/^blob:/);await root.getByRole('button',{name:'Markdown source',exact:true}).click();
  const current=await api('GET','/notes/entries/'+entry.id+'/content');await api('PUT','/notes/entries/'+entry.id+'/content',Buffer.from('# After peer edit\n'),{'Content-Type':'application/octet-stream','If-Match':current.headers().etag});
  await page.locator('[data-tab=today]').click();await expect(root.locator('textarea')).toHaveValue('# After peer edit\n');await expect(root.getByRole('button',{name:'Markdown source',exact:true})).toHaveAttribute('aria-pressed','true');
});
