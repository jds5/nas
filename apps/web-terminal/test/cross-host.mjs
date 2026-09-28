// Isolated host tmux socket only, mounted by the operator at /run/cross-test/socket.
// The prefix check prevents use with ordinary production sessions.
import assert from 'node:assert/strict';
import { Tmux } from '../server/tmux.mjs';
const tmux = new Tmux('/run/cross-test/socket');
const sessions = await tmux.list();
assert.equal(sessions.length, 1);
assert.ok(sessions[0].name.startsWith('nas-web-cross-test-'));
const ref = sessions[0];
const term = tmux.attach(ref);
let output = '';
const timer = setTimeout(() => { term.kill(); console.error('cross-host PTY timeout'); process.exitCode = 1; }, 5000);
term.onData(data => { output += data; });
term.write("printf 'CROSS_%s_中文\\n' 'HOST_OK'\r");
try {
  for (let i = 0; i < 100 && !output.includes('CROSS_HOST_OK_中文'); i++) await new Promise(r => setTimeout(r, 30));
  assert.ok(output.includes('CROSS_HOST_OK_中文'));
  term.resize(90, 30);
  term.kill();
  for (let i = 0; i < 100 && (await tmux.list())[0].attached; i++) await new Promise(r => setTimeout(r, 30));
  assert.equal(await tmux.exists(ref), true);
  assert.equal((await tmux.list())[0].attached, 0);
  console.log('cross-host PTY / Chinese / resize / detach preserves host session: PASS');
} finally { clearTimeout(timer); try { term.kill(); } catch {} }
