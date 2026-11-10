import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {captureScreenshot} from './screenshots.js';

const origin=()=>new URL(process.env.TIMEKEEPER_WEB_URL).origin;
test.beforeAll(async({request})=>{
  const headers={Origin:origin()},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){
    const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'Administrator',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);
    await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});
  }
});
async function administrator(page,request){
  const username='storage-'+randomUUID().slice(0,8),password='Storage administrator fixture password';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:'Storage operator',email:username+'@example.invalid',administrator:true}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  const session=await(await browserRequest(page).get('/api/v1/auth/session')).json(),headers={'X-CSRF-Token':session.csrf};
  return {password,headers};
}

test('Storage shows retained categories and administrator quota changes apply without signing out',async({page,request})=>{
  const admin=await administrator(page,request),api=browserRequest(page);
  await page.goto('/web/#account/storage');await expect(page.getByRole('heading',{name:'Storage',exact:true})).toBeVisible();await expect(page.getByRole('progressbar',{name:'Storage per account',exact:true})).toHaveAttribute('aria-valuetext','0 B of 5 GB');
  const empty=await(await api.get('/api/v1/notes/storage')).json();expect(empty.usage.entries).toBe(0);
  const note=await api.put('/api/v1/notes/files?path=Ordinary.md',{headers:{...admin.headers,'If-None-Match':'*','Content-Type':'application/octet-stream'},data:Buffer.from('Four')});expect(note.status()).toBe(201);
  expect((await api.put('/api/v1/notes/files?path=image.bin',{headers:{...admin.headers,'If-None-Match':'*','Content-Type':'application/octet-stream'},data:Buffer.alloc(7)})).status()).toBe(201);
  await page.getByRole('button',{name:'Refresh',exact:true}).click();
  await expect(page.getByRole('progressbar',{name:'Ordinary notes',exact:true})).toHaveAttribute('aria-valuetext','4 B of 5 GB');await expect(page.getByRole('progressbar',{name:'Attachments and other files',exact:true})).toHaveAttribute('aria-valuetext','7 B of 5 GB');
  expect((await api.delete('/api/v1/notes/entries/'+(await note.json()).id,{headers:{...admin.headers,'If-Match':note.headers().etag}})).status()).toBe(204);
  await page.getByRole('button',{name:'Refresh',exact:true}).click();await expect(page.getByRole('progressbar',{name:'Trash',exact:true})).toHaveAttribute('aria-valuetext','4 B of 5 GB');await expect(page.getByRole('progressbar',{name:'Storage per account',exact:true})).toHaveAttribute('aria-valuetext','11 B of 5 GB');
  const defaults=await api.get('/api/v1/admin/notes/limits');
  try{
    await page.getByRole('link',{name:'Manage limits',exact:true}).click();await expect(page.getByRole('heading',{name:'Notes storage limits',exact:true})).toBeVisible();
    await expect(page.getByLabel('Maximum attachment or file size (MB)',{exact:true})).toHaveValue('160');await expect(page.getByLabel('Maximum Markdown file size (MB)',{exact:true})).toHaveValue('10');
    await page.getByLabel('Files and folders per account',{exact:true}).fill('4');await page.getByLabel('Your password',{exact:true}).fill(admin.password);await page.getByRole('button',{name:'Save settings',exact:true}).click();await expect(page.getByText('Storage limits saved and active.',{exact:true})).toBeVisible();await expect(page.getByLabel('Your password',{exact:true})).toHaveValue('');
    expect((await api.get('/api/v1/auth/session')).status()).toBe(200);
    for(const width of [1440,320]){
      await page.setViewportSize({width,height:900});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);await captureScreenshot(page,{path:`test-results/storage-admin-${width}.png`,fullPage:true});
    }
    await page.goto('/web/#account/storage');const entries=page.getByRole('progressbar',{name:'Files and folders per account',exact:true});await expect(entries).toHaveAttribute('aria-valuenow','100');await expect(entries).toHaveAttribute('aria-valuetext','5 of 4');await expect(page.getByText('Usage exceeds the current allowance.',{exact:false})).toBeVisible();
    for(const width of [1440,320]){
      await page.setViewportSize({width,height:1000});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      expect((await new AxeBuilder({page}).include('#account-page').analyze()).violations).toEqual([]);await captureScreenshot(page,{path:`test-results/storage-account-${width}.png`,fullPage:true});
    }
  }finally{
    const current=await api.get('/api/v1/admin/notes/limits');expect((await api.put('/api/v1/admin/notes/limits',{headers:{...admin.headers,'If-Match':current.headers().etag},data:{limits:(await defaults.json()).limits,currentPassword:admin.password}})).status()).toBe(200);
  }
});

test('read-only sessions can inspect Storage without acquiring administrator controls',async({page})=>{
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_READ_TOKEN);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  await page.goto('/web/#account/storage');await expect(page.getByRole('heading',{name:'Storage',exact:true})).toBeVisible();await expect(page.getByRole('progressbar')).toHaveCount(10);await expect(page.getByRole('link',{name:'Manage limits',exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'Refresh',exact:true})).toBeEnabled();
});
