import {test,expect} from '@playwright/test';
import {captureScreenshot} from './screenshots.js';

test('the workspace opens the bundled desktop guide as a read-only note', async ({page}) => {
  await page.setViewportSize({width:1440,height:1000});
  await page.goto('/web/');
  const trigger = page.getByRole('button',{name:'User guide',exact:true});
  await expect(trigger).toBeHidden();
  await page.locator('#auth-mode').selectOption('token');
  await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_READ_TOKEN);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.locator('#status')).toHaveText('Up to date');
  await expect(trigger).toBeVisible();
  await page.locator('#workspace-nav-toggle').click();
  await expect(trigger.locator('.nav-label')).toBeVisible();
  await page.mouse.move(0,0);
  await captureScreenshot(page,{path:'test-results/desktop-guide-navigation.png'});

  await trigger.focus();
  await trigger.press('Enter');
  const guide = page.getByRole('dialog',{name:'User guide'});
  const note = guide.locator('.ProseMirror');
  await expect(note.getByRole('heading',{name:'Use Dagsverk in a desktop browser'})).toBeVisible();
  await expect(note).toHaveAttribute('contenteditable','false');
  const image = note.getByRole('img',{name:'Today view in a desktop browser'});
  await image.scrollIntoViewIfNeeded();
  await expect.poll(() => image.evaluate(img => img.naturalWidth)).toBeGreaterThan(0);
  await guide.locator('.journal-document-content').evaluate(element => {element.scrollTop=0;});
  await captureScreenshot(page,{path:'test-results/desktop-guide-note.png'});

  await page.keyboard.press('Escape');
  await expect(guide).toBeHidden();
  await expect(trigger).toBeFocused();
  await expect(page.locator('[data-tab=today]')).toHaveAttribute('aria-current','page');

  await page.setViewportSize({width:1100,height:800});
  await trigger.click();
  await expect(guide).toBeVisible();
  expect(await guide.evaluate(element => element.getBoundingClientRect().right)).toBeLessThanOrEqual(1100);
  await guide.getByRole('button',{name:'Close',exact:true}).click();
  await expect(trigger).toBeFocused();
  await page.setViewportSize({width:320,height:844});
  await expect(trigger).toBeHidden();
});
