import {expect} from '@playwright/test';

export async function tooltipFor(target) {
  if(await target.isDisabled()){
    await target.scrollIntoViewIfNeeded();const box=await target.boundingBox();await target.page().mouse.move(box.x+box.width/2,box.y+box.height/2);
  }else await target.hover();
  const tooltip=target.page().getByRole('tooltip');await expect(tooltip).toBeVisible();return tooltip;
}
