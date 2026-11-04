import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {captureScreenshot} from './screenshots.js';

async function setup(page,request){
  const username='navigator-'+randomUUID().slice(0,8),password='Private navigator test passphrase';
  const headers={Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`};
  expect((await request.post('/api/v1/admin/users',{headers,data:{username,password,email:username+'@example.invalid',displayName:username,administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,extra={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...extra}});expect(reply.ok(),method+' '+path+': '+reply.status()).toBe(true);return reply;};
  await api('GET','/notes');const folder=await(await api('POST','/notes/folders',{path:'Notes'})).json();
  await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-title')).toBeVisible();return {api,folder};
}
const put=(api,path,text='Body.')=>api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(text),{'Content-Type':'application/octet-stream','If-None-Match':'*'});

test('navigator toolbar sorts complete paginated folders in all six orders without replacing a draft',async({page,request})=>{
  const {api,folder}=await setup(page,request);await api('POST','/notes/folders',{path:'Notes/Nested'});
  for(const name of ['Alpha','Beta','Zulu'])await put(api,'Notes/'+name+'.md');
  const dates={Alpha:['2022','2023'],Beta:['2021','2021'],Zulu:['2020','2022']},offsets=[];
  await page.route(/\/api\/v1\/notes\/entries\?/,async route=>{
    const response=await route.fetch(),body=await response.json(),query=new URL(route.request().url()).searchParams;
    for(const entry of body.items){const name=entry.path.split('/').at(-1).replace('.md','');if(dates[name]){entry.createdAt=dates[name][0]+'-01-01T00:00:00Z';entry.modifiedAt=dates[name][1]+'-01-01T00:00:00Z';}}
    if(query.get('parentId')===folder.id){const offset=Number(query.get('offset'));offsets.push(offset);if(body.items.length>1)body.nextOffset=offset+1;else delete body.nextOffset;body.items=body.items.slice(0,1);}
    await route.fulfill({response,json:body});
  });
  const navigation=page.locator('#notes-navigation');await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await navigation.getByRole('button',{name:'Notes',exact:true}).click();
  const rows=navigation.locator(`[role=treeitem][data-id="${folder.id}"] > [role=group] > [role=treeitem] > button`);
  await expect(rows).toHaveText(['Nested','Alpha.md','Beta.md','Zulu.md']);expect(new Set(offsets)).toEqual(new Set([0,1,2,3]));
  await navigation.getByRole('button',{name:'Notes/Alpha.md',exact:true}).click();await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#notes-body').fill('Unsaved navigator draft.');
  const toolbar=navigation.getByRole('toolbar',{name:'Note navigation actions'});await expect(toolbar.getByRole('group')).toHaveCount(3);
  const refresh=toolbar.getByRole('button',{name:'Refresh',exact:true}),trash=toolbar.getByRole('button',{name:'Recently deleted',exact:true});
  await expect(trash).toHaveCSS('color',await refresh.evaluate(node=>getComputedStyle(node).color));
  for(const [label,names]of [
    ['Name (Z–A)',['Zulu','Beta','Alpha']],['Name (A–Z)',['Alpha','Beta','Zulu']],
    ['Created (oldest first)',['Zulu','Beta','Alpha']],['Created (newest first)',['Alpha','Beta','Zulu']],
    ['Modified (oldest first)',['Beta','Zulu','Alpha']],['Modified (newest first)',['Alpha','Zulu','Beta']]
  ]){
    await toolbar.getByRole('button',{name:'Sort notes',exact:true}).click();await page.getByRole('menuitemradio',{name:label,exact:true}).click();
    await expect(rows).toHaveText(['Nested',...names.map(name=>name+'.md')]);await expect(page.locator('#notes-body')).toHaveValue('Unsaved navigator draft.');
    await expect(page.locator('.notes-footer').getByRole('button',{name:'Save',exact:true})).toBeEnabled();
  }
  const sort=toolbar.getByRole('button',{name:'Sort notes',exact:true});await sort.focus();await sort.press('ArrowDown');await expect(page.getByRole('menuitemradio',{name:'Modified (newest first)',exact:true})).toBeFocused();await page.keyboard.press('Escape');await expect(sort).toBeFocused();
  await sort.press('ArrowRight');await expect(toolbar.getByRole('button',{name:'Collapse all folders',exact:true})).toBeFocused();await page.keyboard.press('ArrowLeft');await expect(sort).toBeFocused();
  await page.locator('#notes-search').fill('.md');await expect(toolbar.getByRole('button',{name:'Expand all folders',exact:true})).toBeDisabled();
  await expect(navigation.locator('[role=treeitem] > button')).toHaveText(['Notes/Alpha.md','Notes/Zulu.md','Notes/Beta.md']);
});

test('expand and collapse cover nested folders, exclude a hidden journal and cancel stale expansion',async({page,request})=>{
  const {api,folder}=await setup(page,request);
  const nested=await(await api('POST','/notes/folders',{path:'Notes/Nested'})).json();await api('POST','/notes/folders',{path:'Notes/Nested/Deep'});await put(api,'Notes/Nested/Deep/Leaf.md');await put(api,'Notes/Draft.md');
  const settings=await api('GET','/notes/settings');await api('PUT','/notes/settings',{hideJournalFolder:true},{'If-Match':settings.headers().etag});
  const journal=await(await api('GET','/notes/resolve?path=journal')).json(),navigation=page.locator('#notes-navigation');
  await navigation.getByRole('button',{name:'Refresh',exact:true}).click();await navigation.getByRole('button',{name:'Notes',exact:true}).click();await navigation.getByRole('button',{name:'Notes/Draft.md',exact:true}).click();
  await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#notes-body').fill('Retain this draft.');
  let release,blocked=false;const hold=new Promise(resolve=>{release=resolve;}),parents=[];
  await page.route(/\/api\/v1\/notes\/entries\?/,async route=>{const parent=new URL(route.request().url()).searchParams.get('parentId');parents.push(parent);if(parent===nested.id){blocked=true;await hold;}await route.continue();});
  await navigation.getByRole('button',{name:'Expand all folders',exact:true}).click();
  try{await expect.poll(()=>blocked).toBe(true);}catch(error){release();throw error;}
  await navigation.getByRole('button',{name:'Collapse all folders',exact:true}).click();release();
  const root=navigation.locator(`[role=treeitem][data-id="${folder.id}"]`);await expect(root).toHaveAttribute('aria-expanded','false');await expect(navigation.getByRole('button',{name:'Notes/Nested/Deep/Leaf.md',exact:true})).toHaveCount(0);
  await page.unroute(/\/api\/v1\/notes\/entries\?/);await navigation.getByRole('button',{name:'Expand all folders',exact:true}).click();await expect(navigation.getByRole('button',{name:'Notes/Nested/Deep/Leaf.md',exact:true})).toBeVisible();
  for(const row of await navigation.locator('[role=treeitem][aria-expanded]').all())await expect(row).toHaveAttribute('aria-expanded','true');
  expect(parents).not.toContain(journal.id);await expect(page.locator('#notes-body')).toHaveValue('Retain this draft.');
});

test('navigator toolbar and sort menu fit desktop and mobile themes with the shared 14px default',async({page,request})=>{
  await setup(page,request);const navigation=page.locator('#notes-navigation');
  await expect(page.locator('html')).toHaveCSS('font-size','14px');await expect(page.locator('body')).toHaveCSS('font-size','14px');
  for(const width of [1440,390,320]){
    await page.setViewportSize({width,height:900});if(width<760)await page.locator('.notes-drawer-toggle').click();
    await expect(navigation).toBeVisible();expect(await navigation.evaluate(node=>node.scrollWidth<=node.clientWidth)).toBe(true);
    const tops=await navigation.locator('.notes-navigation-tools > .btn-group').evaluateAll(groups=>groups.map(group=>group.getBoundingClientRect().top));expect(Math.max(...tops)-Math.min(...tops)).toBeLessThan(2);
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=['timekeeper','solarized-light'].includes(theme)?'light':'dark';},theme);
      await navigation.getByRole('button',{name:'Sort notes',exact:true}).click();expect((await new AxeBuilder({page}).include('#notes-navigation').analyze()).violations).toEqual([]);
      const box=await page.getByRole('menu',{name:'Sort notes',exact:true}).boundingBox();expect(box.x).toBeGreaterThanOrEqual(0);expect(box.x+box.width).toBeLessThanOrEqual(width);
      await page.keyboard.press('Escape');
    }
    await captureScreenshot(page,{path:`test-results/notes-navigator-${width}.png`,fullPage:false});
    if(width<760)await page.locator('.notes-drawer-toggle').click();
  }
});

test('read-only navigation can sort and expand folders without issuing collection writes',async({page,request})=>{
  const headers={Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},path='Read-only-'+randomUUID().slice(0,8);
  expect((await request.get('/api/v1/notes',{headers})).ok()).toBe(true);
  for(const name of [path,path+'/Nested'])expect((await request.post('/api/v1/notes/folders',{headers,data:{path:name}})).status()).toBe(201);
  expect((await request.put('/api/v1/notes/files?'+new URLSearchParams({path:path+'/Nested/Read.md'}),{headers:{...headers,'If-None-Match':'*','Content-Type':'application/octet-stream'},data:Buffer.from('Read-only note.')})).ok()).toBe(true);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_READ_TOKEN);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');await page.locator('[data-tab=notes]').click();
  const writes=[];page.on('request',request=>{if(request.url().includes('/api/v1/notes')&&request.method()!=='GET')writes.push(request.method());});
  const navigation=page.locator('#notes-navigation');await expect(navigation.getByRole('button',{name:'New note',exact:true})).toBeDisabled();
  await navigation.getByRole('button',{name:'Expand all folders',exact:true}).click();await expect(navigation.getByRole('button',{name:path+'/Nested/Read.md',exact:true})).toBeVisible();
  await navigation.getByRole('button',{name:'Sort notes',exact:true}).click();await page.getByRole('menuitemradio',{name:'Name (Z–A)',exact:true}).click();
  await navigation.getByRole('button',{name:'Collapse all folders',exact:true}).click();await expect(navigation.getByRole('button',{name:path+'/Nested/Read.md',exact:true})).toHaveCount(0);expect(writes).toEqual([]);
});
