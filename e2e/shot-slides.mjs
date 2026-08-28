import { chromium } from '@playwright/test';
import { pathToFileURL } from 'url';
const [html, outPrefix, ...idx] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1278, height: 719 } });
await page.goto(pathToFileURL(html).href, { waitUntil: 'networkidle' });
for (const i of idx.map(Number)) await page.locator('.slide').nth(i).screenshot({ path: `${outPrefix}-${i}.png` });
await browser.close();
