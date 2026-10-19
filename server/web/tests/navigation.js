import {expect} from '@playwright/test';

export async function openAccountMenu(page) {
  const menu = page.locator('#account-menu');
  const toggle = page.getByRole('button', {name: 'Account menu', exact: true});
  await expect(toggle).toBeVisible();
  if (await menu.getAttribute('open') === null) await toggle.click();
}

export async function openAbout(page) {
  await openAccountMenu(page);await page.getByRole('button',{name:'About',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'About Timekeeper',exact:true})).toBeVisible();
}

export async function setTimeZone(page, zone) {
  await openAccountMenu(page);
  await page.getByRole('link', {name:'My Account',exact:true}).click();
  await page.getByLabel('Time zone',{exact:true}).fill(zone);
  await page.getByRole('button',{name:'Save time zone',exact:true}).click();
  await expect(page.locator('#account-content')).toContainText('Time zone saved.');
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();
}

export async function openThemeSettings(page){
  if(await page.locator('#account-content #theme').isVisible())return;
  if(await page.locator('#account-menu').getAttribute('open')===null)await page.locator('#account-menu > summary').click();await page.locator('#account').click();await expect(page.locator('#account-content #theme')).toBeVisible();
}
export async function setTheme(page,value){
  await openThemeSettings(page);await page.locator('#theme').selectOption(value);await page.locator('#account-page a[href="#tracking"]').click();await expect(page.locator('#workspace')).toBeVisible();
}

export async function openTaskManagement(page){
  await page.locator('[data-tab=activities]').click();const panel=page.locator('#activity-tasks');
  if(await panel.getAttribute('open')===null)await panel.locator('summary').click();await expect(panel.locator('#tasks')).toBeVisible();
}
