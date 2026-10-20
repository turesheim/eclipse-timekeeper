// Full-page captures place viewport-fixed navigation partway down tall images.
// Preserve the actual visible window whenever the workspace bar is displayed.
// Account and administration pages can still capture their full scrollable form.
export async function captureScreenshot(page, options = {}) {
  const fullPage = !!options.fullPage && !await page.locator('#workspace-navigation').isVisible();
  return page.screenshot({...options, fullPage});
}
