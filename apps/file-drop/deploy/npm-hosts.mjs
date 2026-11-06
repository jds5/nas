// 在 NPM 容器内运行：node /tmp/file-drop-npm-hosts.mjs apply|remove
// 需要 /tmp/file-drop-nginx-drop.conf 与 /tmp/file-drop-nginx-dl.conf（本目录模板）。
// 使用 NPM 自身的模型与配置生成器，只新增 / 删除 drop、dl 两个 Proxy Host，不改其他 Host。
import fs from 'node:fs';
import Model from '/app/models/proxy_host.js';
import nginx from '/app/internal/nginx.js';
import db from '/app/db.js';

const hosts = { 'drop.rokano.org': '/tmp/file-drop-nginx-drop.conf', 'dl.rokano.org': '/tmp/file-drop-nginx-dl.conf' };
const action = process.argv[2];
const find = name => Model.query().where('is_deleted', 0).then(rows => rows.filter(r => r.domain_names.includes(name)));
try {
  if (!['apply', 'remove'].includes(action)) throw new Error('expected apply or remove');
  for (const [name, file] of Object.entries(hosts)) {
    const existing = await find(name);
    if (action === 'remove') {
      for (const row of existing) {
        await Model.query().findById(row.id).patch({ is_deleted: 1, enabled: 0 });
        await nginx.deleteConfig('proxy_host', row);
        await nginx.reload();
        console.log(`removed ${name} (id ${row.id})`);
      }
      continue;
    }
    if (existing.length) throw new Error(`${name} already exists (id ${existing[0].id}); inspect before editing`);
    const row = await Model.query().insertAndFetch({
      owner_user_id: 1, domain_names: [name], forward_scheme: 'http', forward_host: 'nas-file-drop',
      forward_port: name.startsWith('drop') ? 3000 : 3001, access_list_id: 0, certificate_id: 2,
      ssl_forced: true, caching_enabled: false, block_exploits: true, allow_websocket_upgrade: false,
      http2_support: true, hsts_enabled: false, hsts_subdomains: false, trust_forwarded_proto: false,
      enabled: true, locations: [], meta: {}, advanced_config: fs.readFileSync(file, 'utf8'),
    });
    const result = await nginx.configure(Model, 'proxy_host', await Model.query().findById(row.id).withGraphFetched('certificate'));
    if (!result.nginx_online) {
      // configure 已删除失败的配置并重载，这里只把记录标记为删除。
      await Model.query().findById(row.id).patch({ is_deleted: 1, enabled: 0 });
      throw new Error(`${name}: nginx validation failed, host removed`);
    }
    console.log(`created ${name} (id ${row.id})`);
  }
} finally { await db().destroy(); }
