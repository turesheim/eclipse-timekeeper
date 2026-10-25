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

export async function setDurationFormat(page, value) {
  await openAccountMenu(page);
  await page.getByRole('link',{name:'My Account',exact:true}).click();
  await expect(page.locator('#account-content #duration-format')).toBeVisible();
  await page.getByLabel('Duration format',{exact:true}).selectOption(value);
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();
}

export async function openThemeSettings(page){
  if(await page.locator('#account-content #theme').isVisible())return;
  if(await page.locator('#account-menu').getAttribute('open')===null)await page.locator('#account-menu > summary').click();await page.locator('#account').click();await expect(page.locator('#account-content #theme')).toBeVisible();
}
export async function setTheme(page,value){
  await openThemeSettings(page);await page.locator('#theme').selectOption(value);await page.locator('#account-page a[href="#tracking"]').click();await expect(page.locator('#workspace')).toBeVisible();
}
export async function expandProjectClient(page,clientId) {
  const toggle=page.locator(`#projects tbody[data-client-id="${clientId}"] .projects-client-toggle`);
  if(await toggle.getAttribute('aria-expanded')==='false')await toggle.click();
}
