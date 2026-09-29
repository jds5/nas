import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SSH, Terminals } from '../server/ssh.mjs';

test('SSH references cannot select arbitrary endpoints or fall through to tmux', async () => {
  const ssh = new SSH();
  for (const ref of [null, {}, { kind: 'ssh', id: '$1', generation: 'ephemeral' }, { kind: 'ssh', id: 'ssh-' + 'a'.repeat(32), generation: 'other' }]) assert.equal(await ssh.exists(ref), false);
  let calls = 0;
  const tmux = { exists: async () => { calls++; return true; }, list: async () => { throw new Error(); } };
  const router = new Terminals(tmux);
  assert.equal(await router.exists({ kind: 'unknown' }), false);
  assert.equal(await router.exists({ kind: 'ssh' }), false);
  assert.equal(calls, 0);
  assert.deepEqual(await router.list(), []);
  assert.equal(await router.exists({ id: '$1' }), true);
});
