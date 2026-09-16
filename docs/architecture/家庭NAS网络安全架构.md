# 家庭 NAS 网络安全架构

整理日期：2026-09-10。本文只维护网络决策、边界和验收要求；设备与已发生操作见 [运维记录](../operations/运维记录.md)，应用鉴权细节见 [Access 实施手册](../security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md)。本次整理未连接 NAS，目标架构不等于已部署。

## 现行决策

- Nginx Proxy Manager（NPM）统一承接公网 HTTPS 业务入口，家庭侧使用 `8443/TCP`。历史记录称运营商限制 80/443，变更前重新测试，不根据旧记录新增转发。
- Immich / Jellyfin 等媒体数据面按独立域名、DNS-only HTTPS `:8443` 进入 NPM，使用应用自身认证；轻量 Web 继续经 Cloudflare。
- Homeland 保留已记录的“灰云自选 Cloudflare IP → Cloudflare/Worker → NPM → 应用”路径。灰云不等于直连家庭；实际连接路径须核验。标准 Cloudflare 入口须重新验收后才能作为回退。
- 管理面默认仅 LAN；手机 App 主要在外网使用，可采用已验收的公网 key-only SSH 入口，或自建 WireGuard → SSH。用户已确认公网 SSH 域名与外部端口尚未配置，需新建入口并核对防火墙，不因已有 HTTPS 服务而视为可用。Tailscale 退出是目标，先核对 DNS、路由与客户端依赖，并验收替代路径后再停用。
- 每应用独立 front 网络，数据库与内部任务使用该应用的 back 网络。暂不增加第二层通用代理或集群。

## 目标拓扑

```text
轻量 Web → Cloudflare / Worker ─┐
媒体客户端 → DNS-only :8443 ────┤
                               ▼
                     家庭路由器 → NPM
                                  ├─ homeland-front → Homeland
                                  ├─ immich-front → Immich Server
                                  │                    └─ immich-back → DB / Redis
                                  └─ 各媒体应用的独立 front 网络

LAN / 可选 WireGuard → SSH、NPM:81、下载器、监控、SMB
```

NPM 宿主机映射的历史记录为 `8443:443`，不是统一把路由器转到 NAS 的 443。实施前逐层核对路由器外部端口、NAS 端口和容器监听。

## 入口与认证

| 类型 | 入口与约束 |
| --- | --- |
| Homeland / 实验 Web | 单 hostname，公开路径仅无副作用读取，`/manage` 及子树由 Access 保护，服务端逐请求验 JWT，写入再验 CSRF；带 basePath 时为 `/home/manage/*` |
| Immich | 独立域名根路径；保留上传所需头、长超时、关闭请求缓冲；不把 2283 明文端口直接转发公网 |
| Jellyfin | 独立域名，普通播放账号，Known Proxies 仅信任实际 NPM；验证 Range、WebSocket、播放与拖动，不直接开放 8096 |
| Navidrome / Kavita | 大量媒体传输需要公网时走 DNS-only HTTPS，否则仅 LAN；先验证原生客户端 / OPDS，避免额外认证层破坏兼容 |
| Seerr | 轻量控制面可经 Cloudflare，使用应用自身认证 |
| 数据库、下载器、监控、SMB、NPM:81 | 私有网络或 LAN，不通过公网反代发布管理面 |
| 既有 `orc` / `orchomev` VLESS | 独立维护协议、监控和密钥，不与 Web 的 Worker 或 HTTP 测速结论混用 |

媒体直连会公开家庭 IP，不能再以“源站 IP 隐藏”作为 Web 防护依据。公网开放前需确认应用认证、反代支持、更新回滚与备份恢复能力，并验收外网客户端功能。

## Cloudflare 源站边界

Access JWT 保护管理请求，不能保护没有 JWT 的公开 GET 不绕过 Cloudflare。若继续使用 Worker，保留原评审的优先方案：Worker 覆盖同名入站头并注入独立回源 Secret，NPM 对该 hostname 的全部业务路径验证，缺失或错误即拒绝。Secret 经受控配置注入，不进入应用、Git 或日志。

自有证书 mTLS / per-hostname AOP 是可选替代，实施前核实账户能力与 Worker 回源兼容性。Cloudflare 全局共享 AOP 证书不代表本账户专属身份。媒体 hostname 不套用 Cloudflare 专属限制；未知 SNI/Host 不落入任意应用。

优选 IP 保留上一可用配置与回退方法。切换必须验证 DNS、TLS、公开读取、Access 登录和匿名写拒绝；`CF-Ray` 仅作路径诊断。不同网络分别测成功率与延迟，不用 NAS 的单一出口结论代替手机蜂窝体验。旧 Worker PoC 和旧测速样本已移至 Git 历史，不作为生产配置。

## 容器与管理网络

- front 网络只包含 NPM 与对应应用入口；数据库、Redis 与内部任务不与 NPM 同网。应用端口默认不发布到宿主机。
- 分别限制应用到宿主机、LAN、其他应用与 `npm:81` 的访问，允许必要 DNS、HTTPS/JWKS 和明确业务依赖。不能用 DNS 名解析失败代替 IP/端口可达性测试。
- 独立 bridge 不自动隔离宿主机/LAN。先核实防火墙后端，区分转发、宿主机 INPUT 和同桥流量；不能把 `DOCKER-USER` 当成覆盖所有路径的规则。
- NPM 仍可访问其加入的全部 front 网络，必须维护版本、管理凭据与配置备份。LAN 绑定 `192.168.50.33:81:81` 不能阻止同网容器访问 `npm:81`；此剩余风险曾由用户接受，改进未完成。
- 容器不挂载无必要的 Docker socket，使用非 root 用户、必要资源限额和日志轮转；确需 Docker API 时收窄代理权限。
- 媒体 split-DNS 属待实施优化：在家解析 LAN IP，在外解析公网 IP，保留证书与应用认证。DNS 不改端口；外部使用 `:8443` 时内网也需能接收。核对 AAAA 和客户端 DoH 是否绕过家庭 DNS。

## 远程维护

手机接回当前运行会话的现场核对与分阶段步骤见 [手机接入现有 tmux 规划](../operations/手机接入现有tmux规划.md)，该规划不代表已部署远控。

保留 LAN 救援通道。WireGuard 优先在支持的路由器上部署，否则在 NAS 部署；UDP 可达性必须另验，不能从 TCP 8443 可用推断。每设备独立 VPN / SSH 密钥，服务端限制目的网段和端口，`AllowedIPs` 不替代防火墙。

接回终端使用启动任务的同一 Unix 用户、tmux socket 与容器环境，不放宽 socket 权限。密钥登录、主机指纹、断线重连、撤销客户端和任务不中断均需验收；先验证新通道再调整旧登录方式。

2026-09-16 用户明确手机 App 主要在外部网络使用，当前 NAS 即连接目标；公网域名/IP＋SSH TCP 端口直连纳入支持路径，VPN 不作为 App 强制前提。接入与仅允许密钥认证的操作步骤统一见 [外网连接手册](../operations/手机外网连接与SSH密钥配置.md)。本轮未修改路由器、生产 SSH 或防火墙，用户随后确认 SSH 公网入口尚未配置，待配置后进行蜂窝验证。局域网 APK 下载页独立绑定 LAN，不作为公网业务入口。

## 待验收事项

以下均未在本次整理中执行；操作证据与后续状态统一追加到运维记录。

| 优先级 | 缺口 | 完成证据 |
| --- | --- | --- |
| P0 | 旧 Uptime Kuma Push Token 曾进入 Git 历史 | 旧值失效、新值受控、Push 监控恢复；删文档不等于撤销凭据 |
| P0 | Worker 源站认证未闭环 | 直连家庭 IP 并伪造目标 SNI/Host 时，公开和管理请求均按预期拒绝 |
| P0 | 公网 IPv4/IPv6 边界待复扫 | 转发表、监听与外网结果一致；仅预期业务入口可达，UPnP/DMZ/OpenNAT 关闭 |
| P0 | 媒体直连尚未完整验收 | 核对 Jellyfin 8096/8920 发布及 DLNA UDP 需求，外网验证登录、上传、后台同步、播放、拖动与日志脱敏 |
| P0 | 恢复演练缺失 | 隔离环境恢复 Homeland SQLite、NPM 配置/证书、Immich 数据库与照片，并验证服务 |
| P1 | 共享网络与 NPM 管理口访问 | 每应用网络成员明确，IP/端口矩阵验证横向访问受限 |
| P1 | Access 剩余测试矩阵 | 保留真实 DELETE 成功证据，补齐创建/更新及过期、错误 issuer/audience/Origin 等拒绝用例 |
| P1 | Tailscale 退出与 DNS 依赖 | LAN/替代远程路径验收完成后再移除，容器解析与路由正常 |
| P2 | 更新、备份、证书与优选回退 | 分层监控有效、日志脱敏、版本可回滚、异地备份与故障回退经过验证 |

## 来源与整理说明

依据迁入文档中截至 2026-09-10 的评审与运维记录整合，本次未重新联网核实产品规则。移除与后续记录冲突的旧待办，历史原文见 [迁移与清理记录](../migration/README.md)。原评审保留的实施参考：

- [Docker bridge](https://docs.docker.com/engine/network/drivers/bridge/) / [Docker iptables](https://docs.docker.com/engine/network/firewall-iptables/)
- [Cloudflare AOP](https://developers.cloudflare.com/ssl/origin-configuration/authenticated-origin-pull/explanation/) / [Origin Rules](https://developers.cloudflare.com/rules/origin-rules/)
- [Immich 反代](https://docs.immich.app/administration/reverse-proxy/) / [Jellyfin 反代](https://jellyfin.org/docs/general/post-install/networking/reverse-proxy/)
- [WireGuard](https://www.wireguard.com/quickstart/) / [tmux](https://github.com/tmux/tmux/wiki/Getting-Started)
