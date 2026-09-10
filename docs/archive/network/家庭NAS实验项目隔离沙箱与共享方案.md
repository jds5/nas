# 家庭 NAS 实验项目公开读与登录写方案

> **迁移说明（2026-09-10）**：来自 Homeland；原文中的“本轮”“已完成”和版本信息保留原记录日期，本次未复验现网。本文仅供历史追溯，实施以[现行网络安全架构](../../architecture/家庭NAS网络安全架构.md)及现场核验为准。原文的应用仓库、根目录配置及前端路径指 Homeland。

> **本文档定位**：历史方案归档，保留早期“双 hostname”讨论过程，**不得作为现行实施依据**。唯一现行架构与鉴权口径见[家庭 NAS 网络安全架构](../../architecture/家庭NAS网络安全架构.md)：单 hostname、固定 `/manage/*`、服务端验证 Cloudflare Access JWT。
>
> **当前状态**：已被 2026-08-30 的单 hostname 方案取代；本文其余内容仅用于追溯，不再维护。
>
> **配套文档**：[家庭 NAS 网络安全架构](../../architecture/家庭NAS网络安全架构.md) · [本地 Docker 与 NPM 部署记录](../../operations/本地Docker与NPM部署记录.md) · [NAS 运维记录](../../operations/运维记录.md)

## 一、结论

**可以，而且这是当前最合适的简化方案：匿名可读，登录后统一可写。**

不需要角色、管理员、逐字段权限，也不需要先部署完整沙箱平台。推荐采用一个二元规则：

- `GET`、`HEAD`：公开；
- `POST`、`PUT`、`PATCH`、`DELETE`：必须是允许名单中的登录用户；
- `OPTIONS`：只用于协议/CORS 预检，不得产生副作用；
- 未明确列出的新方法或新写路由：默认拒绝。

这个方案适合“内容可以公开、写入者彼此信任、数据可以恢复”的实验项目。它不能保护公开数据的隐私，也不能阻止已登录朋友互相修改或删除数据。

## 二、推荐架构：同一应用，两个 hostname

```text
匿名访客
    │
    ▼
home.example.com（公开 hostname）
    │ GET / HEAD
    ▼
Homeland 同一容器 ──────► 公开页面和只读 API
    │
    └─ POST / PUT / PATCH / DELETE 无合法 Access JWT → 401/403

受邀写入者
    │
    ▼
edit-home.example.com（Cloudflare Access 保护整个 hostname）
    │ 精确邮箱 / OTP 或既有 IdP
    ▼
Cloudflare 注入 Cf-Access-Jwt-Assertion
    │
    ▼
Homeland 同一容器
    ├─ 验证 JWT 签名、issuer、audience、expiry
    ├─ 校验精确 Origin / CSRF
    └─ 允许所有写操作
```

Stock 可采用相同模式：`stock.example.com` 公开读，`edit-stock.example.com` 登录写。两个 hostname 可以指向同一容器和同一套页面；公共站点即使显示编辑按钮，未携带合法身份的写请求也必须在服务端失败。

使用两个 hostname 的好处是不用把当前混合 GET/POST 的路由全部拆开：写入者在编辑 hostname 登录和操作，公共 hostname 仍可匿名访问相同 GET 路由。真正的授权仍由应用统一中间件完成，不能只依赖 hostname 或前端 UI。

## 三、为什么不只靠 Cloudflare 路径规则

Cloudflare Access 可以保护特定 hostname 或 path，且更具体的 path 规则优先。但 Homeland 当前存在同一路径同时承担读写的情况：

| 路径 | 公开方法 | 写方法 |
|---|---|---|
| `/api/bills` | `GET` | `POST` |
| `/api/bills/[id]` | `GET` | `PUT`、`DELETE` |
| `/api/excel-files` | `GET` | `POST` |
| `/api/excel-files/[id]` | `GET` | `POST`、`PUT`、`DELETE` |
| `/api/payments` | — | `POST` |
| `/api/payments/[id]` | — | `PUT`、`DELETE` |

Access 的常规应用规则最适合按 hostname/path 划分，不应假定它会自动识别“同一路径的 GET 公开、POST 登录”。如果直接保护 `/api/bills`，匿名 GET 也会要求登录；如果保持路径公开，POST 也会公开。

可选方案有两个：

1. **推荐**：保留现有路由，用两个 hostname + 应用统一方法鉴权。
2. 将所有写 API 迁到 `/api/write/*`、编辑页迁到 `/edit/*`，再用 Access path 规则保护；路由更清晰，但改动更大。

## 四、最小可靠实现

### 4.1 登录与写权限

- 使用 Cloudflare Access、成熟 OAuth/OIDC 或维护良好的会话库；不自制密码哈希、找回密码和长期 Token。
- 朋友共享使用**精确邮箱允许名单**。不要配置 `Include Everyone`，也不要把“使用 OTP 登录”本身当成授权条件，否则所有有效邮箱都可能获得写权限。
- 不做角色系统：登录且在允许名单内就是 Writer；其他人只有匿名读。
- 每次写请求都经过同一个服务端 `requireWriter`/middleware，失败时返回 401 或 403。不能在每个 route 里手工复制一段容易漏掉的判断。
- 新增 route 时默认禁止不安全方法，测试必须证明未登录写入失败。

### 4.2 Access JWT 与源站防绕过

- Cloudflare 会向 Origin 发送 `Cf-Access-Jwt-Assertion`；应用应验证签名、issuer、audience 和过期时间，不能只检查 header 是否存在。
- 不能相信客户端自行提交的 `CF_Authorization` Cookie 或伪造 header。Cloudflare 官方推荐 Origin 验证 assertion header。
- 若使用 Cloudflare Tunnel，可启用 `Protect with Access` 由 `cloudflared` 验证 Token；应用继续做方法级拒绝可作为第二层。
- 当前公网 `:8443`、备用 hostname 或其他代理路径若能绕开 Access，写请求仍必须被应用 JWT 校验拒绝。随机 hostname 不是安全边界。
- 公共 hostname 与编辑 hostname 都到同一 Origin 时，服务端不能仅凭 `Host=edit-*` 放行写入。

#### Cloudflare Access 与 Homeland 应用鉴权互通核对（2026-08-30）

核对 Cloudflare 官方文档后确认：**Access 可以作为 Homeland 的外部身份提供者，Homeland 在写入前自行验证 Access 签发的 JWT。**

Access 放行请求后，Cloudflare 会向 Origin 添加 `Cf-Access-Jwt-Assertion` 请求头；浏览器请求还可能携带 `CF_Authorization` Cookie。应用应验证 assertion header，不依赖不保证传递的 Cookie。验证至少包括：

1. 用 `https://<team-name>.cloudflareaccess.com/cdn-cgi/access/certs` 的公钥/JWKS验证签名；
2. `iss` 精确匹配本账户的 Access team domain；
3. `aud` 包含 Homeland 编辑端 Access Application 的 Audience tag；
4. Token 未过期，并按库默认检查 `nbf` 等时间约束；
5. 如应用内仍保留 Writer allowlist，`email` 必须精确命中。Access 策略的精确邮箱 allowlist 是第一层，应用 allowlist 是可选第二层。

只检查 header 存在、只解析 JWT payload、只检查 `email` 或只相信 NPM/Cloudflare 传入的 Host 都不足够。攻击者可以在直连家庭 `:8443` 时伪造同名 header，但无法生成通过账户公钥、issuer 和 audience 联合验证的签名 Token。

结合 Homeland 当前 Next.js 14 结构，推荐的最小实现是：

- 公共 hostname 不要用 `Bypass` 来假装已鉴权；它就是公开读入口，其写请求由应用固定拒绝。Cloudflare 明确说明 Bypass 会关闭 Access 安全控制且不记录请求。
- 编辑 hostname 创建独立 Self-hosted Access Application，使用精确邮箱 Allow policy；不使用 `Include Everyone`。
- 全局 middleware 覆盖 `/home/api/:path*` 及任何未来写入入口：`GET/HEAD/OPTIONS` 按明确规则处理，`POST/PUT/PATCH/DELETE` 以及未识别方法默认要求有效 Access JWT。
- 对写方法同时要求 `Host` 为编辑 hostname，且 `Origin` 精确匹配 `https://<edit-hostname>`；Host/Origin 是路由与 CSRF 防线，不替代 JWT。
- 用类似 `jose` 的成熟 JWT 库和远程 JWKS缓存，配置 `CF_ACCESS_TEAM_DOMAIN` 与 `CF_ACCESS_AUD` 环境变量；缺配置、JWKS 不可用或验证异常时 fail closed 返回 403。
- NPM 必须原样转发 Cloudflare 已注入的 assertion header，不能在 NPM 删除后期待再注入，因为 NPM 之后已不再经过 Cloudflare。应用端通过完整验签拒绝直连源站时的伪造同名 header。

当前代码尚无 middleware、JWT 验证库或上述环境变量，因此本节是经官方机制确认的可行设计，**不是已实施状态**。

### 4.3 CSRF

登录 Cookie 会随浏览器请求发送，写操作仍面临 CSRF：

- 所有状态变更只能使用 `POST`、`PUT`、`PATCH`、`DELETE`，禁止用 GET 写入、删除、触发任务或导入数据。
- 对不安全方法校验 `Origin`，只接受准确的编辑 hostname；缺失、`null` 或不匹配时默认拒绝。
- 会话 Cookie 使用 `Secure`、`HttpOnly` 和合适的 `SameSite`；SameSite 只是防御层，不能代替 Origin/CSRF Token。
- 公共与编辑 hostname 属于同一 registrable domain 时属于 same-site，公共子域的漏洞可能绕过只依赖 SameSite 的假设；重要写操作应使用 CSRF Token 或等价框架能力。
- 删除全部数据、批量导入等高影响操作增加二次确认，但二次确认不能代替服务端鉴权。

### 4.4 XSS 与不可信内容

登录写模型会让存储型 XSS 更危险：攻击者若能把脚本写入公开内容，等合法 Writer 查看时，脚本可能借其会话执行写操作。

Homeland 当前 `MarkdownRenderer.tsx` 启用了 `rehypeRaw`，但没有 `rehype-sanitize`。当前 Markdown 来自仓库只读文档时风险受限；如果以后允许登录用户编辑 Markdown，必须：

- 禁用原始 HTML，或在 `rehypeRaw` 之后加入严格的 `rehype-sanitize` schema；
- 继续依赖 React 默认文本转义，不使用未经净化的 `dangerouslySetInnerHTML`；
- 限制 Markdown/上传文件大小，避免大输入造成解析拒绝服务；
- 为页面设置合理 CSP，作为 XSS 的额外缓解层。

### 4.5 数据与审计

- 所有公开 GET 返回的数据都视为公开信息；API 响应、错误栈、导出文件和隐藏字段也要单独审查。
- Homeland 的 `/api/export` 是 GET。如果保持匿名，它等于公开批量导出；需明确这是期望行为，否则应移到登录写侧或单独保护。
- 所有 Writer 权限相同，但仍记录 `email + 时间 + 操作 + 对象 ID`，便于定位误操作。
- 删除优先软删除或保留短期修订记录；至少保证 SQLite 能从备份恢复。
- 登录不能防止合法 Writer、账号失窃或恶意浏览器扩展破坏数据，因此备份与恢复仍是最后防线。

## 五、仍然需要的 Docker 最小隔离

“登录后才能写”只保护业务数据，不代表容器失陷后安全。公开 GET 页面、图片解析、依赖库和查询参数仍可能存在 RCE、SSRF 或拒绝服务漏洞。

不必默认部署 rootless + gVisor，但至少保留：

- Homeland/Stock 各自独立 front 网络，只连接入口代理与本应用；不加入共享 `nas-net`、`common_network`。
- 应用保持非 root 运行，不使用 `privileged`、host network、host PID、设备透传或新增 capabilities。
- 不挂 Docker Socket；docs 只读，只有 SQLite/上传等明确目录可写。
- 应用容器不能访问 NAS 管理面、数据库、SMB、备份目录和其他生产容器。
- 设置 CPU、内存、PID、日志和请求体限制；公开读接口也需要基本限流和缓存。
- 完成真实恢复演练，而不是只确认备份文件存在。

如果未来要运行第三方插件、用户代码或高风险解析器，再把 rootless/gVisor 作为加固项；当前自研早期 Web 项目不必先承担这套复杂度。

## 六、主要风险判断

| 风险 | 是否仍存在 | 控制方式 |
|---|---|---|
| 匿名用户直接修改数据 | 可大幅降低 | 所有不安全方法统一验证 Writer 身份 |
| 漏掉一个写接口 | **存在，且常见** | 全局 middleware 默认拒绝 + 自动化遍历测试 |
| 隐藏按钮但直接调用 API | **存在** | 服务端鉴权；前端状态不作为安全依据 |
| 绕过 Cloudflare 直连 Origin | **存在** | 验证 Access JWT + 收紧/关闭替代入口 |
| CSRF 借 Writer 会话写入 | **存在** | 精确 Origin + CSRF Token + SameSite |
| 存储型 XSS 借 Writer 身份写入 | **存在** | 输出转义、HTML 净化、CSP |
| Writer 误删/恶意修改 | **设计上接受** | 审计、软删除、备份恢复 |
| 公开信息被抓取/批量导出 | **设计上存在** | 公开字段审查、限流、单独保护 export |
| 公开读路径触发 RCE/SSRF | **仍存在** | 依赖更新、输入验证、Docker 网络隔离 |
| 暴力登录/账号失窃 | **存在** | 外部 IdP/OTP、精确 allowlist、会话过期与撤销 |

对不含敏感数据、Writer 彼此信任且可恢复的实验项目，这些剩余风险可以接受；对真实财务数据、个人隐私、凭据或唯一副本则不够。

## 七、Homeland 当前差距

本次代码核对确认：

1. 多组 GET 与写方法共用 route path；
2. 未找到认证 middleware、Access JWT 验证、Origin 校验或 CSRF 防护；
3. `POST /api/bills` 等写接口仅做少量字段存在性检查；
4. `/api/export` 当前为 GET，若整站公开会成为公开导出；
5. Markdown 渲染启用 `rehypeRaw`，未接入 `rehype-sanitize`；
6. Compose 仍加入 `common_network` 和 `nas-net`。

因此当前状态不是“公开读、登录写”，而是**公开路径可直接写**。必须完成实现与端到端测试后才能变更状态。

Stock 不在当前仓库中，本次无法核对其 route、认证、渲染和数据导出逻辑；不能用 Homeland 的结论替代 Stock 验证。

## 八、验收清单

### 匿名访问

- [ ] 所有预期页面、GET API 正常浏览
- [ ] 对每个 API 直接发送 POST/PUT/PATCH/DELETE 均返回 401 或 403
- [ ] GET/HEAD/OPTIONS 不修改数据库、不触发任务或导入
- [ ] `/api/export` 是否公开已有明确决策
- [ ] 公共响应不含 Secret、私有字段、错误栈或内部路径

### 登录写入

- [ ] 允许名单邮箱能从编辑 hostname 登录并完成全部正常写操作
- [ ] 非允许邮箱、过期 Token、错误 audience/issuer、伪造 JWT 均失败
- [ ] 公共 hostname 即使携带伪造 Host/header 也不能写
- [ ] 错误 Origin、跨站表单和无 CSRF Token 的受保护写请求失败
- [ ] 新增 route 未显式声明安全策略时默认拒绝写入

### XSS、恢复与容器边界

- [ ] 存储内容中的脚本、事件属性和危险 URL 不能在 Writer 页面执行
- [ ] 删除/批量修改有审计记录，SQLite 恢复演练成功
- [ ] 未认证洪泛只触发当前项目资源限制，不拖垮 NAS
- [ ] 应用不能访问 NPM:81、Docker Socket、SMB、数据库和其他生产容器
- [ ] 公网扫描没有出现新的管理端口

## 九、来源

本地证据（2026-08-26 查阅）：

- [`../frontend/src/app/api`](../../../../homeland/frontend/src/app/api)：Homeland 现有 GET/POST/PUT/DELETE routes
- [`../frontend/src/components/MarkdownRenderer.tsx`](../../../../homeland/frontend/src/components/MarkdownRenderer.tsx)：`rehypeRaw` 配置
- [`../docker-compose.yml`](../../../../homeland/docker-compose.yml)：非 root 用户、bind mount 与现有网络

官方资料（2026-08-26 查阅）：

- [Cloudflare Access application paths](https://developers.cloudflare.com/cloudflare-one/access-controls/policies/app-paths/)
- [Cloudflare Access policies and Bypass behavior](https://developers.cloudflare.com/cloudflare-one/access-controls/policies/) （2026-08-30 复核）
- [Cloudflare Access JWT validation](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/) （2026-08-30 复核）
- [Cloudflare self-hosted application and origin token validation](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/)
- [OWASP Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)
- [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- [react-markdown Security](https://github.com/remarkjs/react-markdown#security)
- [rehype-sanitize](https://github.com/rehypejs/rehype-sanitize)

## 下一步行动

1. 为 Homeland 设计公共/编辑两个 hostname，并实现一个覆盖所有不安全 HTTP 方法的 Writer middleware；先在本地与测试入口完成匿名写入失败、JWT 伪造失败和正常写入成功三类测试。
2. 明确 `/api/export` 是否允许匿名，并决定 Markdown 是否永远只读；这两项直接影响公开数据面与 XSS 加固范围。

## 修订日志

| 日期 | 版本 | 变更摘要 |
|---|---|---|
| 2026-08-30 | v3.1 | 核对 Cloudflare Access 与应用侧鉴权互通机制：确认 Homeland 可在写入前验证 `Cf-Access-Jwt-Assertion`，明确签名/issuer/audience/有效期、公共与编辑 hostname、全局 middleware、Origin/CSRF 和 fail-closed 要求 |
| 2026-08-26 | v3.0 | 按新决策改为公开读、登录写的二元权限模型；默认保留普通 Docker，仅用两个 hostname、Access JWT 和全局方法鉴权；增加 CSRF、XSS、Origin 绕过及公开导出风险 |
| 2026-08-26 | v2.0 | 曾将默认方案改为共享 lab 用户下的 rootless Docker + gVisor；现降为高风险负载的可选加固 |
| 2026-08-26 | v1.0 | 曾提出 Incus/KVM 单项目虚拟机；现仅保留为敌对代码隔离选项 |
