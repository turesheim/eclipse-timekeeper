import {test} from 'node:test';
import assert from 'node:assert/strict';
import {lineChanges,revisionDiff} from '../src/revision-diff.js';

test('line operations reconstruct both texts including repeated lines',()=>{
 let seed=31;const random=()=>{seed=(seed*1664525+1013904223)>>>0;return seed;};
 for(let example=0;example<200;example++){
  const before=Array.from({length:random()%20},()=>String(random()%5)),after=Array.from({length:random()%20},()=>String(random()%5));
  for(const budget of [1,1000000]){const rows=lineChanges(before,after,{budget});assert.deepEqual(rows.filter(row=>row.kind!=='added').map(row=>row.text),before);assert.deepEqual(rows.filter(row=>row.kind!=='removed').map(row=>row.text),after);}
 }
});
test('separated changes have context and individual unified hunks',()=>{
 const before=Array.from({length:30},(_,n)=>'Line '+n),after=[...before];after[2]='First change';after[25]='Second change';const result=revisionDiff(after.join('\n'),before.join('\n'));
 assert.equal(result.hunks.length,2);assert.equal(result.truncated,false);assert.deepEqual(result.hunks.flatMap(hunk=>hunk.rows).filter(row=>row.kind==='added').map(row=>row.text),['First change','Second change']);assert.equal(result.hunks[1].oldStart,23);
});
test('BOM and line-ending-only differences remain visible as exact escaped source',()=>{
 for(const [current,previous,token]of [['x\n','x\r\n','\\r\\n'],['\uFEFFx','x','\\uFEFF']]){const rows=revisionDiff(current,previous).hunks[0].rows;assert.deepEqual(rows.map(row=>row.kind),['removed','added']);assert.ok(rows.some(row=>row.text.includes(token)));}
 assert.deepEqual(revisionDiff('Same','Same').hunks,[]);
});
test('large comparisons are bounded and explicitly marked without changing source strings',()=>{
 const current='x'.repeat(2000001),previous='original';const result=revisionDiff(current,previous);assert.equal(result.truncated,true);assert.ok(result.hunks.flatMap(hunk=>hunk.rows).length<=2000);assert.equal(current.length,2000001);assert.equal(previous,'original');
 const many=revisionDiff(Array.from({length:3000},(_,n)=>'after '+n).join('\n'),Array.from({length:3000},(_,n)=>'before '+n).join('\n'));assert.equal(many.truncated,true);assert.ok(many.hunks.flatMap(hunk=>hunk.rows).length<=2000);
});

test('comparison distinguishes the draft and selected revision as literal line content',()=>{const rows=revisionDiff('Keep\nNew\nEnd','Keep\nOld\nEnd').hunks[0].rows;assert.deepEqual(rows.map(({kind,text})=>({kind,text})),[{kind:'context',text:'Keep'},{kind:'removed',text:'Old'},{kind:'added',text:'New'},{kind:'context',text:'End'}]);});
