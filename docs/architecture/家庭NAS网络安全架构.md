# 家庭 NAS 网络安全架构

> **迁移说明（2026-09-10）**：来自 Homeland；原文中的“本轮”“已完成”和版本信息保留原记录日期，本次未复验现网。原文的应用仓库、根目录配置及前端路径指 Homeland。

> **本文档定位**：家庭 NAS 网络与安全架构的**唯一现行主文档（Single Source of Truth）**，统一公网入口、管理面、Cloudflare、NPM、容器网络、应用鉴权、更新、日志与备份恢复口径。只记录当前决策、已核对状态、差距和验收标准；性能试验与故障过程另行归档。其他 NAS 网络文档与本文冲突时，以本文为准。
>
> **评审日期**：2026-09-10（本轮为仓库与官方资料评审，未连接 NAS 复验）
>
> **配套文档**：[实验项目隔离沙箱与共享方案](../archive/network/家庭NAS实验项目隔离沙箱与共享方案.md) · [NAS 运维记录](../operations/运维记录.md) · [本地 Docker 与 NPM 部署记录](../operations/本地Docker与NPM部署记录.md) · [Cloudflare 入口与优选 IP 实验记录](../archive/network/Cloudflare入口与优选IP实验记录.md) · [NAS 搭建进度（归档）](../archive/setup/NAS搭建进度.md) · [装修总预算主文档](../../../homeland/docs/装修总预算主文档.md)

## 2026-09-10 架构复评：保留分流，优先补齐边界

本节是新增评估与建议，不表示配置已经修改。下文旧检查表保留历史过程；与本节所引日期更晚的证据冲突时，不得把旧待办当成当前事实。本轮没有可调用的 TaskCreate/TaskUpdate/TaskList 工具，后续动作在本节记录。

### 当前证据与文档冲突

| 项目 | 本轮判断 | 依据 |
|---|---|---|
| Homeland 入口 | 记录为灰云自选 Cloudflare IP → Cloudflare/Worker → 家庭 NPM → 应用；不等于灰云直连家庭 | 本文零节、[入口实验记录](../archive/network/Cloudflare入口与优选IP实验记录.md)；未复测现网 |
| Writer 写入 | 2026-08-31 已有真实 DELETE 200 且数据库记录消失的证据；不能继续笼统说合法 Writer 从未验收 | [NPM 部署记录](../operations/本地Docker与NPM部署记录.md)；不代表全部写接口及失败矩阵已验收 |
| Docker 隔离 | 仓库仍使用 `common_network` 与 `nas-net`；不发布宿主机端口 | [Compose](../../../homeland/docker-compose.yml)，2026-09-10 读取 |
| Jellyfin | 2026-09-01 记录仍向所有宿主机地址发布 8096/8920 TCP、1900/7359 UDP | [运维记录](../operations/运维记录.md)；宿主机发布不等于已能公网访问，需结合路由和 IPv6 验证 |
| Worker 回源认证、Tailscale 退出、恢复演练 | 未找到闭环证据，仍待复验 | 本文与上述运维记录 |

### 推荐优化顺序

1. **优先补齐源站入口认证。** 管理区 Access JWT 验签保护写权限，但公开 GET 没有 JWT，不能据此宣称整站无法绕过 Cloudflare 的 WAF/限流。现有 Worker 链路若继续保留，建议先使用独立高熵回源 Secret：Worker 覆盖同名入站头，NPM 对该源站 hostname 的全部业务路径验证，缺失/错误即拒绝；密钥只经受控配置注入，不进入应用容器或日志。媒体直连 hostname 保留应用自身认证，不全局套用 Cloudflare 限制。自有证书 mTLS/AOP 可作为后续替代，须先核实实际账户能力与 Worker 回源兼容性；Cloudflare 全局共享 AOP 证书不是账户专属身份。[官方 AOP 说明](https://developers.cloudflare.com/ssl/origin-configuration/authenticated-origin-pull/explanation/)（查阅：2026-09-10）。
2. **拆网络并补访问策略。** 保留每应用 front/back 的方向，但独立 bridge 不自动阻止访问宿主机/LAN，同网应用仍可能访问 NPM:81。NPM 绑定宿主机 LAN 地址也不消除容器内监听。需分别限制应用→宿主机、应用→LAN、应用→NPM 管理口，允许必要 DNS、HTTPS/JWKS 及业务依赖。先确认 NAS 使用的防火墙后端，再设置对应规则；iptables 下 `DOCKER-USER` 处理转发，不能当成覆盖宿主机 INPUT 与全部同桥流量的万能规则。验收要实际测试目标 IP/端口，不能只验证 DNS 名解析失败。[Docker bridge](https://docs.docker.com/engine/network/drivers/bridge/) · [Docker iptables](https://docs.docker.com/engine/network/firewall-iptables/)（查阅：2026-09-10）。NPM:81 既有风险此前已被业主接受，本次仅提出改进，不变更该决定。
3. **媒体内外网分流解析，作为体验优化。** 建议相同媒体域名在家解析到 NAS LAN IP、在外解析到公网 IP，保留有效 HTTPS 证书与应用认证；这是架构建议，尚无本轮性能收益数据。DNS 不改端口：外部 URL 若为 `:8443`，内网 NPM 也需可接收 8443；不能以 DNS 覆盖假设客户端的 443 自动映射。只先覆盖媒体域名，Homeland 管理路径继续经过 Access。核对 AAAA、客户端加密 DNS 和实际连接地址，避免分流未生效却误报成功。Immich 使用独立域名根路径及官方上传反代要求。[Immich 反代](https://docs.immich.app/administration/reverse-proxy/)（查阅：2026-09-10）。
4. **给优选 IP 建立可验证的回退。** 不因历史标准入口较慢就立即撤销优选；比较标准入口与当前入口的成功率、首字节及晚高峰表现，再决定是否值得继续维护 Worker。生产优选切换须验证 TLS、公开读、Access 登录和匿名写拒绝，并保留上一可用配置；标准入口仅在重新验收通过后作为回退。`CF-Ray` 是诊断信号，不替代源站认证。若 Worker 只用于端口改写，可评估原生 Origin Rule，但不能假定其与现有自选 IP hostname 绑定等价。[Origin Rules](https://developers.cloudflare.com/rules/origin-rules/)（查阅：2026-09-10）。
5. **远程维护保留独立恢复路径。** 延续 LAN 默认、必要时自建 WireGuard 的决定。若启用 WireGuard，需另行配置并验证 UDP 可达、路由和防火墙；`AllowedIPs` 不能替代管理端口 ACL。只有替代路径验收完成且依赖查清后才停 Tailscale。DNS 不应依赖被关闭的 Tailscale，也应避免家庭基础解析与 NAS 维护形成循环依赖。[WireGuard Quick Start](https://www.wireguard.com/quickstart/)（查阅：2026-09-10）。

本轮不建议先更换路由器、升级链路速率、引入第二层通用代理或集群。仓库没有足够的新吞吐、链路协商、CPU/转码与磁盘指标来证明其收益。容器限额、公网边界复扫、独立备份和真实恢复演练继续保留；网络加固不能替代恢复能力。

### 下一步行动（未实施）

1. 只读盘点 NAS 的监听端口、容器网络成员、路由器 IPv4/IPv6 放行项与实际生产 Worker/NPM 路径；不输出环境变量值、令牌或原始敏感配置。形成带日期的可达性矩阵，再落实源站认证及网络隔离。
2. 在家庭 Wi-Fi 与外部移动网络对同一媒体样本分别验证 DNS、TLS、上传/播放/拖动与耗时，并模拟优选入口故障验证回退；另安排隔离环境恢复演练。本轮未连接 NAS、未改现网、未执行这些验收。

## NAS 上 Codex / tmux 的外网指挥（2026-09-10）

### 官方功能核对

Codex 已有官方实验性 `remote-control`，不能再笼统说不支持远程控制。官方命令参考列出 `codex remote-control start`（启动启用远控的本地 app-server daemon）、`stop` 和 `pair`（创建短期配对码）。本轮在 Windows 的 VS Code 扩展所带 CLI 0.153.0 上执行 `--version`、`--help`、`remote-control --help` 与 `remote-control start --help`，确认命令存在；**没有启动远控或生成配对码，NAS 上安装版本仍未核对**。[官方命令参考](https://learn.chatgpt.com/docs/developer-commands?surface=cli#codex-remote-control)（查阅：2026-09-10）。

官方手机 Remote 指南描述的受支持配对流程是 ChatGPT 手机端连接在线的 Mac/Windows 桌面主机；桌面主机再通过 SSH 使用远程项目。指南尚未建立“Linux NAS CLI 配对即可让手机接管任意已有 tmux 窗口”的完整证据。CLI 命令存在、手机账户开放功能、运行中会话由同一 daemon 管理，是不同条件；需分别实测。不能把文档中的 `resume`、App Server 远程 TUI 或桌面 SSH 项目误写成 tmux 终端镜像。[官方远程连接指南](https://learn.chatgpt.com/docs/remote-connections) · [App Server](https://learn.chatgpt.com/docs/app-server)（查阅：2026-09-10）。

### 推荐：私有管理通道接回原 tmux

```text
手机/笔记本 → WireGuard 加密通道 → 家庭 VPN 端点
                                      │ 防火墙仅允许所需 NAS SSH
                                      ▼
                                NAS SSH → 原用户 tmux → 已运行 Codex

既有 Cloudflare / NPM / 8443 Web 与媒体入口保持独立
```

这是针对现有 tmux 的推荐方案，尚未部署。VPN 优先部署在路由器（需先核实当前固件支持）；不支持时可选择 NAS 上维护成熟的 WireGuard 部署。需确认家庭上游 NAT 的 UDP 可达性，不能从 TCP 8443 可用推断 UDP 必然可用。新增一个实际选定的 UDP VPN 入口，不公开 TCP 22、tmux socket 或 Codex App Server。普通 NPM HTTP 反代不承载此 SSH 链路。[WireGuard Quick Start](https://www.wireguard.com/quickstart/)（查阅：2026-09-10）。

配置约束：每台客户端独立 VPN 密钥；客户端只路由需要的 NAS 地址/管理网段，服务端防火墙再限制目的端口，不能只靠 `AllowedIPs`。SSH 使用独立客户端密钥、核对主机指纹；先验证密钥登录再调整密码策略，避免锁死。使用启动现有 tmux 的同一 Unix 用户及同一 socket/容器环境，不能通过放宽 socket 权限来共享会话；未来若采用专用账号，另行迁移任务。手机丢失时分别撤销 VPN peer 和 SSH key。VPN 与手机已有代理的共存能力需实测，不假定两个 VPN 可同时使用。

VPN 连通后示例（用户名/IP 沿用仓库历史记录，执行前确认；会话名替换为真实值）：

```bash
ssh yao@192.168.50.33
tmux ls
tmux attach -t <会话名>
```

默认快捷键：`Ctrl-b w` 选择窗口，`Ctrl-b s` 选择会话，`Ctrl-b d` 脱离且保持任务运行。普通 attach 允许多个客户端连接；`attach -d` 会脱离其他客户端，只在明确接管时使用。无需为每个 Codex 窗口开放独立端口，也无需重启现有 Codex。若窗口看不到，先检查 Unix 用户、tmux `-L/-S` socket 与容器归属。[tmux 官方入门](https://github.com/tmux/tmux/wiki/Getting-Started)（查阅：2026-09-10）。

### 备选与实施边界

| 方案 | 适用性 | 当前结论 |
|---|---|---|
| CLI 官方 Remote | 希望手机原生任务界面、审批和通知 | 可小范围试用；NAS 版本、手机配对及既有会话接管未验证 |
| 手机 Remote → Mac/Windows 桌面 → NAS SSH 项目 | 官方已描述的远程项目流程 | 增加一台常在线桌面依赖，不承诺直接接管旧 tmux |
| WireGuard → SSH → tmux | 原样继续当前窗口、输入和审批 | 首选稳定管理路径；手机终端操作不如专用任务 UI 方便 |
| 浏览器 Web terminal | 必须只用浏览器时 | 暂不选；它具有 shell 权限，整个入口及 WebSocket 均须保护，不能套用 Homeland 公开 GET 的模式 |

若未来使用 `codex --remote`，优先让 App Server 只监听本地 socket/loopback，再经 SSH 转发；它不是让互联网直接连接开放 WebSocket 的理由。官方也明确建议使用 VPN/mesh 而非公网暴露 App Server。Codex 的原有审批与沙箱设置应保留，不因手机操作而统一关闭。

### 下一步行动与未完成

1. 在 NAS 只读核对 `codex --version`、`codex remote-control --help`、tmux 用户/socket 归属与 VPN 状态；使用独立空白测试会话验证官方配对，配对码不进入 Git、日志或公开文档。测试成功前不停止/迁移现有任务。
2. 若落实推荐方案，从外部移动网络验证 VPN、SSH、原会话 attach、断网重连、任务不中断及撤销客户端后不可连接；核对公网 IPv4/IPv6 无 SSH/App Server 直开。本轮只完成文档与本地 CLI 帮助核查，未连接 NAS、部署 VPN 或实际手机配对。

检索说明：OpenBSD tmux 手册站读取失败，已改由 tmux 官方 GitHub 入门文档核实；本机 Docker 未观察到运行中的浏览器容器，未执行浏览器回退验证。官方命令参考补足了首次 Remote 关键词搜索未命中的证据。

## Android Codex 客户端自研投入与收益（2026-09-10）

用户明确主要使用 Android，认为直接 SSH 的体验与官方手机 App 差距大。本节评估自研价值，不代表已经决定开发或变更管理入口。

### 两条技术路线

1. **SSH/tmux 增强客户端**：使用成熟 SSH/终端组件，提供会话/窗口卡片、命名收藏、中文多行输入、切换/脱离/重连与特殊键按钮。可保留原任务，兼容其他 CLI；但 ANSI 终端输出不等于结构化聊天、运行状态和审批事件。不能从“输出停止”推断任务已完成，不能把终端文字匹配作为可靠审批协议。适合解决手机键盘和 tmux 操作障碍。
2. **Codex App Server 客户端 + 终端备用入口（推荐评估方向）**：主界面呈现线程列表、Markdown 消息、工具执行、待审批卡片与继续/中断操作；终端只用于旧会话和排障。官方协议提供 `thread/list/read/resume`、`turn/start/steer/interrupt`、流式事件和服务端审批请求，因此可实现任务界面，而不必从屏幕抓取状态。旧 tmux 进程是否归属于同一 daemon、是否能被并行连接，需要实测；不能把恢复历史等同于接管运行中进程，也不能让两个执行器同时运行同一任务。[Codex App Server](https://learn.chatgpt.com/docs/app-server)（查阅：2026-09-10）。

Android 原生客户端可经 VPN 建立 SSH 隧道，内部再连接仅 loopback/socket 暴露的 App Server；SSH 是加密传输，用户看到的可以是聊天界面。隧道与 socket 适配须先验证。第一版建议单 NAS、单用户、一个经验证的 Codex 版本、Android 私有安装，不包含公开网站、多租户、应用商店发布或完整 IDE。若采用 WebView/xterm.js 作为终端组件，终端内容视为不可信，保持终端与有权限的控制 UI 隔离，不把 shell 输出直接插入 HTML。[xterm.js 安全指南](https://xtermjs.org/docs/guides/security/)（查阅：2026-09-10）。

### 工作量规划区间（应用户要求给出，非实测工时或交付承诺）

假设一名熟悉 Android/网络的开发者配合 AI、已有可用 VPN/SSH、复用成熟组件，1 人日按 6 小时有效开发和验证计。区间包含基本真机联调，不包含网络基础部署、等待用户验证或产品上架；实际工期须在原型后重估。

| 交付目标 | 规划投入 | 收益与边界 |
|---|---|---|
| SSH/tmux 增强版，能持续个人使用 | 3–6 人日（18–36 小时） | 原会话无需迁移；解决导航、输入与快捷键，回复阅读仍受终端限制 |
| Codex 结构化客户端，核心流程可日用 | 8–15 人日（48–90 小时） | 消息、线程状态、常用审批、继续/中断与重连；旧会话接管和特殊工具流程是主要不确定性 |
| 接近官方 App 的完善个人客户端 | 20–40 人日（120–240 小时） | 更完整的审批/附件/diff、大历史、后台通知与异常恢复；不承诺全功能对等，消息推送路径需另验 |

这些是互相替代的目标总量，不是应累加的阶段；展示原型不能算日用成品。AI 可以减少界面和协议样板代码，但连接状态、请求去重、审批过期、多客户端并发和 Android 真机后台行为仍须测试。Android 后台执行受系统限制，不能把常驻 SSH/WebSocket 当成可靠通知机制；前台恢复需重新同步，离线推送属于单独功能。[Android 后台限制](https://developer.android.com/develop/background-work/background-tasks/bg-work-restrictions)（查阅：2026-09-10）。

### 收益判断与建议

- 如果只是偶尔查进度，投入完整客户端的时间收益不明确，先核实官方 Remote 可用性。
- 如果每天多次指导任务、多项目切换、输入中文长指令，结构化客户端更契合目标；收益包括减少切错窗口、错过审批和输入困难，不能仅按“节省敲命令时间”衡量。
- tmux 层天然可兼容 Codex/Claude/其他 CLI；协议客户端获得更好状态与审批体验，但依赖 Codex 协议版本，升级前需要回归测试。不要预先承诺固定月维护工时。
- 回本计算只能用用户实际使用数据：开发及维护小时数 ÷ 每日净节省小时数。当前没有用户频次、节时和维护记录，不声称几周/月回本。

推荐先为**一天的可行性验证设置投入上限**，而非承诺一天交付客户端：核对官方 Remote；用无重要数据的 App Server 会话验证列举、发送、流式读取、审批、断线恢复和旧 tmux 归属。通过后优先做“结构化任务界面 + 一个原终端备用入口”；不通过则保留旧 tmux，仅对新任务采用协议客户端。不要先花大部分投入制作界面，再发现会话无法接管。

### 下一步行动与未完成

1. 如进入开发，先验证上述协议和迁移边界并重新估时；列出第一版明确支持的审批类型，未知请求必须显示待处理或转终端，不能静默允许。
2. 以 Android 真机执行断网、锁屏、恢复、重复发送、审批已失效及桌面同时操作测试；任务不能因客户端退出而丢失，不能把重连重发变成重复执行。

本轮只完成投入收益分析、Android 平台确认与官方资料核查；没有实现客户端、连接 NAS 或验证性能。本项目预算无金额变化。

## 公网 SSH 与 Android 动态 IP 白名单评估（2026-09-10）

**结论：维护及时、有效配置为仅密钥登录并限制账号的公网 OpenSSH，可以作为接受剩余风险的方案；VPN 不是唯一安全方式。** 用户本轮询问可行性，不构成已经部署或替换原 LAN/VPN 管理设计的记录。

### 仅非 root + SSH key 的边界

- 仅公钥认证能关闭密码猜测入口，但不阻止认证前实现漏洞、资源耗尽、私钥被盗或客户端被控制。OpenSSH 官方安全页记录过认证前漏洞，因此密钥登录不能替代发行版安全更新；判断补丁状态应看发行版公告/包修订，而非只比较上游版本号。[OpenSSH Security](https://www.openssh.org/security.html)（查阅：2026-09-10）。
- 非 root 不等于低权限。无约束 sudo/NOPASSWD、rootful Docker daemon 控制、可读凭据/备份以及高权限 Codex 会话都会扩大影响面。Docker 官方明确指出 docker 组授予 root 级权限。本轮未核对 NAS 用户权限，不声称 `yao` 已满足或不满足最小权限。[Docker Linux post-install](https://docs.docker.com/engine/install/linux-postinstall/)（查阅：2026-09-10）。
- 推荐的配置意图是 `PermitRootLogin no`、`PubkeyAuthentication yes`、`AuthenticationMethods publickey`、`PasswordAuthentication no`、`KbdInteractiveAuthentication no` 和精确 `AllowUsers`。实施时保留现有登录通道，先 `sshd -t`，再用 `sshd -T -C` 按实际用户/来源核对有效配置及 Include/Match，重载后另开连接验收。上述禁用交互认证仅适用于本轮纯 key 方案，未来增加 PAM OTP 时应重新设计组合认证。[sshd_config](https://man.openbsd.org/sshd_config)（查阅：2026-09-10）。
- 每台设备独立密钥，保护私钥并核对服务器指纹；高端口主要减少日志噪声，不能隐藏入口。连接限速、未认证连接限制和失败封禁是辅助控制，不替代补丁和密钥。
- tmux 操作无需 SSH 转发，可限制不用的 agent/X11/端口转发；如果客户端需要 SSH 隧道连接 App Server，则按所需目标开放本地转发，不全禁。具有 shell 的用户仍能自行运行转发程序，关闭内置转发不等于主机网络隔离。
- 路由器 IPv4 转发与主机 IPv6 防火墙分别验收；不通过现有 NPM HTTP Proxy Host 暴露原生 SSH，也不直接公网发布 App Server。

### 手机白名单的可行性

白名单匹配的是 NAS 看到的网络源地址，不是 SIM、手机号或设备身份。手机切 Wi-Fi/蜂窝、重新联网、代理路径变化时可能换出口；CGNAT 可让多个用户共享公网出口，IPv6 地址也须单独考虑。用户实际运营商出口行为本轮未测。CGNAT 地址共享机制参考 [RFC 6598](https://www.rfc-editor.org/info/rfc6598/)（查阅：2026-09-10）；不能用运营商整个网段当作个人白名单。

| 方式 | 对手机的适用性 | 代价/边界 |
|---|---|---|
| 无 IP 白名单，严格按设备 SSH key 认证 | 网络切换无需更新名单 | sshd 认证前入口对公网可见，需接受并维护 |
| 固定 IP 跳板机 + SSH ProxyJump | 手机 IP 任意，NAS 只允许跳板固定源 IP | 跳板也需加固；NAS 必须确实看到该固定源地址，端到端仍验证 NAS host key；无需把 NAS 私钥放跳板或启用 agent forwarding |
| 固定出口 VPN / 自建 WireGuard 私网 | 按固定出口或设备隧道地址限制 | 仍需客户端隧道；私网方案可关闭公网 SSH，而不是追踪手机公网 IP |
| 强认证后动态添加当前 IP，短时自动过期 | 能实现，但不优先 | 要解决认证入口、原始源地址、CGNAT/代理出口差异、换网重试、过期清理和 IPv6；同出口用户也进入网络白名单，仍需 SSH key |

动态名单不能只让手机提交任意 IP 便放行，也不能信任未校验的 `X-Forwarded-For`。若通过 Cloudflare 登录页面申请，网页流量与 SSH 可能走不同出口，网页观察到的地址不一定适用于 SSH。DDNS 主要解决家庭目的 IP 变化，不自动解决手机来源白名单。此功能若未来开发，属于管理操作，必须走已鉴权管理入口并使用成熟身份验证组件，不能新增匿名防火墙修改 API。

推荐选择：如主要希望省去手机 VPN 且接受公网 SSH，先评估严格 key-only 的直接接入；如还希望 NAS SSH 不接受全网握手，可评估现有固定 IP 服务器承担跳板（本轮未连接或变更该服务器）。不优先开发动态手机 IP 放行系统。

### 下一步行动与未完成

1. 部署前只读核对 sshd 补丁、有效认证配置、目标用户 sudo/docker/文件权限与 tmux 归属，再决定是否接受该账号公网登录。
2. 从外部 Android 网络验证正确密钥成功、密码/root/错误密钥失败及 IPv4/IPv6 实际入口；若加白名单，验证不在名单来源不可连接。现网配置与上述测试均未执行。

## 零、2026-08-30 当前安全状态

Homeland 已恢复灰云自选 Cloudflare IP 公网入口，采用“公开读、Access 登录写”的单 hostname 模式。这里的灰云只表示 DNS 记录不由 Cloudflare 自动代理；A 记录仍指向可承载该 hostname 的 Cloudflare 边缘 IP，请求没有直连家庭源站，因此 Access 仍可生效：

| 项目 | 状态 | 证据与边界 |
|---|---|---|
| Homeland NPM 转发 | 已恢复并完成匿名边界复验 | 公网 `/home/` 返回 200、`/home/manage` 返回 Access 302、公开 POST 返回 403；合法 Writer 写入成功仍需使用业主真实登录态验收 |
| Homeland 容器 | 已核对 | `docker compose ps` 显示 `healthy`，只有容器内 `3000/tcp`，无宿主机端口发布 |
| 公网入口策略 | 灰云自选 CF IP + Access | 公开主页可查看汇总、账单及付款明细；新增、修改、删除、导出位于 `/home/manage/*` |
| 自选 IP | 已恢复生产使用 | 2026-08-30 实测 DNS 指向自选 CF IP，公网响应包含 `CF-Ray`，管理区返回 Access 登录 302，匿名写入返回 403；自选 IP 的持续测速、自动更新与故障回退仍需独立维护 |
| Tailscale | 仍在运行/待核对依赖 | NAS 仍观察到 `tailscale0` 与 `100.106.3.86/32`；“从目标架构移除”尚未实施 |

以下内网阅读命令仅作为公网故障时的临时运维备用（容器 IP 可在重建后改变，使用前重新查询）：

```powershell
ssh -N `
  -L 127.0.0.1:33000:172.20.0.2:3000 `
  yao@192.168.50.33
```

浏览器访问 `http://127.0.0.1:33000/home/docs`。这是临时运维通道，不是未来公网架构。

### 文档分工

| 文档 | 作用 | 是否定义现行架构 |
|---|---|---|
| 本文 | 现状、最终拓扑、信任边界、执行优先级和上线门槛 | **是** |
| [实验项目公开读与登录写方案](../archive/network/家庭NAS实验项目隔离沙箱与共享方案.md) | 历史方案归档（含已废弃的双 hostname 设计） | 否，不得作为现行实施依据 |
| [本地 Docker 与 NPM 部署记录](../operations/本地Docker与NPM部署记录.md) | 已做过的 NPM/Compose 操作和历史验证 | 否，它是运维证据 |
| [Cloudflare 入口与优选 IP 实验记录](../archive/network/Cloudflare入口与优选IP实验记录.md) | 性能样本、Worker PoC、失败分支与历史方案 | 否，不得用历史结论覆盖本文 |
| [NAS 运维记录](../operations/运维记录.md) | 端口、监控、备份和历史待办 | 否，未复验记录不等于现网事实 |

## 一、评审结论

整体方向合理，适合当前家庭场景，但**设计目标尚未全部落地**，不能把本文目标架构当作现网完成状态。

合理之处：

1. 公网只保留一个 HTTPS 业务入口，由 Nginx Proxy Manager（NPM）终止 TLS；运维面默认仅走 LAN，确需远程运维时使用自建 WireGuard，不再依赖 Tailscale 控制面。
2. 按数据面和客户端能力选择 Cloudflare 或直连：Immich/Jellyfin 使用 DNS-only HTTPS 反代，轻量 Web 使用 Cloudflare，不强迫原生客户端共用浏览器认证层。
3. 目标网络采用“每应用独立 front 网络、数据库独立 back 网络”，能缩小应用失陷后的横向访问面。
4. 少量路径型项目直接由 NPM 路由，不额外增加一层通用 nginx；只有路由规则明显失控时才引入内部网关。
5. 把恢复演练、更新回滚和密钥管理视为安全边界，而不是只关注端口隐藏。
6. 不追求“所有流量必须经过Cloudflare”的表面一致性：在已有公网 IP 的前提下，媒体数据面直接进入 NPM；Cloudflare 只保护适合其套餐与认证模型的轻量 Web。

当前主要缺口：

1. 仓库中的 Homeland Compose 仍同时加入 `common_network` 与共享 `nas-net`，尚未达到独立网络目标。
2. Homeland 已把写接口和导出迁入单 hostname 的 `/manage/*`，并实现统一 Access JWT 与 Origin 校验；真实 Access 配置已由业主注入且橙云入口已恢复。匿名、伪造令牌和源站绕过路径已经复验；合法 Writer 写入及错误 Origin 拒绝仍需使用业主真实登录态完成最终验收。
3. `origin-home` 已跑通 Worker 回源，但部署记录明确写明尚未配置 Worker Secret header；若把 Worker 当安全入口，源站仍可被绕过。
4. 运维记录称路由器仅转发 `8443/TCP`、UPnP/DMZ/OpenNAT 已关闭、IPv6 防火墙已启用，但本次没有新的外网 IPv4/IPv6 扫描证据，需重新验收。
5. 已有 restic / 异地复制备份链记录，但 Homeland、NPM、Immich 的实际恢复演练证据仍需补齐。
6. NAS 运维记录曾写入疑似 Uptime Kuma Push Token；本次已从当前文档树脱敏，但 Git 历史仍保留旧值，必须人工轮换。
7. Immich/Jellyfin 直连公网的新决策尚未完成端到端验收：Immich 已有 DNS-only/NPM 记录，Jellyfin 仍需建立独立反代入口，并验证登录、上传、Range/HLS、WebSocket、限流与日志脱敏。
8. 媒体 DNS-only 记录会主动公开家庭公网 IP；因此不能再宣称家庭 Origin IP 已隐藏。Cloudflare hostname 只有在源站强制验证 per-hostname AOP、Access JWT 或回源 Secret 时，才能阻止利用该 IP 绕过 Cloudflare。
9. Tailscale 是否仍在 NAS、路由或客户端运行尚未重新连接现网核对；“移除 Tailscale”目前是目标架构，不是已完成状态。

## 二、证据口径与当前状态

状态含义：`已核对`表示本次能从仓库直接确认；`运维记录`表示历史记录声称完成但本次未重新连接 NAS 验证；`缺口`表示本次直接确认尚未满足；`待验收`表示没有足够证据下结论。

| 项目 | 状态 | 证据与判断 |
|---|---|---|
| Homeland 不发布宿主机端口 | 已核对 | 根目录 `docker-compose.yml` 没有 `ports`，仅通过 Docker 网络供 NPM 访问 |
| Homeland 以非 root UID 运行 | 已核对 | Compose 使用 `user: "1001:1001"`；镜像运行阶段也定义非 root 用户 |
| 文档只读、SQLite 单独持久化 | 已核对 | `docs:/app/docs:ro`；`data:/app/data` 可写 |
| Homeland 健康检查 | 已核对 | 镜像对 `http://127.0.0.1:3000/home` 执行健康检查 |
| Homeland 网络隔离 | 缺口 | 仍加入 `common_network` 与共享 `nas-net` |
| Homeland 写接口鉴权 | 已核对 | 已有全局 middleware、`jose` JWKS/JWT 验签、精确 audience/issuer、CSRF Origin header、`/manage/*` 写路由和静态边界检查；匿名/伪 Token/源站绕过已实测失败。2026-08-31 经 NPM API 移除 `origin-home` 的 `/home` 只读 `limit_except` 后，Access Writer 的真实 DELETE 全链路返回 200，数据库记录同步消失；全部现有客户端写操作均走统一 `manageFetch`，并增加静态回归检查。此状态不代表 Worker 回源防绕过缺口已经解决 |
| Homeland 框架安全版本 | 已核对 | 2026-08-30 已由存在官方安全告警的 Next.js 14.2.18 升级到 Active LTS 16.3.3；依据 [Next.js 2026-08 安全发布](https://nextjs.org/blog)（访问日期：2026-08-30）。Fortune Sheet 间接依赖 `uuid<11.1.1` 的中危告警无上游修复，需跟踪替换/升级 |
| NPM 管理端口仅绑定 LAN | 运维记录 | 记录为 `192.168.50.33:81:81`；同网络容器仍可访问 `npm:81`，这是已接受但需保留的剩余风险 |
| 公网 IPv4 仅转发 8443 | 运维记录 | 记录称 AX86U 仅保留 `8443 → 192.168.50.33:8443`，本次未做外网复扫 |
| UPnP / DMZ / OpenNAT 关闭 | 运维记录 | 本次未重新取得路由器截图或导出 |
| IPv6 入站默认拒绝 | 待验收 | 记录称 IPv6 防火墙已开；仍需从外网验证管理端口均不可达 |
| 业务容器取消直接端口发布 | 运维记录 | Immich、Navidrome、Kavita、Seerr 已记录为只经 NPM 访问 |
| Worker 回源防绕过 | 缺口 | `origin-home:8443` 无 Secret 时仍返回 200 的记录尚未被后续验收覆盖 |
| 备份链 | 运维记录 | restic → 本地镜像 / secondary / OneDrive，配置由 Git 管理 |
| 恢复演练 | 待验收 | 未观察到 Homeland SQLite、NPM 与 Immich 数据库的完整恢复记录 |

## 三、目标架构

```text
公网浏览器 / 原生客户端
        │
        ├─ Cloudflare 橙云 443 → 回源家庭 8443
        │    适合轻量 Web；是否使用以客户端兼容和实测性能为准
        │
        └─ Immich / Jellyfin DNS-only HTTPS :8443
             原生客户端直达；使用应用自身认证
                         │
家庭路由器：只转发 8443/TCP，关闭 UPnP / DMZ / OpenNAT
                         │
                         ▼
                        NPM
       ┌─────────────────┼──────────────────┐
       │                 │                  │
homeland-front      immich-front        media-front
       │                 │                  │
 Homeland          Immich Server       媒体应用前端
                         │
                    immich-back
                     ├─ PostgreSQL
                     └─ Redis

LAN → NPM:81、SSH、Arr、qBittorrent、Dozzle、监控、SMB
可选自建 WireGuard → 仅在确需外部运维时接入上述 LAN 管理面

匿名访客 → 同一 hostname → `/home/*` 公共 GET/HEAD
受邀写入者 → 同一 hostname → `/home/manage/*`（Cloudflare Access）
                                  └→ 服务端逐请求验证 JWT；写入再验 Origin
```

核心边界：

1. NPM 是生产服务的统一公网反向代理；每个实验应用使用一个 hostname，Cloudflare Access 只保护其固定 `/manage/*` 子树，应用本身仍须逐请求验证 Writer 身份。
2. NPM 可以加入多个窄 front 网络；每个 front 网络只放 NPM 与该应用入口。
3. 数据库、Redis 和内部任务容器只加入应用自己的 back 网络，不能与 NPM 同网。
4. 后端服务默认不发布宿主机端口；确需 LAN 访问时只绑定 NAS 的 LAN 地址。
5. NPM 被攻破后仍可访问其加入的所有 front 网络，因此 NPM 必须及时更新、使用强管理凭据并备份配置；网络拆分不能消除入口代理本身的高信任属性。
6. Immich/Jellyfin 与 Cloudflare hostname 可以共用 NPM 的 `8443` 监听，但策略必须按 SNI/Host 分开：媒体 hostname 接受普通公网 TLS，Cloudflare hostname 要求 AOP/Secret/JWT，未知 SNI/Host 直接拒绝。

Docker 官方说明，同一 user-defined bridge 网络中的容器默认可互通；隔离效果来自缩小网络成员范围，而不是给大共享网络换名字。[Docker bridge 网络](https://docs.docker.com/engine/network/drivers/bridge/)

## 四、服务入口与认证

| 服务类型 | 示例 | 推荐入口 | 认证与约束 |
|---|---|---|---|
| 实验 Web 与写接口 | Homeland、Stock | 单 hostname：公开路径匿名读，`/manage/*` 登录写 | 不做复杂角色：公开区只有无副作用 GET/HEAD；管理区逐请求验证 Writer JWT，写入再验 Origin/CSRF；导出也归管理区 |
| 照片原生客户端 | Immich | DNS-only 独立 hostname `:8443` → NPM → Immich | 使用 Immich 自身账号；不能挂在 `/immich` 子路径；NPM 允许大文件、关闭请求缓冲并配置长超时；不直接发布 2283 |
| 媒体客户端 | Navidrome、Kavita、Seerr | Seerr 等轻量控制面可走 Cloudflare；音频、漫画等批量内容若需公网则走 DNS-only HTTPS，否则仅 LAN | 使用应用自身认证；启用额外代理认证前先验证客户端、OPDS 和回调兼容性 |
| 高带宽串流 | Jellyfin | DNS-only 独立 hostname `:8443` → NPM → Jellyfin | 使用普通非管理员播放账号；只为所需账号允许远程连接；NPM 保留 Range/WebSocket，Jellyfin 只信任 NPM 为 Known Proxy；不得记录含 `api_key` 的完整 URL |
| 运维管理面 | NPM:81、SSH、Arr、qBittorrent、Dozzle、Beszel | 默认仅 LAN；确需远程管理时可选路由器/NAS自建 WireGuard | 不公开 DNS，不做公网端口转发；WireGuard 不是媒体分享前置条件，也不依赖第三方协调服务 |
| 数据层 | PostgreSQL、Redis、SQLite | 私有 back 网络或本地文件挂载 | 不经 NPM、不发布宿主机端口；按最小权限授权并纳入一致性备份 |
| VLESS 入口 | `orchomev` / `orc` | 独立协议链路 | 与 Homeland/Immich 的 HTTP 指标、健康检查、Worker 和密钥完全分开维护 |

Immich 官方要求使用独立域名根路径，正确转发 `Host`、`X-Real-IP`、`X-Forwarded-Proto`、`X-Forwarded-For`，并为大文件上传与长连接配置足够限制；官方也明确警告不要把 2283 明文端口直接转发到互联网。[Immich Reverse Proxy](https://docs.immich.app/administration/reverse-proxy/) · [Immich Remote Access](https://docs.immich.app/guides/remote-access/)

Jellyfin 官方把“端口直接转发到应用”列为不推荐方案，把 HTTPS 反向代理列为标准外网方案；反代必须配置 Known Proxies 与 WebSocket，并避免在日志中泄漏 URL 查询参数里的 `api_key`。[Jellyfin Networking](https://jellyfin.org/docs/general/post-install/networking/) · [Jellyfin Reverse Proxy](https://jellyfin.org/docs/general/post-install/networking/reverse-proxy/)

### 直接公网开放的准入标准

项目社区规模和活跃度只能作为“是否有人持续修补、是否容易获得公告”的加分项，不能作为公网准入条件。Immich 官方即使在活跃开发状态下，仍明确提示严重漏洞不能排除。服务只有同时满足以下条件才进入直连名单：

1. 原生客户端或大文件数据面确实不适合 Cloudflare/Access；
2. 官方明确支持 HTTPS 反向代理和远程访问；
3. 有独立账号体系，能禁止匿名访问并限制远程用户；
4. 后端端口、数据库、管理面不直接发布，只能经 NPM 到达前端；
5. 能持续收到更新通知、完成回归后及时升级，并有可恢复备份；
6. 能接受家庭公网 IP 暴露、应用零日攻击和上行带宽被打满的剩余风险。

按这个标准，Immich/Jellyfin 可以接受直连；Homeland/Stock 等早期项目不因“代码小”而直连，继续让 Cloudflare 过滤入口。Navidrome/Kavita 的判断取决于是否承载大量媒体数据，而不是项目知名度。

### Homeland / Stock 的权限口径

1. 不维护复杂角色：匿名用户只有读能力；精确允许名单中的登录用户都是 Writer，彼此权限相同。
2. 每个应用只使用一个 hostname，固定 `/manage/*` 为管理子树；Cloudflare Access 保护该路径，应用对其中每个请求验证 Access JWT，不能只隐藏编辑按钮。
3. 所有写接口、上传、批量导入/导出和任务触发必须迁入 `/manage/*`；公开 API 只保留无副作用读取，禁止依赖 Access 按 HTTP 方法分流。
4. 写操作不得使用 GET，并须校验 CSRF 来源；当前 Worker 回源会把 Host 改为 `origin-home`，Homeland 使用统一客户端写请求携带自定义 CSRF Origin header，服务端将其精确匹配公开 origin，且不开放跨域预检。公共导出、存储型 XSS、Writer 误删和源站绕过仍需单独处理。
5. 公开响应等同公开信息；真实财务数据、隐私、凭据和唯一副本不得进入这类实验项目。

### Cloudflare Access 与应用鉴权的最终链路

Access 是 Homeland 的外部身份提供者，不是应用授权的替代品。现行设计固定为：

```text
匿名用户 → hostname 公共路径 → GET/HEAD → Homeland
                                  └→ 任何写方法 → 403

Writer → 同一 hostname `/manage/*` → Cloudflare Access 精确邮箱 Allow
                                  → Cf-Access-Jwt-Assertion
                                  → NPM 原样转发
                                  → Homeland 验证签名 + iss + aud + exp/nbf
                                  → 写请求校验精确 Origin/CSRF
                                  → 放行管理读取或写入
```

实施约束：

1. `/manage/*` 是单 hostname 内的路由与用户体验边界；真正的授权边界是 Homeland 的统一 middleware。
2. Homeland 使用 Access Team Domain 的 JWKS 验签，严格匹配本应用独立的 Audience tag。不得只检查 header、Cookie、email 或 Host 存在。
3. NPM 必须保留 Cloudflare 已注入的 assertion header；应用通过密码学验签识别直连 `:8443` 时的伪造 header。
4. 缺少环境变量、JWKS 获取/缓存异常、Token 过期、issuer/audience 不符时一律 fail closed，返回 403。
5. `GET/HEAD/OPTIONS` 必须无副作用；未识别 HTTP 方法与新增写入通道默认拒绝。
6. 导出固定属于管理操作，当前入口为 `/manage/api/export`；原公开 `/api/export` 已删除。
7. 运行时只注入 `CF_ACCESS_TEAM_DOMAIN`、`CF_ACCESS_AUD`、`CF_ACCESS_ALLOWED_ORIGIN`。应用验签不需要 Cloudflare API Token，禁止把 API Token/Key、Service Token Secret 或 Tunnel Token 注入应用容器、源码、日志或前端 bundle。

跨项目强制约束原同步于 Homeland 根目录 `AGENTS.md`；迁移后的可复制规则见 [AGENTS.access-auth.md](../security/AGENTS.access-auth.md)，实施手册见 [`CLOUDFLARE_ACCESS_IMPLEMENTATION.md`](../security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md) 作为执行手册。本文是 Homeland 唯一现行架构依据。

## 五、Cloudflare 与直连边界

Cloudflare 不是全局必选项，也不能替代应用认证：

1. **橙云记录**：客户端流量经 Cloudflare，可使用 WAF、缓存和规则；Origin Rule 可让访客使用标准 443、由 Cloudflare 回源家庭 8443。大陆链路性能必须实测，不能从产品能力推断延迟。
2. **DNS-only 直连**：链路简单、性能和客户端兼容性通常更可控，但公网 IP 与 8443 源站直接暴露，必须按互联网入口加固。
3. **Cloudflare Tunnel**：当前不纳入目标架构；它能关闭家庭公网入站并隐藏源站，但本项目已有公网 IP，且媒体上传与套餐边界仍然存在。
4. **Worker 回源**：只有源站校验 Worker Secret、mTLS/AOP 或等价不可伪造条件后，Worker 才能成为安全边界。随机 hostname 只能减少噪声，不能防绕过。
5. **灰云自选 Cloudflare IP**：灰云 DNS 指向 Cloudflare 边缘 IP 不等于绕过 Cloudflare；只要 TLS SNI/Host 被该边缘链路正确承载，请求仍可获得 Access。Homeland 当前采用此方案，但必须持续验证 `CF-Ray`、Access 跳转、TLS、可用性及自动回退，不得只验证 HTTP 200。
6. **媒体数据面**：Worker支持流式响应、Range与WebSocket，不代表适合所有媒体服务。Immich上传受账户请求体上限约束；Jellyfin、Navidrome、Kavita等大量视频/音频/图片还需满足Cloudflare当前付费服务条款。Tunnel隐藏源站但不绕过HTTP边缘限制。
7. **自选IP与Origin隐藏**：普通橙云hostname可由客户端本地DNS覆盖到候选Cloudflare IP，TLS SNI/Host仍用域名，通常不需要Worker。Immich/Jellyfin 的 DNS-only 记录会公开家庭 IP，这是当前主动接受的代价；Cloudflare 对轻量应用仍可提供保护，但不再提供源站地址保密。
8. **防绕过**：攻击者可把 Cloudflare hostname 的 SNI/Host 直接发往已公开的家庭 IP。橙云 DNS 和 Access 页面本身不能阻止这种绕过；源站必须对这些 hostname 校验 per-hostname AOP、Access JWT 或独立回源 Secret。Cloudflare 提醒其全局 AOP 证书并非账户专属，因此本项目优先自有 per-hostname 证书或叠加应用层验证。

当前决策：

- 根域/轻量 Web 优先保留已经验证的标准 Cloudflare 入口；性能不满足时先与 DNS-only `:8443` 做受控对照。
- Homeland/Stock 各使用一个 hostname：公共路径匿名读，`/manage/*` 由 Access 精确邮箱保护；应用仍验证 Access JWT，防止从 `:8443` 或备用入口绕过。Homeland 当前使用灰云 DNS 指向自选 Cloudflare IP，并以 `CF-Ray` 与 Access 登录跳转作为仍经过 Cloudflare 的运行验收信号。
- Immich完整同步和Jellyfin播放使用 DNS-only 独立 hostname，经家庭 `:8443` 直接进入 NPM；朋友直接使用各自应用账号，不安装 VPN 客户端。
- Tailscale 从目标架构移除；远程管理默认不开放。只有确需在外运维时，才在 AX86U/NAS 自建 WireGuard，并只放行管理网段。
- 免费Cloudflare Worker只用于轻量Web和Immich/Jellyfin协议PoC，不承担大文件上传或完整视频生产数据面；透明Worker不能把超过账户限制的单次上传拆分后继续兼容原客户端。
- Cloudflare 优选 IP 已接管 Homeland 生产域名；更新器每次切换候选 IP 后必须至少验证公开 GET、Access 302、TLS 和匿名写入 403，失败时回退到上一可用 IP。单次人工验证不能替代这一自动验收闭环。
- 详细样本、525/520/Schannel/TUN 回环诊断与 PoC 步骤见 [Cloudflare 入口与优选 IP 实验记录](../archive/network/Cloudflare入口与优选IP实验记录.md)。

Cloudflare 官方边界：[DNS Proxy status](https://developers.cloudflare.com/dns/proxy-status/) · [Origin Rules](https://developers.cloudflare.com/rules/origin-rules/) · [支持的代理端口](https://developers.cloudflare.com/fundamentals/reference/network-ports/) · [Authenticated Origin Pulls](https://developers.cloudflare.com/ssl/origin-configuration/authenticated-origin-pull/) · [Workers Routes](https://developers.cloudflare.com/workers/configuration/routing/routes/) · [Workers Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)

完整的媒体协议、Worker流式PoC、100 MB上传边界和Cloudflare服务条款分析见[Cloudflare入口与优选IP实验记录 §10.9](../archive/network/Cloudflare入口与优选IP实验记录.md#109-immich--jellyfin-全量经-cloudflare-与-worker-的边界2026-08-27)。

## 六、强制安全控制

| 控制 | 要求 | 验收重点 |
|---|---|---|
| 公网端口 | 路由器只保留明确需要的 `8443/TCP`；关闭 UPnP/NAT-PMP、DMZ、OpenNAT | 路由器配置证据 + 外网 IPv4 扫描一致 |
| IPv6 | 路由器与主机入站默认拒绝，只放行明确服务 | 从外部 IPv6 网络验证所有管理端口不可达 |
| Docker 发布端口 | 后端不发布；LAN 管理端口绑定 LAN 地址，不用 `0.0.0.0`/`[::]` | `docker ps`、`docker inspect`、`ss -lntup` 三方一致 |
| Docker 网络 | 每应用 front/back 分离，无全栈共享网络 | 反代可访问前端；应用间和 NPM→数据库连接失败 |
| Cloudflare 防绕过 | 轻量 Web 的源站 hostname 强制校验 per-hostname AOP、Access JWT 或回源 Secret；未知 Host 拒绝 | 直连家庭 IP 并伪造目标 SNI/Host 时，请求在 TLS 或应用层失败 |
| 管理面 | NPM:81、SSH、下载器、Arr、监控默认只走 LAN；可选自建 WireGuard只进入管理网段 | 公网 IPv4/IPv6 均不可达；未启用 WireGuard 时没有远程管理路径 |
| 应用认证 | Homeland/Stock 单 hostname 匿名读、`/manage/*` 登录写；管理请求统一验证 Writer JWT，写请求再验证来源 | 匿名 GET 成功，匿名/伪造/过期/错误 audience/跨站写入失败，登录写入正常 |
| Secret | 最小权限、分服务 Token、Secret/只读文件注入，不进 Git/日志/备份明文 | secret 扫描无真实凭据；泄漏凭据完成轮换 |
| 镜像更新 | 固定已验证版本或 digest；通知后人工升级，保留回滚 | 重建版本可复现；更新后健康检查和业务 smoke test |
| 容器权限 | 非 root、`no-new-privileges`、最小 capabilities；只读文件系统按兼容性逐项实施 | 逐服务验证，不因“一次性加固”破坏写入和升级 |
| Docker socket | 不把原始 `/var/run/docker.sock` 广泛挂给监控容器 | 改用只读 socket proxy，并只开放必需 API |
| 日志 | 轮转；不记录 Authorization、Cookie、Token、请求正文或私密查询参数 | 抽查 NPM、Worker、应用和监控日志 |
| 备份恢复 | 配置、SQLite、PostgreSQL、照片元数据分层备份，至少一份异机/云端副本 | 必须完成真实恢复，不能只确认备份文件存在 |

Docker 发布端口会影响主机防火墙路径，不能只看 UFW 状态推断容器不可达；应结合 Docker 的端口与防火墙规则做外部验证。[Docker packet filtering](https://docs.docker.com/engine/network/packet-filtering-firewalls/) · [Docker port publishing](https://docs.docker.com/engine/network/port-publishing/)

## 七、执行优先级

### Homeland 重新开放的硬门槛

下列项目未全部提供实测证据前，Homeland NPM 转发保持关闭：

- [x] 全局 middleware 将公开路径的 POST/PUT/PATCH/DELETE 拒绝为 403；静态检查确认写路由只存在于 `/manage/*`。
- [ ] 单 hostname `/manage/*` 的 Access Application 使用精确邮箱 Allow，Homeland 以真实 assertion JWT 完成成功/失败矩阵。
- [ ] 写方法的精确 Origin/CSRF 完成真实入口验证；公开 GET/HEAD 无副作用已完成代码审计，仍需外网回归。
- [ ] 导出已迁入 `/manage/api/export`；Markdown `rehypeRaw`/XSS 策略仍需明确并实施。
- [ ] Homeland 移入独立 `homeland-front`，无法访问 NPM:81、媒体栈、下载器、数据库和 Docker Socket。
- [ ] 直连家庭 IP `:8443` 并伪造 Homeland SNI/Host/header 的写请求失败；未知 Host 返回 404/TLS 失败。
- [ ] 本机 `docker compose ps`、健康检查、匿名读、匿名/伪 Token 拒绝已通过；仍需从外网完成合法登录写、过期/错误 issuer/audience 与跨站写入测试。
- [ ] NPM 显式拒绝 `TRACE`/`CONNECT` 为 403/405；Next.js 会在应用入口前处理这两个方法，本机实测分别为 500/断开，不能把应用 middleware 当作该边界。
- [ ] Homeland SQLite 在隔离目录/测试栈中完成一次真实恢复。

### P0：立即闭环

- [ ] **轮换疑似已泄漏的 Uptime Kuma Push Token**；当前文档脱敏不能撤销 Git 历史与既有副本中的暴露。
  - 验收：旧 Token 失效；新 Token 只存在于受控配置；Push 监控恢复正常。
- [ ] **重验公网边界**：导出/截图路由器转发、UPnP/DMZ/OpenNAT 与 IPv6 防火墙配置，并从家庭网络外扫描 IPv4/IPv6。
  - 验收：只有预期业务入口可达；NPM:81、SSH、Arr、qBittorrent、Dozzle、监控、数据库均不可达。
- [ ] **上线 Immich/Jellyfin 直连入口**：保留 Immich DNS-only/NPM 入口并新增 Jellyfin 独立 DNS-only/NPM hostname；两者只经 `8443`，不发布 2283/8096。
  - 验收：家庭网络外完成登录、Immich 大文件上传与后台同步、Jellyfin Direct Play/转码/拖动、WebSocket；错误密码限流有效，日志不含 Token/API Key。
- [ ] **退出 Tailscale**：先确认没有监控、备份、反代或 SSH 仍依赖其地址/DNS，再停用服务并移除客户端；检查宿主机和容器 DNS，不保留 `100.100.100.100`。
  - 验收：Tailscale 服务/接口/路由均不存在；必要时重建继承旧 DNS 的容器；公网业务与 LAN 管理面回归正常。
- [ ] **完成 Homeland 匿名读、登录写联调**：代码侧已实现单 hostname `/manage/*`、统一 Writer middleware、Access JWT 与精确 Origin；继续保持 NPM 公网入口关闭，待填写不入库 `.env` 并配置 Access path policy。
  - 验收：当前匿名 GET 正常、匿名/伪造写入 403；尚需合法 Writer、过期/错误 issuer/audience、跨站 Origin 和直连源站测试。
- [ ] **确认 Worker 是否承担生产入口**；若承担，配置源站 Secret/mTLS 并阻断绕过；若只是 PoC，明确下线或隔离测试 hostname。
  - 验收：生产 Worker入口正常；无合法回源凭据直连 `origin-home:8443` 返回403或TLS失败。
- [ ] **完成一次恢复演练**：至少覆盖 Homeland SQLite、NPM 配置/证书和 Immich PostgreSQL 元数据。
  - 验收：在隔离临时目录或测试栈恢复并读取真实数据，留下日期、版本、耗时和失败项记录。

### P1：缩小横向移动面

- [ ] 为 Homeland 创建独立 `homeland-front`，只连接 NPM 与 Homeland；移除 `common_network` 和共享 `nas-net`，保留 docs只读与 SQLite 明确写目录。
- [ ] 审查 `/api/export` 是否应匿名、所有 GET 是否无副作用，以及 `rehypeRaw` 在内容可写时的存储型 XSS 风险。
- [ ] 将 Immich 拆为 `immich-front` 与 `immich-back`；NPM 不能访问 PostgreSQL/Redis。
- [ ] 按真实调用关系拆分媒体、Arr、下载器与监控网络；不保留“所有服务都能互通”的通用网络。
- [ ] 逐个取消或收紧 `0.0.0.0/[::]` 管理端口；保留 LAN 可用性；若启用自建 WireGuard，只放行其管理网段。
- [ ] 评估 NPM 管理端口在共享网络内可达的已知风险；若继续接受，记录强密码、MFA可用性、升级和审计补偿措施。

### P2：提高可维护性

- [ ] 固定 NPM、Homeland 和数据库的已验证版本；记录升级与回滚步骤。
- [ ] DIUN 只通知，不自动替换公网入口镜像；定义高危补丁与普通更新窗口。
- [ ] 通过 socket proxy 收紧 DIUN、Uptime Kuma、Dozzle、Beszel Agent 对 Docker API 的访问。
- [ ] 为公网页面/API、DDNS、证书到期、备份任务和容器健康建立分层监控，避免“容器 Up 等于业务正常”。
- [ ] 为 NPM、Worker、应用和系统日志设置轮转与敏感字段审查。

### P3：有明确收益再做

- [ ] 实验项目开始运行第三方插件、用户代码或高风险解析器时，再引入 rootless Docker、gVisor 或完整 VM；当前自研 Web 项目不默认承担该复杂度。
- [ ] 路径项目增长到 NPM Custom Locations 难以审计时，再引入 `lab_gateway`。
- [ ] Writer 数量增长、需要多角色或更细审计时，再在二元 Writer 模型上增加成熟应用授权，不预先建设复杂 RBAC。
- [ ] 对所有 Cloudflare 生产 hostname 实施 per-hostname AOP/mTLS、Access JWT 或独立回源 Secret；媒体直连 hostname 不套用该限制。
- [ ] 只有 Cloudflare 优选 PoC 连续满足多网络、P95、失败率和自动回退门槛后，才讨论生产化。

## 八、验收清单

### 公网与管理面

- [ ] 路由器端口表只有预期 `8443/TCP` 和另行书面确认的非 Web 端口
- [ ] UPnP/NAT-PMP、DMZ、OpenNAT 均关闭
- [ ] 外网 IPv4 与 IPv6 扫描结果和路由器/主机配置一致
- [ ] NPM `81`、SSH、Arr、下载器、Dozzle、监控、SMB、数据库均不能从公网访问
- [ ] LAN 运维路径正常；若没有明确远程运维需求，则不存在任何公网管理路径
- [ ] Tailscale 服务、接口、路由和 DNS 残留均已清除，容器外部解析正常

### 应用与反代

- [ ] Homeland 单 hostname 的公开页面和 GET API 可匿名访问，所有公开不安全方法未登录时返回 403
- [ ] `/manage/*` 的合法 Writer 可写；伪造/过期 JWT、错误 audience/issuer 和跨站 Origin 均失败
- [ ] 所有公开 GET/HEAD 无副作用，导出仅存在于 `/manage/api/export`
- [ ] 可写内容不会在 Writer 页面形成存储型 XSS
- [ ] Immich 独立 hostname、上传、后台同步、视频、WebSocket 与深链接正常
- [ ] Jellyfin 独立 hostname、登录、Direct Play、转码、拖动与 WebSocket 正常；仅普通播放用户允许远程访问
- [ ] Worker生产入口启用时，直接绕过 Worker 的回源请求失败
- [ ] 未知 Host 和未知路径返回404，不落到任意应用首页

### Docker 隔离

- [ ] 公网只到 NPM；应用前端与数据库均无不必要宿主机端口
- [ ] Homeland 不能解析/连接 Immich、Arr、下载器或数据库
- [ ] NPM 可访问各应用前端，但不能访问 PostgreSQL/Redis
- [ ] 每个网络的成员与业务调用关系一致，不存在无边界共享网络
- [ ] `docker compose ps` 健康，重启后配置和数据持久化正常

### 凭据、更新与恢复

- [ ] 已轮换疑似泄漏的监控 Token，旧 Token 验证失效
- [ ] Git、Compose config、容器环境与日志中没有真实明文凭据
- [ ] 镜像版本可复现，升级后完成页面、API、上传、反代和回滚测试
- [ ] Homeland、NPM、Immich 各完成一次实际恢复演练
- [ ] 备份副本至少有一份不与 NAS 主数据同故障域

## 九、来源

本地证据：

- [`../docker-compose.yml`](../../../homeland/docker-compose.yml)（2026-08-26 查阅）
- [`../frontend/Dockerfile`](../../../homeland/frontend/Dockerfile)（2026-08-26 查阅）
- [NAS 运维记录](../operations/运维记录.md)（2026-08-26 查阅；其中完成状态未在本轮远程复验）
- [本地 Docker 与 NPM 部署记录](../operations/本地Docker与NPM部署记录.md)（2026-08-26 查阅）
- [Cloudflare 入口与优选 IP 实验记录](../archive/network/Cloudflare入口与优选IP实验记录.md)（历史样本日期 2026-08-23 至 2026-08-24）

官方资料（2026-08-26 初查，2026-08-27 复核）：

- [Docker bridge network](https://docs.docker.com/engine/network/drivers/bridge/)
- [Docker packet filtering and firewalls](https://docs.docker.com/engine/network/packet-filtering-firewalls/)
- [Docker port publishing and mapping](https://docs.docker.com/engine/network/port-publishing/)
- [Cloudflare DNS proxy status](https://developers.cloudflare.com/dns/proxy-status/)
- [Cloudflare Origin Rules](https://developers.cloudflare.com/rules/origin-rules/)
- [Cloudflare network ports](https://developers.cloudflare.com/fundamentals/reference/network-ports/)
- [Cloudflare Authenticated Origin Pulls](https://developers.cloudflare.com/ssl/origin-configuration/authenticated-origin-pull/)
- [Cloudflare Protect your origin server](https://developers.cloudflare.com/fundamentals/security/protect-your-origin-server/)
- [Cloudflare Access self-hosted public application](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/)
- [Cloudflare Workers Routes](https://developers.cloudflare.com/workers/configuration/routing/routes/)
- [Cloudflare Workers Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)
- [Cloudflare Access application paths](https://developers.cloudflare.com/cloudflare-one/access-controls/policies/app-paths/)
- [Cloudflare Access JWT validation](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/)
- [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- [Immich Reverse Proxy](https://docs.immich.app/administration/reverse-proxy/)
- [Immich Remote Access](https://docs.immich.app/guides/remote-access/)
- [Immich FAQ：Cloudflare Tunnel文件大小](https://docs.immich.app/FAQ/)
- [Jellyfin Reverse Proxy](https://jellyfin.org/docs/general/post-install/networking/reverse-proxy/)
- [Jellyfin Networking](https://jellyfin.org/docs/general/post-install/networking/)
- [WireGuard Quick Start](https://www.wireguard.com/quickstart/)
- [Cloudflare Workers限制](https://developers.cloudflare.com/workers/platform/limits/)
- [Cloudflare Service-Specific Terms](https://www.cloudflare.com/service-specific-terms-application-services/)
- [Next.js 2026-08 安全发布与受支持版本](https://nextjs.org/blog)（2026-08-30 查阅）

## 修订日志

| 日期 | 版本 | 变更摘要 |
|---|---|---|
| 2026-09-10 | v4.8 | 评估公网 key-only SSH 的可接受条件、非 root 实际权限与手机动态出口白名单，比较固定跳板和动态放行；仅记录备选，未开放公网 SSH |
| 2026-09-10 | v4.7 | 按 Android 优先需求评估 SSH/tmux 增强与 App Server 结构化客户端，列出有假设的投入区间、收益、协议/后台/迁移风险和原型止损点；未启动开发 |
| 2026-09-10 | v4.6 | 核对 Codex 官方实验 Remote 与本地 CLI 0.153.0，区分手机配对、App Server 与既有 tmux；补充 WireGuard→SSH→tmux 推荐路径、最小权限、操作示例及待验收边界，未变更现网 |
| 2026-09-10 | v4.5 | 增加仓库与官方资料架构复评，区分历史成功记录与过期待办；明确公开 GET 源站防绕过、Docker 到宿主机/LAN 管控、媒体 split-DNS 端口约束、优选回退与远程维护依赖；仅更新评估，未改现网 |
| 2026-09-01 | v4.4 | 直连上线前核对媒体服务版本：Jellyfin 10.11.8→10.11.11、Navidrome 0.61.2→0.63.2 并完成容器/HTTP验证；Immich 已是最新 v3.1.0，未无意义重建。记录 Immich 单资产元数据任务错误及 Jellyfin 仍向宿主机发布 8096/8920、1900/7359 的上线前缺口 |
| 2026-08-31 | v4.3 | 更正删除失败根因：NPM Proxy Host 7 的 `/home` 自定义位置仍有只读 `limit_except`，请求在应用前即被 403；通过 NPM API 仅移除该限制，保留 Access JWT 透传。真实 Writer DELETE 返回 200 且数据库记录消失；审计全部客户端写操作并增加禁止管理 API 使用原生 `fetch` 的回归检查 |
| 2026-08-31 | v4.2 | 根据真实删除连续 403 的 NPM 证据，确认 Access JWT 正常、失败位于 Worker 回源后的 CSRF 来源校验；改为统一客户端自定义 CSRF Origin header + 服务端精确匹配允许 origin，覆盖账单、付款与表格写入，待真实 Writer 复验成功路径 |
| 2026-08-30 | v4.1 | 将实验 Web 鉴权收敛为单 hostname + 固定 `/manage/*`；记录 Homeland 已实现服务端 Access JWT/Origin 验证、公开写入拒绝、导出迁移、`.env` 配置边界与 Next.js 16.3.3 安全升级，并明确合法 JWT/NPM 外网联调及 Fortune Sheet 间接依赖告警仍未完成 |
| 2026-08-30 | v4.0 | 综合 Access、NPM、Cloudflare 实验、Docker 隔离和 NAS 运维文档，将本文设为唯一现行网络架构；记录 Homeland NPM 临时关闭、SSH 阅读和 Tailscale 尚存的真实状态；固化 Access 与应用鉴权链路及重新开放硬门槛 |
| 2026-08-27 | v3.5 | 采用“Immich/Jellyfin DNS-only HTTPS直连、轻量Web经Cloudflare”的分流；从目标架构移除Tailscale/VPS主链路，远程运维默认LAN-only、可选自建WireGuard；明确项目热度不是公网准入条件，并增加Cloudflare源站防绕过与直连媒体验收项 |
| 2026-08-27 | v3.4 | 明确“全部藏在Cloudflare后”不是绝对安全目标；Immich完整上传与Jellyfin视频默认改走Tailscale或VPS回源隧道，免费Worker仅保留协议PoC；补充100 MB请求体、Range/WebSocket和媒体付费服务条款边界 |
| 2026-08-26 | v3.3 | 采用“匿名可读、登录后统一可写”的简化模型：公共/编辑双 hostname、Access JWT、统一方法鉴权与 Origin/CSRF；rootless/gVisor降为高风险负载可选加固 |
| 2026-08-26 | v3.2 | 按需求将 Homeland/Stock 默认隔离方案从完整 VM 修正为 rootless Docker + gVisor；允许实验应用无内部读写权限，并区分朋友入口门禁与完全公开风险 |
| 2026-08-26 | v3.1 | 增加实验项目独立 Incus/KVM 虚拟机决策与配套方案链接，避免自由写入和朋友共享扩大 NAS 生产信任域 |
| 2026-08-26 | v3.0 | 重新评审并精简主文档：区分现状、目标与待验收；保留单层NPM和按服务选入口的总体方向；确认Homeland共享网络、写接口鉴权、Worker回源防绕过和恢复演练缺口；Cloudflare长篇实验迁入运维档案；增加疑似泄漏监控Token的轮换动作 |
| 2026-08-24 | v2.7 | 完成橙云根域与灰云自选Worker首轮对照实测（详细过程已迁入实验记录） |
| 2026-08-23 | v1.0 | 汇总家庭 NAS 安全讨论，形成兼顾大陆性能、客户端兼容和维护复杂度的初版架构 |
