# Cloudflare Access 公开读与登录写实施手册

整理日期：2026-09-10。合并原实施手册与独立鉴权规则，本文件为该模式的统一实施依据。本次仅整理既有约束，未重新核实产品功能或部署。

适用于少量受邀 Writer 的实验 Web 项目；不适用于无法完成浏览器登录的媒体原生客户端。机器调用另用独立集成入口和对应认证。

## 一、固定安全模型

```text
匿名用户
  └─ 同一 hostname 的公开路径
       ├─ GET / HEAD → 公开页面和只读 API
       └─ POST / PUT / PATCH / DELETE / 未识别方法 → 应用统一拒绝

Writer
  └─ 同一 hostname 的 /manage/*
       └─ Cloudflare Access 精确允许策略
            └─ Cf-Access-Jwt-Assertion
                 └─ 应用服务端验证签名、iss、aud、exp、nbf
                      ├─ GET / HEAD → 管理读取
                      └─ 写方法 → 再校验精确 Origin/CSRF → 执行业务写入

机器调用
  └─ /integrations/*
       └─ Service Token、签名 + 时间戳/nonce、防重放或 mTLS
```

固定原则：

- 一个项目一个 hostname、一个独立 Access Application、一个独立 Audience；不要让多个项目共享 Audience。
- `/manage` 与 `/manage/*` 都必须被 Access 覆盖；使用子路径部署时应覆盖完整外部路径，例如 `/stock/manage/*`。
- Access 是外部身份提供者，应用服务端验签才是防止源站绕过的授权边界。
- UI 的“只读模式”和隐藏按钮只是体验优化，绝不是安全控制。
- 公开响应中的全部字段都视为已公开信息；隐私、凭据和不应公开的财务字段不能依靠“没有链接”隐藏。

## 二、开发前必须完成的写入面审计

Codex 必须搜索并列出以下内容，不得只检查名称中带 `api` 的目录：

- 所有 `POST`、`PUT`、`PATCH`、`DELETE` 路由；
- 带副作用的 `GET`、GraphQL mutation、RPC、Server Action、WebSocket 消息；
- 文件上传、导入、导出、批处理、计划任务触发、重算、同步和 webhook；
- 表格编辑、自动保存、撤销恢复、乐观更新及后台 fetch；
- 数据库写方法、对象存储写入、消息队列生产者和 shell/子进程调用；
- 前端 `fetch`、Axios、表单 action、`sendBeacon` 和客户端 SDK 写调用。

审计输出至少包含：原路径、HTTP 方法、写入对象、调用方、新路径、是否需要浏览器 Writer、是否属于机器调用。

迁移规则：

- 浏览器写入全部迁入 `/manage/*`；公开路径只保留无副作用读取。
- 导出默认属于管理操作，因为它可能暴露完整数据集。
- webhook/机器调用不得伪装成浏览器 Writer，迁入 `/integrations/*` 并单独设计认证与防重放。
- 任何带副作用的 GET 必须改成写方法；不能依赖 Access 按 HTTP 方法保护同一路径。

## 三、Cloudflare 控制台配置

每个项目单独建立 Self-hosted Access Application：

1. Application domain 填项目对外 hostname；如只保护管理子树，路径填写完整 `/manage/*` 或实际 base path 下的管理路径。
2. 同时确认裸 `/manage` 也受保护；若控制台的通配规则不覆盖裸路径，增加一条精确路径应用或重定向规则。
3. Allow policy 只放明确邮箱或受控身份组；不要使用所有已登录用户均可写的宽泛策略。
4. 记录该项目独立的 Application Audience (`aud`)；只写入部署环境，不写入源码文档中的真实配置块。
5. 记录 Team Domain，例如 `https://<team>.cloudflareaccess.com`；应用使用其 `/cdn-cgi/access/certs` 获取 JWKS。
6. NPM/nginx 必须原样保留 `Cf-Access-Jwt-Assertion`，不得自行生成、覆盖或记录该 header。
7. 灰云自选 Cloudflare IP 方案可继续使用 Access，但上线验收必须证明请求仍到达 Cloudflare 边缘：TLS 正常、响应含 `CF-Ray`、管理路径返回 Access 登录跳转。只看到 HTTP 200 不足以证明这一点。

不要把 Cloudflare Dashboard 配置当作应用改造已完成。攻击者可能已知家庭公网 IP，并把目标 SNI/Host 直接发往源站；应用仍必须验签。

## 四、应用服务端实现要求

### 4.1 配置

统一使用以下环境变量名称；目标项目确有命名规范时可以映射，但语义不得改变：

```dotenv
CF_ACCESS_TEAM_DOMAIN=
CF_ACCESS_AUD=
CF_ACCESS_ALLOWED_ORIGIN=
```

- `CF_ACCESS_TEAM_DOMAIN`：完整 HTTPS origin，不带额外路径。
- `CF_ACCESS_AUD`：当前项目自己的 Audience。
- `CF_ACCESS_ALLOWED_ORIGIN`：浏览器对外 origin，必须精确匹配 scheme、hostname 和非默认端口。
- 任一配置缺失或格式错误时，`/manage/*` 必须 fail closed；公开只读路径仍可工作。
- 应用验签不需要 Cloudflare API Token、Global API Key、Service Token Secret 或 Tunnel Token，禁止把这些凭据注入应用。

### 4.2 成熟库

按目标项目语言选择维护活跃、能验证远程 JWKS 的成熟库，并锁定经过审计的版本。实施前检查目标项目现有依赖与维护状态，验证签名、声明及 JWKS 刷新策略；禁止自行实现 JWT 解析、密码算法或登录体系。

### 4.3 JWT 验证

对 `/manage` 和 `/manage/*` 的每个请求执行：

1. 从 `Cf-Access-Jwt-Assertion` 读取 assertion；缺失即拒绝。
2. 从 `${TEAM_DOMAIN}/cdn-cgi/access/certs` 获取并缓存 JWKS；遵循库的安全刷新策略。
3. 只允许预期算法（当前 Access 路径为 `RS256`），拒绝 `none` 和算法混淆。
4. 验证密码学签名。
5. 精确验证 issuer 为 Team Domain origin。
6. 验证 audience 包含当前项目独立 Audience。
7. 验证 `exp`、`nbf`；时间偏差只使用库的最小合理容忍值。
8. 验证失败、JWKS 网络失败或配置异常统一返回 `403` 和 `Cache-Control: no-store`，响应不得包含 token、claim、配置值或内部异常。

如业务需要将 Writer 身份传给后续 handler，只能使用**验签后的** claim。应用必须删除客户端传入的同名内部身份 header，再写入自己的可信值，避免 header spoofing。

### 4.4 方法与 Origin 边界

统一入口的判定顺序建议为：

1. 标准化 HTTP 方法；
2. 拒绝未识别方法；
3. 公开路径上的写方法直接拒绝；
4. 公开安全方法放行；
5. 管理路径的所有方法先验 JWT；
6. 普通反代链的管理写方法精确比较 `Origin === CF_ACCESS_ALLOWED_ORIGIN`；如果 Worker 回源会改写或丢失原始 Origin，则统一客户端封装发送自定义 CSRF Origin header，服务端精确匹配该值与 `CF_ACCESS_ALLOWED_ORIGIN`；
7. 无副作用的 `OPTIONS` 仅在框架确有需要时处理，不得让它成为业务写入通道。

自定义 CSRF header 必须由统一写请求函数添加，不能允许普通 HTML form 提交替代；应用不得向跨域来源返回允许该 header 的 CORS 响应，预检必须失败。该 header 解决的是 Worker 代理链破坏原生 Origin 信号的问题，不能替代 Access JWT。若目标框架在应用 middleware 前处理 `TRACE`、`CONNECT` 或其他方法，则由 NPM/nginx 显式返回 `403`/`405`。

## 五、前端与路由体验

- 公共页面直接渲染只读详情，不要求 Access 登录。
- 公共页面不得渲染会触发写入的按钮、手势、自动保存或导出链接。
- “进入管理区”链接指向 `/manage`；由 Access 负责登录，应用不自建登录表单。
- 管理区可以复用公共组件，但必须通过显式 `readOnly`/`canWrite` 模式控制交互；服务端仍独立校验每次写请求。
- 客户端收到 `401/403` 时回滚乐观更新并提示重新进入管理区，不能静默丢数据。

## 六、Docker、反向代理与 Secret

源站认证与应用鉴权分别验收：JWT 拒绝伪造写入，不代表无 JWT 的公开读取已防止绕过 Cloudflare。Worker Secret / AOP、网络隔离和媒体入口规则统一见 [网络安全架构](../architecture/家庭NAS网络安全架构.md)。

- 自研模块必须容器化；应用端口只加入反代所需的 Docker front 网络，不发布到宿主机公网。
- `.env` 加入 `.gitignore`，仓库只提交空值 `.env.example`。
- Compose 用运行时 `environment`/secret mount 注入配置，禁止把真实值作为 Docker build args 或写进镜像层。
- 日志不得记录 `Cf-Access-Jwt-Assertion`、Cookie、Authorization、请求正文或完整登录跳转 URL。
- Codex/脚本检查 `.env` 时只能输出变量名以及 `SET/MISSING`，不得输出长度、前后缀或真实值。
- NPM 必须保留目标 header、拒绝未知 Host，并按项目网络只连接应用入口；数据库不得与 NPM 同网。

## 七、强制测试矩阵

| 层级 | 用例 | 预期 |
|---|---|---|
| 静态 | 搜索全部写路由 | 写入只存在于 `/manage/*` 或独立 `/integrations/*` |
| 公开 | 匿名 `GET`/`HEAD` 页面与只读 API | `200`，数据符合公开口径 |
| 公开 | `POST`/`PUT`/`PATCH`/`DELETE` | `403`/`405`，数据库不变 |
| 公开 | `TRACE`/`CONNECT`/`PROPFIND` 等异常方法 | NPM 显式返回 `403`/`405` |
| 管理 | 缺少 assertion | 应用直连测试返回 `403` |
| 管理 | 随机或伪造 assertion | `403` |
| 管理 | 过期、错误 issuer、错误 audience 的签名测试 token | 全部 `403` |
| 管理写 | 有效 JWT + 错误/缺失 Origin 或 CSRF Origin header | `403` |
| 管理读 | 真实 Writer 登录 | 成功，并确认身份来自验签 claim |
| 管理写 | 真实 Writer + 正确 Origin/CSRF header | 创建、修改、删除一条专用测试数据均成功 |
| 源站 | 家庭 IP/8443 + 目标 SNI/Host + 伪造 header | 在 TLS、NPM 或应用层失败；不得写入 |
| 自选 IP（如启用） | DNS 指向候选 CF IP | TLS 通过、含 `CF-Ray`、公开 GET 成功、管理路径 Access 302 |
| 日志 | 完整测试后扫描 | 无 token、Cookie、Authorization、敏感 claim 和异常堆栈泄漏 |
| 数据 | 测试前后核对 | 失败用例无副作用，专用测试数据已清理，数据库健康 |

真实 Writer 验收不得要求用户把 Cookie/JWT 粘贴给 Codex。优先让用户在自己的已登录浏览器执行专用测试，或使用不回显凭据的本地自动化；测试数据必须有明确前缀并在验证后通过正常业务 API 删除。

## 八、变更上线与回退门槛

以下是后续新上线或鉴权变更的验收标准，不代表 Homeland 当前入口关闭。执行前先读 [运维记录](../operations/运维记录.md)，按本次变更补齐证据：

- 应用构建和类型检查通过；
- 容器健康检查通过，端到端 smoke test 通过；
- Cloudflare Access 精确覆盖 `/manage` 与全部子路径；
- 匿名、伪造、过期、错误 issuer/audience/Origin 全部失败；
- 合法 Writer 读取和完整写入生命周期成功；
- 源站绕过失败；
- 日志无凭据泄漏；
- 数据库/持久化卷已有备份，且知道如何回滚镜像和路由；
- 使用自选 IP 时，确认入口仍命中 Cloudflare，而不是误指向家庭源站。

若任何一项失败，保持应用 fail closed，并关闭管理路径公网入口；不要增加临时免鉴权开关。公开只读区是否继续开放，应按其数据敏感度单独决定。

## 九、原手册参考来源

- Cloudflare Access JWT 验证：<https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/>（访问日期：2026-08-30）
- Self-hosted Application：<https://developers.cloudflare.com/cloudflare-one/applications/configure-apps/self-hosted-apps/>（访问日期：2026-08-30）
- Service Token：<https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/>（访问日期：2026-08-30）
- Access 应用路径行为：<https://developers.cloudflare.com/cloudflare-one/access-controls/policies/app-paths/>（访问日期：2026-08-30）
