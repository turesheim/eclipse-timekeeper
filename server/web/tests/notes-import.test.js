import {test} from 'node:test';
import assert from 'node:assert/strict';
import {folderImportPlan} from '../src/notes-folder-import.js';
const file=path=>({name:path.split('/').at(-1),webkitRelativePath:path,size:4});
test('folder import strips only the chosen root and retains journal, event and binary paths',()=>{
  const rows=folderImportPlan(['Vault/journal/2026-11-02.md','Vault/events/Meeting.md','Vault/Research/Unicode æ.md','Vault/journal/assets/image.png'].map(file));
  assert.deepEqual(rows.map(row=>row.path).sort(),['events/Meeting.md','journal/2026-11-02.md','journal/assets/image.png','Research/Unicode æ.md'].sort());assert.ok(rows.every(row=>row.status==='ready'));
});
test('configuration, traversal and case/Unicode collisions cannot be imported as new files',()=>{
  const rows=folderImportPlan(['Vault/.obsidian/plugins/main.js','Vault/.git/config','Vault/../secret','Vault/A.md','Vault/a.md','Vault/Å.md','Vault/Å.md','single.md'].map(file));
  assert.ok(rows.slice().every(row=>row.status!=='ready'));assert.equal(rows.filter(row=>row.status==='conflict').length,4);
});
