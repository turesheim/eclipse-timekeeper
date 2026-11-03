import {captureScreenshot} from './screenshots.js';
import AxeBuilder from '@axe-core/playwright';
import {test,expect} from '@playwright/test';

async function engine(page,value,options={}){
  await page.goto('/web/markdown-lab.html');
  await page.evaluate(async({value,options})=>{
    const {createMarkdownEditor}=await import('/web/assets/milkdown/milkdown.js');
    const root=document.createElement('div');root.id='experience-editor';document.body.append(root);
    globalThis.experience=await createMarkdownEditor({root,value,label:'Experience body',taskLabel:text=>text,escape:()=>{},...options});
  },{value,options});
  await expect(page.locator('#experience-editor .ProseMirror')).toBeVisible();
}
const source=page=>page.evaluate(()=>globalThis.experience.markdown());

test('unknown constructs are inert editable fragments while surrounding Markdown remains formatted',async({page})=>{
  const value='Before.\r\n\r\n<script>window.bad=true</script>\r\n\r\n==unknown highlight==\r\n\r\nAfter.\r\n';
  await engine(page,value);expect(await source(page)).toBe(value);expect(await page.evaluate(()=>globalThis.bad)).toBeUndefined();
  await expect(page.locator('.markdown-source-fragment textarea')).toHaveCount(2);
  await page.locator('#experience-editor p').filter({hasText:'After.'}).click();await page.keyboard.press('End');await page.keyboard.type(' Edited.');
  expect(await source(page)).toBe(value.replace('After.','After. Edited.'));
  await page.locator('.markdown-source-fragment textarea').last().fill('==changed highlight==');expect(await source(page)).toContain('==changed highlight==');
});
test('wiki aliases, headings and sized embeds preserve their portable source',async({page})=>{
  const value='Read [[Notes/Design#Decisions|the design]] and [[2026-11-02]].\n\n![[journal/assets/image.png|320x200]]\n';
  await engine(page,value);await expect(page.locator('[data-wiki-reference="Notes/Design#Decisions|the design"]')).toHaveText('the design');expect(await source(page)).toBe(value);
  await page.locator('#experience-editor p').first().click({position:{x:3,y:3}});await page.keyboard.press('Home');await page.keyboard.type('Now ');expect(await source(page)).toContain('[[Notes/Design#Decisions|the design]]');
});
test('source fragments cannot be changed in a read-only editor',async({page})=>{
  await engine(page,'Before.\n\n<div>Unknown</div>\n',{readOnly:true});await expect(page.locator('.markdown-source-fragment textarea')).toHaveAttribute('readonly','');
  expect(await page.evaluate(()=>globalThis.experience.command('bold'))).toBe(false);
});

async function field(page,value,formatted=true,catalog=[]){
  await page.goto('/web/markdown-lab.html');await page.evaluate(()=>{const link=document.createElement('link');link.rel='stylesheet';link.href='/web/assets/bootstrap-icons/bootstrap-icons.min.css';document.head.append(link);});
  await page.evaluate(async({value,formatted,catalog})=>{
    const {MarkdownField}=await import('/web/markdown-field.js');const root=document.createElement('section');root.id='experience-field';document.body.append(root);
    const resources=catalog.length?{allowsAsset:()=>false,allowsLink:()=>false,complete:async query=>catalog.filter(entry=>entry.path.toLowerCase().includes(query.toLowerCase())),resolveWiki:async()=>catalog[0],openWiki:async()=>{}}:undefined;
    globalThis.field=new MarkdownField(root,()=>{},()=>{},'experience',async file=>{if(globalThis.failUpload||globalThis.failUploadName===file.name)throw new Error('Upload failed');(globalThis.uploaded??=[]).push(file.name);return 'assets/'+file.name;},resources);
    globalThis.field.setValue(value);globalThis.field.setReadOnly(false);if(formatted)await globalThis.field.showFormatted();
  },{value,formatted,catalog});
}
test('rich paste retains safe structure and URL-over-selection preserves the selected text',async({page})=>{
  await engine(page,'Text to link.\n');await page.locator('#experience-editor p').click();await page.keyboard.press('End');
  await page.evaluate(()=>{const data=new DataTransfer();data.setData('text/plain','Heading\nBold');data.setData('text/html','<h2 onclick="bad()">Heading</h2><p><strong>Bold</strong><img src="https://evil.invalid/track"><script>window.bad=true</script></p>');document.querySelector('#experience-editor .ProseMirror').dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));});
  await expect(page.locator('#experience-editor h2')).toHaveText('Heading');await expect(page.locator('#experience-editor strong')).toHaveText('Bold');await expect(page.locator('#experience-editor img')).toHaveCount(0);expect(await page.evaluate(()=>globalThis.bad)).toBeUndefined();
  await page.evaluate(()=>{const range=document.createRange();range.selectNodeContents(document.querySelector('#experience-editor strong'));const selection=getSelection();selection.removeAllRanges();selection.addRange(range);document.dispatchEvent(new Event('selectionchange'));});await expect.poll(()=>page.evaluate(()=>globalThis.experience.selectedText())).toBe('Bold');
  await page.evaluate(()=>{const data=new DataTransfer();data.setData('text/plain','https://example.org/design');document.querySelector('#experience-editor .ProseMirror').dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));});
  await expect(page.locator('#experience-editor a[href="https://example.org/design"]')).toHaveText('Bold');
});
test('failed pasted files retain the draft and can be retried at the original insertion',async({page})=>{
  await field(page,'Keep this draft.\n');await page.locator('#experience-formatted p').click();await page.keyboard.press('End');
  await page.evaluate(()=>{globalThis.failUpload=true;const data=new DataTransfer();data.items.add(new File(['bytes'],'example.txt',{type:'text/plain'}));document.querySelector('#experience-formatted .ProseMirror').dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));});
  await expect(page.locator('#experience-field')).toContainText('Upload failed');expect(await page.evaluate(()=>globalThis.field.value())).toBe('Keep this draft.\n');
  await page.evaluate(()=>globalThis.failUpload=false);await page.locator('#experience-field').getByRole('button',{name:'Retry',exact:true}).click();
  await expect(page.locator('#experience-formatted a')).toHaveText('example.txt');expect(await page.evaluate(()=>globalThis.field.value())).toContain('Keep this draft.');
});
test('source tools preserve mixed line endings and provide highlighted source, search and split preview',async({page})=>{
  const value='# Title\r\n\r\nKeep __this__.\n\nLast.\r\n';await field(page,value,false);
  await page.locator('#experience-field').getByRole('button',{name:'Source editor tools',exact:true}).click();await expect(page.locator('#experience-field .cm-editor')).toBeVisible();expect(await page.evaluate(()=>globalThis.field.value())).toBe(value);
  await page.locator('#experience-field .cm-content').click();await page.keyboard.press('ControlOrMeta+End');await page.keyboard.type('Added');expect(await page.evaluate(()=>globalThis.field.value())).toBe(value+'Added');
  await page.locator('#experience-field').getByRole('button',{name:'Find and replace',exact:true}).click();await expect(page.locator('.cm-search')).toBeVisible();
  await page.locator('#experience-field').getByRole('button',{name:'Source and preview',exact:true}).click();await expect(page.locator('.markdown-split-preview h1')).toHaveText('Title');
});
test('table commands edit rows, columns and alignment and preserve other blocks',async({page})=>{
  await engine(page,'Before.\n\n| A | B |\n| --- | --- |\n| One | Two |\n\nAfter.\n');await page.locator('#experience-editor td p').first().click({position:{x:3,y:3}});await expect.poll(()=>page.evaluate(()=>globalThis.experience.context().table)).toBe(true);
  expect(await page.evaluate(()=>globalThis.experience.command('row_after'))).toBe(true);await expect(page.locator('#experience-editor tr')).toHaveCount(3);
  expect(await page.evaluate(()=>globalThis.experience.command('column_after'))).toBe(true);await expect(page.locator('#experience-editor tr').first().locator('th')).toHaveCount(3);
  expect(await page.evaluate(()=>globalThis.experience.command('align_right'))).toBe(true);expect(await source(page)).toContain('Before.');expect(await source(page)).toContain('After.');
});
test('callouts and footnotes render, remain editable and preserve Markdown references',async({page})=>{
  const value='Text[^one].\n\n> [!NOTE] Decision\n> Keep this callout.\n\n[^one]: A **useful** footnote.\n\nTail.\n';await engine(page,value);
  await expect(page.locator('.markdown-callout')).toContainText('Keep this callout.');await expect(page.locator('[data-footnote-reference]')).toHaveText('one');await expect(page.locator('[data-footnote-definition]')).toContainText('useful');expect(await source(page)).toBe(value);
  await page.locator('.markdown-callout input').fill('New decision');await page.locator('.markdown-callout input').press('Tab');const edited=await source(page);expect(edited).toContain('> [!NOTE] New decision');expect(edited).toContain('[^one]: A **useful** footnote.');
});
test('code blocks expose language and copy controls and highlight without replacing their source',async({page})=>{
  const value='~~~js\nconst answer = 42;\n~~~\n';await engine(page,value);await expect(page.locator('#experience-editor .tok-keyword')).toContainText('const');expect(await source(page)).toBe(value);
  await page.getByRole('combobox',{name:'Code language',exact:true}).fill('javascript');await page.getByRole('combobox',{name:'Code language',exact:true}).press('Tab');expect(await source(page)).toContain('```javascript');await expect(page.getByRole('button',{name:'Copy code',exact:true})).toBeVisible();
});
test('switching to source and back without edits keeps rich undo history',async({page})=>{
  await field(page,'Original.\n');await page.locator('#experience-formatted p').click();await page.keyboard.press('End');await page.keyboard.type(' Added.');
  const text=await page.evaluate(()=>globalThis.field.value());expect(text).toContain('Added.');await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#experience-field').getByRole('button',{name:'Formatted',exact:true}).click();
  await page.locator('#experience-field').getByRole('button',{name:'Undo',exact:true}).click();expect(await page.evaluate(()=>globalThis.field.value())).toBe('Original.\n');
});
test('multi-file upload retry keeps completed files and does not move an unrelated caret',async({page})=>{
  await field(page,'One.\n\nTwo.\n');await page.locator('#experience-formatted .ProseMirror').focus();await page.evaluate(()=>{const range=document.createRange();range.selectNodeContents(document.querySelector('#experience-formatted p'));range.collapse(false);getSelection().removeAllRanges();getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));});await expect.poll(()=>page.evaluate(()=>globalThis.field.editor.context().text)).toBe('One.');
  await page.evaluate(()=>{globalThis.failUploadName='second.txt';const data=new DataTransfer();for(const name of ['first.txt','second.txt'])data.items.add(new File(['bytes'],name,{type:'text/plain'}));document.querySelector('#experience-formatted .ProseMirror').dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));});
  await expect(page.locator('#experience-field')).toContainText('Upload failed');await expect(page.locator('#experience-formatted a')).toHaveCount(1);await page.locator('#experience-formatted p').last().click();await page.evaluate(()=>{const range=document.createRange();range.selectNodeContents(document.querySelector('#experience-formatted .ProseMirror').lastElementChild);range.collapse(false);getSelection().removeAllRanges();getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));});await expect.poll(()=>page.evaluate(()=>globalThis.field.editor.context().text)).toBe('Two.');
  await page.evaluate(()=>globalThis.failUploadName=null);await page.locator('#experience-field').getByRole('button',{name:'Retry',exact:true}).click();await expect(page.locator('#experience-formatted a')).toHaveCount(2);expect(await page.evaluate(()=>globalThis.uploaded)).toEqual(['first.txt','second.txt']);
  await page.locator('#experience-formatted .ProseMirror').pressSequentially(' Kept caret.');await expect(page.locator('#experience-formatted p').last()).toContainText('Two. Kept caret.');await expect(page.locator('#experience-formatted p').first().locator('a')).toHaveCount(2);
});
test('source undo restores the exact original line ending after joining mixed-EOL lines',async({page})=>{
  const value='First.\r\nSecond.\nThird.\r\n';await field(page,value,false);await page.locator('#experience-field').getByRole('button',{name:'Source editor tools',exact:true}).click();
  await expect(page.locator('#experience-field .cm-editor')).toBeVisible();await page.evaluate(()=>globalThis.field.codeEditor.select(14));await page.keyboard.press('Delete');expect(await page.evaluate(()=>globalThis.field.value())).not.toBe(value);await page.keyboard.press('ControlOrMeta+z');expect(await page.evaluate(()=>globalThis.field.value())).toBe(value);
});
test('shared controls remain keyboard accessible and fit narrow screens in every theme',async({page})=>{
  await page.setViewportSize({width:390,height:844});await field(page,'# Heading\n\n- [ ] Task\n\nParagraph.\n');await page.locator('#experience-field').getByRole('button',{name:'More formatting',exact:true}).click();
  for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=['timekeeper','solarized-light'].includes(theme)?'light':'dark';},theme);
    expect((await new AxeBuilder({page}).include('#experience-field').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations,theme).toEqual([]);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }
  await page.locator('#experience-field').screenshot({path:'test-results/editor-experience-mobile.png'});
  await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#experience-field').getByRole('button',{name:'Source editor tools',exact:true}).click();await page.locator('#experience-field').screenshot({path:'test-results/editor-experience-source-mobile.png'});
});
test('source styles keep the strict CSP and a horizontal gutter without trapping keyboard focus',async({page})=>{
  await field(page,'# Heading\n\nBody.\n',false);await page.evaluate(()=>{globalThis.styleViolations=[];document.addEventListener('securitypolicyviolation',event=>{if(event.violatedDirective.startsWith('style-src'))globalThis.styleViolations.push(event.blockedURI);});});
  await page.locator('#experience-field').getByRole('button',{name:'Source editor tools',exact:true}).click();const content=page.locator('#experience-field .cm-content');await expect(content).toBeVisible();await expect(page.locator('#experience-field .cm-scroller')).toHaveCSS('display','flex');await expect(page.locator('#experience-field .cm-gutters')).toHaveCSS('display','flex');
  await content.click();await page.keyboard.press('Escape');await page.keyboard.press('Tab');expect(await content.evaluate(node=>node.getRootNode().activeElement===node)).toBe(false);expect(await page.evaluate(()=>globalThis.styleViolations)).toEqual([]);
  await page.locator('#experience-field').screenshot({path:'test-results/editor-experience-source-styled.png'});
});
test('rich undo and redo span a source edit, including a change of Markdown spelling',async({page})=>{
  await field(page,'Title\n=====\n\nOriginal.\n');const original=await page.evaluate(()=>globalThis.field.value());await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();
  const changed=original.replace('Original.','__Changed__.');await page.locator('#experience-body').fill(changed);await page.locator('#experience-field').getByRole('button',{name:'Formatted',exact:true}).click();expect(await page.evaluate(()=>globalThis.field.value())).toBe(changed);
  await page.locator('#experience-field').getByRole('button',{name:'Undo',exact:true}).click();expect(await page.evaluate(()=>globalThis.field.value())).toBe(original);await page.locator('#experience-field').getByRole('button',{name:'Redo',exact:true}).click();expect(await page.evaluate(()=>globalThis.field.value())).toBe(changed);
  await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#experience-body').fill(original);await page.locator('#experience-field').getByRole('button',{name:'Formatted',exact:true}).click();expect(await page.evaluate(value=>globalThis.field.matches(value),original)).toBe(true);
});
test('enhanced source history survives a formatted round trip',async({page})=>{
  await field(page,'Original.\n',false);await page.locator('#experience-field').getByRole('button',{name:'Source editor tools',exact:true}).click();const content=page.locator('#experience-field .cm-content');await expect(content).toBeVisible();await content.click();await page.keyboard.press('ControlOrMeta+End');await page.keyboard.type('Added.');
  await page.locator('#experience-field').getByRole('button',{name:'Formatted',exact:true}).click();await expect(page.locator('#experience-formatted .ProseMirror')).toBeVisible();await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();await expect(content).toBeVisible();await content.click();await page.keyboard.press('ControlOrMeta+z');expect(await page.evaluate(()=>globalThis.field.value())).toBe('Original.\n');
});
test('editing around authored break HTML never mistakes it for an empty editor paragraph',async({page})=>{
  const value='Before.\r\n\r\n<br />\r\n\r\nAfter.\r\n';await engine(page,value);await expect(page.locator('.markdown-source-fragment textarea')).toHaveValue('<br />');await page.locator('#experience-editor p').last().click();await page.keyboard.press('End');await page.keyboard.type(' Kept.');expect(await source(page)).toBe(value.replace('After.','After. Kept.'));
});
test('typed and pasted ordinary wikilinks become active without escaping their Markdown',async({page})=>{
  await engine(page,'');await page.locator('#experience-editor .ProseMirror').click();await page.keyboard.type('[[Notes/Design|design]]');await expect(page.locator('[data-wiki-reference="Notes/Design|design"]')).toHaveText('design');expect(await source(page)).toContain('[[Notes/Design|design]]');
  await page.keyboard.press('End');await page.evaluate(()=>{const data=new DataTransfer();data.setData('text/plain','![[journal/assets/pixel.png|32]]');document.querySelector('#experience-editor .ProseMirror').dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));});expect(await source(page)).toContain('![[journal/assets/pixel.png|32]]');
});
test('enhanced source completes vault names without changing line endings or metadata',async({page})=>{
  await field(page,'',false,[{path:'Notes/Design.md',kind:'MARKDOWN'}]);await page.locator('#experience-field').getByRole('button',{name:'Source editor tools',exact:true}).click();const content=page.locator('#experience-field .cm-content');await expect(content).toBeVisible();await content.click();await page.keyboard.type('[[Des');await page.getByRole('option',{name:'Notes/Design.md'}).click();expect(await page.evaluate(()=>globalThis.field.value())).toBe('[[Notes/Design]]');
});
test('spreadsheet paste creates a table and fills existing cells without nesting tables',async({page})=>{
  await engine(page,'Before.\n\nAfter.\n');await page.locator('#experience-editor p').last().click();
  const paste=async text=>page.evaluate(text=>{const data=new DataTransfer();data.setData('text/plain',text);document.querySelector('#experience-editor .ProseMirror').dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));},text);
  await paste('A\tB\nOne\tTwo');await expect(page.locator('#experience-editor table')).toHaveCount(1);await expect(page.locator('#experience-editor tr')).toHaveCount(2);await expect(page.locator('#experience-editor th')).toHaveText(['A','B']);
  await page.locator('#experience-editor td p').first().click();await expect.poll(()=>page.evaluate(()=>globalThis.experience.context().table)).toBe(true);await paste('Three\tFour');await expect(page.locator('#experience-editor table')).toHaveCount(1);await expect(page.locator('#experience-editor td')).toHaveText(['Three','Four']);expect(await source(page)).toContain('Before.');
  await page.locator('#experience-editor td p').first().click();await paste('Five\tSix\tSeven\nEight\tNine\tTen');await expect(page.locator('#experience-editor tr')).toHaveCount(3);await expect(page.locator('#experience-editor th')).toHaveCount(3);await expect(page.locator('#experience-editor td')).toHaveText(['Five','Six','Seven','Eight','Nine','Ten']);
});
test('heading levels and formatted selection stay meaningful across source mode',async({page})=>{
  await field(page,'# Heading\n\nFirst **bold** phrase.\n\nLast paragraph.\n');
  await page.locator('#experience-formatted p').first().click();await page.evaluate(()=>{const text=document.querySelector('#experience-formatted strong').firstChild,range=document.createRange();range.setStart(text,0);range.setEnd(text,4);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);document.dispatchEvent(new Event('selectionchange'));});
  await expect.poll(()=>page.evaluate(()=>globalThis.field.editor.selectedText())).toBe('bold');
  await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();
  expect(await page.locator('#experience-body').evaluate(node=>node.value.slice(node.selectionStart,node.selectionEnd))).toBe('bold');
  await page.locator('#experience-field').getByRole('button',{name:'Formatted',exact:true}).click();expect(await page.evaluate(()=>globalThis.field.editor.selectedText())).toBe('bold');
});
test('file drop inserts at the pointed paragraph and preserves the other paragraph',async({page})=>{
  await field(page,'One.\n\nTwo.\n');const paragraph=page.locator('#experience-formatted p').last();await paragraph.scrollIntoViewIfNeeded();
  await paragraph.evaluate(node=>{const bounds=node.getBoundingClientRect(),data=new DataTransfer();data.items.add(new File(['attachment'],'dropped.txt',{type:'text/plain'}));node.closest('.ProseMirror').dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,clientX:bounds.left+10,clientY:bounds.top+bounds.height/2,dataTransfer:data}));});
  await expect(page.locator('#experience-formatted p').first()).toHaveText('One.');await expect(page.locator('#experience-formatted p').last().getByRole('link')).toHaveText('dropped.txt');expect(await page.evaluate(()=>globalThis.uploaded)).toEqual(['dropped.txt']);
});
test('composition keeps Unicode text and does not accept a slash command on composing Enter',async({page})=>{
  await field(page,'');const rich=page.locator('#experience-formatted .ProseMirror');await rich.click();await page.keyboard.type('/');
  await rich.evaluate(node=>node.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true,data:''})));await page.keyboard.insertText('日本語');
  await rich.evaluate(node=>{node.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',isComposing:true,bubbles:true,cancelable:true}));node.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true,data:'日本語'}));});
  await expect(page.locator('#experience-formatted p')).toHaveText('/日本語');await expect(page.locator('#experience-formatted :is(h1,h2,h3,h4,h5,h6)')).toHaveCount(0);expect(await page.evaluate(()=>globalThis.field.value())).toContain('日本語');
});
test('revision comparison exposes line-ending-only changes',async({page})=>{
  await page.goto('/web/markdown-lab.html');const diff=await page.evaluate(async()=>{const {lineComparison}=await import('/web/note-revisions.js');return lineComparison('Same.\n','Same.\r\n');});expect(diff).toContain('\\r\\n');expect(diff).toContain('- ');expect(diff).toContain('+ ');
});

test('formatted checklists stay editable without extra formatting panels',async({page})=>{
  const value='* [ ] Oppgradere AMQ i PROD\n* [ ] <br />\n';await field(page,value);
  await expect(page.locator('#experience-formatted input[type=checkbox]')).toHaveCount(2);
  await expect(page.locator('#experience-field .markdown-source-fragment')).toHaveCount(0);
  await expect(page.locator('#experience-field .markdown-structured, #experience-field .markdown-context-menu')).toHaveCount(0);
  expect(await page.evaluate(()=>globalThis.field.value())).toBe(value);
  await captureScreenshot(page,{path:'test-results/simple-checklist-editor.png',fullPage:true});
  await page.locator('#experience-formatted input[type=checkbox]').first().check();
  expect(await page.evaluate(()=>globalThis.field.value())).toContain('[x] Oppgradere AMQ i PROD');
  await page.evaluate(()=>{const paragraph=document.querySelector('#experience-formatted p'),range=document.createRange();range.selectNodeContents(paragraph);getSelection().removeAllRanges();getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));});
  await expect(page.locator('#experience-field .markdown-context-menu')).toHaveCount(0);
});

for(const enhanced of [false,true])test(`split preview retains reading position and source focus (${enhanced?'highlighted':'plain'} source)`,async({page})=>{
  const value=Array.from({length:100},(_,i)=>`## Block ${i}\n\nParagraph ${i}. ${'Keep writing without jumps. '.repeat(6)}\n`).join('\n');await field(page,value,false);
  await page.evaluate(()=>{for(const node of [globalThis.field.text,globalThis.field.codeHost,globalThis.field.splitHost])Object.assign(node.style,{height:'320px',minHeight:'0',maxHeight:'320px'});});
  if(enhanced)await page.locator('#experience-field').getByRole('button',{name:'Source editor tools',exact:true}).click();
  await page.locator('#experience-field').getByRole('button',{name:'Source and preview',exact:true}).click();await expect(page.locator('.markdown-split-preview h2')).toHaveCount(100);
  const offset=await page.evaluate(enhanced=>{const f=globalThis.field,offset=f.rawBody().indexOf('Paragraph 55.');if(enhanced)f.codeEditor.select(offset);else{f.text.focus();f.text.setSelectionRange(offset,offset);}return offset;},enhanced);
  const scrollSource=enhanced?page.locator('#experience-field .cm-scroller'):page.locator('#experience-body');await scrollSource.hover();await page.mouse.wheel(0,2000);
  await expect.poll(()=>page.locator('.markdown-split-preview').evaluate(node=>node.scrollTop)).toBeGreaterThan(100);
  await page.keyboard.insertText('Edited ');await expect(page.locator('.markdown-split-preview')).toContainText('Edited Paragraph 55.');
  expect(await page.evaluate(()=>globalThis.field.position().anchor)).toBe(offset+7);
  const source=enhanced?page.locator('#experience-field .cm-content'):page.locator('#experience-body');await expect(source).toBeFocused();
  expect(await page.locator('.markdown-split-preview').evaluate(node=>node.scrollTop)).toBeGreaterThan(100);
  if(enhanced){
    await page.locator('#experience-field').getByRole('button',{name:'Source editor tools',exact:true}).click();await expect(page.locator('#experience-body')).toBeFocused();
    expect(await page.locator('#experience-body').evaluate(node=>node.selectionStart)).toBe(offset+7);
    await page.locator('#experience-field').getByRole('button',{name:'Source editor tools',exact:true}).click();await expect(page.locator('#experience-field .cm-content')).toBeFocused();
    expect(await page.evaluate(()=>globalThis.field.codeEditor.position().anchor)).toBe(offset+7);
  }
  await expect.poll(()=>page.locator('.markdown-split-preview').evaluate(node=>node.scrollTop)).toBeGreaterThan(100);
  await page.locator('.markdown-split-preview').hover();await page.mouse.wheel(0,-100000);
  await expect.poll(()=>page.evaluate(()=>{const f=globalThis.field;return (f.codeEditor?.scrollElement||f.text).scrollTop;})).toBeLessThan(5);
});

test('a delayed split preview cannot replace newer content or reappear after clearing the note',async({page})=>{
  await page.goto('/web/markdown-lab.html');await page.evaluate(async()=>{
    const {buildPreview}=await import('/web/markdown-preview.js');
    const plain=document.createElement('div'),text=document.createElement('textarea'),splitHost=document.createElement('div');plain.append(text,splitHost);document.body.append(plain);
    globalThis.previews=[];globalThis.previewField={plain,text,splitHost,generation:1,rawBody:()=>text.value};
    buildPreview(globalThis.previewField,async()=>({createMarkdownEditor:options=>new Promise(resolve=>{const entry={value:options.value,resolve:()=>{options.root.textContent=options.value;resolve({destroy:async()=>{entry.destroyed=true;}});}};globalThis.previews.push(entry);})}));
    text.value='Old preview';globalThis.previewField.refreshPreview();
  });
  await expect.poll(()=>page.evaluate(()=>globalThis.previews.length)).toBe(1);
  await page.evaluate(()=>{globalThis.previewField.text.value='Newest preview';globalThis.previewField.refreshPreview();});
  await expect.poll(()=>page.evaluate(()=>globalThis.previews.length)).toBe(2);
  await page.evaluate(()=>globalThis.previews[1].resolve());await expect(page.locator('body')).toContainText('Newest preview');
  await page.evaluate(()=>globalThis.previews[0].resolve());await expect.poll(()=>page.evaluate(()=>globalThis.previews[0].destroyed)).toBe(true);
  expect(await page.evaluate(()=>globalThis.previewField.splitHost.textContent)).toBe('Newest preview');
  await page.evaluate(()=>{globalThis.previewField.text.value='Private pending preview';globalThis.previewField.refreshPreview();});await expect.poll(()=>page.evaluate(()=>globalThis.previews.length)).toBe(3);
  await page.evaluate(()=>{globalThis.previewField.generation++;globalThis.previewField.resetPreview();globalThis.previewField.splitHost.hidden=true;globalThis.previews[2].resolve();});
  await expect.poll(()=>page.evaluate(()=>globalThis.previews[2].destroyed)).toBe(true);expect(await page.evaluate(()=>globalThis.previewField.splitHost.textContent)).toBe('');
});

test('Find opens after highlighted source loads without relying on a timer',async({page})=>{
  await field(page,'Find this text.\n',false);let release,entered;
  const held=new Promise(resolve=>release=resolve),started=new Promise(resolve=>entered=resolve);
  await page.route('**/assets/milkdown/source-editor.js',async route=>{entered();await held;await route.continue();});
  await page.clock.install();await page.locator('#experience-field').getByRole('button',{name:'Find and replace',exact:true}).click();
  await started;await expect(page.locator('#experience-field').getByRole('button',{name:'Find and replace',exact:true})).toBeDisabled();await page.clock.fastForward(500);release();
  await expect(page.locator('#experience-field .cm-search')).toBeVisible();expect(await page.evaluate(()=>globalThis.field.value())).toBe('Find this text.\n');
});
