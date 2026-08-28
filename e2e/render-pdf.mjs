// HTML → PDF (Chromium print). 인자: <html경로> <pdf경로> [landscape]
import { chromium } from '@playwright/test';
import { pathToFileURL } from 'url';
const [html, pdf, landscape] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(pathToFileURL(html).href, { waitUntil: 'networkidle' });
const opts = { path: pdf, printBackground: true, margin: { top: 0, bottom: 0, left: 0, right: 0 } };
if (landscape === 'slides') Object.assign(opts, { width: '338mm', height: '190mm' }); // 16:9
else Object.assign(opts, { format: 'A4', landscape: landscape === 'landscape' });
await page.pdf(opts);
await browser.close();
console.log('PDF 생성:', pdf);
