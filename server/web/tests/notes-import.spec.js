import {clickNoteAction} from './editor-interactions.js';
import {noteEntry} from './note-entry.js';
import {searchNotes} from './notes-search.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {mkdtemp,mkdir,writeFile,truncate,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {openAccountMenu} from './navigation.js';
import {captureScreenshot} from './screenshots.js';

async function setup(page,request){
  const username='import-'+randomUUID().slice(0,8),password='Private import test passphrase';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,email:username+'@example.invalid',displayName:username,administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),method+' '+path+': '+reply.status()).toBe(true);return reply;};
  await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});await page.evaluate(()=>location.hash='#account/notes');
  const card=page.locator('#account-content section').filter({has:page.getByRole('heading',{name:'Import notes',exact:true})});await expect(card).toBeVisible();return {api,client,card};
}
async function choose(page,card,root){const choice=page.waitForEvent('filechooser');await card.getByRole('button',{name:'Choose vault folder',exact:true}).click();await(await choice).setFiles(root);}
const put=(api,path,body,dates={})=>api('PUT','/notes/files?'+new URLSearchParams({path,...dates}),Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
const resolve=async(client,path)=>client.get('/api/v1/notes/resolve?'+new URLSearchParams({path}));

test('selecting files and folders limits uploads, explains oversized files and reports real progress accessibly',async({page,request})=>{
  const {api,client,card}=await setup(page,request),root=await mkdtemp(join(tmpdir(),'notes-selection-'));let release;
  try{
    const maximum=(await(await api('GET','/notes')).json()).limits.markdownBytes,bytes=new Intl.NumberFormat('en-GB');
    for(const folder of ['Notes','SkipFolder','.git'])await mkdir(join(root,folder));
    for(const [path,body]of [['Notes/Included.md','Keep'],['Notes/Skip.md','Exclude'],['SkipFolder/Nested.md','Exclude folder'],['.git/config','Hidden'],['Notes/Oversized.md','']])await writeFile(join(root,path),body);
    // The review must skip files above the server's actual limit. A sparse file
    // exercises that boundary without materializing a quota-sized test buffer.
    await truncate(join(root,'Notes/Oversized.md'),maximum+1);
    await choose(page,card,root);await expect(card).toContainText('3 new files ready to import');
    const oversized=card.locator('.notes-import-row').filter({hasText:'Oversized.md'});await expect(oversized).toContainText(`${bytes.format(maximum+1)} bytes exceeds the ${bytes.format(maximum)}-byte limit`);await expect(oversized.getByRole('checkbox')).toBeDisabled();
    await card.getByRole('checkbox',{name:'SkipFolder/',exact:true}).uncheck();await card.getByRole('checkbox',{name:'Notes/Skip.md',exact:true}).uncheck();expect(await card.getByRole('checkbox',{name:'Notes/',exact:true}).evaluate(input=>input.indeterminate)).toBe(true);
    const hold=new Promise(resolve=>{release=resolve;}),uploads=[];
    await page.route('**/api/v1/notes/files?**',async route=>{uploads.push(new URL(route.request().url()).searchParams.get('path'));await hold;await route.continue();});
    await card.getByRole('button',{name:'Import new files',exact:true}).click();await expect.poll(()=>uploads.length).toBe(1);
    const progress=card.getByRole('progressbar',{name:'Notes import progress',exact:true});await expect(progress).toBeVisible();await expect(progress).toHaveAttribute('aria-valuenow','0');await expect(progress).toHaveAttribute('aria-valuetext','0 of 1 selected items processed');
    await expect(card.getByRole('checkbox',{name:'Notes/Included.md',exact:true})).toBeDisabled();release();await expect(card).toContainText('Imported 1 file.');await expect(progress).toHaveAttribute('aria-valuenow','100');expect(uploads).toEqual(['Notes/Included.md']);
    expect((await resolve(client,'Notes/Included.md')).ok()).toBe(true);for(const path of ['Notes/Skip.md','SkipFolder/Nested.md','Notes/Oversized.md'])expect((await resolve(client,path)).status()).toBe(404);
    for(const width of [1440,320]){
      await page.setViewportSize({width,height:900});for(const theme of width===320?['timekeeper','timekeeper-dark']:['timekeeper']){
        await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);
        expect((await new AxeBuilder({page}).include('.notes-import-review').analyze()).violations).toEqual([]);
      }
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await captureScreenshot(page,{path:`test-results/notes-import-selection-${width}.png`,fullPage:true});
    }
  }finally{release?.();await page.unrouteAll({behavior:'wait'});await rm(root,{recursive:true,force:true});}
});

test('prepared ZIP download reimports exact notes, assets, empty folders and nanosecond dates into another private account',async({page,request})=>{
  const first=await setup(page,request),dates={createdAt:'2020-01-02T03:04:05.123456789Z',modifiedAt:'2021-02-03T04:05:06.987654321Z'},root=await mkdtemp(join(tmpdir(),'notes-roundtrip-'));
  const files=new Map([['Notes/Exact.md',Buffer.from('---\r\ncustom: x\r\n---\r\n[[Link]]  \r\n')],['journal/2026-11-04.md',Buffer.from('- [ ] Exact journal\r\n')],['journal/2026-W45.md',Buffer.from('Week\r\n')],['journal/assets/raw.bin',Buffer.from([0,255,4])],['timekeeper-notes-metadata.json',Buffer.from('A real user file')]]);
  try{
    await first.api('POST','/notes/folders',{path:'Empty',...dates});for(const [path,body]of files)await put(first.api,path,body,dates);
    const downloads=page.locator('#account-content section').filter({has:page.getByRole('heading',{name:'Export notes',exact:true})});await downloads.getByRole('button',{name:'Prepare archive',exact:true}).click();
    const link=downloads.getByRole('link',{name:'Download archive',exact:true});await expect(link).toBeVisible();await expect(link).toHaveAttribute('href',/^blob:/);
    const pending=page.waitForEvent('download');await link.click();const download=await pending;expect(download.suggestedFilename()).toMatch(/^timekeeper-notes-.*\.zip$/);const archive=join(root,'notes.zip');await download.saveAs(archive);
    const vault=join(root,'vault');await mkdir(vault);await promisify(execFile)('python3',['-c','import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])',archive,vault]);
    await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();const second=await setup(page,request);
    const label=await second.api('GET','/day-labels/2026-11-04');await second.api('PUT','/day-labels/2026-11-04',{text:'Target label'},{'If-Match':label.headers().etag});
    await choose(page,second.card,vault);await expect(second.card).toContainText('5 new files ready to import');await second.card.getByRole('button',{name:'Import new files',exact:true}).click();await expect(second.card).toContainText('Imported 5 files.');
    for(const [path,body]of files){const entry=await(await second.api('GET','/notes/resolve?'+new URLSearchParams({path}))).json();expect(entry.createdAt).toBe(dates.createdAt);expect(entry.modifiedAt).toBe(dates.modifiedAt);expect(Buffer.from(await(await second.api('GET','/notes/entries/'+entry.id+'/content')).body()).equals(body)).toBe(true);}
    const empty=await(await second.api('GET','/notes/resolve?path=Empty')).json();expect(empty.kind).toBe('FOLDER');expect(empty.createdAt).toBe(dates.createdAt);expect(empty.modifiedAt).toBe(dates.modifiedAt);
    expect((await(await second.api('GET','/day-labels/2026-11-04')).json()).text).toBe('Target label');
  }finally{await rm(root,{recursive:true,force:true});}
});

test('invalid timestamp metadata blocks import and cancelling an in-flight review sends no writes',async({page,request})=>{
  const {client,card}=await setup(page,request),root=await mkdtemp(join(tmpdir(),'notes-review-'));let release;
  try{
    await writeFile(join(root,'Exact.md'),'Body');await writeFile(join(root,'timekeeper-notes-metadata.json'),'invalid metadata');await choose(page,card,root);await expect(card).toContainText('timestamp metadata is invalid');await expect(card.getByRole('button',{name:'Import new files',exact:true})).toBeDisabled();
    await rm(join(root,'timekeeper-notes-metadata.json'));let blocked=false;const hold=new Promise(resolve=>{release=resolve;}),writes=[];
    await page.route('**/api/v1/notes/resolve?**',async route=>{blocked=true;await hold;await route.continue();});page.on('request',request=>{if(request.url().includes('/api/v1/notes')&&request.method()!=='GET')writes.push(request.method());});
    await choose(page,card,root);await expect.poll(()=>blocked).toBe(true);await card.getByRole('button',{name:'Cancel',exact:true}).click();release();await expect(card.locator('.notes-import-review')).not.toBeVisible();await expect(card.getByRole('button',{name:'Choose vault folder',exact:true})).toBeEnabled();
    await page.unrouteAll({behavior:'wait'});expect((await resolve(client,'Exact.md')).status()).toBe(404);expect(writes).toEqual([]);
  }finally{release?.();await page.unrouteAll({behavior:'wait'});await rm(root,{recursive:true,force:true});}
});

test('files named by export metadata but absent from the folder selection are identified and never fabricated',async({page,request})=>{
  const {client,card}=await setup(page,request),root=await mkdtemp(join(tmpdir(),'notes-missing-'));
  try{
    await writeFile(join(root,'Real.md'),'Body');
    await writeFile(join(root,'timekeeper-notes-metadata.json'),JSON.stringify({format:'timekeeper-notes-v1',entries:[{path:'.private.md',kind:'FILE',bytes:0,sha256:'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'}]}));
    await choose(page,card,root);await expect(card).toContainText('1 new file ready to import');
    const missing=card.locator('.notes-import-row').filter({hasText:'.private.md'});await expect(missing).toContainText('not provided by the folder picker');await expect(missing.getByRole('checkbox')).toBeDisabled();
    await card.getByRole('button',{name:'Import new files',exact:true}).click();await expect(card).toContainText('Imported 1 file.');expect((await resolve(client,'Real.md')).ok()).toBe(true);expect((await resolve(client,'.private.md')).status()).toBe(404);
  }finally{await rm(root,{recursive:true,force:true});}
});


test('Obsidian imports use filenames across navigation, recent notes and search while formatting callouts and opening PDF attachments',async({page,request})=>{
  const {api,client,card}=await setup(page,request),root=await mkdtemp(join(tmpdir(),'notes-obsidian-'));
  const path='Notes/Security review.md',source='>[!IMPORTANT] Viktig!\r\n> Dette er et arbeidsdokument.\r\n\r\nKort oppsummert:\r\nPakken inneholder _Security_.\r\n\r\n## Må avklares\r\n- [ ] Sjekk `ServiceAccount`\r\n\r\nAnbefalt video:\r\n<iframe src="https://www.youtube-nocookie.com/embed/example" title="Video"></iframe>\r\n\r\n![[security-datasheet.pdf]]\r\n';
  try{
    await mkdir(join(root,'Notes'));await writeFile(join(root,path),source);await writeFile(join(root,'Notes/security-datasheet.pdf'),'%PDF-1.4\nSynthetic attachment\n');
    const external=[];page.on('request',request=>{if(!request.url().startsWith(new URL(process.env.TIMEKEEPER_WEB_URL).origin)&&!request.url().startsWith('blob:'))external.push(request.url());});
    await choose(page,card,root);await expect(card).toContainText('2 new files ready to import');await card.getByRole('button',{name:'Import new files',exact:true}).click();await expect(card).toContainText('Imported 2 files.');
    const entry=await(await resolve(client,path)).json();expect(entry.title).toBe('Security review');expect(entry.heading).toBe('Må avklares');
    expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(source);
    await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await page.locator('[data-tab=notes]').click();await noteEntry(page.locator('#notes-navigation'),'Notes').click();
    const item=noteEntry(page.locator('#notes-navigation'),path);await expect(item).toHaveText('Security review');await expect(item).toHaveAccessibleName(path);await item.click();
    await expect(page.locator('#notes-document-title')).toHaveText('Security review');await expect(page.locator('#notes-formatted .markdown-callout input')).toHaveValue('Viktig!');await expect(page.locator('#notes-formatted h2')).toHaveText('Må avklares');await expect(page.locator('#notes-formatted').getByRole('checkbox')).toHaveCount(1);await expect(page.locator('#notes-formatted iframe')).toHaveCount(0);
    await expect(page.locator('#notes-recent-notes [data-recent-note="'+entry.id+'"]')).toContainText('Security review');
    const panel=await searchNotes(page,'Security review');await expect(panel.locator('[data-search-note="'+entry.id+'"] .notes-search-result-title')).toHaveText('Security review');await panel.getByRole('button',{name:'Close',exact:true}).click();
    await captureScreenshot(page,{path:'test-results/obsidian-formatted-import.png',fullPage:true});
    await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await captureScreenshot(page,{path:'test-results/obsidian-formatted-import-mobile.png'});await page.setViewportSize({width:1280,height:900});
    const attachment=page.locator('#notes-formatted [data-wiki-reference="security-datasheet.pdf"]');await expect(attachment).toHaveAttribute('data-resolved','true');await expect(page.locator('#notes-formatted img[data-wiki-embed]')).toHaveCount(0);await attachment.click();
    await expect(page.locator('.notes-document-path')).toHaveText('Notes/security-datasheet.pdf');const pending=page.waitForEvent('download');await clickNoteAction(page,page.locator('#notes-panel'),'Download attachment');expect((await pending).suggestedFilename()).toBe('security-datasheet.pdf');
    await item.click();await expect(page.locator('#notes-formatted')).toBeVisible();expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(source);
    const after=await(await resolve(client,path)).json();for(const key of ['createdAt','modifiedAt','version','contentVersion','etag','entryEtag'])expect(after[key]).toEqual(entry[key]);expect(external).toEqual([]);
  }finally{await rm(root,{recursive:true,force:true});}
});
