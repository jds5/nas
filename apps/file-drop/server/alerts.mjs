import { randomBytes } from 'node:crypto';
import { isIPv6 } from 'node:net';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';

const DAY = 24 * 60 * 60 * 1000;

// IPv6 按 /64 归并（运营商常在同一前缀内轮换地址），IPv4 按单个地址。
export function ipKey(ip) {
  if (typeof ip !== 'string' || !ip || ip.length > 64) return null;
  if (!isIPv6(ip)) return ip;
  const [head, tail = ''] = ip.toLowerCase().split('::');
  const a = head ? head.split(':') : [], b = tail ? tail.split(':') : [];
  const full = [...a, ...Array(8 - a.length - b.length).fill('0'), ...b];
  return `${full.slice(0, 4).map(x => x.replace(/^0+(?=.)/, '')).join(':')}::/64`;
}

// 记录见过的来源 IP；首次出现时在 root/alerts/ 写一个事件文件，由宿主机定时任务推送到 Kuma 后删除。
// 应用本身不访问 Kuma，出站限制保持不变。90 天未出现的 IP 会被遗忘，再次出现时重新告警。
export class IpAlerts {
  constructor(root, now = () => Date.now()) {
    this.file = `${root}/known-ips.json`;
    this.dir = `${root}/alerts`;
    this.now = now;
    this.known = new Map();
    this.saving = Promise.resolve();
  }

  async init() {
    await mkdir(this.dir, { mode: 0o700, recursive: true });
    try {
      for (const [key, last] of Object.entries(JSON.parse(await readFile(this.file, 'utf8')))) {
        if (Number.isFinite(last) && this.now() - last < 90 * DAY) this.known.set(key, last);
      }
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }

  #save() {
    const data = JSON.stringify(Object.fromEntries(this.known));
    this.saving = this.saving.catch(() => {}).then(async () => {
      await writeFile(`${this.file}.tmp`, data, { mode: 0o600 });
      await rename(`${this.file}.tmp`, this.file);
    });
    return this.saving;
  }

  async seen(ip, { via, action, email = '', country = '' }) {
    const key = ipKey(ip);
    if (!key) return false;
    const last = this.known.get(key);
    const now = this.now();
    if (last !== undefined && now - last < DAY) return false;
    this.known.set(key, now);
    if (last === undefined) {
      const event = { time: new Date(now).toISOString(), ip: String(ip), country: String(country).slice(0, 8), via, action, email };
      const name = `${now}-${randomBytes(4).toString('hex')}`;
      await writeFile(`${this.dir}/${name}.tmp`, JSON.stringify(event), { mode: 0o600 });
      await rename(`${this.dir}/${name}.tmp`, `${this.dir}/${name}.json`);
    }
    await this.#save();
    return last === undefined;
  }
}
