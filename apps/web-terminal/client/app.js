import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import './style.css';
const $ = id => document.getElementById(id);
const term = new Terminal({ cursorBlink: true, fontSize: 14, scrollback: 3000,
  fontFamily: '"SFMono-Regular", Consolas, "Liberation Mono", monospace',
  theme: { background: '#10151b', foreground: '#d8e2ee', cursor: '#80d9c4', selectionBackground: '#385862' },
  linkHandler: { activate() {} }, allowProposedApi: false });
const fit = new FitAddon(); term.loadAddon(fit);
const encoder = new TextEncoder();
const drafts = new Map();
const maxBytes = 20 * 1024 * 1024;
let opened = false, socket, selected, connecting = false, ready = false, revision = 0, epoch = 0;
let sessions = [], switchQueued, composing = false, frameScheduled = false, lastSize = '';
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
  $('send').disabled = !active || Boolean(submission) || d.files.some(x => !x.uploaded || x.uploading);
  $('attach').disabled = !active || Boolean(submission) || d.files.length >= 4;
  $('draft').disabled = Boolean(submission);
  $('reconnect').hidden = !selected || active || connecting;
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
  if (!connected() || submission) return;
  if (encoder.encode(data).length > 16000) { notify('单次文本过长，请分段发送。'); return; }
  sendFrame({ type: 'input', data });
}
term.onData(input);
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
  revision++; switchQueued = undefined; connecting = ready = false; clearAck();
  const old = socket; socket = undefined; old?.close();
  if (submission) { clearTimeout(submission.timer); submission = undefined; notify('发送结果尚未确认，草稿已保留。请查看终端后决定是否重发。'); }
  updateState('已断开');
}
function select(session) {
  if (selected) draft().text = $('draft').value;
  else if ($('draft').value) draft(session).text = $('draft').value;
  selected = session; $('draft').value = draft().text; $('session-name').textContent = session.name;
  renderSessions(); renderAttachments();
}
async function connect(session) {
  if (submission) { notify('正在确认发送结果，请稍后切换会话。'); return; }
  if (connecting && socket?.readyState === WebSocket.OPEN) { switchQueued = session; return; }
  if (connected() && key(selected) === key(session)) { $('draft').focus(); return; }
  if (connected()) {
    select(session); connecting = true; ready = false; clearAck(); epoch++; term.reset();
    const s = size(); lastSize = `${s.cols}x${s.rows}`;
    updateState('切换中…'); notify(); sendFrame({ type: 'switch', ref: session, ...s }); return;
  }
  detach(); select(session); connecting = true; epoch = 0;
  const current = revision; updateState('连接中…'); notify();
  const s = size(); lastSize = `${s.cols}x${s.rows}`; term.reset();
  try {
    const { ticket } = await api('/terminal/manage/api/connections', { id: session.id, generation: session.generation, ...s });
    if (current !== revision) return;
    const url = new URL('/terminal/manage/ws', location.href); url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(url, ['nas-terminal.v1', `ticket.${ticket}`]); socket = ws;
    ws.onmessage = event => {
      if (socket !== ws) return;
      const msg = JSON.parse(event.data);
      if (msg.epoch !== epoch) return;
      if (msg.type === 'ready') {
        ready = true; connecting = false; updateState('已连接'); $('draft').focus(); resize();
        const next = switchQueued; switchQueued = undefined;
        if (next) connect(next);
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
      notify(event.code === 4001 ? '连接到期或会话已变化，请重新连接。后台任务继续运行。' : '连接已断开，草稿已保留；后台任务继续运行。若刚发送消息，请先核对终端再重发。');
    };
    ws.onerror = () => { if (socket === ws) notify('无法连接，请重试或刷新页面重新登录。'); };
  } catch (e) { if (current === revision) { connecting = false; updateState('连接失败'); notify(e.message); } }
}
function clearDraft() {
  const d = draft(); for (const item of d.files) URL.revokeObjectURL(item.preview);
  d.files = []; d.text = ''; $('draft').value = ''; renderAttachments(); notify();
}
function submit() {
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
  const d = draft(), picked = [...files];
  if (!picked.length) return;
  if (d.files.length + picked.length > 4 || picked.some(f => !['image/png', 'image/jpeg', 'image/webp'].includes(f.type) || f.size === 0) ||
      [...d.files.map(x => x.file), ...picked].reduce((n, f) => n + f.size, 0) > maxBytes) {
    notify('每条消息最多 4 张 PNG、JPEG、WebP 图片，总量不超过 20 MiB。'); return;
  }
  for (const file of picked) { const item = { file, preview: URL.createObjectURL(file), uploading: true, progress: 0 }; d.files.push(item); upload(item, selected); }
  renderAttachments();
}
$('refresh').onclick = refresh;
$('detach').onclick = () => { detach(); notify('已断开，草稿保留，后台任务继续运行。'); };
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
updateState('尚未连接'); refresh();
