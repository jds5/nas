# 网页 tmux 终端实施计划

2026-09-29，按用户要求新增整站 Cloudflare Access 保护的独立应用。参考 Homeland 的服务端验签方式，不复用其 Audience，也不修改 Homeland。

## 使用流程与范围

访问 `https://rokano.org/terminal` → Access 登录 → 选择 NAS 已有 tmux 会话 → 完整终端交互。支持手机键盘、快捷键、中文输入、调整终端尺寸、断开与重新连接。关闭页面仅释放本次 tmux client，后台任务继续运行；不会接管或踢掉其他 client。第一版不提供会话删除、创建项目、上传下载或命令自动执行。

直接接入 tmux PTY，因此 Codex 的首次启动、信任目录、升级选项及其他终端程序均可直接操作，不解析 Codex 私有消息格式。

## 写入面审计（开发前）

| 原路径 | 方法 | 写入对象/副作用 | 调用方 | 实施路径 | 浏览器登录 | 机器调用 |
| --- | --- | --- | --- | --- | --- | --- |
| 新增 | GET/HEAD | 页面、资源、会话元数据；无写入 | 浏览器 | `/terminal`、`/terminal/`、`/terminal/assets/*`、`/terminal/manage/api/sessions` | 全部需要 | 否 |
| 新增 | POST | 内存中的单次连接票据，不启动进程 | 浏览器 | `/terminal/manage/api/connections` | JWT + 精确 Origin + CSRF header | 否 |
| 新增 | WebSocket upgrade | 消费票据并连接已存在 tmux 会话 | 浏览器 | `/terminal/manage/ws` | JWT + Origin + 绑定身份的短期单次票据 | 否 |
| 新增 | WebSocket input/resize | 输入可执行宿主机用户有权执行的命令；resize 改变 client 尺寸 | 浏览器 | `/terminal/manage/ws` | 继承已鉴权连接，有效期内；限制帧大小与连接数 | 否 |
| 新增 | 断开/进程退出 | 仅关闭本次 tmux client | 浏览器/服务端 | WebSocket close | 已鉴权连接 | 否 |

无其他 RPC、导入导出、数据库、自动保存、上传、计划任务、webhook。子进程只通过固定 tmux 二进制和参数数组调用；目标使用服务端核对的 session ID，用户输入只通过 PTY 传送。日志不保存终端输出、会话名称、命令、身份、Cookie 或 JWT。

## 实施顺序

1. Node + jose + ws + node-pty 后端，xterm.js 移动端页面；所有 HTTP 和 WebSocket fail closed。
2. 隔离 tmux 测试：真实 PTY 输入输出、尺寸、断开后会话存活；JWT/Origin/票据/过期/错误路径回归。
3. 容器非 root、只挂载 tmux socket 目录、无公开端口、独立 front 网络；验证跨容器连接宿主机 tmux。
4. 独立 Access 应用及精确允许身份、反代与 Worker 配置，最后进行公网和真实用户验收。没有配置或验收失败时不开放入口。

实现与验证结果见 [README](README.md) 和对应运维记录。统一鉴权规则见 [Access 手册](../../docs/security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md)。

## PC 优化与图片上传增量审计（2026-09-29）

- `POST /terminal/manage/api/uploads`：浏览器上传 PNG/JPEG/WebP 到独立宿主目录 `.nas-web-uploads`，最多 4 张/消息、合计 20 MiB，存储配额 512 MiB。JWT、Origin、CSRF、会话引用、格式、限额校验后以随机文件名写入。无公开图片 URL，无上传执行或自动删除历史图片。
- `/terminal/manage/ws` 新增 `switch`：复用已鉴权连接，重新验证目标会话，释放旧 client，连接新 client；epoch 防止旧输出/输入串入新会话，不延长连接有效期。
- `/terminal/manage/ws` 新增 `submit-images`：图片绑定验签身份、session generation 和 pane ID。核对当前前台 Codex 后提交图片路径说明，拒绝非 Codex 及 `/`、`!` 命令混发；确认超时保留草稿，不自动重发。
- 前端新增文件选择、拖放、剪贴板上传，图片预览使用浏览器本地 blob URL；各会话保留独立草稿。Enter 发送、Ctrl+J 换行，输入法确认不发送。移除移动端按键栏，默认输入高度 156 px。
- 性能基线（本机 15 次只读采样）：list 中位 38.8 ms；exists 中位 41.0 ms、最大 53.6 ms。连接中的全列表检查改为单目标检查，切换复用 WebSocket，初始尺寸在连接时传入，ACK 合并至 32 KiB/40 ms，尺寸通知按帧去重。不修改 tmux window-size 策略。

## 0.3：PC 原生终端与临时 SSH

普通 tmux 窗格默认收起消息框并聚焦 xterm，保留完整终端键盘语义；每 1.5 秒查询当前前台程序，Codex 才自动显示消息框，手动展开仍支持 shell 文本输入。临时 SSH 按钮独立于 tmux 列表，新建真实 NAS SSH 登录，切换/断开销毁此连接，不创建 tmux、不恢复上一 shell。

沿用所有 Access、Origin、单次票据和连接配额。目标固定为 NAS 当前用户，不接受客户端提供 SSH 地址或命令。使用独立 Ed25519 密钥、固定主机公钥，authorized_keys 限制来源 loopback、禁止转发并允许 PTY。容器通过仅 UID 1000 可访问的 Unix socket，由独立 socat 转接容器转发到 127.0.0.1:22；不修改防火墙或发布端口。Web 专用目录只读挂载；转接容器使用 host 网络仅访问 loopback SSH，仅监听 Unix socket，挂载 transport 子目录，无密钥权限，不监听 TCP。此特定传输用途不增加公网入口。专用密钥，既有 SSH 密钥及 .env 不读取。关闭 WS 杀 SSH client，服务器以标准 SSH 挂断语义关闭 shell；0.5.1 起网络消失由 15 秒心跳、90 秒无 Pong 检测，握手成功后独立 24 小时上限生效；nohup/disown/自行启动的 tmux 不承诺随断线关闭。


## 0.4：大屏布局和滚轮历史

将顶部、边距、会话卡片压缩；会话栏可折叠，终端字号 12–24（默认 16），这两项偏好仅存浏览器。保留 156px 消息框，增加输入焦点提示，合并快捷键说明，显示 tmux/临时 SSH 和服务端截止时间的倒计时。

拦截 tmux 终端滚轮，向上时通过已鉴权 WebSocket 的 `history` 请求读取当前 pane 的 `capture-pane -p -S -1000` 快照，在只读可滚动层展示（当前屏幕及最近最多 1000 行，受 tmux 实际保留量限制）。不调用 copy-mode、不发送方向键、不修改共享 mouse 配置；快照前后检查 session/pane，旧 epoch/切换结果丢弃，每连接一次并发、最多每秒一次、捕获输出限 1 MiB。关闭/Esc 返回实时画面，实时输出仍消费和 ACK。纯文本 textContent 渲染，不执行终端转义/HTML，不存储历史。临时 SSH 保留普通 buffer 的本地滚动；备用屏幕未启用鼠标协议时阻断方向键回退，已启用鼠标协议的程序仍可处理鼠标事件。

## 0.5.2：异常断连与恢复

增加双向应用心跳、按字节确认的有界输出流控、低敏感连接关闭日志及页面原因提示。tmux 短暂失联最多自动重试三次，重新鉴权、核对原会话身份并保留原 24 小时截止时间，不重放输入；临时 SSH 不自动恢复。测试和部署记录见[断连排查与优化](../../docs/operations/2026-10-07-网页终端断连排查与优化.md)。
