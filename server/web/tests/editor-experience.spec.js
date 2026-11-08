import {openEditorFixture} from './editor-fixture.js';
import {appendEditorText} from './editor-interactions.js';
import {captureScreenshot} from './screenshots.js';
import AxeBuilder from '@axe-core/playwright';
import {test,expect} from '@playwright/test';

async function engine(page,value,options={}){
  await openEditorFixture(page);
  await page.evaluate(async({value,options})=>{
    const {createMarkdownEditor}=await import('/web/assets/milkdown/milkdown.js');
    const root=document.createElement('div');root.id='experience-editor';document.body.append(root);
    globalThis.experience=await createMarkdownEditor({root,value,label:'Experience body',taskLabel:text=>text,escape:()=>{},...options});
  },{value,options});
  await expect(page.locator('#experience-editor .ProseMirror')).toBeVisible();
}
const source=page=>page.evaluate(()=>globalThis.experience.markdown());

test('Backspace deletes rich text and joins paragraphs at the caret',async({page})=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await field(page,'First.\n\nSecond.\n');const paragraph=page.locator('#experience-formatted p').last();
  await appendEditorText(paragraph,'x');await page.keyboard.press('Backspace');await expect(paragraph).toHaveText('Second.');
  await paragraph.evaluate(node=>{node.closest('[contenteditable=true]').focus();const range=document.createRange();range.selectNodeContents(node);range.collapse(true);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);document.dispatchEvent(new Event('selectionchange'));});
  await page.keyboard.press('Backspace');await expect(page.locator('#experience-formatted p')).toHaveText(['First.Second.']);
  await page.keyboard.press('ControlOrMeta+z');await expect(page.locator('#experience-formatted p')).toHaveText(['First.','Second.']);expect(errors).toEqual([]);
});

test('new checkbox items retain a visible caret and Backspace removes the empty item',async({page})=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await field(page,'- [ ] First task\n');const rich=page.locator('#experience-formatted .ProseMirror');
  await appendEditorText(rich.locator('li p'),'');await page.keyboard.press('Enter');
  await expect(rich.locator('li[data-task=true]')).toHaveCount(2);
  const empty=rich.locator('li').last().locator('p');
  const caret=await empty.evaluate(node=>{const selection=getSelection(),rect=globalThis.field.editor.selectionRect(),box=node.getBoundingClientRect();return {inside:node.contains(selection.anchorNode),height:rect.bottom-rect.top,width:box.width,focus:node.closest('.ProseMirror')===document.activeElement};});
  expect(caret.inside).toBe(true);expect(caret.focus).toBe(true);expect(caret.height).toBeGreaterThan(0);expect(caret.width).toBeGreaterThan(8);
  await page.keyboard.type('Second task');await expect(empty).toHaveText('Second task');
  for(let count=0;count<'Second task'.length;count++)await page.keyboard.press('Backspace');
  await expect(empty).toHaveText('');await page.keyboard.press('Backspace');await expect(rich.locator('li')).toHaveCount(1);
  expect(errors).toEqual([]);
});

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
  await openEditorFixture(page);await page.evaluate(()=>{const link=document.createElement('link');link.rel='stylesheet';link.href='/web/assets/bootstrap-icons/bootstrap-icons.min.css';document.head.append(link);});
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
  const value='~~~js\nconst answer = 42;\n~~~\n';await engine(page,value);await expect(page.locator('#experience-editor .hljs-keyword')).toContainText('const');expect(await source(page)).toBe(value);
  await page.getByRole('button',{name:/^Code language:/}).click();await page.getByRole('combobox',{name:'Code language',exact:true}).fill('javascript');await page.getByRole('combobox',{name:'Code language',exact:true}).press('Enter');expect(await source(page)).toContain('```javascript');await expect(page.getByRole('button',{name:'Copy code',exact:true})).toBeVisible();
});
test('switching to source and back without edits keeps rich undo history',async({page})=>{
  await field(page,'Original.\n');await page.locator('#experience-formatted p').click();await page.keyboard.press('End');await page.keyboard.type(' Added.');
  const text=await page.evaluate(()=>globalThis.field.value());expect(text).toContain('Added.');await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#experience-field').getByRole('button',{name:'Formatted',exact:true}).click();
  await page.keyboard.press('ControlOrMeta+z');expect(await page.evaluate(()=>globalThis.field.value())).toBe('Original.\n');
});
test('multi-file upload retry keeps completed files and does not move an unrelated caret',async({page})=>{
  await field(page,'One.\n\nTwo.\n');await page.locator('#experience-formatted .ProseMirror').focus();await page.evaluate(()=>{const range=document.createRange();range.selectNodeContents(document.querySelector('#experience-formatted p'));range.collapse(false);getSelection().removeAllRanges();getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));});await expect.poll(()=>page.evaluate(()=>globalThis.field.editor.context().text)).toBe('One.');
  await page.evaluate(()=>{globalThis.failUploadName='second.txt';const data=new DataTransfer();for(const name of ['first.txt','second.txt'])data.items.add(new File(['bytes'],name,{type:'text/plain'}));document.querySelector('#experience-formatted .ProseMirror').dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));});
  await expect(page.locator('#experience-field')).toContainText('Upload failed');await expect(page.locator('#experience-formatted a')).toHaveCount(1);await page.locator('#experience-formatted p').last().click();await page.evaluate(()=>{const range=document.createRange();range.selectNodeContents(document.querySelector('#experience-formatted .ProseMirror').lastElementChild);range.collapse(false);getSelection().removeAllRanges();getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));});await expect.poll(()=>page.evaluate(()=>globalThis.field.editor.context().text)).toBe('Two.');
  await page.evaluate(()=>globalThis.failUploadName=null);await page.locator('#experience-field').getByRole('button',{name:'Retry',exact:true}).click();await expect(page.locator('#experience-formatted a')).toHaveCount(2);expect(await page.evaluate(()=>globalThis.uploaded)).toEqual(['first.txt','second.txt']);
  await page.locator('#experience-formatted .ProseMirror').pressSequentially(' Kept caret.');await expect(page.locator('#experience-formatted p').last()).toContainText('Two. Kept caret.');await expect(page.locator('#experience-formatted p').first().locator('a')).toHaveCount(2);
});
test('shared controls remain keyboard accessible and fit narrow screens in every theme',async({page})=>{
  await page.setViewportSize({width:390,height:844});await field(page,'# Heading\n\n- [ ] Task\n\nParagraph.\n');
  for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=['timekeeper','solarized-light'].includes(theme)?'light':'dark';},theme);
    expect((await new AxeBuilder({page}).include('#experience-field').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations,theme).toEqual([]);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }
  await page.locator('#experience-field').screenshot({path:'test-results/editor-experience-mobile.png'});
  await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();
  await expect(page.locator('#experience-body')).toBeVisible();await expect(page.locator('#experience-field .markdown-source-tools')).toHaveCount(0);await expect(page.locator('#experience-field .cm-editor')).toHaveCount(0);
  await page.locator('#experience-field').screenshot({path:'test-results/editor-experience-source-mobile.png'});
});
test('rich undo and redo span a source edit, including a change of Markdown spelling',async({page})=>{
  await field(page,'Title\n=====\n\nOriginal.\n');const original=await page.evaluate(()=>globalThis.field.value());await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();
  const changed=original.replace('Original.','__Changed__.');await page.locator('#experience-body').fill(changed);await page.locator('#experience-field').getByRole('button',{name:'Formatted',exact:true}).click();expect(await page.evaluate(()=>globalThis.field.value())).toBe(changed);
  await page.keyboard.press('ControlOrMeta+z');expect(await page.evaluate(()=>globalThis.field.value())).toBe(original);await page.keyboard.press('ControlOrMeta+Shift+z');expect(await page.evaluate(()=>globalThis.field.value())).toBe(changed);
  await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#experience-body').fill(original);await page.locator('#experience-field').getByRole('button',{name:'Formatted',exact:true}).click();expect(await page.evaluate(value=>globalThis.field.matches(value),original)).toBe(true);
});
test('editing around authored break HTML never mistakes it for an empty editor paragraph',async({page})=>{
  const value='Before.\r\n\r\n<br />\r\n\r\nAfter.\r\n';await engine(page,value);await expect(page.locator('.markdown-source-fragment textarea')).toHaveValue('<br />');await page.locator('#experience-editor p').last().click();await page.keyboard.press('End');await page.keyboard.type(' Kept.');expect(await source(page)).toBe(value.replace('After.','After. Kept.'));
});
test('typed and pasted ordinary wikilinks become active without escaping their Markdown',async({page})=>{
  await engine(page,'');await page.locator('#experience-editor .ProseMirror').click();await page.keyboard.type('[[Notes/Design|design]]');await expect(page.locator('[data-wiki-reference="Notes/Design|design"]')).toHaveText('design');expect(await source(page)).toContain('[[Notes/Design|design]]');
  await page.keyboard.press('End');await page.evaluate(()=>{const data=new DataTransfer();data.setData('text/plain','![[journal/assets/pixel.png|32]]');document.querySelector('#experience-editor .ProseMirror').dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));});expect(await source(page)).toContain('![[journal/assets/pixel.png|32]]');
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
  await openEditorFixture(page);const diff=await page.evaluate(async()=>{const {revisionDiff}=await import('/web/revision-diff.js');return revisionDiff('Same.\n','Same.\r\n').hunks[0].rows;});expect(diff.map(row=>row.kind)).toEqual(['removed','added']);expect(diff[0].text).toContain('\\r\\n');
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

test('existing Table button supports keyboard structure edits without extra controls',async({page,browserName})=>{
  await field(page,'Before.\n\n| A | B |\n| --- | --- |\n| One | Two |\n\nAfter.\n');
  await page.locator('#experience-formatted td p').first().click();await page.keyboard.press('Escape');const button=page.locator('#experience-field [data-command=table]');
  for(let step=0;step<24&&!await button.evaluate(node=>document.activeElement===node);step++)await page.keyboard.press(browserName==='webkit'&&process.platform==='darwin'?'Alt+Tab':'Tab');
  await expect(button).toBeFocused();
  await expect(button).toHaveAttribute('aria-description',/Table keyboard controls/);
  expect((await new AxeBuilder({page}).include('#experience-field').analyze()).violations).toEqual([]);
  await button.press('ArrowDown');await expect(page.locator('#experience-formatted tr')).toHaveCount(3);await expect(button).toBeFocused();
  await button.press('Shift+ArrowUp');await expect(page.locator('#experience-formatted tr').last()).toContainText('One');
  await button.press('ArrowRight');await expect(page.locator('#experience-formatted tr').first().locator('th')).toHaveCount(3);
  await button.press('c');expect(await page.locator('#experience-formatted th').evaluateAll(cells=>cells.filter(cell=>getComputedStyle(cell).textAlign==='center').length)).toBe(1);
  await button.press('Backspace');await expect(page.locator('#experience-formatted tr')).toHaveCount(2);
  await button.press('Delete');await expect(page.locator('#experience-formatted tr').first().locator('th')).toHaveCount(2);
  await page.evaluate(()=>globalThis.field.setReadOnly(true));const before=await page.evaluate(()=>globalThis.field.value());
  await button.dispatchEvent('keydown',{key:'ArrowDown'});expect(await page.evaluate(()=>globalThis.field.value())).toBe(before);
  await page.evaluate(()=>globalThis.field.setReadOnly(false));await button.focus();await button.press('Shift+Delete');await expect(page.locator('#experience-formatted table')).toHaveCount(0);
  expect(await page.evaluate(()=>globalThis.field.value())).toContain('Before.');expect(await page.evaluate(()=>globalThis.field.value())).toContain('After.');
  await page.keyboard.press('ControlOrMeta+z');await expect(page.locator('#experience-formatted table')).toHaveCount(1);
  await expect(page.locator('.markdown-structured, .markdown-context-menu')).toHaveCount(0);
});

test('lists continue and indent with ordinary keyboard actions',async({page})=>{
  await field(page,'* Parent\n* Child\n');await page.locator('#experience-formatted li p').last().click();await page.keyboard.press('Tab');
  await expect(page.locator('#experience-formatted ul ul')).toHaveCount(1);await page.keyboard.press('Shift+Tab');await expect(page.locator('#experience-formatted ul ul')).toHaveCount(0);
  await appendEditorText(page.locator('#experience-formatted li p').last(),' continued');await page.keyboard.press('Enter');await page.keyboard.insertText('Next item');await expect(page.locator('#experience-formatted li')).toHaveCount(3);await expect(page.locator('#experience-formatted li').last()).toContainText('Next item');
});

test('table header boundaries remain valid during keyboard operations',async({page})=>{
  const value='| A | B |\n| --- | --- |\n| One | Two |\n';await field(page,value);await page.locator('#experience-formatted th p').first().click();await page.keyboard.press('Escape');
  const button=page.locator('#experience-field [data-command=table]');await button.focus();await button.press('Backspace');await button.press('ArrowUp');await button.press('Shift+ArrowDown');
  await expect(page.locator('#experience-formatted tr')).toHaveCount(2);await expect(page.locator('#experience-formatted th').first()).toHaveText('A');expect(await page.evaluate(()=>globalThis.field.value())).toBe(value);
});


test('completion choices keep readable rows and scroll to keyboard selection on desktop and phones',async({page})=>{
  for(const width of [1440,390]){
    await page.setViewportSize({width,height:900});await field(page,'');const rich=page.locator('#experience-formatted .ProseMirror');await rich.click();await page.keyboard.type(':a');
    const list=page.locator('#experience-field .markdown-suggestions'),choices=list.getByRole('option');await expect(choices).toHaveCount(12);
    const sizes=await choices.evaluateAll(nodes=>nodes.map(node=>{const style=getComputedStyle(node);return {height:node.getBoundingClientRect().height,natural:parseFloat(style.lineHeight)+parseFloat(style.paddingTop)+parseFloat(style.paddingBottom)};}));
    for(const size of sizes)expect(size.height,'A suggestion must show its complete line and padding').toBeGreaterThanOrEqual(size.natural-1);
    expect(await list.evaluate(node=>node.scrollHeight>node.clientHeight)).toBe(true);
    await page.keyboard.press('ArrowDown');await expect(choices.first()).toBeFocused();for(let index=1;index<12;index++)await page.keyboard.press('ArrowDown');await expect(choices.last()).toBeFocused();
    const visible=await choices.last().evaluate(node=>{const row=node.getBoundingClientRect(),parent=node.parentElement.getBoundingClientRect();return row.top>=parent.top&&row.bottom<=parent.bottom;});expect(visible).toBe(true);
    const label=await choices.last().textContent(),shortcode=label.match(/:[a-z0-9_+-]+:/)[0];await page.keyboard.press('Enter');await expect(list).toBeHidden();expect(await page.evaluate(()=>globalThis.field.value())).toContain(shortcode);
    await appendEditorText(rich.locator('p'),' :a');await expect(list).toBeVisible();await page.keyboard.press('ArrowDown');await page.keyboard.press('Escape');await expect(list).toBeHidden();await expect(rich).toBeFocused();
    await appendEditorText(rich.locator('p'),' :air');await expect(list).toBeVisible();expect(await choices.count()).toBeLessThan(12);
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=['timekeeper','solarized-light'].includes(theme)?'light':'dark';},theme);
      expect((await new AxeBuilder({page}).include('#experience-field').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations,theme).toEqual([]);
    }
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await captureScreenshot(page,{path:`test-results/completion-readable-${width}.png`,fullPage:true});
  }
});
