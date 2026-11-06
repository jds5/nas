import http from 'node:http';
import { createReadStream } from 'node:fs';
import { appendFile, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { authorizer } from './auth.mjs';
import { StoreError, validId } from './store.mjs';
import { Tokens } from './tokens.mjs';

const staticRoot = fileURLToPath(new URL('../public/', import.meta.url));
const pages = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['/app.css', ['app.css', 'text/css; charset=utf-8']],
]);
const base = {
  'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
  'X-Frame-Options': 'DENY', 'Cross-Origin-Opener-Policy': 'same-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
};
const UPLOAD_TTL = 30 * 60 * 1000, DOWNLOAD_TTL = 5 * 60 * 1000;

function reply(res, code, value, extra = {}) {
  res.writeHead(code, { ...base, ...extra, 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(value));
}
function deny(res, code = 404, extra = {}) {
  res.writeHead(code, { ...base, ...extra, 'Content-Length': 0 });
  res.end();
}
async function json(req) {
  if (req.headers['content-type'] !== 'application/json') throw new StoreError(415, 'bad_body');
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 4096) throw new StoreError(413, 'bad_body');
  }
  let value;
  try { value = JSON.parse(raw); } catch { throw new StoreError(400, 'bad_body'); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new StoreError(400, 'bad_body');
  return value;
}
// RFC 5987：ASCII 兜底名 + UTF-8 原名。
function disposition(name) {
  const ascii = name.replace(/[^\x20-\x7e]/g, '_').replace(/["\\%;]/g, '_');
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(name)}`;
}
function range(header, size) {
  if (!header) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(header);
  if (!match || (!match[1] && !match[2])) return false;
  let start, end;
  if (!match[1]) { start = Math.max(0, size - Number(match[2])); end = size - 1; }
  else { start = Number(match[1]); end = match[2] ? Math.min(Number(match[2]), size - 1) : size - 1; }
  return start <= end && start < size ? { start, end } : false;
}

// page：drop 子域名，经 Cloudflare Access，每个请求验 JWT；写操作再验 Origin 与 CSRF 头。
// direct：dl 子域名，灰云直连，只接受带有效短期令牌的上传分片与下载，其他一律 404。
export function createApp({ config, keyResolver, store, directOrigin, tokens = new Tokens(), alerts = null, logFile = null }) {
  // 日志同时写 stdout 和数据目录中的文件；容器重建后文件仍在，不含 JWT、令牌与查询串。
  const log = event => {
    const line = JSON.stringify({ time: new Date().toISOString(), ...event });
    console.log(line);
    if (logFile) appendFile(logFile, `${line}\n`, { mode: 0o600 }).catch(() => {});
  };
  const audit = event => log({ event: 'audit', ...event });
  // 新来源 IP 告警：失败只记日志，不影响请求。
  const notice = (ip, info) => alerts?.seen(ip, info).catch(error =>
    console.error(JSON.stringify({ event: 'alert_error', code: error.code || 'internal' })));
  const authenticate = authorizer(config, keyResolver);
  const csp = `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; ` +
    `connect-src 'self' ${directOrigin}; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`;
  const cache = new Map();
  const asset = file => {
    if (!cache.has(file)) cache.set(file, readFile(`${staticRoot}${file}`).catch(error => { cache.delete(file); throw error; }));
    return cache.get(file);
  };
  const writeOK = req => req.headers.origin === config.origin && req.headers['x-drop-csrf'] === config.origin;

  const page = http.createServer({ maxHeaderSize: 24576, requestTimeout: 60000, headersTimeout: 10000 }, async (req, res) => {
    try {
      const user = await authenticate(req);
      if (!user) {
        // 经 Cloudflare 的请求在 Access 已被拦下，到这里的多为绕过 Cloudflare 直连源站的尝试。
        log({ event: 'page_reject', method: req.method, path: String(req.url).split('?')[0].slice(0, 80), ip: req.headers['x-real-ip'] || '' });
        req.resume();
        return reply(res, 403, { error: '请重新登录' });
      }
      const ip = req.headers['cf-connecting-ip'] || '';
      const country = req.headers['cf-ipcountry'] || '';
      notice(ip, { via: 'page', action: `${req.method} ${String(req.url).split('?')[0].slice(0, 40)}`, email: user.email, country });
      const url = new URL(req.url, 'http://drop.invalid');
      if (req.method === 'GET' || req.method === 'HEAD') {
        if (pages.has(req.url)) {
          const [file, type] = pages.get(req.url);
          const data = await asset(file);
          res.writeHead(200, { ...base, 'Content-Security-Policy': csp, 'Content-Type': type, 'Content-Length': data.length });
          return res.end(req.method === 'HEAD' ? undefined : data);
        }
        if (req.url === '/api/files') {
          return reply(res, 200, { files: store.list(), used: store.usage().used, limits: store.options });
        }
        return deny(res);
      }
      if (req.method !== 'POST' && req.method !== 'DELETE') return deny(res, 405);
      if (!writeOK(req)) return reply(res, 403, { error: '来源校验失败' });
      const parts = url.pathname.split('/');
      if (req.method === 'POST' && url.pathname === '/api/uploads') {
        const { name, size } = await json(req);
        const entry = await store.create({ name, size, email: user.email, ip, country });
        audit({ action: 'upload_start', email: user.email, id: entry.id, size, ip, country });
        return reply(res, 201, { id: entry.id, name: entry.name, chunk: store.options.chunk,
          token: tokens.sign('up', entry.id, UPLOAD_TTL), url: `${directOrigin}/u/${entry.id}` });
      }
      // /api/uploads/<id>/token：令牌过期或断点续传时续签，返回服务器已收字节数。
      if (req.method === 'POST' && parts.length === 5 && parts[2] === 'uploads' && parts[4] === 'token' && !url.search) {
        const entry = store.pending(parts[3]);
        if (!entry) return reply(res, 404, { error: '上传不存在或已结束' });
        return reply(res, 200, { token: tokens.sign('up', entry.id, UPLOAD_TTL), received: entry.received });
      }
      // /api/files/<id>/link：生成 5 分钟有效的直连下载地址。
      if (req.method === 'POST' && parts.length === 5 && parts[2] === 'files' && parts[4] === 'link' && !url.search) {
        const entry = store.complete(parts[3]);
        if (!entry) return reply(res, 404, { error: '文件不存在或已过期' });
        audit({ action: 'download_link', email: user.email, id: entry.id, ip, country });
        const token = tokens.sign('dl', entry.id, DOWNLOAD_TTL);
        return reply(res, 200, { url: `${directOrigin}/d/${entry.id}/${encodeURIComponent(entry.name)}?t=${token}` });
      }
      if (req.method === 'DELETE' && parts.length === 4 && parts[2] === 'files' && !url.search) {
        if (!await store.remove(parts[3])) return reply(res, 404, { error: '文件不存在或已过期' });
        audit({ action: 'delete', email: user.email, id: parts[3], ip, country });
        return deny(res, 204);
      }
      return deny(res);
    } catch (error) {
      if (error instanceof StoreError) return reply(res, error.status, { error: error.message });
      console.error(JSON.stringify({ event: 'page_error', code: error.code || 'internal' }));
      if (!res.headersSent) reply(res, 500, { error: '服务器错误' }); else res.destroy();
    }
  });

  const cors = { 'Access-Control-Allow-Origin': config?.origin || 'null', Vary: 'Origin' };
  const direct = http.createServer({ maxHeaderSize: 8192, requestTimeout: 0, headersTimeout: 10000 }, async (req, res) => {
    const ip = req.headers['x-real-ip'] || '';
    const url = new URL(req.url, 'http://dl.invalid');
    const parts = url.pathname.split('/');
    const reject = (reason, code = 404, extra = {}) => {
      log({ event: 'direct_reject', method: req.method, path: url.pathname.slice(0, 80), reason, ip });
      req.resume();
      deny(res, code, extra);
    };
    try {
      if (!config) return reject('unconfigured');
      // 上传：PUT /u/<id>?offset=N，令牌在 X-Drop-Token 头，只允许 drop 页面跨域调用。
      if (parts.length === 3 && parts[1] === 'u' && validId(parts[2])) {
        if (req.method === 'OPTIONS') {
          if (req.headers.origin !== config.origin) return reject('preflight_origin', 403);
          return deny(res, 204, { ...cors, 'Access-Control-Allow-Methods': 'PUT',
            'Access-Control-Allow-Headers': 'x-drop-token, content-type', 'Access-Control-Max-Age': '600' });
        }
        if (req.method !== 'PUT') return reject('method');
        if (req.headers.origin !== config.origin) return reject('origin', 403);
        if (!tokens.verify(req.headers['x-drop-token'], 'up', parts[2])) return reject('token', 403, cors);
        notice(ip, { via: 'direct', action: 'upload' });
        const offset = Number(url.searchParams.get('offset'));
        const length = Number(req.headers['content-length']);
        if (!/^\d+$/.test(url.searchParams.get('offset') || '') || [...url.searchParams.keys()].length !== 1) return reject('query', 400, cors);
        try {
          const entry = await store.write(parts[2], offset, length, req);
          if (entry.sha256) audit({ action: 'upload_done', id: entry.id, size: entry.size, ip });
          return reply(res, 200, { received: entry.received, sha256: entry.sha256 }, cors);
        } catch (error) {
          if (!(error instanceof StoreError)) throw error;
          const entry = store.pending(parts[2]);
          req.resume();
          return reply(res, error.status, { error: error.message, received: entry?.received ?? null }, { ...cors, Connection: 'close' });
        }
      }
      // 下载：GET/HEAD /d/<id>/<文件名>?t=令牌；令牌只在请求开始时检查，传输途中过期不打断。
      if (parts.length === 4 && parts[1] === 'd' && validId(parts[2])) {
        if (req.method !== 'GET' && req.method !== 'HEAD') return reject('method');
        if (!tokens.verify(url.searchParams.get('t'), 'dl', parts[2])) return reject('token', 403);
        notice(ip, { via: 'direct', action: 'download' });
        const entry = store.complete(parts[2]);
        if (!entry) return reject('gone');
        const r = range(req.headers.range, entry.size);
        const headers = { ...base, 'Content-Type': 'application/octet-stream', 'Content-Disposition': disposition(entry.name),
          'Content-Security-Policy': "default-src 'none'; sandbox", 'Accept-Ranges': 'bytes' };
        if (r === false) return deny(res, 416, { ...headers, 'Content-Range': `bytes */${entry.size}` });
        const { start, end } = r || { start: 0, end: entry.size - 1 };
        res.writeHead(r ? 206 : 200, { ...headers, 'Content-Length': end - start + 1,
          ...(r ? { 'Content-Range': `bytes ${start}-${end}/${entry.size}` } : {}) });
        if (req.method === 'HEAD') return res.end();
        if (start === 0) audit({ action: 'download', id: entry.id, size: entry.size, ip });
        const stream = createReadStream(store.path(entry.id), { start, end });
        stream.on('error', () => res.destroy());
        res.on('close', () => stream.destroy());
        return stream.pipe(res);
      }
      return reject('path');
    } catch (error) {
      console.error(JSON.stringify({ event: 'direct_error', code: error.code || 'internal' }));
      if (!res.headersSent) deny(res, 500, { Connection: 'close' }); else res.destroy();
    }
  });
  return { page, direct, tokens };
}
