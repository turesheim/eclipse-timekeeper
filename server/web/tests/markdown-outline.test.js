import test from 'node:test';
import assert from 'node:assert/strict';
import {markdownOutline} from '../editor/markdown-outline.js';

test('the contents list follows heading levels and readable inline text, including repeated headings',()=>{
  const source='# Plan\n\n### **Build** and [ship](https://example.com)\n\n## ~~Done~~ with `code`\n\n# Plan\n';
  const headings=markdownOutline(source);
  assert.deepEqual(headings.map(({text,level})=>({text,level})),[{text:'Plan',level:1},{text:'Build and ship',level:3},{text:'Done with code',level:2},{text:'Plan',level:1}]);
  assert.notEqual(headings[0].offset,headings[3].offset);
  assert.equal(source.slice(headings[3].offset),'# Plan\n');
});

test('metadata and code examples never become contents entries',()=>{
  const source='---\ntitle: private\n# Metadata\n---\n\n```md\n# Example\n```\n\n    # Indented code\n\n\\# Literal hash\n\n<!--\n# Hidden HTML\n-->\n\n## Actual heading\n';
  assert.deepEqual(markdownOutline(source),[{text:'Actual heading',level:2,offset:source.indexOf('## Actual heading')}]);
});

test('Setext, nested and Unicode headings retain their exact source navigation positions',()=>{
  const source='---\r\nname: ÆØÅ\r\n---\r\n\r\nDagsverk æøå 😄\r\n================\r\n\r\n> ## A quoted heading\r\n';
  assert.deepEqual(markdownOutline(source),[{text:'Dagsverk æøå 😄',level:1,offset:source.indexOf('Dagsverk')},{text:'A quoted heading',level:2,offset:source.indexOf('## A quoted')}]);
});
