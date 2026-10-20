import {captureScreenshot} from './screenshots.js';
import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {openAccountMenu,openAbout} from './navigation.js';

test('bottom navigation keeps every view accessible, stationary and clear of content at narrow and wide widths',async({page,browserName})=>{
  await page.goto('/web/');const bar=page.locator('#workspace-navigation');await expect(bar).not.toBeVisible();
  await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_TOKEN);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  await expect(bar).toHaveClass(/navbar/);await expect(bar).toHaveClass(/fixed-bottom/);
  await expect(bar.locator('.navbar-nav')).toHaveCount(1);
  expect(await page.evaluate(async()=>{
    await document.fonts.load('18px bootstrap-icons');
    return [...document.fonts].some(font=>font.family.includes('bootstrap-icons') && font.status==='loaded');
  })).toBe(true);
  await expect(page.locator('#refresh')).toHaveClass(/\bbtn\b/);
  const views=[['track','Calendar'],['week','Workweek'],['calendar','Week'],['activities','Activities'],['clients','Clients'],['projects','Projects'],['labels','Labels']];
  await expect(page.locator('[data-tab=tasks],#tasks-panel')).toHaveCount(0);
  expect(await bar.locator('button').evaluateAll(nodes=>nodes.map(node=>node.dataset.tab))).toEqual(views.map(([id])=>id));
  for(const width of [320,390,768,1440]) {
    await page.setViewportSize({width,height:844});
    await expect(bar.locator('button')).toHaveCount(7);await expect(bar.locator('.bi[aria-hidden=true]')).toHaveCount(7);
    const brand=await page.locator('.brand').boundingBox(),status=await page.locator('#sync-status').boundingBox();expect(status.x).toBeGreaterThanOrEqual(brand.x+brand.width);expect(Math.abs(status.y+status.height/2-brand.y-brand.height/2)).toBeLessThan(1);
    const bounds=await bar.boundingBox();expect(bounds.y+bounds.height).toBeCloseTo(844,0);
    for(const [id,label] of views) {
      const button=bar.getByRole('button',{name:label,exact:true});
      await expect(button).toHaveAttribute('title',label);
      const box=await button.boundingBox();expect(box.x).toBeGreaterThanOrEqual(0);expect(box.x+box.width).toBeLessThanOrEqual(width);expect(box.width).toBeGreaterThanOrEqual(32);expect(box.height).toBeGreaterThanOrEqual(44);
      if(width<1024)await expect(button.locator('.nav-label')).not.toBeVisible();else await expect(button.locator('.nav-label')).toBeVisible();
      await button.click();await expect(button).toHaveAttribute('aria-current','page');await expect(page.locator('#'+id+'-panel')).toHaveJSProperty('hidden',false);
    }
    await page.evaluate(()=>scrollTo(0,document.documentElement.scrollHeight));
    await expect.poll(async()=>{const content=await page.locator('#labels-panel').boundingBox();return content.y+content.height;}).toBeLessThanOrEqual(bounds.y);
    expect((await bar.boundingBox()).y).toBeCloseTo(bounds.y,0);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    const audit=await new AxeBuilder({page}).include('#workspace-navigation').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
    const screenshot=await captureScreenshot(page,{path:`test-results/bottom-navigation-${width}.png`,fullPage:true});
    expect(screenshot.readUInt32BE(16)).toBe(width);expect(screenshot.readUInt32BE(20)).toBe(844);
  }
  const activities=bar.getByRole('button',{name:'Activities',exact:true});await activities.focus();await page.keyboard.press('Enter');await expect(activities).toHaveAttribute('aria-current','page');
  await page.keyboard.press(browserName==='webkit' && process.platform==='darwin' ? 'Alt+Tab':'Tab');await expect(bar.getByRole('button',{name:'Clients',exact:true})).toBeFocused();
  await page.setViewportSize({width:390,height:844});await openAbout(page);
  const aboutAudit=await new AxeBuilder({page}).include('#server-info').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(aboutAudit.violations).toEqual([]);
  await page.keyboard.press('Escape');await expect(page.getByRole('button',{name:'Account menu',exact:true})).toBeFocused();
  await openAccountMenu(page);await page.getByRole('link',{name:'My Account',exact:true}).click();await expect(bar).not.toBeVisible();
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await expect(bar).toBeVisible();
  await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(bar).not.toBeVisible();await expect(page.locator('#sync-status')).not.toBeVisible();
});
