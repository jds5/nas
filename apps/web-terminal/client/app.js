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
let opened = false;
let socket;
let selected;
let ctrl = false;
let connecting = false;
let revision = 0;
const encoder = new TextEncoder();
const keyMap = { esc: '\x1b', tab: '\t', up: '\x1b[A', down: '\x1b[B', left: '\x1b[D', right: '\x1b[C', enter: '\r' };

function notify(message = '') { $('notice').textContent = message; $('notice').hidden = !message; }
function connected() { return socket?.readyState === WebSocket.OPEN; }
function updateState(text, active = false) {
  $('state').textContent = text;
  $('connection-dot').classList.toggle('idle', !active);
  $('detach').disabled = !active && !connecting;
  $('paste').disabled = $('send').disabled = !active;
  $('reconnect').hidden = !selected || active || connecting;
  for (const button of document.querySelectorAll('.keyboard-bar button')) button.disabled = !active;
}
function sendFrame(frame) { if (connected()) socket.send(JSON.stringify(frame)); }
function resize() {
  if (!opened || !connected()) return;
  fit.fit();
  sendFrame({ type: 'resize', cols: Math.max(20, Math.min(term.cols, 400)), rows: Math.max(5, Math.min(term.rows, 160)) });
}
function input(data) {
  if (!connected()) { notify('连接已断开，重新连接后再输入。'); return; }
  // Never retain/replay keyboard events after a disconnect.
  if (encoder.encode(data).length > 16000) { notify('单次文本过长，请分段粘贴（每段不超过 16 KB）。'); return; }
  if (ctrl && data.length === 1 && /[a-zA-Z@\[\]\\^_?]/.test(data)) {
    data = data === '?' ? '\x7f' : String.fromCharCode(data.toUpperCase().charCodeAt(0) & 31);
    ctrl = false; $('ctrl').setAttribute('aria-pressed', 'false');
  }
  sendFrame({ type: 'input', data });
}
term.onData(input);

async function api(path, payload) {
  const response = await fetch(path, { method: payload ? 'POST' : 'GET', credentials: 'same-origin', redirect: 'manual',
    headers: payload ? { 'Content-Type': 'application/json', 'X-Nas-Csrf-Origin': location.origin } : {},
    body: payload ? JSON.stringify(payload) : undefined });
  if (response.status === 403 || response.type === 'opaqueredirect') throw new Error('登录已失效，请刷新页面重新通过 Access 登录。');
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '请求失败，请重试。');
  return data;
}
async function refresh() {
  $('refresh').disabled = true;
  try {
    const { sessions } = await api('/terminal/manage/api/sessions');
    $('sessions').replaceChildren();
    for (const session of sessions) {
      const button = document.createElement('button'); button.className = 'session';
      button.classList.toggle('selected', selected?.id === session.id && selected?.generation === session.generation);
      const name = document.createElement('strong'); name.textContent = session.name;
      const detail = document.createElement('span'); detail.textContent = `${session.windows} 个窗口 · ${session.attached} 个连接`;
      button.append(name, detail); button.addEventListener('click', () => connect(session));
      $('sessions').append(button);
    }
    $('list-status').textContent = sessions.length ? `${sessions.length} 个会话可连接` : '暂无 tmux 会话，请先通过 SSH 创建。';
  } catch (e) { $('list-status').textContent = e.message; }
  finally { $('refresh').disabled = false; }
}
function detach() {
  revision++;
  connecting = false;
  ctrl = false; $('ctrl').setAttribute('aria-pressed', 'false');
  const old = socket; socket = undefined; old?.close();
  updateState('已断开');
}
async function connect(session) {
  detach();
  const current = revision;
  selected = session; connecting = true;
  $('session-name').textContent = session.name;
  updateState('连接中…'); notify();
  try {
    const { ticket } = await api('/terminal/manage/api/connections', { id: session.id, generation: session.generation });
    if (current !== revision) return;
    $('empty').hidden = true; $('terminal').hidden = false;
    if (!opened) { term.open($('terminal')); opened = true; }
    term.reset();
    const url = new URL('/terminal/manage/ws', location.href); url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(url, ['nas-terminal.v1', `ticket.${ticket}`]); socket = ws;
    ws.onopen = () => { if (current !== revision) return; connecting = false; updateState('已连接', true); resize(); term.focus(); refresh(); };
    ws.onmessage = event => {
      if (current !== revision) return;
      const message = JSON.parse(event.data);
      if (message.type === 'output') term.write(message.data, () => {
        if (socket === ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'ack', bytes: encoder.encode(message.data).length }));
      });
    };
    ws.onclose = event => {
      if (current !== revision) return;
      socket = undefined; connecting = false; updateState('已断开');
      notify(event.code === 4001 ? '本次连接已到期，请重新连接；若登录过期，请刷新页面。任务继续运行。' : '终端连接已断开，后台任务不受影响。可以重新连接。');
    };
    ws.onerror = () => { if (current === revision) notify('无法建立终端连接，请重试；持续失败时刷新页面重新登录。'); };
  } catch (e) {
    if (current !== revision) return;
    connecting = false; updateState('连接失败'); notify(e.message);
  }
}
$('refresh').onclick = refresh;
$('detach').onclick = () => { detach(); notify('已断开终端，后台任务继续运行。'); };
$('reconnect').onclick = () => selected && connect(selected);
$('ctrl').onclick = () => { ctrl = !ctrl; $('ctrl').setAttribute('aria-pressed', String(ctrl)); term.focus(); };
$('keyboard').onclick = () => term.focus();
for (const button of document.querySelectorAll('[data-key]')) button.onclick = () => { input(keyMap[button.dataset.key]); term.focus(); };
function paste(submit) {
  const text = $('draft').value;
  if (!connected() || encoder.encode(text).length > 15000) { notify('请确认已连接，单次输入不超过 15 KB。'); return; }
  // xterm respects the shell's bracketed-paste mode; submitting Enter is a separate explicit action.
  if (text) term.paste(text);
  if (submit) input('\r');
  $('draft').value = ''; notify();
}
$('paste').onclick = () => paste(false);
$('composer').onsubmit = event => { event.preventDefault(); paste(true); };
$('fullscreen').onclick = async () => {
  try { if (document.fullscreenElement) await document.exitFullscreen(); else await document.querySelector('.workspace').requestFullscreen(); }
  catch { notify('当前浏览器不支持全屏；可使用横屏获得更大空间。'); }
};
new ResizeObserver(resize).observe($('terminal-wrap'));
window.visualViewport?.addEventListener('resize', () => {
  document.documentElement.style.setProperty('--viewport-height', `${window.visualViewport.height}px`); resize();
});
window.addEventListener('pagehide', detach);
updateState('尚未连接'); refresh();
