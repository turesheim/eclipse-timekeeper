import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir: './tests', testMatch: '*.spec.js', workers: 1, retries: 0, timeout: 45000,
  expect: {timeout: 12000},
  reporter: [['list'], ['junit', {outputFile: 'test-results/browser.xml'}]],
  // Traces include authorization headers; never retain them as release evidence.
  use: {baseURL: process.env.TIMEKEEPER_WEB_URL, trace: 'off', video: 'off', screenshot: 'only-on-failure'},
  projects: [{name:process.env.TIMEKEEPER_WEB_BROWSER ?? 'chromium', use:{browserName:process.env.TIMEKEEPER_WEB_BROWSER ?? 'chromium'}}],
});
