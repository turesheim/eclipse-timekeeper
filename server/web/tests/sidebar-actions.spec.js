import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {navigateView,setTheme} from './navigation.js';
import {captureScreenshot} from './screenshots.js';

async function signIn(page){
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_TOKEN);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveAttribute('data-state','ready');
}
async function api(request,method,path,data,headers={}){
  const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`,...headers}});expect(response.ok()).toBe(true);return response;
}

test('Main actions offer controls only for sidebars present in the view',async({page})=>{
  await page.setViewportSize({width:1440,height:1000});await signIn(page);
  for(const present of [[],['left'],['right'],['left','right']]){
    await page.evaluate(async sides=>{
      const {SidebarActions}=await import('/web/sidebar-actions.js');
      const layout=document.createElement('section'),host=document.createElement('div'),panes={};layout.id='sidebar-fixture';host.id='fixture-main-actions';host.setAttribute('role','group');host.setAttribute('aria-label','Example main actions');layout.append(host);
      for(const side of sides){const panel=document.createElement('aside');panel.id='fixture-'+side;panel.textContent=side+' content';layout.append(panel);panes[side]={panel};}
      document.querySelector('main').append(layout);new SidebarActions(host,layout,panes);
    },present);
    const fixture=page.locator('#sidebar-fixture'),buttons=fixture.getByRole('button');await expect(buttons).toHaveCount(present.length);
    for(const side of present){
      await fixture.getByRole('button',{name:'Collapse '+side+' sidebar',exact:true}).click();await expect(fixture.locator('#fixture-'+side)).toBeHidden();
      await fixture.getByRole('button',{name:'Expand '+side+' sidebar',exact:true}).click();await expect(fixture.locator('#fixture-'+side)).toBeVisible();
    }
    await fixture.evaluate(node=>node.remove());
  }
});

for(const locale of ['en-GB','nb-NO'])test(`Main actions independently toggle Today sidebars without losing the journal in ${locale}`,async({browser,request})=>{
  test.setTimeout(90000);
  const date='2026-07-11',path='/day-notes/'+date+'/content',original=await api(request,'GET',path),body=(await original.json()).body;
  await api(request,'PUT',path,{body:'# Sidebar heading\n\nKeep the journal visible.\n\n## Next heading\n'},{'If-Match':original.headers().etag});
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true,locale,viewport:{width:1440,height:1000}}),page=await context.newPage();
  try{
    await signIn(page);await page.locator('#today-date').fill(date);await expect(page.locator('#today-contents-list button')).toHaveCount(2);
    const left=page.locator('#today-sidebar'),right=page.locator('#today-contents'),resize=page.locator('.today-resize'),editor=page.locator('#today-note-editor'),source=page.locator('#today-journal-body');
    const toggles={left:page.locator('#today-main-actions-toggle-left-sidebar'),right:page.locator('#today-main-actions-toggle-right-sidebar')};
    await expect(toggles.left).toHaveAttribute('aria-controls','today-sidebar');await expect(toggles.right).toHaveAttribute('aria-controls','today-contents');
    const set=async(side,expanded)=>{if(await toggles[side].getAttribute('aria-expanded')!==String(expanded))await toggles[side].click();};
    await resize.focus();await resize.press('End');const leftWidth=(await left.boundingBox()).width;
    const saved=await api(request,'GET',path);
    for(const theme of ['timekeeper','timekeeper-dark']){
      await setTheme(page,theme);
      for(const width of [1440,390,320]){
        await page.setViewportSize({width,height:1000});await set('left',true);await set('right',true);
        const initialWidth=(await page.locator('.today-main').boundingBox()).width;
        for(const [showLeft,showRight] of [[true,true],[false,true],[true,false],[false,false]]){
          await set('left',showLeft);await set('right',showRight);
          for(const [side,shown,panel] of [['left',showLeft,left],['right',showRight,right]]){
            await expect(toggles[side]).toHaveAttribute('aria-expanded',String(shown));
            const label=locale==='nb-NO'?(shown?'Skjul ':'Vis ')+(side==='left'?'venstre':'høyre')+' sidepanel':(shown?'Collapse ':'Expand ')+side+' sidebar';
            await expect(toggles[side]).toHaveAccessibleName(label);if(shown)await expect(panel).toBeVisible();else await expect(panel).toBeHidden();
          }
          if(width===1440){
            if(showLeft)await expect(resize).toBeVisible();else await expect(resize).toBeHidden();
            if(!showLeft||!showRight)expect((await page.locator('.today-main').boundingBox()).width).toBeGreaterThan(initialWidth);
          }else await expect(resize).toBeHidden();
          expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
          const journal=await editor.boundingBox(),dock=await page.locator('#workspace-navigation').boundingBox();expect(journal.y+journal.height).toBeLessThanOrEqual(dock.y+1);
        }
        expect((await new AxeBuilder({page}).include('#today-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
        await captureScreenshot(page,{path:`test-results/sidebar-controls-${locale}-${theme}-${width}.png`});
      }
    }
    expect((await api(request,'GET',path)).headers().etag).toBe(saved.headers().etag);
    await editor.getByRole('button',{name:locale==='nb-NO'?'Markdown-kilde':'Markdown source',exact:true}).click();const draft=(await source.inputValue())+'\nDraft survives sidebar changes.\n';await source.fill(draft);
    await set('left',true);await set('left',false);await set('right',true);await set('right',false);await expect(source).toHaveValue(draft);
    await toggles.left.focus();await toggles.left.press('ControlOrMeta+s');await expect.poll(async()=>(await(await api(request,'GET',path)).json()).body).toBe(draft);
    await navigateView(page,'track');await navigateView(page,'today');await expect(toggles.left).toHaveAttribute('aria-expanded','false');await expect(toggles.right).toHaveAttribute('aria-expanded','false');await expect(source).toHaveValue(draft);
    await page.setViewportSize({width:1440,height:1000});await expect(toggles.left).toHaveAttribute('aria-expanded','false');await expect(toggles.right).toHaveAttribute('aria-expanded','false');
    await set('left',true);expect((await left.boundingBox()).width).toBeCloseTo(leftWidth,0);
    await set('right',true);await expect(right.locator('ol button').first()).toHaveText('Sidebar heading');
    await toggles.left.focus();await toggles.left.press('Space');await expect(left).toBeHidden();await expect(toggles.left).toBeFocused();await toggles.left.press('Enter');await expect(left).toBeVisible();
  }finally{await context.close();const current=await api(request,'GET',path);await api(request,'PUT',path,{body},{'If-Match':current.headers().etag});}
});
