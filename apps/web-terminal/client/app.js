import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import './style.css';
import { conversationView } from './conversation.js';
import { renderHistory } from './history.mjs';
const $ = id => document.getElementById(id);
function preference(name, fallback) { try { return localStorage.getItem(name) ?? fallback; } catch { return fallback; } }
function savePreference(name, value) { try { localStorage.setItem(name, String(value)); } catch {} }
let fontSize = Math.min(24, Math.max(12, Number(preference('terminal-font', '16')) || 16));
const term = new Terminal({ cursorBlink: true, fontSize, scrollback: 3000,
  fontFamily: '"SFMono-Regular", Consolas, "Liberation Mono", monospace',
  theme: { background: '#10151b', foreground: '#d8e2ee', cursor: '#80d9c4', selectionBackground: '#385862' },
  linkHandler: { activate() {} }, allowProposedApi: false });
const fit = new FitAddon(); term.loadAddon(fit);
const encoder = new TextEncoder();
const drafts = new Map();
const maxBytes = 20 * 1024 * 1024;
let opened = false, socket, selected, connecting = false, ready = false, revision = 0, epoch = 0;
let sessions = [], switchQueued, composing = false, frameScheduled = false, lastSize = '';
let terminalMode = true, manualComposer = false;
let expires = 0, historyOpen = false, historyTimer, historyMode = 'terminal';
const chat = conversationView({ send: sendFrame, connected });
let pendingAck = 0, ackTimer, submission;
const key = ref => ref && `${ref.id}|${ref.generation}`;
function draft(ref = selected) {
  if (!drafts.has(key(ref))) drafts.set(key(ref), { text: '', files: [] });
  return drafts.get(key(ref));
}
function notify(text = '') { $('notice').textContent = text; $('notice').hidden = !text; }
function connected() { return socket?.readyState === WebSocket.OPEN && ready && !connecting; }
function updateState(text) {
  if (text) $('state').textContent = text;
  const active = connected(), d = draft();
  $('connection-dot').classList.toggle('idle', !active);
  $('detach').disabled = !socket && !connecting;
  $('open-history').disabled = !active || selected?.kind === 'ssh';
  $('send').disabled = !active || Boolean(submission) || d.files.some(x => !x.uploaded || x.uploading);
  $('attach').disabled = !active || Boolean(submission) || d.files.length >= 4;
  $('draft').disabled = Boolean(submission);
  $('connection-kind').textContent = selected ? selected.kind === 'ssh' ? '临时 SSH' : 'tmux' : '';
  updateExpiry();
  $('reconnect').hidden = !selected || active || connecting;
}
function focusInput() { if ($('composer').hidden) term.focus(); else $('draft').focus(); }
function setMode(mode) {
  terminalMode = mode !== 'codex';
  $('composer').hidden = terminalMode && !manualComposer;
  $('attach').hidden = terminalMode;
  $('toggle-composer').textContent = $('composer').hidden ? '展开输入框' : '收起输入框';
  $('hint').textContent = selected?.kind === 'ssh' ? '临时 SSH：断开、切换或连接到期会关闭登录 shell；重新连接会新建。' : terminalMode ? '直接在终端输入；支持 Tab、方向键、Ctrl+C 和全屏程序。' : '点击终端操作 Codex 菜单；上滚查看只读历史。';
  resize();
}
function sendFrame(frame) { if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ ...frame, epoch })); }
function size() {
  if (!opened) { $('empty').hidden = true; $('terminal').hidden = false; term.open($('terminal')); opened = true; }
  fit.fit(); return { cols: Math.max(20, Math.min(term.cols, 400)), rows: Math.max(5, Math.min(term.rows, 160)) };
}
function resize() {
  if (!opened || frameScheduled) return;
  frameScheduled = true;
  requestAnimationFrame(() => {
    frameScheduled = false;
    if (!connected()) return;
    const s = size(), next = `${s.cols}x${s.rows}`;
    if (next !== lastSize) { sendFrame({ type: 'resize', ...s }); lastSize = next; }
  });
}
function input(data) {
  if (!connected() || submission || historyOpen) return;
  if (encoder.encode(data).length > 16000) { notify('单次文本过长，请分段发送。'); return; }
  sendFrame({ type: 'input', data });
}
term.onData(input);
function updateExpiry() {
  const seconds = connected() && expires ? Math.max(0, Math.ceil((expires - Date.now()) / 1000)) : 0;
  $('expiry').textContent = seconds ? `剩余 ${Math.floor(seconds / 3600)}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}` : '';
  $('expiry').classList.toggle('expiring', seconds > 0 && seconds <= 60);
  $('expiry').title = selected?.kind === 'ssh' ? '到期关闭临时 SSH；重连会新建 shell' : '到期断开网页连接，tmux 任务继续运行';
}
setInterval(updateExpiry, 1000);
function closeHistory(focus = true) {
  historyOpen = false; chat.close(); clearTimeout(historyTimer); $('history').hidden = true; $('history-content').textContent = '';
  if (focus && opened) term.focus();
}
function historyTab(mode) {
  historyMode = mode; clearTimeout(historyTimer); chat.close();
  const isChat = mode === 'chat';
  $('history-content').hidden = isChat; $('conversation-content').hidden = !isChat;
  $('history-wrap').hidden = isChat;
  $('history-chat').setAttribute('aria-pressed', String(isChat));
  $('history-terminal').setAttribute('aria-pressed', String(!isChat));
  document.querySelector('.history-tip').textContent = isChat ? 'Codex 原始消息 · Markdown 阅读 · 返回终端可处理菜单与输入' : '最近最多 1000 行及当前屏幕；Shift＋滚轮或底部滚动条横向查看。';
  if (isChat) { chat.open(); $('conversation-content').focus(); }
  else {
    $('history-content').textContent = '正在读取历史…'; $('history-content').focus();
    sendFrame({ type: 'history' });
    historyTimer = setTimeout(() => { if (historyOpen && historyMode === 'terminal') $('history-content').textContent = '读取超时，请返回实时终端后重试。'; }, 5000);
  }
}
function openHistory() {
  if (!connected() || historyOpen || selected?.kind === 'ssh') return;
  historyOpen = true; $('history').hidden = false;
  $('history-chat').hidden = terminalMode;
  historyTab(terminalMode ? 'terminal' : 'chat');
}
$('open-history').onclick = openHistory;
$('history-chat').onclick = () => historyTab('chat');
$('history-terminal').onclick = () => historyTab('terminal');
$('history-wrap').onclick = () => {
  const wrapped = $('history-content').classList.toggle('wrap-lines');
  $('history-wrap').setAttribute('aria-pressed', String(wrapped));
  $('history-wrap').textContent = wrapped ? '自动换行：开' : '自动换行：关';
};
$('history-content').addEventListener('wheel', event => {
  if (event.shiftKey && event.deltaY && !event.deltaX) { event.preventDefault(); $('history-content').scrollLeft += event.deltaY; }
}, { passive: false });
$('history-close').onclick = () => closeHistory();
$('history').onkeydown = event => { if (event.key === 'Escape') { event.preventDefault(); closeHistory(); } };
term.attachCustomWheelEventHandler(event => {
  if (event.ctrlKey) { event.preventDefault(); return false; }
  if (selected && selected.kind !== 'ssh') {
    event.preventDefault(); if (event.deltaY < 0) openHistory(); return false;
  }
  // Never let alternate-screen wheel fallback generate Up/Down input.
  if (term.buffer.active.type === 'alternate' && term.modes.mouseTrackingMode === 'none') {
    event.preventDefault(); notify('当前全屏程序没有本地滚动历史；滚轮不会发送方向键。'); return false;
  }
  return true;
});
function applyFont() {
  term.options.fontSize = fontSize; $('font-size').textContent = String(fontSize);
  document.documentElement.style.setProperty('--terminal-font', `${fontSize}px`);
  $('font-down').disabled = fontSize <= 12; $('font-up').disabled = fontSize >= 24;
  savePreference('terminal-font', fontSize); resize();
}
$('font-down').onclick = () => { fontSize = Math.max(12, fontSize - 1); applyFont(); };
$('font-up').onclick = () => { fontSize = Math.min(24, fontSize + 1); applyFont(); };
function sidebar(collapsed) {
  $('sidebar').hidden = collapsed; document.querySelector('main').classList.toggle('sidebar-collapsed', collapsed);
  $('toggle-sidebar').textContent = collapsed ? '展开会话栏' : '收起会话栏';
  $('toggle-sidebar').setAttribute('aria-expanded', String(!collapsed)); savePreference('terminal-sidebar', collapsed ? 'closed' : 'open'); resize();
}
$('toggle-sidebar').onclick = () => sidebar(!$('sidebar').hidden);
document.addEventListener('focusin', () => {
  $('focus-status').textContent = historyOpen ? '只读历史 · 不发送按键' : document.activeElement === $('draft') ? '正在输入消息' : document.activeElement?.closest('#terminal') ? '正在操作终端' : '选择终端或消息输入框';
});
applyFont(); sidebar(preference('terminal-sidebar', 'open') === 'closed');
async function api(path, payload) {
  const response = await fetch(path, { method: payload ? 'POST' : 'GET', credentials: 'same-origin', redirect: 'manual', signal: AbortSignal.timeout(15000),
    headers: payload ? { 'Content-Type': 'application/json', 'X-Nas-Csrf-Origin': location.origin } : {}, body: payload ? JSON.stringify(payload) : undefined });
  if (response.status === 403 || response.type === 'opaqueredirect') throw new Error('登录已失效，请刷新页面重新通过 Access 登录。');
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '请求失败，请重试。');
  return data;
}
function renderSessions() {
  $('sessions').replaceChildren();
  for (const session of sessions) {
    const button = document.createElement('button'); button.className = 'session';
    button.classList.toggle('selected', key(selected) === key(session));
    const name = document.createElement('strong'); name.textContent = session.name;
    const detail = document.createElement('span'); detail.textContent = `${session.windows} 个窗口`;
    button.append(name, detail); button.onclick = () => connect(session); $('sessions').append(button);
  }
}
async function refresh() {
  $('refresh').disabled = true;
  try {
    ({ sessions } = await api('/terminal/manage/api/sessions')); renderSessions();
    $('list-status').textContent = sessions.length ? `${sessions.length} 个会话可连接` : '暂无 tmux 会话，请先通过 SSH 创建。';
  } catch (e) { $('list-status').textContent = e.message; }
  finally { $('refresh').disabled = false; }
}
function clearAck() { clearTimeout(ackTimer); ackTimer = undefined; pendingAck = 0; }
function ack(ws, forEpoch, bytes) {
  if (socket !== ws || epoch !== forEpoch || ws.readyState !== WebSocket.OPEN) return;
  pendingAck += bytes;
  const flush = () => { if (pendingAck && socket === ws && epoch === forEpoch) sendFrame({ type: 'ack', bytes: pendingAck }); clearAck(); };
  if (pendingAck >= 32768) flush(); else if (!ackTimer) ackTimer = setTimeout(flush, 40);
}
function detach() {
  closeHistory(false); expires = 0;
  revision++; switchQueued = undefined; connecting = ready = false; clearAck();
  const old = socket; socket = undefined; old?.close();
  if (submission) { clearTimeout(submission.timer); submission = undefined; notify('发送结果尚未确认，草稿已保留。请查看终端后决定是否重发。'); }
  updateState('已断开');
}
function select(session) {
  closeHistory(false);
  if (selected) draft().text = $('draft').value;
  else if ($('draft').value) draft(session).text = $('draft').value;
  selected = session; manualComposer = false; setMode('terminal'); $('draft').value = draft().text; $('session-name').textContent = session.name;
  renderSessions(); renderAttachments();
}
async function connect(session) {
  if (submission) { notify('正在确认发送结果，请稍后切换会话。'); return; }
  if (connecting && socket?.readyState === WebSocket.OPEN) { switchQueued = session; return; }
  if (connected() && key(selected) === key(session)) { focusInput(); return; }
  if (connected()) {
    select(session); connecting = true; ready = false; clearAck(); epoch++; term.reset();
    const s = size(); lastSize = `${s.cols}x${s.rows}`;
    updateState('切换中…'); notify(); sendFrame({ type: 'switch', ref: session, ...s }); return;
  }
  detach(); select(session); connecting = true; epoch = 0;
  const current = revision; updateState('连接中…'); notify();
  const s = size(); lastSize = `${s.cols}x${s.rows}`; term.reset();
  try {
    const { ticket } = await api('/terminal/manage/api/connections', { kind: session.kind, id: session.id, generation: session.generation, ...s });
    if (current !== revision) return;
    const url = new URL('/terminal/manage/ws', location.href); url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(url, ['nas-terminal.v1', `ticket.${ticket}`]); socket = ws;
    ws.onmessage = event => {
      if (socket !== ws) return;
      const msg = JSON.parse(event.data);
      if (msg.epoch !== epoch) return;
      if (msg.type === 'ready') {
        expires = msg.expires; ready = true; connecting = false; updateState('已连接'); focusInput(); resize();
        const next = switchQueued; switchQueued = undefined;
        if (next) connect(next);
      } else if (msg.type === 'mode') {
        const hadFocus = document.activeElement === $('draft') || document.activeElement?.classList.contains('xterm-helper-textarea');
        setMode(msg.mode); if (hadFocus) focusInput();
      } else if (msg.type === 'conversation') {
        if (historyOpen && historyMode === 'chat') chat.receive(msg);
      } else if (msg.type === 'history' || msg.type === 'history-error') {
        if (!historyOpen || historyMode !== 'terminal') return;
        clearTimeout(historyTimer);
        renderHistory($('history-content'), msg.type === 'history' ? msg.data : '历史读取失败，请返回实时终端后重试。');
        $('history-content').scrollLeft = 0;
        $('history-content').scrollTop = Math.max(0, $('history-content').scrollHeight - $('history-content').clientHeight - 120);
      } else if (msg.type === 'output') {
        const forEpoch = epoch;
        term.write(msg.data, () => ack(ws, forEpoch, encoder.encode(msg.data).length));
      } else if (['submitted', 'submission-error'].includes(msg.type) && msg.requestId === submission?.id) {
        clearTimeout(submission.timer); submission = undefined;
        if (msg.type === 'submitted') clearDraft(); else notify(msg.error);
        updateState();
      }
    };
    ws.onclose = event => {
      if (socket !== ws) return;
      detach();
      notify(selected?.kind === 'ssh' ? '临时 SSH 已关闭；重新连接将新建 shell。' : event.code === 4001 ? '连接到期或会话已变化，请重新连接。后台任务继续运行。' : '连接已断开，草稿已保留；后台任务继续运行。若刚发送消息，请先核对终端再重发。');
    };
    ws.onerror = () => { if (socket === ws) notify('无法连接，请重试或刷新页面重新登录。'); };
  } catch (e) { if (current === revision) { connecting = false; updateState('连接失败'); notify(e.message); } }
}
function clearDraft() {
  const d = draft(); for (const item of d.files) URL.revokeObjectURL(item.preview);
  d.files = []; d.text = ''; $('draft').value = ''; renderAttachments(); notify();
}
function submit() {
  if (historyOpen) closeHistory(false);
  const d = draft(); d.text = $('draft').value;
  if (!connected() || submission || d.files.some(x => !x.uploaded || x.uploading)) return;
  if (!d.text.trim() && !d.files.length) return;
  if (encoder.encode(d.text).length > 15000) { notify('单条文字不能超过 15 KB。'); return; }
  if (d.files.length) {
    const id = [...crypto.getRandomValues(new Uint8Array(16))].map(x => x.toString(16).padStart(2, '0')).join('');
    submission = { id, timer: setTimeout(() => {
      if (submission?.id === id) { submission = undefined; detach(); notify('发送确认超时，草稿已保留。请重新连接并核对结果，避免重复发送。'); }
    }, 15000) };
    sendFrame({ type: 'submit-images', requestId: id, text: d.text, attachments: d.files.map(x => x.uploaded.id) });
    updateState();
  } else {
    term.paste(d.text); input('\r'); clearDraft();
  }
}
function renderAttachments() {
  $('attachments').replaceChildren();
  for (const item of draft().files) {
    const card = document.createElement('div'); card.className = 'attachment';
    const img = document.createElement('img'); img.src = item.preview; img.alt = item.file.name;
    const label = document.createElement('span'); label.textContent = `${item.file.name} · ${item.uploading ? `${item.progress}%` : item.uploaded ? '已上传' : '上传失败'}`;
    const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = '移除'; remove.disabled = Boolean(submission);
    remove.onclick = () => { item.xhr?.abort(); draft().files = draft().files.filter(x => x !== item); URL.revokeObjectURL(item.preview); renderAttachments(); };
    card.append(img, label, remove);
    if (!item.uploading && !item.uploaded) {
      const retry = document.createElement('button'); retry.type = 'button'; retry.textContent = '重试'; retry.onclick = () => upload(item, selected); card.append(retry);
    }
    $('attachments').append(card);
  }
  updateState();
}
function upload(item, target) {
  item.uploading = true; item.progress = 0;
  const xhr = new XMLHttpRequest(); item.xhr = xhr;
  xhr.open('POST', '/terminal/manage/api/uploads'); xhr.timeout = 65000;
  xhr.setRequestHeader('Content-Type', item.file.type);
  xhr.setRequestHeader('X-Nas-Csrf-Origin', location.origin);
  xhr.setRequestHeader('X-Upload-Name', encodeURIComponent(item.file.name));
  xhr.setRequestHeader('X-Session-Ref', encodeURIComponent(JSON.stringify({ id: target.id, generation: target.generation })));
  const render = () => { if (key(selected) === key(target)) renderAttachments(); };
  xhr.upload.onprogress = event => { if (event.lengthComputable) { item.progress = Math.round(event.loaded * 100 / event.total); render(); } };
  xhr.onload = () => {
    item.uploading = false;
    try { const result = JSON.parse(xhr.responseText); if (xhr.status !== 201) throw new Error(result.error || '上传失败'); item.uploaded = result; }
    catch (e) { if (key(selected) === key(target)) notify(e.message); }
    render();
  };
  xhr.onerror = xhr.ontimeout = () => { item.uploading = false; if (key(selected) === key(target)) notify('图片上传失败，文字尚未发送。可以重试。'); render(); };
  xhr.onabort = () => { item.uploading = false; render(); };
  xhr.send(item.file); render();
}
function addFiles(files) {
  if (!connected() || submission) { notify('请先连接会话。'); return; }
  if (terminalMode) { notify('图片仅支持 tmux 中前台运行的 Codex。'); return; }
  const d = draft(), picked = [...files];
  if (!picked.length) return;
  if (d.files.length + picked.length > 4 || picked.some(f => !['image/png', 'image/jpeg', 'image/webp'].includes(f.type) || f.size === 0) ||
      [...d.files.map(x => x.file), ...picked].reduce((n, f) => n + f.size, 0) > maxBytes) {
    notify('每条消息最多 4 张 PNG、JPEG、WebP 图片，总量不超过 20 MiB。'); return;
  }
  for (const file of picked) { const item = { file, preview: URL.createObjectURL(file), uploading: true, progress: 0 }; d.files.push(item); upload(item, selected); }
  renderAttachments();
}
$('new-ssh').onclick = () => connect({ kind: 'ssh', id: 'ssh-' + [...crypto.getRandomValues(new Uint8Array(16))].map(x => x.toString(16).padStart(2, '0')).join(''), generation: 'ephemeral', name: '临时 SSH · NAS' });
$('toggle-composer').onclick = () => { manualComposer = $('composer').hidden; if (!terminalMode) { $('composer').hidden = !$('composer').hidden; $('toggle-composer').textContent = $('composer').hidden ? '展开输入框' : '收起输入框'; resize(); } else setMode('terminal'); focusInput(); };
$('refresh').onclick = refresh;
$('detach').onclick = () => { detach(); notify(selected?.kind === 'ssh' ? '临时 SSH 已关闭。' : '已断开，草稿保留，tmux 任务继续运行。'); };
$('reconnect').onclick = () => selected && connect(selected);
$('composer').onsubmit = event => { event.preventDefault(); submit(); };
$('draft').oninput = () => { draft().text = $('draft').value; };
$('draft').addEventListener('compositionstart', () => { composing = true; });
$('draft').addEventListener('compositionend', () => { composing = false; });
$('draft').onkeydown = event => {
  if (event.isComposing || composing || event.keyCode === 229) return;
  if (event.ctrlKey && event.key.toLowerCase() === 'j') { event.preventDefault(); $('draft').setRangeText('\n', $('draft').selectionStart, $('draft').selectionEnd, 'end'); draft().text = $('draft').value; }
  else if (event.key === 'Enter') { event.preventDefault(); submit(); }
};
$('draft').onpaste = event => {
  const files = [...(event.clipboardData?.items || [])].filter(i => i.kind === 'file').map(i => i.getAsFile()).filter(Boolean);
  if (files.length) { event.preventDefault(); addFiles(files); }
};
$('composer').ondragover = event => { if ([...event.dataTransfer.types].includes('Files')) { event.preventDefault(); $('composer').classList.add('dragging'); } };
$('composer').ondragleave = () => $('composer').classList.remove('dragging');
$('composer').ondrop = event => { event.preventDefault(); $('composer').classList.remove('dragging'); addFiles(event.dataTransfer.files); };
$('attach').onclick = () => $('image-files').click();
$('image-files').onchange = () => { addFiles($('image-files').files); $('image-files').value = ''; };
$('fullscreen').onclick = async () => {
  try { if (document.fullscreenElement) await document.exitFullscreen(); else await document.querySelector('.workspace').requestFullscreen(); }
  catch { notify('当前浏览器不支持全屏。'); }
};
new ResizeObserver(resize).observe($('terminal-wrap'));
window.addEventListener('pagehide', detach);
setMode('terminal'); updateState('尚未连接'); refresh();
