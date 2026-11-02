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

async function field(page,value,formatted=true){
  await page.goto('/web/markdown-lab.html');
  await page.evaluate(async({value,formatted})=>{
    const {MarkdownField}=await import('/web/markdown-field.js');const root=document.createElement('section');root.id='experience-field';document.body.append(root);
    globalThis.field=new MarkdownField(root,()=>{},()=>{},'experience',async file=>{if(globalThis.failUpload||globalThis.failUploadName===file.name)throw new Error('Upload failed');(globalThis.uploaded??=[]).push(file.name);return 'assets/'+file.name;});
    globalThis.field.setValue(value);globalThis.field.setReadOnly(false);if(formatted)await globalThis.field.showFormatted();
  },{value,formatted});
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
  await page.getByRole('textbox',{name:'Code language',exact:true}).fill('javascript');await page.getByRole('textbox',{name:'Code language',exact:true}).press('Tab');expect(await source(page)).toContain('```javascript');await expect(page.getByRole('button',{name:'Copy code',exact:true})).toBeVisible();
});
test('switching to source and back without edits keeps rich undo history',async({page})=>{
  await field(page,'Original.\n');await page.locator('#experience-formatted p').click();await page.keyboard.press('End');await page.keyboard.type(' Added.');
  const text=await page.evaluate(()=>globalThis.field.value());expect(text).toContain('Added.');await page.locator('#experience-field').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#experience-field').getByRole('button',{name:'Formatted',exact:true}).click();
  await page.locator('#experience-field').getByRole('button',{name:'Undo',exact:true}).click();expect(await page.evaluate(()=>globalThis.field.value())).toBe('Original.\n');
});
test('multi-file upload retry keeps completed files and does not move an unrelated caret',async({page})=>{
  await field(page,'One.\n\nTwo.\n');await page.locator('#experience-formatted p').first().click();await page.keyboard.press('End');
  await page.evaluate(()=>{globalThis.failUploadName='second.txt';const data=new DataTransfer();for(const name of ['first.txt','second.txt'])data.items.add(new File(['bytes'],name,{type:'text/plain'}));document.querySelector('#experience-formatted .ProseMirror').dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));});
  await expect(page.locator('#experience-field')).toContainText('Upload failed');await expect(page.locator('#experience-formatted a')).toHaveCount(1);await page.locator('#experience-formatted p').last().click();await page.keyboard.press('End');
  await page.evaluate(()=>globalThis.failUploadName=null);await page.locator('#experience-field').getByRole('button',{name:'Retry',exact:true}).click();await expect(page.locator('#experience-formatted a')).toHaveCount(2);expect(await page.evaluate(()=>globalThis.uploaded)).toEqual(['first.txt','second.txt']);
  await page.locator('#experience-formatted .ProseMirror').pressSequentially(' Kept caret.');await expect(page.locator('#experience-formatted p').last()).toContainText('Two. Kept caret.');await expect(page.locator('#experience-formatted p').first().locator('a')).toHaveCount(2);
});
test('source undo restores the exact original line ending after joining mixed-EOL lines',async({page})=>{
  const value='First.\r\nSecond.\nThird.\r\n';await field(page,value,false);await page.locator('#experience-field').getByRole('button',{name:'Source editor tools',exact:true}).click();
  await page.evaluate(()=>globalThis.field.codeEditor.select(14));await page.keyboard.press('Delete');expect(await page.evaluate(()=>globalThis.field.value())).not.toBe(value);await page.keyboard.press('ControlOrMeta+z');expect(await page.evaluate(()=>globalThis.field.value())).toBe(value);
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
