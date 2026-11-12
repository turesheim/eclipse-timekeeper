import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {openEditorFixture} from './editor-fixture.js';
import {appendEditorText,noteActions} from './editor-interactions.js';

async function open(page,value){
  await openEditorFixture(page);
  await page.evaluate(async value=>{
    const {MarkdownField}=await import('/web/markdown-field.js'),root=document.createElement('section');root.id='presentation-editor';document.body.append(root);
    globalThis.followedLinks=[];globalThis.openedLinks=[];window.open=(...args)=>{globalThis.openedLinks.push(args);return null;};
    Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async value=>{globalThis.copiedCode=value;}}});
    globalThis.presentation=new MarkdownField(root,()=>{},()=>{},'presentation',undefined,{
      allowsLink:href=>typeof href==='string'&&href.endsWith('.md'),openLink:async href=>{globalThis.followedLinks.push(href);},
      resolveWiki:async()=>({path:'Notes/Target.md'}),openWiki:async reference=>{globalThis.followedLinks.push(reference);},complete:async()=>[],revisions:async()=>[]
    });
    globalThis.presentation.setReadOnly(false);globalThis.presentation.setValue(value);await globalThis.presentation.showFormatted(false);
  },value);
  const root=page.locator('#presentation-editor');await expect(root.locator('.ProseMirror')).toBeVisible();return root;
}

test('code uses highlight.js in a compact badge, preserving source, copy and language history',async({page})=>{
  const shell='# Finalize the namespace\r\ncurl -H "Content-Type: application/json" http://localhost/$NAMESPACE/finalize';
  const yaml='apiVersion: v1\nkind: Pod\nreplicas: 3 # desired pods\nenabled: true\nmetadata:\n  name: dnsutils\nspec:\n  image: busybox:1.7',original='---\r\nprivate: keep\r\n---\r\n\r\n~~~yaml\r\n'+yaml.replaceAll('\n','\r\n')+'\r\n~~~\r\n\r\n```javascript\r\nconst ready = true;\r\nconsole.log(ready);\r\n```\r\n\r\n```shell\r\n'+shell+'\r\n```\r\n';
  const root=await open(page,original),block=root.locator('.markdown-code-block').first(),badge=block.locator('.markdown-code-controls');
  await expect(block.locator('.hljs-attr').first()).toHaveText('apiVersion:');await expect(root.locator('.hljs-keyword').first()).toHaveText('const');await expect(badge.getByRole('button',{name:/^Code language:/})).toHaveText('YAML');
  const script=root.locator('.markdown-code-block').last();await expect(script.locator('.hljs-variable')).toHaveText('$NAMESPACE');await expect(script.locator('.hljs-string')).toHaveText('"Content-Type: application/json"');await expect(script.locator('.hljs-comment')).toHaveText('# Finalize the namespace');
  await script.getByRole('button',{name:'Copy code',exact:true}).click();await expect.poll(()=>page.evaluate(()=>globalThis.copiedCode)).toBe(shell);
  const bounds=await block.evaluate(node=>{const outer=node.getBoundingClientRect(),badge=node.querySelector('.markdown-code-controls').getBoundingClientRect();return {gap:outer.right-badge.right,top:badge.top-outer.top};});expect(bounds.gap).toBeGreaterThanOrEqual(0);expect(bounds.gap).toBeLessThan(20);expect(bounds.top).toBeLessThan(20);
  await badge.getByRole('button',{name:'Copy code',exact:true}).click();await expect.poll(()=>page.evaluate(()=>globalThis.copiedCode)).toBe(yaml.replaceAll('\n','\r\n'));expect(await page.evaluate(()=>globalThis.presentation.value())).toBe(original);
  await badge.getByRole('button',{name:/^Code language:/}).click();const language=page.getByRole('dialog',{name:'Code language',exact:true}).getByRole('combobox',{name:'Code language',exact:true});await language.fill('yml');await language.press('Enter');
  expect(await page.evaluate(()=>globalThis.presentation.value())).toContain('```yml');await page.keyboard.press('ControlOrMeta+z');expect(await page.evaluate(()=>globalThis.presentation.value())).toBe(original);
  await page.evaluate(()=>document.fonts.load('1em bootstrap-icons'));expect(await badge.locator('.icon').evaluate(node=>getComputedStyle(node,'::before').fontFamily)).toContain('bootstrap-icons');
  await expect(root.getByRole('button',{name:'Undo',exact:true})).toHaveCount(0);await expect(root.getByRole('button',{name:'Redo',exact:true})).toHaveCount(0);
  for(const width of [1440,320]){
    await page.setViewportSize({width,height:900});for(const theme of ['timekeeper','timekeeper-dark']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=(theme==='timekeeper')?'light':'dark';},theme);
      const colors=await script.locator('code').evaluate(code=>{
        // Normalize color-mix() and rgb() to actual rendered sRGB pixels.
        const context=document.createElement('canvas').getContext('2d'),color=node=>{context.fillStyle=getComputedStyle(node).color;context.fillRect(0,0,1,1);return [...context.getImageData(0,0,1,1).data].join(',');};
        return [code,...code.querySelectorAll('span')].map(color);
      });
      expect(colors.slice(1).every(color=>color!==colors[0]),`${theme} shell token colors at ${width}px`).toBe(true);
      expect((await new AxeBuilder({page}).include('#presentation-editor').analyze()).violations,`${theme} at ${width}px`).toEqual([]);
    }
    await page.evaluate(()=>{document.documentElement.dataset.theme='timekeeper';document.documentElement.dataset.bsTheme='light';});await root.screenshot({path:`test-results/editor-code-presentation-${width}.png`});
  }
});

test('formatted Markdown, HTML and wiki links follow ordinary clicks without modifying the draft',async({page})=>{
  const original='[Web link](https://example.test/page) and [Local](Notes/Target.md).\n\n<a href="https://example.test/?one=1&amp;two=2" onclick="window.badLink=true">HTML link</a>\n\n[[Notes/Target|Wiki link]] and [[2026-11-02]]\n';
  const root=await open(page,original);
  for(const readOnly of [false,true]){
    await page.evaluate(value=>globalThis.presentation.setReadOnly(value),readOnly);
    for(const link of await root.locator('a[href],[data-wiki-reference]').all())await expect(link).toHaveCSS('cursor','pointer');
  }
  await root.getByRole('link',{name:'Web link',exact:true}).click();await root.getByRole('link',{name:'HTML link',exact:true}).click();
  expect(await page.evaluate(()=>globalThis.openedLinks)).toEqual([['https://example.test/page','_blank','noopener,noreferrer'],['https://example.test/?one=1&two=2','_blank','noopener,noreferrer']]);
  await root.getByRole('link',{name:'Local',exact:true}).click();await root.getByRole('button',{name:'Wiki link',exact:true}).click();expect(await page.evaluate(()=>globalThis.followedLinks)).toEqual(['Notes/Target.md','Notes/Target|Wiki link']);
  const link=root.getByRole('link',{name:'Web link',exact:true});await link.focus();await link.press('Enter');expect(await page.evaluate(()=>globalThis.openedLinks.length)).toBe(3);
  expect(await page.evaluate(()=>globalThis.badLink)).toBeUndefined();await expect(root.locator('a[onclick]')).toHaveCount(0);expect(await page.evaluate(()=>globalThis.presentation.value())).toBe(original);
});

test('Mermaid shows only a diagram and code highlights preserve editable caret and Backspace',async({page})=>{
  const original='```javascript\nconst answer = 42;\n```\n\n```mermaid\ngraph LR\n  Start --> Finish\n```\n\nAfter the diagram.\n',root=await open(page,original);
  const diagram=root.locator('.markdown-mermaid-block');await expect(diagram.locator('img')).toBeVisible();await expect(diagram.locator('pre,code,textarea,[contenteditable=true]')).toHaveCount(0);
  const code=root.locator('.markdown-code-block code');await appendEditorText(code,'abcd');for(let i=0;i<4;i++)await page.keyboard.press('Backspace');await expect(code).toHaveText('const answer = 42;');
  const after=root.locator('.ProseMirror > p').last();await appendEditorText(after,'x');await page.keyboard.press('Backspace');await expect(after).toHaveText('After the diagram.');expect(await page.evaluate(()=>globalThis.presentation.value())).toBe(original);
  const menu=await noteActions(page,root);await expect(menu.getByRole('menuitem',{name:'Note revisions',exact:true})).toBeVisible();await page.keyboard.press('Escape');await expect(root.getByRole('button',{name:'Note tools',exact:true})).toBeFocused();
  await root.getByRole('button',{name:'Markdown source',exact:true}).click();await expect(root.locator('textarea').first()).toHaveValue(original);await root.getByRole('button',{name:'Formatted',exact:true}).click();await expect(diagram.locator('img')).toBeVisible();expect(await page.evaluate(()=>globalThis.presentation.value())).toBe(original);
});

test('a final code block allows keyboard and pointer navigation to following prose',async({page})=>{
  for(const original of ['~~~shell\r\necho "$NAMESPACE"\r\n~~~\r\n','> ~~~shell\r\n> echo "$NAMESPACE"\r\n> ~~~\r\n'])for(const action of ['ArrowDown','ArrowRight','click']){
    const root=await open(page,original),code=root.locator('.markdown-code-block code'),rich=root.locator('.ProseMirror');
    await appendEditorText(code,'');
    if(action==='click'){
      await rich.scrollIntoViewIfNeeded();
      const block=await root.locator('.markdown-code-block').boundingBox(),editor=await rich.boundingBox();
      expect(editor.y+editor.height).toBeGreaterThan(block.y+block.height+10);
      await page.mouse.click(editor.x+24,block.y+block.height+10);
    }else await page.keyboard.press(action);
    const paragraph=rich.locator(':scope > p');await expect(paragraph,action).toHaveCount(1);await expect(paragraph).toBeEmpty();
    expect(await page.evaluate(()=>globalThis.presentation.value()),`${action} preserves untouched source`).toBe(original);
    await page.keyboard.insertText('After code');await expect(paragraph).toHaveText('After code');await expect(code).toHaveText('echo "$NAMESPACE"');
    const edited=await page.evaluate(()=>globalThis.presentation.value());expect(edited).toContain(original.trimEnd());expect(edited).toMatch(/~~~\r\n\r\nAfter code/);
    await page.keyboard.press('ControlOrMeta+z');expect(await page.evaluate(()=>globalThis.presentation.value())).toBe(original);
    await page.keyboard.press('ControlOrMeta+Shift+z');expect(await page.evaluate(()=>globalThis.presentation.value())).toBe(edited);
    await root.getByRole('button',{name:'Markdown source',exact:true}).click();await expect(root.locator('textarea').first()).toHaveValue(edited.replaceAll('\r\n','\n'));expect(await page.evaluate(()=>globalThis.presentation.value())).toBe(edited);
    await root.getByRole('button',{name:'Formatted',exact:true}).click();await expect(paragraph).toHaveText('After code');
  }
});

test('code navigation reuses following prose and cannot edit a read-only document',async({page})=>{
  const original='```shell\necho "$NAMESPACE"\n```\n\nExisting prose.\n',root=await open(page,original),rich=root.locator('.ProseMirror'),code=root.locator('.markdown-code-block code');
  await appendEditorText(code,'');await page.keyboard.press('ArrowDown');await page.keyboard.insertText('Continued. ');
  await expect(rich.locator(':scope > p')).toHaveCount(1);await expect(rich.locator(':scope > p')).toHaveText('Continued. Existing prose.');await expect(code).toHaveText('echo "$NAMESPACE"');
  await page.evaluate(async()=>{globalThis.presentation.setValue('```shell\necho "$NAMESPACE"\n```\n');globalThis.presentation.setReadOnly(true);await globalThis.presentation.showFormatted(false);});
  const before=await page.evaluate(()=>globalThis.presentation.value());await rich.scrollIntoViewIfNeeded();
  const block=await root.locator('.markdown-code-block').boundingBox(),editor=await rich.boundingBox();await page.mouse.click(editor.x+24,block.y+block.height+10);await page.keyboard.press('ArrowDown');
  await expect(rich.locator(':scope > p')).toHaveCount(0);expect(await page.evaluate(()=>globalThis.presentation.value())).toBe(before);
});
