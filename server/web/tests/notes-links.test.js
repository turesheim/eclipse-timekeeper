import {test} from 'node:test';
import assert from 'node:assert/strict';
import {relativeNotePath,rasterType} from '../src/notes-links.js';

test('collection-relative links decode once and cannot escape or load external images',()=>{
  assert.equal(relativeNotePath('Notes/First.md','../Images/a%20b.png'),'Images/a b.png');
  assert.equal(relativeNotePath('Notes/First.md','Second.md#Heading'),'Notes/Second.md');
  assert.equal(relativeNotePath('Notes/First.md','percent%2520.png'),'Notes/percent%20.png');
  for(const href of ['../../outside','//host/x','https://host/x','javascript:alert(1)','data:image/png,x','/etc/passwd','a%2Fb','a%5Cb','%00.png','../..','bad%zz'])assert.equal(relativeNotePath('Notes/First.md',href),null,href);
});
test('legacy journal assets keep their canonical association when folders move',()=>{
  assert.equal(relativeNotePath('Private/Journal/2026-10-30.md','assets/image.png',{kind:'DAY',assetsFolder:'Shared/Assets'}),'Shared/Assets/image.png');
  assert.equal(relativeNotePath('Private/Journal/General.md','assets/image.png',{kind:'MARKDOWN',assetsFolder:'Shared/Assets'}),'Private/Journal/assets/image.png');
});
test('only raster signatures are eligible for authenticated image previews',()=>{
  assert.equal(rasterType(new Uint8Array([137,80,78,71,13,10,26,10])),'image/png');
  assert.equal(rasterType(new TextEncoder().encode('<svg onload=')),null);
  assert.equal(rasterType(new TextEncoder().encode('<html>')),null);
});
