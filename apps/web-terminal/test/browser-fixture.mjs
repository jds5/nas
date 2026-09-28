// TEST IMAGE ONLY. Simulates the edge assertion with temporary keys on an isolated Docker network.
// Never publish this fixture or mount production tmux sockets into it.
import { generateKeyPair, exportJWK, createLocalJWKSet, SignJWT } from 'jose';
import { mkdtemp, rm } from 'node:fs/promises';
import { createApp } from '../server/app.mjs';
import { Tmux } from '../server/tmux.mjs';
const dir = await mkdtemp('/tmp/nas-browser-test-');
const tmux = new Tmux(`${dir}/socket`);
await tmux.command(['-f', '/dev/null', 'new-session', '-d', '-s', 'demo-project', '/bin/sh']);
await tmux.command(['new-session', '-d', '-s', '中文测试', '/bin/sh']);
const pair = await generateKeyPair('RS256');
const config = { issuer: 'https://test.cloudflareaccess.com', audience: 'browser-test', origin: 'http://terminal-fixture:3000', emails: ['test@example.test'] };
const token = await new SignJWT({ email: config.emails[0], type: 'app' }).setProtectedHeader({ alg: 'RS256', kid: 'browser-test' })
  .setIssuer(config.issuer).setAudience(config.audience).setSubject('test-browser').setIssuedAt().setExpirationTime('1h').sign(pair.privateKey);
const app = createApp({ config, tmux, keyResolver: createLocalJWKSet({ keys: [{ ...await exportJWK(pair.publicKey), kid: 'browser-test' }] }) });
const assertion = req => { req.headers['cf-access-jwt-assertion'] = token; };
app.server.prependListener('request', assertion);
app.server.prependListener('upgrade', assertion);
app.server.listen(3000, '0.0.0.0');
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, async () => {
  app.shutdown(); await tmux.command(['kill-server']); await rm(dir, { recursive: true });
});
