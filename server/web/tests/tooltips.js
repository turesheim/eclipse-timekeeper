import {expect} from '@playwright/test';

export async function tooltipFor(target) {
  // Each explicit inspection starts a new hint gesture. A persistent popup can
  // cover the next control: moving into it should preserve that popup, so first
  // dismiss it rather than expecting a different hint through its surface.
  const other=await target.evaluate(node=>{
    const owner=node.closest('[data-tk-tooltip]')||node,ids=(owner.getAttribute('aria-describedby')||'').split(/\s+/);
    return [...document.querySelectorAll('.timekeeper-tooltip')].some(tip=>!ids.includes(tip.id));
  });
  if(other){await target.page().keyboard.press('Escape');await expect(target.page().locator('.timekeeper-tooltip')).toHaveCount(0);}
  if(await target.isDisabled()){
    await target.scrollIntoViewIfNeeded();const box=await target.boundingBox();await target.page().mouse.move(box.x+box.width/2,box.y+box.height/2);
  }else await target.hover();
  let id;await expect.poll(async()=>id=await target.evaluate(node=>((node.closest('[data-tk-tooltip]')||node).getAttribute('aria-describedby')||'').split(/\s+/).find(id=>{const tip=document.getElementById(id);return tip?.classList.contains('timekeeper-tooltip')&&['tooltip','dialog'].includes(tip.getAttribute('role'));})), {message:'Associated tooltip for '+await target.getAttribute('aria-label')}).toBeTruthy();
  const tooltip=target.page().locator('#'+id);await expect(tooltip).toBeVisible();return tooltip;
}
