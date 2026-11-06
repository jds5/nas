// 浏览器验收：上传 2.5 MiB（3 个分片，中途断开一次后续传）、校验列表与 SHA-256、下载比对、删除。
const { chromium } = require('playwright');
const crypto = require('crypto');
const fs = require('fs');
(async () => {
  const jwt = fs.readFileSync('/shared/jwt', 'utf8');
  const data = crypto.randomBytes(2.5 * 1024 * 1024);
  fs.writeFileSync('/tmp/测试 文件.bin', data);
  const browser = await chromium.launch();
  const context = await browser.newContext({ acceptDownloads: true });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  // 测试环境为 HTTP，COOP 警告属预期；模拟断流产生的连接重置也属预期。
  page.on('console', m => { if (m.type() === 'error' && !/Cross-Origin-Opener-Policy|ERR_CONNECTION_RESET/.test(m.text())) errors.push(m.text()); });
  // 模拟 Access 边缘只给 drop 页面端注入身份；直连端不带 JWT。
  await page.route('http://drop-fixture:3000/**', route => route.continue({ headers: { ...route.request().headers(), 'cf-access-jwt-assertion': jwt } }));
  // 第二个分片第一次请求时模拟断流，验证续签令牌后从断点继续。
  let dropped = false;
  await page.route('http://drop-fixture:3001/u/**', route => {
    if (!dropped && route.request().method() === 'PUT' && route.request().url().includes('offset=1048576')) { dropped = true; return route.abort('connectionreset'); }
    return route.continue();
  });
  await page.goto('http://drop-fixture:3000/');
  await page.setInputFiles('#picker', '/tmp/测试 文件.bin');
  await page.waitForSelector('#files li', { timeout: 30000 }).catch(async e => { await page.screenshot({ path: '/artifacts/fail.png', fullPage: true }); throw new Error(`${e.message.split('\n')[0]} | ${await page.textContent('main')} | ${errors.join(' | ')}`); });
  const text = await page.textContent('#files');
  const sha = crypto.createHash('sha256').update(data).digest('hex');
  if (!text.includes('测试 文件.bin') || !text.includes(sha)) throw new Error('列表缺少文件名或 SHA-256');
  if (!dropped) throw new Error('未触发断流');
  await page.screenshot({ path: '/artifacts/list.png', fullPage: true });
  const [download] = await Promise.all([page.waitForEvent('download'), page.click('#files button.primary')]);
  if (download.suggestedFilename() !== '测试 文件.bin') throw new Error(`文件名不符：${download.suggestedFilename()}`);
  const got = fs.readFileSync(await download.path());
  if (!got.equals(data)) throw new Error('下载内容不一致');
  page.once('dialog', d => d.accept());
  await page.click('#files button.danger');
  await page.waitForSelector('#empty:not([hidden])');
  if (errors.length) throw new Error(`页面错误：${errors.join(' | ')}`);
  console.log('browser_ok: upload with resume, sha256, download, delete');
  await browser.close();
})().catch(e => { console.error('browser_fail', e.message); process.exit(1); });
