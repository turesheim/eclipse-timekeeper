import {expect} from '@playwright/test';

export async function tooltipFor(target) {
  if(await target.isDisabled()){
    await target.scrollIntoViewIfNeeded();const box=await target.boundingBox();await target.page().mouse.move(box.x+box.width/2,box.y+box.height/2);
  }else await target.hover();
  let id;await expect.poll(async()=>id=await target.evaluate(node=>((node.closest('[data-tk-tooltip]')||node).getAttribute('aria-describedby')||'').split(/\s+/).find(id=>document.getElementById(id)?.getAttribute('role')==='tooltip')), {message:'Associated tooltip for '+await target.getAttribute('aria-label')}).toBeTruthy();
  const tooltip=target.page().locator('#'+id);await expect(tooltip).toBeVisible();return tooltip;
}
