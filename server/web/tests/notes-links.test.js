import {folderImportPlan} from '../src/notes-folder-import.js';
import {parseNotesMetadata,readNotesMetadata,importedFileDates} from '../src/notes-import-metadata.js';
import {createHash} from 'node:crypto';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {relativeNotePath,rasterType,notesEntryVisible} from '../src/notes-links.js';
import {needsSource} from '../src/markdown-document.js';

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
