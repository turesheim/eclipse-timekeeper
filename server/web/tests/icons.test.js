import {test} from 'node:test';
import assert from 'node:assert/strict';
import {isProjectIcon,projectIconLabel,searchProjectIcons} from '../src/icons.js';
import {readFile} from 'node:fs/promises';

test('project icon searches cover the entire catalog, including names and keywords beyond the first page',()=>{
  const all=searchProjectIcons();assert.ok(all.length>1400);
  assert.ok(all.findIndex(entry=>entry.id==='yin-yang')>1000);
  assert.deepEqual(searchProjectIcons('yin-yang').map(entry=>entry.id),['yin-yang']);
  assert.ok(searchProjectIcons('balance harmony').some(entry=>entry.id==='yin-yang'));
  assert.deepEqual(searchProjectIcons('  YÍN_yang  ').map(entry=>entry.id),['yin-yang']);
  assert.equal(searchProjectIcons('no such icon qzqzqz').length,0);
  assert.equal(searchProjectIcons('  ').length,all.length);
});
test('the server contract accepts exactly the Font Awesome catalog',async()=>{
  const contract=JSON.parse(await readFile(new URL('../../../common/api/openapi.json',import.meta.url)));
  const allowed=contract.components.schemas.ProjectIcon.anyOf[0].enum;
  assert.deepEqual([...allowed].sort(),searchProjectIcons().map(entry=>entry.id).sort());
});
test('project icon lookup accepts Font Awesome names and safely rejects unknown icons',()=>{
  for(const id of ['briefcase','tools','chart','mobile','phone','hammer','flask','palette','yin-yang','gavel','dragon','basketball','futbol','baseball','volleyball','golf-ball-tee','table-tennis-paddle-ball'])assert.equal(isProjectIcon(id),true);
  assert.equal(projectIconLabel('yin-yang'),'Yin Yang');
  for(const id of [undefined,null,'','not-an-icon','constructor','__proto__','airplane-engines','calendar3'])assert.equal(isProjectIcon(id),false);
});

test('sports balls can be found by their everyday names and upstream aliases',()=>{
  for(const [query,id] of [['basketball','basketball'],['basketball-ball','basketball'],['soccer','futbol'],['football','football'],['baseball','baseball'],['volleyball','volleyball'],['golf ball','golf-ball-tee'],['ping pong','table-tennis-paddle-ball']]) {
    assert.ok(searchProjectIcons(query).some(entry=>entry.id===id),`${query} finds ${id}`);
  }
});
