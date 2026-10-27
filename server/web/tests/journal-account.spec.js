import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {captureScreenshot} from './screenshots.js';
import {setTheme} from './navigation.js';

const base=()=>process.env.TIMEKEEPER_WEB_URL;
test.beforeAll(async({request})=>{
  const headers={Origin:new URL(base()).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){
    const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});
    expect(reply.status()).toBe(200);
    await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});
  }
});
async function signIn(page,token){
  await page.goto('/web/');
  await page.locator('#auth-mode').selectOption('token');
  await page.locator('#token').fill(token);
  await page.locator('#login-form button[type=submit]').click();
  await expect(page.locator('#workspace')).toBeVisible();
  await page.evaluate(()=>location.hash='#account/journal');
  await expect(page.getByRole('heading',{name:'Journal',exact:true})).toBeVisible();
}

test('Journal shows WebDAV connection and links to API token management',async({browser})=>{
  const context=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB'}),page=await context.newPage();
  try{
    await signIn(page,process.env.TIMEKEEPER_WEB_TOKEN);
    const card=page.locator('.journal-account');
    await expect(card.locator('.journal-webdav-url')).toHaveText(new URL('/dav/journal/',base()).href);
    await expect(card).toContainText('YYYY-MM-DD.md');
    await expect(card).toContainText('If-Match');
    await expect(card.getByRole('link',{name:'Manage API tokens'})).toHaveAttribute('href','#account/tokens');
    await expect(card.getByRole('link',{name:'WebDAV connection and compatibility guide'})).toHaveAttribute('href',/JOURNAL-WEBDAV\.md$/);
    await expect(card.getByText('Public SSH key (.pub)')).toHaveCount(0);
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
      await setTheme(page,theme);
      await page.evaluate(()=>location.hash='#account/journal');
      await expect(page.getByRole('heading',{name:'Journal',exact:true})).toBeVisible();
      expect((await new AxeBuilder({page}).include('#account-page').analyze()).violations).toEqual([]);
    }
    await setTheme(page,'timekeeper');
    await page.evaluate(()=>location.hash='#account/journal');
    await page.setViewportSize({width:320,height:740});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await captureScreenshot(page,{path:'test-results/journal-account-mobile.png',fullPage:true});
    await card.getByRole('link',{name:'Manage API tokens'}).click();
    await expect(page).toHaveURL(/#account\/tokens$/);
    await expect(page.getByRole('heading',{name:'Personal API tokens'})).toBeVisible();
  }finally{await context.close();}
});

test('read-only accounts can view the connection without SSH controls',async({browser})=>{
  const context=await browser.newContext({baseURL:base(),ignoreHTTPSErrors:true,locale:'en-GB'}),page=await context.newPage();
  try{
    await signIn(page,process.env.TIMEKEEPER_WEB_READ_TOKEN);
    await expect(page.locator('.journal-webdav-url')).toHaveText(new URL('/dav/journal/',base()).href);
    await expect(page.getByRole('button',{name:'Add client'})).toHaveCount(0);
    await expect(page.getByRole('button',{name:'Save synchronization settings'})).toHaveCount(0);
  }finally{await context.close();}
});
