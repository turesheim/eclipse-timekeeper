import {test} from 'node:test';
import assert from 'node:assert/strict';
import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkStringify from 'remark-stringify';
import remarkGfm from 'remark-gfm';
import {remarkInlineStyles} from '../editor/remark-inline-styles.js';

const parser=()=>unified().use(remarkParse).use(remarkGfm,{singleTilde:false}).use(remarkInlineStyles).use(remarkStringify);
const marks=node=>[...(['highlight','subscript','superscript','delete'].includes(node.type)?[node.type]:[]),...(node.children||[]).flatMap(marks)];
test('highlight, subscript and superscript parse alongside nested Markdown and double-tilde strike',()=>{
  const p=parser(),source='==very **important** words==, H~2~O, X^2^ and ~~obsolete~~.\n';
  const tree=p.runSync(p.parse(source));assert.deepEqual(marks(tree),['highlight','subscript','superscript','delete']);
  const saved=p.stringify(tree);assert.ok(saved.includes('==very **important** words=='));assert.ok(saved.includes('H~2~O'));assert.ok(saved.includes('X^2^'));
  assert.deepEqual(marks(p.runSync(p.parse(saved))),marks(tree));
});
test('code, escaped delimiters, incomplete forms and URL destinations remain literal',()=>{
  const p=parser();
  for(const source of ['`==a== ~2~ ^2^`','```md\n==a== ~2~ ^2^\n```','\\==a== \\~2~ \\^2^','==unfinished','~two words~','^two words^','[link](https://example.org/==a==/~2~/^2^)']){
    const tree=p.runSync(p.parse(source));assert.deepEqual(marks(tree),[],source);assert.deepEqual(marks(p.runSync(p.parse(p.stringify(tree)))),[],source);
  }
});
test('highlight admits nested inline styles and literal equals or code delimiters',()=>{
  const p=parser();
  for(const source of ['==a = b==','==a `x==y` z==','==**bold** and ~2~ and ^3^==','==x\\==y==']){
    const tree=p.runSync(p.parse(source));assert.equal(tree.children[0].children[0].type,'highlight',source);
    assert.deepEqual(marks(p.runSync(p.parse(p.stringify(tree)))),marks(tree),source);
  }
});
