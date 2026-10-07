import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { connectTerminal } from '../server/terminal.mjs';

function fixture(t, duration = 86400000) {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout', 'setInterval'], now: 1000000 });
  const ws = new EventEmitter();
  Object.assign(ws, { readyState: 1, bufferedAmount: 0, messages: [], pings: 0,
    send(raw) { this.messages.push(JSON.parse(raw)); }, ping() { this.pings++; },
    close(code) { this.code = code; this.readyState = 3; this.emit('close'); },
    terminate() { this.terminated = true; this.readyState = 3; this.emit('close'); } });
  let kills = 0;
  const peers = new Map([[ws, 'owner']]);
  const tmux = { attach: () => ({ kill() { kills++; }, onData() {}, onExit() {} }), snapshot: async () => null, exists: async () => true };
  connectTerminal({ ws, ref: {}, user: { exp: 1001 }, tmux, peers, maxDurationMs: duration });
  t.after(() => ws.emit('close'));
  return { ws, peers, kills: () => kills };
}

test('authenticated connection survives JWT expiry and switching, but ends at fixed 24h deadline', async t => {
  const { ws, peers, kills } = fixture(t);
  const deadline = ws.messages.find(m => m.type === 'ready').expires;
  assert.equal(deadline, 1000000 + 86400000);
  for (let i = 0; i < 5759; i++) {
    ws.emit('pong'); t.mock.timers.tick(15000);
  }
  assert.equal(ws.readyState, 1);
  ws.emit('message', Buffer.from(JSON.stringify({ type: 'switch', epoch: 1, ref: {}, cols: 80, rows: 24 })), false);
  await Promise.resolve();
  assert.equal(ws.messages.filter(m => m.type === 'ready').at(-1).expires, deadline);
  ws.emit('pong'); t.mock.timers.tick(15000);
  assert.equal(ws.code, 4001); assert.equal(peers.size, 0); assert.equal(kills(), 2);
});

test('heartbeat tolerates brief loss, recovers with pong and cleans up after 90 seconds without pong', t => {
  const { ws, peers, kills } = fixture(t);
  for (let i = 0; i < 5; i++) t.mock.timers.tick(15000);
  assert.equal(ws.readyState, 1); assert.equal(ws.pings, 5);
  ws.emit('pong');
  for (let i = 0; i < 5; i++) t.mock.timers.tick(15000);
  assert.equal(ws.readyState, 1);
  t.mock.timers.tick(15000);
  assert.equal(ws.terminated, true); assert.equal(peers.size, 0); assert.equal(kills(), 1);
});
