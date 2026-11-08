import {folderImportPlan} from '../src/notes-folder-import.js';
import {parseNotesMetadata,readNotesMetadata,importedFileDates} from '../src/notes-import-metadata.js';
import {createHash} from 'node:crypto';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {relativeNotePath,rasterType,notesEntryVisible} from '../src/notes-links.js';
import {needsSource} from '../src/markdown-document.js';
import {vaultEditorResources} from '../src/vault-editor-resources.js';

const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
const collection=(journalFolder='journal',assetsFolder='journal/assets')=>({settings:{journalFolder,assetsFolder,eventsFolder:'events'},limits:{pageSize:200},snapshot:'snapshot'});
test('concurrent native image lookups share settings while later lookups see changed folders',async()=>{
  const first=deferred(),requests=[];let settings=collection(),infos=0;
  const session={request:async path=>{requests.push(path);if(path==='/notes')return {data:++infos===1?await first.promise:settings};
    const target=new URLSearchParams(path.split('?')[1]).get('path');if(!target.startsWith(settings.settings.assetsFolder+'/'))throw Object.assign(new Error('missing'),{status:404});return {data:{id:target,path:target,kind:'ASSET'}};}};
  const resources=vaultEditorResources(()=>session,()=>({sourceType:'DAY_NOTE',sourceKey:'2026-11-08'}));
  const pending=Promise.all(['one','two','three'].map(name=>resources.canonicalAsset('assets/'+name+'.png')));
  assert.equal(infos,1);first.resolve(settings);assert.deepEqual(await pending,['journal/assets/one.png','journal/assets/two.png','journal/assets/three.png']);
  assert.equal(requests.length,4);
  settings=collection('journal','Pictures');assert.equal(await resources.canonicalAsset('assets/four.png'),'Pictures/four.png');assert.equal(infos,2);
});
test('ordinary relative assets use their known origin without a collection read',async()=>{
  const requests=[],session={request:async path=>{requests.push(path);const target=new URLSearchParams(path.split('?')[1]).get('path');return {data:{id:'image',path:target}};}};
  const resources=vaultEditorResources(()=>session,()=> 'Notes/page.md');
  assert.equal(await resources.canonicalAsset('../Images/a.png'),'Images/a.png');assert.equal(requests.length,1);assert.ok(requests[0].startsWith('/notes/resolve?'));
  assert.equal(await resources.assetReference({path:'Images/a.png'}),'../Images/a.png');assert.equal(requests.length,1);
});
test('wiki image resolution filters the filename and still reads every ambiguity candidate',async()=>{
  const requests=[],entries=[{id:'local',path:'Notes/shot.png',kind:'ASSET'},{id:'other',path:'Other/shot.png',kind:'ASSET'}];
  const session={request:async(path,options)=>{requests.push(path);if(path==='/notes')return {data:collection()};const query=new URLSearchParams(path.split('?')[1]);
    assert.equal(query.get('query'),'shot.png');assert.equal(options.etag,'snapshot');const offset=Number(query.get('offset'));return {data:{items:[entries[offset]],...(offset===0?{nextOffset:1}:{})}};}};
  let origin='Notes/page.md';const resources=vaultEditorResources(()=>session,()=>origin);
  assert.equal((await resources.resolveWiki('shot.png')).id,'local');assert.equal(requests.length,3);
  origin='page.md';await assert.rejects(resources.resolveWiki('shot.png'));assert.equal(requests.length,3);
  await assert.rejects(resources.resolveWiki('https://foreign.invalid/image.png'));assert.equal(requests.length,3);
});
test('superseded image settings cannot issue lookups or replace another account request',async()=>{
  const delayed=deferred(),oldRequests=[],newRequests=[];
  const old={request:async path=>{oldRequests.push(path);return {data:await delayed.promise};}};
  const current={request:async path=>{newRequests.push(path);if(path==='/notes')return {data:collection('Private','Private/assets')};return {data:{id:'current-image',path:new URLSearchParams(path.split('?')[1]).get('path')}};}};
  let session=old;const resources=vaultEditorResources(()=>session,()=>({sourceType:'DAY_NOTE',sourceKey:'2026-11-08'}));
  const pending=resources.canonicalAsset('assets/private.png'),rejected=assert.rejects(pending);
  session=current;assert.equal(await resources.canonicalAsset('assets/own.png'),'Private/assets/own.png');delayed.resolve(collection('Other','Other/assets'));await rejected;
  assert.deepEqual(oldRequests,['/notes']);assert.equal(newRequests.filter(path=>path==='/notes').length,1);
});
test('invalidated settings and failed old catalog reads cannot evict fresh lookups',async()=>{
  const delayedInfo=deferred(),delayedPage=deferred();let infos=0,pages=0;
  const session={request:async path=>{if(path==='/notes')return {data:++infos===1?await delayedInfo.promise:collection()};
    if(path.startsWith('/notes/entries?'))return {data:++pages===1?await delayedPage.promise:{items:[{id:'new',path:'Notes/new.md',kind:'MARKDOWN'}]}};
    return {data:{id:'asset',path:new URLSearchParams(path.split('?')[1]).get('path')}};}};
  const resources=vaultEditorResources(()=>session,()=>({sourceType:'DAY_NOTE',sourceKey:'2026-11-08'}));
  const old=resources.canonicalAsset('assets/old.png'),rejected=assert.rejects(old);resources.invalidate();
  assert.equal(await resources.canonicalAsset('assets/current.png'),'journal/assets/current.png');delayedInfo.resolve(collection());await rejected;
  const pending=resources.complete(''),failed=assert.rejects(pending);await new Promise(resolve=>setImmediate(resolve));resources.invalidate();
  assert.deepEqual((await resources.complete('new')).map(entry=>entry.id),['new']);delayedPage.reject(new Error('old page failed'));await failed;
  assert.deepEqual((await resources.complete('new')).map(entry=>entry.id),['new']);assert.equal(pages,2);
});

test('collection-relative links decode once and cannot escape or load external images',()=>{
  assert.equal(relativeNotePath('Notes/First.md','../Images/a%20b.png'),'Images/a b.png');
  assert.equal(relativeNotePath('Notes/First.md','Second.md#Heading'),'Notes/Second.md');
  assert.equal(relativeNotePath('Notes/First.md','percent%2520.png'),'Notes/percent%20.png');
  for(const href of ['../../outside','//host/x','https://host/x','javascript:alert(1)','data:image/png,x','/etc/passwd','a%2Fb','a%5Cb','%00.png','../..','bad%zz'])assert.equal(relativeNotePath('Notes/First.md',href),null,href);
});
test('legacy journal assets keep their canonical association when folders move',()=>{
  assert.equal(relativeNotePath('Private/Journal/2026-10-30.md','assets/image.png',{kind:'DAY',assetsFolder:'Shared/Assets'}),'Shared/Assets/image.png');
  assert.equal(relativeNotePath('Private/Journal/General.md','assets/image.png',{kind:'MARKDOWN',assetsFolder:'Shared/Assets'}),'Private/Journal/assets/image.png');
  assert.equal(relativeNotePath('Private/Journal/2026-10-30.md','assets/image.png',{kind:'DAY',assetsFolder:'Shared/Assets',legacyAssets:false}),'Private/Journal/assets/image.png');
});
test('only raster signatures are eligible for authenticated image previews',()=>{
  assert.equal(rasterType(new Uint8Array([137,80,78,71,13,10,26,10])),'image/png');
  assert.equal(rasterType(new TextEncoder().encode('<svg onload=')),null);
  assert.equal(rasterType(new TextEncoder().encode('<html>')),null);
});
test('vault-relative images enter rich mode only with an explicit safe resolver',()=>{
  const allowed=reference=>!!relativeNotePath('Notes/Document.md',reference);
  assert.equal(needsSource('![Pixel](image.png)'),true);
  assert.equal(needsSource('![Pixel](image.png)',allowed),false);
  assert.equal(needsSource('![Pixel](../../outside.png)',allowed),true);
  assert.equal(needsSource('![Pixel](https://external.example/image.png)',allowed),true);
  assert.equal(needsSource('![[image.png]]',allowed),true);
});

test('hidden journals exclude their complete subtree and old journal tombstones without hiding similar names',()=>{
  const settings={hideJournalFolder:true,journalFolder:'Private/Journål'};
  for(const path of ['Private/Journål','PRIVATE/JOURNÅL/2026-11-01.md','Private/Journål/assets/photo.png'])
    assert.equal(notesEntryVisible({kind:'BINARY',path},settings),false,path);
  for(const path of ['Private','Private/Journål-backup/Keep.md','events/event.md','Notes/Keep.md'])
    assert.equal(notesEntryVisible({kind:'MARKDOWN',path},settings),true,path);
  assert.equal(notesEntryVisible({kind:'DAY',path:'old-journal/2026-11-01.md'},settings),false);
  assert.equal(notesEntryVisible({kind:'WEEK',path:'old-journal/2026-W44.md',deleted:true},settings),false);
  assert.equal(notesEntryVisible({kind:'DAY',path:'journal/2026-11-01.md'},{hideJournalFolder:false}),true);
  assert.equal(notesEntryVisible({kind:'FOLDER',path:'journal'},{}),true);
});

const file=path=>({name:path.split('/').at(-1),webkitRelativePath:path,size:4});
test('folder import strips only the chosen root and retains journal, event and binary paths',()=>{
  const rows=folderImportPlan(['Vault/journal/2026-11-02.md','Vault/events/Meeting.md','Vault/Research/Unicode æ.md','Vault/journal/assets/image.png'].map(file));
  assert.deepEqual(rows.map(row=>row.path).sort(),['events/Meeting.md','journal/2026-11-02.md','journal/assets/image.png','Research/Unicode æ.md'].sort());assert.ok(rows.every(row=>row.status==='ready'));
});
test('configuration, traversal and case/Unicode collisions cannot be imported as new files',()=>{
  const rows=folderImportPlan(['Vault/.obsidian/plugins/main.js','Vault/.git/config','Vault/../secret','Vault/A.md','Vault/a.md','Vault/Å.md','Vault/Å.md','single.md'].map(file));
  assert.ok(rows.slice().every(row=>row.status!=='ready'));assert.equal(rows.filter(row=>row.status==='conflict').length,4);
});
test('portable folder aliases cannot silently merge different source directories',()=>{
  const rows=folderImportPlan(['Vault/Notes/A.md','Vault/NOTES/B.md','Vault/Notes/C.md'].map(file));
  assert.ok(rows.every(row=>row.status==='conflict'&&!row.selected));
});
const creation='2020-01-02T03:04:05.123456789Z',modification='2021-02-03T04:05:06.987654321Z';
const manifestEntry=(path,body)=>({path,kind:'FILE',bytes:Buffer.byteLength(body),sha256:createHash('sha256').update(body).digest('hex'),createdAt:creation,modifiedAt:modification});
const chosenFile=(name,body)=>{const value=new File([body],name,{lastModified:Date.parse('2026-01-01T00:00:00Z')});Object.defineProperty(value,'webkitRelativePath',{value:'Vault/'+name});return value;};
test('import metadata preserves nanosecond dates and uses current modification time after a content edit',async()=>{
  const entry=manifestEntry('Exact.md','Original\r\n'),value={format:'timekeeper-notes-v1',entries:[entry]};
  assert.equal(parseNotesMetadata(value).get('exact.md').createdAt,creation);
  assert.deepEqual((await importedFileDates(chosenFile('Exact.md','Original\r\n'),entry)).dates,{createdAt:creation,modifiedAt:modification});
  const edited=await importedFileDates(chosenFile('Exact.md','Edited'),entry);assert.equal(edited.changed,true);assert.deepEqual(edited.dates,{createdAt:creation,modifiedAt:'2026-01-01T00:00:00.000Z'});
  for(const bad of ['2021-02-30T00:00:00Z','0000-01-01T00:00:00Z','0001-01-01T00:00:00+14:00','9999-12-31T23:59:59-14:00','not-a-date'])assert.throws(()=>parseNotesMetadata({...value,entries:[{...entry,createdAt:bad}]}));
  assert.throws(()=>parseNotesMetadata({...value,entries:[entry,{...entry,path:'exact.md'}]}));
});
test('export metadata cannot hide a source file using the same conventional metadata filename',async()=>{
  const original=chosenFile('timekeeper-notes-metadata.json','A real user file'),metadataName='timekeeper-notes-metadata-00000000-0000-0000-0000-000000000001.json';
  const transport=chosenFile(metadataName,JSON.stringify({format:'timekeeper-notes-v1',entries:[manifestEntry(original.name,'A real user file')]}));
  const metadata=await readNotesMetadata([original,transport]),rows=folderImportPlan([original,transport],{metadata});
  assert.equal(metadata.file,transport);assert.equal(rows.length,1);assert.equal(rows[0].file,original);assert.equal(rows[0].status,'ready');
  const independent=chosenFile('timekeeper-notes-metadata-00000000-0000-0000-0000-000000000002.json',JSON.stringify({format:'timekeeper-notes-v1',entries:[]}));
  await assert.rejects(()=>readNotesMetadata([transport,independent]));
});
