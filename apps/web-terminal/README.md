# NAS 网页终端

独立网页应用，访问 NAS 上已有的 tmux 会话。整站由 Cloudflare Access 保护，应用再次验签并校验精确邮箱。设计范围与写入面审计见 [PLAN.md](PLAN.md)，统一规则见 [Access 手册](../../docs/security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md)。

## 页面能力

- 会话列表与刷新；连接现有会话，完整显示 Codex 等终端程序，包括首次启动和确认选项。
- 手机/桌面自适应、终端快捷键、中文输入框、粘贴与显式回车、全屏。
- 断开和重连仅操作本网页的 tmux client，不向任务发送 Ctrl-C，不踢掉其他客户端。
- 不自动新建或删除会话、不解析 Codex 对话格式、不存储终端记录。需要新会话时可在已连接的终端通过 tmux 自身操作。

## 边界

```text
浏览器 → terminal.rokano.org（整个 hostname 的独立 Access 应用）
       → 专用 Worker（覆盖并注入独立回源 Secret，保留 Origin）
       → origin-terminal.rokano.org:8443 / NPM（Secret 检查）
       → nas-terminal-front / nas-web-terminal:3000
       → RS256 JWT + 独立 aud + 邮箱白名单
       → 同 UID 的 tmux socket → NAS 上已有会话
```

网页终端具有 tmux 宿主用户的实际权限。容器内不挂载 Docker socket 或家目录，但 tmux 中执行的命令仍在宿主机上运行。这是远程终端的能力边界，不能理解为沙箱终端。

所有路径包括 HTML/JS/CSS 都要求有效 JWT。没有匿名 HTTP 健康检查；Docker 只检查进程 TCP 监听，健康不代表 Access 已配置或公网验收通过。配置缺失/错误统一拒绝。服务只接受人类邮箱白名单，不接受 Service Token。

连接过程使用 POST 申请 30 秒有效的一次性内存票据，再通过 WebSocket 子协议提交；票据不进入 URL。POST 同时要求精确 Origin、自定义 CSRF Origin，WebSocket 要求精确 Origin。票据绑定验签身份与 session ID、创建时间、server PID 和 socket 身份；变化时重新选取，不按名字盲连。WebSocket 最迟在 JWT 到期或连接满 15 分钟时断开，任务保留，可再次连接。Access 撤销不会实时推送到已建立的连接，此时最长窗口为 15 分钟；紧急撤销可直接停止此容器，已有 tmux 任务不受影响。

最多 8 个活动连接、每身份 4 个；输出背压、帧长、票据数量受限；无输入重放。`attach-session -E` 不用容器环境覆盖宿主 session 环境；不使用 `-d`。检测到 tmux `exit-unattached` 开启时拒绝连接，防止断开误终止整个 server。尺寸按 tmux 自身 window-size 规则与其他客户端共同决定。

## 构建与测试

依赖 Docker；宿主机无需 Node/npm。Node 24 Debian trixie 镜像锁定 digest，npm 锁文件固定依赖；镜像内 tmux 3.5a 与当前 NAS 一致。升级宿主 tmux 时需要同步验证客户端协议兼容性。

```bash
cd /home/yao/code/nas/apps/web-terminal
docker build --target test -t nas-web-terminal:test .
docker run --rm --init --cap-drop ALL --security-opt no-new-privileges nas-web-terminal:test
docker build -t nas-web-terminal:0.1.0 .
```

测试使用隔离 tmux server 和临时 RSA 测试密钥，覆盖错误/缺失 JWT、exp/nbf/iss/aud、身份、Origin/CSRF、重放、真实终端输入输出与 resize、断开与到期后会话存活。测试密钥不进入生产镜像；无环境变量免鉴权开关。

浏览器验收脚本为 `test/browser.cjs`，搭配 Playwright 1.48.0 镜像；`test/browser-fixture.mjs` 仅在测试镜像中模拟边缘签发身份，并创建容器内独立 tmux。运行示例：

```bash
docker network create nas-terminal-test
docker run -d --rm --name terminal-fixture --init --network nas-terminal-test nas-web-terminal:test node test/browser-fixture.mjs
mkdir -p artifacts
docker run --rm --init --network nas-terminal-test \
  -v "$PWD/test/browser.cjs:/test/browser.cjs:ro" -v "$PWD/artifacts:/artifacts" \
  mcr.microsoft.com/playwright:v1.48.0-jammy \
  sh -c 'cd /tmp && npm install --no-audit --no-fund playwright@1.48.0 && NODE_PATH=/tmp/node_modules node /test/browser.cjs'
docker stop terminal-fixture
# 等待 --rm 清理完成后删除临时网络。
docker network rm nas-terminal-test
```

该 fixture 不发布端口，不挂载生产 socket，不能用于线上部署。截图只含测试会话，保存在 Git 忽略的 `artifacts/`。跨宿主 PTY 验收脚本 `test/cross-host.mjs` 只接受专用 socket 目录 `/run/cross-test/socket` 下唯一一个 `nas-web-cross-test-` 前缀会话；使用宿主临时目录创建隔离 server 后挂入测试容器，验证完清理该临时 server，绝不能对 `/tmp/tmux-1000/default` 运行它。

## 部署（按顺序）

1. Cloudflare Zero Trust 创建 **Self-hosted** 应用，hostname 为 `terminal.rokano.org`，**路径留空，保护整个域名**；不设 Bypass。Allow 精确沿用 home 的允许邮箱；使用独立 Audience，建议应用 session duration 15 分钟。不能复用 home 的 Audience。
2. 在本目录复制 `.env.example` 为 `.env`，权限 `600`，填写 Team Domain、该新应用 AUD、`https://terminal.rokano.org` 和精确允许邮箱列表。生产只注入这四项身份配置，不需要 CF API Token。UID/GID 必须与宿主 tmux 用户一致；socket 目录不存在时先由该用户启动 tmux，不放宽其权限。
3. `docker network create nas-terminal-front`（只需一次），然后 `docker compose up -d --build`。无宿主机发布端口；只读 rootfs、临时 `/tmp`、非 root、drop capabilities 与资源限额。确认 fail closed 后执行 `docker network connect nas-terminal-front npm`；在 NPM 的权威 Compose 配置中记录此 external network，避免 NPM 重建时丢失网络。不要把其他业务容器加入此网络。
4. 创建独立 NPM Proxy Host `origin-terminal.rokano.org`，指向 `http://nas-web-terminal:3000`，启用 WebSocket，复用经核验覆盖该域名的证书。修改前备份 NPM 配置/数据库，使用 NPM 管理接口生成配置，不直接覆盖其生成文件。
5. 本地产生独立 32 字节随机 Secret（例如 `openssl rand -hex 32`，保存到权限 600 的部署文件，不提交、不粘贴到会话）。将 [NPM 配置模板](deploy/nginx-advanced.conf.example) 中的占位值替换，并写入该独立 Host 的 Advanced。检查生成 location 没有覆盖 Origin/JWT、没有启用其他请求日志。NPM 不记录 header、URL 或 WebSocket 票据。
6. 配置 `origin-terminal.rokano.org` 灰云 DNS 到同一已验证家庭入口（核对现有 origin-home 的实际 A/AAAA，不复制历史 IP）。专用 Worker 使用 [worker.mjs](deploy/worker.mjs)，设置 `PUBLIC_ORIGIN=https://terminal.rokano.org`、`ORIGIN_URL=https://origin-terminal.rokano.org:8443`，将相同值写入 Worker Secret `ORIGIN_SECRET`。禁止把 Secret 设成代码常量或普通可公开变量。关闭 workers.dev，绑定该域名的 Worker route。Worker 保留浏览器 Origin；不能沿用重写 Origin 的旧回源行为。
7. 配置 `terminal.rokano.org` 的 Cloudflare 入口与 Worker 路由。若沿用自选 Cloudflare IP 的灰云入口，必须验证 TLS、CF-Ray、Access 登录跳转；无证据时不要认定入口受保护。

### 上线验收

- 匿名访问首页、资源、API、WebSocket 均被 Access 阻断/跳转；应用直连缺少或伪造 JWT 均为 403。
- 直连家庭 IP/8443 指定 origin-terminal SNI/Host，缺少或伪造回源 Secret 被拒绝；即使有正确 Secret，错误 JWT 仍被应用拒绝。
- 真实允许邮箱登录后能查看会话、连接专用测试会话、操作初始化提示和输入中文；手机竖屏和横屏可用。
- 错误 Origin、重复票据不可连接；断线/到期不会结束 tmux 任务；重连不重放输入。
- 完成后检查日志无 Cookie/JWT/票据/命令/终端输出。不要向助手提供浏览器凭据。

未完成真实 Access 登录与源站绕过验收时，保持应用 fail closed，不开放公网入口。

## 回滚

本应用不修改 tmux 会话内容、不保存业务数据。停用专用 Worker route/公网 DNS，禁用此独立 NPM Proxy Host，执行 `docker compose down`，再 `docker network disconnect nas-terminal-front npm`。确认网络无成员后可以删除该网络。停容器仅释放终端 client，现有 tmux server/会话继续运行。不要执行 `tmux kill-server` 或删除 socket。

代码回退：恢复上个已验证 Git 提交的本目录及镜像标签，使用保留的 `.env` 重新 `docker compose up -d`。NPM 通过变更前备份恢复本应用 Host；不覆盖其他服务的并发变更。Worker 与 Access 各自保留配置备份。

## 官方资料

- [Cloudflare JWT 校验](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/)
- [Workers WebSocket 转发](https://developers.cloudflare.com/workers/examples/websockets/)
- [xterm.js 安全说明](https://xtermjs.org/docs/guides/security/)

以上资料核对日期：2026-09-29。实际部署和未完成事项见仓库运维记录，不将本说明视为已通过公网验收。
