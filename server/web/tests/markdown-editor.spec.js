import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {readFile} from 'node:fs/promises';
import {captureScreenshot} from './screenshots.js';
const fixture=async name=>readFile(new URL('./fixtures/markdown/'+name+'.md',import.meta.url),'utf8');
const open=async page=>{await page.goto('/web/markdown-lab.html');await expect(page.locator('#markdown-toolbar button')).toHaveCount(8);};
async function importNote(page,text){await page.locator('#markdown-file').setInputFiles({name:'note.md',mimeType:'text/markdown',buffer:Buffer.from(text)});await expect(page.locator('#markdown-status')).toContainText('Opened locally');}
async function downloaded(page){const pending=page.waitForEvent('download');await page.locator('#markdown-download').click();return readFile(await(await pending).path(),'utf8');}

test('Markdown editor lazily loads local formatting and preserves exact mode round trips',async({page})=>{
  const requests=[],outside=[];page.on('request',request=>{requests.push(request);if(new URL(request.url()).origin!==new URL(process.env.TIMEKEEPER_WEB_URL).origin)outside.push(request.url());});
  await open(page);expect(requests.some(request=>request.url().includes('/milkdown/milkdown.js'))).toBe(false);
  const original=(await fixture('standard')).replaceAll('\n','\r\n');await importNote(page,original);await page.locator('#day-label').fill('Ferie');
  await page.locator('#markdown-rich').click();const rich=page.getByRole('textbox',{name:'Note body',exact:true});await expect(rich).toBeVisible();
  for(const selector of ['h1','strong','em','del','ul','ol','blockquote','pre code','table'])await expect(page.locator('#markdown-formatted '+selector).first()).toBeVisible();
  await expect(page.getByRole('checkbox',{name:'Task: Follow up'})).not.toBeChecked();await expect(page.getByRole('checkbox',{name:'Task: Completed'})).toBeChecked();
  await page.locator('#markdown-formatted a').click();await expect(page).toHaveURL(/markdown-lab\.html$/);
  await page.locator('#markdown-source').click();await expect(page.locator('#markdown-body')).toBeFocused();expect(await downloaded(page)).toBe(original);
  await page.locator('#markdown-rich').click();await expect(rich).toBeVisible();await page.keyboard.press('Escape');await expect(page.locator('#markdown-source')).toBeFocused();
  await expect(page.locator('#day-label')).toHaveValue('Ferie');expect(await downloaded(page)).toBe(original);
  expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
  await captureScreenshot(page,{path:'test-results/markdown-desktop.png',fullPage:true});
  expect(outside).toEqual([]);expect(requests.filter(request=>!['GET','HEAD'].includes(request.method()))).toEqual([]);
});
test('Markdown rich edits, tasks, undo and source edits keep semantic content and metadata',async({page})=>{
  await open(page);const original=await fixture('standard');await importNote(page,original);await page.locator('#markdown-rich').click();
  await page.getByRole('checkbox',{name:'Task: Follow up'}).check();await page.locator('#markdown-source').click();
  await expect(page.locator('#markdown-body')).toHaveValue(/[-*] \[x\] Follow up/);const output=await downloaded(page);expect(output.startsWith(original.slice(0,original.indexOf('# Sunday')))).toBe(true);expect(output).toContain('[[This is a code example, not a wikilink]]');
  await page.locator('#markdown-body').fill('# New note\n\nPreserve **this**.\n');await page.locator('#markdown-rich').click();
  const paragraph=page.locator('#markdown-formatted p').first();await paragraph.click();await page.keyboard.press('End');await page.keyboard.type(' More.');
  await page.locator('#markdown-toolbar').getByRole('button',{name:'Undo',exact:true}).click();await expect(paragraph).not.toContainText('More.');
  await page.locator('#markdown-toolbar').getByRole('button',{name:'Redo',exact:true}).click();await expect(paragraph).toContainText('More.');
  await page.locator('#markdown-source').click();await expect(page.locator('#markdown-body')).toHaveValue(/Preserve \*\*this\*\*\. More\./);
  await downloaded(page);
});
test('Obsidian extensions, frontmatter and CRLF remain exact source, with a usable failed-load fallback',async({page})=>{
  await open(page);const original='\uFEFF'+(await fixture('obsidian')).replaceAll('\n','\r\n');await importNote(page,original);
  await expect(page.locator('#markdown-metadata')).toBeVisible();await expect(page.locator('#markdown-body')).not.toHaveValue(/^---/);
  await page.locator('#markdown-rich').click();await expect(page.locator('#markdown-status')).toContainText('cannot safely preserve');await expect(page.locator('#markdown-body')).toBeVisible();expect(await downloaded(page)).toBe(original);
  await page.locator('#markdown-body').fill('Edited [[wikilink]]\n');expect(await downloaded(page)).toBe(original.slice(0,original.indexOf('# Sunday'))+'Edited [[wikilink]]\n');
  await page.locator('#markdown-body').fill('# Ordinary note\n');await page.route('**/assets/milkdown/milkdown.js',route=>route.abort());
  await page.locator('#markdown-rich').click();await expect(page.locator('#markdown-status')).toContainText('could not be loaded');await expect(page.locator('#markdown-body')).toHaveValue('# Ordinary note\n');
  await page.unroute('**/assets/milkdown/milkdown.js');await page.locator('#markdown-rich').click();await expect(page.locator('#markdown-formatted')).toBeVisible();
  await downloaded(page);
});
test('Markdown controls are localized, keyboard accessible and fit mobile in all themes',async({page})=>{
  await page.addInitScript(()=>{localStorage.setItem('timekeeper.theme','timekeeper-dark');});
  await page.setViewportSize({width:390,height:844});await page.route('**/api/v1/auth/session',route=>route.fulfill({json:{locale:'nb-NO'}}));
  await open(page);await page.getByRole('button',{name:'Formatert',exact:true}).click();const rich=page.getByRole('textbox',{name:'Notattekst',exact:true});await expect(rich).toBeFocused();
  await page.keyboard.press('Escape');await expect(page.getByRole('button',{name:'Markdown-kilde',exact:true})).toBeFocused();
  for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=['timekeeper','solarized-light'].includes(theme)?'light':'dark';},theme);
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
test('Markdown schema round-trip guard retains unsupported code-block metadata as source',async({page})=>{
  await open(page);const value='```js title="preserve this"\nconst day = "Sunday";\n```\n';
  await page.locator('#markdown-body').fill(value);await page.locator('#markdown-rich').click();await expect(page.locator('#markdown-status')).toContainText('cannot safely preserve');
  await expect(page.locator('#markdown-body')).toHaveValue(value);expect(await downloaded(page)).toBe(value);
});
