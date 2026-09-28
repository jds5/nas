// Run inside NPM: node /tmp/nas-terminal-routing.mjs apply|update|remove
// Requires /tmp/nas-terminal-location.conf (copy the reviewed location template).
// Uses NPM's model/config generator. Only the marked terminal block is changed.
import fs from 'node:fs';
import Model from '/app/models/proxy_host.js';
import nginx from '/app/internal/nginx.js';
import db from '/app/db.js';
const begin = '# BEGIN nas-web-terminal';
const end = '# END nas-web-terminal';
const action = process.argv[2];
const getHost = () => Model.query().findById(7).withGraphFetched('certificate').modifyGraph('certificate', b => b.select('id', 'provider'));
try {
  if (!['apply', 'update', 'remove'].includes(action)) throw new Error('expected apply, update or remove');
  const host = await getHost();
  if (!host?.enabled || host.access_list_id || !host.domain_names.includes('origin-home.rokano.org')) throw new Error('unexpected proxy boundary');
  const original = host.advanced_config || '';
  let next = original;
  if (action === 'apply') {
    if (original.includes(begin) || /location[^\n]*\/terminal/.test(original) || host.locations.some(l => l.path.startsWith('/terminal'))) throw new Error('terminal route already exists; inspect before editing');
    const snippet = fs.readFileSync('/tmp/nas-terminal-location.conf', 'utf8');
    next = `${original}\n${begin}\n${snippet.trimEnd()}\n${end}\n`;
  } else {
    const start = original.indexOf(`\n${begin}\n`);
    const finish = original.indexOf(`\n${end}\n`, start);
    if (start < 0 || finish < 0) throw new Error('marked terminal block missing');
    const replacement = action === 'update' ? `\n${begin}\n${fs.readFileSync('/tmp/nas-terminal-location.conf', 'utf8').trimEnd()}\n${end}\n` : '';
    next = original.slice(0, start) + replacement + original.slice(finish + end.length + 2);
  }
  const backup = `/data/nas-terminal-routing-${Date.now()}.json`;
  fs.writeFileSync(backup, JSON.stringify({ id: host.id, advanced_config: original, locations: host.locations }), { mode: 0o600, flag: 'wx' });
  try {
    await Model.query().findById(host.id).patch({ advanced_config: next });
    const result = await nginx.configure(Model, 'proxy_host', await getHost());
    if (!result.nginx_online) throw new Error('nginx validation failed');
    console.log(`terminal routing ${action} complete; backup: ${backup}`);
  } catch {
    await Model.query().findById(host.id).patch({ advanced_config: original });
    const restored = await nginx.configure(Model, 'proxy_host', await getHost());
    throw new Error(restored.nginx_online ? 'change failed; previous configuration restored' : 'change and rollback validation failed');
  }
} finally { await db().destroy(); }
