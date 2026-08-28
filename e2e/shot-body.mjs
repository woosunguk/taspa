import { chromium } from '@playwright/test';
import { pathToFileURL } from 'url';
const [html, out] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 794, height: 1123 } });
await page.goto(pathToFileURL(html).href, { waitUntil: 'networkidle' });
await page.screenshot({ path: out, fullPage: false });
await browser.close();
