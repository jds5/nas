# 接续 · NAS Remote

独立 Android 子项目：通过 SSH 查看已有 tmux 窗格，向同一 Codex 进程发送中文和特殊键。无需部署 NAS Web 服务，也不会新建或重启 Codex。当前为 0.1.1 原型，先连接测试会话，再接入正在使用的会话。

## 结构

```text
apps/android/
  app/                 # Compose 界面、连接生命周期、Keystore 凭据存储
  core/                # SSH、主机指纹、tmux 协议与安全测试（纯 Kotlin/JVM）
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
5. 选择窗格，先阅读输出。在独立输入框编辑后按“粘贴到窗格”，核对后按“回车”。其他程序只读；首版仅向前台命令名为 `codex` 的窗格发送输入。
6. 离开 App 或锁屏会关闭 SSH；远端任务继续。返回后手动重连。电脑和手机共享输入，请避免同时操作。

首版使用默认 tmux socket，远端需要 POSIX shell、PATH 中的 tmux（已在 3.5a 验证）。不支持跳板、密码登录、SSH 配置文件和自定义 socket。连接需有正在运行的 tmux server。填写的指纹必须与 SSH 实际协商的主机密钥匹配；指纹变更时拒绝连接，需通过可信渠道重新核对。

## 安全边界

- 仅公钥认证，强制固定 SHA256 主机指纹；保留 SSH 库默认算法限制，不自动接受未知主机。
- 私钥副本与连接配置使用 Android Keystore AES-256-GCM 加密，存于不备份目录；禁用应用备份与截图。私钥在连接时仍需进入应用内存，Keystore 不等于硬件内 SSH 签名；不抵御已经失陷的手机或 NAS。
- 不持久化对话输出、草稿、私钥口令或连接日志；草稿仅在当前 App 进程中保留，按窗格区分，更换连接资料时清空。系统杀进程后草稿丢失。
- 输入通过 SSH stdin → 随机命名 tmux buffer 传递，不拼入 shell 命令；拒绝终端控制字符，单次最多 16 KiB。发送前校验 pane ID、pane PID、server PID、存活与前台命令；目标变化则失败，不自动创建替代会话。
- 检查与发送并非操作系统级原子操作，不能保证目标恰在检查后退出时绝对不会落入其他程序；tmux 和当前 Unix 用户本身属于信任边界。命令名也不是程序签名认证。
- 写入结果不确定时立即断开，禁止自动重试；重连、读取窗格并手动确认后恢复操作。Ctrl-C 需二次确认。按钮成功只表示终端输入已发送，不代表任务执行成功。
- 输出按不可信纯文本显示，不执行 ANSI/OSC、HTML、链接或远端剪贴板动作；限制单次读取为 256 KiB、显示约 400 行。
- App 限制不是服务器权限隔离：手机持有的 SSH 密钥仍具有该 NAS 用户的权限。遗失手机后应在 NAS 撤销对应公钥；“清除本机资料”不会撤销服务器授权。

## 体验与当前限制

第一版每秒读取 `capture-pane` 屏幕快照，不附着 PTY，不改变电脑窗口尺寸，也不向终端声称已经建立结构化聊天协议。它提供完整的本地中文编辑和明确的终端键操作，但不是完整终端模拟器：不保留全部历史、颜色、光标、鼠标或终端排版，手机文字会重新换行。轮询中断时先重连，避免盲目发送。

页面、列表和系统组件使用 Compose/Material 动画，SSH 与文件操作在 IO 线程，终端更新不强制播放长动画，界面遵循系统动画时长设置。是否达到设备的 60/90/120 Hz 帧预算仍需在真机 Release 构建中用 Perfetto / Macrobenchmark 验证；不能以框架选择替代性能测量。

后续优先验证：真机 Keystore/Ed25519 连接、中文输入法与多行粘贴、Android 17 权限拒绝/撤销、锁屏及网络切换、发送期间断网、目标退出、横竖屏和字体缩放。当前只实现 tmux 路径；结构化协议、通知和后台连接未实现。

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
