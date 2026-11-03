import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';

async function open(page,source){
  await page.goto('/web/markdown-lab.html');await expect(page.locator('#markdown-toolbar button')).toHaveCount(8);
  await page.locator('#markdown-file').setInputFiles({name:'fidelity.md',mimeType:'text/markdown',buffer:Buffer.from(source)});
  await expect(page.locator('#markdown-status')).toContainText('Opened locally');await page.locator('#markdown-rich').click();
  await expect(page.locator('#markdown-formatted [role=textbox]')).toBeVisible();
}
async function download(page){const pending=page.waitForEvent('download');await page.locator('#markdown-download').click();return readFile(await(await pending).path(),'utf8');}

test('editing one paragraph retains untouched Markdown spelling, blank lines, mixed line endings and frontmatter',async({page})=>{
  const fixture=await readFile(new URL('./fixtures/markdown/fidelity/blocks.md',import.meta.url),'utf8');
  const source='\uFEFF'+fixture.replaceAll('\n','\r\n').replace('42 |\r\n\r\nTail','42 |\n\nTail');
  await open(page,source);expect(await download(page)).toBe(source);
  const paragraph=page.locator('#markdown-formatted p').filter({hasText:'Edit this paragraph.'});await paragraph.click();await page.keyboard.press('End');await page.keyboard.type(' More.');
  expect(await download(page)).toBe(source.replace('Edit this paragraph.','Edit this paragraph. More.'));
  await page.locator('#markdown-toolbar').getByRole('button',{name:'Undo',exact:true}).click();expect(await download(page)).toBe(source);
  await page.locator('#markdown-toolbar').getByRole('button',{name:'Redo',exact:true}).click();
  const changed=source.replace('Edit this paragraph.','Edit this paragraph. More.');expect(await download(page)).toBe(changed);
  await page.locator('#markdown-source').click();await page.locator('#markdown-rich').click();expect(await download(page)).toBe(changed);
});

test('duplicate paragraphs and inserted blocks retain the correct untouched source',async({page})=>{
  const source='Title\r\n=====\r\n\r\nSame text.\r\n\r\nSame text.\r\n\r\n+ Keep __this__ list\r\n';
  await open(page,source);const paragraphs=page.locator('#markdown-formatted p').filter({hasText:/^Same text\.$/});
  await paragraphs.nth(1).click();await page.keyboard.press('End');await page.keyboard.type(' Changed.');
  const changed=source.replace('Same text.\r\n\r\n+','Same text. Changed.\r\n\r\n+');expect(await download(page)).toBe(changed);
  await page.locator('#markdown-formatted p').filter({hasText:'Same text. Changed.'}).click();await page.keyboard.press('End');await page.keyboard.press('Enter');await page.keyboard.type('Inserted.');
  const inserted=await download(page);expect(inserted).toContain('Title\r\n=====');expect(inserted).toContain('+ Keep __this__ list\r\n');expect(inserted).toContain('Inserted.');
  await page.locator('#markdown-source').click();await page.locator('#markdown-rich').click();await expect(page.locator('#markdown-formatted')).toContainText('Same text. Changed.');await expect(page.locator('#markdown-formatted')).toContainText('Inserted.');
});

test('a changed block boundary stays parseable without normalizing a distant list',async({page})=>{
  await page.goto('/web/markdown-lab.html');
  const source='# Heading\nParagraph.\n\n+ Keep __this__ list\n';
  await page.evaluate(async source=>{
    const {createMarkdownEditor}=await import('/web/assets/milkdown/milkdown.js');
    const root=document.createElement('div');root.id='fidelity-boundary';document.body.append(root);
    globalThis.boundaryEditor=await createMarkdownEditor({root,value:source,label:'Boundary body',taskLabel:value=>value,escape:()=>{}});
  },source);
  expect(await page.evaluate(source=>globalThis.boundaryEditor.matchesSource(source),source)).toBe(true);
  await page.locator('#fidelity-boundary h1').click();await page.evaluate(()=>globalThis.boundaryEditor.command('paragraph'));
  expect(await page.evaluate(source=>globalThis.boundaryEditor.matchesSource(source),source)).toBe(false);
  const edited=await page.evaluate(()=>globalThis.boundaryEditor.markdown());
  expect(await page.evaluate(source=>globalThis.boundaryEditor.matchesSource(source),edited)).toBe(true);expect(edited).toContain('Heading\n\nParagraph.');expect(edited).toContain('+ Keep __this__ list\n');
  await page.evaluate(async source=>{
    await globalThis.boundaryEditor.destroy();
    const {createMarkdownEditor}=await import('/web/assets/milkdown/milkdown.js');
    globalThis.boundaryEditor=await createMarkdownEditor({root:document.getElementById('fidelity-boundary'),value:source,label:'Boundary body',taskLabel:value=>value,escape:()=>{}});
  },edited);
  await expect(page.locator('#fidelity-boundary p')).toHaveCount(3);
});

test('entering formatted mode never repairs or deletes authored HTML source implicitly',async({page})=>{
  const source='\uFEFF---\r\ntitle: Keep HTML\r\n---\r\n\r\n<br />\r\n\r\nOriginal text\r\n';
  await page.goto('/web/markdown-lab.html');await expect(page.locator('#markdown-toolbar button')).toHaveCount(8);
  await page.locator('#markdown-file').setInputFiles({name:'source.md',mimeType:'text/markdown',buffer:Buffer.from(source)});await expect(page.locator('#markdown-status')).toContainText('Opened locally');
  await page.locator('#markdown-rich').click();await expect(page.locator('#markdown-status')).toContainText('cannot safely preserve');expect(await download(page)).toBe(source);
});

test('long-note dirty checks avoid serialization while explicit Markdown remains cached',async({page})=>{
  await page.goto('/web/markdown-lab.html');
  await page.evaluate(async()=>{
    const {createMarkdownEditor}=await import('/web/assets/milkdown/milkdown.js');
    const root=document.createElement('div');root.id='fidelity-performance';document.body.append(root);
    const value=Array.from({length:350},(_,index)=>'Paragraph '+index+' __retains its authored formatting__ '+('text '.repeat(20))).join('\n\n')+'\n';
    globalThis.fidelityTimings=[];globalThis.fidelityBytes=new TextEncoder().encode(value).length;
    globalThis.performanceEditor=await createMarkdownEditor({root,value,label:'Performance body',taskLabel:value=>value,escape:()=>{},changed:()=>{
      if(!globalThis.performanceEditor)return;const start=performance.now();globalThis.performanceEditor.matchesSource(value);globalThis.fidelityTimings.push(performance.now()-start);
    }});
  });
  await page.locator('#fidelity-performance p').nth(175).click();await page.keyboard.press('End');await page.keyboard.type(' measured');
  const result=await page.evaluate(()=>{
    const values=[...globalThis.fidelityTimings].sort((a,b)=>a-b),serializationStart=performance.now();globalThis.performanceEditor.markdown();const serialization=performance.now()-serializationStart;
    const start=performance.now();for(let index=0;index<100;index++)globalThis.performanceEditor.markdown();
    return {bytes:globalThis.fidelityBytes,samples:values.length,dirtyP95:values[Math.ceil(values.length*.95)-1],serialization,cached100:performance.now()-start};
  });
  console.log('Markdown fidelity timing '+JSON.stringify(result));expect(result.samples).toBeGreaterThan(0);expect(result.dirtyP95).toBeLessThan(50);expect(result.cached100).toBeLessThan(1000);
});

test('long-note input reaches the next animation frame within the typing budget',async({page,browser})=>{
  await page.goto('/web/markdown-lab.html');await page.evaluate(async()=>{
    const {createMarkdownEditor}=await import('/web/assets/milkdown/milkdown.js'),root=document.createElement('div');root.id='typing-performance';document.body.append(root);
    const value=Array.from({length:350},(_,index)=>'Paragraph '+index+' __retains its authored formatting__ '+('text '.repeat(20))).join('\n\n')+'\n';
    globalThis.typingEditor=await createMarkdownEditor({root,value,label:'Typing performance',taskLabel:value=>value,escape:()=>{}});globalThis.typingSamples=[];
    root.addEventListener('beforeinput',()=>{const start=performance.now();requestAnimationFrame(()=>globalThis.typingSamples.push(performance.now()-start));});
  });
  await page.locator('#typing-performance p').nth(175).click();await page.keyboard.type(' measured typing responsiveness');await expect.poll(()=>page.evaluate(()=>globalThis.typingSamples.length)).toBeGreaterThan(20);
  const samples=await page.evaluate(()=>[...globalThis.typingSamples].sort((a,b)=>a-b)),p95=samples[Math.ceil(samples.length*.95)-1];console.log('Editor input timing '+JSON.stringify({browser:browser.version(),platform:process.platform,architecture:process.arch,samples:samples.length,p95}));expect(p95).toBeLessThan(150);
});
