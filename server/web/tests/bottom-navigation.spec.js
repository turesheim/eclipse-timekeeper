import {tooltipFor} from './tooltips.js';
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
  const views=[['today','Today'],['track','Calendar'],['calendar','Week'],['week','Workweek'],['activities','Activities'],['clients','Clients'],['projects','Projects'],['notes','Notes']];
  await expect(page.locator('[data-tab=labels]')).toHaveCount(0);
  await expect(page.locator('[data-tab=tasks],#tasks-panel,#activity-tasks,#tasks,#add-task')).toHaveCount(0);
  expect(await bar.locator('button').evaluateAll(nodes=>nodes.map(node=>node.dataset.tab))).toEqual(views.map(([id])=>id));
  for(const width of [320,390,768,1440]) {
    await page.setViewportSize({width,height:844});
    await expect(bar.locator('button')).toHaveCount(8);await expect(bar.locator('.bi[aria-hidden=true]')).toHaveCount(8);
    const brand=await page.locator('.brand').boundingBox(),status=await page.locator('#sync-status').boundingBox();expect(status.x).toBeGreaterThanOrEqual(brand.x+brand.width);expect(Math.abs(status.y+status.height/2-brand.y-brand.height/2)).toBeLessThan(1);
    const bounds=await bar.boundingBox();expect(bounds.y+bounds.height).toBeCloseTo(844,0);
    for(const [id,label] of views) {
      const button=bar.getByRole('button',{name:label,exact:true});
      await expect(await tooltipFor(button)).toContainText(label);
      const box=await button.boundingBox();expect(box.x).toBeGreaterThanOrEqual(0);expect(box.x+box.width).toBeLessThanOrEqual(width);expect(box.width).toBeGreaterThanOrEqual(32);expect(box.height).toBeGreaterThanOrEqual(44);
      if(width<1024)await expect(button.locator('.nav-label')).not.toBeVisible();else await expect(button.locator('.nav-label')).toBeVisible();
      await button.click();await expect(button).toHaveAttribute('aria-current','page');await expect(page.locator('#'+id+'-panel')).toHaveJSProperty('hidden',false);
    }
    await page.locator('[data-tab=projects]').click();
    await page.evaluate(()=>scrollTo(0,document.documentElement.scrollHeight));
    await expect.poll(async()=>{const content=await page.locator('#projects-panel').boundingBox();return content.y+content.height;}).toBeLessThanOrEqual(bounds.y);
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

test('bottom navigation uses compact desktop spacing while preserving touch safe areas at every width',async({browser,browserName})=>{
  for(const [touch,width] of [[false,1440],[false,390],[true,390],[true,1440]]){
    const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true,viewport:{width,height:900},hasTouch:touch,isMobile:touch&&browserName!=='firefox',locale:'en-GB'});
    try{
      const page=await context.newPage();await page.goto('/web/');
      if(browserName==='chromium'){
        const protocol=await context.newCDPSession(page);
        await protocol.send('Emulation.setSafeAreaInsetsOverride',{insets:{bottom:34}});
      }
      const inset=await page.evaluate(()=>{
        const probe=document.createElement('div');probe.style.height='env(safe-area-inset-bottom, 0px)';document.body.append(probe);const value=probe.getBoundingClientRect().height;probe.remove();return value;
      });
      if(browserName==='chromium')expect(inset).toBe(34);
      expect(await page.evaluate(()=>matchMedia('(any-pointer: coarse)').matches)).toBe(touch);
      await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_TOKEN);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
      const bar=page.locator('#workspace-navigation');await expect(bar).toBeVisible();
      await captureScreenshot(page,{path:`test-results/bottom-navigation-inset-${touch?'touch':'desktop'}-${width}.png`});
      const bounds=await bar.boundingBox(),target=await bar.getByRole('button',{name:'Today',exact:true}).boundingBox();
      expect(bounds.height).toBeCloseTo(64+(touch?inset:0),0);expect(bounds.y+bounds.height).toBeCloseTo(900,0);
      const above=target.y-bounds.y,below=bounds.y+bounds.height-target.y-target.height;
      const borders=await bar.evaluate(value=>{const style=getComputedStyle(value);return parseFloat(style.borderTopWidth)-parseFloat(style.borderBottomWidth);});
      expect(below-above).toBeCloseTo((touch?inset:0)-borders,0);expect(target.height).toBeGreaterThanOrEqual(44);
      for(const name of ['Today','Calendar','Week','Workweek','Activities','Clients','Projects'])await expect(bar.getByRole('button',{name,exact:true})).toBeVisible();
      if(width>=1024){
        await bar.getByRole('button',{name:'Today',exact:true}).click();
        await expect.poll(async()=>{const note=await page.locator('#today-note-editor').boundingBox(),nav=await bar.boundingBox();return Math.abs(nav.y-note.y-note.height-8);}).toBeLessThan(2);
        await bar.getByRole('button',{name:'Week',exact:true}).click();
        await expect.poll(async()=>{const calendar=await page.locator('#calendar-panel .calendar-scroll').boundingBox(),nav=await bar.boundingBox();return Math.abs(nav.y-calendar.y-calendar.height-8);}).toBeLessThan(2);
      }
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    }finally{await context.close();}
  }
});
