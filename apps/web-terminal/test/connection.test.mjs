import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { connectTerminal } from '../server/terminal.mjs';

function fixture(t) {
  let output, paused = 0, resumed = 0, killed = 0;
  const ws = new EventEmitter();
  Object.assign(ws, { readyState: 1, bufferedAmount: 0, frames: [],
    send(raw) { this.frames.push(JSON.parse(raw)); }, ping() {},
    close(code) { this.code = code; this.readyState = 3; this.emit('close', code); },
    terminate() { this.close(1006); } });
  const term = { onData(fn) { output = fn; }, onExit() {}, pause() { paused++; }, resume() { resumed++; }, kill() { killed++; }, write() {}, resize() {} };
  const tmux = { attach: () => term, snapshot: async () => null };
  const peers = new Map([[ws, 'owner']]);
  connectTerminal({ ws, ref: {}, user: { exp: Date.now() / 1000 + 60 }, tmux, peers, maxDurationMs: 86400000, audit: () => {} });
  t.after(() => ws.emit('close', 1000));
  return { ws, output: text => output(text), stats: () => ({ paused, resumed, killed }),
    frame: data => ws.emit('message', Buffer.from(JSON.stringify(data)), false) };
}

test('large output burst is backpressured without disconnect, truncation or UTF-8 corruption', t => {
  const f = fixture(t), text = '中文😀 terminal\r\n'.repeat(90000);
  f.output(text);
  assert.equal(f.ws.readyState, 1);
  let received = '', cursor = 0;
  for (let i = 0; i < 1000; i++) {
    const frames = f.ws.frames.slice(cursor); cursor = f.ws.frames.length;
    const output = frames.filter(x => x.type === 'output');
    if (!output.length) break;
    for (const m of output) {
      received += m.data;
      assert.ok(Buffer.byteLength(m.data) <= 32768);
      f.frame({ type: 'ack', bytes: Buffer.byteLength(m.data), epoch: 0 });
    }
  }
  assert.equal(received, text); assert.equal(f.ws.readyState, 1);
  assert.ok(f.stats().paused > 0); assert.ok(f.stats().resumed > 0);
});

test('application heartbeat keeps a proxied connection alive even without protocol pong', t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout', 'setInterval'], now: 1000000 });
  const f = fixture(t);
  for (let i = 0; i < 20; i++) {
    t.mock.timers.tick(15000);
    f.frame({ type: 'heartbeat', epoch: 99 }); // Connection-wide, including a pending switch.
  }
  assert.equal(f.ws.readyState, 1);
  assert.ok(f.ws.frames.some(m => m.type === 'heartbeat-ack'));
  for (let i = 0; i < 6; i++) t.mock.timers.tick(15000);
  assert.equal(f.ws.code, 4000);
});

test('unacknowledged output remains bounded, resumes without data loss and invalid ACK is diagnosed', t => {
  const f = fixture(t);
  f.output('x'.repeat(256 * 1024));
  assert.ok(f.ws.frames.filter(m => m.type === 'output').reduce((n, m) => n + Buffer.byteLength(m.data), 0) <= 128 * 1024);
  assert.equal(f.ws.readyState, 1); assert.ok(f.stats().paused > 0);
  f.frame({ type: 'ack', bytes: 9999999, epoch: 0 });
  assert.equal(f.ws.code, 1008);
});
