import {test} from 'node:test';
import assert from 'node:assert/strict';
import {isProjectIcon,projectIconCategories,projectIconLabel,searchProjectIcons} from '../src/icons.js';
import {readFile} from 'node:fs/promises';

test('project icon searches cover the entire catalog, including names and keywords beyond the first page',()=>{
  const all=searchProjectIcons();assert.ok(all.length>2000);
  assert.ok(all.findIndex(entry=>entry.id==='yin-yang')>1000);
  assert.deepEqual(searchProjectIcons('yin-yang').map(entry=>entry.id),['yin-yang']);
  assert.ok(searchProjectIcons('balance harmony').some(entry=>entry.id==='yin-yang'));
  assert.deepEqual(searchProjectIcons('  YÍN_yang  ').map(entry=>entry.id),['yin-yang']);
  assert.equal(searchProjectIcons('no such icon qzqzqz').length,0);
  assert.equal(searchProjectIcons('  ').length,all.length);
});
test('Bootstrap categories and filled/unfilled styles combine with icon search',()=>{
  assert.equal(projectIconCategories.length,29);
  assert.equal(projectIconCategories.reduce((sum,category)=>sum+category.count,0),2078);
  for(const category of projectIconCategories)assert.equal(searchProjectIcons('',{category:category.id}).length,category.count);
  assert.deepEqual(searchProjectIcons('bell',{category:'communications',style:'filled'}).map(entry=>entry.id),['bell-fill','bell-slash-fill']);
  assert.deepEqual(searchProjectIcons('bell',{category:'communications',style:'unfilled'}).map(entry=>entry.id),['bell','bell-slash']);
  assert.equal(searchProjectIcons('bell',{category:'weather'}).length,0);
  assert.equal(searchProjectIcons('communications').length,143);
  assert.equal(searchProjectIcons('building-fill-add',{category:'buildings',style:'filled'})[0]?.id,'building-fill-add');
  assert.equal(searchProjectIcons('',{style:'filled'}).length+searchProjectIcons('',{style:'unfilled'}).length,2078);
});
test('the server contract accepts exactly the Bootstrap catalog',async()=>{
  const contract=JSON.parse(await readFile(new URL('../../../common/api/openapi.json',import.meta.url)));
  const allowed=contract.components.schemas.ProjectIcon.anyOf[0].enum;
  assert.deepEqual([...allowed].sort(),searchProjectIcons().map(entry=>entry.id).sort());
});
test('project icon lookup accepts Bootstrap names and safely rejects unknown icons',()=>{
  for(const id of ['briefcase','tools','bar-chart','phone','hammer','flask','palette','yin-yang'])assert.equal(isProjectIcon(id),true);
  assert.equal(projectIconLabel('yin-yang'),'Yin Yang');
  for(const id of [undefined,null,'','not-an-icon','constructor','__proto__','chart','mobile','gavel','dragon'])assert.equal(isProjectIcon(id),false);
});
