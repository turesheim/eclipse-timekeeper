import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {captureScreenshot} from './screenshots.js';
import {setTheme,openThemeSettings} from './navigation.js';

// Inspect actual rendered controls, including client swatches and pseudo-elements.
// This catches a colored Bootstrap state or mixed tint escaping the two-ink theme.
async function twoInks(page) {
  const invalid=await page.evaluate(()=>{
    const canvas=document.createElement('canvas'),context=canvas.getContext('2d'),failures=[];
    canvas.width=canvas.height=1;
    function allowed(value) {
      context.clearRect(0,0,1,1);context.fillStyle=value;context.fillRect(0,0,1,1);
      const [r,g,b,a]=context.getImageData(0,0,1,1).data;
      return a===0 || a===255 && (r===0 && g===0 && b===0 || r===250 && g===249 && b===246 || r===255 && g===255 && b===255);
    }
    for(const element of document.body.querySelectorAll('*')) {
      if(!element.checkVisibility() || !element.getBoundingClientRect().width)continue;
      for(const pseudo of [null,'::before','::after']) {
        const style=getComputedStyle(element,pseudo);
        if(pseudo && ['none','normal'].includes(style.content))continue;
        const properties=['color','backgroundColor'];
        for(const side of ['Top','Right','Bottom','Left'])if(parseFloat(style['border'+side+'Width']))properties.push('border'+side+'Color');
        if(style.outlineStyle!=='none')properties.push('outlineColor');
        for(const property of properties)if(!allowed(style[property]))failures.push({element:element.id||element.className,pseudo,property,value:style[property]});
      }
    }
    return failures;
  });
  expect(invalid).toEqual([]);
}
async function review(page,name) {
  await twoInks(page);
  const result=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();
  expect(result.violations.map(({id,nodes})=>({id,targets:nodes.map(node=>node.target)}))).toEqual([]);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.mouse.move(0,0);
  await captureScreenshot(page,{path:`test-results/newspaper-${name}.png`,fullPage:true});
}

test('Newspaper prints records, journals and controls in two inks with serif type on desktop and mobile',async({page,request,context})=>{
  test.setTimeout(150000);
  const records=[],date='2025-10-20',headers={Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`};
  async function api(method,path,data,extra={}) {
    const response=await request.fetch('/api/v1'+path,{method,data,headers:{...headers,...extra}});
    expect(response.ok(),`${method} ${path}: ${response.status()}`).toBe(true);return response;
  }
  async function create(collection,data) {
    const value=await(await api('POST','/'+collection,data)).json();records.unshift([collection,value.id]);return value;
  }
  const note=await api('GET',`/day-notes/${date}/content`),original=(await note.json()).body;
  try {
    const client=await create('clients',{name:'The Morning Edition',color:'#db2244'});
    const project=await create('projects',{name:'Daily dispatch',clientId:client.id,icon:'newspaper'});
    const task=await create('tasks',{projectId:project.id,parentTaskId:null,url:null,summary:'Prepare the next edition'});
    const session=await create('work-sessions',{clientId:client.id,projectId:project.id,summary:'Editorial desk',start:date+'T07:00:00Z',end:date+'T14:30:00Z'});
    await create('activities',{taskId:task.id,workSessionId:session.id,summary:'Research and writing',start:date+'T08:00:00Z',end:date+'T11:00:00Z',labelIds:[]});
    const body='# The day’s work, in print\n\nA quiet place to gather the day’s observations, record progress, and plan what comes next.\n\n## From the editorial desk\n\nThe morning began with research and a fresh page. By noon, the first draft was ready for review.\n\n> “Make every word count.”\n\n## In this edition\n\n- Review the morning’s notes\n- Bring the daily dispatch together\n- Leave a clear plan for tomorrow\n\n---\n\n**On the record** · A well-spent day, one entry at a time.\n';
    await api('PUT',`/day-notes/${date}/content`,{body},{'If-Match':note.headers().etag});
    await page.setViewportSize({width:1440,height:1000});
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_TOKEN);
    await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await setTheme(page,'newspaper');await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(date);
    await expect(page.locator('#today-journal-formatted')).toContainText('The day’s work, in print');
    await expect(page.locator('body')).toHaveCSS('font-family',/Georgia/);
    await expect(page.locator('#today-journal-formatted .ProseMirror')).toHaveCSS('font-family',/Georgia/);
    await review(page,'today-desktop');
    await page.reload();await expect(page.locator('html')).toHaveAttribute('data-theme','newspaper');
    await page.locator('[data-tab=track]').click();await page.locator('#track-month').fill('2025-10');
    await expect(page.locator('.track-client-day').first()).toHaveCSS('border-left-color','rgb(0, 0, 0)');
    await review(page,'calendar-desktop');
    await page.locator('[data-tab=calendar]').click();await page.locator('#calendar-date').fill(date);await review(page,'week-desktop');
    await expect(page.locator('.calendar-day.weekend').first()).toHaveCSS('background-image',/conic-gradient/);
    await page.locator('[data-tab=clients]').click();await page.getByRole('button',{name:'New client',exact:true}).click();
    await page.getByLabel('Name',{exact:true}).fill('The Sunday Review');await review(page,'editor');
    const name=page.getByLabel('Name',{exact:true});await name.focus();await page.keyboard.press('Tab');
    await expect(page.getByLabel('Color',{exact:true})).toBeFocused();
    await page.locator('#cancel').click();
    await openThemeSettings(page);await review(page,'account');
    // Switching back restores the real client color; the theme never rewrites it.
    await setTheme(page,'timekeeper');await page.locator('[data-tab=track]').click();
    await expect(page.locator('.track-client-day').first()).toHaveCSS('border-left-color','rgb(219, 34, 68)');
    expect((await(await api('GET','/clients/'+client.id)).json()).color).toBe('#db2244');
    await setTheme(page,'newspaper');await page.locator('[data-tab=today]').click();await page.locator('#today-date').fill(date);
    await page.setViewportSize({width:390,height:844});await review(page,'today-mobile');
    await page.setViewportSize({width:320,height:844});await review(page,'today-320');
    const other=await context.newPage();await other.setViewportSize({width:1440,height:1000});await other.goto('/admin/');await review(other,'administration');await other.close();
    const signedOut=await context.browser().newContext({baseURL:process.env.TIMEKEEPER_WEB_URL});
    const login=await signedOut.newPage();await login.addInitScript(()=>localStorage.setItem('timekeeper.theme','newspaper'));await login.goto('/web/');await review(login,'sign-in');await signedOut.close();
  } finally {
    const current=await api('GET',`/day-notes/${date}/content`);await api('PUT',`/day-notes/${date}/content`,{body:original},{'If-Match':current.headers().etag});
    for(const [collection,id] of records) {const current=await api('GET','/'+collection+'/'+id);await api('DELETE','/'+collection+'/'+id,undefined,{'If-Match':current.headers().etag});}
  }
});
