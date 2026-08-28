import { chromium } from '@playwright/test';
import { pathToFileURL } from 'url';
const [html, outPrefix, ...pages] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 794, height: 1123 } }); // A4 @96dpi
await page.goto(pathToFileURL(html).href, { waitUntil: 'networkidle' });
const els = page.locator('.page');
for (const i of pages.map(Number)) {
  await els.nth(i).screenshot({ path: `${outPrefix}-${i}.png` });
}
await browser.close();
