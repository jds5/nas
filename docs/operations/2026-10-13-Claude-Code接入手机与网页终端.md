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

## 追加：Claude Code diff 侧栏说明与界面改版（同日）

- 网页中 Claude Code 右侧出现的 “No changes this session” 是 Claude Code 自带的 diff 侧栏：终端宽度 ≥144 列、位于 Git 仓库且未设偏好时自动打开，不是网页终端功能。在 Claude Code 输入 `/diff` 可关闭并记住偏好。
- 网页终端 0.6.1 改为终端风格界面（详见[网页终端 README](../../apps/web-terminal/README.md)）。容器测试 31 项、Playwright 两种桌面尺寸验收通过；已部署，两个容器 healthy，源站匿名 403、公网匿名 302。回滚为改回 0.6.0 镜像。
- 网页终端 0.6.2：静态资源压缩与 ETag 重验证、积压输出合帧、会话列表单次查询、上传索引过期清理、前端标注与列表刷新修正（详见[网页终端 README](../../apps/web-terminal/README.md)）。容器测试 34 项、读取器 6 项、Playwright 两种桌面尺寸通过；已部署，两个容器 healthy，源站匿名 403（`no-store`）、公网匿名 302，端口绑定不变。回滚为改回 0.6.1 镜像。
- 2026-10-14 安卓 0.6.1：Material 3 界面整理与传输/轮询优化（详见[App README](../../apps/android/README.md)）。65 项 Python（含隔离 tmux）与 24 项 Kotlin（含 loopback SSH 实际安装缓存后上传 1 MiB）通过；Debug/Release 构建与 Lint（0 警告）通过；无模拟器，界面未实机查看。APK 3,444,813 字节，SHA-256 `2a0ab1d2036356fdd293c59cfc0f360de22940e2e210f803b3380d38ed818058`，签名同前；LAN 下载页已切换，HTTP 下载一致，旧 unit 备份为 `nas-app-download.service.before-0.6.1`。
- 同日用户反馈下载页无法访问：服务端监听与 HTTP 均正常，原因为手机 Android 本地网络权限，授权后恢复，未修改服务器。
- 2026-10-14 网页终端 0.6.3：按用户反馈改为与安卓一致的 Material 3 现代化界面（替换 0.6.1 终端风格），跟随系统浅色/深色。容器测试 34 项与 Playwright 两种桌面尺寸（含深色截图）通过；已部署，容器 healthy，源站匿名 403、公网匿名 302，端口绑定不变。回滚为改回 0.6.2 镜像。
- 2026-10-14 网页终端 0.6.4：按用户反馈，历史阅读从覆盖终端的只读页改为终端右侧并排面板，终端与消息框保持可用；面板随新输出自动刷新，宽度可拖动调整，切换会话或重连后自动保持（详见[网页终端 README](../../apps/web-terminal/README.md)）。只改前端。容器测试 34 项、Playwright 两种桌面尺寸通过，新增并排布局、拖动调宽、打开面板时发送不关闭、自动跟随新输出、切换会话保持面板的验收。已部署，两个容器 healthy，源站匿名 403（`no-store`）、公网匿名 302，端口绑定 `{}` 与网络不变。回滚为改回 0.6.3 镜像。
- 2026-10-14 网页终端 0.6.5：按用户选择的方案，Claude Code / Codex 会话默认“对话模式”：对话为主区域并自动跟随，下方保留可操作的实时终端条（只裁切显示，展开或收起不改 tmux 尺寸）；工具栏可切回 0.6.4 的终端模式，选择保存在浏览器本地（详见[网页终端 README](../../apps/web-terminal/README.md)）。只改前端。容器测试 34 项、Playwright 两种桌面尺寸通过，新增默认对话模式布局、终端条展开不触发 resize、模式切换与记忆的验收。回滚为改回 0.6.4 或 0.6.3 镜像。
- 2026-10-14 网页终端 0.6.6：按用户反馈参照 Claude、ChatGPT 官网重做对话模式：对话为居中阅读栏，回复为无边框正文，你的消息为右侧气泡；输入框改为对话下方的浮动卡片，随内容自动增高；实时终端改为底部停靠栏，可收起 / 小窗 / 展开，选择保存在浏览器本地（详见[网页终端 README](../../apps/web-terminal/README.md)）。修正切换会话时输入框留在隐藏面板中的问题（开发中发现，未上线）。容器测试 34 项、Playwright 两种桌面尺寸（含深色）通过，新增停靠栏三档、输入框位置与自动增高、复制回复的验收。回滚为改回 0.6.5 镜像。
- 2026-10-14 网页终端 0.6.7：按用户反馈，对话模式去掉底部常驻终端栏，改为输入框里的“终端”按钮，需要时从底部弹出（只裁切显示，开关不改 tmux 尺寸），平时对话占满整个区域；对话模式只保留工具栏的“对话 / 终端”切换，终端历史在终端模式的右侧面板中查看（详见[网页终端 README](../../apps/web-terminal/README.md)）。容器测试 34 项、Playwright 两种桌面尺寸通过，新增对话区占满、终端弹出 / 收起不触发 resize、只有一个切换的验收。回滚为改回 0.6.6 镜像。
- 2026-10-14 网页终端 0.6.8：按用户比较后的选择，恢复 0.6.6 的底部终端栏（收起 / 小窗 / 展开），小窗改为只露出终端底部 7 行，按 xterm 实际行高换算。行数依据只读查看现场 Claude Code 窗格底部画面确定，未向窗格发送任何输入。保留 0.6.7 的单一“对话 / 终端”切换（详见[网页终端 README](../../apps/web-terminal/README.md)）。容器测试 34 项、Playwright 两种桌面尺寸通过，新增小窗高度等于 7 行的验收。回滚为改回 0.6.7 或 0.6.6 镜像。
