import {test} from 'node:test';
import assert from 'node:assert/strict';
import {isProjectIcon,projectIconLabel,searchProjectIcons} from '../src/icons.js';
import {readFile} from 'node:fs/promises';
import {legacyProjectIcons} from '../src/legacy-project-icons.js';

test('project icon searches cover the entire catalog, including names and keywords beyond the first page',()=>{
  const all=searchProjectIcons();assert.ok(all.length>2000);
  assert.ok(all.findIndex(entry=>entry.id==='yin-yang')>1000);
  assert.deepEqual(searchProjectIcons('yin-yang').map(entry=>entry.id),['yin-yang']);
  assert.ok(searchProjectIcons('balance harmony').some(entry=>entry.id==='yin-yang'));
  assert.deepEqual(searchProjectIcons('  YÍN_yang  ').map(entry=>entry.id),['yin-yang']);
  assert.equal(searchProjectIcons('no such icon qzqzqz').length,0);
  assert.equal(searchProjectIcons('  ').length,all.length);
});
test('Bootstrap catalog and retained project identifiers are accepted by the server contract',async()=>{
  const contract=JSON.parse(await readFile(new URL('../../../common/api/openapi.json',import.meta.url)));
  const allowed=contract.components.schemas.ProjectIcon.anyOf[0].enum;
  const current=new Set(searchProjectIcons().map(entry=>entry.id));
  for(const entry of searchProjectIcons())assert.ok(allowed.includes(entry.id),entry.id);
  for(const [id,target] of Object.entries(legacyProjectIcons)) {
    assert.ok(allowed.includes(id),id);assert.ok(isProjectIcon(id),id);assert.ok(current.has(target),target);
  }
  assert.equal(legacyProjectIcons.mobile,'phone');assert.equal(legacyProjectIcons.gavel,'hammer');
  assert.equal(legacyProjectIcons.dragon,'question-circle');
});
test('project icon lookup retains legacy identifiers and safely rejects unknown icons',()=>{
  for(const id of ['briefcase','tools','chart','flask','palette','yin-yang'])assert.equal(isProjectIcon(id),true);
  assert.equal(projectIconLabel('yin-yang'),'Yin Yang');
  for(const id of [undefined,null,'','not-an-icon','constructor','__proto__'])assert.equal(isProjectIcon(id),false);
});
