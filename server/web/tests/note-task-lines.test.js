import test from 'node:test';
import assert from 'node:assert/strict';
import {noteTaskLines} from '../editor/markdown-preview.js';

test('source task controls distinguish nested duplicate tasks from code and literal examples',()=>{
  const source='😀\n- [ ] Same\n  - [x] Same\n\n```md\n- [ ] Example\n```\n\n    - [ ] Indented code\n\n\\- [ ] Literal\n\n<!--\n- [ ] HTML\n-->\n\n- Ordinary\n- [ ] \n';
  const tasks=noteTaskLines(source);assert.equal(tasks.length,2);assert.notEqual(tasks[0].offset,tasks[1].offset);assert.equal(source.slice(tasks[1].start,tasks[1].end),'  - [x] Same');
});
