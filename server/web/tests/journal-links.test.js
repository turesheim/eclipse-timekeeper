import {test} from 'node:test';
import assert from 'node:assert/strict';
import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkStringify from 'remark-stringify';
import {remarkDailyWikilinks} from '../editor/remark-daily-wikilinks.js';
import {dailyNoteDate,dailyNoteHref,dailyNoteDateFromSearch} from '../src/journal-links.js';
import {needsSource} from '../src/markdown-document.js';

const parser=()=>unified().use(remarkParse).use(remarkDailyWikilinks).use(remarkStringify);
const links=node=>[...(node.type==='dailyWikilink'?[node.value]:[]),...(node.children||[]).flatMap(links)];
test('daily-note targets use strict civil dates and an internal canonical route',()=>{
  for(const date of ['0001-01-01','2000-02-29','2026-10-26','9999-12-31']){
    assert.equal(dailyNoteDate(date),date);assert.equal(dailyNoteHref(date),'/web/?day='+date);assert.equal(dailyNoteDateFromSearch('?day='+date),date);
  }
  for(const value of [null,undefined,'0000-01-01','1900-02-29','2026-02-30','2026-13-01','2026-10-26/../x','2026-10-26#x','javascript:alert(1)','2026-1-1']){
    assert.equal(dailyNoteDate(value),null);assert.equal(dailyNoteHref(value),null);
  }
  assert.equal(dailyNoteDateFromSearch('?unrelated=true'),null);assert.equal(dailyNoteDateFromSearch('?day=2026-02-30'),null);
});
test('daily wikilinks are phrasing nodes and serialize as wikilinks through rich edits',()=>{
  const p=parser(),source='# Review\n\nSee **[[2026-10-26]]** and [[2026-10-27]].\n',tree=p.runSync(p.parse(source));
  assert.deepEqual(links(tree),['2026-10-26','2026-10-27']);assert.equal(needsSource(source),false);
  const serialized=p.stringify(tree);assert.match(serialized,/\*\*\[\[2026-10-26\]\]\*\*/);assert.ok(serialized.includes('[[2026-10-27]]'));
  assert.deepEqual(links(p.runSync(p.parse(serialized))),links(tree));
});
test('code, escaped brackets and existing link labels never create nested active daily links',()=>{
  const p=parser();
  for(const source of ['`[[2026-10-26]]`','```md\n[[2026-10-26]]\n```',String.raw`\[\[2026-10-26\]\]`,'[Before [[2026-10-26]] after](https://example.org)']){
    const tree=p.runSync(p.parse(source));assert.deepEqual(links(tree),[],source);assert.deepEqual(links(p.runSync(p.parse(p.stringify(tree)))),[],source);
  }
});
test('invalid dates, other wikilinks and embeds retain the conservative source fallback',()=>{
  for(const source of ['[[2026-02-30]]','[[0000-01-01]]','[[Project]]','[[2026-10-26|alias]]','![[2026-10-26]]','[[[2026-10-26]]]'])assert.equal(needsSource(source),true,source);
  const p=parser();assert.deepEqual(links(p.runSync(p.parse('[[2026-02-30]]'))),[]);
});
test('image alt text retains literal wiki brackets across Markdown round trips',()=>{
  const p=parser(),tree=p.runSync(p.parse('![A [[2026-10-26]]](assets/pixel.png)'));
  assert.equal(tree.children[0].children[0].alt,'A [[2026-10-26]]');assert.deepEqual(links(tree),[]);
  const again=p.runSync(p.parse(p.stringify(tree)));assert.equal(again.children[0].children[0].alt,'A [[2026-10-26]]');
});
