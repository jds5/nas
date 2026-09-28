import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../deploy/worker.mjs';
const env = { PUBLIC_ORIGIN: 'https://terminal.example.test', ORIGIN_URL: 'https://origin-terminal.example.test:8443', ORIGIN_SECRET: 'a'.repeat(64) };
test('Worker fails closed and rejects alternate hosts and unknown methods', async () => {
  assert.equal((await worker.fetch(new Request(env.PUBLIC_ORIGIN), {})).status, 503);
  assert.equal((await worker.fetch(new Request('https://other.example.test/'), env)).status, 403);
  assert.equal((await worker.fetch(new Request(env.PUBLIC_ORIGIN, { method: 'DELETE' }), env)).status, 403);
});
test('Worker preserves browser Origin, assertion and WS subprotocol while overwriting origin secret', async () => {
  const originalFetch = globalThis.fetch;
  let forwarded;
  globalThis.fetch = async request => { forwarded = request; return new Response('upstream'); };
  try {
    const request = new Request(`${env.PUBLIC_ORIGIN}/manage/ws`, { headers: { Origin: env.PUBLIC_ORIGIN,
      'Cf-Access-Jwt-Assertion': 'test-assertion', Upgrade: 'websocket', 'Sec-WebSocket-Protocol': 'nas-terminal.v1, ticket.test',
      'X-Nas-Terminal-Origin': 'attacker-value' } });
    await worker.fetch(request, env);
    assert.equal(forwarded.url, `${env.ORIGIN_URL}/manage/ws`);
    assert.equal(forwarded.headers.get('Origin'), env.PUBLIC_ORIGIN);
    assert.equal(forwarded.headers.get('Cf-Access-Jwt-Assertion'), 'test-assertion');
    assert.equal(forwarded.headers.get('Sec-WebSocket-Protocol'), 'nas-terminal.v1, ticket.test');
    assert.equal(forwarded.headers.get('Upgrade'), 'websocket');
    assert.equal(forwarded.headers.get('X-Nas-Terminal-Origin'), env.ORIGIN_SECRET);
    assert.equal(forwarded.redirect, 'manual');
  } finally { globalThis.fetch = originalFetch; }
});
