import { Marked } from 'marked';
import DOMPurify from 'dompurify';
const escape = text => String(text).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const markdown = new Marked({ gfm: true, breaks: false, renderer: {
  html: ({ text }) => escape(text),
  image: ({ text }) => escape(`[图片：${text || '附件'}]`),
} });
const tags = ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'del', 'blockquote', 'ul', 'ol', 'li', 'pre', 'code', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a'];
async function copyText(text, button, label) {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard_unavailable');
    await navigator.clipboard.writeText(text); button.textContent = '已复制';
  } catch {
    // Clipboard API may be denied by browser policy; keep a user-gesture fallback.
    const field = document.createElement('textarea'); field.value = text;
    field.style.position = 'fixed'; field.style.opacity = '0'; field.readOnly = true;
    document.body.append(field); field.select();
    try { button.textContent = document.execCommand('copy') ? '已复制' : '请选中文字复制'; }
    catch { button.textContent = '请选中文字复制'; }
    finally { field.remove(); button.focus(); }
  }
  setTimeout(() => { button.textContent = label; }, 2000);
}
export function messageElement(message, agentName = 'Codex') {
  const article = document.createElement('article'); article.className = `chat-message ${message.role}${message.phase === 'commentary' ? ' commentary' : ''}`;
  const heading = document.createElement('div'); heading.className = 'chat-author';
  heading.textContent = message.role === 'user' ? '你' : message.role === 'command' ? '命令输出' : message.phase === 'commentary' ? `${agentName} · 进度` : agentName; article.append(heading);
  const body = document.createElement('div'); body.className = 'chat-body'; article.append(body);
  if (message.role !== 'assistant') body.textContent = message.text;
  else {
    try {
      body.append(DOMPurify.sanitize(markdown.parse(message.text), {
        RETURN_DOM_FRAGMENT: true, ALLOWED_TAGS: tags, ALLOWED_ATTR: ['href', 'title', 'start'], ALLOW_DATA_ATTR: false,
      }));
      for (const link of body.querySelectorAll('a')) {
        try {
          const url = new URL(link.getAttribute('href'));
          if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error();
          link.href = url.href; link.target = '_blank'; link.rel = 'noopener noreferrer';
        } catch { link.replaceWith(document.createTextNode(link.textContent)); }
      }
      for (const table of body.querySelectorAll('table')) {
        const wrap = document.createElement('div'); wrap.className = 'chat-table'; wrap.tabIndex = 0;
        wrap.setAttribute('aria-label', '表格，可横向滚动'); table.replaceWith(wrap); wrap.append(table);
        wrap.addEventListener('wheel', event => { if (event.shiftKey && event.deltaY && !event.deltaX) { event.preventDefault(); wrap.scrollLeft += event.deltaY; } }, { passive: false });
      }
      for (const code of body.querySelectorAll('pre')) {
        const wrap = document.createElement('div'); wrap.className = 'chat-code'; code.replaceWith(wrap); wrap.append(code);
        const copy = document.createElement('button'); copy.type = 'button'; copy.className = 'copy-code'; copy.textContent = '复制代码';
        copy.onclick = () => copyText(code.textContent, copy, '复制代码'); wrap.prepend(copy);
      }
    } catch { body.textContent = message.text; }
  }
  // Like chat apps: a quiet copy action under each reply (raw Markdown / original text).
  const actions = document.createElement('div'); actions.className = 'chat-actions';
  const copy = document.createElement('button'); copy.type = 'button'; copy.className = 'copy-message'; copy.textContent = '复制';
  copy.onclick = () => copyText(message.text, copy, '复制'); actions.append(copy); article.append(actions);
  return article;
}

export function conversationView({ send, connected, agentName = () => 'Codex' }) {
  const $ = id => document.getElementById(id);
  let active = false, pending, timer, binding, page, cursor = null, stack = [], serial = 0, shown = '', follow = false;
  function busy(value) { for (const id of ['chat-older', 'chat-newer', 'chat-refresh']) $(id).disabled = value; }
  // quiet: background follow of the newest page; keeps scroll position and status unless something changed.
  function request(before = null, movement = 'refresh', quiet = false) {
    if (!active || !connected() || pending) return;
    const requestId = `conversation-${++serial}`; pending = { requestId, before, movement, quiet };
    if (!quiet) { busy(true); $('chat-status').textContent = `正在读取 ${agentName()} 原始消息…`; }
    send({ type: 'conversation', requestId, ...(movement !== 'refresh' ? { before, binding } : {}) });
    timer = setTimeout(() => { if (pending?.requestId === requestId) { pending = undefined; busy(false); if (!quiet) $('chat-status').textContent = '读取超时，可刷新重试或切换到终端历史。'; } }, 25000);
  }
  function reset() {
    active = false; clearTimeout(timer); pending = undefined; binding = page = undefined; cursor = null; stack = []; shown = '';
    $('chat-messages').replaceChildren(); $('chat-status').textContent = ''; busy(false);
    $('chat-older').hidden = $('chat-newer').hidden = true;
  }
  $('chat-refresh').onclick = () => request();
  $('chat-older').onclick = () => page?.hasOlder && request(page.before, 'older');
  $('chat-newer').onclick = () => stack.length && request(stack.at(-1), 'newer');
  return {
    open() { reset(); active = true; request(); }, close: reset,
    followLatest() { follow = true; }, // After sending, jump to the reply even if the reader had scrolled up.
    refresh() {
      const selection = getSelection();
      if (!active || pending || stack.length || cursor !== null) return; // Only follow the newest page.
      if (selection && !selection.isCollapsed && $('chat-messages').contains(selection.anchorNode)) return;
      request(null, 'refresh', true);
    },
    receive(result) {
      if (!active || !pending || result.requestId !== pending.requestId) return;
      clearTimeout(timer); const action = pending; pending = undefined; busy(false);
      if (!result.ok) { if (!action.quiet) $('chat-status').textContent = result.error || '读取失败，可刷新重试或切换到终端历史。'; return; }
      const signature = `${result.binding}|${result.before}|${result.messages.map(m => `${m.id}:${m.text.length}`).join(',')}`;
      if (action.quiet && signature === shown) return;
      const viewport = $('conversation-content'), atBottom = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight < 80, top = viewport.scrollTop;
      shown = signature;
      if (action.movement === 'older') stack.push(cursor);
      else if (action.movement === 'newer') stack.pop();
      else stack = [];
      binding = result.binding; cursor = action.before; page = result;
      const fragment = document.createDocumentFragment();
      for (const message of result.messages) fragment.append(messageElement(message, agentName()));
      $('chat-messages').replaceChildren(fragment);
      $('chat-older').hidden = !result.hasOlder; $('chat-newer').hidden = !stack.length;
      $('chat-status').textContent = (result.skipped ? '部分超大记录未显示；' : '') + (result.messages.length ? `${result.messages.length} 条消息 · ${stack.length ? '较早的一页' : '自动跟随最新回复'}` : result.hasOlder ? '此段没有可展示的消息，请继续加载更早消息。' : '暂无已完成的消息；可在终端处理启动菜单或发送首条消息。');
      const stick = atBottom || follow; follow = false;
      viewport.scrollTop = action.quiet ? (stick ? viewport.scrollHeight : top) : action.movement === 'refresh' ? viewport.scrollHeight : 0;
    },
  };
}
