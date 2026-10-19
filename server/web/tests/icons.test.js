import {test} from 'node:test';
import assert from 'node:assert/strict';
import {isProjectIcon,projectIconLabel,searchProjectIcons} from '../src/icons.js';

test('project icon searches cover the entire catalog, including names and keywords beyond the first page',()=>{
  const all=searchProjectIcons();assert.ok(all.length>1400);
  assert.ok(all.findIndex(entry=>entry.id==='yin-yang')>1400);
  assert.deepEqual(searchProjectIcons('yin-yang').map(entry=>entry.id),['yin-yang']);
  assert.ok(searchProjectIcons('balance harmony').some(entry=>entry.id==='yin-yang'));
  assert.deepEqual(searchProjectIcons('  YÍN_yang  ').map(entry=>entry.id),['yin-yang']);
  assert.equal(searchProjectIcons('no such icon qzqzqz').length,0);
  assert.equal(searchProjectIcons('  ').length,all.length);
});
test('project icon lookup retains legacy identifiers and safely rejects unknown icons',()=>{
  for(const id of ['briefcase','tools','chart','flask','palette','yin-yang'])assert.equal(isProjectIcon(id),true);
  assert.equal(projectIconLabel('yin-yang'),'Yin Yang');
  for(const id of [undefined,null,'','not-an-icon','constructor','__proto__'])assert.equal(isProjectIcon(id),false);
});
