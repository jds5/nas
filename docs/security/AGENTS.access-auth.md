## 实验 Web 项目 Cloudflare Access 鉴权强制规则

> **迁移说明（2026-09-10）**：来自 Homeland；原文中的“本轮”“已完成”和版本信息保留原记录日期，本次未复验现网。

所有“公开读、认证写”的实验 Web 项目必须使用单 hostname 和固定 `/manage/*` 管理子树。公开路由只能执行无副作用的 `GET`/`HEAD`；管理区允许受保护读取，但所有创建、修改、删除、上传、批量导入/导出和任务触发必须位于 `/manage/*`。导出默认属于管理操作；浏览器之外的 webhook/机器调用必须放在独立 `/integrations/*`，使用签名、防重放、Cloudflare Service Token 或 mTLS，不得混入公开写入或浏览器 Access 流程。

每个项目必须有独立 Cloudflare Access Application 和 Audience。各项目在**自己的服务端**使用该语言经批准的成熟 JWT/JWKS 库，按 Cloudflare 官方方式验证 `Cf-Access-Jwt-Assertion` 的密码学签名、精确 issuer、本项目 audience、`exp` 与 `nbf`；禁止自行实现密码、Session、OAuth/OIDC、JWT 解析或签名算法。全局 middleware/统一服务端入口必须默认拒绝 `/manage/*` 之外的 `POST`/`PUT`/`PATCH`/`DELETE` 及未识别 HTTP 方法；框架在 middleware 前自行处理、报错或断开的方法（如部分框架的 `TRACE`/`CONNECT`）必须由 NPM/nginx 显式返回 403/405。管理区每个请求都须验证 Access JWT，写请求还必须校验 CSRF：普通代理链精确校验浏览器 `Origin`；Worker 会改写/丢失原始 Origin 时，由统一客户端封装发送不可用 HTML 表单构造的自定义 CSRF Origin header，服务端将其精确匹配允许 origin，并且不得开放跨域预检。缺少配置、无 Token、JWKS 异常、Token 过期、issuer/audience/CSRF 来源不符时一律 fail closed，禁止开发/应急免鉴权开关，禁止只检查 header/email/Host、只隐藏前端按钮或逐路由选择性鉴权。

Cloudflare 验签配置必须从不入库的 `.env` 注入，仓库只提供无真实值的 `.env.example`。Cloudflare API Token/Key、Service Token Secret、Tunnel Token 等凭据必须最小权限、分项目、可轮换，不得写入 Git、AGENTS.md、Markdown、源码、镜像、前端 bundle、构建参数、日志、错误响应或测试快照；应用运行时验证 Access JWT **不需要 Cloudflare API Token**，因此禁止将该 Token 注入应用容器。AI/脚本诊断 `.env` 时只允许列出变量名或结果是否存在，不得读取、回显、复制或传递变量值；任何疑似泄漏必须立即停止并轮换。

上线前必须以自动化测试证明：匿名公开 GET 成功；公开路径的不安全方法失败；管理区的缺失/伪造/过期 Token、错误 issuer/audience/Origin 全部失败；合法 Writer 成功；直连源站伪造 header 失败。必须以路由静态检查或测试证明不安全方法只存在于 `/manage/*`；未通过全部测试不得创建/恢复 NPM 公网转发。
