import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { mkdtemp, rm, readdir, readFile, stat } from 'node:fs/promises';
import { UploadStore, MAX_IMAGE_BYTES } from '../server/uploads.mjs';
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlZsAAAAASUVORK5CYII=', 'base64');
const user = { sub: 'test', exp: Math.floor(Date.now() / 1000) + 300 };
const ref = { id: '$1', generation: 'test-generation' };
function request(bytes = png, type = 'image/png', headers = {}) {
  const r = Readable.from([bytes]); r.headers = { 'content-type': type, 'content-length': String(bytes.length), 'x-upload-name': encodeURIComponent('../图片.png'), ...headers }; return r;
}
async function setup(t, quota) {
  const root = await mkdtemp('/tmp/web-upload-test-'); t.after(() => rm(root, { recursive: true, force: true }));
  return { root, store: new UploadStore(root, '/test-host/images', quota) };
}
test('upload uses random mode-600 filename and constructs Android-style host image metadata', async t => {
  const { root, store } = await setup(t);
  const item = await store.receive(request(), user, ref, '%1');
  const names = await readdir(root); assert.equal(names.length, 1); assert.match(names[0], /^[a-f0-9]{48}\.png$/);
  assert.equal((await stat(root + '/' + names[0])).mode & 0o777, 0o600);
  assert.deepEqual(await readFile(root + '/' + names[0]), png);
  const message = await store.message({ text: '看一下', attachments: [item.id] }, user, ref, '%1');
  assert.ok(message.includes('/test-host/images/' + names[0])); assert.ok(message.includes('图片请使用图片查看工具读取'));
  for (const [u, r, pane] of [[{ ...user, sub: 'other' }, ref, '%1'], [user, { ...ref, generation: 'replaced' }, '%1'], [user, ref, '%2']]) {
    await assert.rejects(store.message({ text: '', attachments: [item.id] }, u, r, pane));
  }
  for (const text of ['/command', '!command']) await assert.rejects(store.message({ text, attachments: [item.id] }, user, ref, '%1'));
});
test('format spoofing, unsupported types, oversized uploads and quota exhaustion leave no file', async t => {
  const { root, store } = await setup(t);
  await assert.rejects(store.receive(request(Buffer.from('not png')), user, ref, '%1'));
  await assert.rejects(store.receive(request(png, 'image/svg+xml'), user, ref, '%1'));
  await assert.rejects(store.receive(request(png, 'image/png', { 'content-length': String(MAX_IMAGE_BYTES + 1) }), user, ref, '%1'));
  await assert.rejects(store.receive(request(png, 'image/png', { 'content-length': '10' }), user, ref, '%1'));
  assert.deepEqual(await readdir(root), []);
  const tiny = new UploadStore(root, '/test-host/images', 10);
  await assert.rejects(tiny.receive(request(), user, ref, '%1'));
  assert.deepEqual(await readdir(root), []);
});
test('expired identity and interrupted stream delete partial files', async t => {
  const { root, store } = await setup(t);
  await assert.rejects(store.receive(request(), { ...user, exp: 1 }, ref, '%1'));
  const stream = Readable.from((async function* () { yield png.subarray(0, 15); throw new Error('interrupted'); })());
  stream.headers = request().headers;
  await assert.rejects(store.receive(stream, user, ref, '%1'));
  assert.deepEqual(await readdir(root), []); assert.equal(store.active, 0); assert.equal(store.reserved, 0);
});
