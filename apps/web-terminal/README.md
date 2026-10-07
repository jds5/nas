# NAS 网页终端

独立 PC 网页应用，访问 NAS 上已有的 tmux 会话，或新建断开即关闭的临时 SSH 登录。整站由 Cloudflare Access 保护，应用再次验签并校验精确邮箱。设计范围与写入面审计见 [PLAN.md](PLAN.md)，统一规则见 [Access 手册](../../docs/security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md)。

## 页面能力

- 会话列表与刷新；连接现有会话，完整显示 Codex 等终端程序，包括首次启动和确认选项。
- 非 Codex 窗格默认收起输入框并聚焦原生终端，支持 Tab、方向键、Ctrl+C 和全屏程序；“展开输入框”可手动输入长文本。每 1.5 秒检测前台程序，Codex 显示消息框，退出后返回普通终端。
- “＋ 临时 SSH”直接新建 NAS shell，不经过 tmux，即使没有 tmux 会话也能用；关闭页面、断开或切换都会关闭该 SSH。重新连接会新建 shell。
- PC 专用：默认 156 px 高的可拖高输入框，Enter 发送、Ctrl+J 换行，输入法确认不发送；移除手机按键栏。
- PNG/JPEG/WebP 图片选择、拖放、剪贴板粘贴和预览；每条消息最多 4 张，总量 20 MiB。
- 切换复用已鉴权 WebSocket，各会话独立保留文字/图片草稿；失败和未确认消息不自动重发。
- 断开和重连仅操作本网页的 tmux client，不向任务发送 Ctrl-C，不踢掉其他客户端。
- Codex 上滚默认打开 Markdown 对话阅读，普通 shell 使用终端历史；支持分页、刷新、表格横向滚动与代码复制。
- 不自动新建或删除会话、不持久保存对话或终端记录。需要新会话时可在已连接的终端通过 tmux 自身操作。

## 边界

```text
浏览器 → rokano.org/terminal（整个子树的独立 Access 应用）
       → 现有 Worker（复用现有回源链路，保留 Origin）
       → origin-home.rokano.org:8443/terminal / NPM（终端路径转发）
       → nas-terminal-front / nas-web-terminal:3000
       → RS256 JWT + 独立 aud + 邮箱白名单
       → 同 UID 的 tmux socket → NAS 上已有会话
```

网页终端具有 tmux 宿主用户的实际权限。Web 容器挂载 tmux socket、专用图片目录与独立 SSH 凭据/传输目录，不挂载 Docker socket 或整个家目录，但 tmux 中执行的命令仍在宿主机上运行。这是远程终端的能力边界，不能理解为沙箱终端。

所有路径包括 HTML/JS/CSS 都要求有效 JWT。没有匿名 HTTP 健康检查；Docker 只检查进程 TCP 监听，健康不代表 Access 已配置或公网验收通过。配置缺失/错误统一拒绝。服务只接受人类邮箱白名单，不接受 Service Token。

连接过程使用 POST 申请 30 秒有效的一次性内存票据，再通过 WebSocket 子协议提交；票据不进入 URL。POST 同时要求精确 Origin、自定义 CSRF Origin，WebSocket 要求精确 Origin。票据绑定验签身份与 session ID、创建时间、server PID 和 socket 身份；变化时重新选取，不按名字盲连。0.5.1 起，WebSocket 在成功鉴权建连满 24 小时时由服务端断开，原 JWT 到期不提前终止已建立连接，tmux 任务保留，可再次连接；临时 SSH 则关闭，再连接会新建 shell。Access 撤销不会实时推送到已建立的连接，已建立连接的剩余窗口最长为 24 小时；紧急撤销可直接停止此容器，已有 tmux 任务不受影响。

最多 8 个活动连接、每身份 4 个；输出背压、帧长、票据数量受限；无输入重放。`attach-session -E` 不用容器环境覆盖宿主 session 环境；不使用 `-d`。检测到 tmux `exit-unattached` 开启时拒绝连接，防止断开误终止整个 server。尺寸按 tmux 自身 window-size 规则与其他客户端共同决定。

## 图片与输入

图片通过受保护的 `/terminal/manage/api/uploads` 上传，流式写入专用目录 `/home/yao/.nas-web-uploads`，随机文件名、权限 600、不提供公开下载 URL；浏览器只用本地 blob 预览。发送时生成与安卓端相同用途的本地路径说明，供 Codex 用图片工具读取。不能将图片与 `/`、`!` 命令混发；后台核对所属用户、会话和窗格，并确认前台仍是 Codex。普通 shell 拒绝图片消息，文字仍可直接操作终端。

存储配额 512 MiB，最多 4 个并发上传；失败的临时文件会清理。为保证历史对话可继续读取，成功上传的图片不自动删除，移除草稿缩略图也不删除 NAS 文件。用量满后由用户清理不再需要的图片并重启应用重新统计；本应用不会擅自清理已上传文件。进程重启后尚未发送的图片需重新选择上传；已发送的图片路径仍有效。草稿只保留在当前浏览器页面内存，刷新页面会丢失未发送草稿。

首次连接仍采用短期单次票据；切换走已鉴权 WebSocket、逐次校验目标，使用 epoch 隔离旧输出/输入，不重做握手、不延长连接的 24 小时期限。ACK 按 32 KiB 或 40 ms 合并，尺寸变化按动画帧去重，保持原有输出背压。没有修改 tmux 的 window-size 策略。

## 构建与测试

依赖 Docker；宿主机无需 Node/npm。Node 24 Debian trixie 镜像锁定 digest，npm 锁文件固定依赖；镜像内 tmux 3.5a 与当前 NAS 一致。升级宿主 tmux 时需要同步验证客户端协议兼容性。

```bash
cd /home/yao/code/nas/apps/web-terminal
docker build --target test -t nas-web-terminal:test .
docker run --rm --init --cap-drop ALL --security-opt no-new-privileges nas-web-terminal:test
docker build -t nas-web-terminal:0.5.3 .
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

1. Cloudflare Zero Trust 创建独立 **Self-hosted** 应用，hostname 为 `rokano.org`，保护 **`/terminal` 与 `/terminal/*`**（同一应用的路径条目），覆盖首页、资源、接口和 WebSocket；不设 Bypass。Allow 精确沿用 home 的允许邮箱，使用独立 Audience，Access session duration 按身份策略配置；它控制新的 HTTP 请求和握手，不决定已建立连接的 24 小时期限。不能仅保护 `/terminal/manage/*`，也不复用 home 的 Audience。
2. 本目录 `.env` 权限设为 `600`，填写 Team Domain、该新应用 AUD、`CF_ACCESS_ALLOWED_ORIGIN=https://rokano.org` 和精确允许邮箱。Origin **不带 `/terminal`**。UID/GID 与宿主 tmux 用户一致，socket 目录需由该用户创建，不放宽权限。
3. 以 `yao` 执行 `bash deploy/setup-ssh.sh`，创建专用密钥、固定主机公钥记录及私有 socket 目录；脚本不读取 `.env` 或既有私钥。随后由宿主 tmux 用户创建 `/home/yao/.nas-web-uploads`，权限 700（可用部署变量 `UPLOAD_DIR` 指定其他专用目录；容器返回给 Codex 的宿主路径会同步使用该值）。创建 `nas-terminal-front` 网络并执行 `docker compose up -d --build`。确认拒绝未配置请求后将 NPM 加入该网络，并在 NPM 的权威 Compose 中记录 external network。应用不发布宿主端口。
4. 备份现有 NPM `origin-home.rokano.org` Host 的配置，在其 Advanced 中合并 [终端 location 模板](deploy/nginx-advanced.conf.example)，只新增精确 `/terminal` 与 `/terminal/` 子树，指向 `nas-web-terminal:3000`，**完整保留路径前缀**。不能替换该 Host 或覆盖已有 `/home`、`/` 路由；用 NPM 生成配置后运行 `nginx -t` 并验证既有服务。
5. 复用既有 `rokano.org` Worker 回源链路，本次不要求新建 Worker 或回源 Secret。确认该链路保留 Origin、Access JWT 和 WebSocket；本仓库 `deploy/worker.mjs` 是备用独立转发示例，当前未部署。终端授权边界是应用逐请求验证独立 Access JWT。
6. 沿用现有 DNS，不新增域名。验证 `/terminal`、`/terminal/` 均受 Access 保护，以及 `/home`、`/stock` 原行为保持。NPM 可用本目录的 `deploy/npm-routing.mjs` 配合 location 模板添加/移除标记块；脚本仅操作现有 Host 7，备份后由 NPM 自身生成配置和重载。

共享 origin 意味着同域其他应用的脚本也属于相同浏览器 Origin；Origin 校验不能隔离同域应用的 XSS。独立 Audience 仍用于服务端身份边界。退出登录使用 Cloudflare 的同域退出入口，可能同时影响该域其他 Access 会话。

### 上线验收

- 匿名访问首页、资源、API、WebSocket 均被 Access 阻断/跳转；应用直连缺少或伪造 JWT 均为 403。
- 直连家庭 IP/8443 指定 origin-home SNI/Host 请求 `/terminal`，缺少或伪造 Access JWT 被应用拒绝。
- 真实允许邮箱登录后能查看会话、连接专用测试会话、操作初始化提示和输入中文；桌面快捷键和图片上传可用。
- 错误 Origin、重复票据不可连接；断线/到期不会结束 tmux 任务；重连不重放输入。
- 完成后检查日志无 Cookie/JWT/票据/命令/终端输出。不要向助手提供浏览器凭据。

未完成真实 Access 登录与源站绕过验收时，保持应用 fail closed，不开放公网入口。

## 回滚

tmux 连接只结束自己的 client；临时 SSH 则关闭对应登录 shell。上传图片保存在专用目录。停容器和回滚不删除图片。移除 NPM 中本次新增的两个终端 location（保留域名、DNS 和其他 location），执行 `docker compose down`，再 `docker network disconnect nas-terminal-front npm`。确认网络无成员后可以删除该网络。停容器释放终端 client 并关闭临时 SSH，现有 tmux server/会话继续运行。不要执行 `tmux kill-server` 或删除 socket。

代码回退：恢复上个已验证 Git 提交的本目录及镜像标签，使用保留的 `.env` 重新 `docker compose up -d`。NPM 通过变更前备份恢复本次新增的终端 location；不覆盖其他服务的并发变更。Access 变更单独保留配置备份。

## 官方资料

- [Cloudflare JWT 校验](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/)
- [Workers WebSocket 转发](https://developers.cloudflare.com/workers/examples/websockets/)
- [xterm.js 安全说明](https://xtermjs.org/docs/guides/security/)

以上资料核对日期：2026-09-29。实际部署和未完成事项见仓库运维记录，不将本说明视为已通过公网验收。

## 临时 SSH 部署与回滚（0.3.0）

使用固定 NAS 用户 `yao`，不允许网页指定其他主机、用户或私钥。Web 容器只读挂载 `/home/yao/.nas-web-ssh`；密钥文件 600、目录 700。authorized_keys 仅接受 loopback 来源，`restrict,pty` 禁止端口/代理/X11 转发并保留交互终端。

`ssh-transport` 是只运行 socat 的独立非 root 容器：host 网络用于连接 NAS 的 `127.0.0.1:22`，仅监听私有 Unix socket，不监听 TCP，不挂载密钥目录，只挂载 transport 子目录。Web 服务仍在专用 bridge；不修改 UFW、不增加公网端口。两个容器随 Docker 自动重启。主机公钥从 NAS 本地公开文件固定，SSH 严格验签、禁用密码回退。

临时 SSH 按正常 SSH 挂断语义结束登录 shell；主动断开立即关闭，WebSocket 每 15 秒 Ping，连续 90 秒没有 Pong 或有效应用消息才判定失联；SSH 每 15 秒保活，容忍 6 次无响应。服务端 24 小时上限也会关闭，不能用于需要断线保活的任务；这类任务使用 tmux。自行 nohup/disown 或新建 tmux 的进程遵循其自身保活规则。临时 SSH 中可以运行 Codex 终端，但网页图片提交仍限定于可核对前台进程的 tmux 窗格。

回滚到 0.2.0：停止 `ssh-transport`，恢复备份的 Compose，执行 `docker compose up -d --no-build --remove-orphans`；不会杀 tmux。备份目录见操作记录。若撤销专用密钥，只删除 `~/.ssh/authorized_keys` 中末尾注释为 `nas-web-terminal-ephemeral` 的这一行，保留其他登录密钥，不直接覆盖其他并发变更。专用密钥目录可保留但不得入库。该回滚会结束所有网页临时 SSH。


## PC 布局与滚轮（0.4.0）

顶部提供可记忆的会话栏折叠开关；终端工具栏 A−/A＋调整字号（默认 16，范围 12–24）。保留大消息框，当前输入区域高亮，显示键盘焦点归属。连接状态旁显示连接类型和剩余有效时间。

tmux 会话向上滚动打开只读历史快照，再用滚轮浏览；点击“返回实时终端”或 Esc 关闭。读取当前窗格保存的最近最多 1000 行及当前屏幕，不改变 Codex 输入、不进入共享 copy-mode、不更改 tmux mouse 设置。只展示 tmux 实际保留的终端文本，不保证包含完整 Codex 对话；快照期间新输出在后台继续接收，重新打开可读取新快照。未把历史写入浏览器持久存储或服务端日志。

临时 SSH 普通终端使用本地滚动历史；全屏程序未开启鼠标协议时禁止滚轮模拟方向键，因此不会误选历史命令。程序主动启用鼠标协议时保留原生鼠标交互。


## 历史表格与样式（0.4.1）

历史默认保留空格与原始行宽，长表格通过底部横向滚动条或 Shift+滚轮查看；可按“自动换行”临时切换为适合正文阅读的折行模式。捕获时使用 tmux `capture-pane -e -J` 合并终端软换行，不合并程序自己输出的硬换行，不修改共享 tmux 尺寸。仍只读取现有终端历史；被程序截断、省略或已从 tmux 历史淘汰的内容无法恢复。

历史保留终端已有颜色、粗体、斜体和下划线，支持 16/256 色及 RGB。只解析允许的 SGR 样式，使用 DOM textContent 渲染；不执行 HTML、OSC 链接或终端控制指令，不加载外部图片，样式节点最多 4096 个，超出后保留文字。

终端历史仍是快照；原始 Markdown 阅读已在 0.5.0 实现，见下一节。

## Codex 对话阅读（0.5.0）

连接 Codex 后向上滚动，或点击“查看历史”，默认打开“Codex 对话”。支持标题、列表、引用、Markdown 表格、代码块、HTTP(S) 链接与复制代码。表格独立横向滚动，普通段落自动排版；用户消息保留原文。顶部可切换“终端历史”，Esc 返回实时终端。普通 shell 默认终端历史，临时 SSH 保持原终端滚动。

“更早消息 / 较新消息”逐页浏览，顶部工具栏固定可见；“刷新对话”回到最新页。每页最多 60 条、384 KiB 公共消息，每次扫描最多 4 MiB 原记录。分页按记录字节位置绑定当前 Codex 会话，未完成 JSON 行等待下次刷新；长表格不按安卓端的 24,000 字符截取。单条超过页容量的极端记录明确提示未显示。仅展示已完成的用户/助手消息，不展示系统指令、推理和工具记录；正在生成的回复完成后刷新可读。旧的题答传输 JSON 转为可读回答。无记录或关联失败明确报错，可切换终端处理启动菜单，不猜测最近项目或其他对话。

读取通过既有固定 SSH 通道，以 NAS 用户运行只读适配器；复用安卓端已验证的进程树/后台端点关联逻辑，读取前后重新核对窗格与会话绑定。切换或断开取消读取、丢弃过期响应。每连接最多一个在途读取、间隔至少 1 秒，全服务最多两个 SSH 读取，18 秒宿主期限/22 秒 SSH 期限。浏览器只保存当前页和分页位置，退出历史即清理；没有新增日志目录挂载、公开下载接口或原始消息日志。

首次部署 0.5.0，先在 NAS 以 yao 安装适配器，再升级容器；不用 sudo，不读取 .env：

```bash
cd /home/yao/code/nas/apps/web-terminal
sh deploy/setup-reader.sh
docker build -t nas-web-terminal:0.5.3 .
docker compose up -d --no-build
```

`setup-reader.sh` 依赖 Python 3.9+、tmux 和已配置的 SSH 通道；复制版本化读取器与安卓桥接模块到 `~/.local/share/nas-web-terminal/reader-0.5.0/`（目录 700、文件 600）。不重启 Codex、不发送按键。新版本更新应使用新目录并同步固定 SSH 命令，旧目录可保留用于回滚；部署本版本前重复运行会同步本版本文件。Python 测试：`python3 -m unittest discover -s apps/web-terminal/test -p 'test_*.py'`（仓库根目录执行）。

Markdown 使用锁定版本的 [Marked](https://marked.js.org/using_advanced) 与 [DOMPurify](https://github.com/cure53/DOMPurify)，按其官方建议解析后净化。仅允许正文标签，原始 HTML 显示为文字，不加载远程图片、不执行脚本；只保留 HTTP(S) 链接并使用新窗口与 noopener/noreferrer。复制代码仅由点击触发，Permissions-Policy 为本源开放 clipboard-write，clipboard-read 仍禁用。鉴权、Origin、WS 有效期和其他服务入口不变。


## 24 小时连接（0.5.1，2026-10-07）

正常空闲连接依靠服务端 WebSocket Ping / 浏览器自动 Pong 保活，不向 shell 发送空命令。24 小时从 WebSocket 建立时计算，切换终端、输入和心跳均不续期；前端仅显示时:分:秒，不按空闲时间主动断开。

HTTP 请求与新握手仍逐次验证有效 Access JWT、Origin 和一次性票据；已鉴权 WebSocket 使用独立固定连接期限。这是用户明确要求的终端特例，不适用于其他应用。JWT 过期后刷新会话列表、上传图片或重新连接仍可能要求登录，已连接终端继续使用。撤销 Access 身份不会即时终止已有连接，紧急处理可停止 web 容器。

心跳不能保证断网、电脑休眠、浏览器关闭或 Cloudflare 边缘重启时不断线；临时 SSH 断线后关闭，需长期任务保活时使用 tmux。部署、验证边界及回滚见[24 小时连接实施记录](../../docs/operations/2026-10-07-网页终端24小时连接.md)。

## 断连诊断与恢复（0.5.2，2026-10-07）

浏览器与服务端每 15 秒交换应用层心跳，补充 WebSocket Ping/Pong；不向终端发送保活输入。服务端连续 90 秒未收到 Pong 或有效应用消息才关闭，浏览器连续 120 秒未收到服务端消息才主动关闭。窗口重新获得焦点、网络恢复或重新可见时立即探测，并给予浏览器恢复事件处理的时间。

输出按 UTF-8 字节确认并分块传输，待确认窗口不超过 128 KiB，通过 PTY pause/resume 限制生产速度。单连接待发送与待确认数据合计上限 4 MiB，超过才以 4002 关闭；暂停期间继续处理心跳，不因普通渲染变慢直接断开。对已断网/休眠页面无法承诺永远不断线。

tmux 的 1006、1012、1013、4000 异常关闭可按 1/3/8 秒延迟自动恢复，最多尝试三次；重新验证 Access、Origin、一次性票据及原会话身份。自动恢复保留原截止时间，不自动新建 tmux、重新运行 Codex 或重发指令。鉴权失效、会话变化、24 小时到期、主动断开和临时 SSH 不自动恢复；等待重连期间可点击断开取消。终端自身的设备查询应答仍正常发送。图片提交结果不确定时保留草稿并提示先核对。

页面显示关闭原因、代码和连接编号；容器日志新增 terminal_opened / terminal_closed，只有随机编号、连接类型、期限、耗时、关闭原因/代码、缓冲字节、心跳年龄及终端退出码。不记录用户名、会话名、命令、输出、JWT 或 Cookie。详见[断连排查与优化](../../docs/operations/2026-10-07-网页终端断连排查与优化.md)。

## 页面版本（0.5.3）

主界面顶部显示当前加载前端的版本号（如 v0.5.3），构建时直接读取 package.json 的 version，避免手动维护重复版本。升级后刷新页面即可核对；旧页面显示旧版本时，刷新后再连接。
