import {expect} from '@playwright/test';
export async function searchNotes(page,query){
  const panel=page.locator('#notes-search-panel');
  if(!await panel.isVisible()){if(await page.locator('#titlebar-notes-query').isVisible()){await page.locator('#titlebar-notes-query').fill(query);await page.locator('#titlebar-notes-query').press('Enter');}else{await page.locator('#titlebar-notes-search button').click();await page.locator('#notes-search-query').fill(query);await page.locator('#notes-search-query').press('Enter');}}
  else {await page.locator('#notes-search-query').fill(query);await page.locator('#notes-search-query').press('Enter');}
  await expect(panel).toBeVisible();return panel;
}
