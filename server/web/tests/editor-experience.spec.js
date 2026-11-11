import {openEditorFixture} from './editor-fixture.js';
import {appendEditorText} from './editor-interactions.js';
import {captureScreenshot} from './screenshots.js';
import AxeBuilder from '@axe-core/playwright';
import {test,expect} from '@playwright/test';

async function engine(page,value,options={}){
  await openEditorFixture(page);
  await page.evaluate(async({value,options})=>{
    const {createMarkdownEditor}=await import('/web/assets/milkdown/milkdown.js');
    if(options.tags){globalThis.searchedTags=[];options.resources={searchTag:tag=>globalThis.searchedTags.push(tag)};delete options.tags;}
    const root=document.createElement('div');root.id='experience-editor';document.body.append(root);
    globalThis.experience=await createMarkdownEditor({root,value,label:'Experience body',taskLabel:text=>text,escape:()=>{},...options});
  },{value,options});
  await expect(page.locator('#experience-editor .ProseMirror')).toBeVisible();
}
const source=page=>page.evaluate(()=>globalThis.experience.markdown());

test('extended inline styles render and retain exact source, nested formatting and literal code',async({page})=>{
  const value='# ==**Focus**==\r\n\r\n==Very **important** words==: H~2~O, X^2^ and ~~obsolete~~. **==Reverse==**\r\n\r\n`==literal== H~2~O X^2^`\r\n';
  await field(page,value);const rich=page.locator('#experience-formatted .ProseMirror');
  await expect(rich.locator('mark')).toHaveText(['Focus','Very important words','Reverse']);await expect(rich.locator('mark strong')).toHaveText(['Focus','important','Reverse']);
  await expect(rich.locator('sub')).toHaveText('2');await expect(rich.locator('sup')).toHaveText('2');await expect(rich.locator('del')).toHaveText('obsolete');
  await expect(rich.locator('code')).toHaveText('==literal== H~2~O X^2^');expect(await page.evaluate(()=>globalThis.field.value())).toBe(value);
  await appendEditorText(rich.locator('p').first(),' Edited.');
  const changed=await page.evaluate(()=>globalThis.field.value());expect(changed).toContain('==Very **important** words==');expect(changed).toContain('H~2~O');expect(changed).toContain('X^2^');
  const host=page.locator('#experience-field');await host.getByRole('button',{name:'Markdown source',exact:true}).click();await host.getByRole('button',{name:'Formatted',exact:true}).click();
  await expect(rich.locator('sub')).toHaveText('2');await expect(rich.locator('sup')).toHaveText('2');expect(await page.evaluate(()=>globalThis.field.value())).toBe(changed);
  for(const theme of ['timekeeper','timekeeper-dark']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=(theme==='timekeeper')?'light':'dark';},theme);
    const mark=rich.locator('mark').first();await expect(mark).toBeVisible();
    expect(await mark.evaluate(node=>getComputedStyle(node).backgroundColor)).not.toBe('rgba(0, 0, 0, 0)');
  }
});

test('typed inline styles and their toolbar controls stay editable with undo and read-only guards',async({page})=>{
  await field(page,'');const rich=page.locator('#experience-formatted .ProseMirror');await rich.click();await page.keyboard.type('H~2~O X^2^ ==Øving== ~~old~~');
  await expect(rich.locator('sub')).toHaveText('2');await expect(rich.locator('sup')).toHaveText('2');await expect(rich.locator('mark')).toHaveText('Øving');await expect(rich.locator('del')).toHaveText('old');
  expect((await page.evaluate(()=>globalThis.field.value())).trim()).toBe('H~2~O X^2^ ==Øving== ~~old~~');
  await field(page,'water');const host=page.locator('#experience-field');await page.locator('#experience-formatted .ProseMirror').focus();await page.keyboard.press('ControlOrMeta+a');
  await host.getByRole('button',{name:'Highlight',exact:true}).click();await expect(host.locator('mark')).toHaveText('water');
  await page.keyboard.press('ControlOrMeta+z');await expect(host.locator('mark')).toHaveCount(0);await page.keyboard.press('ControlOrMeta+Shift+z');await expect(host.locator('mark')).toHaveText('water');
  await page.evaluate(()=>globalThis.field.setReadOnly(true));await expect(host.locator('[data-command=highlight]')).toBeDisabled();
});

test('prose hashtags use accent links without changing Markdown or literal code and link content',async({page})=>{
  const value='# Tag examples\n\nA #Open**Shift** deployment, #Cafe\u0301 and #work/cloud.\n\n`#literal` [#linked](https://example.org/#destination) [[#heading]]\n\n~~~text\n#code\n~~~\n';
  await engine(page,value,{tags:true});const tags=page.locator('#experience-editor [data-hashtag]');await expect(tags).toHaveCount(4);expect(await tags.evaluateAll(nodes=>[...new Set(nodes.map(node=>node.dataset.hashtag))])).toEqual(['#openshift','#café','#work/cloud']);expect(await source(page)).toBe(value);
  await tags.first().click();expect(await page.evaluate(()=>globalThis.searchedTags)).toEqual(['#openshift']);
  const café=page.locator('[data-hashtag="#café"]');await café.focus();await café.press('Enter');expect(await page.evaluate(()=>globalThis.searchedTags)).toEqual(['#openshift','#café']);expect(await source(page)).toBe(value);
  for(const theme of ['timekeeper','timekeeper-dark']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=(theme==='timekeeper')?'light':'dark';},theme);
    expect(await café.evaluate(node=>{const probe=document.createElement('span');probe.style.color='var(--accent)';node.after(probe);const expected=getComputedStyle(probe).color;probe.remove();return getComputedStyle(node).color===expected;})).toBe(true);
    expect((await new AxeBuilder({page}).include('#experience-editor .markdown-hashtag').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations,theme).toEqual([]);
  }
  await page.setViewportSize({width:390,height:844});await captureScreenshot(page,{path:'test-results/hashtag-accent-phone.png',fullPage:true});
  await page.evaluate(()=>globalThis.experience.setReadOnly(true));await tags.last().focus();await tags.last().press('Enter');expect(await source(page)).toBe(value);expect(await page.evaluate(()=>globalThis.searchedTags.at(-1))).toBe('#work/cloud');
});

test('hashtag presentation follows typing and undo while source remains portable text',async({page})=>{
  await engine(page,'An ordinary note.\n',{tags:true});await appendEditorText(page.locator('#experience-editor p'),' #NewTag');await expect(page.locator('[data-hashtag="#newtag"]')).toHaveText('#NewTag');
  expect(await source(page)).toContain('#NewTag');await page.keyboard.press('ControlOrMeta+z');await expect(page.locator('[data-hashtag]')).toHaveCount(0);
});

test('client annotations share hashtag presentation while code, links and saved Markdown stay literal',async({page})=>{
  const value='A #work item for @Client_Name and @client:Øving.\n\n`@Code` [@Link](https://example.invalid/) email@example.invalid\n\n```md\n@Example\n```\n';await engine(page,value,{tags:true});
  const mentions=page.locator('#experience-editor .markdown-client-mention');await expect(mentions).toHaveText(['@Client_Name','@client:Øving.']);expect(await source(page)).toBe(value);
  for(const theme of ['timekeeper','timekeeper-dark','newspaper']){await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper-dark'?'dark':'light';},theme);expect(await mentions.first().evaluate(node=>{const tag=document.querySelector('[data-hashtag]');return getComputedStyle(node).color===getComputedStyle(tag).color&&getComputedStyle(node).textDecorationLine===getComputedStyle(tag).textDecorationLine;})).toBe(true);}
  await page.evaluate(()=>globalThis.experience.setReadOnly(true));await expect(mentions).toHaveCount(2);expect(await source(page)).toBe(value);
});

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
  const value='Before.\r\n\r\n<script>window.bad=true</script>\r\n\r\n%%unknown comment%%\r\n\r\nAfter.\r\n';
  await engine(page,value);expect(await source(page)).toBe(value);expect(await page.evaluate(()=>globalThis.bad)).toBeUndefined();
  await expect(page.locator('.markdown-source-fragment textarea')).toHaveCount(2);
  await page.locator('#experience-editor p').filter({hasText:'After.'}).click();await page.keyboard.press('End');await page.keyboard.type(' Edited.');
  expect(await source(page)).toBe(value.replace('After.','After. Edited.'));
  await page.locator('.markdown-source-fragment textarea').last().fill('%%changed comment%%');expect(await source(page)).toContain('%%changed comment%%');
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
async function pasteText(page,text,{html='',plain=false}={}){
  await page.evaluate(({text,html,plain})=>{
    const root=document.querySelector('#experience-editor .ProseMirror'),data=new DataTransfer();data.setData('text/plain',text);if(html)data.setData('text/html',html);
    if(plain)root.dispatchEvent(new KeyboardEvent('keydown',{key:'v',ctrlKey:true,shiftKey:true,bubbles:true,cancelable:true}));
    root.dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));
  },{text,html,plain});
}
test('pasted URLs retain their spelling and remain links through rich edits and source replacement',async({page})=>{
  const url='https://example.org/a_b/one_(two)?name[0]=value&next=a_b#part_one';
  await engine(page,'Before.\n');await page.locator('#experience-editor p').click();await page.keyboard.press('End');await page.keyboard.insertText(' ');
  await pasteText(page,url,{html:'<a href="'+url+'">'+url+'</a>'});
  let markdown=await source(page);expect(markdown).toContain(url);
  await expect(page.locator('#experience-editor a')).toHaveAttribute('href',url);await expect(page.locator('#experience-editor a')).toHaveText(url);
  expect(markdown).toContain('<'+url+'>');expect(markdown).not.toContain('\\_');
  await page.keyboard.insertText(' More.');markdown=await source(page);expect(markdown).toContain('<'+url+'>');await expect(page.locator('#experience-editor a')).toHaveText(url);
  expect(await page.evaluate(value=>globalThis.experience.replaceMarkdown(value),markdown)).toBe(true);await appendEditorText(page.locator('#experience-editor p'),' Again.');expect(await source(page)).toContain('<'+url+'>');
});
test('authored autolinks survive edits beside explicit Markdown links',async({page})=>{
  const url='https://example.org/a_b?key=one&next=two',resource='[Named link]('+url+')';
  await engine(page,'Before <'+url+'> and '+resource+'.\n');await appendEditorText(page.locator('#experience-editor p'),' More.');
  const markdown=await source(page);expect(markdown).toContain('<'+url+'>');expect(markdown).toContain('[Named link](');
  await expect(page.locator('#experience-editor a')).toHaveCount(2);expect(await page.evaluate(value=>globalThis.experience.replaceMarkdown(value),markdown)).toBe(true);
  await expect(page.getByRole('link',{name:'Named link',exact:true})).toHaveAttribute('href',url);
  await appendEditorText(page.locator('#experience-editor p'),' Again.');expect(await source(page)).toContain('[Named link](');
});
test('plain clipboard URLs become links in table cells without changing table structure',async({page})=>{
  const url='https://example.org/a_b?name[0]=one&next=two';await engine(page,'| A | B |\n| --- | --- |\n| One | Two |\n');
  const cell=page.locator('#experience-editor td p').first();await appendEditorText(cell,' ');await pasteText(page,url);
  await expect(cell.locator('a')).toHaveText(url);const markdown=await source(page);expect(markdown).toContain('<'+url+'>');
  expect(await page.evaluate(value=>globalThis.experience.replaceMarkdown(value),markdown)).toBe(true);await expect(page.locator('#experience-editor tr')).toHaveCount(2);await expect(page.locator('#experience-editor td')).toHaveCount(2);
});
test('code, plain paste, unsafe schemes and read-only notes do not create URL links',async({page})=>{
  const url='https://example.org/a_b?key=one&next=two';await engine(page,'~~~text\nliteral\n~~~\n');
  await page.locator('#experience-editor pre code').click();await page.keyboard.press('End');await pasteText(page,url);await expect(page.locator('#experience-editor pre')).toContainText(url);await expect(page.locator('#experience-editor a')).toHaveCount(0);
  expect(await page.evaluate(()=>globalThis.experience.replaceMarkdown('`literal`\n'))).toBe(true);await page.locator('#experience-editor code').evaluate(node=>{node.closest('.ProseMirror').focus();const range=document.createRange();range.setStart(node.firstChild,3);range.collapse(true);getSelection().removeAllRanges();getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));});await pasteText(page,url);await expect(page.locator('#experience-editor code')).toContainText(url);await expect(page.locator('#experience-editor a')).toHaveCount(0);
  expect(await page.evaluate(()=>globalThis.experience.replaceMarkdown('Before.\n'))).toBe(true);await appendEditorText(page.locator('#experience-editor p'),' ');await pasteText(page,url,{plain:true});await expect(page.locator('#experience-editor a')).toHaveCount(0);await expect(page.locator('#experience-editor p')).toContainText(url);
  await pasteText(page,'javascript:alert(1)');await expect(page.locator('#experience-editor a')).toHaveCount(0);
  const before=await source(page);await page.evaluate(()=>globalThis.experience.setReadOnly(true));await pasteText(page,url);expect(await source(page)).toBe(before);
});

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
  for(const theme of ['timekeeper','timekeeper-dark']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);
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
    expect(await list.evaluate(node=>node.scrollHeight>node.clientHeight)).toBe(true);await captureScreenshot(page,{path:`test-results/completion-many-${width}.png`,fullPage:true});
    await page.keyboard.press('ArrowDown');await expect(choices.first()).toBeFocused();for(let index=1;index<12;index++)await page.keyboard.press('ArrowDown');await expect(choices.last()).toBeFocused();
    const visible=await choices.last().evaluate(node=>{const row=node.getBoundingClientRect(),parent=node.parentElement.getBoundingClientRect();return row.top>=parent.top&&row.bottom<=parent.bottom;});expect(visible).toBe(true);
    const label=await choices.last().textContent(),shortcode=label.match(/:[a-z0-9_+-]+:/)[0];await page.keyboard.press('Enter');await expect(list).toBeHidden();expect(await page.evaluate(()=>globalThis.field.value())).toContain(shortcode);
    await appendEditorText(rich.locator('p'),' :a');await expect(list).toBeVisible();await page.keyboard.press('ArrowDown');await page.keyboard.press('Escape');await expect(list).toBeHidden();await expect(rich).toBeFocused();
    await appendEditorText(rich.locator('p'),' :airplane');await expect(choices).toHaveCount(2);await expect(list).toBeVisible();
    for(const theme of ['timekeeper','timekeeper-dark']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=(theme==='timekeeper')?'light':'dark';},theme);
      expect((await new AxeBuilder({page}).include('#experience-field').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations,theme).toEqual([]);
    }
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await captureScreenshot(page,{path:`test-results/completion-readable-${width}.png`,fullPage:true});
  }
});


test('internal link assistance pairs brackets and completes a narrowed document in both modes',async({page})=>{
  const catalog=[{path:'Notes/Design.md',title:'Design decisions',kind:'MARKDOWN'},{path:'Notes/Diary.md',kind:'MARKDOWN'},{path:'Notes/Other.md',kind:'MARKDOWN'}];
  for(const formatted of [true,false]){
    await field(page,'',formatted,catalog);const edit=page.locator(formatted?'#experience-formatted .ProseMirror':'#experience-body');await edit.click();
    await page.keyboard.type('[');expect(await edit.evaluate(node=>node instanceof HTMLTextAreaElement?node.value:node.textContent)).toBe('[]');
    await page.keyboard.type('[');expect(await edit.evaluate(node=>node instanceof HTMLTextAreaElement?node.value:node.textContent)).toBe('[[]]');
    const list=page.locator('#experience-field .markdown-suggestions'),options=list.getByRole('option');await expect(options).toHaveCount(3);
    await page.keyboard.type('Desx');await expect(options).toHaveCount(0);await page.keyboard.press('Backspace');await expect(options).toHaveCount(1);await expect(options.first()).toContainText('Notes/Design.md');
    await page.keyboard.press('ArrowDown');await expect(options.first()).toBeFocused();await page.keyboard.press('Enter');await expect(list).toBeHidden();
    const completed=await page.evaluate(()=>globalThis.field.value());expect(completed.trim()).toBe('[[Notes/Design]]');
    if(formatted)await expect(edit.locator('[data-wiki-reference="Notes/Design"]')).toHaveCount(1);
    await page.keyboard.press('ControlOrMeta+z');expect((await page.evaluate(()=>globalThis.field.value())).trim()).not.toBe('[[Notes/Design]]');await page.keyboard.press('ControlOrMeta+Shift+z');expect(await page.evaluate(()=>globalThis.field.value())).toBe(completed);
    await captureScreenshot(page,{path:`test-results/internal-completion-${formatted?'formatted':'source'}.png`,fullPage:true});
  }
});

test('generated bracket closers skip typed closers and empty pairs delete together',async({page})=>{
  for(const formatted of [true,false]){
    await field(page,'',formatted);const edit=page.locator(formatted?'#experience-formatted .ProseMirror':'#experience-body');await edit.click();await page.keyboard.type('[[');
    await page.keyboard.press('Backspace');expect(await edit.evaluate(node=>node instanceof HTMLTextAreaElement?node.value:node.textContent)).toBe('[]');await page.keyboard.press('Backspace');expect((await page.evaluate(()=>globalThis.field.value())).trim()).toBe('');
    await page.keyboard.type('[[Notes/Design]]');expect((await page.evaluate(()=>globalThis.field.value())).trim()).toBe('[[Notes/Design]]');await page.keyboard.type(' tail');expect((await page.evaluate(()=>globalThis.field.value())).trim()).toBe('[[Notes/Design]] tail');
  }
});

test('generated wikilink closers can be accepted by moving past them without escaping the link',async({page})=>{
  await field(page,'',true,[{path:'Notes/Design.md',kind:'MARKDOWN'}]);
  const rich=page.locator('#experience-formatted .ProseMirror');await rich.click();
  await page.keyboard.type('[[New document');await expect(rich).toHaveText('[[New document]]');
  expect((await page.evaluate(()=>globalThis.field.value())).trim()).toBe('[[New document]]');
  await page.keyboard.press('ArrowRight');await page.keyboard.press('ArrowRight');
  await expect(rich.locator('[data-wiki-reference="New document"]')).toHaveText('New document');
  expect((await page.evaluate(()=>globalThis.field.value())).trim()).toBe('[[New document]]');
  await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();
  await expect(page.locator('#experience-body')).toHaveValue('[[New document]]\n');
});

test('switching modes accepts generated wikilink closers without changing their Markdown',async({page})=>{
  await field(page,'',true);const host=page.locator('#experience-field'),rich=page.locator('#experience-formatted .ProseMirror');
  await rich.click();await page.keyboard.type('[[Øving');
  expect((await page.evaluate(()=>globalThis.field.value())).trim()).toBe('[[Øving]]');
  await host.getByRole('button',{name:'Markdown source',exact:true}).click();await expect(page.locator('#experience-body')).toHaveValue('[[Øving]]\n');
  await host.getByRole('button',{name:'Formatted',exact:true}).click();await expect(rich.locator('[data-wiki-reference="Øving"]')).toHaveText('Øving');
});


test('bracket assistance leaves code, escaped links and read-only documents literal',async({page})=>{
  const catalog=[{path:'Notes/Design.md',kind:'MARKDOWN'}];
  for(const formatted of [true,false]){
    for(const code of ['```text\nliteral\n```\n','`literal`\n']){
      await field(page,code,formatted,catalog);const edit=page.locator(formatted?'#experience-formatted .ProseMirror':'#experience-body');
      if(formatted)await edit.locator('code').evaluate(node=>{node.closest('[contenteditable=true]').focus();const text=document.createTreeWalker(node,NodeFilter.SHOW_TEXT).nextNode(),range=document.createRange();range.setStart(text,3);range.collapse(true);getSelection().removeAllRanges();getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));});
      else{await edit.focus();await edit.evaluate(node=>{const at=node.value.indexOf('literal')+3;node.setSelectionRange(at,at);});}
      const literal=formatted?edit.locator('code'):edit;await page.keyboard.type('[');expect(await literal.evaluate(node=>node instanceof HTMLTextAreaElement?node.value:node.textContent)).toContain('lit[eral');await page.keyboard.type('[');expect(await literal.evaluate(node=>node instanceof HTMLTextAreaElement?node.value:node.textContent)).toContain('lit[[eral');
      await expect(page.locator('#experience-field .markdown-suggestions')).toBeHidden();await page.keyboard.type('Design]]');await expect(page.locator('#experience-field [data-wiki-reference]')).toHaveCount(0);expect(await page.evaluate(()=>globalThis.field.value())).toContain('lit[[Design]]eral');
    }
    await field(page,'',formatted,catalog);const edit=page.locator(formatted?'#experience-formatted .ProseMirror':'#experience-body');await edit.click();await page.keyboard.type('\\[[Design]]');
    await expect(page.locator('#experience-field .markdown-suggestions')).toBeHidden();await expect(page.locator('#experience-field [data-wiki-reference]')).toHaveCount(0);
    if(!formatted)await expect(edit).toHaveValue('\\[[Design]]');
    await page.evaluate(()=>globalThis.field.setReadOnly(true));const before=await page.evaluate(()=>globalThis.field.value());await edit.focus();await page.keyboard.type('[[');expect(await page.evaluate(()=>globalThis.field.value())).toBe(before);
  }
});

test('document assistance rejects delayed old results and clears them when its document changes',async({page})=>{
  const catalog=[{path:'Notes/Design.md',kind:'MARKDOWN'},{path:'Notes/Diary.md',kind:'MARKDOWN'}];
  for(const formatted of [true,false]){
    await field(page,'',formatted,catalog);await page.evaluate(catalog=>{globalThis.field.resources.complete=query=>query?Promise.resolve(catalog.filter(entry=>entry.path.includes(query))):new Promise(resolve=>globalThis.finishCompletion=()=>resolve(catalog));},catalog);
    const edit=page.locator(formatted?'#experience-formatted .ProseMirror':'#experience-body'),list=page.locator('#experience-field .markdown-suggestions');await edit.click();await page.keyboard.type('[[');await page.waitForFunction(()=>!!globalThis.finishCompletion);
    await page.keyboard.type('Des');await expect(list.getByRole('option')).toHaveCount(1);await expect(list).toContainText('Notes/Design.md');await page.evaluate(()=>globalThis.finishCompletion());await expect(list.getByRole('option')).toHaveCount(1);
    await page.evaluate(async formatted=>{globalThis.finishCompletion=null;globalThis.field.setValue('');if(formatted)await globalThis.field.showFormatted();},formatted);await edit.click();await page.keyboard.type('[[');await page.waitForFunction(()=>!!globalThis.finishCompletion);
    await page.evaluate(()=>{globalThis.field.setValue('A different private document.');globalThis.finishCompletion();});await expect(list).toBeHidden();await expect(list.getByRole('option')).toHaveCount(0);expect(await page.evaluate(()=>globalThis.field.value())).toBe('A different private document.');
  }
});


test('internal-link choices fit phones and remain accessible in every theme in both modes',async({page})=>{
  const catalog=Array.from({length:20},(_,index)=>({path:'Notes/Document-'+index+'.md',title:'Document '+index,kind:'MARKDOWN'}));
  await page.setViewportSize({width:390,height:844});
  for(const formatted of [true,false]){
    await field(page,'',formatted,catalog);const edit=page.locator(formatted?'#experience-formatted .ProseMirror':'#experience-body');await edit.click();await page.keyboard.type('[[');
    const list=page.locator('#experience-field .markdown-suggestions');await expect(list.getByRole('option')).toHaveCount(20);
    const fits=await list.evaluate(node=>{const rect=node.getBoundingClientRect();return rect.left>=7&&rect.right<=innerWidth-7&&rect.top>=7&&rect.bottom<=innerHeight-7;});expect(fits).toBe(true);
    for(const theme of ['timekeeper','timekeeper-dark']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=(theme==='timekeeper')?'light':'dark';},theme);
      expect((await new AxeBuilder({page}).include('#experience-field').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations,theme).toEqual([]);
    }
    await captureScreenshot(page,{path:`test-results/internal-choices-phone-${formatted?'formatted':'source'}.png`,fullPage:true});
    await page.keyboard.press('Escape');await expect(list).toBeHidden();await expect(edit).toBeFocused();
  }
});

test('bracket pairing wraps a selection without losing its text and composition stays unpaired',async({page})=>{
  for(const formatted of [true,false]){
    await field(page,'Keep selected text.',formatted);const edit=page.locator(formatted?'#experience-formatted .ProseMirror':'#experience-body');await edit.focus();
    await edit.evaluate(node=>{if(node instanceof HTMLTextAreaElement)node.setSelectionRange(5,13);else{const text=node.querySelector('p').firstChild,range=document.createRange();range.setStart(text,5);range.setEnd(text,13);getSelection().removeAllRanges();getSelection().addRange(range);document.dispatchEvent(new Event('selectionchange'));}});
    await page.keyboard.type('[');expect(await edit.evaluate(node=>node instanceof HTMLTextAreaElement?node.value:node.textContent)).toBe('Keep [selected] text.');
    await field(page,'',formatted,[{path:'Notes/Design.md',kind:'MARKDOWN'}]);const composing=page.locator(formatted?'#experience-formatted .ProseMirror':'#experience-body');await composing.click();await composing.dispatchEvent('compositionstart',{data:''});await page.keyboard.type('[[');
    await expect(page.locator('#experience-field .markdown-suggestions')).toBeHidden();expect(await composing.evaluate(node=>node instanceof HTMLTextAreaElement?node.value:node.textContent)).toBe('[[');await composing.dispatchEvent('compositionend',{data:'[['});
  }
});


test('source bracket tracking cannot skip unrelated text after a formatted edit',async({page})=>{
  await field(page,'',false);const source=page.locator('#experience-body');await source.click();await page.keyboard.type('[');await expect(source).toHaveValue('[]');
  const host=page.locator('#experience-field');await host.getByRole('button',{name:'Formatted',exact:true}).click();const rich=page.locator('#experience-formatted .ProseMirror');await rich.focus();await page.keyboard.press('ControlOrMeta+a');await page.keyboard.type('AB');
  await host.getByRole('button',{name:'Markdown source',exact:true}).click();await source.evaluate(node=>node.setSelectionRange(1,1));await page.keyboard.type(']');expect(await page.evaluate(()=>globalThis.field.value())).toContain('A]B');
});

test('native formatted input preserves active wikilinks without Markdown escapes',async({page})=>{
  await engine(page,'Before.\n');
  await page.locator('#experience-editor p').evaluate(node=>{
    node.textContent='Before. [[Notes/New note|New note]] after native input.';
    const range=document.createRange();range.selectNodeContents(node);range.collapse(false);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);
    node.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'[[Notes/New note|New note]]'}));
  });
  await expect.poll(()=>source(page)).toContain('[[Notes/New note|New note]]');
  await expect(page.locator('[data-wiki-reference="Notes/New note|New note"]')).toHaveText('New note');
  await page.locator('#experience-editor .ProseMirror').focus();await page.keyboard.press('ControlOrMeta+z');expect(await source(page)).toBe('Before.\n');await page.keyboard.press('ControlOrMeta+Shift+z');await expect(page.locator('[data-wiki-reference="Notes/New note|New note"]')).toHaveCount(1);
});

test('native wikilink conversion preserves authored escapes across edits and source replacement',async({page})=>{
  const value='Authored \\[[Keep literal]] text.\n\nChange here.\n';await engine(page,value);
  await appendEditorText(page.locator('#experience-editor p').last(),' More.');await expect(page.locator('[data-wiki-reference]')).toHaveCount(0);expect(await source(page)).toContain('\\[[Keep literal]]');
  await page.evaluate(()=>globalThis.experience.replaceMarkdown('Replaced \\[[Still literal]].\n\nEditable paragraph.\n'));await expect(page.locator('[data-wiki-reference]')).toHaveCount(0);
  await appendEditorText(page.locator('#experience-editor p').last(),' More.');expect(await source(page)).toContain('\\[[Still literal]]');
});

test('native composition creates an active wikilink after composition ends',async({page})=>{
  await engine(page,'');await page.locator('#experience-editor .ProseMirror').evaluate(node=>{
    node.focus();node.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}));const paragraph=node.querySelector('p');paragraph.textContent='[[Øving]]';
    const range=document.createRange();range.selectNodeContents(paragraph);range.collapse(false);getSelection().removeAllRanges();getSelection().addRange(range);
    paragraph.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertCompositionText',data:'[[Øving]]',isComposing:true}));node.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true,data:'[[Øving]]'}));
  });
  await expect(page.locator('[data-wiki-reference="Øving"]')).toHaveText('Øving');expect(await source(page)).toContain('[[Øving]]');
  await page.locator('#experience-editor .ProseMirror').focus();await page.keyboard.press('ControlOrMeta+z');expect(await source(page)).toBe('');await page.keyboard.press('ControlOrMeta+Shift+z');await expect(page.locator('[data-wiki-reference="Øving"]')).toHaveCount(1);
});
