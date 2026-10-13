# Claude Code 接入手机与网页终端（2026-10-13）

## 目标与结果

安卓“接续”和网页终端原先只认 tmux 前台命令 `codex`、`~/.codex` 记录和 Codex 输入框。本次两端都支持 Claude Code，并提供“智能体”切换，默认 Claude Code：

- **运行中的窗格**：按前台进程逐个识别 `claude` / `codex`，两者都能聊天阅读、发送文字与附件、处理启动菜单；不靠开关强行判定。
- **安卓切换**：决定新建/恢复历史使用的 CLI、默认参数（Claude Code `--dangerously-skip-permissions`，Codex `--yolo`，均可删改）和窗格排序。偏好保存在应用私有 SharedPreferences（非敏感）。
- **网页切换**：用户选择“识别＋偏好开关”。开关只在浏览器本地决定会话排序和标注；网页仍不新建会话，不新增写入入口。

版本：安卓 0.6.0（versionCode 14），网页终端 0.6.0，读取器 `reader-0.6.0`。

## 实现要点

| 部分 | 改动 |
| --- | --- |
| 桥接 `nas_remote_bridge.py` | 目标白名单 `claude`/`codex` 并锁定首次核对的类型；Claude Code 经 `sessions/<pid>.json`（PID＋`/proc` 启动时间核对）关联唯一 `projects/*/<会话ID>.jsonl`；记录解析只取公开正文；输入框按分隔线框定的 `❯` 行及暗色占位识别；启动阶段（信任目录等）沿用画面令牌与按键白名单；技能读 `~/.claude/skills` 与项目 `.claude/skills`，以 `/名称` 插入 |
| 生命周期 `nas_remote_lifecycle.py` | 新增 `agent` 字段（缺省视为 Codex，兼容旧 App）；历史列表读 `~/.claude/projects`；恢复为 `claude --resume <UUID>`，在原目录启动；会话登记显示仍在运行时拒绝重复恢复 |
| 安卓 App | `Pane.canWrite` 与 tmux 守卫按实际命令；窗格/启动页“智能体”切换；聊天、控制面板和提示文案随所选窗格；Codex 专属的问题卡片/快捷键仅对 Codex 显示 |
| 网页终端 | 会话列表新增 `agent` 字段；`claude` 前台同样进入消息框/图片/对话阅读；读取器支持 Claude Code，新增“命令输出”角色（纯文本）；会话栏开关 |

## 验证

- Python：安卓桥接与生命周期 65 项（含 `NAS_TMUX_TESTS=1` 真实隔离 tmux）通过；新增 Claude Code 记录过滤、增量游标、假 `claude` 进程的首条消息/绑定/暗色建议/拒绝覆盖桌面草稿/类型锁定，以及历史列表和 `--resume` 启动测试。网页读取器 6 项通过。
- Kotlin/JVM：23 项（含隔离 tmux 与 loopback SSH）通过；新增 Claude 窗格守卫与默认值测试。Debug、Release 构建和 Lint（0 警告）通过。
- 网页：容器测试 31 项通过（新增 Claude 前台进入智能体模式）；Playwright 隔离验收两种桌面尺寸通过，覆盖开关排序、标注和 Claude 会话消息框。
- 真实 Claude Code 2.1.296：在 scratch 目录、独立 tmux socket 中启动临时实例，经桥接完成信任目录菜单、首条消息、多行/折行/长粘贴发送、`!` 命令和拒绝覆盖已有输入；随后清理该 tmux server 与测试记录。测试向临时实例发送了少量短消息（产生少量用量），没有向任何生产会话输入。
- 生产只读：对当前运行的 Claude Code 会话执行桥接 snapshot 与 `reader-0.6.0` 读取，均正确关联（agent=claude）；未发送任何输入。

未验证：Android 真机安装与交互；Claude Code 提问/权限界面的实际操作（设计为控制面板手动处理）；`/clear` 后会话 ID 变化的体验（预期提示会话已变化，返回列表重开）；真实 Access 账号的浏览器登录。

## 部署与端口

- 读取器：以 yao 运行 `sh apps/web-terminal/deploy/setup-reader.sh`，安装到 `~/.local/share/nas-web-terminal/reader-0.6.0/`（700/600），`reader-0.5.0` 保留。
- 网页终端：构建 `nas-web-terminal:0.6.0`，`compose.yaml` 两处镜像改为 0.6.0 后 `docker compose up -d --no-build`。`nas_web_terminal`、`nas_web_ssh_transport` 均 healthy；公网 `/terminal` 匿名 302 至 Access，源站无 JWT 403。端口绑定 `{}`、网络模式（`nas-terminal-front` / host）不变，宿主无 3000 监听。
- APK：Release 用既有测试密钥签名（证书 SHA-256 `7197d2a3…91fe4e`，与 0.5.3 相同，可覆盖安装），`nas-remote-0.6.0.apk` 3,391,565 字节，SHA-256 `0277f1874e317a3f2b5745cbeace6ac126262e5d24f90a15ae232f0cfcfb7b6f`。下载服务改为 0.6.0 并重启，HTTP 下载与 SHA256SUMS 一致；仍仅 `192.168.50.33:8765`。
- 无端口新增、释放或预留变更。

## 回滚

1. 网页终端：`apps/web-terminal/compose.yaml` 两处镜像改回 `nas-web-terminal:0.5.3`，`docker compose up -d --no-build`。0.5.3 镜像仍读取 `reader-0.5.0`，不受新读取器影响。
2. 下载页：`cp ~/.config/systemd/user/nas-app-download.service.before-0.6.0 ~/.config/systemd/user/nas-app-download.service`，`systemctl --user daemon-reload && systemctl --user restart nas-app-download.service`。旧 APK 保留；这不会降级手机上已安装的 App。
3. 代码：回退本次提交。`reader-0.6.0` 目录可在确认不用后删除。

## 遗留与注意

- Claude Code 的会话登记、记录格式和输入框画面都是内部实现，不是公开协议。升级 Claude Code 后先运行 Python 测试，并在隔离 tmux 中做一次真实启动/发送验证。
- 只识别原生安装的 `claude` 进程名；经 Node 启动的旧版安装不会被识别为智能体窗格。
- Claude Code 空闲时会在输入框显示暗色的建议提示；识别逻辑将其视为空输入。电脑端若已键入正文（即使光标移到行首）仍拒绝发送。
