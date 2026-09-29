import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { generateKeyPair, exportJWK, SignJWT, createLocalJWKSet } from 'jose';
import { WebSocket } from 'ws';
import { mkdtemp, rm, mkdir, copyFile, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { createApp } from '../server/app.mjs';
import { accessConfig } from '../server/auth.mjs';
import { UploadStore } from '../server/uploads.mjs';
import { Tmux } from '../server/tmux.mjs';

const config = { issuer: 'https://test.cloudflareaccess.com', audience: 'terminal-test-only', origin: 'https://terminal.example.test', emails: ['owner@example.test'] };
let app, url, tmux, dir, key, keys;
async function token(overrides = {}) {
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ email: config.emails[0], sub: 'owner', type: 'app', iat: now, exp: now + 300, iss: config.issuer, aud: config.audience, ...overrides })
    .setProtectedHeader({ alg: 'RS256', kid: 'test' }).sign(key);
}
async function request(path, { assertion, payload, origin = config.origin, csrf = config.origin, method } = {}) {
  return fetch(url + path, { method: method || (payload ? 'POST' : 'GET'), headers: {
    ...(assertion ? { 'cf-access-jwt-assertion': assertion } : {}), ...(origin ? { origin } : {}),
    ...(csrf ? { 'x-nas-csrf-origin': csrf } : {}), ...(payload ? { 'content-type': 'application/json' } : {}),
  }, body: payload ? JSON.stringify(payload) : undefined });
}
async function reservation(assertion, ref) {
  const response = await request('/terminal/manage/api/connections', { assertion, payload: ref });
  assert.equal(response.status, 201);
  return (await response.json()).ticket;
}
function wsConnect(assertion, ticket, origin = config.origin) {
  return new WebSocket(url.replace('http:', 'ws:') + '/terminal/manage/ws', ['nas-terminal.v1', `ticket.${ticket}`],
    { origin, headers: { 'cf-access-jwt-assertion': assertion } });
}
async function deniedWS(ws) {
  return new Promise((resolve, reject) => {
    ws.once('unexpected-response', (_req, res) => { res.resume(); ws.terminate(); resolve(res.statusCode); });
    ws.once('open', () => { ws.terminate(); reject(new Error('unexpected upgrade')); });
    ws.on('error', () => {});
  });
}
before(async () => {
  const pair = await generateKeyPair('RS256'); key = pair.privateKey;
  keys = createLocalJWKSet({ keys: [{ ...await exportJWK(pair.publicKey), kid: 'test' }] });
  dir = await mkdtemp(`${tmpdir()}/nas-terminal-test-`); tmux = new Tmux(`${dir}/tmux`);
  await tmux.command(['-f', '/dev/null', 'new-session', '-d', '-s', 'web-test', '/bin/sh']);
  await mkdir(`${dir}/uploads`);
  app = createApp({ config, keyResolver: keys, tmux, uploads: new UploadStore(`${dir}/uploads`, `${dir}/uploads`) });
  app.server.listen(0, '127.0.0.1'); await once(app.server, 'listening');
  url = `http://127.0.0.1:${app.server.address().port}`;
});
after(async () => { app?.shutdown(); await tmux?.command(['kill-server']); await rm(dir, { recursive: true, force: true }); });

test('configuration rejects missing values, non-HTTPS origins and issuer paths', () => {
  const env = { CF_ACCESS_TEAM_DOMAIN: config.issuer, CF_ACCESS_AUD: config.audience, CF_ACCESS_ALLOWED_ORIGIN: config.origin, CF_ACCESS_ALLOWED_EMAILS: config.emails.join(',') };
  assert.deepEqual(accessConfig(env), config);
  for (const name of Object.keys(env)) assert.equal(accessConfig({ ...env, [name]: '' }), null);
  assert.equal(accessConfig({ ...env, CF_ACCESS_TEAM_DOMAIN: `${config.issuer}/other` }), null);
  assert.equal(accessConfig({ ...env, CF_ACCESS_ALLOWED_ORIGIN: 'http://terminal.example.test' }), null);
});
test('all paths, assets and methods require a valid signed human identity', async () => {
  for (const path of ['/terminal', '/terminal/', '/terminal/assets/app.js', '/terminal/assets/app.css', '/terminal/manage/api/sessions', '/unknown']) {
    const response = await request(path);
    assert.equal(response.status, 403); assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal((await request(path, { assertion: 'forged' })).status, 403);
  }
  const now = Math.floor(Date.now() / 1000);
  for (const claims of [{ exp: now - 1 }, { aud: 'home-audience' }, { iss: 'https://other.cloudflareaccess.com' },
    { nbf: now + 120 }, { email: 'stranger@example.test' }, { type: 'service' }, { exp: undefined }, { sub: undefined }, { iat: now + 120 }]) {
    assert.equal((await request('/terminal/manage/api/sessions', { assertion: await token(claims) })).status, 403);
  }
  const assertion = await token();
  for (const method of ['OPTIONS', 'PUT', 'DELETE', 'PATCH', 'POST']) assert.equal((await request('/terminal', { assertion, method })).status, 403);
  assert.equal((await request('/terminal', { assertion })).status, 200);
  assert.equal((await request('/terminal/', { assertion })).status, 200);
  for (const path of ['/', '/assets/app.js', '/manage/api/sessions', '/terminal-other']) {
    assert.equal((await request(path, { assertion })).status, 404);
  }
  const html = await (await request('/terminal', { assertion })).text();
  assert.ok(html.includes('src="/terminal/assets/app.js"'));
  assert.ok(html.includes('href="/terminal/assets/app.css"'));
  assert.equal((await request('/terminal/assets/app.js', { assertion })).status, 200);
  assert.equal((await request('/terminal/assets/../server/main.mjs', { assertion })).status, 404);
});
test('missing configuration fails closed even with otherwise valid JWT', async () => {
  const isolated = createApp({ config: null, keyResolver: keys, tmux });
  isolated.server.listen(0, '127.0.0.1'); await once(isolated.server, 'listening');
  try { assert.equal((await fetch(`http://127.0.0.1:${isolated.server.address().port}/`, { headers: { 'cf-access-jwt-assertion': await token() } })).status, 403); }
  finally { isolated.shutdown(); }
});
test('POST requires exact Origin and custom CSRF header; stale session rejected without attaching', async () => {
  const assertion = await token(); const ref = (await tmux.list())[0];
  for (const options of [{ origin: '' }, { origin: 'https://evil.test' }, { csrf: '' }, { csrf: 'https://evil.test' }]) {
    assert.equal((await request('/terminal/manage/api/connections', { assertion, payload: ref, ...options })).status, 403);
  }
  assert.equal((await request('/terminal/manage/api/connections', { assertion, payload: { ...ref, generation: 'stale' } })).status, 409);
  assert.equal((await request('/terminal/manage/api/connections', { assertion, payload: { ...ref, id: ';echo bad' } })).status, 409);
  assert.equal((await tmux.list())[0].attached, 0);
});
test('WebSocket requires Origin, JWT, same identity and one-use ticket; real tmux input, resize, detach', { timeout: 15000 }, async () => {
  const assertion = await token(); const ref = (await tmux.list())[0];
  const ticket = await reservation(assertion, ref);
  assert.equal(await deniedWS(wsConnect(assertion, ticket, 'https://evil.test')), 403);
  assert.equal(await deniedWS(wsConnect('forged', ticket)), 403);
  assert.equal(await deniedWS(wsConnect(await token({ sub: 'another' }), ticket)), 403);
  const ws = wsConnect(assertion, ticket);
  let output = '';
  ws.on('message', raw => {
    const message = JSON.parse(raw);
    if (message.type === 'output') {
      output += message.data;
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'ack', bytes: Buffer.byteLength(message.data) }));
    }
  });
  await once(ws, 'open');
  try {
    assert.equal(await deniedWS(wsConnect(assertion, ticket)), 403);
    ws.send(JSON.stringify({ type: 'resize', cols: 91, rows: 29 }));
    ws.send(JSON.stringify({ type: 'input', data: "printf 'NAS_%s_中文\\n' 'WEB_OK'\r" }));
    await waitFor(() => output.includes('NAS_WEB_OK_中文'));
    await waitFor(async () => (await tmux.command(['display-message', '-p', '-t', ref.id, '#{window_width}x#{window_height}'])) === '91x28');
  } finally { ws.close(); await once(ws, 'close'); }
  await waitFor(async () => (await tmux.list())[0].attached === 0);
  assert.equal(await tmux.exists(ref), true);
});
test('connection expiry releases client and leaves the task alive', { timeout: 8000 }, async () => {
  const expiring = createApp({ config, keyResolver: keys, tmux, maxDurationMs: 250 });
  expiring.server.listen(0, '127.0.0.1'); await once(expiring.server, 'listening');
  const previous = url; url = `http://127.0.0.1:${expiring.server.address().port}`;
  try {
    const assertion = await token(); const ref = (await tmux.list())[0];
    const ws = wsConnect(assertion, await reservation(assertion, ref));
    const [code] = await once(ws, 'close'); assert.equal(code, 4001);
    await waitFor(async () => (await tmux.list())[0].attached === 0);
    assert.equal(await tmux.exists(ref), true);
  } finally { url = previous; expiring.shutdown(); }
});
test('oversized input and invalid resize close only that client; no shell command is run', { timeout: 8000 }, async () => {
  const assertion = await token(); const ref = (await tmux.list())[0];
  const ws = wsConnect(assertion, await reservation(assertion, ref));
  await once(ws, 'open');
  ws.send(JSON.stringify({ type: 'resize', cols: -1, rows: 24 }));
  const [code] = await once(ws, 'close'); assert.equal(code, 1008);
  await waitFor(async () => (await tmux.list())[0].attached === 0);
  assert.equal(await tmux.exists(ref), true);
  const oversized = wsConnect(assertion, await reservation(assertion, ref));
  await once(oversized, 'open');
  oversized.send(JSON.stringify({ type: 'input', data: 'x'.repeat(17000) }));
  assert.equal((await once(oversized, 'close'))[0], 1008);
});
test('signed with another key is rejected; no Origin cannot open WebSocket', async () => {
  const other = await generateKeyPair('RS256');
  const forged = await new SignJWT({ email: config.emails[0] }).setProtectedHeader({ alg: 'RS256', kid: 'test' })
    .setIssuer(config.issuer).setAudience(config.audience).setSubject('owner').setIssuedAt().setExpirationTime('5m').sign(other.privateKey);
  assert.equal((await request('/terminal', { assertion: forged })).status, 403);
  const assertion = await token(); const ref = (await tmux.list())[0];
  assert.equal(await deniedWS(wsConnect(assertion, await reservation(assertion, ref), '')), 403);
});
async function waitFor(check) {
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) { if (await check()) return; await new Promise(r => setTimeout(r, 30)); }
  assert.fail('condition did not become true');
}

test('switch reuses WebSocket, separates epochs, preserves both sessions and rejects stale input', { timeout: 10000 }, async () => {
  await tmux.command(['new-session', '-d', '-s', 'switch-test', '/bin/sh']);
  const refs = await tmux.list(), first = refs.find(x => x.name === 'web-test'), second = refs.find(x => x.name === 'switch-test');
  const assertion = await token(), ws = wsConnect(assertion, await reservation(assertion, first));
  const messages = [];
  ws.on('message', raw => {
    const m = JSON.parse(raw); messages.push(m);
    if (m.type === 'output' && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'ack', bytes: Buffer.byteLength(m.data), epoch: m.epoch }));
  });
  try {
    await once(ws, 'open');
    ws.send(JSON.stringify({ type: 'switch', ref: second, epoch: 1, cols: 100, rows: 30 }));
    await waitFor(() => messages.some(x => x.type === 'ready' && x.epoch === 1));
    ws.send(JSON.stringify({ type: 'input', data: "touch /tmp/unused", epoch: 0 }));
    ws.send(JSON.stringify({ type: 'input', data: "printf 'SWITCH_%s_OK\\n' 'SECOND'\r", epoch: 1 }));
    await waitFor(() => messages.filter(x => x.type === 'output' && x.epoch === 1).map(x => x.data).join('').includes('SWITCH_SECOND_OK'));
    const screen = await tmux.command(['capture-pane', '-p', '-t', second.id]);
    assert.ok(!screen.includes('touch /tmp/unused'));
    assert.equal(await tmux.exists(first), true); assert.equal(await tmux.exists(second), true);
  } finally { ws.close(); await once(ws, 'close'); await tmux.command(['kill-session', '-t', second.id]); }
});
test('upload endpoint enforces CSRF; attached Codex receives image path, shell cannot receive image submission', { timeout: 10000 }, async () => {
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlZsAAAAASUVORK5CYII=', 'base64');
  const assertion = await token();
  await copyFile('/bin/cat', `${dir}/codex`); await chmod(`${dir}/codex`, 0o755);
  await tmux.command(['new-session', '-d', '-s', 'image-test', `${dir}/codex`]);
  const ref = (await tmux.list()).find(x => x.name === 'image-test');
  const uploadHeaders = { 'cf-access-jwt-assertion': assertion, origin: config.origin, 'x-nas-csrf-origin': config.origin,
    'content-type': 'image/png', 'x-session-ref': encodeURIComponent(JSON.stringify(ref)), 'x-upload-name': encodeURIComponent('截图.png') };
  const bad = await fetch(url + '/terminal/manage/api/uploads', { method: 'POST', headers: { ...uploadHeaders, origin: 'https://evil.test' }, body: png });
  assert.equal(bad.status, 403);
  const good = await fetch(url + '/terminal/manage/api/uploads', { method: 'POST', headers: uploadHeaders, body: png });
  assert.equal(good.status, 201); const item = await good.json();
  const ws = wsConnect(assertion, await reservation(assertion, ref)); const messages = [];
  ws.on('message', raw => messages.push(JSON.parse(raw)));
  try {
    await once(ws, 'open');
    ws.send(JSON.stringify({ type: 'submit-images', requestId: 'image-message', text: '描述图片', attachments: [item.id], epoch: 0 }));
    await waitFor(() => messages.some(x => x.type === 'submitted'));
    await waitFor(() => messages.filter(x => x.type === 'output').map(x => x.data).join('').includes(item.id + '.png'));
  } finally { ws.close(); await once(ws, 'close'); await tmux.command(['kill-session', '-t', ref.id]); }
  const shellRef = (await tmux.list()).find(x => x.name === 'web-test');
  const shell = wsConnect(assertion, await reservation(assertion, shellRef)); const replies = [];
  shell.on('message', raw => replies.push(JSON.parse(raw)));
  try {
    await once(shell, 'open');
    shell.send(JSON.stringify({ type: 'submit-images', requestId: 'shell-rejected', text: '', attachments: [item.id], epoch: 0 }));
    await waitFor(() => replies.some(x => x.type === 'submission-error'));
    assert.ok(!replies.some(x => x.type === 'submitted'));
  } finally { shell.close(); await once(shell, 'close'); }
});

test('temporary SSH shares authentication and closes its process on disconnect and expiry', async () => {
  const pty = (await import('node-pty')).default;
  let child, killed = false;
  const ssh = {
    exists: async ref => ref?.kind === 'ssh' && ref.id === 'ssh-' + 'a'.repeat(32),
    attach: (_ref, size) => {
      child = pty.spawn('/bin/sh', [], { ...size, cwd: '/tmp', env: { PATH: '/usr/bin:/bin', TERM: 'xterm-256color' } });
      child.onExit(() => { killed = true; }); return child;
    },
  };
  const instance = createApp({ config, keyResolver: keys, tmux: new Tmux('/tmp/absent-socket-for-ssh-test'), ssh, maxDurationMs: 500 });
  instance.server.listen(0, '127.0.0.1'); await once(instance.server, 'listening');
  const previous = url; url = `http://127.0.0.1:${instance.server.address().port}`;
  try {
    const assertion = await token(), ref = { kind: 'ssh', id: 'ssh-' + 'a'.repeat(32), generation: 'ephemeral' };
    assert.equal((await request('/terminal/manage/api/connections', { payload: ref })).status, 403);
    assert.equal((await request('/terminal/manage/api/connections', { assertion, payload: ref, origin: 'https://wrong.test' })).status, 403);
    assert.deepEqual((await (await request('/terminal/manage/api/sessions', { assertion })).json()).sessions, []);
    for (const expire of [false, true]) {
      killed = false;
      const ws = wsConnect(assertion, await reservation(assertion, ref));
      const closed = once(ws, 'close');
      await once(ws, 'message');
      assert.ok(child.pid > 0);
      if (!expire) ws.close();
      await closed;
      for (let i = 0; i < 50 && !killed; i++) await new Promise(r => setTimeout(r, 20));
      assert.equal(killed, true);
      assert.throws(() => process.kill(child.pid, 0), { code: 'ESRCH' });
    }
  } finally { url = previous; instance.shutdown(); }
});
