import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {openAccountMenu} from './navigation.js';

test('bottom navigation keeps every view accessible, stationary and clear of content at narrow and wide widths',async({page,browserName})=>{
  await page.goto('/web/');const bar=page.locator('#workspace-navigation');await expect(bar).not.toBeVisible();
  await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_TOKEN);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  const views=[['track','Track'],['week','Workweek'],['activities','Activities'],['calendar','Calendar'],['tasks','Tasks'],['clients','Clients'],['projects','Projects'],['labels','Labels']];
  for(const width of [320,390,768,1440]) {
    await page.setViewportSize({width,height:844});
    await expect(bar.locator('button')).toHaveCount(8);await expect(bar.locator('.icon[aria-hidden=true]')).toHaveCount(8);
    const bounds=await bar.boundingBox();expect(bounds.y+bounds.height).toBeCloseTo(844,0);
    for(const [id,label] of views) {
      const button=bar.getByRole('button',{name:label,exact:true});
      await expect(button).toHaveAttribute('title',label);
      const box=await button.boundingBox();expect(box.x).toBeGreaterThanOrEqual(0);expect(box.x+box.width).toBeLessThanOrEqual(width);expect(box.width).toBeGreaterThanOrEqual(32);expect(box.height).toBeGreaterThanOrEqual(44);
      if(width<1024)await expect(button.locator('.nav-label')).not.toBeVisible();else await expect(button.locator('.nav-label')).toBeVisible();
      await button.click();await expect(button).toHaveAttribute('aria-current','page');await expect(page.locator('#'+id+'-panel')).toBeVisible();
    }
    await page.evaluate(()=>scrollTo(0,document.documentElement.scrollHeight));
    const footer=await page.locator('#server-info').boundingBox();expect(footer.y+footer.height).toBeLessThanOrEqual(bounds.y);
    expect((await bar.boundingBox()).y).toBeCloseTo(bounds.y,0);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    const audit=await new AxeBuilder({page}).include('#workspace-navigation').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
    await page.screenshot({path:`test-results/bottom-navigation-${width}.png`,fullPage:true});
  }
  const activities=bar.getByRole('button',{name:'Activities',exact:true});await activities.focus();await page.keyboard.press('Enter');await expect(activities).toHaveAttribute('aria-current','page');
  await page.keyboard.press(browserName==='webkit' && process.platform==='darwin' ? 'Alt+Tab':'Tab');await expect(bar.getByRole('button',{name:'Calendar',exact:true})).toBeFocused();
  await openAccountMenu(page);await page.getByRole('link',{name:'My Account',exact:true}).click();await expect(bar).not.toBeVisible();
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await expect(bar).toBeVisible();
  await openAccountMenu(page);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(bar).not.toBeVisible();
});
