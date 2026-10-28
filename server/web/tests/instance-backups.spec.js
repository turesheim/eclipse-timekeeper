import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';
import AxeBuilder from '@axe-core/playwright';
const phrase='Separate encrypted instance backup passphrase';
const card=(page,title)=>page.getByRole('heading',{name:title,exact:true}).locator('..');
async function password(section){await section.getByLabel('Current administrator password',{exact:true}).fill(process.env.TIMEKEEPER_WEB_PASSWORD);}
test('instance backup Administration supports native encrypted download and upload review over HTTPS',async({page,request},testInfo)=>{
  test.skip(!process.env.TIMEKEEPER_WEB_URL?.startsWith('https:'),'Native grants require HTTPS');test.setTimeout(120000);
  const origin=new URL(process.env.TIMEKEEPER_WEB_URL).origin;
  const status=await request.get('/api/v1/auth/status',{headers:{Origin:origin}});
  if((await status.json()).setupRequired){const setup=await request.post('/api/v1/auth/setup',{headers:{Origin:origin},data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',displayName:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,email:'browser-admin@example.org'}});expect(setup.status()).toBe(200);const account=await setup.json();await request.post('/api/v1/auth/logout',{headers:{Origin:origin,'X-CSRF-Token':account.csrf}});}
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill('browser-admin');await page.locator('#password').fill(process.env.TIMEKEEPER_WEB_PASSWORD);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
  await page.goto('/admin/#backups');await expect(page.getByRole('heading',{name:'Backup & Restore',exact:true})).toBeVisible();
  const create=card(page,'Create encrypted backup');await password(create);await create.getByLabel('Backup encryption passphrase',{exact:true}).fill(phrase);await create.getByLabel('Confirm encryption passphrase',{exact:true}).fill(phrase);await create.getByRole('button',{name:'Create encrypted backup',exact:true}).click();
  await expect(create.getByLabel('Current administrator password',{exact:true})).toHaveValue('');
  const download=card(page,'Download encrypted backup');await expect(download).toBeVisible({timeout:60000});
  await page.reload();await expect(download).toBeVisible();await password(download);
  const pending=page.waitForEvent('download');await download.getByRole('button',{name:'Download encrypted backup',exact:true}).click();const file=await pending;expect(file.suggestedFilename()).toMatch(/^timekeeper-instance-[a-f0-9-]{36}\.tkbackup$/);
  const path=testInfo.outputPath('synthetic-instance.tkbackup');await file.saveAs(path);expect((await readFile(path)).subarray(0,8).toString()).toBe('TKIBK001');
  const discardBackup=card(page,'Instance backup').getByRole('heading',{name:'Discard server artifact',exact:true}).locator('..');await password(discardBackup);await discardBackup.getByRole('button',{name:'Discard server artifact',exact:true}).click();await expect(card(page,'Instance backup')).toContainText('Artifact discarded');
  const upload=card(page,'Upload encrypted backup');await upload.getByLabel('Encrypted backup file',{exact:true}).setInputFiles(path);await expect(upload).toContainText('File checksum complete');await password(upload);await upload.getByRole('button',{name:'Start upload',exact:true}).click();
  const validate=card(page,'Verify uploaded archive');await expect(validate).toBeVisible();await password(validate);await validate.getByLabel('Backup encryption passphrase',{exact:true}).fill('Incorrect encryption passphrase');await validate.getByRole('button',{name:'Verify uploaded archive',exact:true}).click();await expect(card(page,'Restore review')).toContainText('Verification failed',{timeout:60000});
  await password(validate);await validate.getByLabel('Backup encryption passphrase',{exact:true}).fill(phrase);await validate.getByRole('button',{name:'Verify uploaded archive',exact:true}).click();await expect(card(page,'Restore review')).toContainText('restore activation is not yet available',{timeout:60000});
  await page.reload();await expect(card(page,'Restore review')).toContainText('restore activation is not yet available');await expect(card(page,'Restore review')).toContainText('tk_account');
  for(const width of [1440,390,320]){await page.setViewportSize({width,height:900});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);const audit=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations.map(value=>value.id)).toEqual([]);await page.screenshot({path:testInfo.outputPath('instance-backups-'+width+'.png'),fullPage:true});}
  const discard=card(page,'Restore review').getByRole('heading',{name:'Discard server artifact',exact:true}).locator('..');await password(discard);await discard.getByRole('button',{name:'Discard server artifact',exact:true}).click();await expect(card(page,'Restore review')).toContainText('Artifact discarded');
});
