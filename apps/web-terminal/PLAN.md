# 网页 tmux 终端实施计划

2026-09-29，按用户要求新增整站 Cloudflare Access 保护的独立应用。参考 Homeland 的服务端验签方式，不复用其 Audience，也不修改 Homeland。

## 使用流程与范围

访问独立域名 → Access 登录 → 选择 NAS 已有 tmux 会话 → 完整终端交互。支持手机键盘、快捷键、中文输入、调整终端尺寸、断开与重新连接。关闭页面仅释放本次 tmux client，后台任务继续运行；不会接管或踢掉其他 client。第一版不提供会话删除、创建项目、上传下载或命令自动执行。

直接接入 tmux PTY，因此 Codex 的首次启动、信任目录、升级选项及其他终端程序均可直接操作，不解析 Codex 私有消息格式。

## 写入面审计（开发前）

| 原路径 | 方法 | 写入对象/副作用 | 调用方 | 实施路径 | 浏览器登录 | 机器调用 |
| --- | --- | --- | --- | --- | --- | --- |
| 新增 | GET/HEAD | 页面、资源、会话元数据；无写入 | 浏览器 | `/`、`/assets/*`、`/manage/api/sessions` | 全部需要 | 否 |
| 新增 | POST | 内存中的单次连接票据，不启动进程 | 浏览器 | `/manage/api/connections` | JWT + 精确 Origin + CSRF header | 否 |
| 新增 | WebSocket upgrade | 消费票据并连接已存在 tmux 会话 | 浏览器 | `/manage/ws` | JWT + Origin + 绑定身份的短期单次票据 | 否 |
| 新增 | WebSocket input/resize | 输入可执行宿主机用户有权执行的命令；resize 改变 client 尺寸 | 浏览器 | `/manage/ws` | 继承已鉴权连接，有效期内；限制帧大小与连接数 | 否 |
| 新增 | 断开/进程退出 | 仅关闭本次 tmux client | 浏览器/服务端 | WebSocket close | 已鉴权连接 | 否 |

无其他 RPC、导入导出、数据库、自动保存、上传、计划任务、webhook。子进程只通过固定 tmux 二进制和参数数组调用；目标使用服务端核对的 session ID，用户输入只通过 PTY 传送。日志不保存终端输出、会话名称、命令、身份、Cookie 或 JWT。

## 实施顺序

1. Node + jose + ws + node-pty 后端，xterm.js 移动端页面；所有 HTTP 和 WebSocket fail closed。
2. 隔离 tmux 测试：真实 PTY 输入输出、尺寸、断开后会话存活；JWT/Origin/票据/过期/错误路径回归。
3. 容器非 root、只挂载 tmux socket 目录、无公开端口、独立 front 网络；验证跨容器连接宿主机 tmux。
4. 独立 Access 应用及精确允许身份、反代与 Worker 配置，最后进行公网和真实用户验收。没有配置或验收失败时不开放入口。

实现与验证结果见 [README](README.md) 和对应运维记录。统一鉴权规则见 [Access 手册](../../docs/security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md)。
