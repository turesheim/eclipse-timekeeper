import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {setTheme} from './navigation.js';
import {browserRequest} from './browser-request.js';

test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin};const status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function setup(page,request){
  const username='notes-'+randomUUID().slice(0,8),password='Private collection test password';
  const created=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});expect(created.status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page);const identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;};
  await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-title')).toBeVisible();return api;
}
test('Notes preserves exact source, keeps drafts across tabs and rejects stale saves',async({page,request})=>{
  const api=await setup(page,request);const path='Notes/Exact.md',original='---\r\nunknown: [x, true]\r\n---\r\n[[Other note]]  \r\n![[image.png]]\r\n';
  const created=await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(original),{'Content-Type':'application/octet-stream','If-None-Match':'*'});const entry=await created.json();
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();
  await page.locator('#notes-navigation').getByRole('button',{name:'Notes',exact:true}).click();await page.locator('#notes-navigation').getByRole('button',{name:path,exact:true}).click();
  await expect(page.locator('#notes-body')).toBeVisible();await expect(page.locator('#notes-body')).toHaveValue('[[Other note]]  \n![[image.png]]\n');
  await expect(page.locator('#notes-panel').getByRole('button',{name:'Save',exact:true})).toBeDisabled();
  const unedited=await api('GET','/notes/entries/'+entry.id+'/content');expect(Buffer.from(await unedited.body()).equals(Buffer.from(original))).toBe(true);
  await page.locator('#notes-body').fill('Draft kept in memory');await page.locator('[data-tab=today]').click();await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-body')).toHaveValue('Draft kept in memory');
  await page.locator('#notes-panel').getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('.notes-footer')).toContainText('Saved');
  await page.locator('#notes-body').fill('Concurrent local draft');
  const current=await api('GET','/notes/entries/'+entry.id+'/content');await api('PUT','/notes/entries/'+entry.id+'/content',Buffer.from('Remote edit'),{'Content-Type':'application/octet-stream','If-Match':current.headers().etag});
  await page.locator('.notes-footer button').evaluate(button=>{if(!button.disabled)button.click();});await expect(page.locator('#notes-panel .alert').first()).toBeVisible();await expect(page.locator('#notes-body')).toHaveValue('Concurrent local draft');
  expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('Remote edit');
  const violations=await new AxeBuilder({page}).include('#notes-panel').analyze();expect(violations.violations).toEqual([]);
});
test('Notes supports nested creation, keyboard resize, rename and recovery on mobile',async({page,request})=>{
  await setup(page,request);await page.locator('.notes-resize').focus();const before=await page.locator('.notes-resize').getAttribute('aria-valuenow');await page.keyboard.press('ArrowRight');expect(await page.locator('.notes-resize').getAttribute('aria-valuenow')).not.toBe(before);
  await page.locator('#notes-panel').getByRole('button',{name:'New note',exact:true}).click();await page.locator('#notes-path').fill('Notes/New.md');await page.getByRole('dialog',{name:'New note',exact:true}).getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#notes-document-title')).toHaveText('Notes/New.md');
  await page.locator('#notes-body').fill('Body');await page.locator('.notes-footer').getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('.notes-footer')).toContainText('Saved');
  await page.getByRole('button',{name:'Rename or move',exact:true}).click();await page.locator('#notes-path').fill('Notes/Renamed.md');await page.getByRole('dialog',{name:'Rename or move',exact:true}).getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#notes-document-title')).toHaveText('Notes/Renamed.md');
  page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Delete file or folder',exact:true}).click();await expect(page.locator('#notes-body')).not.toBeVisible();
  await page.locator('#notes-panel').getByRole('button',{name:'Recently deleted',exact:true}).click();await page.locator('#notes-navigation').getByRole('button',{name:'Notes/Renamed.md',exact:true}).click();await page.getByRole('button',{name:'Restore deleted item',exact:true}).click();await expect(page.locator('#notes-body')).toHaveValue('Body');
  await page.setViewportSize({width:390,height:844});await expect(page.locator('#notes-navigation')).not.toBeVisible();await page.locator('.notes-drawer-toggle').click();await expect(page.locator('#notes-navigation')).toBeVisible();await page.locator('#notes-search').fill('Renamed');await expect(page.locator('#notes-navigation').getByRole('button',{name:'Notes/Renamed.md',exact:true})).toBeVisible();
  expect(await page.locator('#workspace-navigation [data-tab]').evaluateAll(buttons=>buttons.every(button=>Math.abs(button.getBoundingClientRect().top-buttons[0].getBoundingClientRect().top)<1))).toBe(true);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
test('Notes settings preserve journal associations and relative images use authenticated previews',async({page,request})=>{
  const api=await setup(page,request),day='2026-10-30';
  const original=await api('PUT','/notes/files?path=journal%2F'+day+'.md',Buffer.from('Journal body'),{'Content-Type':'application/octet-stream','If-None-Match':'*'}),identity=await original.json();
  const label=await api('GET','/day-labels/'+day);await api('PUT','/day-labels/'+day,{text:'Independent label'},{'If-Match':label.headers().etag});
  await page.getByRole('button',{name:'Notes settings',exact:true}).click();await page.getByLabel('Journal folder',{exact:true}).fill('Private/Journal');await page.getByRole('dialog',{name:'Notes settings',exact:true}).getByRole('button',{name:'Save',exact:true}).click();await expect(page.getByRole('dialog',{name:'Notes settings',exact:true})).not.toBeVisible();
  expect((await(await api('GET','/notes/resolve?path=Private%2FJournal%2F'+day+'.md')).json()).id).toBe(identity.id);expect((await(await api('GET','/day-labels/'+day)).json()).text).toBe('Independent label');
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9o9u4AAAAASUVORK5CYII=','base64');
  await api('PUT','/notes/files?path=Notes%2Fimage.png',png,{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await api('PUT','/notes/files?path=Notes%2FImage.md',Buffer.from('# Image\n\n![Pixel](image.png)\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).first().click();await page.locator('#notes-navigation').getByRole('button',{name:'Notes',exact:true}).click();await page.locator('#notes-navigation').getByRole('button',{name:'Notes/Image.md',exact:true}).click();
  await page.locator('#notes-panel').getByRole('button',{name:'Formatted',exact:true}).click();await expect(page.locator('#notes-formatted img[alt=Pixel]')).toHaveAttribute('src',/^blob:/);await expect.poll(()=>page.locator('#notes-formatted img[alt=Pixel]').evaluate(image=>image.naturalWidth)).toBe(1);
  await expect(page.locator('.notes-footer').getByRole('button',{name:'Save',exact:true})).toBeDisabled();
  const violations=await new AxeBuilder({page}).include('#notes-panel').analyze();expect(violations.violations).toEqual([]);
});

test('Notes supports all themes, Norwegian labels and a read-only client',async({page,request,browser})=>{
  test.setTimeout(150000);const api=await setup(page,request);
  await api('PUT','/notes/files?path=Notes%2FTheme.md',Buffer.from('# Theme note\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
    await setTheme(page,theme);await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-title')).toBeVisible();
    const result=await new AxeBuilder({page}).include('#notes-panel').analyze();expect(result.violations).toEqual([]);
  }
  const me=await api('GET','/me'),profile=await me.json();await api('PUT','/me',{zoneId:profile.zoneId,locale:'nb-NO'},{'If-Match':me.headers().etag});
  await page.reload();await page.locator('[data-tab=notes]').click();await expect(page.locator('#notes-title')).toHaveText('Notater');await expect(page.locator('#notes-search')).toHaveAccessibleName('Søk i filnavn');
  const token=await(await api('POST','/auth/tokens',{name:'Read-only Notes browser','permissions':['read'],'expiresAt':null})).json();
  const session=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true,locale:'en-GB'});
  try{const reader=await session.newPage();await reader.goto('/web/');await reader.locator('#auth-mode').selectOption('token');await reader.locator('#token').fill(token.token);await reader.locator('#login-form button[type=submit]').click();await expect(reader.locator('#workspace')).toBeVisible();await reader.locator('[data-tab=notes]').click();await expect(reader.locator('#notes-search')).toBeVisible();
    await reader.locator('#notes-navigation').getByRole('button',{name:'Notes',exact:true}).click();await reader.locator('#notes-navigation').getByRole('button',{name:'Notes/Theme.md',exact:true}).click();await expect(reader.locator('#notes-body')).toHaveValue('# Theme note\n');await expect(reader.locator('#notes-body')).not.toBeEditable();await expect(reader.locator('.notes-footer button')).toBeDisabled();
  }finally{await session.close();}
});
