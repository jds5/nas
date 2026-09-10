# NAS 网络架构设计

> **迁移说明（2026-09-10）**：来自 Homeland；原文中的“本轮”“已完成”和版本信息保留原记录日期，本次未复验现网。本文仅供历史追溯，实施以[现行网络安全架构](../../architecture/家庭NAS网络安全架构.md)及现场核验为准。

> 编制日期：2026-05-11；最近修订：2026-08-22
> 关联文档：[NAS 搭建进度](NAS搭建进度.md)
> 主题：在家庭宽带端口受限的现实约束下，给所有应用规划一条最优访问路径，并把 Tailscale 与 Cloudflare Tunnel 的关键设置一项一项谈清楚

---

## 一、硬约束（既定事实）

| 约束 | 影响 |
|------|------|
| **已获得公网 IPv4；8443 已可提供服务**（2026-08-22 业主确认） | 可验证并选择性使用端口转发；这不等于应该把所有服务暴露公网 |
| 无法配置光猫 | 光猫桥接走不通，**双 NAT 是既定事实** |
| **80/443 已确认被封**（2026-08-22 业主确认） | 不依赖入站 80/443；直连 Web 只能走已验证的 8443 或另测端口；证书续期宜用 DNS-01 |
| Cloudflare 开代理后访问速度明显下降（2026-08-22 业主观察） | 已观察到体验问题；尚无同网络、同文件的对照测速，不能据此归因到单一链路 |
| Cloudflare Tunnel 禁视频流 | Jellyfin 远程**不能**走 CF Tunnel |
| AX86U（千兆 LAN，Asuswrt-Merlin 可刷） | 内网够用；可装 Tailscale subnet router |

**结论**：不存在"一条路打天下"的方案。必须**分层**：公网入口 + mesh VPN 各做各的，按应用类型分流。

### 1.1 2026-08-22 推荐结论（先读这节）

1. **自己远程运维、SSH、Codex 相关控制面：Tailscale SSH 为主，不开放公网 SSH。** 公网 IPv4 的价值用于让 Tailscale 的 WireGuard 尽可能直连，而不是把 OpenSSH 放到扫描器面前。
2. **面向家人/外人的 Web：保留 Cloudflare Tunnel + Access。** 这是安全和易用性优先的路径；非视频、低吞吐业务可接受其性能代价。
3. **大文件、视频、管理后台：走 Tailscale 直连。** 若 `tailscale status`/`tailscale ping` 显示 `direct`，它通常是三者中延迟和吞吐最好的路径；若显示 `relay`，再排查 UDP 直连。
4. **只为速度而灰云直连的 Web：按服务逐个决定。** 灰云会暴露源站 IP，失去 Cloudflare WAF/Access；仅适合已具备强应用认证、低暴露价值且确有测速收益的服务。不要把 NAS 管理面、Docker、数据库或 SSH 放进这一类。

Cloudflare 官方确认 HTTPS `8443` 可被代理，但默认禁用缓存；普通代理不能承载 SSH，除非使用 Enterprise Spectrum 或改为灰云直连。因此「橙云 + `:8443`」对动态 NAS 应用增加转发路径，却没有默认缓存收益。[Cloudflare network ports（2026-04-20）](https://developers.cloudflare.com/fundamentals/reference/network-ports/)

> **重要推论，不是已验证事实**：Cloudflare 说明只有 80/443 可在其 China Network 数据中心处理 HTTP/HTTPS；若当前域名/流量路径不具备该能力，8443 的流量可能无法利用中国节点，这与已观察到的降速相符，但必须以第 1.2 节的实测结果判断，不能直接当作根因。

### 1.2 先测再调：最小对照表

同一台外网笔记本、同一时段、同一个 100MB 以上静态测试文件，分别记录 DNS、首字节时间、总耗时和下载速率；每项至少 3 次，记录中位数。不要拿不同网络、不同时段的单次体感做结论。

| 路径 | 目的 | 判断 |
|------|------|------|
| 橙云 `https://域名:8443/测试文件` | 当前安全公网基线 | 记录实际体验，不假定 CDN 一定更快 |
| 灰云/公网 IP 的同一 Web 服务 | 判断 CF 额外路径的净损失 | 仅短时测试；测试后按决策恢复 DNS 状态 |
| Tailscale 到 NAS | 判断私有运维实际能力 | 先执行 `tailscale ping ynas` 与 `tailscale status`；应看到 `direct` 而非 `relay` |
| Tailscale 临时 `iperf3` | 排除 Web 应用本身瓶颈 | 只在 tailnet 内临时启动，测试完停止 |

建议记录命令（按实际域名/主机名替换）：

```bash
# 客户端：先确认 Tailscale 是否真正直连
tailscale ping ynas
tailscale status

# Web 首字节/下载速度（不输出文件）
curl -L -o /dev/null -s -w 'dns=%{time_namelookup} connect=%{time_connect} ttfb=%{time_starttransfer} total=%{time_total} speed=%{speed_download}\n' 'https://<host>:8443/<test-file>'

# 吞吐对照；服务端临时执行 iperf3 -s，客户端完成后立即 Ctrl+C 结束服务端
iperf3 -c ynas -P 4 -R
```

### 1.3 为 Tailscale 直连而开一个 UDP 口（推荐）

在 AX86U 为 NAS 固定 DHCP 地址后，**仅**转发 `UDP 41641` 到 NAS，并确保 NAS 防火墙允许该 UDP 入站；不要转发 TCP 22。Tailscale 官方说明这会提高家庭网络下的直连成功率；直连失败才走 DERP 中继，后者通常吞吐较低。该 UDP 端口服务的是经 WireGuard 认证的 Tailscale 数据面，不会把 OpenSSH 服务暴露给公网。[Tailscale connection types（2026-06-01）](https://tailscale.com/docs/reference/connection-types)

执行前后均从外网运行 `tailscale ping ynas` 验证。若仍为 `relay`，保留现状，不应擅自开放更多端口；先检查光猫/路由器双 NAT 是否确实把 UDP 41641 送达 NAS。

---

## 二、推荐架构（三层分流，三条腿走路）

```
                        ┌── 老婆微信 / 远程网页 ──┐
                        ↓                       ↓
   公网 ─── Cloudflare ───┐                  Tailscale Funnel*
              │           │                  （备份方案）
              ↓           ↓
        Cloudflare Tunnel（cloudflared 容器在 NAS 内）
              │
              ↓
   ezBookkeeping / Immich Web / Gitea Web（Tier 1：公网 HTTPS）
   ────────────────────────────────────────────────────────────
                       ↑
                       │ Tailscale mesh（mesh VPN，端到端加密）
   你手机/老婆手机/PC ──┘
                       ↓
              ★ NAS 同时跑 Tailscale subnet router
                       ↓
   Jellyfin / Beszel / qB Web / Sonarr/Radarr/Prowlarr/Bazarr / Komga
   （Tier 2：仅 Tailscale 远程；内网直连无关）
   ────────────────────────────────────────────────────────────
                       ↑
                       │ 仅 LAN
                       ↓
   Samba / SSH / NAS 系统管理 (Tier 3：永远不出内网)
```

*Tailscale Funnel：免域名公网入口，作为 CF Tunnel 备份/快速验证用。

---

## 三、应用分层（最终决策表）

| 应用 | 层 | 走哪条 | 公网访问者 | 是否需要 LE 证书 | 是否过 Cloudflare |
|------|----|--------|-----------|-----------------|------------------|
| **ezBookkeeping** | 1 | CF Tunnel | 老婆微信、家人 | ✅ 自动 | ✅ |
| **Immich Web** | 1 | CF Tunnel | 朋友看相册分享 | ✅ | ✅ |
| **Gitea/Forgejo Web** | 1 | CF Tunnel | 公开仓库分享 | ✅ | ✅ |
| **Gitea/Forgejo SSH** | 2 | Tailscale | 仅你 push | — | ❌ |
| **Jellyfin** | 2 | Tailscale | 仅你/老婆远程 | 走 ts.net 自动 | ❌（CF 禁视频） |
| **Beszel / qB / Sonarr / Radarr / Prowlarr / Bazarr / Komga** | 2 | Tailscale | 仅你 | 走 ts.net 自动 | ❌ |
| **Navidrome**（将来装） | 2 | Tailscale | 你/老婆 Subsonic 客户端 | ts.net | ❌ |
| **HA Web**（家装尾声） | 2 | Tailscale | 仅你/老婆 | ts.net | ❌ |
| **SSH（运维 / Codex 控制面）** | 2 | **Tailscale SSH** | 仅你 | — | ❌ |
| Samba / NAS BIOS/IPMI | 3 | LAN（必要时经 Tailscale 子网路由） | 自己 | — | ❌ |
| **Cloudflared 容器** | 0 | 出站 443 to CF | 无入站 | — | ✅ |

**核心判断**：能走 Tailscale 就走 Tailscale；只有"外人/微信 webview/手机不装 TS 也要看"的服务才上 CF Tunnel。

---

## 四、Tailscale 关键设置（一项一项谈）

### 4.1 MagicDNS + HTTPS 证书 🔴 必开

| 项 | 推荐 | 理由 |
|---|------|------|
| MagicDNS | **开** | 内网用 `ynas.tailNNNN.ts.net` 替代 IP，IP 漂移也无感 |
| HTTPS 证书 | **开** | Tailscale 自动给你的 `*.ts.net` 域名签 Let's Encrypt 证书，**内网都能拿到公网信任证书**。这点解决了 95% 的"自签 → 浏览器/微信白屏"问题 |
| Tailnet 名 | 改成可识别的（如 `yao-home`） | 默认是随机词，长得丑 |

**操作**：登录 https://login.tailscale.com → DNS → 开 MagicDNS + HTTPS Certificates。

### 4.2 Subnet Router（NAS 当家庭网关）🟡 强烈建议

让 NAS 把整个 `192.168.50.0/24` 子网 advertise 给 Tailscale。**这样在外面的设备开 Tailscale，能访问家里所有 IP**（路由器、打印机、未来的 HA、IoT 设备等），不只是 NAS。

```bash
sudo tailscale up --advertise-routes=192.168.50.0/24
```

然后管理后台 → Machines → ynas → Edit route settings → 勾上 `192.168.50.0/24` 启用。

**注意**：开了 subnet router 后，**Tailscale 内部 DNS 还是优先 MagicDNS**，所以局域网设备如果你想用名字访问（如 `router.lan`），最好在路由器或 NAS 上跑个本地 DNS（Adguard Home 是顺手的选择）。

### 4.3 ACL（控制谁能访问什么）🟡 建议加

默认 Tailscale ACL 是"全网互通"，单人用没问题；但你以后会加老婆手机、丈母娘看相册的设备，要做隔离。

推荐 ACL 雏形（管理后台 → Access controls）：

```hujson
{
  "tagOwners": {
    "tag:server": ["yourmail@gmail.com"],
    "tag:family": ["yourmail@gmail.com"],
  },
  "acls": [
    // 你自己（owner）全开
    {"action": "accept", "src": ["yourmail@gmail.com"], "dst": ["*:*"]},
    // 家人设备只能访问 NAS 上的特定端口
    {"action": "accept", "src": ["tag:family"], "dst": [
        "tag:server:8096",  // Jellyfin
        "tag:server:2283",  // Immich
    ]},
  ],
  "ssh": [
    // Tailscale SSH：你的设备能 SSH 到 server 标签设备
    {"action": "accept", "src": ["yourmail@gmail.com"], "dst": ["tag:server"], "users": ["yao"]},
  ],
}
```

NAS 加 `--advertise-tags=tag:server` 上线后归到 server 组。

### 4.4 Tailscale SSH 🟢 推荐启用

```bash
sudo tailscale up --ssh
```

启用后，`ssh yao@ynas` 走 Tailscale 网络，认证和授权由 Tailscale 身份与 ACL 接管；原有 `sshd` 的 key 免密、禁密码、禁 root 可保留为**仅局域网救援通道**。Tailscale SSH 只接管 tailnet 方向的 NAS Tailscale IP `:22`，不会修改 `sshd_config` 或 `authorized_keys`。

对这个 NAS，不要照搬本文件旧 ACL 的宽泛示例。实际策略应只允许你的 Tailscale 登录身份进入 `tag:server` 上的既有 Unix 用户 `yao`，并使用 `check`（重新登录确认）而不是 `accept`。不允许 root；对日常终端建议 `checkPeriod: "12h"`，涉及 sudo 的高风险操作再设为 `1h` 或 `always`。Tailscale 官方也提醒：Tailscale SSH 不适合不信任其所运行代码的多用户机器；本机是单人 NAS、且将访问来源限制为你自己的受管设备时适配度较高。[Tailscale SSH（2026-01-05）](https://tailscale.com/docs/features/tailscale-ssh)

推荐策略骨架（邮箱、标签和用户名必须改为实际值后，先在策略编辑器校验）：

```hujson
{
  "ssh": [
    {
      "action": "check",
      "checkPeriod": "12h",
      "src": ["你的Tailscale登录邮箱"],
      "dst": ["tag:server"],
      "users": ["yao"]
    }
  ]
}
```

启用动作是 `sudo tailscale set --ssh`。执行前必须保留一个已登录的 LAN SSH 会话；执行后用外网设备的 `ssh yao@ynas` 验证登录、`sudo -v` 验证权限，再决定是否保留传统 LAN SSH。不要把 NAS 分配给家人/访客的 Tailscale 账号后仍使用默认“全员可达”策略。

### 4.5 Exit Node 🟢 出差/咖啡馆 WiFi 时有用

把 NAS advertise 成 exit node：

```bash
sudo tailscale up --advertise-exit-node
```

外面用公共 WiFi 时手机 / 笔记本切到 ynas 作 exit → 所有流量加密走家网络。**典型场景：出差用酒店 WiFi 时不放心 → 切 exit node 走家**。

**注意**：开了 exit node 后家里上行带宽变成你出门的天花板（联通家宽上行一般 30–100Mbps），看高清视频会卡。日常不要常开。

### 4.6 Funnel（免域名公网入口）🟢 备份手段

Funnel 是 Tailscale 给你的 `*.ts.net` 加一个**公网入口**，外部任何人（不需要装 TS）都能访问。但**有限制**：

- 仅 443/8443/10000 端口
- 流量从 Tailscale 公网入口走，**视频流不受 CF Tunnel 那种 ToS 限制**（但 Tailscale 自己也有 1GB/天的 free tier 软限制）
- 域名是 `ynas.tailNNNN.ts.net`，不好记

**适用场景**：

- 临时给某人发个相册分享链接，不想搞 CF Tunnel
- CF Tunnel 故障时的备份入口

```bash
sudo tailscale funnel 443 on  # 将本地 443 暴露公网
```

**不推荐用 Funnel 跑 ezBookkeeping**——CF Tunnel 加 Cloudflare Access 的 SSO 防护更完善；Funnel 是裸暴露，靠应用本身认证扛压。

### 4.7 Auth Keys + 设备生命周期 🟡

| 设置 | 推荐 |
|------|------|
| 设备密钥过期 | **关闭**（管理后台 → Machines → 每台 → Disable key expiry）；不然每 180 天 NAS 自己掉线，半夜出门没法用 |
| Auth key | 加新设备时用预生成 key，**24h 过期 + 一次性** |
| Tagged 设备 | NAS 用 tag:server，家人手机用 tag:family，方便 ACL |

### 4.8 直连 vs DERP（性能）🟢 自动，但要监控

Tailscale 默认尝试 NAT 穿透直连，穿透失败才走 DERP 中继（Tailscale 官方服务器）。CGNAT + 双 NAT 下你能不能直连**不一定**，要看运气和 STUN 表现。

测试：

```bash
tailscale status         # 看连接质量
tailscale ping ynas      # via direct 还是 via DERP
```

- **direct**：好
- **via DERP iad**（或其他城市）：穿透失败，走美国/日本中继 → **延迟 + 慢**

CGNAT 下走 DERP 概率不低。如果发现 NAS 经常 DERP，方案：
1. 部署自己的 **derper**（自建 DERP 中继）在国内云服务器上 → 延迟降到 30ms
2. 或买个低价美西/日本 VPS 跑 derper（10–20 元/月 VPS 就够）

这个是后期优化，先看实测。

---

## 五、Cloudflare Tunnel 关键设置

### 5.1 部署形态

| 形态 | 推荐？ | 说明 |
|------|-------|------|
| Docker 容器 `cloudflared`（接入 `nas-net`） | ✅ | 与其他容器一致管理，service token 在 .env |
| 系统服务（apt 装） | ❌ | 配置散落，不利迁移 |
| 路由器装 cloudflared | ❌ | AX86U Merlin 跑 cloudflared 资源紧、维护麻烦 |

### 5.2 子域规划

域名买好（建议 `.top` / `.xyz` / `.icu` ¥10/年，足够用；DNS 托管到 Cloudflare 免费）。子域规划：

| 子域 | 服务 | 是否走 Access |
|------|------|--------------|
| `money.yourdom.top` | ezBookkeeping | 否（应用自带账号） |
| `photo.yourdom.top` | Immich Web | 应用自带账号 |
| `git.yourdom.top` | Gitea/Forgejo Web | 应用自带账号 |
| `share.yourdom.top` | 给朋友看的临时分享 | **Access 一次性链接** |

### 5.3 Cloudflare Access（应用前 SSO）🟡 强烈建议

**为什么**：Access 在 CF 边缘给你的应用挂一层 SSO 网关——访客必须用 Google/邮箱 OTP 验证才能进到应用本身。哪怕 ezBookkeeping 自己出 0day，攻击者也进不来登录页。

**典型策略**：

| 应用 | Access 策略 |
|------|-----------|
| ezBookkeeping | 老婆 Google 邮箱 + 你的 Google 邮箱 + 邮箱 OTP（验证码登录） |
| Immich | 同上 |
| 给朋友的相册分享链接 | bypass Access（让 Immich 自己的 share-link 直接公网，因为 Immich share link 已有强 token） |

设置入口：CF Zero Trust → Access → Applications → Add an application。

**注意**：微信内置浏览器对 Google SSO 弹窗不友好（很多 Google 登录因检测到 webview 直接拒绝）。所以 **ezBookkeeping 给老婆用，建议 Access 选"邮箱 OTP"而不是 Google SSO**。

### 5.4 不放 Jellyfin 的真实理由 + 替代

CF 服务条款 2.8：禁止"a disproportionate percentage of the bandwidth via the Service is video or audio streaming"。Jellyfin 高码率 4K 会**触发 CF 风控**，可能整个隧道被封一段时间。

**替代**：Jellyfin 只走 Tailscale。如果想给老婆远程看，让她手机装 Tailscale 客户端，开常驻；用 Jellyfin 客户端连 `ynas.tailNNNN.ts.net:8096`。**实测体验：iOS 后台 Tailscale 偶尔休眠，需要解锁手机后重新唤醒**，是体验小坑但能用。

如果将来真要给"不装 TS 的人"看视频，方案：
1. 自建小 VPS 跑 Nginx 反向代理 + 自己的域名（绕开 CF）
2. 用 Plex 的 free tier 远程播放（但 Plex 2026 远程也要付费了……）
3. 让来访者打开 PWA，提前预下载，看离线

### 5.5 Tunnel 配置文件管理

把 `cloudflared` 的 ingress 配置写进 git（你的 Gitea/Forgejo 仓库），路由规则版本化：

```yaml
# ~/cloudflared/config.yml（挂载进容器）
tunnel: <tunnel-id>
credentials-file: /etc/cloudflared/<tunnel-id>.json
ingress:
  - hostname: money.yourdom.top
    service: http://ezbookkeeping:8080
  - hostname: photo.yourdom.top
    service: http://immich-server:3001
  - hostname: git.yourdom.top
    service: http://forgejo:3000
  - service: http_status:404
```

---

## 六、AX86U 配合

| 设置 | 状态 / 推荐 |
|------|------------|
| 光猫桥接 | 现在做不了（无超管），先不管，双 NAT 影响有限 |
| DHCP 静态绑定 NAS → 192.168.50.33 | 🔴 必做（已计划） |
| Asuswrt-Merlin 固件 | 可选，刷了能装 Tailscale 让 AX86U 当 subnet router；但你 NAS 已经能跑 subnet router，**重复投入** |
| WireGuard server 内建（Merlin 原生） | 可作为 Tailscale 的**冷备份**：万一 Tailscale 全挂可临时切 WG。配置成本 30 分钟，但常态不用 |
| UPnP | **关掉**，CGNAT 下没用，反而是攻击面 |

---

## 七、DNS 策略（搞清楚谁解析谁）

| 域名类型 | 解析方 |
|---------|-------|
| `*.tail<NNN>.ts.net`（内部） | Tailscale MagicDNS（公网 DNS 也能解析到 100.64/12 IP，因为 ACL 限制访问） |
| `*.yourdom.top`（公网应用） | Cloudflare DNS → CF Tunnel |
| `192.168.50.*`（内网设备） | AX86U DHCP（或可选 Adguard Home） |
| 公网普通查询 | 路由器/手机默认（联通 DNS，可换 8.8.8.8） |

可选优化：装 **Adguard Home** 容器作家庭 DNS 服务器，AX86U DHCP 把 DNS 改成 AGH IP，全屋广告拦截 + 自定义本地解析。轻量，10 分钟搞定。

---

## 八、如果 Tailscale 不可用，才启用公网 SSH 冷备

**默认决定：不做。** Tailscale SSH 已能满足“在外网随时指挥 NAS 上的 Codex / 写代码”，并且没有 OpenSSH 的公网暴露面。只有同时满足「确认 Tailscale 在常用外网长期无法使用」和「Cloudflare Tunnel/Access 也不能接受」时，才考虑此冷备通道。

### 8.1 不能做的事

- 普通 Cloudflare 橙云不能代理 SSH；`8443` 是 Cloudflare 可代理的 **HTTPS** 端口，不是任意 TCP 入口。一个 `IP:8443` 同时也不能既跑 HTTPS 又跑 SSH。
- 不要为了“安全”只把 SSH 改到高端口；改端口只减少无意义扫描，不替代身份认证、最小权限和补丁。
- 不要把 NAS 的 Docker socket、管理面板、Samba 或数据库随 SSH 一起映射到公网；也不要给日常管理账号 root 直登权限。

### 8.2 冷备的最小安全基线

若以后必须做，在路由器上使用一个**不同于 8443**、先实测未被运营商封禁的 TCP 外部端口（如 `22022`）仅转发到 NAS `22`；不需要为了外部端口改变内网 `sshd` 监听端口。放行前按以下次序完成并逐项验证：

1. **账号与认证**：仅允许 `yao`（或另建无共享用途的专用运维账号），`PermitRootLogin no`、`PasswordAuthentication no`、`KbdInteractiveAuthentication no`、`PubkeyAuthentication yes`、`AllowUsers <专用账号>`。使用带强口令的 `ed25519` 密钥；更优先使用 FIDO2 硬件密钥 `ed25519-sk`，启用 PIN/触摸验证，另准备一把离线备份密钥。OpenSSH 的 FIDO2 私钥不可从硬件认证器导出，且默认需要用户触摸确认。[OpenSSH `ssh-keygen` 手册](https://man.openbsd.org/ssh-keygen)
2. **收窄服务面**：限制认证次数与未认证并发（`MaxAuthTries`、`MaxStartups`），关闭不需要的 X11/agent/隧道转发；若确实需要本地端口转发来访问 NAS Web 服务，使用 `PermitOpen` 白名单，而不是开放任意目的地址。`AllowUsers` 可以把允许登录者限制为用户名，甚至用户名加来源网段；但移动网络 IP 经常变化，不能把它当作唯一防线。[OpenSSH `sshd_config` 手册](https://man.openbsd.org/sshd_config)
3. **网络与告警**：NAS UFW 只允许该端口；路由器只建这一条 TCP 映射；启用 `fail2ban`，将 SSH 认证失败、成功登录和 sudo 事件发送到现有告警渠道；保留 unattended-upgrades，并月度核查 `journalctl -u ssh`。fail2ban 只是减噪与延迟攻击，不是认证替代品。
4. **变更安全**：先用 `sudo sshd -t` 校验配置；先从**第二个外网会话**完成 key 登录和 sudo 验证，再关闭旧规则。任何一步失败，撤回端口映射并从 LAN 现有会话修复，避免把自己锁在 NAS 外。

可放入 `/etc/ssh/sshd_config.d/99-public-cold-backup.conf` 的方向如下，实际值必须先按现有 Debian 配置核对，且不立即执行：

```text
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
AllowUsers yao
MaxAuthTries 3
MaxStartups 10:30:30
X11Forwarding no
AllowAgentForwarding no
PermitTunnel no
# 仅在确认不需要 SSH 端口转发后：AllowTcpForwarding no
```

### 8.3 Cloudflare Access 是“非暴露”的第二冷备，不是速度方案

Cloudflare Tunnel + Access for Infrastructure 可以让 NAS 只建立**出站**连接，并以 SSO、短期 SSH 证书、用户/端口/Unix 用户策略和审计日志控制 SSH；无需开放任何入站端口。它适合“临时从陌生电脑的浏览器进入”或强审计场景，但流量必经 Cloudflare，不能用来解决目前的速度问题；官方还列出不支持端口转发、agent forwarding，且 SSH 会话最长预期 10 小时。[Cloudflare Access for Infrastructure SSH](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/use-cases/ssh/ssh-infrastructure-access/)

## 九、执行顺序建议（按风险排序）

| 顺序 | 项 | 时间 | 阻塞 |
|------|----|------|------|
| 1 | AX86U DHCP 绑定 NAS → `192.168.50.33` | 5 min | 无 |
| 2 | 建立并验证“橙云 / 灰云 / Tailscale”测速记录 | 30 min | 1 |
| 3 | 将 UDP 41641 仅转发至 NAS，确认 `tailscale ping ynas` 变为 `direct` | 15 min | 1 |
| 4 | 收紧 Tailscale ACL 后启用 Tailscale SSH；外网实测 `ssh yao@ynas` | 20 min | 3，且保留 LAN 救援会话 |
| 5 | 只将需给外人/微信使用的 Web 服务保留在 Cloudflare Tunnel + Access | 按服务 | 2 |
| 6 | 若仍需要公网 SSH，另立一次变更、按第八章冷备基线执行 | 30–60 min | 4 后仍不满足需求 |

最关键节点是第 3 步是否为 `direct`，以及第 4 步能否在外网稳定 SSH。任何测速没有完成前，不把“灰云更快”或“Cloudflare 是唯一瓶颈”写成结论。

---

## 十、要拍板的几个决策点

1. **域名**：买 `.top`（最便宜，¥6/年）还是 `.xyz`（¥10/年）？还是已经有域名了？
2. **是否要装 Adguard Home**？（家庭 DNS + 广告拦截，对老婆刷视频体验有帮助，但增加一个容器）
3. **Tailscale ACL 是否现在就配**？（单人用可以先不配，加家人时再说）
4. **UDP 41641 直连验证**：按本次推荐先验证；若仍走 DERP，再决定是否排查双 NAT / 部署 peer relay。
5. **公网 SSH 冷备**：默认不启用；仅在 Tailscale 不能满足日常可达性时再按第八章实施。

**当前推荐**：1/2 沿用既有规划；3 = 立即最小化 ACL（至少只放你的设备）；4 = 先做；5 = 不启用。

---

## 修订日志

| 日期 | 版本 | 变更摘要 |
|------|------|----------|
| 2026-05-11 | v1 | 初版：基于当时 CGNAT 假设的 Tailscale + Cloudflare Tunnel 分层设计。 |
| 2026-08-22 | v2 | 按业主确认更新为“已有公网 IPv4、8443 可用、80/443 封禁、Cloudflare 代理降速已观察”；新增测速方法、Tailscale UDP 直连优先、Tailscale SSH 主通道与公网 SSH 冷备安全基线。 |
