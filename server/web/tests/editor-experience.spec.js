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
