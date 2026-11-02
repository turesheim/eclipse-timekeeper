import {test,expect} from '@playwright/test';
import {randomUUID,createHash} from 'node:crypto';
import {mkdtemp,mkdir,writeFile,readFile,cp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,dirname} from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {browserRequest} from './browser-request.js';
const execute=promisify(execFile),digest=bytes=>createHash('sha256').update(bytes).digest('hex');

test('packaged manual client round-trips a representative vault after a formatted journal edit',async({page,request})=>{
  test.setTimeout(90000);
  const origin=new URL(process.env.TIMEKEEPER_WEB_URL).origin,status=await request.get('/api/v1/auth/status',{headers:{Origin:origin}});
  if((await status.json()).setupRequired){const setup=await request.post('/api/v1/auth/setup',{headers:{Origin:origin},data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(setup.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{Origin:origin,'X-CSRF-Token':(await setup.json()).csrf}});}
  const username='vault-'+randomUUID().slice(0,8),password='Private representative vault passphrase';expect((await request.post('/api/v1/admin/users',{headers:{Authorization:'Bearer '+process.env.TIMEKEEPER_WEB_TOKEN},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const http=browserRequest(page),identity=await(await http.get('/api/v1/auth/session')).json(),api=async(method,path,data,headers={})=>{const reply=await http.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),method+' '+path+' '+reply.status()).toBe(true);return reply;};
  const token=(await(await api('POST','/auth/tokens',{name:'Manual vault acceptance',permissions:['read','write'],expiresAt:null})).json()).token;
  const directory=await mkdtemp(join(tmpdir(),'timekeeper-515-vault-')),folder=join(directory,'vault'),state=join(directory,'private-sync-state');
  const subscription=randomUUID(),event=digest(Buffer.from('representative event')),eventPath='events/event-'+subscription+'-'+event+'.md';
  const journal='\uFEFF---\r\ncustom: "Keep this exact YAML"\r\n---\r\n\r\nA day heading\r\n=============\r\n\r\nEdit this paragraph.\r\n\r\n+ Keep __this__ list\r\n  + Nested item\r\n+ [ ] Follow up\r\n\r\n| Topic | Value |\r\n| :--- | ---: |\r\n| Editor | 42 |\r\n\r\n~~~js title="retain metadata"\r\nconst text = "[[literal]]";\r\n~~~\r\n\r\n[[Notes/Øving design#Decisions|design]]\r\n\r\n![[journal/assets/pixel.png|32]]\r\n\r\n==unknown highlight==\r\n';
  const files=new Map([
    ['journal/2026-11-02.md',Buffer.from(journal)],['journal/2026-W45.md',Buffer.from('# Week\n\nWeekly review.\n')],
    [eventPath,Buffer.from('---\ntimekeeper-event: '+JSON.stringify({subscriptionId:subscription,eventKey:event,event:{uid:'sample-event',recurrenceId:'',title:'Design review'}})+'\ncustom: keep\n---\n\nEvent notes.\n')],
    ['Notes/Øving design.md',Buffer.from('# Design\n\n## Decisions\n\n> [!NOTE] Decision\n> Keep vault compatibility.\n\nText[^one].\n\n[^one]: A useful footnote.\n\n```mermaid\ngraph LR\nA-->B\n```\n')],
    ['Notes/Unknown.md',Buffer.from('Before.\n\n:::custom\nUnknown extension.\n\n<div data-keep="true">Harmless HTML.</div>\n\nAfter.\n')],['Notes/Attachments.md',Buffer.from('[Read the attachment](../journal/assets/notes.txt)\n')],['Notes/Empty.md',Buffer.alloc(0)],
    ['journal/assets/pixel.png',Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jI1sAAAAASUVORK5CYII=','base64')],['journal/assets/notes.txt',Buffer.from('Synthetic vault attachment.\r\n')]
  ]);
  const base=[process.env.TIMEKEEPER_WEB_DAV_CLIENT,'--url',origin+'/dav/vault/','--folder',folder,'--state-dir',state];if(origin.startsWith('http:'))base.push('--allow-loopback-http');if(process.env.TIMEKEEPER_WEB_CA_FILE)base.push('--ca-file',process.env.TIMEKEEPER_WEB_CA_FILE);
  const sync=async extra=>JSON.parse((await execute('python3',[...base,...extra],{env:{...process.env,WEBDAV_TOKEN:token},timeout:45000,maxBuffer:1_000_000})).stdout);
  try{
    for(const [path,bytes]of files){await mkdir(dirname(join(folder,path)),{recursive:true});await writeFile(join(folder,path),bytes);}
    const initial=await sync([]);expect(initial.conflicts).toEqual([]);expect((await sync(['--apply',initial.review])).applied).toBe(true);
    await page.locator('[data-tab=notes]').click();await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await page.locator('#notes-navigation').getByRole('button',{name:'journal',exact:true}).click();await page.locator('#notes-navigation').getByRole('button',{name:'journal/2026-11-02.md',exact:true}).click();await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();
    const paragraph=page.locator('#notes-formatted p').filter({hasText:'Edit this paragraph.'});await paragraph.click({position:{x:10,y:8}});await expect.poll(()=>page.evaluate(()=>getSelection()?.anchorNode?.parentElement?.closest('p')?.textContent)).toBe('Edit this paragraph.');await page.keyboard.press(process.platform==='darwin'?'Meta+ArrowRight':'End');await expect.poll(()=>page.evaluate(()=>getSelection()?.anchorNode?.parentElement?.closest('p')?.textContent)).toBe('Edit this paragraph.');await page.keyboard.type(' Reviewed.');await page.locator('.notes-footer').getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('.notes-footer').getByRole('button',{name:'Save',exact:true})).toBeDisabled();
    const review=await sync([]);expect(review.conflicts).toEqual([]);expect(review.actions.some(action=>action.operation==='pull'&&action.path==='journal/2026-11-02.md')).toBe(true);expect((await sync(['--apply',review.review])).applied).toBe(true);
    files.set('journal/2026-11-02.md',Buffer.from(journal.replace('Edit this paragraph.','Edit this paragraph. Reviewed.')));
    for(const [path,bytes]of files){const actual=await readFile(join(folder,path));if(digest(actual)!==digest(bytes)&&path.endsWith('.md')){await writeFile('test-results/vault-expected.md',bytes);await writeFile('test-results/vault-actual.md',actual);expect(actual.toString('utf8'),path).toBe(bytes.toString('utf8'));}expect(digest(actual),path).toBe(digest(bytes));}
    const updatedEvent=Buffer.concat([files.get(eventPath),Buffer.from('Reviewed from the vault.\n')]);await writeFile(join(folder,eventPath),updatedEvent);const eventReview=await sync([]);expect(eventReview.conflicts).toEqual([]);expect((await sync(['--apply',eventReview.review])).applied).toBe(true);files.set(eventPath,updatedEvent);
    const eventEntry=await(await api('GET','/notes/resolve?'+new URLSearchParams({path:eventPath}))).json(),eventHistory=await(await api('GET','/notes/revisions?'+new URLSearchParams({sourceType:eventEntry.sourceType,sourceId:eventEntry.sourceId}))).json();expect(eventHistory.items).toHaveLength(1);
    expect((await sync([])).actions).toEqual([]);
    await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=notes]').click();await page.locator('#notes-navigation').getByRole('button',{name:'journal',exact:true}).click();await page.locator('#notes-navigation').getByRole('button',{name:'journal/2026-11-02.md',exact:true}).click();await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();await expect(page.locator('#notes-formatted')).toContainText('Edit this paragraph. Reviewed.');
    await cp(folder,'test-results/representative-vault',{recursive:true});await writeFile('test-results/representative-vault-check.json',JSON.stringify({files:[...files].map(([path,bytes])=>({path,sha256:digest(bytes)})),manualClient:'packaged webdav_sync.py',obsidianReopen:'pending native UI access'},null,2)+'\n');
  }finally{await rm(directory,{recursive:true,force:true});}
});
