import {test} from 'node:test';
import assert from 'node:assert/strict';
import {splitMarkdown,needsSource} from '../src/markdown-document.js';
test('frontmatter is kept byte-for-byte outside the body, including BOM and CRLF',()=>{
  for(const value of ['---\ntags: [daily]\n---\n\n# Day\n','\uFEFF---\r\ntitle: Mandag\r\n...\r\n\r\nA note\r\n','---\n---\nBody','---\nkey: value\n---']){
    const parts=splitMarkdown(value);assert.ok(parts.frontmatter);assert.equal(parts.unterminated,false);assert.equal(parts.frontmatter+parts.body,value);
  }
  assert.deepEqual(splitMarkdown('# Day\n\n---\n'),{frontmatter:'',body:'# Day\n\n---\n',unterminated:false});
  assert.deepEqual(splitMarkdown('---\ntags: [unfinished]'),{frontmatter:'',body:'---\ntags: [unfinished]',unterminated:true});
});
test('Obsidian extensions and unsupported constructs use exact source mode',()=>{
  for(const value of ['[[Project|alias]]','![[diagram.png]]','> [!note] Callout','==highlight==','%%private%%','$a+b$','text ^block-id','<div>HTML</div>','![image](https://example.org/pixel)','Footnote[^one]','[reference]: /somewhere',':::custom\ncontent'])assert.equal(needsSource(value),true,value);
  for(const value of ['# Heading\n\n**bold** and *emphasis*','- [ ] Task\n- [x] Done','[link](https://example.org)','> Quotation','| Name | Value |\n| --- | --- |\n| a | b |','```md\n[[literal]]\n```','~~~\n<div>Literal HTML</div>\n~~~','Example `[[literal]]`'])assert.equal(needsSource(value),false,value);
});
