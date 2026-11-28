import {expect} from '@playwright/test';

export const selectedJournal=page=>page.locator('#today-title');
export const calendarDay=(page,date)=>page.locator(`#today-month-grid [data-date="${date}"]`);

export async function browseTodayMonth(page,month){
  const grid=page.locator('#today-month-grid');
  await expect(grid).toHaveAttribute('data-month',/^\d{4}-\d{2}$/);
  const monthIndex=value=>{const [year,month]=value.split('-').map(Number);return year*12+month;};
  const distance=monthIndex(month)-monthIndex(await grid.getAttribute('data-month'));
  if(!distance)return;
  const arrow=page.locator(distance<0?'#today-month-previous':'#today-month-next');
  // Fixture setup may span many months. Activate the controls without
  // scrolling the editor off screen or waiting for intermediate animations.
  await arrow.evaluate((button,count)=>{for(let i=0;i<count;i++)button.click();},Math.abs(distance));
  await expect(grid).toHaveAttribute('data-month',month);
}

export async function chooseTodayDate(page,date){
  await browseTodayMonth(page,date.slice(0,7));
  await calendarDay(page,date).dispatchEvent('click');
}
