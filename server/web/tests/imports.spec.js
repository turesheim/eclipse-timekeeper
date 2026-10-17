import {openAccountMenu} from './navigation.js';
import {test,expect} from '@playwright/test';
import {fileURLToPath} from 'node:url';
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash,randomBytes,randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';

const fixture = fileURLToPath(new URL('./fixtures/toggl/detailed-member-stop.csv',import.meta.url));
const token = process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request,path) {
  const response = await request.get('/api/v1'+path,{headers:{Authorization:`Bearer ${token}`}});expect(response.status()).toBe(200);return response.json();
}
async function login(page) {
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(token);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  await openAccountMenu(page);await page.getByRole('link',{name:'My Account',exact:true}).click();await page.getByRole('link',{name:'Import',exact:true}).click();
}
async function review(page) {
  await page.getByLabel('Source time zone',{exact:true}).fill('Europe/Oslo');await page.getByLabel('Detailed Report CSV',{exact:true}).setInputFiles(fixture);
  await page.getByRole('button',{name:'Read CSV',exact:true}).click();await expect(page.getByRole('heading',{name:'Review import',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Review mappings',exact:true}).click();await expect(page.getByRole('heading',{name:'Review mappings',exact:true})).toBeVisible();
  await page.getByLabel('New client name',{exact:true}).fill('Browser imported client');
  await expect(page.getByLabel('New label name',{exact:true})).toHaveCount(1);
  await page.getByRole('button',{name:'Review server matches',exact:true}).click();await expect(page.getByRole('heading',{name:'Confirm import',exact:true})).toBeVisible();
}
async function undo(page,index) {
  const history = page.getByRole('heading',{name:'Import history',exact:true}).locator('..');
  await history.getByRole('button',{name:'Undo import',exact:true}).nth(index).click();
  await page.getByRole('button',{name:'Confirm Undo',exact:true}).click();
  await expect(history).toContainText('Undo complete');
}
test('Toggl CSV worker, reviewed mappings, repeat import and whole-job Undo preserve exact time',async({page,request})=>{
  await login(page);await page.getByLabel('Import as',{exact:true}).selectOption('ACTIVITY');const before = await api(request,'/data/export');
  await review(page);await expect(page.locator('#account-content')).toContainText('7 selected entries');
  const accessibility = await new AxeBuilder({page}).include('#account-page').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(accessibility.violations).toEqual([]);
  await page.screenshot({path:'test-results/toggl-import-review.png',fullPage:true});
  await page.getByRole('button',{name:'Import selected entries',exact:true}).click();await expect(page.getByRole('heading',{name:'Import complete',exact:true})).toBeVisible();
  const first = await api(request,'/data/export');expect(first.activities.length-before.activities.length).toBe(7);expect(first.workSessions.length).toBe(before.workSessions.length);
  const jobs = await api(request,'/imports');expect(jobs.items).toHaveLength(1);expect(jobs.items[0].importedNanos).toBe('151462000000000');expect(jobs.items[0].state).toBe('IMPORTED');
  const originalId = jobs.items[0].id;
  const pendingAudit = page.waitForEvent('download');await page.getByRole('button',{name:'Download audit',exact:true}).click();
  const auditDownload = await pendingAudit,audit = JSON.parse(readFileSync(await auditDownload.path(),'utf8'));
  expect(audit.job.id).toBe(originalId);const entries = audit.items.filter(value=>value.kind==='ENTRY');expect(entries).toHaveLength(7);
  expect(entries.reduce((sum,value)=>sum+BigInt(value.sourceNanos),0n)).toBe(151462000000000n);expect(entries.every(value=>Number.isInteger(value.sourceLine) && value.outcome==='IMPORTED')).toBe(true);
  await page.getByRole('button',{name:'Import another file',exact:true}).click();await review(page);
  await expect(page.locator('#account-content')).toContainText('7 already imported');await page.getByRole('button',{name:'Import selected entries',exact:true}).click();await expect(page.getByRole('heading',{name:'Import complete',exact:true})).toBeVisible();
  const repeated = await api(request,'/data/export');expect(repeated.activities.length).toBe(first.activities.length);expect(repeated.clients.length).toBe(first.clients.length);expect(repeated.projects.length).toBe(first.projects.length);
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:'test-results/toggl-import-history-mobile.png',fullPage:true});
  const ordered = (await api(request,'/imports')).items.sort((a,b)=>b.createdAt.localeCompare(a.createdAt));
  await undo(page,ordered.findIndex(job=>job.id!==originalId));
  expect((await api(request,'/data/export')).activities.length).toBe(first.activities.length);
  await undo(page,0);
  const after = await api(request,'/data/export');expect(after.activities.length).toBe(before.activities.length);expect(after.clients.length).toBe(before.clients.length);expect(after.projects.length).toBe(before.projects.length);
  expect((await api(request,`/imports/jobs/${originalId}`)).state).toBe('UNDONE');
  await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#account-content')).toBeEmpty();
  expect(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length}))).toEqual({local:0,session:0});
});

for(const mode of ['ACTIVITY','WORK_SESSION']) test(`Toggl ${mode} import resumes after a lost reply and reload, then reviews edited records during Undo`,async({page,request})=>{
  await login(page);await page.getByLabel('Import as',{exact:true}).selectOption(mode);const before = await api(request,'/data/export');
  const header = 'Description,Duration,Member,Email,Project,Tags,Start date,Start time,Stop date,Stop time';
  const lines = Array.from({length:201},(_,n)=>{
    const start = new Date(Date.UTC(2025,9,20,9,0,n)),end = new Date(+start+1000);
    return [`Bulk row ${n}`,'00:00:01','Bulk member','bulk@example.invalid','Bulk project','','2025-10-20',start.toISOString().slice(11,19),'2025-10-20',end.toISOString().slice(11,19)].join(',');
  });
  const file = {name:'bulk.csv',mimeType:'text/csv',buffer:Buffer.from([header,...lines].join('\r\n'))};
  await page.getByLabel('Source time zone',{exact:true}).fill('Europe/Oslo');await page.getByLabel('Detailed Report CSV',{exact:true}).setInputFiles(file);
  await page.getByRole('button',{name:'Read CSV',exact:true}).click();await page.getByRole('button',{name:'Review mappings',exact:true}).click();
  await page.getByLabel('New client name',{exact:true}).fill('Bulk client');await page.getByRole('button',{name:'Review server matches',exact:true}).click();
  let dropped = false;
  await page.route('**/api/v1/imports/jobs/*/batches/0',async route=>{
    await route.fetch();dropped = true;await page.getByRole('button',{name:'Pause after current batch',exact:true}).click();await route.abort('failed');
  });
  await page.getByRole('button',{name:'Import selected entries',exact:true}).click();await expect(page.locator('#account-content')).toContainText('Paused. Resume from Import history.');
  expect(dropped).toBe(true);await page.unroute('**/api/v1/imports/jobs/*/batches/0');
  const job = (await api(request,'/imports')).items.find(value=>value.selectedEntries===201 && value.mode===mode && value.state==='IMPORTING');expect(job.nextBatch).toBe(1);expect(job.importedEntries).toBe(100);
  page.once('dialog',dialog=>dialog.accept());await page.reload();await expect(page.getByRole('heading',{name:'Import history',exact:true})).toBeVisible();
  await expect(page.getByLabel('Detailed Report CSV',{exact:true})).toHaveValue('');
  await page.getByRole('button',{name:'Resume',exact:true}).click();await expect(page.locator('#account-content')).toContainText('Choose the original CSV, source user and row exclusions to resume.');
  await expect(page.getByLabel('Detailed Report CSV',{exact:true})).toBeEnabled();await page.getByLabel('Detailed Report CSV',{exact:true}).setInputFiles(file);
  await page.getByRole('button',{name:'Read CSV',exact:true}).click();await page.getByRole('button',{name:'Review mappings',exact:true}).click();
  await expect(page.getByLabel('New client name',{exact:true})).toBeDisabled();
  await page.getByRole('button',{name:'Review server matches',exact:true}).click();await page.getByRole('button',{name:'Resume import',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Import complete',exact:true})).toBeVisible();
  const imported = await api(request,'/data/export');expect(imported[mode==='WORK_SESSION' ? 'workSessions' : 'activities'].length-before[mode==='WORK_SESSION' ? 'workSessions' : 'activities'].length).toBe(201);
  const complete = await api(request,`/imports/jobs/${job.id}`);expect(complete.nextBatch).toBe(3);expect(complete.importedNanos).toBe('201000000000');
  const collection=mode==='WORK_SESSION' ? 'work-sessions' : 'activities',records=mode==='WORK_SESSION' ? 'workSessions' : 'activities';
  const activity = imported[records].find(value=>value.summary==='Bulk row 0');
  const current = await request.get('/api/v1/'+collection+'/'+activity.id,{headers:{Authorization:`Bearer ${token}`}});const body = await current.json();
  const changed = await request.put('/api/v1/'+collection+'/'+activity.id,{headers:{Authorization:`Bearer ${token}`,'If-Match':current.headers().etag},data:{start:body.start,end:body.end,summary:'Edited bulk entry',...(mode==='WORK_SESSION' ? {title:body.title,clientId:body.clientId} : {labelIds:body.labelIds})}});expect(changed.status()).toBe(200);
  const history = page.getByRole('heading',{name:'Import history',exact:true}).locator('..');
  await history.getByRole('button',{name:'Undo import',exact:true}).click();await page.getByRole('button',{name:'Confirm Undo',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Review records retained or changed since import',exact:true})).toBeVisible();
  await page.getByLabel('Undo decision',{exact:true}).selectOption('DELETE');await page.getByRole('button',{name:'Apply decisions and continue Undo',exact:true}).click();
  await expect(history).toContainText('Undo complete');expect((await api(request,'/data/export'))[records].length).toBe(before[records].length);
});

test('Toggl worker and previous-account preview are cleared on sign-out and account switch',async({page})=>{
  await login(page);let release;const blocked = new Promise(resolve=>{release=resolve;});let intercepted=false;
  await page.route('**/web/toggl-worker.js',async route=>{intercepted=true;await blocked;try{await route.continue();}catch{}});
  await page.getByLabel('Detailed Report CSV',{exact:true}).setInputFiles(fixture);await page.getByRole('button',{name:'Read CSV',exact:true}).click();
  await expect.poll(()=>intercepted).toBe(true);await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.locator('#account-content')).toBeEmpty();release();
  const other = randomBytes(32).toString('base64url'),owner = 'import-owner-'+randomUUID();
  const registryPath = process.env.TIMEKEEPER_WEB_REGISTRY,registry = JSON.parse(readFileSync(registryPath,'utf8'));
  registry.push({ownerId:owner,sha256:createHash('sha256').update(other).digest('hex'),permissions:['read','write']});writeFileSync(registryPath,JSON.stringify(registry));
  await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(other);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  await openAccountMenu(page);await page.getByRole('link',{name:'My Account',exact:true}).click();await page.getByRole('link',{name:'Import',exact:true}).click();
  await expect(page.getByLabel('Detailed Report CSV',{exact:true})).toHaveValue('');await expect(page.locator('#account-content')).toContainText('No imports yet.');
  await expect(page.getByRole('heading',{name:'Review import',exact:true})).not.toBeVisible();
  expect(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length}))).toEqual({local:0,session:0});
});

test('Toggl source selection, rejected-row diagnostics and independent task descriptions are explicitly reviewed',async({page,request})=>{
  await login(page);await page.getByLabel('Import as',{exact:true}).selectOption('ACTIVITY');const sent = [];page.on('request',request=>{if(request.url().includes('/api/v1/imports') && request.postData()) sent.push(request.postData());});
  const csv = 'Description,Duration,Member,Email,Client,Project,Task,Tags,Start date,Start time,Stop date,Stop time,Billable,Workspace\r\n'
    +'"=SUM(1,2)",01:00:00,Selected member,selected@example.invalid,Selected client,Selected project,Task name,"Tag one,Tag two",2025-10-20,09:00:00,2025-10-20,10:00:00,Yes,Ignored workspace A\r\n'
    +'Wrong duration,00:30:00,Selected member,selected@example.invalid,Selected client,Selected project,,,2025-10-20,11:00:00,2025-10-20,12:00:00,No,Ignored workspace B\r\n'
    +'Other private entry,01:00:00,Other member,other@example.invalid,Other client,Other project,,,2025-10-20,09:00:00,2025-10-20,10:00:00,Yes,Ignored workspace A\r\n';
  await page.getByLabel('Source time zone',{exact:true}).fill('Europe/Oslo');await page.getByLabel('Detailed Report CSV',{exact:true}).setInputFiles({name:'source-selection.csv',mimeType:'text/csv',buffer:Buffer.from(csv)});
  await page.getByRole('button',{name:'Read CSV',exact:true}).click();await page.getByRole('button',{name:'Review mappings',exact:true}).click();
  await expect(page.locator('#account-content')).toContainText('Choose the source user.');
  await page.getByLabel('Source user',{exact:true}).selectOption({label:'Selected member · selected@example.invalid'});
  await page.getByRole('button',{name:'Review mappings',exact:true}).click();await expect(page.locator('#account-content')).toContainText('Review and explicitly exclude invalid rows before continuing.');
  await expect(page.locator('#account-content')).toContainText('Unsupported metadata: Billable, Workspace.');
  await page.getByLabel('Exclude invalid rows after reviewing the diagnostics',{exact:true}).check();
  const pending = page.waitForEvent('download');await page.getByRole('button',{name:'Download preview diagnostics',exact:true}).click();
  const download = await pending,diagnostics = JSON.parse(readFileSync(await download.path(),'utf8'));
  expect(diagnostics.diagnostics).toEqual({sourceRows:3,rejectedRows:1,excludedRows:0,unselectedRows:1,unsupportedFields:['Billable','Workspace']});
  expect(diagnostics.rows[1].errors.join(' ')).toContain('duration disagree');
  await page.getByRole('button',{name:'Review mappings',exact:true}).click();await page.getByRole('button',{name:'Review server matches',exact:true}).click();
  await page.getByRole('button',{name:'Import selected entries',exact:true}).click();await expect(page.getByRole('heading',{name:'Import complete',exact:true})).toBeVisible();
  const exported = await api(request,'/data/export'),entry = exported.activities.find(value=>value.summary==='=SUM(1,2)');expect(entry).toBeTruthy();expect(entry.labelIds).toHaveLength(2);
  expect(exported.tasks.find(value=>value.id===entry.taskId).summary).toBe('Task name');
  const job = (await api(request,'/imports')).items.find(value=>value.sourceDiagnostics?.sourceRows===3);expect(job.sourceDiagnostics).toEqual(diagnostics.diagnostics);expect(job.importedEntries).toBe(1);
  expect(sent.join('\n')).not.toContain('Other private entry');expect(sent.join('\n')).not.toContain('other@example.invalid');expect(sent.join('\n')).not.toContain('selected@example.invalid');expect(sent.join('\n')).not.toContain('Ignored workspace');
  await undo(page,0);
});

test('Toggl defaults to work sessions with client totals, repeat detection and whole-job Undo',async({page,request})=>{
  await login(page);const before=await api(request,'/data/export');
  await expect(page.getByLabel(/Source workspace/)).toHaveCount(0);await expect(page.getByLabel('Import as',{exact:true})).toHaveValue('WORK_SESSION');await review(page);
  await expect(page.getByLabel('New project name',{exact:true})).toHaveCount(0);await expect(page.getByLabel('New label name',{exact:true})).toHaveCount(0);
  const audit=await new AxeBuilder({page}).include('#account-page').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
  await page.screenshot({path:'test-results/toggl-session-import-review.png',fullPage:true});
  await page.getByRole('button',{name:'Import selected entries',exact:true}).click();await expect(page.getByRole('heading',{name:'Import complete',exact:true})).toBeVisible();
  const imported=await api(request,'/data/export'),sessions=imported.workSessions.filter(value=>!before.workSessions.some(old=>old.id===value.id));expect(sessions).toHaveLength(7);
  for(const type of ['activities','tasks']) expect(imported[type].length).toBe(before[type].length);
  const importedProjects=imported.projects.filter(value=>!before.projects.some(old=>old.id===value.id));expect(importedProjects).toHaveLength(1);expect(sessions.every(value=>value.projectId===importedProjects[0].id)).toBe(true);
  expect(imported.labels.length).toBe(before.labels.length+1);const importedLabel=imported.labels.find(value=>!before.labels.some(old=>old.id===value.id));
  expect(sessions.every(value=>value.labelIds.includes(importedLabel.id))).toBe(true);
  expect(sessions.every(value=>value.title===value.summary && value.clientId===sessions[0].clientId)).toBe(true);
  expect(sessions.reduce((sum,value)=>sum+(+new Date(value.end)-+new Date(value.start)),0)).toBe(151462000);
  const job=(await api(request,'/imports')).items.find(value=>value.mode==='WORK_SESSION' && value.state==='IMPORTED' && value.selectedEntries===7);expect(job.workSessionCount).toBe(7);expect(job.importedNanos).toBe('151462000000000');
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await page.locator('[data-tab=track]').click();await page.getByLabel('Month',{exact:true}).fill('2025-10');
  await expect(page.locator(`#track-clients [data-track-client="${sessions[0].clientId}"]`)).toContainText('42h 4m');
  await openAccountMenu(page);await page.getByRole('link',{name:'My Account',exact:true}).click();await page.getByRole('link',{name:'Import',exact:true}).click();await review(page);
  await expect(page.locator('#account-content')).toContainText('7 already imported');await page.getByRole('button',{name:'Import selected entries',exact:true}).click();await expect(page.getByRole('heading',{name:'Import complete',exact:true})).toBeVisible();
  expect((await api(request,'/data/export')).workSessions.length).toBe(imported.workSessions.length);
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.screenshot({path:'test-results/toggl-session-import-history-mobile.png',fullPage:true});
  const history=(await api(request,'/imports')).items.sort((a,b)=>b.createdAt.localeCompare(a.createdAt));await undo(page,history.findIndex(value=>value.id!==job.id && value.mode==='WORK_SESSION'));
  const updated=(await api(request,'/imports')).items.filter(value=>['IMPORTING','IMPORTED','BLOCKED','UNDOING'].includes(value.state)).sort((a,b)=>b.createdAt.localeCompare(a.createdAt));await undo(page,updated.findIndex(value=>value.id===job.id));
  const after=await api(request,'/data/export');expect(after.workSessions.length).toBe(before.workSessions.length);expect(after.clients.length).toBe(before.clients.length);expect(after.labels.length).toBe(before.labels.length);
});


for(const mode of ['WORK_SESSION','ACTIVITY']) test(`Toggl ${mode} maps several source clients to an existing client and Undo preserves it`,async({page,request})=>{
  const suffix=randomUUID().slice(0,8),headers={Authorization:`Bearer ${token}`};
  const created=await request.post('/api/v1/clients',{headers,data:{name:'Existing destination '+suffix,color:'#176653'}});expect(created.status()).toBe(201);
  const destination=await created.json();
  const projectReply=await request.post('/api/v1/projects',{headers,data:{name:'Existing delivery '+suffix,clientId:destination.id}});expect(projectReply.status()).toBe(201);const destinationProject=await projectReply.json(),before=await api(request,'/data/export');
  await login(page);await page.getByLabel('Import as',{exact:true}).selectOption(mode);
  const csv='Description,Duration,Member,Email,Client,Project,Start date,Start time,Stop date,Stop time\r\n'
    +[`First ${suffix},01:00:00,Mapping ${suffix},${suffix}@example.invalid,Toggl one,First project,2025-10-20,09:00:00,2025-10-20,10:00:00`,
      `Second ${suffix},01:00:00,Mapping ${suffix},${suffix}@example.invalid,Toggl two,Second project,2025-10-20,10:00:00,2025-10-20,11:00:00`,
      `Missing client ${suffix},01:00:00,Mapping ${suffix},${suffix}@example.invalid,,Fallback project,2025-10-20,11:00:00,2025-10-20,12:00:00`].join('\r\n');
  await page.getByLabel('Source time zone',{exact:true}).fill('Europe/Oslo');
  await page.getByLabel('Detailed Report CSV',{exact:true}).setInputFiles({name:'client-mapping.csv',mimeType:'text/csv',buffer:Buffer.from(csv)});
  await page.getByRole('button',{name:'Read CSV',exact:true}).click();await page.getByRole('button',{name:'Review mappings',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Client mappings',exact:true})).toBeVisible();
  const sources=['Toggl one','Toggl two',mode==='WORK_SESSION' ? 'Fallback project' : 'Entries without a client'];
  for(const source of sources) {
    const select=page.getByLabel('Timekeeper client for '+source,{exact:true});
    await select.selectOption(destination.id);await expect(select).toHaveValue(destination.id);
    await expect(select.locator('..').locator('..').getByLabel('New client name',{exact:true})).toBeHidden();
  }
  // The choice can be changed back before committing; no destination is edited.
  const first=page.getByLabel('Timekeeper client for Toggl one',{exact:true});await first.selectOption('');
  await expect(first.locator('..').locator('..').getByLabel('New client name',{exact:true})).toBeVisible();
  await first.selectOption(destination.id);await expect(first).toHaveValue(destination.id);
  await expect(page.getByLabel('New client name',{exact:true}).filter({visible:true})).toHaveCount(0);
  await page.getByLabel('Project: First project',{exact:true}).selectOption(destinationProject.id);
  await page.getByRole('button',{name:'Review server matches',exact:true}).click();
  const mappingTable=page.getByRole('table').filter({has:page.getByRole('columnheader',{name:'Toggl client',exact:true})});
  for(const source of sources) await expect(mappingTable.getByRole('row').filter({hasText:source})).toContainText('Use existing: '+destination.name);
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:`test-results/toggl-existing-client-${mode.toLowerCase()}.png`,fullPage:true});
  await page.getByRole('button',{name:'Import selected entries',exact:true}).click();await expect(page.getByRole('heading',{name:'Import complete',exact:true})).toBeVisible();
  const imported=await api(request,'/data/export'),collection=mode==='WORK_SESSION' ? 'workSessions' : 'activities';
  expect(imported.clients).toEqual(before.clients);const added=imported[collection].filter(value=>!before[collection].some(old=>old.id===value.id));expect(added).toHaveLength(3);
  if(mode==='WORK_SESSION') expect(added.every(value=>value.clientId===destination.id)).toBe(true);
  else expect(imported.projects.filter(value=>!before.projects.some(old=>old.id===value.id)).every(value=>value.clientId===destination.id)).toBe(true);
  expect(added.find(value=>value.summary===`First ${suffix}`).projectId).toBe(destinationProject.id);
  expect(await api(request,'/clients/'+destination.id)).toEqual(destination);
  await undo(page,0);const undone=await api(request,'/data/export');expect(undone.clients).toEqual(before.clients);expect(undone[collection]).toEqual(before[collection]);
  expect(await api(request,'/clients/'+destination.id)).toEqual(destination);expect(await api(request,'/projects/'+destinationProject.id)).toEqual(destinationProject);
  const projectCurrent=await request.get('/api/v1/projects/'+destinationProject.id,{headers});expect((await request.delete('/api/v1/projects/'+destinationProject.id,{headers:{...headers,'If-Match':projectCurrent.headers().etag}})).status()).toBe(204);
  const current=await request.get('/api/v1/clients/'+destination.id,{headers});expect((await request.delete('/api/v1/clients/'+destination.id,{headers:{...headers,'If-Match':current.headers().etag}})).status()).toBe(204);
});

test('Toggl review explains missing durations beside the continue action and excludes only reviewed invalid rows',async({page})=>{
  await login(page);
  const header='Description,Duration,Member,Email,Project,Start date,Start time,Stop date,Stop time';
  const lines=Array.from({length:351},(_,index)=>{
    const start=new Date(Date.UTC(2025,9,20,9,0,index)),end=new Date(+start+1000);
    return [`Annual entry ${index}`,index===21 || index===22 ? '-' : '00:00:01','Annual member','annual@example.invalid','Annual project','2025-10-20',start.toISOString().slice(11,19),'2025-10-20',end.toISOString().slice(11,19)].join(',');
  });
  await page.getByLabel('Source time zone',{exact:true}).fill('Europe/Oslo');
  await page.getByLabel('Detailed Report CSV',{exact:true}).setInputFiles({name:'annual-anonymized.csv',mimeType:'text/csv',buffer:Buffer.from([header,...lines].join('\r\n'))});
  await page.getByRole('button',{name:'Read CSV',exact:true}).click();
  await expect(page.locator('#account-content')).toContainText('351 source rows · 2 invalid.');
  await expect(page.locator('#account-content')).toContainText('This row has no recorded duration. Exclude it or correct the CSV before importing.');
  await page.getByRole('button',{name:'Review mappings',exact:true}).click();
  const exclude=page.getByLabel('Exclude invalid rows after reviewing the diagnostics',{exact:true});
  await expect(exclude).toBeFocused();await expect(page.getByText('2 invalid rows cannot be imported.',{exact:true})).toBeInViewport();
  await expect(page.locator('#account-content')).toContainText('Review and explicitly exclude invalid rows before continuing.');
  await exclude.check();await page.getByRole('button',{name:'Review mappings',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Review mappings',exact:true})).toBeVisible();await expect(page.locator('#account-content')).toContainText('349 work sessions');
});
