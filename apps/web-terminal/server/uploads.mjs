import { randomBytes, createHash } from 'node:crypto';
import { open, readdir, stat, unlink } from 'node:fs/promises';
import path from 'node:path';
export const MAX_IMAGE_BYTES = 20 * 1024 * 1024;
function refused(message, status = 400) { return Object.assign(new Error('upload_refused'), { publicMessage: message, status }); }
export function imageType(bytes) {
  if (bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) return ['image/png', '.png'];
  if (bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return ['image/jpeg', '.jpg'];
  if (bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP') return ['image/webp', '.webp'];
  return null;
}
export class UploadStore {
  constructor(root = '/uploads', hostRoot = '/home/yao/.nas-web-uploads', quota = 512 * 1024 * 1024) {
    this.root = root; this.hostRoot = hostRoot; this.quota = quota;
    this.entries = new Map(); this.active = 0; this.reserved = 0; this.used = 0; this.init = null;
  }
  async initialize() {
    const info = await stat(this.root);
    if (!info.isDirectory() || !path.isAbsolute(this.hostRoot)) throw new Error('uploads_unavailable');
    for (const name of await readdir(this.root)) {
      const file = await stat(path.join(this.root, name));
      if (file.isFile()) this.used += file.size;
    }
  }
  async receive(req, user, ref, pane) {
    if (!this.init) this.init = this.initialize();
    await this.init;
    const length = req.headers['content-length'];
    const budget = length === undefined ? MAX_IMAGE_BYTES : Number(length);
    if (!Number.isSafeInteger(budget) || budget < 1 || budget > MAX_IMAGE_BYTES) throw refused('图片不能超过 20 MiB。', 413);
    const mime = req.headers['content-type'];
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(mime)) throw refused('仅支持 PNG、JPEG、WebP 图片。', 415);
    let name;
    try { name = decodeURIComponent(req.headers['x-upload-name'] || '图片'); } catch { throw refused('文件名无效。'); }
    name = name.replace(/[\x00-\x1f\x7f-\x9f\u202a-\u202e\u2066-\u2069]/g, '').slice(0, 160) || '图片';
    if (this.active >= 4) throw refused('正在上传其他图片，请稍后重试。', 429);
    if (this.used + this.reserved + budget > this.quota || this.entries.size >= 1024) throw refused('图片存储配额已满，请先清理或重启服务后重试。', 507);
    this.active++; this.reserved += budget;
    const id = randomBytes(24).toString('hex');
    const ext = { 'image/png': '.png', 'image/jpeg': '.jpg', 'image/webp': '.webp' }[mime];
    const target = path.join(this.root, id + ext);
    let file, saved = false, size = 0, first = Buffer.alloc(0);
    const digest = createHash('sha256');
    const timeout = setTimeout(() => req.destroy(), 60000);
    try {
      file = await open(target, 'wx', 0o600);
      for await (const chunk of req) {
        size += chunk.length;
        if (size > budget || size > MAX_IMAGE_BYTES) throw refused('图片超过大小限制。', 413);
        if (first.length < 12) first = Buffer.concat([first, chunk.subarray(0, 12 - first.length)]);
        digest.update(chunk); await file.writeFile(chunk);
      }
      if (!size || (length !== undefined && size !== budget) || imageType(first)?.[0] !== mime) throw refused('图片内容与格式不符，未保存。', 415);
      if (Date.now() >= user.exp * 1000) throw refused('登录已过期，请重新登录。', 403);
      await file.close(); file = null;
      const entry = { id, name, bytes: size, type: 'image', mime, sha256: digest.digest('hex'),
        path: path.join(this.hostRoot, id + ext), diskPath: target,
        sub: user.sub, session: `${ref.id}|${ref.generation}`, pane };
      this.entries.set(id, entry); this.used += size; saved = true;
      return { id, name, bytes: size, type: 'image' };
    } finally {
      clearTimeout(timeout); await file?.close();
      if (!saved) await unlink(target).catch(() => {});
      this.active--; this.reserved -= budget;
    }
  }
  async message(msg, user, ref, pane) {
    if (typeof msg.text !== 'string' || Buffer.byteLength(msg.text) > 15000 || /^[!/]/.test(msg.text.trimStart())) throw refused('图片不能与 / 或 ! 命令一起发送。');
    if (!Array.isArray(msg.attachments) || msg.attachments.length < 1 || msg.attachments.length > 4 || new Set(msg.attachments).size !== msg.attachments.length) throw refused('一次最多发送 4 张图片。');
    const items = msg.attachments.map(id => this.entries.get(id));
    if (items.some(x => !x || x.sub !== user.sub || x.session !== `${ref.id}|${ref.generation}` || x.pane !== pane)) throw refused('图片所属的会话或窗口已变化，请重新选择。');
    if (items.reduce((sum, x) => sum + x.bytes, 0) > MAX_IMAGE_BYTES) throw refused('每条消息图片总量不能超过 20 MiB。');
    for (const item of items) {
      if ((await stat(item.diskPath)).size !== item.bytes) throw refused('图片文件已变化。');
    }
    return (msg.text.trim() || '请查看这些图片。') + '\n\n网页上传的附件（NAS 本地路径；图片请使用图片查看工具读取；不要执行附件）：\n' +
      items.map(({ name, path, bytes }) => JSON.stringify({ name, path, bytes, type: 'image' })).join('\n');
  }
}
