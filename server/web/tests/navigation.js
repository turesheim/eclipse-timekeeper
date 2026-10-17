import {expect} from '@playwright/test';

export async function openAccountMenu(page) {
  const menu = page.locator('#account-menu');
  const toggle = page.getByRole('button', {name: 'Account menu', exact: true});
  await expect(toggle).toBeVisible();
  if (await menu.getAttribute('open') === null) await toggle.click();
}

export async function setTimeZone(page, zone) {
  await openAccountMenu(page);
  await page.getByRole('link', {name:'My Account',exact:true}).click();
  await page.getByLabel('Time zone',{exact:true}).fill(zone);
  await page.getByRole('button',{name:'Save time zone',exact:true}).click();
  await expect(page.locator('#account-content')).toContainText('Time zone saved.');
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();
}
