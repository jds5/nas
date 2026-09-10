# 手机接入现有 tmux 会话规划

日期：2026-09-10。目标是在 Android 手机上继续 NAS 上已经运行的会话，包括当前 `nas` 项目的对话。本轮仅做只读核对与规划，没有修改 SSH、VPN、tmux 配置或启动 Codex 远控。

## 旧讨论与本次更新

最新讨论已随 Homeland 提交 `298904133b47fbae0a8be1e0cf922888ce377cf5` 完整迁入，保存在 NAS 提交 `9982ca9` 的 `docs/architecture/家庭NAS网络安全架构.md`，包括 2026-09-10 的远控、Android 客户端和公网 SSH / 动态白名单评估。上一轮精简移除了长篇讨论，本次按实际需求恢复成独立实施规划，不恢复重复全文。

沿用的偏好：手机为 Android，希望改善纯 SSH 终端的体验；自研客户端、公网 SSH 和动态白名单当时没有成为部署决定。本轮优先确保当前运行中会话能原样继续，再决定是否投入聊天式界面。

## 本次现场核对

| 项目 | 只读结果 |
| --- | --- |
| 用户 / 工作目录 | `yao`，`/home/yao/code/nas` |
| 当前 tmux 目标 | session `nas`，window `0`，pane `0`，pane ID `%1` |
| tmux socket | `/tmp/tmux-1000/default` |
| tmux 版本 | 3.5a |
| NAS LAN 地址 | `192.168.50.33` |
| SSH | 监听 IPv4 / IPv6 的 22；未从手机验证可达，也未核验有效认证规则 |
| Tailscale | 接口 `tailscale0` 存在，地址 `100.106.3.86`，进程运行；手机授权、ACL 和连通性未测 |
| Codex | CLI 0.154.0；`remote-control start/stop/pair` 帮助存在 |
| 当前运行形态 | 当前 pane 对应 CLI/TUI 进程，未带 `--remote`；默认共享 daemon 控制 socket 不存在，`daemon version` 无法连接 |
| 现有客户端 | 一个客户端连接 `nas`，窗口尺寸策略为 `latest`；手机接入可能改变终端尺寸，须体验验证 |

核对命令：`tmux display-message`、`list-panes`、`list-clients`、`show-options`，`ip -brief address`，`ss -ltn`，Codex 版本与帮助、daemon 版本查询，以及进程角色检查。没有抓取其他会话的屏幕内容或读取凭据。

## 第一阶段：局域网接回当前对话

```text
Android SSH 客户端 → NAS SSH（yao）→ 原 tmux socket → nas 会话 → 当前 Codex
```

1. 手机与 NAS 同处可互访的家庭网络，在手机 SSH 客户端生成专用密钥，只把公钥加入 NAS 的授权配置；已有可用密钥时核对使用。核对服务器主机指纹。
2. 连接 `yao@192.168.50.33:22`，设置登录后命令 `tmux attach-session -t nas`。若手机已有 OpenSSH 终端，可使用：

   ```bash
   ssh -t yao@192.168.50.33 'tmux attach-session -t nas'
   ```

3. 先只看当前输出，再在当前回合结束后从手机发送一句测试消息，确认电脑上的同一对话收到，避免两端同时输入。
4. 使用 `Ctrl-b d` 脱离，不使用 `exit` 或 kill 命令终止任务；不加 `-d` 强制踢掉电脑客户端。关闭手机连接后，再接入应仍是原会话。
5. 验证中文、多行粘贴、滚动、特殊键、横竖屏和窗口尺寸变化。若存在审批提示，验证能够处理。此阶段不启动第二个 `codex`，不对当前对话执行 `resume` / `fork`。

完成标准：手机可看到并操作同一 pane 中的当前对话；断连后任务仍在，重连没有新建会话。tmux 支持多客户端附着及脱离后保留运行中的程序，见 [官方入门](https://github.com/tmux/tmux/wiki/Getting-Started)（查阅：2026-09-10）。

## 第二阶段：外网接入

长期路径沿用 [网络安全架构](../architecture/家庭NAS网络安全架构.md)：手机 WireGuard → 家庭路由器或 NAS → SSH → tmux。先核对路由器支持、UDP 可达性与手机代理共存，再落实每设备密钥和目的端口限制；完整验证后才调整既有 Tailscale。

如果手机已经加入现有 tailnet，可先验证 Tailscale ACL 与 SSH 连通，用 `100.106.3.86` 做一次外网体验测试；接口存在不代表手机可用，也不意味着重新决定长期依赖 Tailscale。

若明确不愿手机运行 VPN，再选择公网 key-only SSH 或既有固定 IP 跳板；需先核对 sshd 有效配置、补丁、用户权限和 IPv4/IPv6 放行。旧讨论中的动态手机 IP 白名单暂不开发：换网、代理出口与 CGNAT 会增加维护复杂度。现有 NPM HTTP Proxy Host 不承担原生 SSH。

完成标准：从蜂窝网络接回 `nas`；切换 Wi-Fi / 蜂窝后可重连；撤销手机密钥后无法再连；不依赖电脑原来的 SSH 连接持续在线。没有可用外网路径时，第一阶段仍可独立使用。

## 第三阶段：改善手机体验

| 方向 | 当前定位 | 进入条件 |
| --- | --- | --- |
| 现成 SSH 客户端 + 固定启动命令 | 第一版，保留当前会话 | 第一、二阶段验证通过，补齐快捷键和中文输入体验 |
| 官方 ChatGPT Remote | 优先评估的聊天式界面 | 账户、设备与会话归属实测；不能承诺直接接管当前 CLI |
| 专用 Android 客户端 | 现成方式不满足后的开发选项 | 先证明同一执行器的会话可订阅和控制，再做界面 |

[官方 Remote 指南](https://learn.chatgpt.com/docs/remote-connections) 当前描述手机连接 Mac/Windows 桌面主机，桌面再访问 SSH 项目；[CLI 命令参考](https://learn.chatgpt.com/docs/developer-commands?surface=cli#codex-remote-control) 则提供实验性 `remote-control` daemon 与配对命令。这两项不构成“在 Linux 上启动 daemon 就能无缝接管任意既有 tmux”的证明。本机已有命令，但未启动、未配对，当前默认 daemon socket 也不存在。（查阅：2026-09-10。）

若评估自研，先用独立测试会话验证 [App Server](https://learn.chatgpt.com/docs/app-server) 的线程读取、流式事件、`turn/steer`、审批与断线恢复，并验证当前 CLI 的会话能否由同一执行器管理。`thread/resume` 的历史恢复能力不能当成运行中进程接管能力；不得让两个执行器并发运行同一任务。协议端点放在本地 socket/loopback，经受保护的通道访问。（查阅：2026-09-10。）

最小界面范围：会话列表、当前输出、中文输入、发送、审批、断连状态与重连。若当前 CLI 无法无损接入协议端，当前会话继续用 tmux，未来新任务再考虑 App Server；不复用旧工时估算承诺交付。

## 执行顺序与边界

下一步先完成手机 LAN 登录及 `nas` 会话验收，再处理外网路径；聊天式 UI 单独做可行性验证。尚未完成手机登录、VPN/SSH 配置核验、外网测试或官方配对。此次文档提交不表示授权或已经执行任何远控服务部署。
