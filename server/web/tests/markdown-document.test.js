import {test} from 'node:test';
import assert from 'node:assert/strict';
import {splitMarkdown,needsSource,normalizeEditorMarkdown} from '../src/markdown-document.js';
import {hashtagKey,hashtagRanges} from '../src/hashtags.js';
import {bannerMetadata} from '../src/markdown-banner.js';
test('hashtags use exact bounded Unicode keys and ignore word, URL and wikilink fragments',()=>{
  assert.equal(hashtagKey('#Cafe\u0301'),'#café');assert.equal(hashtagKey('#Straße'),'#strasse');assert.equal(hashtagKey('#work/OpenShift'),'#work/openshift');
  for(const value of ['#','#a*','#a//b','#'+'a'.repeat(128)])assert.equal(hashtagKey(value),null);
  const text='🚀 #OpenShift (#Cafe\u0301) #work/Cloud words#no https://example.invalid/#no [[#heading]] ![[file#heading]]';
  assert.deepEqual(hashtagRanges(text).map(range=>[text.slice(range.from,range.to),range.key]),[['#OpenShift','#openshift'],['#Cafe\u0301','#café'],['#work/Cloud','#work/cloud']]);
});
test('frontmatter is kept byte-for-byte outside the body, including BOM and CRLF',()=>{
  for(const value of ['---\ntags: [daily]\n---\n\n# Day\n','\uFEFF---\r\ntitle: Mandag\r\n...\r\n\r\nA note\r\n','---\n---\nBody','---\nkey: value\n---']){
    const parts=splitMarkdown(value);assert.ok(parts.frontmatter);assert.equal(parts.unterminated,false);assert.equal(parts.frontmatter+parts.body,value);
  }
  assert.deepEqual(splitMarkdown('# Day\n\n---\n'),{frontmatter:'',body:'# Day\n\n---\n',unterminated:false});
  assert.deepEqual(splitMarkdown('---\ntags: [unfinished]'),{frontmatter:'',body:'---\ntags: [unfinished]',unterminated:true});
});
test('Obsidian banners read quoted top-level local links without changing opaque YAML',()=>{
  for(const value of ['"![[Images/Project cover.jpg]]"',"'[[Images/Project cover.jpg]]'"]){
    const source='\uFEFF---\r\n# Keep this comment\r\ntags: [one, two]\r\ncustom:\r\n  banner: ignored\r\nbanner: '+value+' # keep\r\nbanner_x: 0.25\r\nbanner_y: 1e-1\r\n...\r\n';
    assert.deepEqual(bannerMetadata(source),{reference:'Images/Project cover.jpg',x:.25,y:.1});
    assert.equal(splitMarkdown(source+'Body\r\n').frontmatter,source);
  }
  assert.deepEqual(bannerMetadata("---\n'banner': '[[Images/O''Brien.webp]]'\n---\n"),{reference:"Images/O'Brien.webp",x:.5,y:.5});
});
test('banner coordinates default independently and ambiguous axes do not override a valid axis',()=>{
  for(const x of ['-0.1','1.1','.nan','Infinity','"0.2"','true','[0.2]','null'])assert.deepEqual(bannerMetadata('---\nbanner: "[[cover.png]]"\nbanner_x: '+x+'\nbanner_y: 0\n---\n'),{reference:'cover.png',x:.5,y:0},x);
  assert.deepEqual(bannerMetadata('---\nbanner: "[[cover.gif]]"\nbanner_x: 1 # right\nbanner_y: .25\nbanner_y: .75\n---\n'),{reference:'cover.gif',x:1,y:.5});
});
test('unsupported, external, nested and ambiguous banner metadata stays inert',()=>{
  const bodies=['banner: "[[https://example.invalid/a.png]]"','banner: "https://example.invalid/a.png"','banner: "[[/private/a.png]]"','banner: "[[../a.png]]"','banner: "[[cover.svg]]"','banner: "[[cover.pdf]]"','banner: "[[cover.png#part]]"','banner: ![[cover.png]]','banner: ["[[cover.png]]"]','banner: |\n  ![[cover.png]]','banner: "[[one.png]]"\n"banner": "[[two.png]]"','nested:\n  banner: "[[cover.png]]"','banner: *image','banner: "[[cover.png]]"\n<<: *defaults','description: "multiline\nbanner: \'[[cover.png]]\'\n"','description: [multiline,\nbanner: "[[cover.png]]"\n]'];
  for(const body of bodies)assert.equal(bannerMetadata('---\n'+body+'\n---\n'),null,body);
  for(const value of [undefined,'','banner: "[[cover.png]]"','---\nbanner: "[[cover.png]]"'])assert.equal(bannerMetadata(value),null);
});
test('Obsidian extensions and unsupported constructs use exact source mode',()=>{
  for(const value of ['[[Project|alias]]','![[diagram.png]]','> [!note] Callout','%%private%%','$a+b$','text ^block-id','<div>HTML</div>','![image](https://example.org/pixel)','Footnote[^one]','[reference]: /somewhere',':::custom\ncontent'])assert.equal(needsSource(value),true,value);
  for(const value of ['# Heading\n\n**bold** and *emphasis*','- [ ] Task\n- [x] Done','[link](https://example.org)','> Quotation','| Name | Value |\n| --- | --- |\n| a | b |','```md\n[[literal]]\n```','~~~\n<div>Literal HTML</div>\n~~~','Example `[[literal]]`'])assert.equal(needsSource(value),false,value);
});
test('journal attachment images can render while external and unsafe images stay in source mode',()=>{
  for(const value of ['![Photo](assets/7f34343f-e83d-41d4-8cba-55782a286128.png)',
    'Before ![diagram](assets/diagram_1.webp) after'])assert.equal(needsSource(value),false,value);
  for(const value of ['![remote](https://example.org/pixel.png)','![escape](assets/../private.png)',
    '![script](javascript:alert(1))','![space](assets/my image.png)'])assert.equal(needsSource(value),true,value);
});

test('safe CommonMark autolinks remain renderable without admitting HTML or unsafe schemes',()=>{
  for(const value of ['<https://example.com/path?q=1&v=2>','<http://example.com>','<mailto:person@example.com>','<person@example.com>','A link <https://example.com> in text','<https://example.com/?token=abc==>','[link](https://example.com/?token=abc==)','https://example.com/?token=abc=='])assert.equal(needsSource(value),false,value);
  for(const value of ['<script>alert(1)</script>','<img src="https://example.com/pixel">','<javascript:alert(1)>','<data:text/html,x>','<https://example.com> <div>HTML</div>','<https://example.com onload=x>'])assert.equal(needsSource(value),true,value);
});
test('leading dashed horizontal rules are distinct from unfinished YAML frontmatter',()=>{
  for(const value of ['---','---\n','---\n\n','---\n\nParagraph\n'])assert.deepEqual(splitMarkdown(value),{frontmatter:'',body:value,unterminated:false});
  const yaml='---\ntags: [unfinished]';assert.deepEqual(splitMarkdown(yaml),{frontmatter:'',body:yaml,unterminated:true});
});
test('empty rich paragraphs serialize as Markdown blank lines without changing code or inline HTML',()=>{
  assert.equal(normalizeEditorMarkdown('<br />\n\nFirst line\n'),'\n\nFirst line\n');
  assert.equal(normalizeEditorMarkdown('Before\n\n<br />\n\nAfter\n'),'Before\n\n\n\nAfter\n');
  assert.equal(normalizeEditorMarkdown('```html\n<br />\n```\n'),'```html\n<br />\n```\n');
  assert.equal(normalizeEditorMarkdown('Before <br /> after\n'),'Before <br /> after\n');
});

test('setext headings and supported inline styles remain eligible for rich editing',()=>{
  assert.equal(needsSource('Heading\n=======\n\nText.\n'),false);
  assert.equal(needsSource('==highlight==\n'),false);
});

test('completion ranges consume generated closers while source code and escapes remain literal',async()=>{
  const {wikiCompletion,literalBracketContext}=await import('../src/markdown-brackets.js');
  assert.deepEqual(wikiCompletion('[[Des]]',5),{query:'Des',embed:false,from:0,to:7});
  assert.deepEqual(wikiCompletion('![[asset]]',8),{query:'asset',embed:true,from:0,to:10});
  assert.equal(wikiCompletion('\\[[Des]]',6),null);
  for(const value of ['```text\ncode','~~~text\ncode','    code','`code','`one\ntwo','[label](https://example.invalid/','\\','\\['])assert.equal(literalBracketContext(value,value.length),true,value);
  for(const value of ['ordinary text','```text\ncode\n```\nprose','`code` then prose','`one\ntwo` prose','`open\n\nprose','* List item','    * Nested item'])assert.equal(literalBracketContext(value,value.length),false,value);
});
