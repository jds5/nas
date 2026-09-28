// Cloudflare Module Worker. Configure the route terminal.rokano.org/*; disable workers.dev.
// Set PUBLIC_ORIGIN / ORIGIN_URL as variables, ORIGIN_SECRET as a Worker Secret.
export default {
  async fetch(request, env) {
    let publicOrigin, upstream;
    try {
      publicOrigin = new URL(env.PUBLIC_ORIGIN);
      upstream = new URL(env.ORIGIN_URL);
      if (publicOrigin.protocol !== 'https:' || upstream.protocol !== 'https:' ||
          publicOrigin.href !== `${publicOrigin.origin}/` || upstream.pathname !== '/' ||
          upstream.username || upstream.password || upstream.search || upstream.hash ||
          !/^[a-f0-9]{64}$/.test(env.ORIGIN_SECRET || '')) throw new Error();
    } catch { return new Response('Unavailable', { status: 503 }); }
    const incoming = new URL(request.url);
    if (incoming.origin !== publicOrigin.origin || !['GET', 'HEAD', 'POST'].includes(request.method)) {
      return new Response('Forbidden', { status: 403 });
    }
    upstream.pathname = incoming.pathname;
    upstream.search = incoming.search;
    const headers = new Headers(request.headers);
    headers.set('X-Nas-Terminal-Origin', env.ORIGIN_SECRET); // Always overwrite attacker-supplied values.
    // Preserve Origin, Cf-Access-Jwt-Assertion and WebSocket subprotocols unchanged.
    return fetch(new Request(upstream, { method: request.method, headers,
      body: ['GET', 'HEAD'].includes(request.method) ? undefined : request.body,
      redirect: 'manual' })); // Workers forwards an upstream 101 including its WebSocket.
  },
};
