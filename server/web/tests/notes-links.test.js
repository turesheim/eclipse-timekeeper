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
