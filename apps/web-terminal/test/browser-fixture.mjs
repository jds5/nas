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
const app = createApp({ config, tmux, ssh, uploads: new UploadStore(`${dir}/uploads`, `${dir}/uploads`), keyResolver: createLocalJWKSet({ keys: [{ ...await exportJWK(pair.publicKey), kid: 'browser-test' }] }) });
const assertion = req => { req.headers['cf-access-jwt-assertion'] = token; };
app.server.prependListener('request', assertion);
app.server.prependListener('upgrade', assertion);
app.server.listen(3000, '0.0.0.0');
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, async () => {
  app.shutdown(); await tmux.command(['kill-server']); await rm(dir, { recursive: true });
});
