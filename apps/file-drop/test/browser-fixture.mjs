// 仅用于浏览器验收：模拟 Access 签发身份，页面与直连端在同一测试网络内，不能用于线上部署。
import { writeFile, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { generateKeyPair, exportJWK, SignJWT, createLocalJWKSet } from 'jose';
import { createApp } from '../server/app.mjs';
import { Store } from '../server/store.mjs';

const config = { issuer: 'https://test.cloudflareaccess.com', audience: 'drop-browser-test', origin: 'http://drop-fixture:3000', emails: ['owner@example.test'] };
const pair = await generateKeyPair('RS256');
const keyResolver = createLocalJWKSet({ keys: [{ ...await exportJWK(pair.publicKey), kid: 'test' }] });
const store = new Store(await mkdtemp(`${tmpdir()}/drop-browser-`), { chunk: 1024 * 1024 });
await store.init();
const app = createApp({ config, keyResolver, store, directOrigin: 'http://drop-fixture:3001' });
const t = Math.floor(Date.now() / 1000);
const jwt = await new SignJWT({ email: config.emails[0], sub: 'owner', type: 'app', iat: t, exp: t + 3600, iss: config.issuer, aud: config.audience })
  .setProtectedHeader({ alg: 'RS256', kid: 'test' }).sign(pair.privateKey);
await writeFile('/shared/jwt', jwt);
app.page.listen(3000, '0.0.0.0');
app.direct.listen(3001, '0.0.0.0');
console.log('fixture_ready');
