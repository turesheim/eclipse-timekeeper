import {test} from 'node:test';
import assert from 'node:assert/strict';
import {sortedNotes} from '../src/notes-sort.js';

const row=(path,createdAt,modifiedAt,kind='MARKDOWN')=>({id:path,path,kind,createdAt,modifiedAt});
const paths=rows=>rows.map(value=>value.path);
test('navigator sorts names naturally in both directions while keeping folders first',()=>{
  const rows=[row('Notes/Note10.md'),row('Notes/Note2.md'),row('Notes/Archive',null,null,'FOLDER')];
  assert.deepEqual(paths(sortedNotes(rows)),['Notes/Archive','Notes/Note2.md','Notes/Note10.md']);
  assert.deepEqual(paths(sortedNotes(rows,'name-desc')),['Notes/Archive','Notes/Note10.md','Notes/Note2.md']);
  assert.equal(rows[0].path,'Notes/Note10.md');
});
test('creation and modification sort independently, with unknown dates last in both directions',()=>{
  const rows=[row('Old.md','2020-01-01T00:00:00Z','2024-01-01T00:00:00Z'),row('New.md','2023-01-01T00:00:00Z','2023-01-01T00:00:00Z'),row('Unknown.md')];
  assert.deepEqual(paths(sortedNotes(rows,'created-asc')),['Old.md','New.md','Unknown.md']);
  assert.deepEqual(paths(sortedNotes(rows,'created-desc')),['New.md','Old.md','Unknown.md']);
  assert.deepEqual(paths(sortedNotes(rows,'modified-asc')),['New.md','Old.md','Unknown.md']);
  assert.deepEqual(paths(sortedNotes(rows,'modified-desc')),['Old.md','New.md','Unknown.md']);
});
test('equal or invalid dates retain deterministic alphabetical ordering and respect Norwegian names',()=>{
  const rows=[row('Å.md',null,'invalid'),row('A.md',null,'invalid'),row('Æ.md',null,'invalid')];
  assert.deepEqual(paths(sortedNotes(rows,'modified-desc','nb-NO')),['A.md','Æ.md','Å.md']);
  assert.deepEqual(paths(sortedNotes(rows,'invalid','nb-NO')),['A.md','Æ.md','Å.md']);
});
