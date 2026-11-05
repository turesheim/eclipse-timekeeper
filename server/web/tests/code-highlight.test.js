import {test} from 'node:test';
import assert from 'node:assert/strict';
import {highlightCode} from '../editor/code-highlight.js';

test('highlight.js supports fence aliases and languages beyond the old editor subset',()=>{
  for(const [language,text,token]of [['js','const answer = 42;','const'],['yml','apiVersion: v1\nkind: Pod','apiVersion:'],['rust','fn main() { let answer = 42; }','fn']]){
    const spans=highlightCode(text,language);assert.ok(spans.some(span=>text.slice(span.from,span.to)===token),language);
    assert.ok(spans.every(span=>span.from>=0&&span.to<=text.length&&span.to>span.from&&/^[\w -]+$/.test(span.className)));
  }
});
test('code highlight offsets preserve escaped HTML, entities, CRLF and Unicode',()=>{
  const text='const message = "<img src=x onerror=alert(1)> &amp; ø😺";\r\n// After & < > " \'\r\n';
  const spans=highlightCode(text,'javascript'),comment=spans.find(span=>span.className==='hljs-comment');
  assert.equal(comment.from,text.indexOf('//'));assert.equal(text.slice(comment.from,comment.to),'// After & < > " \'');
  assert.ok(spans.some(span=>span.className==='hljs-string'&&text.slice(span.from,span.to).includes('<img src=x onerror=alert(1)> &amp; ø😺')));
});
test('unknown and unusually large fences keep plain text available',()=>{
  assert.deepEqual(highlightCode('exact <source>','unknown-language'),[]);
  assert.deepEqual(highlightCode('x'.repeat(200001),'javascript'),[]);
});
