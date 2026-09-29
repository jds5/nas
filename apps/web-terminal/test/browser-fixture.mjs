import pty from 'node-pty';
// TEST IMAGE ONLY. Simulates the edge assertion with temporary keys on an isolated Docker network.
// Never publish this fixture or mount production tmux sockets into it.
import { generateKeyPair, exportJWK, createLocalJWKSet, SignJWT } from 'jose';
import { mkdtemp, rm, mkdir, copyFile, chmod } from 'node:fs/promises';
import { createApp } from '../server/app.mjs';
import { UploadStore } from '../server/uploads.mjs';
import { Tmux } from '../server/tmux.mjs';
const dir = await mkdtemp('/tmp/nas-browser-test-');
const tmux = new Tmux(`${dir}/socket`);
await tmux.command(['-f', '/dev/null', 'new-session', '-d', '-s', 'demo-project', '/bin/sh']);
await tmux.command(['new-session', '-d', '-s', '中文测试', '/bin/sh']);
await mkdir(`${dir}/uploads`);
await copyFile('/bin/cat', `${dir}/codex`); await chmod(`${dir}/codex`, 0o755);
await tmux.command(['new-session', '-d', '-s', 'codex-images', `${dir}/codex`]);
const pair = await generateKeyPair('RS256');
const config = { issuer: 'https://test.cloudflareaccess.com', audience: 'browser-test', origin: 'http://terminal-fixture:3000', emails: ['test@example.test'] };
const token = await new SignJWT({ email: config.emails[0], type: 'app' }).setProtectedHeader({ alg: 'RS256', kid: 'browser-test' })
  .setIssuer(config.issuer).setAudience(config.audience).setSubject('test-browser').setIssuedAt().setExpirationTime('1h').sign(pair.privateKey);
// Isolated shell double: never mount NAS credentials into this fake-auth fixture.
const ssh = { exists: async ref => ref?.kind === "ssh" && /^ssh-[a-f0-9]{32}$/.test(ref.id), attach: (_ref, size) => pty.spawn("/bin/sh", [], { ...size, cwd: "/tmp", env: { PATH: "/usr/bin:/bin", TERM: "xterm-256color" } }) };
const conversation = { read: async ({ before, binding }) => {
  if (binding && binding !== 'a'.repeat(64)) return { ok: false, error: 'binding changed' };
  const table = '| ' + Array.from({ length: 14 }, (_, i) => '列' + i).join(' | ') + ' |\n|' + ' --- |'.repeat(14) + '\n| ' + Array.from({ length: 14 }, (_, i) => i === 13 ? 'TABLE_RIGHT_EDGE' : '完整表格内容').join(' | ') + ' |';
  return { ok: true, binding: 'a'.repeat(64), before: before == null ? 100 : 0, hasOlder: before == null, skipped: false,
    messages: before == null ? [
      { id: 'u1', role: 'user', text: '用户输入 **保持原文**' },
      { id: 'a1', role: 'assistant', phase: 'final_answer', text: '# Markdown 对话\n\n**粗体** 与 *斜体*\n\n' + table + '\n\n```bash\nprintf hello\n```\n\n- 列表项目\n\n[安全链接](https://example.com) [恶意链接](javascript:alert(1)) [本地路径](/etc/passwd)\n\n![远程图片](https://evil.invalid/track.png)\n\n<img src="https://evil.invalid/raw.png" onerror="alert(1)"><script>alert(1)</script>' },
    ] : [{ id: 'old', role: 'assistant', text: '更早消息 OLD_PAGE' }] };
} };
const app = createApp({ config, tmux, ssh, conversation, uploads: new UploadStore(`${dir}/uploads`, `${dir}/uploads`), keyResolver: createLocalJWKSet({ keys: [{ ...await exportJWK(pair.publicKey), kid: 'browser-test' }] }) });
const assertion = req => { req.headers['cf-access-jwt-assertion'] = token; };
app.server.prependListener('request', assertion);
app.server.prependListener('upgrade', assertion);
app.server.listen(3000, '0.0.0.0');
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, async () => {
  app.shutdown(); await tmux.command(['kill-server']); await rm(dir, { recursive: true });
});
