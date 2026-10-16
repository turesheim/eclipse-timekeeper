import {test, expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {randomUUID} from 'node:crypto';

const token = process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request, method, path, data, headers={}) {
  const response = await request.fetch(`/api/v1${path}`, {method, data, headers:{Authorization:`Bearer ${token}`, ...headers}});
  expect(response.ok(), `${method} ${path}: ${response.status()}`).toBeTruthy();
  return response;
}
async function accessible(page) {
  const result = await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();
  expect(result.violations.map(({id,nodes}) => ({id, targets:nodes.map(node=>node.target)}))).toEqual([]);
}
async function iconTargets(page) {
  for(const button of await page.locator('.icon-button:visible,.tree-toggle:visible').all()) {
    expect(await button.getAttribute('aria-label')).toBeTruthy();
    expect(await button.getAttribute('title')).toBeTruthy();
    const box=await button.boundingBox();expect(box.width).toBeGreaterThanOrEqual(44);expect(box.height).toBeGreaterThanOrEqual(44);
  }
}
async function fixture(request) {
  const records = [], profile = await api(request,'GET','/me'), previous = await profile.json();
  await api(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag});
  async function create(collection, data) {
    const value = await (await api(request,'POST',`/${collection}`,data)).json();
    records.unshift([collection,value.id]); return value;
  }
  const client = await create('clients',{name:'Northwind Studio',color:'#6750a4'});
  const project = await create('projects',{name:'Website refresh',clientId:client.id});
  const task = await create('tasks',{summary:'Plan the new website',projectId:project.id,parentTaskId:null,url:null});
  const session = await create('work-sessions',{title:'Website planning',clientId:client.id,summary:'Design workshop and project planning',start:'2025-10-20T06:00:00Z',end:'2025-10-20T14:00:00Z'});
  await create('activities',{taskId:task.id,summary:'Design workshop',start:'2025-10-20T06:30:00Z',end:'2025-10-20T09:00:00Z',workSessionId:session.id,labelIds:[]});
  await create('activities',{taskId:task.id,summary:'Project planning',start:'2025-10-20T10:00:00Z',end:'2025-10-20T12:00:00Z',workSessionId:session.id,labelIds:[]});
  return {session, async close() {
    for(const [collection,id] of records) {
      const value = await api(request,'GET',`/${collection}/${id}`);
      await api(request,'DELETE',`/${collection}/${id}`,undefined,{'If-Match':value.headers().etag});
    }
    const value = await api(request,'GET','/me');
    await api(request,'PUT','/me',{zoneId:previous.zoneId},{'If-Match':value.headers().etag});
  }};
}
async function signIn(page) {
  await page.goto('/web/'); await page.locator('#auth-mode').selectOption('token');
  await page.getByLabel('Access token').fill(token);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.locator('#status')).toHaveText('Up to date');
}

for(const [name,width,height] of [['desktop',1440,1100],['narrow',390,844]]) {
  test(`Bootstrap design is accessible and keeps every view usable on ${name} screens`,async({page,request,browserName})=>{
    test.setTimeout(120000);
    const f = await fixture(request), external = [], violations = [];
    page.on('request',request=>{if(new URL(request.url()).origin!==new URL(process.env.TIMEKEEPER_WEB_URL).origin) external.push(request.url());});
    await page.addInitScript(()=>{window.designPolicyViolations=[];document.addEventListener('securitypolicyviolation',event=>window.designPolicyViolations.push({directive:event.violatedDirective,blocked:event.blockedURI}));});
    try {
      await page.setViewportSize({width,height});
      await page.goto('/web/');
      for(const mode of ['login','setup','recover','token']) {
        await page.locator('#auth-mode').selectOption(mode); await accessible(page);
      }
      await page.screenshot({path:`test-results/design-sign-in-${name}.png`,fullPage:true});
      await signIn(page); await page.locator('#week-date').fill('2025-10-20');await page.locator('#week-date').blur();
      await expect(page.locator('#zone-caption')).toContainText('2025-10-20');
      await expect(page.locator('#work-session-history')).not.toHaveAttribute('open','');
      await page.locator('#work-session-history > summary').click();
      await page.locator('#work-session-choice').selectOption(f.session.id);
      await expect(page.locator('#selected-session-title')).toHaveText('Website planning');
      await expect(page.locator('#ws-duration')).toHaveText('8h 0m');
      await expect(page.locator('#work-session')).toHaveCSS('border-top-color','rgb(103, 80, 164)');
      await page.mouse.move(0,0);
      await page.screenshot({path:`test-results/design-work-session-${name}.png`,fullPage:true});
      await page.locator('#work-session').screenshot({path:`test-results/design-session-card-${name}.png`});
      await page.locator('#work-session-history > summary').click();
      for(const tab of ['week','calendar','tasks','clients','projects','labels']) {
        await page.locator(`[data-tab=${tab}]`).click();
        await expect(page.locator(`[data-tab=${tab}]`)).toHaveAttribute('aria-current','page');
        await expect(page.locator('#work-session')).toBeVisible();
        await expect(page.locator('#work-session-choice')).not.toBeVisible();
        await expect(page.locator('#work-session')).not.toContainText('No associated activities');
        expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
        await accessible(page);
        await iconTargets(page);
        if(tab!=='week' && tab!=='calendar') {
          for(const action of await page.locator(`#${tab} td.actions button`).all()) {
            const rect=await action.boundingBox();expect(rect.x+rect.width).toBeLessThanOrEqual(width);
          }
        }
        await page.screenshot({path:`test-results/design-${tab}-${name}.png`,fullPage:true});
      }
      await page.getByRole('button',{name:'Account',exact:true}).click();
      await expect(page.locator('#account-content')).toHaveAttribute('aria-busy','false');
      await accessible(page); await page.screenshot({path:`test-results/design-account-${name}.png`,fullPage:true});
      await page.keyboard.press('Escape');
      await expect(page.locator('#account-dialog')).not.toBeVisible();
      violations.push(...await page.evaluate(()=>window.designPolicyViolations));
      expect(external).toEqual([]);
      // macOS WebKit reports inline styles from its native select controls and
      // Playwright screenshots, reproducible with plain HTML and no application
      // scripts or CSS. Still reject every blocked design-asset request. The
      // server policy stays unchanged, including style-src 'self'.
      expect(browserName==='webkit' ? violations.filter(value=>value.blocked!=='inline') : violations).toEqual([]);
    } finally {await f.close();}
  });
}

test('narrow-screen editors support keyboard focus, validation and visible errors',async({page,browserName})=>{
  await page.setViewportSize({width:390,height:844}); await signIn(page);
  const clients=page.locator('[data-tab=clients]');await clients.focus();await page.keyboard.press('Enter');
  const create=page.getByRole('button',{name:'New client',exact:true});await create.focus();await page.keyboard.press('Enter');
  const name=page.getByLabel('Name',{exact:true});await expect(name).toBeFocused();
  await page.locator('#save').click();await expect(name).toBeFocused();
  expect(await name.evaluate(input=>input.validity.valueMissing)).toBeTruthy();
  await name.fill('Keyboard draft '+randomUUID());
  await page.route('**/api/v1/clients',route=>route.request().method()==='POST' ? route.fulfill({status:409,json:{code:'CONFLICT'}}) : route.continue());
  await page.locator('#save').click();await expect(page.locator('#editor-error')).toContainText('conflicts');
  await expect(name).not.toHaveValue('');await accessible(page);
  await page.screenshot({path:'test-results/design-validation-narrow.png',fullPage:true});
  await page.locator('#save').focus();
  await page.keyboard.press(browserName==='webkit' && process.platform==='darwin' ? 'Alt+Shift+Tab' : 'Shift+Tab');
  await expect(page.locator('#cancel')).toBeFocused();
  expect(await page.locator('#cancel').evaluate(button=>getComputedStyle(button).outlineStyle)).toBe('solid');
  await page.keyboard.press('Enter');await expect(page.locator('#editor')).not.toBeVisible();await expect(create).toBeFocused();
});
