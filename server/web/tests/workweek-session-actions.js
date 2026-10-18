export async function workSessionDetails(page,id) {
  await page.locator(`#week-sessions [data-work-session-id="${id}"]`).first().getByRole('button',{name:/^Session actions /}).click();
  return page.getByRole('dialog',{name:'Work session',exact:true});
}
export async function workSessionAction(page,id,action='Edit') {
  const dialog=await workSessionDetails(page,id);
  await dialog.getByRole('button',{name:new RegExp('^'+action+' session ')}).click();
}
