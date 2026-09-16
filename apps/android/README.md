# 接续 · NAS Remote

独立 Android 子项目：通过 SSH 接续 NAS 上正在运行的 Codex。当前为 0.2.1，默认展示聊天消息，普通输入框一次发送；原终端模式保留为可选入口。无需新端口或 NAS 常驻服务，不新建、恢复或分叉正在控制的 Codex。

## 结构

```text
apps/android/
  app/                 # Compose 界面、连接生命周期、Keystore 凭据存储
  core/                # JVM SSH/tmux 传输、安全测试
    src/main/resources/nas_remote_bridge.py  # 随包分发、通过 SSH 执行的无状态适配器
    src/test/python/   # 消息过滤、输入保护、真实 tmux / proc 测试
  distribution/        # 仅 LAN 的 APK 下载页面
  gradle/wrapper/       # 固定 Gradle 版本与发行包校验
```

不增加账户系统、云端服务、数据库或后台保活服务。`app` 依赖 `core`；协议层不依赖 Android。未来确有需要再拆模块。

## 构建与安装

需要 JDK 17、Android SDK Platform 37.0、Build Tools 36.0.0。设置 `ANDROID_HOME` 或在本目录的 `local.properties` 写入 `sdk.dir=SDK绝对路径`，文件已忽略。

```bash
cd apps/android
./gradlew :core:test :app:assembleDebug :app:lintDebug
# Linux 上额外运行隔离的真实 tmux 测试，需要 tmux 和 /usr/bin/python3
NAS_TMUX_TESTS=1 ./gradlew :core:test --rerun-tasks
# 另有本机 /usr/sbin/sshd 时，可加入临时 loopback SSH 认证测试
NAS_TMUX_TESTS=1 NAS_SSH_TESTS=1 ./gradlew :core:test --rerun-tasks
NAS_TMUX_TESTS=1 python3 -m unittest discover -s core/src/test/python -v
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

支持 Android 9（API 28）及以上，目标 Android 17（API 37）。Debug APK 仅用于个人测试；正式版本需单独管理发布签名，私钥和签名资料不入库。

依赖固定版本：AGP 9.4.0、Gradle 9.6.0、Kotlin 2.4.20、Compose BOM 2026.09.00、Material 3 1.4.0、JSch 2.28.7、Bouncy Castle 1.86。Material 3 使用当前稳定发布，不引入 alpha 组件；使用公开的 Compose 弹簧动画、动态配色、深色主题、页面与列表过渡。依赖版本依据及本轮验证见下文。

## 外网使用前提

本 App 主要用于在外部网络连接本仓库所在的 NAS，本机就是 SSH/tmux 目标。支持公网 IP / DNS-only DDNS＋自定义外部 SSH 端口；VPN 可选，不是必须先安装的条件。现有 HTTPS 业务入口不能直接替代原生 SSH 转发。Android 17 的局域网权限不再阻止公网连接，使用内网地址时按需授权。

公网入口、本机信息与完整密钥配置步骤见 [外网连接手册](../../docs/operations/手机外网连接与SSH密钥配置.md)。局域网获取测试 APK 可打开 **http://192.168.50.33:8765/**，服务说明见 [distribution](distribution/README.md)。

## 首次连接

1. 在可信电脑上为手机创建独立 SSH 密钥，例如 `ssh-keygen -t ed25519 -f nas-phone`，设置私钥口令；按现有 NAS SSH 管理流程授权 `nas-phone.pub`。授权的 Unix 用户必须能访问目标 tmux 默认 socket。不要导入日常管理员的通用私钥。
2. 从可信 NAS 终端执行 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256` 核对主机指纹。填写完整 `SHA256:...`，不含末尾注释；不要只通过未经核验的 `ssh-keyscan` 建立信任。
3. 将手机专用私钥通过可信路径转移至手机，在 App 导入。连接成功后 App 加密保存副本；确认可用后自行清理传输副本。口令每次连接输入，不保存。
4. 填入主机、端口、SSH 用户及指纹。外网填写已验收的公网主机和路由器外部 SSH 端口；Android 17 仅在访问内网地址时按需授权。App 不负责路由器转发或 VPN 配置。
5. 点击 Codex 会话默认进入聊天页，编辑后点“发送”。输入 `/` 选择命令，`/skills` 打开技能列表，选择技能插入 `$技能名`；`!命令` 在 NAS 的 Codex 中执行。需要菜单、审批或核对已有草稿时打开“控制面板”。列表及顶部的“终端”保留原先的粘贴、回车操作；其他程序仍只读。
6. 离开 App 或锁屏会关闭 SSH；远端任务继续。返回后手动重连。电脑和手机共享输入，请避免同时操作。

使用默认 tmux socket，远端需要 POSIX shell、PATH 中的 tmux（已在 3.5a 验证）。聊天模式另外需要 Linux `/proc`、Python 3.9+ 以及同一 Unix 用户可读取的运行中 Codex rollout 文件（本次验证 CLI 0.154.0）。不支持跳板、密码登录、SSH 配置文件和自定义 socket。连接需有正在运行的 tmux server。填写的指纹必须与 SSH 实际协商的主机密钥匹配；指纹变更时拒绝连接，需通过可信渠道重新核对。

## 安全边界

- 仅公钥认证，强制固定 SHA256 主机指纹；保留 SSH 库默认算法限制，不自动接受未知主机。
- 私钥副本与连接配置使用 Android Keystore AES-256-GCM 加密，存于不备份目录；禁用应用备份与截图。私钥在连接时仍需进入应用内存，Keystore 不等于硬件内 SSH 签名；不抵御已经失陷的手机或 NAS。
- 不持久化对话输出、草稿、私钥口令或连接日志；草稿仅在当前 App 进程中保留，按窗格区分，更换连接资料时清空。系统杀进程后草稿丢失。
- 输入通过 SSH stdin → 随机命名 tmux buffer 传递，不拼入 shell 命令；拒绝终端控制字符，单次最多 16 KiB。发送前校验 pane ID、pane PID、server PID、存活与前台命令；目标变化则失败，不自动创建替代会话。
- 检查与发送并非操作系统级原子操作，不能保证目标恰在检查后退出时绝对不会落入其他程序；tmux 和当前 Unix 用户本身属于信任边界。命令名也不是程序签名认证。
- 写入结果不确定时立即断开，禁止自动重试；重连、读取窗格并手动确认后恢复操作。Ctrl-C 需二次确认。按钮成功只表示终端输入已发送，不代表任务执行成功。
- 输出使用原生文字组件，可选择复制，支持标题、粗体、行内代码和代码块；不执行 ANSI/OSC、HTML、链接或远端剪贴板动作，不加载远程图片。SSH 单次响应上限 256 KiB；聊天每次扫描至多 1 MiB，返回至多 80 条/约 180 KB 消息，单条上限 24,000 字符，App 内至多 300 条；终端显示约 400 行。
- App 限制不是服务器权限隔离：手机持有的 SSH 密钥仍具有该 NAS 用户的权限。遗失手机后应在 NAS 撤销对应公钥；“清除本机资料”不会撤销服务器授权。

## 体验与当前限制

默认聊天模式按所选 pane 的进程树找到 **该 Codex 进程正在打开的会话文件**，用进程启动时间、文件 inode 和会话 ID 绑定；不按目录或“最新文件”猜会话。每秒读取已落盘的公开用户消息、Codex 进展和最终回复，排除内部推理、系统/开发者提示、代理工具日志。只有用户主动 `!` 执行的命令结果作为独立消息显示。

“发送”在同一 SSH 操作内完成 bracketed paste 与 Enter，先核对原执行器和电脑端空输入框，再核对粘贴结果。支持中文、多行、换行及长文本折叠卡片；无法识别的界面、已有草稿或会话变化会拒绝盲目回车。粘贴后状态不确定则保留草稿、断开连接，重连核对后恢复，不能直接再次点发送。电脑和手机不能同时输入；这些检查不是输入锁。

- `/` 提供常用命令建议，也能输入其他原生 Codex 命令；发送后打开真实终端控制面板，通过方向键、Tab、确认和 Esc 操作。权限确认不自动同意，不伪造审批卡片。
- `/skills` 浏览 NAS 上有界扫描得到的 SKILL.md 名称和说明，选择后使用 Codex 原生 `$技能名`；实际加载、同名技能和插件可用性以当前 Codex 会话为准。可选择“打开 Codex 原菜单”。
- `!` 原样交给 Codex 的 shell 模式，沿用当前 Codex 权限；不经桥接直接执行用户 shell 文本。命令在 NAS 上运行，不是手机本地命令。
- 这是当前 CLI 的适配层，**不是官方 Remote Control / App Server 协议**。消息在 Codex 写入完成事件后出现，不支持逐 token 流式输出；没有可靠事件的菜单状态仍在控制面板查看。
- 从未产生过记录的新会话、其他 CLI 版本或无法唯一关联的文件会提示使用终端模式。先在原终端产生一轮记录，再返回列表重新打开即可尝试聊天模式。
- 单条超过显示上限、超过 300 条历史、超过 1 MiB 的单条 JSON 记录或无法识别的界面需要终端核对。退出 App 后聊天内容不保存在手机；NAS 自身的 Codex 日志保留策略不受 App 控制。

界面沿用稳定版 Material 3、动态配色和系统动画设置；聊天使用稳定消息 ID、惰性列表、轻量原生 Markdown 显示和列表过渡，网络与文件操作在 IO 线程。未连接 Android 真机/模拟器，不能宣称达到 60/90/120 Hz；本版仍需实机验证输入法、旋转、字体缩放、锁屏换网以及发送期间断网。

## 0.2.1 连接诊断（2026-09-16）

将原来统一的“SSH 连接失败”拆为可反馈的错误编号：域名解析 `E_DNS`、系统网络权限 `E_NETWORK_DENIED`、TCP 超时/拒绝/路由错误、SSH 握手、公钥认证与主机指纹错误。TCP 阶段通过实际创建连接记录，指纹通过后才标记认证阶段；不额外探测、不保存网络日志，也不展示原始异常、私钥或口令。

这是一项诊断改进，不代表已经修复蜂窝网络故障。当前用户反馈 4G 下域名和公网 IPv4 直连均失败；NAS 侧访问公网端口的握手成功仅是回环路径证据。下一步用新错误编号结合 4G 实测定位，勿据此放宽指纹/公钥认证或开放更多端口。

## 0.2.0 验证（2026-09-16）

14 项 Python 桥接测试、9 项 JVM 测试（含隔离 tmux 和 loopback OpenSSH）、3 项下载服务测试通过。Debug / Release 构建及 Lint 通过。真实隔离 Codex 0.154.0 验证 `!printf`、`/status`、中文多行、长文本折叠识别；当前项目会话只做消息关联读取，没有发送测试输入。APK 使用与旧版相同的本机测试签名，可覆盖更新。

安全诊断、修复与待办见 [项目安全诊断](../../docs/security/ANDROID_APP_SECURITY_REVIEW.md)。LAN 下载与校验结果记录在 [运维记录](../../docs/operations/运维记录.md)。

## 0.1.1 更新（2026-09-16）

修正连接页的公网使用定位，移除所有连接都必须授予局域网权限的错误限制，保持指纹和公钥认证要求。Release 构建与 Lint 通过；签名安装包现保存在 `distribution/build/nas-remote-0.1.1.apk`，并已启动局域网下载页。服务器实际下载得到的 APK 与本机签名文件完全一致。SSH 配置和外网入口只做核对、记录操作步骤，未修改生产 SSH/防火墙。

## 0.1.0 验证记录（2026-09-16）

- 9 项测试通过，0 跳过：7 项安全单元测试；隔离 tmux server 的真实输入/目标保护测试；临时 loopback OpenSSH 的带口令 Ed25519 登录、错误口令及主机指纹拒绝测试。SSH 测试显式使用 Android 所需的 BC 签名实现，服务端命令输出为测试数据，不连接生产 tmux。
- `:app:assembleDebug`、`:app:assembleRelease`、`:app:lintDebug` 均通过。Lint 无错误，仅提示 Gradle 有新版本；保留与 AGP 配套验证的 9.6.0。
- Release 开启代码及资源压缩，APK 约 2.9 MB。已在本机构建目录生成 `app/build/outputs/apk/release/app-preview.apk`，用本机 Android 调试密钥签名，并通过 `apksigner verify`；可用于个人安装和后续性能测试，不作为正式发布签名。APK、密钥与构建缓存不入库。
- 尚未在 Android 真机或模拟器上启动验收，因此未确认设备端 SSH 兼容性、Android Keystore 行为或动画帧率；没有修改生产 SSH/VPN 或向当前对话发送测试输入。

## 官方依据

查阅日期：2026-09-16。

- [Material 3 发布记录](https://developer.android.com/jetpack/androidx/releases/compose-material3)：稳定版 1.4.0；完整 Expressive motion API 尚未在该版公开，因此页面动效使用 Compose 稳定 API。
- [Material 3 / Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) 与 [动画指南](https://developer.android.com/develop/ui/compose/animation/quick-guide)。
- [AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes)：Gradle / JDK / SDK 要求。
- [Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)：target 37 的运行时授权。
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[JSch](https://github.com/mwiede/jsch)、[Bouncy Castle 发布版本](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/maven-metadata.xml)。

接入路线和后续范围见 [手机接入规划](../../docs/operations/手机接入现有tmux规划.md)。
