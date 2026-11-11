import {openEditorFixture} from './editor-fixture.js';
import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {readFile} from 'node:fs/promises';
import {captureScreenshot} from './screenshots.js';
const fixture=async name=>readFile(new URL('./fixtures/markdown/'+name+'.md',import.meta.url),'utf8');
const open=async page=>{await openEditorFixture(page);await expect(page.locator('#markdown-toolbar')).toBeAttached();};
async function importNote(page,text){await page.locator('#markdown-file').setInputFiles({name:'note.md',mimeType:'text/markdown',buffer:Buffer.from(text)});await expect(page.locator('#markdown-status')).toContainText('Opened locally');}
async function downloaded(page){const pending=page.waitForEvent('download');await page.locator('#markdown-download').click();return readFile(await(await pending).path(),'utf8');}

test('Markdown editor lazily loads local formatting and preserves exact mode round trips',async({page})=>{
  const requests=[],outside=[];page.on('request',request=>{requests.push(request);if(new URL(request.url()).origin!==new URL(process.env.TIMEKEEPER_WEB_URL).origin)outside.push(request.url());});
  await open(page);expect(requests.some(request=>request.url().includes('/milkdown/milkdown.js'))).toBe(false);
  const original=(await fixture('standard')).replaceAll('\n','\r\n');await importNote(page,original);await page.locator('#day-label').fill('Ferie');
  await page.locator('#markdown-rich').click();const rich=page.getByRole('textbox',{name:'Note body',exact:true});await expect(rich).toBeVisible();
  for(const selector of ['h1','strong','em','del','ul','ol','blockquote','pre code','table'])await expect(page.locator('#markdown-formatted '+selector).first()).toBeVisible();
  await expect(page.getByRole('checkbox',{name:'Task: Follow up'})).not.toBeChecked();await expect(page.getByRole('checkbox',{name:'Task: Completed'})).toBeChecked();
  const href=await page.locator('#markdown-formatted a').getAttribute('href');await page.context().route(href,route=>route.fulfill({contentType:'text/html',body:'<title>Linked page</title>'}));const opened=page.waitForEvent('popup');await page.locator('#markdown-formatted a').click();await(await opened).close();await expect(page).toHaveURL(/editor-fixture\.html$/);
  await page.locator('#markdown-source').click();await expect(page.locator('#markdown-body')).toBeFocused();expect(await downloaded(page)).toBe(original);
  await page.locator('#markdown-rich').click();await expect(rich).toBeVisible();await page.keyboard.press('Escape');await expect(page.locator('#markdown-source')).toBeFocused();
  await expect(page.locator('#day-label')).toHaveValue('Ferie');expect(await downloaded(page)).toBe(original);
  expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
  await captureScreenshot(page,{path:'test-results/markdown-desktop.png',fullPage:true});
  expect(outside).toEqual([]);expect(requests.filter(request=>!['GET','HEAD'].includes(request.method()))).toEqual([]);
});
test('Markdown tables stay compact, themed and readable on desktop and mobile',async({page})=>{
  test.setTimeout(120000);
  await open(page);
  const source='| Pool | Nodes | vCPU |\n| :--- | ---: | ---: |\n| Test | 7 | 56 |\n| Production | 18 | 120 |\n\n| Environment | Platform nodes | Worker nodes | Application nodes | Total vCPU | Subscription units |\n| --- | --- | --- | --- | --- | --- |\n| Production | 5 | 7 | 6 | 120 | 30 |\n';
  await importNote(page,source);await page.locator('#markdown-rich').click();
  const table=page.locator('#markdown-formatted table').first();await expect(table).toBeVisible();
  const backgrounds=new Set();
  for(const theme of ['timekeeper','timekeeper-dark']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=(theme==='timekeeper')?'light':'dark';},theme);
    backgrounds.add(await table.locator('th').first().evaluate(cell=>getComputedStyle(cell).backgroundColor));
    for(const width of [1200,390]){
      await page.setViewportSize({width,height:900});
      expect((await table.locator('tr').nth(1).boundingBox()).height).toBeLessThan(36);
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      if(width===390)expect(await page.locator('#markdown-formatted table').last().evaluate(table=>{
        for(let parent=table.parentElement;parent&&!parent.classList.contains('journal-formatted');parent=parent.parentElement)
          if(['auto','scroll'].includes(getComputedStyle(parent).overflowX)&&parent.scrollWidth>parent.clientWidth)return true;
        return false;
      })).toBe(true);
      expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
      if(['timekeeper','timekeeper-dark'].includes(theme))await captureScreenshot(page,{path:`test-results/markdown-table-${theme}-${width}.png`,fullPage:true});
    }
  }
  expect(backgrounds.size).toBe(6);expect(await downloaded(page)).toBe(source);
});
test('Markdown rich edits, tasks, undo and source edits keep semantic content and metadata',async({page})=>{
  await open(page);const original=await fixture('standard');await importNote(page,original);await page.locator('#markdown-rich').click();
  await page.getByRole('checkbox',{name:'Task: Follow up'}).check();await page.locator('#markdown-source').click();
  await expect(page.locator('#markdown-body')).toHaveValue(/[-*] \[x\] Follow up/);const output=await downloaded(page);expect(output.startsWith(original.slice(0,original.indexOf('# Sunday')))).toBe(true);expect(output).toContain('[[This is a code example, not a wikilink]]');
  await page.locator('#markdown-body').fill('# New note\n\nPreserve **this**.\n');await page.locator('#markdown-rich').click();
  const paragraph=page.locator('#markdown-formatted p').first();await paragraph.click();await page.keyboard.press('End');await page.keyboard.type(' More.');
  await page.keyboard.press('ControlOrMeta+z');await expect(paragraph).not.toContainText('More.');
  await page.keyboard.press('ControlOrMeta+Shift+z');await expect(paragraph).toContainText('More.');
  await page.locator('#markdown-source').click();await expect(page.locator('#markdown-body')).toHaveValue(/Preserve \*\*this\*\*\. More\./);
  await downloaded(page);
});
test('empty paragraph created with Enter stays editable after a Markdown round trip',async({page})=>{
  await open(page);await page.locator('#markdown-body').fill('First line\n');await page.locator('#markdown-rich').click();
  const rich=page.locator('#markdown-formatted [role="textbox"]');await expect(rich).toBeVisible();
  await rich.click();await page.keyboard.press('Home');await page.keyboard.press('Enter');
  await page.locator('#markdown-source').click();
  const value=await page.locator('#markdown-body').inputValue();expect(value).toContain('First line');expect(value).not.toContain('<br');
  await page.locator('#markdown-rich').click();await expect(rich).toBeVisible();
  await page.locator('#markdown-source').click();expect(await downloaded(page)).not.toContain('<br');
  const authored='<br />\n\nFirst line\n';await page.locator('#markdown-body').fill(authored);
  await page.locator('#markdown-rich').click();await expect(page.locator('#markdown-formatted')).toBeVisible();
  expect(await downloaded(page)).toBe(authored);
});
test('soft line break stays editable after a Markdown round trip',async({page})=>{
  await open(page);await page.locator('#markdown-body').fill('First line\n');await page.locator('#markdown-rich').click();
  const rich=page.locator('#markdown-formatted [role="textbox"]');await expect(rich).toBeVisible();
  await rich.click();await page.keyboard.press('End');await page.keyboard.press('Shift+Enter');await page.keyboard.type('Second line');
  await page.locator('#markdown-source').click();
  const value=await page.locator('#markdown-body').inputValue();expect(value).toContain('First line');expect(value).toContain('Second line');expect(value).not.toContain('<br');
  await page.locator('#markdown-rich').click();await expect(rich).toBeVisible();
});
test('Obsidian extensions, frontmatter and CRLF remain exact source, with a usable failed-load fallback',async({page})=>{
  await open(page);const original='\uFEFF'+(await fixture('obsidian')).replaceAll('\n','\r\n');await importNote(page,original);
  await expect(page.locator('#markdown-metadata')).toBeVisible();await expect(page.locator('#markdown-body')).not.toHaveValue(/^---/);
  await page.locator('#markdown-rich').click();await expect(page.locator('#markdown-formatted')).toBeVisible();expect(await downloaded(page)).toBe(original);await page.locator('#markdown-source').click();
  await page.locator('#markdown-body').fill('Edited [[wikilink]]\n');expect(await downloaded(page)).toBe(original.slice(0,original.indexOf('# Sunday'))+'Edited [[wikilink]]\n');
  await open(page);await page.locator('#markdown-body').fill('# Ordinary note\n');await page.route('**/assets/milkdown/milkdown.js',route=>route.abort());
  await page.locator('#markdown-rich').click();await expect(page.locator('#markdown-status')).toContainText('could not be loaded');await expect(page.locator('#markdown-body')).toHaveValue('# Ordinary note\n');
  await page.unroute('**/assets/milkdown/milkdown.js');await page.locator('#markdown-rich').click();await expect(page.locator('#markdown-formatted')).toBeVisible();
  await downloaded(page);
});
test('Markdown controls are localized, keyboard accessible and fit mobile in all themes',async({page})=>{
  await page.addInitScript(()=>{localStorage.setItem('timekeeper.theme','timekeeper-dark');});
  await page.setViewportSize({width:390,height:844});await page.route('**/api/v1/auth/session',route=>route.fulfill({json:{locale:'nb-NO'}}));
  await open(page);await page.getByRole('button',{name:'Formatert',exact:true}).click();const rich=page.getByRole('textbox',{name:'Notattekst',exact:true});await expect(rich).toBeFocused();
  await page.keyboard.press('Escape');await expect(page.getByRole('button',{name:'Markdown-kilde',exact:true})).toBeFocused();
  for(const theme of ['timekeeper','timekeeper-dark']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);
    expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations,theme).toEqual([]);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }
  await captureScreenshot(page,{path:'test-results/markdown-mobile.png',fullPage:true});
});
test('Markdown supports touch task toggling and text entry on a mobile viewport',async({browser})=>{
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,viewport:{width:390,height:844},hasTouch:true,isMobile:true,ignoreHTTPSErrors:true});
  try{
    const page=await context.newPage();await open(page);await page.locator('#markdown-body').fill('- [ ] Touch task\n\nTouch text\n');await page.locator('#markdown-rich').tap();
    const checkbox=page.getByRole('checkbox',{name:'Task: Touch task',exact:true});await checkbox.tap();await expect(checkbox).toBeChecked();
    await page.locator('#markdown-formatted p').last().tap();await page.keyboard.press('End');await page.keyboard.insertText(' edited');
    await page.locator('#markdown-source').tap();await expect(page.locator('#markdown-body')).toHaveValue(/\[x\] Touch task[\s\S]*Touch text edited/);
    await downloaded(page);
  }finally{await context.close();}
});
test('Markdown schema guard retains metadata and escaped-code dialects without remote image requests',async({page})=>{
  const external=[];page.on('request',request=>{if(request.url().includes('example.invalid'))external.push(request.url());});
  await open(page);
  for(const value of ['```js title="preserve this"\nconst day = "Sunday";\n```\n','\\`outside [[wikilink]]\\`','\\`![image](https://example.invalid/pixel)\\`']){
    await page.locator('#markdown-body').fill(value);await page.locator('#markdown-rich').click();
    // The shared engine now preserves unfamiliar constructs as inert source
    // fragments. Rich admission is safe when the exact authored bytes survive.
    expect(await downloaded(page)).toBe(value);
    if(await page.locator('#markdown-formatted').isVisible())await page.locator('#markdown-source').click();
    await expect(page.locator('#markdown-body')).toHaveValue(value);
  }
  expect(external).toEqual([]);
});
test('Mermaid fences render as inert SVG previews and preserve editable Markdown',async({page})=>{
  const outside=[],requested=[];page.on('request',request=>{requested.push(request.url());if(!request.url().startsWith('data:')&&new URL(request.url()).origin!==new URL(process.env.TIMEKEEPER_WEB_URL).origin)outside.push(request.url());});
  await open(page);
  expect(requested.some(url=>url.includes('/assets/mermaid/mermaid.js'))).toBe(false);
  const source='```mermaid\nflowchart LR\n  Start --> Finish\n```\n';
  await page.locator('#markdown-body').fill(source);
  await page.locator('#markdown-rich').click();
  const diagram=page.locator('.markdown-mermaid-block');await expect(diagram).toBeVisible();
  await expect(diagram.locator('pre,code,textarea')).toHaveCount(0);
  const image=diagram.getByRole('img',{name:'Mermaid diagram preview'});await expect(image).toBeVisible();
  await expect.poll(()=>image.evaluate(element=>element.complete?element.naturalWidth:0)).toBeGreaterThan(0);
  expect(requested.some(url=>url.includes('/assets/mermaid/mermaid.js'))).toBe(true);
  await captureScreenshot(page,{path:'test-results/mermaid-editor.png',fullPage:true});
  await page.locator('#markdown-source').click();await expect(page.locator('#markdown-body')).toHaveValue(source);
  await page.locator('#markdown-body').fill('```mermaid\nnot a diagram\n```\n');await page.locator('#markdown-rich').click();
  await expect(page.locator('.markdown-mermaid-preview')).toContainText('Diagram syntax could not be rendered');
  await page.locator('#markdown-source').click();await expect(page.locator('#markdown-body')).toHaveValue('```mermaid\nnot a diagram\n```\n');
  const external='```mermaid\nflowchart LR\n  A --> B\n  click A "https://example.invalid/private"\n```\n';
  await page.locator('#markdown-body').fill(external);await page.locator('#markdown-rich').click();
  await expect(page.locator('.markdown-mermaid-preview')).toContainText('cannot contain external resources');
  await page.locator('#markdown-source').click();await expect(page.locator('#markdown-body')).toHaveValue(external);
  expect(outside).toEqual([]);
});

// API requests bypass the test-only page routes: these check the shipped server.
test('production serves the shared editor without the retired prototype or source bundle',async({request})=>{
  const index=await request.get('/web/');expect(index.ok()).toBe(true);expect(await index.text()).not.toContain('markdown-lab');
  for(const path of ['/web/markdown-lab.html','/web/markdown-lab.js','/web/markdown-preview.js','/web/assets/milkdown/source-editor.js'])expect((await request.get(path)).status(),path).toBe(404);
});
