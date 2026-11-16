import {test} from 'node:test';
import assert from 'node:assert/strict';
import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkStringify from 'remark-stringify';
import remarkGfm from 'remark-gfm';
import {remarkInlineStyles} from '../editor/remark-inline-styles.js';
import {normalizeFormatting} from '../editor/redundant-formatting.js';

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

test('redundant nested bold retains the complete outer span without mutating source nodes',()=>{
  const p=parser(),tree=p.runSync(p.parse('**Wait before activating **ControllerConfig**.**\n')),before=structuredClone(tree);
  const normalized=normalizeFormatting(tree),strong=normalized.children[0].children[0];
  assert.equal(strong.type,'strong');assert.deepEqual(strong.children.map(node=>[node.type,node.value]),[['text','Wait before activating ControllerConfig.']]);
  assert.equal(p.stringify(normalized),'**Wait before activating ControllerConfig.**\n');assert.deepEqual(tree,before);
});

test('different nested marks and link destinations retain their meaning',()=>{
  const p=parser(),source='**Outer *emphasis* and [**linked**](https://example.invalid/a "Title") tail.**\n';
  const normalized=normalizeFormatting(p.runSync(p.parse(source))),outer=normalized.children[0].children[0];
  assert.equal(outer.type,'strong');assert.equal(outer.children[1].type,'emphasis');
  const link=outer.children.find(node=>node.type==='link');assert.equal(link.url,'https://example.invalid/a');assert.equal(link.title,'Title');assert.equal(link.children[0].value,'linked');
  assert.equal(outer.children.at(-1).value,' tail.');
});
