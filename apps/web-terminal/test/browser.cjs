// Run using Playwright 1.48.0 with its matching browser image, against browser-fixture only.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
async function until(check) {
  for (let i = 0; i < 150; i++) { if (await check()) return; await new Promise(r => setTimeout(r, 100)); }
  throw new Error('Browser condition timed out');
}
(async () => {
  fs.mkdirSync('/artifacts', { recursive: true });
  const browser = await chromium.launch({ headless: true });
  try {
    for (const [label, viewport] of [['desktop', { width: 1440, height: 900 }], ['mobile', { width: 390, height: 844 }]]) {
      const page = await browser.newPage({ viewport, isMobile: label === 'mobile', hasTouch: label === 'mobile' });
      const errors = []; page.on('pageerror', e => errors.push(e.message));
      let output = '';
      page.on('websocket', ws => ws.on('framereceived', ({ payload }) => {
        const message = JSON.parse(payload);
        if (message.type === 'output') output += message.data;
      }));
      await page.goto('http://terminal-fixture:3000/terminal');
      await page.getByRole('button', { name: /demo-project/ }).waitFor();
      await page.screenshot({ path: `/artifacts/${label}-sessions.png` });
      await page.getByRole('button', { name: /demo-project/ }).click();
      await until(async () => await page.locator('#state').textContent() === '已连接');
      // Command echo does not contain the joined marker, so output must come from the shell.
      const marker = `${label.toUpperCase()}_${Date.now()}`;
      await page.locator('#draft').fill(`printf 'BROWSER_%s_中文\\n' '${marker}'`);
      await page.locator('#send').click();
      await until(() => output.includes(`BROWSER_${marker}_中文`));
      await until(async () => (await page.locator('.xterm-rows').textContent()).includes(`BROWSER_${marker}_中文`));
      await page.screenshot({ path: `/artifacts/${label}-terminal.png` });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'no horizontal overflow');
      await page.locator('#detach').click();
      output = '';
      await page.getByRole('button', { name: '重新连接' }).click();
      await until(async () => await page.locator('#state').textContent() === '已连接');
      await until(() => output.includes(`BROWSER_${marker}_中文`));
      assert.deepEqual(errors, []);
      await page.close();
      console.log(`${label}: input / Chinese text / disconnect / reconnect / layout PASS`);
    }
  } finally { await browser.close(); }
})().catch(error => { console.error('Browser acceptance failed:', error.message); process.exit(1); });
