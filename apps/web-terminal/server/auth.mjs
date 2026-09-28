import { createRemoteJWKSet, jwtVerify } from 'jose';

export function accessConfig(env) {
  try {
    const issuer = new URL(env.CF_ACCESS_TEAM_DOMAIN);
    const origin = new URL(env.CF_ACCESS_ALLOWED_ORIGIN);
    const emails = (env.CF_ACCESS_ALLOWED_EMAILS || '').split(',').map(x => x.trim().toLowerCase()).filter(Boolean);
    if (issuer.protocol !== 'https:' || !issuer.hostname.endsWith('.cloudflareaccess.com') ||
        issuer.href !== `${issuer.origin}/` || issuer.username || issuer.password ||
        origin.protocol !== 'https:' || origin.href !== `${origin.origin}/` || origin.username || origin.password ||
        !env.CF_ACCESS_AUD?.trim() || !emails.length || emails.some(x => !/^[^\s@]+@[^\s@]+$/.test(x))) return null;
    return { issuer: issuer.origin, origin: origin.origin, audience: env.CF_ACCESS_AUD.trim(), emails };
  } catch { return null; }
}

// keyResolver is dependency injection for signed-token tests; no runtime auth bypass.
export function authorizer(config, keyResolver) {
  const keys = config && (keyResolver || createRemoteJWKSet(new URL('/cdn-cgi/access/certs', config.issuer)));
  return async req => {
    if (!config) return null;
    const token = req.headers['cf-access-jwt-assertion'];
    if (typeof token !== 'string' || token.length > 16384) return null;
    try {
      const { payload } = await jwtVerify(token, keys, {
        issuer: config.issuer, audience: config.audience, algorithms: ['RS256'],
        requiredClaims: ['exp', 'iat', 'sub', 'email'], clockTolerance: 0,
      });
      if (typeof payload.sub !== 'string' || !payload.sub || typeof payload.email !== 'string' ||
          !Number.isFinite(payload.iat) || !Number.isFinite(payload.exp) ||
          !config.emails.includes(payload.email.toLowerCase()) || payload.iat > Date.now() / 1000 ||
          (payload.type !== undefined && payload.type !== 'app')) return null;
      return { sub: payload.sub, exp: payload.exp };
    } catch { return null; }
  };
}
