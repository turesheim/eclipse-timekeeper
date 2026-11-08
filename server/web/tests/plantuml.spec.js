import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {openEditorFixture} from './editor-fixture.js';
import {appendEditorText} from './editor-interactions.js';
import {captureScreenshot} from './screenshots.js';

const examples={sequence:'@startuml\nAlice -> Bob: Hello\nBob --> Alice: World\n@enduml',class:'@startuml\nclass Person\nclass Address\nPerson --> Address\n@enduml',activity:'@startuml\nstart\n:Hello;\nstop\n@enduml'};
const fence=(source,language='plantuml')=>'```'+language+'\n'+source+'\n```\n';
async function open(page,value,{formatted=true,readOnly=false}={}){
  await openEditorFixture(page);await page.evaluate(async({value,formatted,readOnly})=>{
    const {MarkdownField}=await import('/web/markdown-field.js');const root=document.createElement('section');root.id='plantuml-field';document.body.append(root);globalThis.plantumlField=new MarkdownField(root,()=>{},()=>{},'plantuml');plantumlField.setValue(value);plantumlField.setReadOnly(readOnly);if(formatted)await plantumlField.showFormatted();
  },{value,formatted,readOnly});return page.locator('#plantuml-field');
}
const source=page=>page.evaluate(()=>globalThis.plantumlField.value());
const rendered=async root=>{const image=root.locator('.markdown-plantuml-block img');await expect(image).toBeVisible();await expect.poll(()=>image.evaluate(node=>node.complete?node.naturalWidth:0),{timeout:20000}).toBeGreaterThan(0);return image;};

test('PlantUML loads locally on demand and preserves fenced sequence, class and activity source',async({page})=>{
  const requests=[],outside=[];page.on('request',request=>{requests.push(request.url());if(!['data:','blob:'].includes(new URL(request.url()).protocol)&&new URL(request.url()).origin!==new URL(process.env.TIMEKEEPER_WEB_URL).origin)outside.push(request.url());});
  const original='\uFEFF---\r\nprivate: retained\r\n---\r\n\r\n~~~PLANTUML\r\n'+examples.sequence.replaceAll('\n','\r\n')+'\r\n~~~\r\n';
  const root=await open(page,original,{formatted:false});expect(requests.some(url=>url.includes('/assets/plantuml/'))).toBe(false);await root.getByRole('button',{name:'Formatted',exact:true}).click();await rendered(root);expect(await source(page)).toBe(original);
  await expect(root.locator('.markdown-plantuml-block pre,.markdown-plantuml-block code,.markdown-plantuml-block textarea')).toHaveCount(0);expect(requests.some(url=>url.includes('/assets/plantuml/plantuml-engine.js'))).toBe(true);
  await root.getByRole('button',{name:'Markdown source',exact:true}).click();expect(await source(page)).toBe(original);
  for(const [name,value]of Object.entries(examples)){
    await page.evaluate(value=>globalThis.plantumlField.setValue(value),fence(value,name==='class'?'puml':'plantuml'));await root.getByRole('button',{name:'Formatted',exact:true}).click();const image=await rendered(root);await expect(image).toHaveAttribute('src',/^blob:/);expect(await source(page)).toBe(fence(value,name==='class'?'puml':'plantuml'));await root.getByRole('button',{name:'Markdown source',exact:true}).click();
  }
  expect(outside).toEqual([]);
});

test('PlantUML syntax, files and image failures leave their canonical source available',async({page})=>{
  for(const [value,message]of [[fence('@startuml\nthis is nonsense\n@enduml'),'could not be rendered'],[fence('@startuml\n!include https://never-fetch.invalid/private\n@enduml'),'cannot load files'],[fence('@startuml\nAlice -> Bob: <img:https://never-fetch.invalid/pixel>\n@enduml'),'cannot load files'],[fence('x'.repeat(10001)),'10 KB']]){
    const root=await open(page,value);await expect(root.locator('.markdown-diagram-preview')).toContainText(message);await expect(root.locator('.markdown-plantuml-block img')).toBeHidden();await root.getByRole('button',{name:'Markdown source',exact:true}).click();expect(await source(page)).toBe(value);
  }
});

test('PlantUML previews fit every theme and phones and remain read-only with no source rewrite',async({page})=>{
  const value=fence('@startuml\ntitle Blåbær café 🚀\nAlice -> Bob: Feilsøking\n@enduml'),root=await open(page,value,{readOnly:true});await rendered(root);
  for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=['timekeeper','solarized-light'].includes(theme)?'light':'dark';},theme);await rendered(root);
    for(const width of [1440,390]){await page.setViewportSize({width,height:844});expect(await root.locator('.markdown-plantuml-block img').evaluate(node=>node.getBoundingClientRect().width<=node.parentElement.getBoundingClientRect().width)).toBe(true);expect((await new AxeBuilder({page}).include('#plantuml-field').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);}
  }
  await captureScreenshot(page,{path:'test-results/plantuml-preview-phone.png',fullPage:true});expect(await source(page)).toBe(value);await root.getByRole('button',{name:'Markdown source',exact:true}).click();await expect(page.locator('#plantuml-body')).toHaveAttribute('readonly','');
});

test('ordinary prose after PlantUML stays editable and undo preserves the diagram fence',async({page})=>{
  const value=fence(examples.class)+'\nAfter the diagram.\n',root=await open(page,value);await rendered(root);const paragraph=root.locator('.ProseMirror > p').last();await appendEditorText(paragraph,' Changed.');expect(await source(page)).toContain(fence(examples.class).trimEnd());await page.keyboard.press('ControlOrMeta+z');expect(await source(page)).toBe(value);
});

test('mode switches retain the prose caret after an atomic diagram preview',async({page})=>{
  for(const [language,body]of [['plantuml',examples.sequence],['mermaid','graph LR\n  Start --> Finish']]){
    const value='Before the diagram.\n\n'+fence(body,language)+'\nAfter the diagram.\n',root=await open(page,value),image=root.locator('.markdown-plantuml-block img,.markdown-mermaid-block img');await expect(image).toBeVisible();await appendEditorText(root.locator('.ProseMirror > p').last(),'');
    await root.getByRole('button',{name:'Markdown source',exact:true}).click();const textarea=page.locator('#plantuml-body');expect(await textarea.evaluate(node=>node.selectionStart)).toBe(value.indexOf('After the diagram.')+'After the diagram.'.length);
    await root.getByRole('button',{name:'Formatted',exact:true}).click();await page.keyboard.insertText(' Here.');await expect(root.locator('.ProseMirror > p').last()).toHaveText('After the diagram. Here.');expect(await source(page)).toContain(fence(body,language).trimEnd());
  }
});

test('changing a code-block language to PlantUML renders a preview and undo restores editable code',async({page})=>{
  const value=fence(examples.sequence,'text'),root=await open(page,value),code=root.locator('.markdown-code-block');await code.getByRole('button',{name:/Code language:/}).click();const language=page.getByRole('dialog',{name:'Code language',exact:true}).getByRole('combobox',{name:'Code language',exact:true});await language.fill('plantuml');await language.press('Enter');await rendered(root);expect(await source(page)).toContain('```plantuml');await page.keyboard.press('ControlOrMeta+z');await expect(root.locator('.markdown-code-block code')).toHaveText(examples.sequence);expect(await source(page)).toBe(value);
});

test('clearing PlantUML revokes its image and cancels a worker without replacing a newer document',async({page})=>{
  const root=await open(page,fence(examples.sequence));const image=await rendered(root),url=await image.getAttribute('src');await page.evaluate(()=>globalThis.plantumlField.clear());expect(await page.evaluate(url=>new Promise(resolve=>{const image=new Image();image.onload=()=>resolve(false);image.onerror=()=>resolve(true);image.src=url;}),url)).toBe(true);
  let worker;page.on('worker',value=>{worker=value;});await page.route('**/assets/plantuml/worker.js',route=>route.fulfill({contentType:'text/javascript',body:'postMessage({ready:true});onmessage=()=>{};'}));
  await page.evaluate(async value=>{plantumlField.setValue(value);await plantumlField.showFormatted();},fence(examples.class));await expect.poll(()=>!!worker).toBe(true);const closed=new Promise(resolve=>worker.once('close',resolve));await page.evaluate(()=>plantumlField.setValue('A different private document.'));await closed;await expect(root.locator('img')).toHaveCount(0);expect(await source(page)).toBe('A different private document.');
});

test('a stalled PlantUML worker times out while the page stays responsive and the next render succeeds',async({page})=>{
  await page.route('**/assets/plantuml/worker.js',route=>route.fulfill({contentType:'text/javascript',body:'postMessage({ready:true});onmessage=()=>{while(true){}};'}));const root=await open(page,fence(examples.sequence));
  expect(await page.evaluate(()=>new Promise(resolve=>setTimeout(()=>resolve('responsive'),50)))).toBe('responsive');await expect(root.locator('.markdown-diagram-preview')).toContainText('could not be rendered',{timeout:20000});
  await page.unroute('**/assets/plantuml/worker.js');await page.evaluate(async value=>{plantumlField.setValue(value);await plantumlField.showFormatted();},fence(examples.activity));await rendered(root);
});

test('PlantUML SVG validation rejects active or oversized markup and strips links and source metadata',async({page})=>{
  await openEditorFixture(page);const result=await page.evaluate(async()=>{
    const {plantumlImage}=await import('/web/plantuml-svg.js'),wrap=body=>'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">'+body+'</svg>',rejected=[];
    for(const source of [wrap('<script>window.bad=true</script>'),wrap('<image href="https://never-fetch.invalid/pixel"/>'),wrap('<foreignObject/>'),wrap('').replace('100 100','100000 100000')]){try{plantumlImage(source);rejected.push(false);}catch{rejected.push(true);}}
    const cleaned=await plantumlImage(wrap('<?plantuml-src private-source?><a href="https://never-fetch.invalid/"><text onload="window.bad=true" fill="url(https://never-fetch.invalid/)">Safe label</text></a>')).text();return {rejected,cleaned,bad:globalThis.bad};
  });expect(result.rejected).toEqual([true,true,true,true]);expect(result.cleaned).not.toMatch(/never-fetch|onload|plantuml-src|private-source/);expect(result.cleaned).toContain('Safe label');expect(result.bad).toBeUndefined();
});
