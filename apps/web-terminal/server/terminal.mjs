import { WebSocket } from 'ws';
export const validSize = s => s && Number.isInteger(s.cols) && Number.isInteger(s.rows) && s.cols >= 20 && s.cols <= 400 && s.rows >= 5 && s.rows <= 160;

// Each selection has an epoch. Old output/acks/input cannot cross a session switch.
export function connectTerminal({ ws, ref, user, tmux, peers, uploads, conversation, maxDurationMs }) {
  let term, activeRef = ref, epoch = 0, changing = false, serial = 0;
  let reading;
  let closed = false, queued = 0, alive = true, submitting = false;
  const deadline = Math.min(user.exp * 1000, Date.now() + maxDurationMs);
  const send = value => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(value)); };
  const killClient = () => { const old = term; term = null; try { old?.kill(); } catch {} };
  const finish = () => {
    if (closed) return;
    closed = true; serial++; clearTimeout(expiry); clearInterval(heartbeat); clearInterval(modeTimer);
    reading?.abort(); peers.delete(ws); killClient();
  };
  const fail = (code, reason) => { finish(); ws.close(code, reason); };
  const expiry = setTimeout(() => fail(4001, 'reauthenticate'), Math.max(0, deadline - Date.now()));
  let modeBusy = false, lastMode;
  async function reportMode() {
    if (modeBusy || closed || changing) return;
    modeBusy = true; const client = term;
    try {
      const snapshot = await tmux.snapshot(activeRef);
      const mode = snapshot?.command === 'codex' ? 'codex' : 'terminal';
      if (!closed && client === term && mode !== lastMode) { lastMode = mode; send({ type: 'mode', mode, epoch }); }
    } finally { modeBusy = false; }
  }
  const modeTimer = setInterval(() => void reportMode(), 1500);
  const heartbeat = setInterval(() => { if (!alive) return ws.terminate(); alive = false; ws.ping(); }, 5000);
  ws.on('pong', () => { alive = true; });
  ws.on('close', finish); ws.on('error', finish);
  function attach(next, nextEpoch, size) {
    reading?.abort(); killClient(); queued = 0; activeRef = next; epoch = nextEpoch;
    term = tmux.attach(next, size);
    const client = term;
    client.onData(data => {
      if (closed || client !== term || ws.readyState !== WebSocket.OPEN) return;
      queued += Buffer.byteLength(data);
      if (queued > 1024 * 1024 || ws.bufferedAmount > 512 * 1024) return fail(4002, 'slow_client');
      send({ type: 'output', data, epoch });
      if (queued > 256 * 1024) client.pause();
    });
    client.onExit(() => { if (client === term) fail(1000, 'detached'); });
    changing = false; lastMode = undefined; void reportMode();
    send({ type: 'ready', epoch, expires: deadline });
  }
  async function switchTo(msg) {
    if (!Number.isSafeInteger(msg.epoch) || msg.epoch <= epoch || !validSize(msg) || changing || submitting) return fail(1008, 'invalid_switch');
    changing = true; const ownSerial = ++serial;
    try {
      const exists = await tmux.exists(msg.ref);
      if (closed || ownSerial !== serial) return;
      if (!exists || Date.now() >= deadline) return fail(4001, 'session_unavailable');
      attach(msg.ref, msg.epoch, msg);
    } catch { fail(1011, 'terminal_unavailable'); }
  }
  let historyBusy = false, historyAt = 0;
  async function history() {
    if (historyBusy) { send({ type: 'history-error', epoch }); return; }
    historyBusy = true; const client = term, ownEpoch = epoch;
    try {
      await new Promise(resolve => setTimeout(resolve, Math.max(0, 1000 - (Date.now() - historyAt))));
      if (closed || client !== term || ownEpoch !== epoch || changing || Date.now() >= deadline) return;
      historyAt = Date.now();
      const data = await tmux.history(activeRef);
      if (!closed && client === term && ownEpoch === epoch && !changing && Date.now() < deadline) send({ type: 'history', data, epoch });
    } catch {
      if (!closed && client === term && ownEpoch === epoch) send({ type: 'history-error', epoch });
    } finally { historyBusy = false; }
  }
  let conversationAt = 0;
  async function readConversation(msg) {
    const ownEpoch = epoch, client = term, ownRef = activeRef;
    const respond = value => { if (!closed && client === term && ownEpoch === epoch && !changing && Date.now() < deadline) send({ ...value, type: 'conversation', requestId: msg.requestId, epoch }); };
    if (typeof msg.requestId !== 'string' || !/^[\w-]{1,64}$/.test(msg.requestId)) return fail(1008, 'invalid_read');
    if (!conversation || activeRef.kind === 'ssh') return respond({ ok: false, error: '请选择 tmux 中的 Codex 会话' });
    if (reading) return respond({ ok: false, error: '正在读取，请稍后重试' });
    if ((msg.before != null && (!Number.isSafeInteger(msg.before) || msg.before < 0 || !/^[a-f0-9]{64}$/.test(msg.binding || ''))) ||
        (msg.binding != null && !/^[a-f0-9]{64}$/.test(msg.binding))) return fail(1008, 'invalid_read');
    const controller = new AbortController(); reading = controller;
    try {
      await new Promise(resolve => setTimeout(resolve, Math.max(0, 1000 - (Date.now() - conversationAt))));
      if (closed || client !== term || ownEpoch !== epoch || changing) return;
      conversationAt = Date.now();
      const before = await tmux.snapshot(ownRef);
      if (closed || client !== term || ownEpoch !== epoch || changing) return;
      if (before?.command !== 'codex') return respond({ ok: false, error: '当前窗格没有运行 Codex，请使用终端历史' });
      const result = await conversation.read({ ref: ownRef, before: msg.before, binding: msg.binding }, controller.signal);
      const after = await tmux.snapshot(ownRef);
      if (!after || after.pane !== before.pane || after.command !== 'codex') throw new Error('changed');
      respond(result);
    } catch { respond({ ok: false, error: '读取失败或会话已变化，请刷新；也可切换到终端历史' }); }
    finally { if (reading === controller) reading = undefined; }
  }
  async function submitImages(msg) {
    if (submitting || changing || typeof msg.requestId !== 'string' || !/^[\w-]{1,64}$/.test(msg.requestId)) return fail(1008, 'invalid_submission');
    submitting = true; const ownSerial = serial;
    try {
      const snapshot = await tmux.snapshot(activeRef);
      if (!snapshot || snapshot.command !== 'codex') throw new Error('图片只能发送到当前前台运行 Codex 的窗口。');
      const text = await uploads.message(msg, user, activeRef, snapshot.pane);
      const current = await tmux.snapshot(activeRef);
      if (!current || current.command !== 'codex' || current.pane !== snapshot.pane || closed || ownSerial !== serial || Date.now() >= deadline) throw new Error('连接已变化，消息未发送。');
      // Codex supports bracketed paste. Never interpolate uploaded filenames into shell commands.
      term.write(`\x1b[200~${text.replace(/\x1b/g, '')}\x1b[201~\r`);
      send({ type: 'submitted', epoch, requestId: msg.requestId });
    } catch (e) {
      send({ type: 'submission-error', epoch, requestId: msg.requestId, error: e.publicMessage || (e.message.startsWith('图片只能') ? e.message : '图片不可用或会话已变化，消息未发送，请重新选择图片。') });
    } finally { submitting = false; }
  }
  ws.on('message', (raw, binary) => {
    if (closed || Date.now() >= deadline) return fail(4001, 'reauthenticate');
    try {
      if (binary) throw new Error();
      const msg = JSON.parse(raw.toString());
      if (msg.type === 'switch') { void switchTo(msg); return; }
      // Epoch 0 accepts old clients; after a switch, missing/stale epochs are ignored.
      if ((msg.epoch ?? 0) !== epoch) return;
      if (msg.type === 'ack' && Number.isInteger(msg.bytes) && msg.bytes > 0 && msg.bytes <= queued) {
        queued -= msg.bytes; if (queued < 64 * 1024) term?.resume(); return;
      }
      if (changing || submitting) return;
      if (msg.type === 'input' && typeof msg.data === 'string' && Buffer.byteLength(msg.data) <= 16384) term.write(msg.data);
      else if (msg.type === 'conversation') void readConversation(msg);
      else if (msg.type === 'history') void history();
      else if (msg.type === 'resize' && validSize(msg)) term.resize(msg.cols, msg.rows);
      else if (msg.type === 'submit-images' && uploads) void submitImages(msg);
      else throw new Error();
    } catch { fail(1008, 'invalid_message'); }
  });
  try { attach(ref, 0, validSize(ref) ? ref : { cols: 80, rows: 24 }); }
  catch { fail(1011, 'terminal_unavailable'); }
}
