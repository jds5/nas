'use strict';
// 页面只经 drop 子域名调用 /api；文件数据用短期令牌直连 dl 子域名。
const $ = id => document.getElementById(id);
const origin = location.origin;
let limits = null;

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v; else if (k.startsWith('on')) node.addEventListener(k.slice(2), v); else node.setAttribute(k, v);
  }
  for (const child of children) node.append(child);
  return node;
}
function size(n) {
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(i ? 1 : 0)} ${units[i]}`;
}
function left(ms) {
  const m = Math.max(0, Math.round(ms / 60000));
  return m >= 60 ? `${Math.floor(m / 60)} 小时 ${m % 60} 分` : `${m} 分`;
}
function showError(message) {
  $('error').textContent = message;
  $('error').hidden = !message;
}
async function api(path, options = {}) {
  const headers = { ...(options.body ? { 'content-type': 'application/json' } : {}), ...(options.method ? { 'x-drop-csrf': origin } : {}) };
  let response;
  try {
    response = await fetch(path, { ...options, headers, credentials: 'same-origin', redirect: 'error' });
  } catch {
    throw new Error('连接失败，登录可能已过期，请刷新页面');
  }
  const data = response.status === 204 ? {} : await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `请求失败（${response.status}）`);
  return data;
}

async function refresh() {
  try {
    const data = await api('/api/files');
    limits = data.limits;
    $('limits').textContent = `单个文件不超过 ${size(limits.maxFile)}`;
    $('usage').textContent = `已用 ${size(data.used)} / ${size(limits.quota)}`;
    const items = data.files.filter(f => f.complete).map(f => el('li', {},
      el('div', { class: 'name' }, f.name),
      el('div', { class: 'meta' }, `${size(f.size)} · 剩余 ${left(f.expires - Date.now())} · 来自 ${f.ip || '未知'}${f.country ? ` (${f.country})` : ''}`),
      el('div', { class: 'meta' }, `SHA-256 ${f.sha256}`),
      el('div', { class: 'actions' },
        el('button', { class: 'primary', onclick: () => download(f) }, '下载'),
        el('button', { class: 'danger', onclick: () => remove(f) }, '删除'))));
    $('files').replaceChildren(...items);
    $('empty').hidden = items.length > 0;
  } catch (error) { showError(error.message); }
}

async function download(file) {
  try {
    const { url } = await api(`/api/files/${file.id}/link`, { method: 'POST', body: '{}' });
    location.href = url;
  } catch (error) { showError(error.message); }
}
async function remove(file) {
  if (!confirm(`删除“${file.name}”？`)) return;
  try { await api(`/api/files/${file.id}`, { method: 'DELETE' }); } catch (error) { showError(error.message); }
  refresh();
}

function put(url, token, blob, onprogress) {
  return new Promise(resolve => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url);
    xhr.setRequestHeader('x-drop-token', token);
    xhr.setRequestHeader('content-type', 'application/octet-stream');
    xhr.upload.onprogress = e => onprogress(e.loaded);
    xhr.onload = () => { let data = {}; try { data = JSON.parse(xhr.responseText); } catch {} resolve({ status: xhr.status, data }); };
    xhr.onerror = xhr.ontimeout = () => resolve({ status: 0, data: {} });
    xhr.send(blob);
  });
}

async function upload(file) {
  const bar = el('progress', { max: file.size, value: 0 });
  const note = el('div', { class: 'meta' }, '准备上传…');
  const item = el('li', {}, el('div', { class: 'name' }, file.name), bar, note);
  $('uploads').append(item);
  try {
    const job = await api('/api/uploads', { method: 'POST', body: JSON.stringify({ name: file.name, size: file.size }) });
    let { token } = job, offset = 0, failures = 0;
    while (offset < file.size) {
      const end = Math.min(offset + job.chunk, file.size);
      note.textContent = `${size(offset)} / ${size(file.size)}`;
      const { status, data } = await put(`${job.url}?offset=${offset}`, token, file.slice(offset, end), loaded => { bar.value = offset + loaded; });
      if (status === 200) { offset = data.received; failures = 0; continue; }
      if (++failures > 8) throw new Error('上传多次失败，请重试');
      note.textContent = `连接中断，${failures * 2} 秒后续传…`;
      await new Promise(r => setTimeout(r, failures * 2000));
      // 续签令牌并以服务器实际收到的字节数为准，从断点继续。
      ({ token, received: offset } = await api(`/api/uploads/${job.id}/token`, { method: 'POST', body: '{}' }));
      bar.value = offset;
    }
    item.remove();
  } catch (error) {
    note.textContent = `失败：${error.message}`;
    note.className = 'meta error';
  }
  refresh();
}

function start(files) {
  showError('');
  for (const file of files) {
    if (limits && file.size > limits.maxFile) { showError(`“${file.name}”超过 ${size(limits.maxFile)}`); continue; }
    if (file.size === 0) { showError(`“${file.name}”是空文件`); continue; }
    upload(file);
  }
}

$('picker').addEventListener('change', e => { start([...e.target.files]); e.target.value = ''; });
const drop = $('drop');
drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
drop.addEventListener('dragleave', () => drop.classList.remove('over'));
drop.addEventListener('drop', e => { e.preventDefault(); drop.classList.remove('over'); start([...e.dataTransfer.files]); });
refresh();
setInterval(refresh, 60000);
