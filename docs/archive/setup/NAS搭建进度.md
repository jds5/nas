# NAS 搭建进度

> **迁移说明（2026-09-10）**：来自 Homeland；原文中的“本轮”“已完成”和版本信息保留原记录日期，本次未复验现网。本文仅供历史追溯，实施以[现行网络安全架构](../../architecture/家庭NAS网络安全架构.md)及现场核验为准。

> 项目：铁威马 F4-424 Pro（Intel N305 / 32GB DDR5 / 4 盘位）
> 开工时间：2026-05-10 14:00
> 当前节点：系统、网络、存储、监控、文件共享和应用基础已就位；2026-08-22 已确认公网 IPv4 + 8443 服务可达、80/443 被封，进入"运维稳定化 + 安全远程访问优化"阶段
> 关联文档：[装修总预算主文档 § NAS / Home Assistant 核心](../../../../homeland/docs/装修总预算主文档.md)、[前期调研](../../../../homeland/docs/archive-前期规划/前期调研.md)

---

## 0. 状态总览

| 维度 | 状态 |
|------|------|
| 硬件 | ✅ 完成（4 盘位上电、4 盘清点） |
| 系统层 | ✅ Debian 13.4 trixie + 基础加固完成 |
| 监控基础 | ✅ lm-sensors / smartmontools / nvme-cli / Intel 媒体驱动就位 |
| 存储层 | ✅ 三层目录结构 + Samba 共享完成 |
| 网络层 | ✅ Tailscale + Docker `nas-net` 完成 |
| 应用层 | ✅ 11 个容器组 / 14 个容器部署完成 |
| 备份策略 | ⏳ **未部署**（最关键缺口） |
| 路由器静态绑定 | ⏳ **未做**（防 IP 漂移） |
| 数据迁移（旧媒体/照片） | ⏳ 待开始 |
| HA 容器骨架 | ⏸️ 家装尾声 |
| 反代 + 域名 | ⏸️ 内网先用 |
| 公网连通性（2026-08-22） | ✅ 已确认公网 IPv4，8443 可提供服务；80/443 已确认封禁 |
| Cloudflare 代理性能（2026-08-22） | ⚠️ 业主观察到开启代理后明显变慢；待按 [网络架构设计 §1.2](NAS网络架构设计.md#12-先测再调最小对照表) 做对照测速 |
| 外网 SSH（2026-08-22） | 🟡 推荐 Tailscale SSH + UDP 41641 直连；**不推荐直接暴露 OpenSSH**，详见 [网络架构设计 §8](NAS网络架构设计.md#八如果-tailscale-不可用才启用公网-ssh-冷备) |

> 一句话定位：**12 小时完成 80%，剩下 20% 是"运维稳定化"——备份、IP 绑定、数据迁移。这三件不做完，系统能用但不能丢。**

---

## 一、已完成事项

### 1.1 硬件

| 项 | 内容 |
|---|---|
| 来源 | 闲鱼二手铁威马 F4-424 Pro，¥2,780 |
| CPU/内存 | Intel N305（8C/8T Alder Lake-N）+ 32GB DDR5 |
| 传感器芯片 | IT8613E（需第三方 DKMS 驱动） |
| 4 块磁盘 | NVMe 2T + Toshiba 3T + Seagate 2T + Seagate 16T |
| eMMC 4G | 黑群晖残留，确认无害，保留不动 |
| 预警盘 | 3T 重映射 239 次的盘**不接入 NAS**，留在 PC |

### 1.2 系统层

| 项 | 内容 |
|---|---|
| 发行版 | Debian 13.4 trixie（NVMe 安装） |
| 分区 | EFI 512M + swap 16G + ext4 余下 |
| 清理 | 移除黑群晖 LVM / mdadm 残留 |
| 启动 | BIOS 设 NVMe 第一启动绕过 eMMC（救援路径：GRUB `init=/bin/bash`） |
| 主机/用户 | `ynas` / `yao`（加入 sudo/docker/nas-data） |
| 时区 / 软件源 | Asia/Shanghai / 清华源 + non-free |
| SSH | key 免密 + 禁密码登录 + 禁 root |
| 防火墙 | ufw 仅允许 22 + 局域网 |
| 自动更新 | unattended-upgrades（安全更新） |

### 1.3 监控基础

| 项 | 内容 |
|---|---|
| 温度传感器 | lm-sensors + IT8613E DKMS（`force_id=0x8613`） |
| 硬盘健康 | smartmontools + nvme-cli |
| Intel 媒体 | intel-media-va-driver-non-free + vainfo，验证 N305 支持 H.264/HEVC/VP9/AV1 编解码 |
| 健康脚本 | `~/health.sh`（登录自动跑） |
| 免密 sudo | `/etc/sudoers.d/nas-monitoring`，监控命令免密 |

### 1.4 存储层

**三层目录结构**（决策：每层物理隔离 + 用途隔离）：

| 路径 | 物理盘 | 用途 | 文件系统参数 |
|------|--------|------|-------------|
| `/opt/nas/` | NVMe 2T | 容器配置 + 数据库（高 IO） | ext4 默认 |
| `/mnt/pool-main` | Seagate 16T | 主存储（媒体大文件） | ext4 `-T largefile`，省 ~840GB 预留 |
| `/mnt/critical-mirror` | Seagate 2T | restic 仓库（root 700） | ext4 |
| `/mnt/secondary-mirror` | Toshiba 3T | 镜像副本 | ext4 |

| 项 | 内容 |
|---|---|
| 通用参数 | `lazy_init=0` + `-m 1`（root 预留 1%） |
| 自动挂载 | `/etc/fstab` + `noatime` |
| 共享用户组 | `nas-data` (gid 1001) |

### 1.5 网络层

| 项 | 内容 |
|---|---|
| Tailscale | 已连接（`100.106.3.86`） |
| Docker CE | 已安装，日志限制 `10m × 3` |
| Docker 网络 | 统一 `nas-net` 共享网络（容器互通） |
| Samba | 共享 `media` / `photos-upload` / `manga` / `docs`，`hosts allow` 限局域网 + Tailscale，`force group nas-data`（容器权限通） |

### 1.6 应用部署（11 个容器组 / 14 个容器）

| 类别 | 应用 | 形态 / 关键配置 |
|------|------|----------------|
| 监控 | **Beszel** | Hub + Agent（host network），SMART + Docker 监控 |
| 影视 | **Jellyfin** | Intel QSV 硬件转码（VAAPI 备用），4 个媒体库 |
| 照片 | **Immich** | 4 容器：server + postgres(VectorChord) + redis + ML（CPU 镜像，OpenVINO 在 N305 不稳）；DB 在 NVMe，照片在 16T |
| 漫画 | **Komga** | `/mnt/pool-main/books/manga` 只读挂载 |
| 音乐 | ~~Navidrome~~ | **未部署**（原清单误记） |
| 协作 | **Grist**（待替换） | 社区版，单人邮箱登录；UI 老旧，已决定换 ezBookkeeping（见 § 二·5） |
| Git | **Gitea** | SQLite，仓库在 NVMe（`/opt/nas/gitea/git-repos`），SSH 2222 端口，禁注册 |
| 自动化下载 | **qBittorrent** | 主机模式，匿名 / 加密 / 做种限制开启 |
| | **Prowlarr** | 索引器：Nyaa + AnimeTosho + YTS |
| | **Sonarr** | 电视剧 / 动漫，Hardlinks 启用 |
| | **Radarr** | 电影，Hardlinks 启用 |
| | **Bazarr** | 中文字幕：Subtitlecat + OpenSubtitles |
| | 分类 | sonarr / radarr / anime，Sonarr/Radarr → Jellyfin Webhook |

---

## 二、当前进行中

| 项 | 状态 |
|---|---|
| 数据库扩容 / 迁移到 NAS | 进行中 |
| Frieren / Oppenheimer 测试下载 | 已完成 |
| Bazarr 自动下中文字幕 | 已验证 |

---

## 二·5. 2026 现状核查（联网检索结论，2026-05-11）

> 优选标准：**功能完善 + 社区活跃 + UI 美观**。下表是对 12 个已装应用逐项检索后的结论。

### 总判决

| 应用 | 判决 | 关键事实 |
|------|------|---------|
| **Beszel** | ✅ 留 | 2026-04 仍在月度更新（加 Apple Silicon GPU、nvtop、eMMC SMART），<10MB 内存，轻量监控甜点 |
| **Jellyfin** | ✅ 留 | v10.11.8（2026-04），EF Core 数据库重构落地、FFmpeg 7.1，下一代 v12 路线图清晰；**Plex 2025-2026 连涨 +108%/再涨 50%**，开源阵营无替代 |
| **Immich** | ✅ 留 | v2.7（早 2026），90,000+ star，每两周一发；当前 4 容器 + VectorChord 拓扑就是官方推荐。**PhotoPrism/Ente 都打不过手机自动备份这关** |
| **Komga** | ✅ 留 | v1.24.4（2026-04），Mihon/Kobo Sync/KOReader 集成最强；Kavita 现已并列双雄（扫描更快、AniList 元数据），但**只读挂载形态下 Komga 已合身**，未来切换零迁移成本 |
| ~~Navidrome~~ | ⏸️ **未部署**，需要再说 | v0.60（2026-02）引入 WASM 插件，OpenSubsonic 是开放规范、客户端生态强大；要装时直接装 |
| **Grist** | ⚠️ **换 ezBookkeeping** | 见下方专项 |
| **Gitea** | ⚠️ **换 Forgejo** | 见下方专项 |
| **qBittorrent** | ✅ 留 | v5.2.0（2026-05-03），Qt6 时代成熟产物，*arr 头等公民 |
| **Sonarr** | ✅ 留 | v4.0.17（2026-03），v5 milestone 85% 完成，Servarr 团队无分裂 |
| **Radarr** | ✅ 留 | 已进 v6.2（2026-04），比 Sonarr 还前沿 |
| **Prowlarr** | ✅ 留 | v2.3.5（2026-04），Servarr 亲生子，与 *arr API key 自动下发是 Jackett 永远比不上的护城河 |
| **Bazarr** | ✅ 留 | v1.5.5-beta.1（2026-01），单人项目慢节奏但**赛道实质无竞争**；Whisper AI 字幕作为 provider 接入而非替代 |

### ⚠️ 行动项 1：Gitea → Forgejo 迁移

| 项 | 内容 |
|---|---|
| **理由** | ①治理：Forgejo 由 Codeberg e.V. 非营利治理 vs Gitea 公司主导 + 商业化 Gitea Cloud；②功能：ActivityPub 联邦、Forgejo Actions 先发；③安全响应更勤；④性能/资源持平 |
| **窗口收窄** | **Forgejo v10.0.0（2025-01）是最后一个能从 Gitea ≤1.22 透明升级的版本**。Gitea 1.23+ 已脱离迁移路径——你当前若仍在 Gitea 1.22 区间内，**越早迁越省事** |
| **迁移操作** | ①备份 SQLite 文件 + data 目录；②停 Gitea 容器；③镜像换成 `codeberg.org/forgejo/forgejo`（保留同 volume 与配置）；④启动，首次自动迁移 schema；⑤验证 SSH 2222、推送、Webhook 全部正常 |
| **预计耗时** | 15 分钟 + 验证 30 分钟 |
| **风险** | 极低（数据库基本兼容），但**先确认当前 Gitea 版本号** —— `docker exec gitea gitea --version` |

### ⚠️ 行动项 2：Grist → ezBookkeeping（家庭账单）

**背景**：Grist UI 老旧不符合"美观"硬指标；主用途是家庭账单 + 两人 + **微信内置浏览器友好**。

**选型结论（候选见 § 8 附录·账单工具对比）**：

| 排名 | 应用 | 关键优势 | 关键风险 |
|------|------|---------|---------|
| **#1** | **ezBookkeeping**（mayswind） | Go + Vue3 + Vuetify，国人作者**原生中文**，预置人民币/支付宝/微信支付模板，UI 现代 Material 风，移动端专门做过，2026 仍高频更新（最近加 MCP/OCR/定时账单） | 多用户协作仅"共用账号 + tag"级别 |
| #2 | Actual Budget | UI 美 + 原生 multi-user 双人并发，文档专列 Joint Accounts | 中文 i18n 不完整；envelope budgeting 心智模型偏重；本地优先架构在微信 X5 上未实测 |
| #3 | Cashbook（dingdangdog） | 国人原生最稳、支持微信/支付宝账单导入 | UI 朴素，社区较小 |

**决定**：装 **ezBookkeeping**。

### 🔴 行动项 3（前置必做）：搞定"真域名 + 公网信任证书"

> 这是 ezBookkeeping 能在微信里用的**硬前提**，也直接推翻了原"内网先用、反代后期"的顺序。

**为什么必须前置**：

- **Android 微信内置浏览器对自签证书 = 直接白屏**（不像 Chrome 有"高风险继续"按钮）。多个开发者社区 + 微信开放社区官方 issue 确认。
- iOS 微信（WKWebView）问题较小但仍不稳。
- 意味着：`https://nas.local` + 自签证书 → 老婆在微信里点开 → 白屏，工具再美也用不了。

**方案**：

| 方案 | 工作量 | 注意 |
|------|-------|------|
| **A：真域名 + Cloudflare Tunnel + LE 证书**（推荐） | ¥10/年 .top/.xyz 域名 + CF Tunnel 部署 1h | Tunnel 不允许视频流（Jellyfin 不走它，其余应用都可走） |
| B：Tailscale Funnel（`*.ts.net` 公网入口 + LE 证书自动） | 0 元，配置 10 分钟 | 会把服务暴露公网，需应用本身有强认证；ezBookkeeping 自带账号系统可用 |
| C：Tailscale 内网 + 老婆手机常开 Tailscale | 0 元，但需要她也装 + 开 | 微信里能开但偶尔慢；可作过渡 |

**其他微信 WebView 共性坑（备忘）**：

1. PWA 安装：微信内**不允许"添加到主屏"**（政策限制），但 Service Worker 缓存能用。
2. 首次进入时引导走"右上角 → 在浏览器打开"装 PWA，日常微信内做轻量录入，重操作切换浏览器。
3. 微信 Android XWeb 内核（基于 Chromium 86–119）**不支持 import maps / top-level await** —— ezBookkeeping 用 Vite，默认 ES2020+，**首次部署后必须实测一笔**。

**ezBookkeeping 部署顺序**：
1. 先搞域名 + 证书（方案 A 或 B）
2. Docker 部署 ezBookkeeping（mayswind/ezbookkeeping 镜像）
3. **老婆微信内测一笔**，验证 < 3 秒可交互、中文输入正常
4. Grist 数据导出 CSV（账户/分类/交易三张表）→ ezBookkeeping 导入向导
5. Grist 容器停掉、配置归档

### 其他洞察（不影响留换决策但值得记一笔）

1. **Plex 不可逆涨价**：2025-03 终身 Plex Pass 从 $119 → $249（+108%），2026-06 Remote Watch Pass 再涨 50%。在 Jellyfin 决策上多一份理由。
2. **Huntarr 弃坑事件（2026-02）**：作者删库，社区分裂出 Newtarr/Fetcharr/Seekarr。**警示**：周边 *arr 配套工具有风险，核心 Sonarr/Radarr/Prowlarr 反而最稳。你没用 Huntarr，免疫。
3. **FlareSolverr 替代品 Byparr**：drop-in 兼容、镜像更小。你 4.1 节记录的"Cloudflare 验证导致 1337x/EZTV/TPB 索引失败"将来可直接换 Byparr 试试，配置完全兼容。
4. **Kavita 备选**：如果未来 Komga 扫描慢困扰你，或想读轻小说/Webtoon，Kavita 是平滑替代（同一份 CBZ 文件树）。可双跑对比。

---

## 三、待办事项

### 3.1 🔴 紧急（本周完成）

| 项 | 预计耗时 | 备注 |
|---|---|---|
| **路由器 DHCP 静态绑定** | 5 分钟 | AX86U → LAN → DHCP → 绑 MAC 到 `192.168.50.33`。**先做**，否则后续所有 IP 写死的配置都会因租约更新而漂 |
| **备份策略部署** | 45 分钟 | restic 初始化 `/mnt/critical-mirror` → 备份 `/opt/nas` + `pool-main/{photos,docs,git-repos}`；rsync 镜像 `/opt/nas` → `secondary-mirror/opt-nas-mirror`；crontab 凌晨；**必须做一次恢复演练**才算完 |

### 3.2 🟡 重要（本月完成）

| 项 | 预计耗时 | 备注 |
|---|---|---|
| 旧照片 → Immich（PC → `\\nas\photos-upload`） | 视量级 | 手机端同步 Immich App |
| 旧漫画 → Komga | — | — |
| 旧音乐 → Navidrome | — | — |
| 旧文档 → Samba `docs` | — | — |
| 自动化下载完整验证 | — | Oppenheimer 入库流程、Frieren 字幕齐全；按日常片单扩充 |
| **Gitea → Forgejo 迁移** | 15+30 分钟 | 见 § 二·5 现状核查；Gitea 1.22 → Forgejo 10.x 仍可透明升级，越早越省事 |
| **域名 + Cloudflare Tunnel + LE 证书** | 1–2 小时 | ezBookkeeping 微信内能用的硬前提；见 § 二·5 行动项 3 |
| **Grist → ezBookkeeping 迁移** | 1 小时（含微信内实测） | 域名搞定后再做；见 § 二·5 行动项 2 |

### 3.3 🟢 可选优化（按需）

| 类别 | 项 |
|------|---|
| 系统 | `vm.swappiness=10`、fail2ban（Tailscale 后弱需）、内核调优 |
| 告警 | SMTP（QQ/Gmail）或 Telegram bot → smartd / restic / Beszel 接入 |
| 容器更新 | Watchtower 监控模式（不自动更新）/ 手动 `docker compose pull` |
| 健康脚本 | 备份状态 / 容器健康 / 证书过期 |

### 3.4 ⏸️ 后期（家装尾声）

| 项 | 触发条件 |
|---|---|
| Home Assistant Container | 家装末期，确认具体设备后部署。配套 Mosquitto / Zigbee2MQTT / ESPHome |
| Nginx Proxy Manager + Cloudflare 域名 / 通配符证书 | 内网先用，体验后再上 |
| Cloudflare Tunnel（公开服务） | 子域规划：`jellyfin.*` / `photos.*` / `git.*` |
| Uptime Kuma / Syncthing / Vaultwarden / Frigate / Adguard Home | 按需 |

---

## 四、问题 / 挑战记录

### 4.1 当前未解决

| 问题 | 排查方向 |
|------|---------|
| Oppenheimer 没自动刮削 | Radarr Activity → History；Jellyfin 媒体库是否有；是否需要手动扫描 |
| 健康警告 qBittorrent path mapping | 是否仍残留 |
| Cloudflare 验证导致 1337x / EZTV / TPB 索引失败 | 装 FlareSolverr 或换 **Byparr**（drop-in 兼容、镜像更小，2025 新选择）；或换 PT 站 |

### 4.2 环境限制（不可改）

| 项 | 说明 |
|---|---|
| ~~无公网 IPv4~~ | **已于 2026-08-22 由业主确认不再成立**：已有公网 IPv4，8443 已可对外提供服务 |
| 无法配置光猫 | 无超管密码，光猫路由模式 + AX86U 双 NAT |
| 联通可能封 80/443/8080 | — |
| Cloudflare Tunnel | 不允许视频流（Jellyfin 不能走它） |
| 采用方案 | Tailscale SSH / 直连为个人运维主线；Cloudflare Tunnel + Access 仅给需浏览器公网访问的非视频服务；详见 [NAS网络架构设计](NAS网络架构设计.md) |

---

## 五、应用栈最终状态

```
反代层       [待装] NPM

应用层       Beszel    监控    ✅
            Jellyfin  影视    ✅
            Immich    照片    ✅（4 容器）
            Komga     漫画    ✅
            Navidrome 音乐    ✅
            Grist     协作    ✅
            Gitea     Git     ✅
            qB + Prowlarr + Sonarr + Radarr + Bazarr  下载  ✅

待装层       Home Assistant       家装尾声
            Frigate / Uptime Kuma 按需

基础设施     Docker nas-net + Tailscale   ✅
            NVMe + 16T + 2T(备份) + 3T(镜像)  ✅
            Samba 文件共享                ✅
            restic + rsync                ⏳ 待部署
```

---

## 六、关键决策记录

| 决策 | 理由 |
|------|------|
| 全部 ext4，不用 Btrfs / ZFS | 单盘 + Docker + 文件存储，COW / 快照价值低，ext4 简单可靠 |
| 每应用独立数据库 | 避免共享 PostgreSQL 的版本耦合 / 升级阻塞 |
| 容器配置在 NVMe，媒体在机械盘 | 高 IO 数据库不打机械盘，机械盘只跑顺序读 |
| Sonarr / Radarr Hardlinks 启用 | qB 做种 + 媒体库同一文件不双倍占盘（前提：同盘同分区） |
| Tailscale + Cloudflare Tunnel 组合 | 按访问对象分流：个人运维走 Tailscale，需浏览器公网访问的服务走 Cloudflare Tunnel + Access |
| Beszel 替代 Scrutiny | Beszel 含 SMART，少跑一个容器 |
| 暂缓 Uptime Kuma | 服务少时 Beszel 已覆盖 |
| 暂缓 NPM + 域名 | 内网 ip:port 先用，体验稳定后再上反代 |

---

## 七、当前最关键的下三步

1. **排查 Oppenheimer 刮削问题**（5–10 分钟）
2. **路由器 DHCP 静态绑定**（5 分钟）——防 IP 漂移，必做
3. **备份策略部署 + 恢复演练**（45 分钟）——运维稳定化的起点

> 顺序建议：先做 2（5 分钟，零依赖），再做 3（防丢数据），最后做 1（影响小，可慢慢看）。

---

## 八、待办遗漏复核（建议补入清单）

下列项**未在原清单内**，但对照决策文档与生产实践，属于值得现在或近期补上的缺口：

### 8.1 🔴 建议立刻评估

| 项 | 为什么不能漏 |
|---|---|
| **UPS 不间断电源** | NAS 24/7 + Immich Postgres + Gitea SQLite + qB 元数据，**意外断电极易损坏数据库**。南京夏季雷暴期更明显。当前清单完全没提。最低配 APC BR550G-CN / 山特 TG-BOX 850 ¥400–700 即可 |
| **照片云备份（3-2-1 第二份异地副本）** | 决策文档明确写过"关键数据 → 本地 + **云备份**（OneDrive/Google Drive，restic 加密上云）"。当前规划只到"本地 + 本地镜像"，**缺第三份异地**。火灾 / 失窃 / 入室一次清零。restic → Backblaze B2 / Cloudflare R2 / OneDrive 任选一 |
| **16T 二手企业盘 SMART 长测** | 进数据前应跑一次 `smartctl -t long`（约 18–22 小时），二手盘隐患在长读测才暴露。预警过的 3T 已剔除是对的，16T 这块**没看到结论** |

### 8.2 🟡 建议本月安排

| 项 | 说明 |
|---|---|
| **HA 容器骨架先占位** | 即使家装尾声才接入设备，Mosquitto + HA Container 现在就能跑空架子，提前调通 midea-msmarthome / Home Connect / 米家集成的认证链路，避免家装末期撞墙（这些集成 OAuth 都容易卡） |
| **Hardlinks 真生效验证** | Sonarr/Radarr Hardlinks 启用 ≠ 真生效。`stat <下载文件>` 看 inode 是否与 `media/` 下相同；qB 下载目录与 Sonarr root folder 必须**同一 ext4 挂载点**（不能跨 `pool-main` 和 `opt/nas`） |
| **Immich 大量入库前的设置确认** | ML 选项（人脸/对象识别要不要开，N305 跑 CPU ML 很慢）、缩略图质量、自动去重、外部库 vs 上传库选哪种——大量入库后改设置代价高 |
| **断盘演练 1 次** | 在备份脚本跑过一周后，模拟"`pool-main` 突然挂掉"，用 restic 把 `photos` 恢复到临时目录验证。不演练等于没备份 |
| **媒体硬链接和回收策略** | qB 做种保留期 / 自动删种规则要与 Sonarr/Radarr "完成后处理"对齐，否则要么占空间，要么 PT 站封号 |

### 8.3 🟢 可选但低成本

| 项 | 说明 |
|---|---|
| smartd 月度长测 cron | 每月 1 号自动 `-t long` 16T 主盘；失败再上邮件 |
| Docker `healthcheck` | Immich / Jellyfin / Gitea 容器加 healthcheck，让 Beszel 能看到"容器运行但服务挂了"的状态 |
| Tailscale subnet router | 启用后手机不开 Tailscale 也能内网访问，对家人/访客更友好 |
| Samba Time Machine（如果有 Mac） | 加 `vfs objects = catia fruit streams_xattr` 配段，给 Mac 当 TM 盘用 |
| CPU governor 设 `powersave` | N305 24/7 跑，`powersave` 在轻载时省 5–10W，转码场景仍能瞬时拉到全频 |

### 8.4 ⏸️ 远期记一笔

| 项 | 触发条件 |
|---|---|
| Mosquitto / Zigbee2MQTT / ESPHome 部署 | HA 接入第一个 Zigbee 设备时 |
| Caddy / NPM + 内网 HTTPS | 想用手机 Immich App 在 4G 下流畅访问、且不愿意每次开 Tailscale 时 |
| 第二块 NVMe（读缓存或独立 DB 盘） | Immich 库 > 50GB 后明显感觉慢，或 SSD 价格回落 |
| Frigate + 摄像头 | 装好家、有具体安防需求后 |

---

## 九、一句话总结

12 小时完成 80% 是真本事；剩下 20% 是 **UPS + 异地备份 + 恢复演练 + IP 绑定**——这四件做完，"NAS 项目"才能从"搭好了"变成"丢不了"。
