import {test,expect} from '@playwright/test';
import {openEditorFixture} from './editor-fixture.js';

test('emoji shortcodes render locally while source, code, escapes and unknown names stay intact',async({page})=>{
  await openEditorFixture(page);
  const source='---\r\nprivate: :heart:\r\n---\r\n\r\n# Plan :rocket:\r\n\r\nHello :smile: :+1: :unknown_dagsverk:. Escaped \\:heart:.\r\n\r\n`:heart:` and [Site](https://example.test/:smile:)\r\n\r\n```text\r\n:rocket:\r\n```\r\n';
  await page.evaluate(async source=>{fixtureField.setValue(source);await fixtureField.showFormatted(false);},source);
  const rich=page.locator('.ProseMirror');await expect(rich).toBeVisible();await expect(rich.locator('[data-emoji=rocket]')).toHaveText('🚀');await expect(rich.locator('[data-emoji=smile]')).toHaveText('😄');await expect(rich.locator('[data-emoji="+1"]')).toHaveText('👍');await expect(rich.locator('[data-emoji=heart]')).toHaveCount(0);
  await expect(rich).toContainText(':unknown_dagsverk:');await expect(rich).toContainText('Escaped :heart:');await expect(rich.getByRole('link')).toHaveAttribute('href','https://example.test/:smile:');
  expect(await page.evaluate(()=>fixtureField.value())).toBe(source);await page.locator('#markdown-source').click();await expect(page.locator('#markdown-body')).toHaveValue(source.slice(source.indexOf('\r\n---\r\n')+7).replaceAll('\r\n','\n'));expect(await page.evaluate(()=>fixtureField.value())).toBe(source);
});

test('typing an emoji name offers keyboard completion and the closed shortcode supports undo and redo',async({page})=>{
  await openEditorFixture(page);await page.evaluate(async()=>{fixtureField.setValue('Hello ');await fixtureField.showFormatted(false);});
  const rich=page.locator('.ProseMirror');await rich.click();await page.keyboard.press('ControlOrMeta+End');await page.keyboard.type(':rocket:');await expect(rich.locator('[data-emoji=rocket]')).toHaveText('🚀');expect(await page.evaluate(()=>fixtureField.value())).toContain(':rocket:');
  await page.keyboard.press('ControlOrMeta+z');await expect(rich.locator('[data-emoji=rocket]')).toHaveCount(0);await page.keyboard.press('ControlOrMeta+Shift+z');await expect(rich.locator('[data-emoji=rocket]')).toBeVisible();
  await page.keyboard.type(' :smi');const options=page.getByRole('listbox',{name:'Editor suggestions'});await expect(options).toBeVisible();await rich.press('ArrowDown');await page.keyboard.press('Enter');await expect(rich.locator('[data-emoji]')).toHaveCount(2);
  const before=await page.evaluate(()=>fixtureField.value());await page.evaluate(()=>fixtureField.setReadOnly(true));await rich.press('End');await page.keyboard.type(':heart:');expect(await page.evaluate(()=>fixtureField.value())).toBe(before);
});

for(const [name,ending]of [['LF','\n'],['CRLF','\r\n'],['CR','\r'],['tab','\t']])test('literal colons and incomplete shortcodes before '+name+' remain editable',async({page})=>{
  await openEditorFixture(page);
  const source='Summary:'+ending+'Details :unfinished'+ending+'Keep :smile: text.\n';
  await page.evaluate(async source=>{fixtureField.setValue(source);await fixtureField.showFormatted(false);},source);
  const rich=page.locator('.ProseMirror');await expect(rich).toBeVisible();await expect(rich).toContainText('Summary:');await expect(rich.locator('[data-emoji=smile]')).toHaveText('😄');
  expect(await page.evaluate(()=>fixtureField.value())).toBe(source);
  await rich.click();await page.keyboard.press('ControlOrMeta+End');await page.keyboard.type(' Edited.');
  expect(await page.evaluate(()=>fixtureField.value())).toContain('Edited.');await page.locator('#markdown-source').click();await page.locator('#markdown-rich').click();await expect(rich).toContainText('Summary:');await expect(rich.locator('[data-emoji=smile]')).toHaveText('😄');
});
