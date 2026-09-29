const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlZsAAAAASUVORK5CYII=', 'base64');
async function until(check) {
  for (let i = 0; i < 200; i++) { if (await check()) return; await new Promise(r => setTimeout(r, 50)); }
  throw new Error('Browser condition timed out');
}
(async () => {
  fs.mkdirSync('/artifacts', { recursive: true });
  const browser = await chromium.launch({ headless: true });
  try {
    for (const [label, viewport] of [['desktop', { width: 1440, height: 900 }], ['large-desktop', { width: 1920, height: 1080 }]]) {
      const page = await browser.newPage({ viewport });
      const errors = []; page.on('pageerror', e => errors.push(e.message));
      let output = '', wsCount = 0, ticketRequests = 0, inputCount = 0;
      page.on('request', req => { if (req.url().endsWith('/api/connections')) ticketRequests++; });
      page.on('websocket', ws => {
        wsCount++;
        ws.on('framereceived', ({ payload }) => { const m = JSON.parse(payload); if (m.type === 'output') output += m.data; });
        ws.on('framesent', ({ payload }) => { if (JSON.parse(payload).type === 'input') inputCount++; });
      });
      const choose = async name => {
        await page.getByRole('button', { name: new RegExp(name) }).click();
        await until(async () => await page.locator('#state').textContent() === '已连接' && await page.locator('#session-name').textContent() === name);
      };
      await page.goto('http://terminal-fixture:3000/terminal');
      await choose('demo-project');
      assert.equal(await page.locator('.keyboard-bar, #paste, #keyboard').count(), 0);
      assert.equal(await page.locator('#connection-kind').textContent(), 'tmux');
      await until(async () => (await page.locator('#expiry').textContent()).startsWith('剩余'));
      const oldFont = Number(await page.locator('#font-size').textContent());
      await page.locator('#font-up').click(); assert.equal(Number(await page.locator('#font-size').textContent()), oldFont + 1);
      await page.locator('#font-down').click();
      await page.locator('#toggle-sidebar').click(); assert.equal(await page.locator('#sidebar').isHidden(), true);
      await page.locator('#toggle-sidebar').click();

      assert.equal(await page.locator('#composer').isHidden(), true);
      await page.locator('.xterm-helper-textarea').pressSequentially('printf RAW_TERMINAL_OK');
      await page.locator('.xterm-helper-textarea').press('Enter');
      await until(() => output.includes('RAW_TERMINAL_OK'));
      await page.locator('#toggle-composer').click();
      assert.ok((await page.locator('#draft').boundingBox()).height >= 156);
      const marker = `${label}_${Date.now()}`;
      await page.locator('#draft').fill(`printf 'KEY_%s_OK\\n' '${marker}'`);
      await page.locator('#draft').press('Enter');
      await until(() => output.includes(`KEY_${marker}_OK`));
      await page.locator('#draft').fill('草稿A'); await page.locator('#draft').press('End');
      await page.locator('#draft').press('Control+j'); await page.locator('#draft').pressSequentially('B');
      assert.equal(await page.locator('#draft').inputValue(), '草稿A\nB');
      const beforeIME = inputCount;
      await page.locator('#draft').evaluate(el => el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, keyCode: 229, bubbles: true })));
      assert.equal(await page.locator('#draft').inputValue(), '草稿A\nB'); assert.equal(inputCount, beforeIME);
      await choose('中文测试'); await page.locator('#toggle-composer').click(); await page.locator('#draft').fill('另一个草稿');
      await choose('demo-project'); await page.locator('#toggle-composer').click(); assert.equal(await page.locator('#draft').inputValue(), '草稿A\nB');
      assert.equal(wsCount, 1); assert.equal(ticketRequests, 1);
      await choose('codex-images'); await until(async () => await page.locator('#composer').isVisible());
      await page.locator('#image-files').setInputFiles({ name: '截图.png', mimeType: 'image/png', buffer: Buffer.concat([png, Buffer.alloc(2 * 1024 * 1024)]) });
      await until(async () => (await page.locator('#attachments').textContent()).includes('已上传'));
      assert.equal(await page.locator('#attachments img').evaluate(img => img.naturalWidth > 0), true);
      await page.locator('#draft').fill('请描述这张图片');
      await page.screenshot({ path: `/artifacts/${label}-images.png` });
      await page.locator('#draft').press('Enter');
      await until(async () => await page.locator('#attachments .attachment').count() === 0);
      await until(() => output.includes('网页上传的附件') && output.includes('.png'));
      await new Promise(r => setTimeout(r, 200));
      const codexInputBeforeWheel = inputCount;
      await page.locator('#terminal').hover(); await page.mouse.wheel(0, -300);
      await until(async () => (await page.locator('#history-content').textContent()).includes('网页上传的附件'));
      assert.equal(inputCount, codexInputBeforeWheel, 'Codex wheel must not recall previous prompts');
      await page.locator('#history-close').click();
      for (const method of ['paste', 'drop']) {
        await page.evaluate(({ bytes, method }) => {
          const dt = new DataTransfer(); dt.items.add(new File([new Uint8Array(bytes)], method + '.png', { type: 'image/png' }));
          const event = method === 'paste' ? new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }) : new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true });
          document.getElementById(method === 'paste' ? 'draft' : 'composer').dispatchEvent(event);
        }, { bytes: [...png], method });
        await until(async () => (await page.locator('#attachments').textContent()).includes('已上传'));
        await page.getByRole('button', { name: '移除', exact: true }).click();
      }
      await choose('demo-project'); await page.locator('#toggle-composer').click(); await page.locator('#draft').fill("seq 1 60000; printf 'FLOW_%s_OK\\n' 'END'");
      await page.locator('#draft').press('Enter');
      await until(() => output.includes('FLOW_END_OK'));
      await new Promise(r => setTimeout(r, 250));
      const inputBeforeWheel = inputCount;
      await page.locator('#terminal').hover(); await page.mouse.wheel(0, -500);
      await until(async () => (await page.locator('#history-content').textContent()).includes('FLOW_END_OK'));
      assert.equal(inputCount, inputBeforeWheel, 'wheel must not send arrow/input to shell or Codex');
      const historyTop = await page.locator('#history-content').evaluate(el => el.scrollTop);
      await page.locator('#history-content').hover(); await page.mouse.wheel(0, -400);
      await until(async () => await page.locator('#history-content').evaluate(el => el.scrollTop) < historyTop);
      await page.locator('#history-content').press('Escape'); assert.equal(await page.locator('#history').isHidden(), true);

      assert.equal(await page.locator('#state').textContent(), '已连接');
      assert.equal(wsCount, 1); assert.equal(ticketRequests, 1);
      await page.locator('#detach').click(); await page.getByRole('button', { name: '重新连接', exact: true }).click();
      await until(async () => await page.locator('#state').textContent() === '已连接');
      assert.equal(wsCount, 2); assert.deepEqual(errors, []);
      await page.screenshot({ path: `/artifacts/${label}-terminal.png` });
      await page.locator('#new-ssh').click();
      await until(async () => await page.locator('#state').textContent() === '已连接' && await page.locator('#session-name').textContent() === '临时 SSH · NAS');
      assert.equal(await page.locator('#composer').isHidden(), true);
      await page.locator('.xterm-helper-textarea').pressSequentially(`printf 'SSH_%s_OK\\n' '${marker}'`);
      await page.locator('.xterm-helper-textarea').press('Enter');
      await until(() => output.includes(`SSH_${marker}_OK`));
      // Force alternate buffer in isolated SSH shell to reproduce xterm's arrow fallback.
      await page.locator('.xterm-helper-textarea').pressSequentially("printf '\\033[?1049h'");
      await page.locator('.xterm-helper-textarea').press('Enter');
      await new Promise(r => setTimeout(r, 250));
      const sshInputBeforeWheel = inputCount;
      await page.locator('#terminal').hover(); await page.mouse.wheel(0, -500);
      await new Promise(r => setTimeout(r, 250));
      assert.equal(inputCount, sshInputBeforeWheel, 'SSH alternate wheel must not become arrow input');

      await page.locator('#detach').click();
      assert.equal(await page.locator('#notice').textContent(), '临时 SSH 已关闭。');
      await page.close();
      console.log(`${label}: single-WebSocket switching / drafts / Enter / Ctrl+J / IME / images / clipboard / drop / flow-control / reconnect / read-only history / wheel-no-input / desktop controls PASS`);
    }
  } finally { await browser.close(); }
})().catch(error => { console.error('Browser acceptance failed:', error.stack); process.exit(1); });
