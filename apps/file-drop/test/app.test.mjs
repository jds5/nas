import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createHash, randomBytes } from 'node:crypto';
import { mkdtemp, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { generateKeyPair, exportJWK, SignJWT, createLocalJWKSet } from 'jose';
import { createApp } from '../server/app.mjs';
import { accessConfig } from '../server/auth.mjs';
import { Store, cleanName } from '../server/store.mjs';
import { Tokens } from '../server/tokens.mjs';

const config = { issuer: 'https://test.cloudflareaccess.com', audience: 'drop-test-only', origin: 'https://drop.example.test', emails: ['owner@example.test'] };
const directOrigin = 'https://dl.example.test:8443';
let app, page, direct, dir, store, key, clock = Date.now();
const now = () => clock;

async function jwt(overrides = {}, signer = key) {
  const t = Math.floor(Date.now() / 1000);
  return new SignJWT({ email: config.emails[0], sub: 'owner', type: 'app', iat: t, exp: t + 300, iss: config.issuer, aud: config.audience, ...overrides })
    .setProtectedHeader({ alg: 'RS256', kid: 'test' }).sign(signer);
}
async function call(path, { assertion, method, body, origin = config.origin, csrf = config.origin } = {}) {
  return fetch(page + path, { method: method || 'GET', headers: {
    ...(assertion ? { 'cf-access-jwt-assertion': assertion } : {}),
    ...(origin ? { origin } : {}), ...(csrf ? { 'x-drop-csrf': csrf } : {}),
    ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
  }, body: body !== undefined ? JSON.stringify(body) : undefined });
}
const directPath = url => url.replace(directOrigin, direct);
async function begin(name, size) {
  const response = await call('/api/uploads', { assertion: await jwt(), method: 'POST', body: { name, size } });
  return { status: response.status, ...(await response.json()) };
}
function put(job, offset, data, { token = job.token, origin = config.origin } = {}) {
  return fetch(`${directPath(job.url)}?offset=${offset}`, { method: 'PUT', body: data,
    headers: { 'x-drop-token': token, ...(origin ? { origin } : {}) } });
}
async function uploadAll(name, data) {
  const job = await begin(name, data.length);
  assert.equal(job.status, 201);
  for (let offset = 0; offset < data.length; offset += job.chunk) {
    const response = await put(job, offset, data.subarray(offset, offset + job.chunk));
    assert.equal(response.status, 200);
  }
  return job;
}
async function link(id) {
  const response = await call(`/api/files/${id}/link`, { assertion: await jwt(), method: 'POST', body: {} });
  return { status: response.status, ...(await response.json()) };
}

before(async () => {
  const pair = await generateKeyPair('RS256');
  key = pair.privateKey;
  const keyResolver = createLocalJWKSet({ keys: [{ ...await exportJWK(pair.publicKey), kid: 'test' }] });
  dir = await mkdtemp(`${tmpdir()}/file-drop-test-`);
  store = new Store(dir, { maxFile: 1000, quota: 2500, maxFiles: 5, chunk: 400, minFree: 0 }, now);
  await store.init();
  app = createApp({ config, keyResolver, store, directOrigin, tokens: new Tokens(undefined, now) });
  for (const server of [app.page, app.direct]) { server.listen(0, '127.0.0.1'); await once(server, 'listening'); }
  page = `http://127.0.0.1:${app.page.address().port}`;
  direct = `http://127.0.0.1:${app.direct.address().port}`;
});
after(async () => {
  app.page.close(); app.direct.close(); app.page.closeAllConnections(); app.direct.closeAllConnections();
  await rm(dir, { recursive: true, force: true });
});

test('配置缺项、非 HTTPS 或带路径时拒绝', () => {
  const env = { CF_ACCESS_TEAM_DOMAIN: config.issuer, CF_ACCESS_AUD: config.audience, CF_ACCESS_ALLOWED_ORIGIN: config.origin, CF_ACCESS_ALLOWED_EMAILS: config.emails[0] };
  assert.deepEqual(accessConfig(env), config);
  for (const name of Object.keys(env)) assert.equal(accessConfig({ ...env, [name]: '' }), null);
  assert.equal(accessConfig({ ...env, CF_ACCESS_ALLOWED_ORIGIN: 'http://drop.example.test' }), null);
});

test('页面与接口：缺少、伪造、过期、错误 aud / iss / 邮箱的 JWT 一律 403', async () => {
  const other = (await generateKeyPair('RS256')).privateKey;
  const t = Math.floor(Date.now() / 1000);
  const bad = [undefined, 'not-a-jwt', await jwt({}, other), await jwt({ exp: t - 10, iat: t - 400 }),
    await jwt({ aud: 'other' }), await jwt({ iss: 'https://evil.cloudflareaccess.com' }), await jwt({ email: 'x@example.test' })];
  for (const assertion of bad) {
    for (const path of ['/', '/app.js', '/api/files']) assert.equal((await call(path, { assertion })).status, 403);
  }
  const ok = await call('/', { assertion: await jwt() });
  assert.equal(ok.status, 200);
  assert.match(ok.headers.get('content-security-policy'), /script-src 'self'/);
  const list = await (await call('/api/files', { assertion: await jwt() })).json();
  assert.ok(Array.isArray(list.files));
  assert.equal(list.limits.maxFile, 1000);
  assert.match(ok.headers.get('content-security-policy'), /connect-src 'self' https:\/\/dl\.example\.test:8443/);
});

test('未配置 Access 时页面全部拒绝，直连端全部 404', async () => {
  const bare = createApp({ config: null, store, directOrigin });
  for (const server of [bare.page, bare.direct]) { server.listen(0, '127.0.0.1'); await once(server, 'listening'); }
  assert.equal((await fetch(`http://127.0.0.1:${bare.page.address().port}/`, { headers: { 'cf-access-jwt-assertion': await jwt() } })).status, 403);
  assert.equal((await fetch(`http://127.0.0.1:${bare.direct.address().port}/d/${'a'.repeat(32)}/x`)).status, 404);
  bare.page.close(); bare.direct.close();
});

test('写操作必须同时带正确 Origin 与 CSRF 头', async () => {
  const assertion = await jwt();
  for (const headers of [{ origin: null }, { csrf: null }, { origin: 'https://evil.test' }, { csrf: 'https://evil.test' }]) {
    const response = await call('/api/uploads', { assertion, method: 'POST', body: { name: 'a', size: 1 }, ...headers });
    assert.equal(response.status, 403);
  }
  assert.equal((await call('/api/uploads', { assertion, method: 'PUT', body: {} })).status, 405);
  assert.equal(store.usage().files, 0);
});

test('分片上传、偏移校验、SHA-256、完整与 Range 下载', async () => {
  const data = randomBytes(950);
  const job = await uploadAll('../../etc/passwd\u202e名字.txt', data);
  const [entry] = store.list();
  assert.equal(entry.name, 'passwd名字.txt');
  assert.equal(entry.sha256, createHash('sha256').update(data).digest('hex'));
  assert.deepEqual(await readdir(`${dir}/blobs`), [job.id]);
  assert.equal((await stat(`${dir}/blobs/${job.id}`)).mode & 0o777, 0o600);

  const { url } = await link(job.id);
  const full = await fetch(directPath(url));
  assert.equal(full.status, 200);
  assert.deepEqual(Buffer.from(await full.arrayBuffer()), data);
  assert.equal(full.headers.get('content-type'), 'application/octet-stream');
  assert.match(full.headers.get('content-disposition'), /^attachment; filename="passwd__.txt"; filename\*=UTF-8''passwd%E5%90%8D%E5%AD%97\.txt$/);
  assert.equal(full.headers.get('x-content-type-options'), 'nosniff');
  const part = await fetch(directPath(url), { headers: { range: 'bytes=100-199' } });
  assert.equal(part.status, 206);
  assert.equal(part.headers.get('content-range'), 'bytes 100-199/950');
  assert.deepEqual(Buffer.from(await part.arrayBuffer()), data.subarray(100, 200));
  const tail = await fetch(directPath(url), { headers: { range: 'bytes=-50' } });
  assert.deepEqual(Buffer.from(await tail.arrayBuffer()), data.subarray(900));
  assert.equal((await fetch(directPath(url), { headers: { range: 'bytes=2000-' } })).status, 416);
  await call(`/api/files/${job.id}`, { assertion: await jwt(), method: 'DELETE' });
});

test('上传：错误偏移 409、无令牌 / 错令牌 / 错 Origin 拒绝，断点后续签继续', async () => {
  const data = randomBytes(700);
  const job = await begin('a.bin', data.length);
  assert.equal((await put(job, 0, data.subarray(0, 400))).status, 200);
  const wrong = await put(job, 0, data.subarray(0, 300));
  assert.equal(wrong.status, 409);
  assert.equal((await wrong.json()).received, 400);
  assert.equal((await put(job, 400, data.subarray(400), { token: 'x' })).status, 403);
  const other = await begin('b.bin', 10);
  assert.equal((await put(job, 400, data.subarray(400), { token: other.token })).status, 403);
  assert.equal((await put(job, 400, data.subarray(400), { origin: 'https://evil.test' })).status, 403);
  assert.equal((await put(job, 400, data.subarray(400), { origin: null })).status, 403);
  const renew = await call(`/api/uploads/${job.id}/token`, { assertion: await jwt(), method: 'POST', body: {} });
  const { token, received } = await renew.json();
  assert.equal(received, 400);
  assert.equal((await put(job, 400, data.subarray(400), { token })).status, 200);
  // 上传令牌不能用于下载，未完成文件不能生成下载链接。
  assert.equal((await fetch(`${direct}/d/${job.id}/a.bin?t=${job.token}`)).status, 403);
  assert.equal((await link(other.id)).status, 404);
  for (const id of [job.id, other.id]) await call(`/api/files/${id}`, { assertion: await jwt(), method: 'DELETE' });
});

test('分片超长、超出声明大小、单文件与总量上限', async () => {
  assert.equal((await begin('big', 1001)).status, 413);
  assert.equal((await begin('zero', 0)).status, 400);
  const job = await begin('a', 500);
  assert.equal((await put(job, 0, randomBytes(401))).status, 413);
  await put(job, 0, randomBytes(400));
  assert.equal((await put(job, 400, randomBytes(200))).status, 413);
  const b = await begin('b', 300), c = await begin('c', 300);
  assert.equal(b.status, 201); assert.equal(c.status, 201);
  assert.equal((await begin('d', 1)).status, 429); // 每人最多 3 个进行中的上传
  for (const id of [job.id, b.id, c.id]) await call(`/api/files/${id}`, { assertion: await jwt(), method: 'DELETE' });
  const d = await uploadAll('d', randomBytes(1000)), e = await uploadAll('e', randomBytes(1000));
  assert.equal((await begin('f', 600)).status, 507); // 2000 + 600 > 2500
  for (const id of [d.id, e.id]) await call(`/api/files/${id}`, { assertion: await jwt(), method: 'DELETE' });
});

test('下载令牌：过期、篡改、换文件均拒绝；删除后 404；其他路径与方法 404', async () => {
  const job = await uploadAll('x.txt', Buffer.from('hello'));
  const { url } = await link(job.id);
  const token = new URL(url).searchParams.get('t');
  assert.equal((await fetch(directPath(url).replace(/t=.*/, `t=${token.slice(0, -2)}xx`))).status, 403);
  const other = await uploadAll('y.txt', Buffer.from('world'));
  assert.equal((await fetch(`${direct}/d/${other.id}/y.txt?t=${token}`)).status, 403);
  for (const [method, path] of [['GET', '/'], ['GET', '/api/files'], ['POST', `/d/${job.id}/x.txt?t=${token}`], ['DELETE', `/u/${job.id}`], ['GET', `/u/${job.id}`]]) {
    assert.equal((await fetch(direct + path, { method })).status, 404);
  }
  const pre = await fetch(`${direct}/u/${job.id}`, { method: 'OPTIONS', headers: { origin: 'https://evil.test' } });
  assert.equal(pre.status, 403);
  const preOK = await fetch(`${direct}/u/${job.id}`, { method: 'OPTIONS', headers: { origin: config.origin } });
  assert.equal(preOK.headers.get('access-control-allow-origin'), config.origin);
  clock += 6 * 60 * 1000;
  assert.equal((await fetch(directPath(url))).status, 403);
  clock -= 6 * 60 * 1000;
  assert.equal((await call(`/api/files/${job.id}`, { assertion: await jwt(), method: 'DELETE' })).status, 204);
  assert.equal((await fetch(directPath(url))).status, 404);
  await call(`/api/files/${other.id}`, { assertion: await jwt(), method: 'DELETE' });
});

test('12 小时到期：列表隐藏、下载拒绝、清理删除文件与孤立文件', async () => {
  const job = await uploadAll('old.txt', Buffer.from('old'));
  const { url } = await link(job.id);
  clock += 12 * 60 * 60 * 1000;
  assert.equal(store.list().length, 0);
  assert.equal((await link(job.id)).status, 404);
  const removed = await store.sweep();
  assert.deepEqual(removed.map(x => x.id), [job.id]);
  assert.deepEqual(await readdir(`${dir}/blobs`), []);
  clock -= 12 * 60 * 60 * 1000;
  assert.equal((await fetch(directPath(url))).status, 404);
  // 元数据重新加载后仍一致
  const again = new Store(dir, store.options, now);
  await again.init();
  assert.equal(again.usage().files, 0);
});

test('文件名清洗', () => {
  assert.equal(cleanName('C:\\Users\\a\\报告.pdf'), '报告.pdf');
  assert.equal(cleanName('...hidden'), 'hidden');
  assert.equal(cleanName('a\u0000b\nc'), 'abc');
  assert.equal(cleanName(''), 'file');
  assert.equal(cleanName(42), 'file');
  assert.equal([...cleanName('长'.repeat(500))].length, 200);
});
