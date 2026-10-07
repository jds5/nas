import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canRecover, closeDescription } from '../client/connection.mjs';
test('only transient tmux disconnects recover within deadline and three-attempt budget', () => {
  const ref = { id: '$1' };
  for (const code of [1006, 1012, 1013, 4000]) assert.equal(canRecover(ref, code, 2000, 0, 1000), true);
  for (const code of [1000, 1008, 4001, 4002]) assert.equal(canRecover(ref, code, 2000, 0, 1000), false);
  assert.equal(canRecover({ kind: 'ssh' }, 1006, 2000, 0, 1000), false);
  assert.equal(canRecover(ref, 1006, 1000, 0, 1000), false);
  assert.equal(canRecover(ref, 1006, 2000, 3, 1000), false);
  assert.equal(closeDescription(1008, 'invalid_ack'), '终端输出确认异常');
  assert.equal(closeDescription(1006, '<secret>'), '网络或代理连接中断');
});
