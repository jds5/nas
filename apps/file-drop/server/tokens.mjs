import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

// 直连通道的短期令牌：HMAC-SHA256 签名，绑定动作（up / dl）、文件 ID 和过期时间。
// 密钥每次进程启动随机生成，不落盘、不入库；重启后旧令牌全部失效，页面会自动重新申请。
export class Tokens {
  constructor(secret = randomBytes(32), now = () => Date.now()) {
    this.secret = secret;
    this.now = now;
  }

  #mac(body) {
    return createHmac('sha256', this.secret).update(body).digest();
  }

  sign(action, id, ttlMs) {
    const body = Buffer.from(JSON.stringify({ a: action, id, exp: this.now() + ttlMs })).toString('base64url');
    return `${body}.${this.#mac(body).toString('base64url')}`;
  }

  verify(token, action, id) {
    if (typeof token !== 'string' || token.length > 512) return false;
    const [body, mac, extra] = token.split('.');
    if (!body || !mac || extra !== undefined) return false;
    const given = Buffer.from(mac, 'base64url');
    const expected = this.#mac(body);
    if (given.length !== expected.length || !timingSafeEqual(given, expected)) return false;
    try {
      const claim = JSON.parse(Buffer.from(body, 'base64url').toString());
      return claim.a === action && claim.id === id && Number.isFinite(claim.exp) && claim.exp > this.now();
    } catch { return false; }
  }
}
