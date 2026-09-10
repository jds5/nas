# 本地 Docker 与 NPM 部署记录

> **迁移说明（2026-09-10）**：来自 Homeland；原文中的“本轮”“已完成”和版本信息保留原记录日期，本次未复验现网。原文的应用仓库、根目录配置及前端路径指 Homeland。

> **本文档定位**：记录 Homeland Web 应用在本机 Docker 与 Nginx Proxy Manager（NPM）上的部署结构、验证结果和后续运维动作。
>
> **配套文档**：[项目说明](../../../homeland/README.md) · [装修总预算主文档](../../../homeland/docs/装修总预算主文档.md)
> · [家庭 NAS 网络安全架构](../architecture/家庭NAS网络安全架构.md)

> **状态提示（2026-08-30）**：本文主要保留历史部署和实测证据，不再定义现行网络架构。业主已确认临时关闭 Homeland 的 NPM 转发，但本轮尚未通过 NPM API/数据库复验；当前决策、临时 SSH 阅读方式和重新开放门槛统一以[家庭 NAS 网络安全架构](../architecture/家庭NAS网络安全架构.md)为准。

## 历史入口与结构（已临时停用 Homeland 转发）

- 历史公网入口：<https://rokano.org:8443/home>；2026-08-30 业主已确认临时关闭 Homeland NPM 转发，不应按本节假设其仍可访问
- Cloudflare DNS：`rokano.org` 已指向本机公网入口；Cloudflare 支持代理 HTTPS 端口 `8443`
- NPM：宿主机 `8443` 映射到 NPM 容器 `443`，管理端口 `81` 仅绑定内网地址 `192.168.50.33`
- 前端：`homeland_frontend:3000`，不向宿主机发布端口
- 容器网络：NPM 与前端通过外部网络 `nas-net` 通信；项目原有 `common_network` 保留
- 应用路径：Next.js `basePath=/home`，NPM 保留原始 URI 转发到前端

## NPM 配置（2026-08-23）

- 通过本机 NPM API 创建 `rokano.org` Proxy Host
- 上游：`http://homeland_frontend:3000`
- TLS：为根域 `rokano.org` 单独签发 Let's Encrypt ECDSA 证书
- 安全开关：Force SSL、Block Common Exploits、HTTP/2、HSTS 已启用；HSTS 子域继承未启用
- WebSocket Upgrade 已启用，以兼容 Next.js 运行时需求

说明：原有 `*.rokano.org` 通配符证书不覆盖根域 `rokano.org`，因此不能直接复用；根域证书使用现有 Cloudflare DNS Challenge 配置签发，证书续期由 NPM 管理。

### Worker专用回源Host（2026-08-23）

通过NPM官方API创建Proxy Host ID 7：`origin-home.rokano.org` → `http://homeland_frontend:3000`，复用证书ID 2的 `*.rokano.org` Let's Encrypt证书；Force SSL、Block Common Exploits、WebSocket、HTTP/2和HSTS与根域保持一致。创建后执行 `docker exec npm nginx -t`、重启NPM并再次验证语法。

实测结果：本机以正确SNI访问 `origin-home:8443/home` 返回200，证书SAN覆盖 `*.rokano.org`；公网Worker入口 `edge-home-test.rokano.org/home`、`/home/docs`、`/home/api/upcoming` 均返回200，响应含 `x-served-by: origin-home.rokano.org`。原 `rokano.org/home` 回归仍为200。NPM变更前数据库备份保留于 `/data/database.sqlite.backup-20260823-origin-home`。当前尚未配置Worker Secret header，公网直连 `origin-home:8443/home`仍返回200，这是待完成的源站防绕过项。

2026-08-31 写入故障复盘：Proxy Host 7 的 `/home` 自定义位置曾配置 `limit_except GET HEAD OPTIONS { deny all; }`，导致 Access 已登录用户的所有管理写请求在 NPM 层直接返回 403，应用收不到请求。已通过 NPM API 仅移除 `/home` 的该只读限制，继续透传 `Cf-Access-Jwt-Assertion`；`/stock` 与 `/stock/api/` 的既有只读限制未改。修改后执行 `nginx -t`、重启 NPM，并验证匿名 POST 已进入应用后由应用返回 403；随后真实 Access Writer DELETE 返回 200，数据库目标记录确实消失。以后不得在 Homeland 的 NPM location 无条件拒绝写方法，写入授权统一由应用的 Access JWT + CSRF 校验负责。

## DDNS 状态（2026-08-23 核查）

ddns-go 容器正在运行，Cloudflare Token 校验成功，当前检测到的公网 IPv4 为 `122.96.13.32`。其持久化配置维护以下 A 记录：

- `immich.rokano.org`
- `seerr.rokano.org`
- `music.rokano.org`
- `book.rokano.org`
- `rokano.org`

根域已于 2026-08-23 加入 ddns-go。日志在 `08:50:34` 明确出现 `Domain: rokano.org` 且 IP 未变化，证明配置已被运行中的服务加载；Cloudflare API 显示根域仍为 `122.96.13.32`、橙云开启。由于 IP 没有变化，Cloudflare 的记录修改时间没有刷新属于正常行为。

Cloudflare 当前记录状态：

| 记录 | Cloudflare 源记录值 | 代理状态 | ddns-go 管理 |
|---|---:|---|---|
| `rokano.org` | `122.96.13.32` | 橙云 | 是 |
| `immich.rokano.org` | `122.96.13.32` | DNS-only | 是 |
| `seerr.rokano.org` | `122.96.13.32` | 橙云 | 是 |
| `music.rokano.org` | `122.96.13.32` | 橙云 | 是 |
| `book.rokano.org` | `122.96.13.32` | 橙云 | 是 |

本次验证同时确认 `https://rokano.org:8443/home` 返回 HTTP `200`、TLS 校验通过。后续公网 IP 真正变化时，还应复核日志出现根域更新成功，并通过 Cloudflare API确认新记录值及 `proxied=true` 未被意外改变。不得把 `/opt/nas/ddns-go/.ddns_go_config.yaml` 中的 Token 写入仓库或日志。

## 部署与验证

```bash
docker compose build frontend
docker compose up -d frontend
docker compose ps

docker exec npm nginx -t
curl --resolve rokano.org:8443:127.0.0.1 \
  -o /dev/null -w '%{http_code}\n' \
  https://rokano.org:8443/home
curl -o /dev/null -w '%{http_code}\n' \
  https://rokano.org:8443/home
```

2026-08-23 实测结果：

- `homeland_frontend`：`healthy`
- NPM `nginx -t`：通过
- 源站 HTTPS（强制解析到 `127.0.0.1`）：HTTP `200`，TLS 校验通过
- Cloudflare 公网 HTTPS：HTTP `200`，TLS 校验通过
- `/home/docs`：HTTP `200`
- `/home/api/upcoming`：HTTP `200`
- 根路径 `/`：HTTP `404`，没有把应用映射到 `/home` 之外

## 运维注意

1. `data/` 是 SQLite 持久化目录，宿主机属主须匹配容器运行 UID/GID `1001:1001`。
2. `docs/` 以只读方式挂载；只改 Markdown 时无需重建容器。
3. 修改前端代码后重新执行 `docker compose build frontend && docker compose up -d frontend`，并同时检查 `docker compose ps` 和公网入口。
4. NPM 管理面继续只允许内网访问，不要将端口 `81` 发布到公网。
5. 根域证书与通配符证书是两张独立证书，续期失败时应分别检查 NPM 日志与 Cloudflare DNS API 权限。

## 多项目目标安全架构（2026-08-23 评审）

### 推荐结论（大陆公网场景）

本项目有可用公网 IP，主要访问者在中国大陆，并包含 Immich 等大文件与原生客户端服务。因此 Tunnel 不作为默认主链路；推荐公网直连、双层反代和每项目独立网络：

```text
浏览器 / 原生客户端（中国大陆）
  ↓ DNS-only / 可控解析
家庭公网 IP:8443（运营商阻断 80/443；使用 Cloudflare 兼容 HTTPS 端口）
  ↓ 路由器转发 + 主机防火墙
NPM（公网 TLS 终止；管理端口只在 LAN/VPN）
  ↓ ingress-net
内部网关 nginx（唯一后端路由器，默认 404）
  ├─ homeland-net → homeland_frontend:3000（Access + 应用鉴权）
  ├─ immich-net   → immich_server:2283（不加 Access，使用 Immich 自身鉴权）
  └─ project-net  → project:内部端口（按客户端兼容性选择认证）
                        ↓ 项目私有网络
                     项目数据库/缓存

Cloudflare/Tunnel：仅作兼容服务的可选入口、管理通道或备用链路
```

该方案的主要价值：

1. **保留公网链路性能和控制权**：固定使用可用的 `8443` HTTPS 入口，避免大陆用户的大文件上传、视频播放和客户端同步强制绕行 Cloudflare Tunnel；公网风险通过最小端口、防火墙、双层反代和应用鉴权收紧。
2. **认证策略按服务拆分**：`/home/*` 这类仅供业主浏览器使用的管理页面可选择额外认证；Immich 等带原生客户端和自身登录协议的服务不能额外套交互式认证，否则客户端无法完成认证或 API 调用。
3. **横向隔离**：每个项目使用独立的 user-defined bridge 网络。项目容器不加入 `nas-net`，不能直接访问 Immich、下载器、媒体服务或其他项目。
4. **内部网关集中治理**：统一执行路径白名单、限流、请求体上限、安全响应头、超时和访问日志；未知 Host/路径默认返回 404。
5. **应用自身认证始终是主边界**：有无 Cloudflare 都必须依靠应用自身成熟鉴权、速率限制和及时更新，不能只信任“请求来自反代/内网”。

Tunnel 的出站模型在隐藏源站方面更强，但不保证中国大陆到 Cloudflare 的实际链路质量，并让 HTTP 流量继续受 Cloudflare 代理限制。对本项目而言，这是可选安全/备用通道，不是无条件的最优主链路。

### 认证策略必须按 hostname 分类

不建议在同一个 hostname 上用复杂路径规则混合“有 Access”和“无 Access”服务；路径覆盖遗漏、Cookie Path 和客户端 API 地址都容易产生错误。优先使用独立子域：

| 服务类型 | 示例入口 | Cloudflare Access | 应用层要求 |
|---|---|---|---|
| 仅浏览器使用的私人管理页 | `home.rokano.org`，或当前 `rokano.org/home` | 推荐启用 | 仍需应用会话或验证 Access JWT，写请求做 CSRF 防护 |
| 有官方手机/桌面客户端的服务 | `immich.rokano.org` | **不启用** | 使用 Immich 自身登录、Token、设备会话和权限模型 |
| Webhook、回调、联合登录端点 | 独立 hostname 或精确公开路径 | 通常不启用交互式 Access | 校验签名、时间戳、防重放、来源限制 |
| 纯机器 API | 独立 hostname | 不使用浏览器交互式 Access；兼容时可用 Service Token | API Key/mTLS/OAuth，最小权限和轮换 |
| NPM、Dozzle、数据库管理等运维面 | 内网 hostname | 不对公网发布；远程需要时单独用 Access/WARP | 内网鉴权 + MFA，不与业务入口共用 |

Cloudflare Tunnel 只是安全传输和隐藏源站的入口，不要求必须同时启用 Access。Access 是逐应用、逐 hostname 的可选认证层，不能替代或破坏原生客户端协议。

### Tunnel 与橙云的区别

两者解决的问题不同，可以同时使用：

| 项目 | Cloudflare 橙云（Proxied DNS） | Cloudflare Tunnel |
|---|---|---|
| 解决的问题 | 访客先访问 Cloudflare，再由 Cloudflare 代理到源站 | Cloudflare 通过本机 `cloudflared` 主动建立的出站连接抵达源站 |
| 源站连接 | Cloudflare 连接本机公开 IP 和入站端口 | 本机主动连接 Cloudflare，无需公开 IP或入站端口 |
| 当前项目 | `rokano.org` 橙云 → 家庭公网 IP `:8443` → NPM | hostname → Cloudflare → Tunnel → 内部 nginx/服务 |
| DNS 表现 | 客户端解析到 Cloudflare Anycast IP | 公共 hostname 同样经过 Cloudflare，Tunnel 路由会关联相应 DNS 记录 |
| 是否包含 Access | 不包含 | 不包含；Access 是独立可选能力 |
| 免费可用性 | 基础代理可用于 Free 套餐 | 官方标注 Available on all plans，家庭自托管场景可先使用免费账户 |

Tunnel 的核心安全收益是可以关闭公网入站端口，使外部只能通过 Cloudflare 到达已登记的 hostname；这是以链路控制权和潜在性能代价换取的。本项目保留公网 IP 主链路时不采用这一取舍。`cloudflared` 默认向 Cloudflare 发起出站连接（端口 `7844`，QUIC/HTTP2）。

Tunnel 不会自动给 Immich 等服务增加登录页；是否启用 Access 仍按 hostname 单独决定。需要注意，Tunnel 的 HTTP 流量仍经过 Cloudflare 反向代理，因此上传大小、连接持续时间、缓存规则和产品条款等限制与直接橙云方案同样需要结合 Immich 实际大文件上传做端到端验证。

官方依据（访问日期：2026-08-23）：

- [Cloudflare Tunnel 概览](https://developers.cloudflare.com/tunnel/)：出站连接、无需入站端口，并标明适用于所有套餐。
- [Cloudflare DNS Proxy status](https://developers.cloudflare.com/dns/proxy-status/)：橙云决定 HTTP/HTTPS 流量是否经过 Cloudflare 代理。
- [Cloudflare Tunnel 防火墙要求](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/tunnel-with-firewall/)：`cloudflared` 使用出站 `7844/TCP` 或 `7844/UDP`。

官方依据（访问日期：2026-08-23）：

- [Cloudflare：保护源站](https://developers.cloudflare.com/fundamentals/security/protect-your-origin-server/)：Tunnel 使用出站连接，避免公开源站入口。
- [Cloudflare Access 自托管应用](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/choose-application-type/)：Access 在转发请求前检查身份和策略。
- [Docker user-defined bridge](https://docs.docker.com/engine/network/drivers/bridge/)：只有加入同一自定义网络的容器才能直接通信；同一网络内默认可访问彼此端口。
- [NGINX 官方镜像](https://hub.docker.com/_/nginx/)：支持只读文件系统和非 root 运行方式。

### 每个环节的故障与攻击点

| 环节 | 可能出问题 | 影响 | 必须控制/验证 |
|---|---|---|---|
| Cloudflare DNS | 记录误删、代理变灰云、域名失效 | 无法访问或暴露源站地址 | 变更审计；确认橙云；域名和账号启用 MFA |
| Cloudflare 账号/API Token | Token 泄漏、权限过大、账号被接管 | DNS、Tunnel 或 Access 策略被篡改 | Token 最小权限；Docker secret/只读文件注入；定期轮换；禁止写入 Git/日志 |
| WAF/Access | 对原生客户端服务误加 Access；路径覆盖遗漏；Allow 规则过宽 | 客户端完全不可用，或未授权用户绕过登录 | 按 hostname 分类；Immich 等不加 Access；只对兼容服务默认拒绝；同时测试浏览器和官方客户端 |
| Cloudflare Tunnel | `cloudflared` 停止、凭据泄漏、错误路由到管理面 | 服务中断或内部管理服务暴露 | 两个连接副本（可选）；健康检查；Tunnel Token 最小化；只登记内部网关一个 origin |
| 公网防火墙 | 多开端口、IPv6 入站遗漏、UPnP 自动映射、管理端口误暴露 | 扫描、爆破或绕过入口代理 | 只开放业务 HTTPS；关闭 UPnP；同步检查 IPv4/IPv6；确认 `81` 只在 LAN/VPN |
| 内部网关 nginx | 配置错误、路径穿透、错误信任转发头、单点故障 | 错路由、伪造客户端 IP、所有项目中断 | 默认 server 返回 404；精确 Host/路径匹配；只信任 NPM 来源；`nginx -t` + 每条路由 smoke test |
| nginx 容器 | root 权限、可写根文件系统、镜像漂移或长期不更新 | 容器逃逸影响扩大、引入已知漏洞 | 非 root；`read_only`；`cap_drop: ALL`；固定稳定分支/摘要；DIUN 只通知，验证后升级 |
| Docker ingress 网络 | 把应用或数据库直接加入 `ingress-net` | 任一入口组件可扫描全部后端 | `ingress-net` 只放 NPM 与 gateway；应用只在各自项目网络出现 |
| 项目网络 | 多个无关项目共用网络 | 单项目失陷后横向移动 | 每项目独立 bridge；不发布宿主机端口；数据库只加入项目私有后端网络 |
| Homeland 前端/API | 当前写接口无认证；输入校验或 CSRF 不足 | 账单、付款和 Excel 数据被读取、修改或删除 | 应用层验证 Access JWT/会话；写操作 CSRF 防护；输入 schema；权限校验；审计日志 |
| SQLite 数据 | 目录权限错误、并发写入、文件损坏、无备份 | 页面 500 或账单永久丢失 | UID/GID 检查；定时一致性备份；恢复演练；备份目录不挂载给前端 |
| 文档只读挂载 | Markdown 含隐私，渲染器出现 XSS/链接注入 | 装修信息泄漏或浏览器攻击 | Access 保护；保持 `:ro`；禁用原始 HTML或严格清洗；不要在 docs 写密钥 |
| 日志 | Access JWT、Cookie、查询参数或个人信息被记录 | 凭据和隐私二次泄漏 | 不记录 Authorization/Cookie；日志轮转；限制读取权限；设定保留期 |
| 更新流程 | `latest` 自动更新导致不兼容；固定旧摘要不更新导致 CVE 滞留 | 中断或已知漏洞长期存在 | 固定当前版本；监控公告；先测试再更新；保留旧镜像；健康检查与回滚 |
| 备份/恢复 | 只有备份没有恢复验证，备份与主机同盘 | 故障时才发现不可恢复 | 3-2-1 思路；至少一份离机/离线；定期实际恢复 SQLite 与配置 |
| 可观测性 | 只看容器 Up，不检查真实页面 | 故障长期未发现 | `docker compose ps` + `/home` + 关键读写 API smoke test；证书/Tunnel/5xx 告警 |

### 当前架构已确认的问题

1. `nas-net` 当前同时连接 NPM、Homeland、Immich、下载器、媒体服务等多个容器；Homeland 被攻破后存在横向访问面。
2. Homeland 的账单、付款和 Excel 路由包含 `POST`、`PUT`、`DELETE`，代码中未发现认证 middleware 或 session 校验。
3. 宿主机 `0.0.0.0:8443` 当前公开监听；这是公网直连主链路的必要暴露面，必须确认没有额外端口、IPv6 旁路、UPnP 映射或管理端口泄漏。
4. NPM 使用 `latest`，Homeland 镜像也标记为 `latest`；两者都需要“固定部署版本 + 通知后验证升级”的流程。

### 迁移顺序

1. 先为 Homeland 增加应用层认证和写接口保护，并完成增删改查回归测试。
2. 新建内部网关 nginx、`ingress-net` 和 `homeland-net`；让 Homeland 退出 `nas-net`/`common_network`。
3. 让 nginx 同时加入 `ingress-net` 与 `homeland-net`，除此之外不加入无关网络；验证 `/home` 全路由。
4. 路由器只保留公网 `8443/TCP` 转发到 NPM；检查 IPv4、IPv6、UPnP 和 NPM 管理端口，确保不存在旁路入口。
5. 分别执行浏览器、官方手机/桌面客户端登录与同步、大文件上传、视频播放和 API smoke test。
6. 运维面默认仅通过 LAN 访问；本项目不再依赖 Tailscale。确需远程运维时，在 AX86U/NAS 自建 WireGuard，只放行管理网段，不把管理页面直接暴露公网。
7. 后续项目按“一项目一网络、一条显式路由、一套鉴权策略”接入，不允许为了方便重新共享 `nas-net`。

### 运营商端口限制

- 当前公网 `80` 和 `443` 被运营商阻断，正式入口固定为 `https://<hostname>:8443/`。
- 宿主机映射保持 `0.0.0.0:8443 → npm:443`，容器内部仍使用标准 TLS 端口，不需要让后端项目感知 `8443`。
- 不能依赖公网 HTTP `80` 做跳转或 ACME HTTP-01 验证；证书必须继续使用 DNS-01 Challenge。
- 所有客户端、分享链接、回调 URL 和健康检查必须显式包含 `:8443`；新增服务前必须确认其官方客户端支持自定义端口。
- 非标准端口不会显著提高或降低 TLS 本身的安全性，也不能代替防火墙、认证、限流和及时更新。

### Cloudflare 将访客 443 回源到 8443

如果 hostname 使用 Cloudflare 橙云，可以让访客使用标准地址 `https://<hostname>/`，再由 Cloudflare **Origin Rule** 把回源目标端口改成 `8443`：

```text
访客 https://rokano.org:443
  → Cloudflare Edge :443
  → Origin Rule: destination port = 8443
  → 家庭公网 IP:8443
  → NPM:443
```

这不要求家庭宽带开放 `443`，因为访问者连接的是 Cloudflare 的 `443`，Cloudflare 回源时连接家庭公网 `8443`。Origin Rules 的目标端口覆盖功能在 Free 套餐可用，免费区当前支持最多 10 条规则。

不建议为了单纯改端口使用 Cloudflare Workers：

- Workers 会为每个请求增加一层脚本执行和一个新的故障点；Origin Rule 是原生路由配置。
- Workers Free 当前为 100,000 请求/日，超过后可能失败或按配置绕过。
- Free/Pro 的单次请求体上限为 100 MB；这对 Immich 原始照片、视频上传是明确风险。该上限源于 Cloudflare 账户套餐，即使 Worker 流式转发也不能绕过。
- Worker 必须正确透传请求体、Range、WebSocket、Host、缓存和客户端 IP；任一遗漏都可能破坏原生客户端。

安全边界：

1. Origin Rule/Worker 都不会自动阻止攻击者直接访问 `公网IP:8443`。
2. 若业务决定所有流量必须经 Cloudflare，可在家庭防火墙只允许 Cloudflare 官方 IP 段访问 `8443`，或配置 Authenticated Origin Pulls；但这会放弃真正的公网 IP 直连备用路径。
3. 若需要保留大陆直连/自定义解析路径，就不能只允许 Cloudflare IP；此时 `8443` 必须按公开互联网入口加固，依赖 NPM、内部网关和应用鉴权。
4. Cloudflare SSL/TLS 模式应使用 Full (strict)，NPM 的证书必须覆盖请求 hostname；不能使用 Flexible。

因此有两种互斥取舍：

| 模式 | 用户入口 | 优点 | 代价 |
|---|---|---|---|
| Cloudflare 标准端口模式 | `https://rokano.org/`，Origin Rule 回源 `8443` | URL 无端口、浏览器兼容最好、可用 Cloudflare 防护 | 所有用户仍经过 Cloudflare，大陆性能问题不变 |
| 公网直连模式 | `https://rokano.org:8443/` | 链路和解析可控，可绕开 Cloudflare | URL 必须带端口，源站公开暴露，需要自行防护 |

官方依据（访问日期：2026-08-23）：

- [Cloudflare Origin Rules](https://developers.cloudflare.com/rules/origin-rules/)：Free 套餐支持目标端口覆盖。
- [Origin Rules FAQ](https://developers.cloudflare.com/rules/origin-rules/faq/)：规则可在回源阶段设置 destination port。
- [Cloudflare Workers Limits](https://developers.cloudflare.com/workers/platform/limits/)：免费请求额度、CPU、请求体与连接限制。
- [Workers 自定义回源端口](https://developers.cloudflare.com/workers/configuration/compatibility-flags/#allow-specifying-a-custom-port-when-making-a-subrequest-with-the-fetch-api)：Workers 可显式 fetch 到受支持的 `8443`，但不是本场景的首选。

### 仅禁止根域绕过 Cloudflare 直连 8443

目标：`https://rokano.org/` 经 Cloudflare 443 → Origin Rule → 源站 8443 正常；`https://rokano.org:8443/` 直接访问源站失败；Immich 等其他 hostname 的 8443 直连保持不变。

路由器或主机防火墙不能按域名完成这个目标，因为多个 hostname 共用同一个公网 IP 和 `8443/TCP`。只给整个端口配置 Cloudflare IP 白名单会同时切断其他应用的直连。

推荐使用 Cloudflare **Per-hostname Authenticated Origin Pulls（AOP/mTLS）**：

1. 建立一套私有 CA，离线保管 CA 私钥；为 `rokano.org` 签发 AOP 客户端叶证书。
2. 在 Cloudflare `SSL/TLS → Origin Server → Authenticated Origin Pulls → Per-hostname` 上传叶证书和叶私钥，只关联 `rokano.org`。
3. NPM 持久化目录只保存 CA 公钥证书，例如 `/data/custom_ssl/rokano-aop-rootca.crt`；不要把 CA 私钥或叶私钥放入 NPM、仓库或普通日志。
4. 先在 `rokano.org` Proxy Host 的 Advanced Configuration 中使用观察模式：

   ```nginx
   ssl_client_certificate /data/custom_ssl/rokano-aop-rootca.crt;
   ssl_verify_client optional;
   ```

5. 执行 `docker exec npm nginx -t`，通过 Cloudflare 标准 443 入口验证页面和 API；确认 Cloudflare hostname AOP 状态为 active。
6. 将该虚拟主机切换为强制验证：

   ```nginx
   ssl_client_certificate /data/custom_ssl/rokano-aop-rootca.crt;
   ssl_verify_client on;
   ```

7. 再次执行 `nginx -t` 和双向测试：Cloudflare 标准入口应为 200；使用 `--resolve rokano.org:8443:<公网IP>` 的直接请求应在 TLS/HTTP 层失败；Immich 等其他 hostname 的 `:8443` 仍应正常。
8. 配置 AOP 证书到期告警，并在到期前先上传和部署新证书再移除旧证书。

不推荐用 `CF-Ray`、`CF-Connecting-IP` 等 Header 是否存在来判断，因为直连客户端可以自行伪造这些 Header。NPM 当前还会根据 CDN 地址恢复真实客户端 IP，因此直接套 NPM Access List 的 Cloudflare CIDR 白名单也可能匹配恢复后的访客 IP而误封正常流量。mTLS 在 TLS 握手阶段验证证书，不能靠普通 HTTP Header 伪造。

Cloudflare 官方说明 AOP 在 Free、Pro、Business、Enterprise 均可用；Per-hostname 模式必须上传自有客户端证书。它只影响关联的 hostname，不会改变同区域内其他域名的直连策略。

官方依据（访问日期：2026-08-23）：

- [Authenticated Origin Pulls](https://developers.cloudflare.com/ssl/origin-configuration/authenticated-origin-pull/)：可用套餐、全局/Zone/Per-hostname 三种独立级别。
- [Per-hostname AOP 配置](https://developers.cloudflare.com/ssl/origin-configuration/authenticated-origin-pull/set-up/per-hostname/)：自有 CA、上传叶证书、origin nginx 验证与强制步骤。

## 修订日志

| 日期 | 版本 | 变更摘要 |
|---|---|---|
| 2026-08-31 | v2.2 | 记录通过 NPM API 移除 `origin-home /home` 的旧只读 `limit_except`，完成匿名写入仍 403及真实 Access Writer DELETE 200、数据库落盘验证 |
| 2026-08-27 | v2.1 | 按已有公网IP改为媒体服务经NPM直连、轻量Web经Cloudflare；目标架构移除Tailscale，远程管理仅保留可选自建WireGuard |
| 2026-08-23 | v2.0 | 通过NPM官方API创建Worker专用回源Host，复用通配符证书并完成NPM重启、TLS、Worker页面/API及原根域回归验证 |
| 2026-08-23 | v1.9 | 增加根域 Per-hostname AOP/mTLS 方案，只禁止根域绕过 Cloudflare直连而不影响其他服务 |
| 2026-08-23 | v1.8 | 复核根域已加入 ddns-go，日志、Cloudflare 记录、橙云状态和公网端点均正常 |
| 2026-08-23 | v1.7 | 核查 ddns-go 与 Cloudflare 记录，确认根域尚未纳入动态更新 |
| 2026-08-23 | v1.6 | 增加 Cloudflare Origin Rule 443→8443 方案，并说明 Workers 对 Immich 大文件场景的限制 |
| 2026-08-23 | v1.5 | 记录运营商阻断 80/443，确认 8443 为正式公网 HTTPS 入口并保留 DNS-01 证书方案 |
| 2026-08-23 | v1.4 | 结合大陆访问与公网 IP，将推荐主链路修订为公网直连双层反代，Tunnel 降为可选备用 |
| 2026-08-23 | v1.3 | 补充 Tunnel、橙云、Access 的职责差异、免费可用性及客户端服务限制 |
| 2026-08-23 | v1.2 | 修正全局 Access 方案：按 hostname 区分浏览器服务、原生客户端和机器 API 的认证策略 |
| 2026-08-23 | v1.1 | 增加多项目目标安全架构、逐环节风险矩阵和迁移顺序 |
| 2026-08-23 | v1.0 | 记录本机 Docker 部署、NPM API 配置、根域证书和端到端验证结果 |
