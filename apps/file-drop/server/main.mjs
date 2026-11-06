import { accessConfig } from './auth.mjs';
import { createApp } from './app.mjs';
import { Store } from './store.mjs';

const config = accessConfig(process.env);
if (!config) console.warn('access_config_invalid: all requests denied');
let directOrigin = 'https://invalid.invalid';
try {
  const url = new URL(process.env.DIRECT_ORIGIN);
  if (url.protocol === 'https:' && url.href === `${url.origin}/`) directOrigin = url.origin;
  else throw new Error();
} catch { console.warn('direct_origin_invalid'); }

const store = new Store(process.env.DATA_DIR || '/data');
await store.init();
const app = createApp({ config, store, directOrigin });
app.direct.timeout = 120000; // 传输中 2 分钟无数据才断开
app.page.listen(3000, '0.0.0.0', () => console.log('file_drop_page_listening'));
app.direct.listen(3001, '0.0.0.0', () => console.log('file_drop_direct_listening'));

// 每 10 分钟清理到期文件（含未完成上传）。
const timer = setInterval(async () => {
  try {
    for (const entry of await store.sweep()) console.log(JSON.stringify({ event: 'audit', time: new Date().toISOString(), action: 'expire', id: entry.id, size: entry.size }));
  } catch (error) { console.error(JSON.stringify({ event: 'sweep_error', code: error.code || 'internal' })); }
}, 10 * 60 * 1000);
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => {
  clearInterval(timer);
  app.page.close(); app.direct.close();
  app.page.closeAllConnections(); app.direct.closeAllConnections();
});
