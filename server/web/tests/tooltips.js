import {expect} from '@playwright/test';

export async function tooltipFor(target) {
  await target.hover();const tooltip=target.page().getByRole('tooltip');await expect(tooltip).toBeVisible();return tooltip;
}
