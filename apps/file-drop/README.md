# 临时文件中转站

2026-11-06 起。用途：在外面时把文件从一台电脑临时传到自己的手机（反向也可），代替 U 盘。只提供上传、下载，文件 12 小时后自动删除。

本目录是该任务的独立工作目录，进展、决定和验证结果都同步更新本文件。

## 当前进度

- [x] 设计方案；用户确认（11-06）：独立子域名、4 GiB / 20 GiB、固定保留 12 小时、Access 邮箱验证码登录 + 文件直连
- [x] 第 1 阶段（11-06）：代码、Docker、10 项自动化测试与浏览器验收全部通过，见“构建与测试”
- [x] 第 2 阶段（11-06）：用户 `filedrop`、目录、出站防火墙、容器、NPM 限速；未登录全部拒绝
- [x] 第 3 阶段（11-06）：用户完成 Cloudflare DNS / Origin Rule / Access；NPM 新增 Host 9（drop）、10（dl）；匿名与源站绕过验收通过，见 [部署记录](../../docs/operations/2026-11-06-临时文件中转站部署.md)
- [ ] 第 4 阶段：**等待用户真实登录验收**（电脑上传、手机下载）；之后视需要补 Kuma 告警

## 架构

鉴权要求以 [Access 实施手册](../../docs/security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md) 中的“整站私有应用”为准，实现方式参照 [网页终端](../web-terminal/README.md)：

```text
页面与接口：浏览器 → drop.rokano.org（橙云，CNAME origin-home，Origin Rule 回源 8443）
  → Cloudflare Access（独立应用，只允许你的邮箱，邮箱验证码登录）
  → NPM → nas-drop-front / nas-file-drop:3000
  → 应用逐请求验证 Access JWT（RS256、iss、独立 aud、exp/nbf、邮箱白名单），写操作再验 Origin + CSRF 头
文件数据：浏览器 → dl.rokano.org:8443（灰云，CNAME jelly，直连家庭 IP）
  → NPM（按 IP 限并发与频率）→ nas-file-drop:3001
  → 只接受带有效短期令牌的上传分片与下载，其他一律 404
存储：/mnt/pool-main/drop（专用用户 filedrop，700，不进备份）
```

### 文件直连通道（11-06 用户确认）

Cloudflare 免费线路对家里是负加速，所以**只让文件数据绕开 Cloudflare**：页面、登录、列表仍走 `drop.rokano.org`（橙云 + Access），上传、下载的数据走灰云子域名 `dl.rokano.org` 直连 NAS。做法类似对象存储的“预签名 URL”：

```text
drop.rokano.org（橙云，Access 登录）
  └─ 页面请求“上传 / 下载某个文件” → 应用验 Access JWT → 发短期签名令牌
dl.rokano.org:8443（灰云，直连家庭 IP，复用 NPM 现有 *.rokano.org 证书与 8443 端口）
  └─ 只接受带有效令牌的两个动作：上传分片、下载文件；其他一律 404
```

- 令牌由应用用 HMAC-SHA256 签发，绑定：动作、文件 ID（上传还绑定上传会话和总大小）、过期时间。下载令牌 5 分钟、上传令牌 30 分钟，页面按需续签。密钥只在应用里，不入库，重启可换。
- 令牌只在请求开始时检查；传输中途过期不打断，断点续传需回页面重新获取（自动完成）。
- 直连端只做校验令牌、写分片、读文件三件事，不含登录、列表、删除，代码量很小；与 Access 那一端用不同的监听端口和 NPM Host，互不串用。
- 上传令牌放在请求头（跨域 CORS 只允许 `https://drop.rokano.org`）；下载要用浏览器直接保存，令牌只能放在 URL 里，所以有效期短，NPM 对 `dl` 主机关闭访问日志或去掉查询串。
- NPM 对 `dl` 主机按 IP 限速、限并发，只放行 GET/HEAD/PUT/OPTIONS。
- 不新增路由器转发或防火墙放行：8443 已对公网开放，靠 SNI/Host 区分。家庭 IP 已因 Jellyfin、Immich 等直连域名公开，不新增暴露。
- 附带好处：数据在 NAS 自己的证书下端到端 TLS，Cloudflare 看不到文件内容。

## 功能范围

| 包含 | 不包含 |
| --- | --- |
| 上传（拖放 / 选择，可多个文件，显示进度，断点续传） | 公开分享链接、免登录下载 |
| 文件列表：名称、大小、上传时间、剩余时间、SHA-256 | 在线预览、图片缩略图、播放 |
| 下载（支持断点续传，手机浏览器直接保存） | 文件夹、重命名、编辑 |
| 手动删除；到期自动删除 | 访问 NAS 其他目录 |
| 一个手机友好的页面 | 多用户、自建账号密码 |

删除严格说不在“只上传下载”之内，但为了用完即删、减少文件在 NAS 上的停留时间，保留手动删除和到期删除。

## 安全设计

### 1. 身份：Cloudflare Access + 应用二次验签

- 整个应用（首页、静态资源、所有接口）都要求登录，没有匿名可访问的路径，也没有免鉴权的健康检查或调试开关。
- 独立 Access 应用、独立 Audience，Allow 只放你的邮箱；应用服务端用 `jose` 验签并核对邮箱白名单。配置缺失或 JWKS 不可用时一律拒绝。
- 这样即使有人知道家庭 IP、绕过 Cloudflare 直连 8443，也拿不到文件。

### 2. 隔离：建议独立子域名

`rokano.org` 下已经有 Homeland、Stock 和**网页终端**（有 NAS shell 权限）。如果中转站也放在 `rokano.org/drop`，就和终端同一个浏览器 Origin：中转站一旦出现 XSS，可以借用终端的登录状态。所以建议用独立子域名 `drop.rokano.org`：

- 浏览器同源策略把它和终端隔开；
- Access 会话、退出登录也与其他应用互不影响，可以单独设置较短的会话时长。

代价是要在 Cloudflare 新增 DNS 记录与 Origin Rule，NPM 新增 Host。

### 3. 只传不渲染：防止上传内容变成攻击代码

- 文件下载一律 `Content-Disposition: attachment`、`Content-Type: application/octet-stream`、`X-Content-Type-Options: nosniff`，浏览器只保存、不打开；不做任何预览。
- 页面使用严格 CSP（只允许本站脚本，禁止内联脚本和 iframe），文件名只作为文本插入页面。
- 磁盘上用随机 ID 存储，原文件名清洗后（去掉路径、控制字符，限长）只存在元数据里，下载时用 RFC 5987 的 `filename*` 还原。用户输入不参与任何文件路径拼接。

### 4. 写操作防护

- 上传、删除只接受 POST/DELETE，并同时校验精确 `Origin` 和自定义 CSRF 头；其他方法和未知路径一律拒绝。
- 没有带副作用的 GET。

### 5. 容量与滥用限制

| 项目 | 建议值 |
| --- | --- |
| 单个文件 | ≤ 4 GiB |
| 总容量 | ≤ 20 GiB，满了拒绝上传 |
| 文件数 | ≤ 200 |
| 保存时长 | 固定 12 小时（用户决定），到期前可手动删除 |
| 分片 | 每片 32 MiB（Cloudflare 免费版单个请求上限 100 MB），可续传 |
| 并发上传 | 每人 3 个 |

到期文件由应用每 10 分钟清理一次，未完成的上传 12 小时后清理。

### 6. 存储

- 目录 `/mnt/pool-main/drop`（数据盘，剩余 6.3 TiB），属主 `yao`、权限 700，只挂进这个容器。
- 不在 restic 备份范围内（备份只覆盖 `/mnt/pool-main` 下的固定目录和 `/opt/nas`），临时文件不会进入备份和云端。
- 文件明文存在 NAS 上。**传输经过 Cloudflare 时会在其边缘解密**，这一点和 Homeland、Stock 一样。很敏感的文件建议先打加密压缩包再传。

### 7. 容器加固

只读根文件系统、`cap_drop: ALL`、`no-new-privileges`、内存与进程数上限；只加入 `nas-drop-front` 网络，不发布宿主端口，不挂 Docker socket，不连数据库。

**不用 `yao`（UID 1000）运行。** 现场核对：`yao` 属于 `docker` 组（等同 root）和 `sudo` 组，网页终端为了操作 tmux 才不得不用它，中转站没有这个需要。改为新建专用系统用户 `filedrop`：无登录 shell、不加入任何附加组，`/mnt/pool-main/drop` 只归它所有。即使容器被攻破并逃逸，拿到的也只是这个无权限用户。

**限制出站。** 应用只需要访问 Cloudflare 的 JWKS（HTTPS）。在 `DOCKER-USER` 链给该网络加规则：拒绝访问局域网与宿主机，只放行到公网 443，防止被攻破后当作跳板扫描内网。

### 7.1 凭据泄露时的影响范围

假设别人拿到了 Access 登录（例如在别人电脑上忘了退出，或邮箱被盗）：

| 对方能做 | 对方不能做 / 限制手段 |
| --- | --- |
| 下载当前未过期的文件 | 看不到已删除或已过期的文件；只保留 12 小时 |
| 上传文件 | 文件不会在 NAS 上执行，也不会在浏览器里打开；受容量上限限制，不能填满磁盘 |
| 删除中转站里的文件 | 碰不到中转目录以外的任何数据 |
| — | 用不了网页终端、Homeland、Stock：各自独立 Audience，独立子域名下 Cookie 也不共享 |
| 利用应用漏洞（如有）控制容器 | 容器只有中转目录；专用无权限用户；不能访问内网和其他容器 |
| 再利用内核漏洞逃出容器 | 这是最后一道防线，靠及时安装内核更新并重启 |

**残余风险**：对方可以上传一个伪装成你自己文件的恶意文件，诱导你在手机或电脑上打开。对策：列表显示每个文件的上传时间、来源 IP 和国家；新 IP 或境外上传时通过 Kuma 推送告警。

**降低泄露概率**：Access 登录方式优先用开启两步验证的 Google 账号，而不是邮箱验证码；会话时长设短；可加国家限制。

**发现异常时**：

1. `docker compose down` 立即停止服务（不影响其他应用）。
2. Cloudflare Zero Trust 后台撤销该用户的全部会话。
3. 查审计日志，确认对方下载 / 上传了什么，再决定是否恢复服务。

### 8. 审计日志

记录时间、验签后的邮箱、动作（上传 / 下载 / 删除 / 到期清理）、文件 ID、大小和结果。不记录 JWT、Cookie、文件内容。日志限制大小并轮转。

### 9. 在别人电脑上使用

- 建议用浏览器无痕窗口；页面提供“退出登录”按钮（Cloudflare Access 退出入口）。
- 该 Access 应用的会话时长建议设短一些（例如 2 小时），手机端可以单独多登录几次。

## 技术选型

与网页终端相同：Node 24 + `jose`，Node 自带 HTTP 模块，前端用原生 HTML/JS，不引入框架。

- 依赖少，审计面小；JWT 验证代码、Docker 构建与测试方式都可以照搬网页终端已经验证过的做法。
- 文件以流的方式写入和读出，不整块读入内存。下载支持 `Range`。
- 元数据用目录内一个 JSON 文件，原子写入。数据量很小，不需要数据库。

## 速度预估

- 电脑上传到 NAS：走家庭下行，通常很快。
- 手机从 NAS 下载：受家庭上行约 39 Mbit/s 限制，约 4–5 MB/s，1 GB 大约 4 分钟。

## 已决定（2026-11-06）

- 访问地址：页面 `drop.rokano.org`，文件数据 `dl.rokano.org:8443` 直连。
- 单文件 4 GiB、总容量 20 GiB、文件数 200；文件固定保留 12 小时。
- 登录用 Cloudflare Access 邮箱验证码（用户曾提出固定密码，比较后选择 Access + 直连，不做固定密码）。
- 第一版不做免登录取件码；以后需要时另行设计，因为它会开一个匿名入口。

## 实现

| 文件 | 内容 |
| --- | --- |
| `server/auth.mjs` | Access JWT 验签（与网页终端相同），额外返回验签后的邮箱 |
| `server/tokens.mjs` | 直连令牌：HMAC-SHA256，绑定动作 / 文件 ID / 过期时间；密钥每次启动随机生成，不落盘 |
| `server/store.mjs` | 存储：随机 ID 文件名、元数据 JSON 原子替换、配额、按偏移续传、SHA-256、到期与孤立文件清理 |
| `server/app.mjs` | 两个 HTTP 服务：3000 页面端（Access），3001 直连端（令牌） |
| `public/` | 原生 HTML/JS/CSS 页面，严格 CSP（无内联脚本） |
| `deploy/` | NPM 两个 Host 的 Advanced 模板与创建脚本、NPM http 级限速、出站防火墙脚本与 systemd 单元 |

接口（页面端，全部要求 Access JWT）：`GET /api/files`；`POST /api/uploads`（新建上传，返回令牌）；`POST /api/uploads/<id>/token`（续签并返回已收字节）；`POST /api/files/<id>/link`（5 分钟下载地址）；`DELETE /api/files/<id>`。直连端：`PUT /u/<id>?offset=N`（头 `X-Drop-Token`，每片 ≤ 32 MiB）、`GET/HEAD /d/<id>/<文件名>?t=令牌`（支持 Range）。

审计日志输出到容器 stdout（`docker logs nas_file_drop`）：上传开始 / 完成、生成下载链接、下载、删除、到期清理，以及直连端被拒请求（方法、去掉查询串的路径、原因、IP），不含 JWT 与令牌。

未实现：新 IP / 境外上传的 Kuma 告警（列表已显示上传来源 IP 与国家），上线后视需要补充。

## 构建与测试

```bash
cd /home/yao/code/nas/apps/file-drop
docker build --target test -t nas-file-drop:test .
docker run --rm --init --cap-drop ALL --security-opt no-new-privileges nas-file-drop:test
docker build --target production -t nas-file-drop:0.1.0 .
```

自动化测试覆盖：配置校验；缺少 / 伪造 / 过期 / 错误 aud、iss、邮箱的 JWT；未配置时 fail closed；写操作 Origin 与 CSRF；路径穿越与双向控制符文件名；分片偏移 409、超长分片、单文件 / 总量 / 并发上限；令牌篡改、过期、跨文件、上传令牌不能下载；CORS 预检来源；删除与 12 小时到期后拒绝下载、清理孤立文件。

浏览器验收（`test/browser-fixture.mjs` 仅在测试镜像中模拟 Access 身份，不能用于线上）：Playwright 1.63 上传 2.5 MiB（3 个分片，第二片模拟断流后自动续签续传）、列表与 SHA-256、下载内容与中文文件名一致、删除。11-06 通过；过程中发现并修复了文件列表被计数字段覆盖的问题。

```bash
docker network create drop-browser-test
docker run -d --rm --name drop-fixture --init --network drop-browser-test -v <空目录>:/shared nas-file-drop:test node test/browser-fixture.mjs
docker run --rm --init --network drop-browser-test -v "$PWD/test/browser.cjs:/test/browser.cjs:ro" -v <同一目录>:/shared:ro -v <截图目录>:/artifacts \
  mcr.microsoft.com/playwright:v1.63.0-noble sh -c 'cd /tmp && npm install --no-audit --no-fund playwright@1.63.0 && NODE_PATH=/tmp/node_modules node /test/browser.cjs'
docker stop drop-fixture && docker network rm drop-browser-test
```

## 部署步骤

### 需要 sudo（助手执行）

1. 专用用户与目录：`useradd --system --no-create-home --shell /usr/sbin/nologin filedrop`；`install -d -o filedrop -g filedrop -m 700 /mnt/pool-main/drop`。
2. 出站防火墙（只放行公网 TCP 443 与路由器 DNS 192.168.50.1:53；Docker 内置 DNS 在容器命名空间内向路由器转发，必须放行）：`deploy/egress-firewall.sh` 复制到 `/opt/nas/scripts/file-drop-egress-firewall.sh`，`deploy/nas-file-drop-egress.service` 安装到 `/etc/systemd/system/` 并启用；验证容器访问局域网 / 宿主机失败、访问公网 443 成功。
3. NPM http 级限速：把 `deploy/nginx-http.conf` 追加到 `/opt/nas/npm/data/nginx/custom/http.conf`（先备份）。

### 需要用户在 Cloudflare 控制台配置

1. DNS：`drop` CNAME `origin-home.rokano.org`，**已代理（橙云）**。
2. Rules → Origin Rules：主机名等于 `drop.rokano.org` 时，目标端口改写为 `8443`。
3. DNS：`dl` CNAME `jelly.rokano.org`，**仅 DNS（灰云）**。
4. Zero Trust → Access → Applications：新建 Self-hosted 应用，域名 `drop.rokano.org`（整个域名，不填路径），Allow 策略只放你的邮箱，登录方式 One-time PIN，会话时长 12 小时；不设 Bypass。把应用的 **Application Audience (AUD) Tag** 发给助手（它不是密钥，但只写入不入库的 `.env`）。

### 之后（助手执行）

1. `.env`（权限 600）：Team Domain 与允许邮箱沿用网页终端 `.env` 中的值，填入新 AUD、`DROP_UID/GID`。
2. `docker compose up -d`；确认未带 JWT 时页面端 403；直连端无效令牌 403、其他路径 404。
3. 把 NPM 加入 `nas-drop-front`（同步修改 `/opt/nas/npm/docker-compose.yml` 的 external network），`deploy/npm-hosts.mjs apply` 新增两个 Host（使用 `*.rokano.org` 证书，强制 HTTPS）。
4. 按 Access 手册验收：匿名访问被 Access 拦截；源站绕过（直连 8443 指定 drop 主机名，无 / 伪造 JWT）403；dl 无令牌 404；真实登录后在电脑上传、手机下载；日志无 JWT / 令牌。真实登录这一步需要你在手机和电脑上各操作一次。

## 回滚

1. `docker exec npm node /tmp/file-drop-npm-hosts.mjs remove` 删除两个 Host；从 NPM Compose 与网络中移除 `nas-drop-front`，删除 `http.conf` 中 `nas-file-drop` 标记块并重载。
2. `docker compose down`；`systemctl disable --now nas-file-drop-egress`；`docker network rm nas-drop-front`。
3. Cloudflare 删除 `drop`、`dl` 两条 DNS、Origin Rule 与 Access 应用。
4. `/mnt/pool-main/drop` 中只有临时文件，是否删除另行确认；删除用户 `filedrop`。
