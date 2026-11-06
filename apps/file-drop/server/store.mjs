import { createHash, randomBytes } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, open, readdir, readFile, rename, rm, statfs, writeFile } from 'node:fs/promises';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const GiB = 1024 ** 3, MiB = 1024 ** 2;
export const defaults = {
  maxFile: 4 * GiB, quota: 20 * GiB, maxFiles: 200, maxPendingPerUser: 3,
  chunk: 32 * MiB, ttlMs: 12 * 60 * 60 * 1000, minFree: 1 * GiB,
};
export const validId = id => typeof id === 'string' && /^[0-9a-f]{32}$/.test(id);

export class StoreError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

// 原文件名只作为元数据：去掉路径、控制字符和双向文本控制符，限长；不参与任何磁盘路径。
export function cleanName(name) {
  if (typeof name !== 'string') return 'file';
  const base = name.split(/[\\/]/).pop()
    .replace(/[\u0000-\u001f\u007f-\u009f​-‏‪-‮⁦-⁩﻿]/g, '')
    .trim().replace(/^\.+/, '');
  return [...base].slice(0, 200).join('') || 'file';
}

// 文件以随机 ID 存在 root/blobs/，元数据为 root/meta.json（原子替换）。上传与下载都按流处理。
export class Store {
  constructor(root, options = {}, now = () => Date.now()) {
    this.root = root;
    this.blobs = `${root}/blobs`;
    this.options = { ...defaults, ...options };
    this.now = now;
    this.entries = new Map();
    this.busy = new Set();
    this.saving = Promise.resolve();
  }

  async init() {
    await mkdir(this.blobs, { mode: 0o700, recursive: true });
    try {
      for (const entry of JSON.parse(await readFile(`${this.root}/meta.json`, 'utf8'))) {
        if (validId(entry.id)) this.entries.set(entry.id, entry);
      }
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
    await this.sweep();
  }

  path(id) { return `${this.blobs}/${id}`; }

  #save() {
    const data = JSON.stringify([...this.entries.values()]);
    this.saving = this.saving.catch(() => {}).then(async () => {
      await writeFile(`${this.root}/meta.json.tmp`, data, { mode: 0o600 });
      await rename(`${this.root}/meta.json.tmp`, `${this.root}/meta.json`);
    });
    return this.saving;
  }

  #live(id) {
    const entry = validId(id) && this.entries.get(id);
    return entry && entry.expires > this.now() ? entry : null;
  }

  usage() {
    let used = 0;
    for (const entry of this.entries.values()) used += entry.size;
    return { used, files: this.entries.size };
  }

  list() {
    return [...this.entries.values()].filter(x => x.expires > this.now())
      .sort((a, b) => b.created - a.created)
      .map(({ id, name, size, received, sha256, created, expires, ip, country }) =>
        ({ id, name, size, received, complete: received === size && Boolean(sha256), sha256, created, expires, ip, country }));
  }

  async create({ name, size, email, ip = '', country = '' }) {
    const o = this.options;
    if (!Number.isSafeInteger(size) || size < 1) throw new StoreError(400, '文件大小无效');
    if (size > o.maxFile) throw new StoreError(413, '超过单个文件大小上限');
    const { used, files } = this.usage();
    if (files >= o.maxFiles) throw new StoreError(507, '文件数已满，请先删除');
    if (used + size > o.quota) throw new StoreError(507, '总容量已满，请先删除');
    const pending = [...this.entries.values()].filter(x => x.email === email && x.received < x.size).length;
    if (pending >= o.maxPendingPerUser) throw new StoreError(429, '同时上传的文件过多');
    const fs = await statfs(this.root);
    if (fs.bavail * fs.bsize < size + o.minFree) throw new StoreError(507, 'NAS 磁盘空间不足');
    const id = randomBytes(16).toString('hex');
    const created = this.now();
    const entry = { id, name: cleanName(name), size, received: 0, sha256: null, created,
      expires: created + o.ttlMs, email, ip: String(ip).slice(0, 64), country: String(country).slice(0, 8) };
    // 先登记再建文件，避免并发的孤立文件清理误删。
    this.entries.set(id, entry);
    try { await (await open(this.path(id), 'wx', 0o600)).close(); } catch (error) { this.entries.delete(id); throw error; }
    await this.#save();
    return entry;
  }

  pending(id) {
    const entry = this.#live(id);
    return entry && entry.received < entry.size ? entry : null;
  }

  // 只接受从当前已收字节处继续写入；分片中途断开时不推进进度，下次从同一偏移重写。
  async write(id, offset, length, source) {
    const entry = this.pending(id);
    if (!entry) throw new StoreError(404, 'not_found');
    if (!Number.isSafeInteger(length) || length < 1 || length > this.options.chunk) throw new StoreError(413, 'bad_length');
    if (offset !== entry.received) throw new StoreError(409, 'bad_offset');
    if (offset + length > entry.size) throw new StoreError(413, 'too_large');
    if (this.busy.has(id)) throw new StoreError(409, 'busy');
    this.busy.add(id);
    try {
      let count = 0;
      const limit = new Transform({
        transform(chunk, _encoding, done) {
          count += chunk.length;
          done(count > length ? new StoreError(413, 'too_large') : null, chunk);
        },
      });
      await pipeline(source, limit, createWriteStream(this.path(id), { flags: 'r+', start: offset }));
      if (count !== length) throw new StoreError(400, 'short_body');
      if (!this.entries.has(id)) throw new StoreError(404, 'not_found');
      entry.received += length;
      if (entry.received === entry.size) {
        const hash = createHash('sha256');
        await pipeline(createReadStream(this.path(id)), hash);
        entry.sha256 = hash.digest('hex');
      }
      await this.#save();
      return entry;
    } finally { this.busy.delete(id); }
  }

  complete(id) {
    const entry = this.#live(id);
    return entry && entry.sha256 && entry.received === entry.size ? entry : null;
  }

  async remove(id) {
    const entry = this.#live(id);
    if (!entry) return false;
    this.entries.delete(id);
    await rm(this.path(id), { force: true });
    await this.#save();
    return true;
  }

  // 删除到期条目（含未完成上传），以及不在元数据中的孤立文件。
  async sweep() {
    const removed = [];
    for (const entry of this.entries.values()) {
      if (entry.expires <= this.now() && !this.busy.has(entry.id)) {
        this.entries.delete(entry.id);
        await rm(this.path(entry.id), { force: true });
        removed.push(entry);
      }
    }
    for (const name of await readdir(this.blobs)) {
      if (!this.entries.has(name)) await rm(`${this.blobs}/${name}`, { force: true, recursive: true });
    }
    if (removed.length) await this.#save();
    return removed;
  }
}
