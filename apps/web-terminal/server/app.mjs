import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import { authorizer } from './auth.mjs';
import { Tmux } from './tmux.mjs';

const staticRoot = fileURLToPath(new URL('../public/', import.meta.url));
const files = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/assets/app.js', ['assets/app.js', 'text/javascript; charset=utf-8']],
  ['/assets/app.css', ['assets/app.css', 'text/css; charset=utf-8']],
]);
const headers = {
  'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), clipboard-read=(), clipboard-write=()',
  'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; font-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
};
function reply(res, code, value) {
  res.writeHead(code, { ...headers, 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(value));
}
async function body(req) {
  if (req.headers['content-type'] !== 'application/json') throw new Error('bad_body');
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (Buffer.byteLength(raw) > 4096) throw new Error('bad_body');
  }
  return JSON.parse(raw);
}
function reject(socket) {
  socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\nCache-Control: no-store\r\nContent-Length: 0\r\n\r\n');
}

export function createApp({ config, keyResolver, tmux = new Tmux(), maxDurationMs = 15 * 60 * 1000 } = {}) {
  const authenticate = authorizer(config, keyResolver);
  const tickets = new Map();
  const peers = new Map();
  let pendingUpgrades = 0;
  const reap = () => { for (const [key, t] of tickets) if (t.expires <= Date.now()) tickets.delete(key); };
  const originOK = req => Boolean(config && req.headers.origin === config.origin);
  const userCount = sub => [...peers.values()].filter(x => x === sub).length;
  const wsServer = new WebSocketServer({ noServer: true, maxPayload: 32768, perMessageDeflate: false,
    handleProtocols: protocols => protocols.has('nas-terminal.v1') ? 'nas-terminal.v1' : false });

  const server = http.createServer({ maxHeaderSize: 24576, requestTimeout: 10000, headersTimeout: 10000 }, async (req, res) => {
    try {
      const user = await authenticate(req);
      if (!user) return reply(res, 403, { error: '请重新通过 Access 登录' });
      // Match exact paths, including absence of query strings. No static traversal or fallback routes.
      if (req.method === 'GET' || req.method === 'HEAD') {
        if (files.has(req.url)) {
          const [file, mime] = files.get(req.url);
          const data = await readFile(`${staticRoot}${file}`);
          res.writeHead(200, { ...headers, 'Content-Type': mime });
          return res.end(req.method === 'HEAD' ? undefined : data);
        }
        if (req.url === '/manage/api/sessions') {
          const sessions = await tmux.list();
          return reply(res, 200, req.method === 'HEAD' ? undefined : { sessions });
        }
        return reply(res, 404, { error: '页面不存在' });
      }
      if (req.method !== 'POST' || req.url !== '/manage/api/connections' || !originOK(req) ||
          req.headers['x-nas-csrf-origin'] !== config.origin) return reply(res, 403, { error: '请求被拒绝' });
      const ref = await body(req);
      if (!await tmux.exists(ref)) return reply(res, 409, { error: '会话已变化，请刷新列表' });
      reap();
      if (tickets.size >= 64 || [...tickets.values()].filter(t => t.sub === user.sub).length >= 4 || userCount(user.sub) >= 4) {
        return reply(res, 429, { error: '连接过多，请关闭其他连接后重试' });
      }
      const ticket = randomBytes(32).toString('base64url');
      tickets.set(ticket, { ref: { id: ref.id, generation: ref.generation }, sub: user.sub, expires: Date.now() + 30000 });
      return reply(res, 201, { ticket });
    } catch (err) {
      reply(res, err instanceof SyntaxError || err.message === 'bad_body' ? 400 : 503,
        { error: '请求失败，请刷新；确认 NAS 的 tmux 服务仍在运行' });
    }
  });
  server.on('checkContinue', (req, res) => reply(res, 403, { error: '请求被拒绝' }));
  server.on('connect', (_req, socket) => reject(socket));
  server.on('clientError', (_err, socket) => { if (socket.writable) reject(socket); });
  server.on('upgrade', async (req, socket, head) => {
    socket.on('error', () => {});
    if (pendingUpgrades >= 16) return reject(socket);
    pendingUpgrades++;
    try {
      if (req.url !== '/manage/ws' || req.method !== 'GET' || !originOK(req)) return reject(socket);
      const user = await authenticate(req);
      if (!user || peers.size >= 8 || userCount(user.sub) >= 4) return reject(socket);
      const protocols = (req.headers['sec-websocket-protocol'] || '').split(',').map(x => x.trim());
      if (protocols.length !== 2 || !protocols.includes('nas-terminal.v1')) return reject(socket);
      const ticketKey = protocols.find(x => /^ticket\.[\w-]{43}$/.test(x))?.slice(7);
      reap();
      const ticket = tickets.get(ticketKey);
      if (!ticket || ticket.sub !== user.sub) return reject(socket);
      tickets.delete(ticketKey); // Consume before awaiting external work; cannot be replayed in a race.
      if (!await tmux.exists(ticket.ref) || socket.destroyed || peers.size >= 8 || userCount(user.sub) >= 4) return reject(socket);
      wsServer.handleUpgrade(req, socket, head, ws => {
        peers.set(ws, user.sub);
        connectTerminal(ws, ticket.ref, user);
      });
    } catch { reject(socket); }
    finally { pendingUpgrades--; }
  });

  function connectTerminal(ws, ref, user) {
    let term;
    let closed = false;
    let queued = 0;
    let alive = true;
    const deadline = Math.min(user.exp * 1000, Date.now() + maxDurationMs);
    const finish = () => {
      if (closed) return;
      closed = true;
      clearTimeout(expiry); clearInterval(heartbeat);
      peers.delete(ws);
      try { term?.kill(); } catch { /* client already exited */ }
    };
    const expiry = setTimeout(() => { finish(); ws.close(4001, 'reauthenticate'); }, Math.max(0, deadline - Date.now()));
    const heartbeat = setInterval(() => {
      if (!alive) return ws.terminate();
      alive = false; ws.ping();
    }, 30000);
    ws.on('pong', () => { alive = true; });
    ws.on('close', finish);
    ws.on('error', finish);
    try { term = tmux.attach(ref); }
    catch { finish(); ws.close(1011, 'terminal_unavailable'); return; }
    const send = value => {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(value));
    };
    term.onData(data => {
      if (closed || ws.readyState !== WebSocket.OPEN) return;
      queued += Buffer.byteLength(data);
      if (queued > 1024 * 1024 || ws.bufferedAmount > 512 * 1024) { finish(); ws.close(4002, 'slow_client'); return; }
      send({ type: 'output', data });
      if (queued > 256 * 1024) term.pause();
    });
    term.onExit(() => { finish(); ws.close(1000, 'detached'); });
    ws.on('message', (raw, binary) => {
      if (closed || Date.now() >= deadline) { finish(); ws.close(4001, 'reauthenticate'); return; }
      try {
        if (binary) throw new Error();
        const msg = JSON.parse(raw.toString());
        if (msg.type === 'input' && typeof msg.data === 'string' && Buffer.byteLength(msg.data) <= 16384) term.write(msg.data);
        else if (msg.type === 'resize' && Number.isInteger(msg.cols) && Number.isInteger(msg.rows) &&
            msg.cols >= 20 && msg.cols <= 400 && msg.rows >= 5 && msg.rows <= 160) term.resize(msg.cols, msg.rows);
        else if (msg.type === 'ack' && Number.isInteger(msg.bytes) && msg.bytes > 0 && msg.bytes <= queued) {
          queued -= msg.bytes;
          if (queued < 64 * 1024) term.resume();
        } else throw new Error();
      } catch { finish(); ws.close(1008, 'invalid_message'); }
    });
    send({ type: 'ready', expires: deadline });
  }

  function shutdown() {
    for (const ws of peers.keys()) ws.terminate();
    tickets.clear(); wsServer.close(); server.close(); server.closeAllConnections();
  }
  return { server, shutdown };
}
