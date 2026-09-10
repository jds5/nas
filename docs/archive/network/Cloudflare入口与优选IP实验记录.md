# Cloudflare 入口与优选 IP 实验记录

> **迁移说明（2026-09-10）**：来自 Homeland；原文中的“本轮”“已完成”和版本信息保留原记录日期，本次未复验现网。本文仅供历史追溯，实施以[现行网络安全架构](../../architecture/家庭NAS网络安全架构.md)及现场核验为准。

> **本文档定位**：归档 2026-08-23 至 2026-08-24 对 Cloudflare 标准入口、灰云自选 IP、Worker 回源与 VLESS 入口的测试过程。这里保留样本、失败分支和诊断证据，不作为家庭 NAS 当前安全基线。
>
> **配套文档**：[家庭 NAS 网络安全架构](../../architecture/家庭NAS网络安全架构.md) · [本地 Docker 与 NPM 部署记录](../../operations/本地Docker与NPM部署记录.md)
>
> **阅读提示**：以下为从原主文档迁出的历史原文，原章节编号和当时状态保持不变；涉及公网、DNS、证书和路由的结论均有时效性，复用前必须重新验证。

## 十、Cloudflare 标准 443 入口性能诊断与优化顺序（2026-08-23）

### 10.1 已验证事实

本轮从当前项目环境对 `https://rokano.org/home`、`/home/docs` 和 `/home/api/upcoming` 各进行多次 IPv4 `curl` 分段计时，并用 `--resolve rokano.org:8443:127.0.0.1` 请求同一 NPM 虚拟主机作为本机源站基线。结果只代表 2026-08-23 本轮链路，不冒充全国多运营商数据：

| 请求 | Cloudflare 标准 443 | 本机直达 NPM:8443 | 判断 |
|---|---:|---:|---|
| `/home` | TTFB 3.12–5.83s；总耗时 4.66–7.02s | TTFB 57.7–73.5ms；总耗时 59.0–79.4ms | Cloudflare 路径慢约两个数量级，源站应用不是主瓶颈 |
| `/home/docs` | TTFB 1.48–2.89s；总耗时 2.07–3.72s | TTFB 45.9–46.8ms；总耗时 46.8–47.5ms | 同上 |
| `/home/api/upcoming` | 已观察一轮 TTFB 3.05s；命令到达总超时前未完成三轮 | TTFB 9.1–10.5ms | Cloudflare 样本不完整，只能确认已观察的一轮明显慢 |

Cloudflare 响应头显示 `cf-ray: ...-NRT`，即本轮请求由东京边缘节点处理；`cf-cache-status: DYNAMIC`，且 Next.js HTML 返回 `Cache-Control: private, no-cache, no-store`。这与“大陆用户先跨境到 Cloudflare 境外边缘，再由边缘访问南京家庭源站”的高延迟表现一致，但尚未完成南京移动/联通/电信三网、蜂窝网络和晚高峰的独立测试，不能把 NRT 或上述数值外推为所有用户的固定结果。

### 10.2 优化顺序

1. **先建立直连对照入口，不先购买 Argo**：为受控测试建立独立 DNS-only hostname，使用显式 `:8443` 直达 NPM；保留应用鉴权、TLS、限流和 Host 白名单。分别从家庭 Wi-Fi、手机蜂窝及至少另一运营商测 DNS/TCP/TLS/TTFB/下载速度。根域已有 Per-hostname AOP 规划时，不复用被强制 AOP 的 hostname。
2. **在 Cloudflare 面板看源站指标**：进入 `Speed → Origin Analytics`，对照 P50/P95/P99 origin response time 和慢路径。若 Cloudflare 看到的 origin time 也很高，再排查 Cloudflare→南京回源；若 origin time低而用户仍慢，主要问题在用户→Cloudflare 边缘，Argo帮助有限。
3. **只缓存明确公开且不可因用户变化的静态资源**：检查 `/_next/static/*` 的 `CF-Cache-Status`，使用长文件名哈希资源的缓存头提高 HIT。`/home` 文档、预算、账单和 API 含私人/动态内容，不启用笼统 `Cache Everything`；Cloudflare 官方明确警告动态 HTML 可能把不应共享的内容返回给其他访问者。
4. **确认 HTTP/3 但不把它当根治方案**：当前响应已带 `alt-svc: h3=":443"`，说明边缘提供 HTTP/3。它可能改善丢包环境下的握手和并发体验，但不能消除大陆到境外边缘的物理/路由延迟。
5. **最后才做有截止条件的 Argo 试验**：Argo优化的是 Cloudflare网络到源站的路由。仅当 Origin Analytics 显示回源链路占主要延迟时，才开启短期付费试验，记录开关前后相同运营商、相同时段、相同冷/热缓存请求；达不到预先设定的改善目标即关闭。不要以官方全球平均宣传值替代本项目实测。

### 10.3 如果目标是大陆稳定低延迟

- **最低成本、最快验证**：性能敏感或原生客户端服务使用 DNS-only + `:8443` 直连；Cloudflare橙云保留给轻量 Web、安全优先或不敏感入口。代价是源站公开，必须继续执行最小端口、NPM、应用鉴权、IPv6防火墙和更新策略。
- **必须使用无端口标准 443**：家庭运营商阻断入站 443 时，Cloudflare Origin Rule 只能隐藏端口，不能保证大陆路径质量。需要另设能监听 443、线路合适的中继/VPS，通过 WireGuard等受控链路回家；这引入月费、单点、带宽和运维责任，选型前须从实际运营商测试线路。
- **真正的 Cloudflare 中国大陆节点**：Cloudflare 官方说明 China Network 由京东云运营，是 Enterprise 的单独订阅，并要求每个接入根域具有有效 ICP 备案/许可。它不是免费/个人套餐中可以通过“优选 IP”开出的功能，因此当前项目不把第三方优选 IP 脚本作为正式架构。

### 10.4 下一步验收记录

- [ ] 建立不影响现有入口的 DNS-only 测试 hostname，并确认仅转发既有 `8443/TCP`，不新增端口。
- [ ] 南京家庭 Wi-Fi与手机蜂窝各连续测 10 次：DNS、TCP、TLS、TTFB、总耗时、下载速率；记录运营商与时段。
- [ ] 同时记录 Cloudflare入口的 `CF-Ray` 末尾 colo、`CF-Cache-Status` 和 Origin Analytics P50/P95。
- [ ] 分别比较 HTML/API（动态）和 `/_next/static/*`（静态），避免把缓存收益误认为线路收益。
- [ ] 根据结果二选一：动态入口切直连；或仅在回源段确认为主瓶颈时试用 Argo。

### 10.5 “Cloudflare 优选 IP”在本项目中的适用边界（2026-08-23）

#### 结论

存在**窄幅、客户端侧实验空间**，但不适合作为 `rokano.org` 的公共权威 DNS 或正式家庭服务入口。推荐定位为“诊断某个运营商到 Cloudflare 不同 Anycast 前门的路径差异”，最多在少量自有设备上做可随时撤销的本地 DNS/hosts 对照；不把第三方优选域名、硬编码地址或 Cloudflare for SaaS 绕法纳入生产架构。

Cloudflare 官方说明：橙云记录由 Cloudflare 返回动态分配的共享 Anycast IP，地址可能变化；Anycast 依靠 BGP 把访问者送到网络选定的数据中心。普通 Cloudflare DNS 记录没有“指定某个边缘机房/IP”的正式控制项。因此所谓优选 IP并不是开启官方中国大陆节点，而是客户端绕过正常 DNS答案，尝试连接另一个 Cloudflare共享入口；后续仍由 Cloudflare按 Host/SNI识别站点并回源。

#### 本轮只读试验

测试方法：保持 `rokano.org` 在 Cloudflare 的橙云、TLS、Origin Rule和AOP配置不变，仅用 `curl --resolve rokano.org:443:<候选IP>` 临时覆盖客户端解析。候选取自 Cloudflare 官方公开IP段；未修改公共DNS或路由器。

- 多个候选能返回正确证书和 HTTP 200，证明“自有客户端指定Cloudflare入口IP后仍通过现有Host配置、代理和AOP回源”在当前时点技术可行。
- 同一批候选中存在 TLS EOF和12秒连接超时；不能认为“官方IP段内任一地址都可作为稳定Web前门”。Cloudflare官方也说明部分产品IP不会连接源站，公开网段不是供用户任意选址的服务清单。
- 能通的若干候选 `CF-Ray` 落点为 `LAX`（洛杉矶），并非更近的中国大陆或香港节点；“换IP”不等于选择地理位置。
- 暂时表现较好的一个候选在5次请求中，TTFB约0.86–1.66秒、总耗时约1.51–2.30秒；同期默认DNS 5次TTFB约0.85–3.00秒、总耗时约1.91–3.76秒。候选在这组短样本中抖动较小，但仍远慢于本机源站约60ms，且尚未覆盖不同运营商、晚高峰、IPv6、HTTP/3和长期可用性。
- 以上结果不能用于公布具体“优选IP”：单一时段、单一出口的短测会产生幸存者偏差；地址可用性和BGP路径都可能变化。

#### 三种做法的判断

| 做法 | 是否保留Cloudflare安全链 | 本项目判断 |
|---|---|---|
| 自有设备本地DNS/hosts覆盖，公共记录继续橙云 | 当前测试中保留：TLS、Host代理、Origin Rule和AOP均正常；仍须回归WAF/Access | **可做限时A/B测试**。只适合少量自有设备；需同时控制AAAA/IPv6与缓存，设自动回退和过期时间 |
| 把同一公共记录改为灰云A记录并填Cloudflare共享IP | Cloudflare官方将灰云定义为直达记录，不提供代理保证；证书、路由和站点激活行为不应依赖偶然可用 | **不采用**。测试中的 `--resolve` 成功依赖站点公共记录仍处于橙云，不能证明改灰云后受官方支持 |
| 第三方“优选域名”CNAME、Cloudflare for SaaS或其他域名套Cloudflare | 证书、hostname验证、流量归属和第三方可见性复杂；非Enterprise启用SaaS须录入付费信息，且自定义hostname不能等于本SaaS zone自身名称 | **不采用为家庭生产入口**。收益未验证，增加第三方DNS/证书/隐私/故障边界，复杂度高于直连子域或正规中继 |

#### 若要继续实验，最低安全方案

1. 公共 `rokano.org` 保持橙云不动；只在一台测试客户端用本地DNS覆盖，不修改家庭其他设备。
2. 候选只来自 Cloudflare 官方当前IP列表；不把第三方收集的地址直接写进路由器、公共DNS或仓库。
3. 每个候选按家庭宽带、手机蜂窝和实际主要运营商分别测试，至少覆盖白天/晚高峰；记录成功率、P50/P95 TLS、TTFB、总耗时、`CF-Ray` colo和大文件吞吐。
4. 同时测默认DNS和DNS-only直连 `:8443`。若优选IP仍明显慢于直连，承认跨境Cloudflare链路的结构性限制，不继续堆叠脚本。
5. 测试WAF/Access、AOP直接回源阻断、WebSocket/API、HTTP/3和IPv6；只测首页200不足以证明功能与安全等价。
6. 本地覆盖设置短TTL/明确到期日和自动回退。任何连续失败或证书异常立即恢复系统DNS。

**决策建议**：当前数据足以支持做一次多运营商、跨时段的客户端侧A/B实验，但不足以支持改生产DNS。即使短期候选将页面总耗时从约2–4秒压到约1.5–2.3秒，收益也不如DNS-only直连解决得彻底；对家庭自用服务，应优先比较“Cloudflare安全入口”与“受控直连入口”，而不是把非官方优选IP维护变成新的长期基础设施。

### 10.6 类 ddns-go 的自动优选程序：可行架构与禁止边界（2026-08-23）

#### 不能更新当前 Cloudflare 橙云记录

Cloudflare橙云A/AAAA/CNAME记录中保存的内容用于告诉Cloudflare**如何连接源站**；访客DNS查询得到的Cloudflare Anycast地址由Cloudflare自己分配，且可能变化。因此：

- 把橙云A记录的内容从家庭公网IP改成“优选Cloudflare IP”，不会让访客收到该优选IP；反而会把Cloudflare的回源目标改成Cloudflare共享地址，造成错误回源或代理循环风险。
- 把记录改成灰云再填写Cloudflare共享IP，虽能让公共DNS直接返回该地址，却不再属于Cloudflare官方定义的代理记录；不能依赖其继续提供站点激活、Universal SSL、WAF、Access、缓存或稳定路由。
- 官方可控制代理响应地址的Static IP/BYOIP/Address Maps属于Enterprise能力，不是家庭程序定时改普通DNS记录可以替代的功能。

所以程序不应持有Cloudflare `DNS Edit` Token，也不应修改 `rokano.org`、Origin Rule或AOP配置。

#### 补充核验：灰云A记录直接指定Cloudflare IP不可作为本项目代理入口

业主进一步明确设想为：“hostname设为灰云，A记录填写程序测速选出的Cloudflare共享IP，并由NAS周期更新”。本轮使用当前已是DNS-only的 `immich.rokano.org` 做了不修改现网的等价模拟：通过 `curl --resolve` 分别把该hostname强制解析到5个Cloudflare候选IPv4，同时保留正确的TLS SNI和HTTP Host。

结果：5个候选均未返回HTTP响应；部分地址能完成TLS握手，但全部在6–12秒窗口内于首字节前超时。该结果只证明本项目当前配置下方案不成立，不能外推为Cloudflare所有账户/产品的普遍行为；但它足以否决在现网直接上线，因为“TCP/TLS能连到共享边缘”不等于Cloudflare控制面已为灰云hostname建立可用的HTTP代理路由、Origin Rule和AOP链路。

这也解释了前一轮看似矛盾的结果：对仍保持橙云的 `rokano.org` 使用 `--resolve` 指定候选IP能够返回200，是因为该hostname在Cloudflare控制面仍是正式代理状态；客户端覆盖DNS只替换入口地址，没有删除代理绑定。把hostname真正切为灰云后，不能假设这套绑定、证书续期和规则执行仍按橙云方式长期保留。

因此不实现“**普通灰云记录**的CF-IP DDNS”。若必须公开返回指定Cloudflare入口地址且仍获得正式代理能力，需要Cloudflare提供的静态IP/BYOIP/Address Maps，或进一步验证下文“Cloudflare for SaaS custom hostname + Worker + 灰云优选A记录”的组合；前者是Enterprise能力，后者能补上普通灰云缺失的hostname/证书/Worker绑定，但任意共享IP的A记录接入仍不属于官方默认支持方式。

#### 重新评估：结合Cloudflare Workers的灰云优选IP方案

业主指出的完整设想不是“普通灰云hostname直接撞Cloudflare IP”，而是先在Cloudflare控制面建立Worker/custom-hostname绑定，再由灰云DNS把客户端送到优选边缘IP：

```text
fast.rokano.org 灰云A → NAS程序选出的Cloudflare共享IP
        ↓ TLS SNI / Host仍为fast.rokano.org
Cloudflare for SaaS custom hostname（证书和hostname均active）
        ↓ Workers for Platforms按custom hostname或*/*匹配
反向代理Worker
        ↓ 显式fetch到独立origin hostname:8443
家庭NPM → Homeland
```

这个组合**有独立PoC价值**。Cloudflare Workers for Platforms官方文档明确列出：custom hostname为灰云时，按custom hostname匹配的Worker路由可以执行，`*/*`路由在橙云和灰云两种情况下均可执行。此前对 `immich.rokano.org` 的失败模拟没有创建SaaS custom hostname、证书和Worker绑定，只能否定“普通灰云”，不能否定这个完整组合。

但要区分官方支持层级：

| 组合 | 官方状态 | 是否能自行选IP |
|---|---|---|
| Workers Route + 普通DNS记录 | 官方要求hostname必须橙云 | 否，Cloudflare返回Anycast地址 |
| Worker Custom Domain | Cloudflare自动创建DNS记录和证书，Worker作为origin | 默认否，DNS由Cloudflare创建 |
| Cloudflare for SaaS custom hostname + 灰云CNAME到SaaS target | 官方生产路径；hostname与证书须active | 否，CNAME target最终地址由Cloudflare控制 |
| SaaS custom hostname + 灰云A到任意优选Cloudflare共享IP | A记录指向SaaS target默认不受支持；官方说明需要Apex Proxying | **技术上可能可用，但属非默认/非承诺旁路，必须PoC和持续回退** |

因此Workers解决的是“Cloudflare收到请求后由谁接管、证书是什么、往哪里回源”，但没有把任意共享Cloudflare IP变成官方支持的SaaS A记录目标。Cloudflare明确说明：custom hostname生产就绪要求`status=active`、`ssl.status=active`，且DNS指向SaaS CNAME target或Apex Proxying target；普通A记录指向target默认不受支持。

#### 若做隔离PoC，推荐边界

1. 不动现有 `rokano.org`；使用独立实验hostname，例如 `fast.rokano.org`。若SaaS zone/custom hostname同域配置发生限制，则需另一个受控zone，涉及新域名或收费前先由业主确认。
2. 启用Cloudflare for SaaS需要非Enterprise账户录入付费信息；先确认实际计划、计费和custom hostname配额，不将“面板能点开”写成免费承诺。
3. 创建反向代理Worker和custom hostname，完成hostname TXT预验证、证书DCV；以API的`status=active`和`ssl.status=active`为准，不以一次TLS成功代替。
4. 第一阶段按官方灰云CNAME→SaaS target跑通Worker、TLS、回源和安全控制，建立功能基线；第二阶段才在同一实验hostname上尝试灰云A→候选共享IP，确认它是否仍进入同一个Worker绑定。
5. Worker不得`fetch()`回自身hostname，避免递归。使用独立origin hostname或Tunnel/service binding；请求Host、认证头和真实客户端IP的信任边界须明确。
6. 现有Per-hostname AOP不应假定对Worker二次`fetch()`仍自动生效。PoC须实际验证；如不生效，优先用Tunnel隐藏源站，或在NPM校验Worker secret header并限制源站入口。
7. Homeland的HTML/API可用于PoC；Immich不进入本方案。Cloudflare代理和Workers请求体/连接限制会影响大照片、视频、Range、WebSocket和原生客户端，且Workers增加一层执行与计费。
8. NAS优选程序只改实验hostname的灰云A记录，Token限制到单记录；候选失败时回退到已验证的SaaS CNAME target，而不是继续写另一个未知IP。

#### PoC通过标准

- [ ] SaaS custom hostname和证书状态均为active，续期/DCV路径明确。
- [ ] 灰云CNAME官方基线连续稳定，Worker只允许预期Host和路径。
- [ ] 灰云A优选候选能连续进入同一Worker，并完成TLS、GET/POST、API、WebSocket（如使用）和回源验证。
- [ ] WAF/Access究竟在custom hostname/Worker哪一层执行已通过实际阻断测试，不能根据产品名推断。
- [ ] 源站不能被绕过；Worker回源认证、端口和日志不泄漏secret。
- [ ] 不同运营商、晚高峰连续测试至少3天，P95相对官方SaaS CNAME基线有明确收益。
- [ ] 候选失效后程序能原子恢复SaaS CNAME target；证书和custom hostname状态不受频繁A/CNAME切换破坏。

**修订后的推荐**：认可“Workers + SaaS custom hostname + 灰云优选IP”为实验方案，优先级高于第三方优选域名，但仍低于本地DNS覆盖和DNS-only直连的简单性。只有隔离PoC证明任意A记录候选在本账户中长期稳定、续证和安全链均成立，才讨论扩大使用；不能从Workers支持灰云custom hostname直接推导出Cloudflare官方支持任意共享IP A记录。

#### 现网更正：`orchomev.rokano.org` 已采用“灰云优选IP + Worker”并能命中Worker

业主提供的实际Worker代码：

```javascript
addEventListener("fetch", event => {
  let url = new URL(event.request.url);
  url.hostname = "orc.rokano.org";
  let request = new Request(url, event.request);
  event.respondWith(fetch(request));
});
```

2026-08-23只读核查观察到：

| hostname | DNS状态/地址 | 当前作用 |
|---|---|---|
| `orchomev.rokano.org` | 公开A记录为自选Cloudflare地址 `104.19.215.184`，无AAAA | 客户端入口；请求能进入Cloudflare，`CF-Ray`显示本轮落到LAX |
| `orc.rokano.org` | A记录为 `132.145.115.29`，无AAAA | Worker代码的真实上游目标 |

对 `https://orchomev.rokano.org/` 和 `/home` 的实测均返回Cloudflare HTTP 520，约1.7–2.7秒；直接请求 `https://orc.rokano.org/` 和 `/home` 能完成TLS握手，但随后收到empty reply，无有效HTTP响应。`orc.rokano.org` 当前证书CN/SAN匹配，签发者为ZeroSSL，有效期记录为2026-07-31至2026-10-29。以上只说明本轮观察状态，不代表证书续期或源站服务长期正常。

这组证据修正了前述判断：在**这个已实际部署Worker绑定的hostname**上，灰云A指向自选Cloudflare IP确实能把请求交给Worker。业主随后澄清 `orc.rokano.org` 不是网站，而是需要正确VLESS密钥及传输参数的v2ray代理入口；普通curl没有携带VLESS认证/握手，空响应和Worker侧520是无效协议探测的表现，**不能据此判断上游故障**。此前以普通灰云 `immich` 模拟缺少同等Worker绑定，也不能代表 `orchomev` 的机制。

#### 验证方法修正：不能要求源站HTTP根路径返回200

现有代码只替换hostname，保留原协议、路径、查询参数、方法、请求头与body；当VLESS使用Cloudflare可代理的HTTP/WebSocket传输时，客户端的正确Upgrade请求可由Worker `fetch()` 转发到 `orc.rokano.org`。Cloudflare官方说明代理WebSocket在所有计划可用，Workers的`fetch()`也支持WebSocket握手；实际仍以本项目VLESS客户端完整连接为准。

正确验收应分为：

1. **入口层**：由Worker单独处理一个不回源的轻量HTTP路径，只证明候选IP能完成TLS、命中正确Worker及其colo；不携带VLESS密钥。
2. **协议层**：使用真实v2ray/sing-box客户端、正确VLESS UUID/密钥、WebSocket路径、TLS/SNI和其他现有参数，完成代理连接；普通curl 200/520不作为结论。
3. **业务层**：通过已建立的VLESS代理访问一个业主控制或稳定的测试目标，记录连接成功率、握手/首包时间和小流量吞吐；不能只测ping，也不能用大流量测速频繁消耗Worker/宽带。
4. **源站隐蔽**：`orc`无网站响应是可接受的暴露面收缩，不应为了监控额外增加公开网页；如需源站健康，应在VLESS协议内验证，或使用仅内网可见的运维监控。
5. **防递归**：Worker不回源 `orchomev`；继续使用独立 `orc` 是正确结构。

VLESS UUID、路径、订阅内容和任何密钥不得写入Git、Worker响应、DNS记录、日志或候选评分数据库。

#### 针对现网的自动更新器方案

NAS守护程序可以像ddns-go一样只维护 `orchomev.rokano.org` 的灰云A记录：

1. Worker增加一个随机命名或仅允许探针访问的边缘健康分支：直接返回固定小响应、Worker版本和`request.cf.colo`，不访问 `orc`，用于测客户端/NAS到候选Cloudflare入口。不要用公开、长期固定的明显路径泄漏不必要的部署特征。
2. 完整链路不设置普通HTTP `/__origin-health`；改由本地真实VLESS客户端执行认证连接和受控代理请求，避免为了监控改变 `orc` 的隐蔽行为。
3. 入口探测器以 `--resolve orchomev.rokano.org:443:<候选>` 等效方式带正确SNI/Host请求每个候选；协议探测器通过临时DNS覆盖让v2ray/sing-box连接同一候选。入口评分与VLESS完整链路评分分开。
4. 候选胜出后，通过最小权限Cloudflare API Token只修改该DNS record ID，强制`proxied=false`；不允许修改根域、`orc`、证书、Worker或其他记录。
5. 更新后从正常DNS重新解析 `orchomev`，依次验证边缘标记、Worker版本、真实VLESS握手和受控代理请求。任一失败原子回滚到上一个已知可用IP。
6. 保留本地状态：当前/前一IP、连续失败数、切换原因、样本P50/P95、`CF-Ray` colo和更新时间；不记录API Token、Cookie或请求正文。
7. NAS探针只代表NAS所在运营商出口。若该入口给手机蜂窝或异地用户使用，须部署对应网络探针并采用保守的多网络评分，否则自动更新可能优化家庭宽带却恶化移动网络。

#### Worker建议改造方向

现有十行代码足以完成透明转发验证。若加固，不能破坏VLESS WebSocket Upgrade、路径和二进制流；至少应限制入口Host，单独处理边缘健康分支，其他请求继续流式转发到明确的`orc`上游，并记录不含UUID、路径、订阅和请求内容的最小错误指标。Service Workers语法已被Cloudflare标记为deprecated但仍支持，后续可迁移Module Worker；迁移必须以真实VLESS端到端连接为回归标准。

该hostname当前服务对象是VLESS代理，不应与Homeland或Immich的HTTP优化测试混为一套指标。Cloudflare Workers Free当前为100,000请求/日、单次CPU 10ms；WebSocket初始Upgrade计为一次请求、后续消息不按请求计费，HTTP触发Worker在客户端保持连接时没有固定墙钟时长上限，但运行时更新可能给长连接30秒结束宽限，且单条Worker收到的WebSocket消息上限为32MiB。实际代理稳定性仍须用真实客户端长连接和重连测试验证。

### 10.7 `rokano.org/home` 灰云优选IP + Worker首轮测试方案

#### 目标与不变量

目标是让访客继续使用标准地址 `https://rokano.org/home`（客户端443），根域灰云A记录指向程序选出的Cloudflare入口IP，Worker再显式回源家庭公网8443。以下现网边界保持不变：

- 路由器仍只做现有 `公网TCP 8443 → NAS/NPM TCP 443`；不新增公网443转发，不修改8443目标。
- NPM仍在容器内443终止TLS；Homeland仍由NPM转发到 `homeland_frontend:3000`。
- 其他子域各自选择橙云完全代理或DNS-only直接访问，不因根域切换自动改变；DNS记录的代理状态按hostname独立。
- `rokano.org`的优选入口记录与家庭公网DDNS记录必须分离，禁止两个程序写同一记录。

目标链路：

```text
访客 https://rokano.org/home（标准443）
  ↓ 灰云A：优选器维护Cloudflare入口IP
根域对应Worker（保留Host、路径、方法和body）
  ↓ fetch https://origin-home.rokano.org:8443/home
家庭公网8443
  ↓ 路由器现有转发
NPM:443（校验origin hostname证书与Worker回源secret）
  ↓
homeland_frontend:3000
```

Cloudflare官方列明8443为支持代理的HTTPS端口；本方案在Worker URL中显式写 `:8443`，因此客户端443与源站8443互不冲突。端口转发只依据目标公网端口，不会读取根域优选A记录；只要 `origin-home` 始终解析到家庭公网IP，优选器更换根域Cloudflare IP不会改变回源路径。

#### `origin-home` 是否可以开橙云

**技术上可以，但本方案推荐保持灰云。** 橙云是DNS记录的代理状态，不是“给8443开橙云”；Cloudflare官方端口表确认HTTPS 8443可被代理。因此把 `origin-home.rokano.org` 设为橙云后，Worker对 `https://origin-home.rokano.org:8443/...` 的子请求理论链路会变为 `Worker → Cloudflare代理层 → 家庭公网8443`，而非当前设计的 `Worker → 家庭公网8443`。

本项目不推荐这样做，原因是：它增加一次Cloudflare代理/缓存/规则匹配，无法解决当前重点测试的大陆到首个Cloudflare入口质量，还会让回源故障定位、Host/TLS、WAF和端口规则更复杂。若 `origin-home` 上又绑定同区Worker Route，官方明确说明同区Worker不能把Route作为普通 `fetch()` 目标，可能出现1042等错误；所以绝不能给它套用覆盖该hostname的Worker Route。仅在明确需要隐藏源站DNS答案、并愿意单独验证二次代理时，才把橙云作为隔离实验分支。

首轮固定配置仍为：根域/测试入口灰云A指向优选Cloudflare IP；`origin-home`灰云A指向家庭公网IP并由ddns-go更新；公网8443→NPM 443映射不变。源站防绕过依靠NPM校验Worker Secret header和防火墙，而不是依赖 `origin-home` 橙云。官方依据（检索日期2026-08-23）：[Cloudflare支持的代理端口](https://developers.cloudflare.com/fundamentals/reference/network-ports/)、[Workers Routes与同区fetch限制](https://developers.cloudflare.com/workers/configuration/routing/routes/)、[Worker错误码1042](https://developers.cloudflare.com/workers/observability/errors/)。

#### 为什么先用测试hostname

首轮不直接修改 `rokano.org`，先建立 `edge-home-test.rokano.org`。它使用与现有 `orchomev`相同、已验证能在灰云自选IP下命中Worker的绑定方式；Worker回源新的 `origin-home.rokano.org:8443`。测试通过后再把同一Worker逻辑绑定到根域并切换根域DNS。这样失败只影响测试入口，不影响当前 `/home`。

#### 第一步：拆分DDNS所有权

1. 在Cloudflare创建 `origin-home.rokano.org` 灰云A记录，初值为当前家庭公网IPv4；不创建AAAA，除非已完成IPv6入站防火墙和独立回源验证。
2. 把 `origin-home.rokano.org` 加入ddns-go维护列表。
3. 首轮测试期间根域仍由当前ddns-go维护；正式切换前必须将 `rokano.org` 从ddns-go列表移除，并观察至少一个ddns-go检查周期，确认它只更新`origin-home`而不会把根域优选IP覆盖回家庭IP。
4. 未来优选器Token只允许编辑根域/测试入口记录；ddns-go Token只承担origin-home及明确需要的直接子域。若Cloudflare Token无法细化到单记录，程序自身仍须硬编码hostname白名单并使用不同Token降低误改风险。

#### 第二步：给NPM增加专用回源hostname

在NPM创建 `origin-home.rokano.org` Proxy Host，配置应与当前根域Homeland转发保持一致：

- Scheme：`http`
- Forward Hostname/IP：`homeland_frontend`
- Forward Port：`3000`
- WebSocket：保持与当前根域一致
- SSL：为 `origin-home.rokano.org` 使用有效证书；由于公网80/443受限，继续用DNS-01签发/续期

##### 2026-08-23 首次实测：525的直接原因

配置测试入口后，浏览器曾显示HTTP 503；从项目环境复测时，HTTP 80已能由Cloudflare 301到HTTPS，HTTPS入口证书也覆盖 `*.rokano.org`，但443请求稳定返回Cloudflare `525 SSL handshake failed`。进一步直连家庭公网8443并携带SNI `origin-home.rokano.org`，NPM立即返回TLS alert `unrecognized name`且不下发证书；同一公网IP和8443端口携带SNI `rokano.org` 则返回HTTP 200。由此确认：端口转发、根域NPM虚拟主机和Homeland应用正常，当前阻塞点是NPM尚未为 `origin-home.rokano.org` 提供可握手的TLS虚拟主机/证书。

修复顺序：在NPM新增或扩展Proxy Host使Domain Names包含 `origin-home.rokano.org`，转发目标与现有 `rokano.org` Homeland配置一致；通过DNS-01申请/选择覆盖该hostname的证书并启用SSL；保存后先用 `openssl s_client -connect origin-home.rokano.org:8443 -servername origin-home.rokano.org` 确认下发证书，再用 `curl -I https://origin-home.rokano.org:8443/home` 验证不再出现SNI错误，最后复测Worker入口。若已启用源站secret校验，直接curl预期应为403而非200，Worker携带正确secret才应为200。不得通过关闭Cloudflare Full (strict)或跳过TLS校验掩盖此问题。

2026-08-23随后已通过NPM官方API完成修复：创建独立Proxy Host ID 7，复用现有 `*.rokano.org` 证书并转发至 `homeland_frontend:3000`。NPM语法检查、重启后语法检查均通过；源站正确SNI、Worker `/home`、`/home/docs`、`/home/api/upcoming`及原根域回归均为HTTP 200。当前结论为“功能链路已跑通，源站防绕过未闭环”：未配置Worker Secret header时，公网直连 `origin-home:8443/home`仍为200。

2026-08-24针对浏览器显示 `canceled` 再次复测：Worker `/home` 通过HTTP/1.1、HTTP/2及Chrome风格Accept/压缩请求均返回200并完整下载362,197字节；页面引用的9个JS和1个CSS资源全部返回200，NPM访问日志对应请求也均为200且无新错误。服务端响应公布 `alt-svc: h3=":443"`，但本轮curl环境未验证HTTP/3。因此当前不能把 `canceled` 归因为Worker/NPM HTTP错误；若页面本身打不开，优先以Chrome无痕窗口/清站点数据和临时禁用QUIC做A/B，判断自选IP的UDP/443路径；若页面正常而仅DevTools个别预取显示canceled，则属于Next.js导航/预取主动取消，不应计为可用性失败。仍需用户提供被取消请求的具体URL、Protocol、Initiator和浏览器界面现象才能进一步定因。

业主随后在Windows复现出明确错误：curl使用Schannel默认吊销检查时以 `CRYPT_E_REVOCATION_OFFLINE` 中止；加入仅用于诊断的 `--ssl-no-revoke` 后，同一客户端、同一 `104.19.215.184`、同一SNI完整返回HTTP/1.1 200，下载353.7 KiB，响应含 `CF-Ray ...-LAX` 与 `x-served-by: origin-home.rokano.org`。这确认两个Worker hostname共用同一Cloudflare IP并非本次直接原因，故障点是Windows/TUN链路无法完成证书CRL/OCSP查询。正式修复应让证书状态查询域名经可用出站访问，而不是长期关闭系统吊销检查；不同Worker采用不同优选IP仅是降低IP级路由耦合的运维优化。

最终经业主本机路由调整确认：真正根因是访问 `edge-home-test.rokano.org` 时流量又经 `orchomev.rokano.org` 的VLESS代理出站，形成代理路径耦合；改为让测试hostname直连后浏览器恢复正常。上述Schannel吊销检查离线是异常代理路径中的表象，不能单独作为最终根因。两个hostname使用同一Cloudflare IP在SNI层可正常区分，并非协议上禁止，但若TUN按IP分流会增加误路由风险；可用不同候选IP降低耦合，更可靠的控制仍是给 `edge-home-test.rokano.org` 及未来根域优选入口设置明确的 `direct` 域名规则，且规则优先级高于通用代理规则。所有优选测速也必须直连，否则结果反映的是VLESS出口到Cloudflare的链路而非本地运营商到Cloudflare的链路。
- Force SSL、HTTP/2、HSTS策略按回源用途审慎设置；Worker请求本身使用HTTPS

为防止任何人知道origin hostname后绕过Worker直连8443，NPM专用host必须校验一个高强度随机header，例如 `X-Worker-Origin-Auth`。值只保存在Worker Secret与NPM持久化配置，不写Git、Markdown或日志。Nginx仅做`return 403`式校验；部署前先备份NPM配置，测试错误secret为403、正确secret为200。若NPM UI/模板无法安全表达header校验，首轮可先通过随机origin hostname完成功能验证，但不得把“难猜”当成最终安全边界。

#### 第三步：部署独立测试Worker

使用Module Worker，不修改现有VLESS Worker。逻辑骨架如下，secret与随机健康路径均以绑定注入，示例占位值不可直接使用：

```javascript
export default {
  async fetch(request, env) {
    const incoming = new URL(request.url);

    if (incoming.hostname !== "edge-home-test.rokano.org") {
      return new Response("Not Found", { status: 404 });
    }

    if (incoming.pathname === env.EDGE_HEALTH_PATH) {
      return Response.json(
        { ok: true, colo: request.cf?.colo ?? "unknown", version: "home-edge-test-1" },
        { headers: { "cache-control": "no-store" } },
      );
    }

    const upstream = new URL(request.url);
    upstream.protocol = "https:";
    upstream.hostname = "origin-home.rokano.org";
    upstream.port = "8443";

    const headers = new Headers(request.headers);
    headers.delete("x-worker-origin-auth");
    headers.set("x-worker-origin-auth", env.ORIGIN_SECRET);
    headers.set("x-forwarded-host", incoming.hostname);

    return fetch(new Request(upstream, {
      method: request.method,
      headers,
      body: request.body,
      redirect: "manual",
    }));
  },
};
```

实际部署须处理无body的GET/HEAD，避免某些运行时因显式`body:null`行为差异；可直接以`new Request(upstream, request)`为基线后替换headers。不要在响应、日志或源码中输出`ORIGIN_SECRET`。如果源站返回绝对`Location`或设置带Domain的Cookie，要检查是否泄漏/跳转到`origin-home`并在Worker中定向改写。

将 `edge-home-test.rokano.org` 按现有 `orchomev` 已验证方式绑定到该Worker，然后设为灰云A，首个功能候选可暂用当前已知能命中Worker的Cloudflare地址；该地址只用于验证链路，不代表性能最优。

#### 第四步：首轮功能测试

按以下顺序，任何一步失败都不切根域：

1. `dig edge-home-test.rokano.org A`只返回指定候选；无意外AAAA。
2. 请求随机edge health路径返回预期Worker版本和colo，证明灰云IP命中正确Worker。
3. `https://edge-home-test.rokano.org/home`、`/home/docs`、`/home/api/upcoming`均返回200；页面静态资源、深链接刷新正常。
4. 从公网直接请求 `https://origin-home.rokano.org:8443/home`：无secret应403；通过Worker应200。
5. 对一个安全的测试账单执行新增、修改、删除回归；完成后恢复测试数据。当前Homeland写接口尚缺成熟应用认证，不能因origin secret就视为用户鉴权完成。
6. 检查Worker日志、NPM日志和应用日志不出现secret、Cookie或敏感正文。
7. 分别验证家庭Wi-Fi和手机蜂窝；记录DNS、TLS、TTFB、总耗时、CF colo和失败率。

#### 第五步：候选IP首轮对照

先不自动改DNS。用客户端临时解析依次测试5–10个有限候选：

```bash
curl --resolve edge-home-test.rokano.org:443:CANDIDATE_IP \
  -o /dev/null -sS \
  -w 'code=%{http_code} tls=%{time_appconnect} ttfb=%{time_starttransfer} total=%{time_total}\n' \
  https://edge-home-test.rokano.org/home
```

每候选至少5次，并单独请求edge health确认Worker版本/colo。按失败率、P95、P50排序，不按单次最低值。首轮同时保留三组基线：当前根域橙云443、测试Worker默认/已知CF地址、DNS-only origin直连8443（仅作链路性能对照，携带secret的测试只在受控环境执行）。

#### 2026-08-24 首轮对照实测

在NAS当前南京网络出口、确认测试hostname不经VLESS代理后，对现有橙云根域与灰云自选入口交错请求相同路径。根域DNS返回 `104.21.93.145`/`172.67.211.27`，本轮 `CF-Ray` 落NRT；测试入口固定返回 `104.19.215.184`，落LAX，且响应含 `x-served-by: origin-home.rokano.org`。因此“公共DNS返回指定IP、Cloudflare按SNI命中测试Worker、Worker回源专用Host”三项均已实证成功。

| 负载 | 入口 | 成功率 | TTFB P50 / P95 | 总耗时 P50 / P95 | 本轮判断 |
|---|---|---:|---:|---:|---|
| `/home/api/upcoming` | 根域橙云 | 7/7 | 2.542s / 3.153s | 2.546s / 3.640s | 抖动较大 |
| `/home/api/upcoming` | 自选Worker | 8/8 | 1.421s / 1.667s | 1.421s / 1.667s | P50与P95均明显改善 |
| `/home`（362,197字节） | 根域橙云 | 5/5 | 0.830s / 3.384s | 1.910s / 4.379s | 中位更快，但长尾明显 |
| `/home`（362,197字节） | 自选Worker | 5/5 | 1.044s / 1.695s | 2.091s / 2.301s | 中位总耗时慢0.181s，但P95快2.078s、稳定性更好 |

结论限定为本轮样本：自选机制**确实生效**，当前候选对轻量动态API有明显收益，并大幅收敛完整页面长尾；但它不是所有指标上的绝对最快IP，完整页面P50反而略慢。LAX落点也说明不能按地理直觉挑选。正式切根域前仍须从Windows真实客户端、手机蜂窝和至少另一运营商重复测试，并继续筛选候选；排名以失败率和P95优先，不能只看单次速度或P50。

#### 第六步：根域切换条件与动作

只有测试hostname完成功能、安全和多网络性能闭环后，才切根域：

1. 备份Cloudflare DNS、Worker路由、Origin Rule、AOP及NPM配置截图/导出。
2. 从ddns-go移除 `rokano.org`；确认 `origin-home`仍正常跟随公网IP。
3. 将测试Worker的Host白名单增加/替换为 `rokano.org`，并按 `orchomev` 的同类绑定方式把根域交给Worker。
4. 把根域设为灰云A并写入已验证候选；不创建AAAA。
5. 当前“Cloudflare 443→Origin Rule 8443”将不再承担主回源，因为Worker已显式fetch `origin-home:8443`；首轮可保留规则作为配置快照，但必须确认它不会匹配/改写Worker的origin请求。稳定后再清理无效规则，避免双重端口改写认知混乱。
6. 端到端验证 `/home`、关键API和源站绕过阻断；失败立即把根域恢复原橙云家庭公网记录和原Worker绑定。

#### 端口转发验收

切换前后路由器端口表应完全一致：

```text
保留：公网TCP 8443 → NPM TCP 443
不存在：公网TCP 443 → 任意内网主机
不存在：UPnP/NAT-PMP自动新增映射
```

分别验证：Worker访问 `origin-home:8443`成功；公网IP的443仍不可达；NPM管理端口81仍仅LAN/VPN可达；其他直连/橙云子域按其原方案工作。优选器只改DNS，不调用路由器API，也不应拥有UPnP能力，因此不会直接改变端口转发。

#### 可行实现：局域网DNS覆盖控制器

```text
Cloudflare公共权威DNS：rokano.org保持橙云
                         │
家庭客户端DNS查询 ──→ NAS本地DNS（AdGuard Home/dnsmasq/Unbound）
                         │ 返回本轮选中的Cloudflare入口IPv4/IPv6
家庭客户端 ── TLS SNI/HTTP Host=rokano.org ──→ Cloudflare
                                                │ WAF/Access/缓存/Origin Rule
                                                └─ AOP → 家庭公网:8443 → NPM
```

本地控制器只修改家庭DNS的rewrite/hosts数据。公共DNS、Cloudflare站点状态和源站配置保持不变；选择失败时删除本地覆盖，客户端立即回到Cloudflare正常DNS答案。这是本项目唯一建议继续验证的自动优选形态。

#### 程序组件

1. **候选集管理器**：定期读取Cloudflare官方IP段的版本，但不扫描整个CIDR。使用“Cloudflare当前正常DNS答案 + 少量经过人工/历史验证的候选”组成有界池；失效地址隔离一段时间后才复测。
2. **探测器**：必须连接候选 `IP:443`，同时发送 `SNI=rokano.org` 和 `Host: rokano.org`；普通ping或TCP 443通不代表站点TLS、WAF、Origin Rule和AOP可用。
3. **分层探测**：边缘握手/诊断请求用于观察用户到Cloudflare；已缓存的 `/_next/static/*` 用于观察静态体验；只读动态端点用于观察完整“Cloudflare→南京源站”链路。不得用写API做周期测速。
4. **评分器**：失败率优先，其次P95/P50 TLS、TTFB和总耗时，吞吐单独评分；不按单次最低值选IP。`CF-Ray` colo只作解释信息，不能简单认定香港一定比东京/洛杉矶快。
5. **决策器**：候选须连续多轮优于当前地址，改善超过门槛才切换；设置最短保持时间和冷却时间，避免每轮抖动都改DNS。
6. **发布器**：通过本地DNS受限API或原子配置切换单个hostname，TTL建议60–300秒；更新后从真实客户端重新解析并完成HTTPS smoke test。
7. **看门狗与回退**：连续TLS失败、非预期状态码、证书/hostname错误或超时达到阈值时，立即删除rewrite回归系统DNS；不能只切到排名第二但同样失效的地址。

#### 推荐初始参数（仅为试验配置，不是已验证最优值）

| 参数 | 起始建议 | 原因 |
|---|---:|---|
| 探测间隔 | 30–60分钟 | Anycast路径会变，但分钟级频繁扫描没有价值 |
| 每候选样本 | 每轮3–5次 | 排除单次握手和回源抖动 |
| 切换条件 | 连续3轮，P95改善≥25%，失败率不高于当前 | 防止追逐偶然最低值 |
| 最短保持 | 12–24小时 | 限制频繁切换和DNS缓存干扰 |
| 硬失败回退 | 连续2–3次TLS/HTTP失败 | 家庭自用优先恢复可用性 |
| 候选数量 | 5–20个已验证地址 | 避免扫描Cloudflare整个公网段及制造无意义流量 |

这些数值必须通过项目实测再调整；当前没有连续数日数据，不能报告它们已经有效。

#### 影响正确性的关键问题

- **测量位置**：NAS探测只代表南京家庭宽带出口，适合优化家中Wi-Fi客户端；手机蜂窝和异地用户路径不同，需要各自探针/本地DNS，不能共用NAS结论。
- **IPv6旁路**：客户端可能优先AAAA。如果只优化A记录，浏览器仍可能走默认IPv6。要么分别评测IPv6候选，要么在家庭DNS中仅对该hostname暂时不返回AAAA，并记录放弃IPv6的代价；不能全网粗暴关闭IPv6。
- **加密DNS旁路**：浏览器或手机启用外部DoH/Private DNS后可能绕过NAS本地DNS。测试设备须明确使用家庭DNS，否则控制器更新不会生效。
- **HTTP/3**：浏览器可能使用UDP/443；探测器只测TCP/TLS不能代表QUIC表现。进入正式试验前要分别验证HTTP/2与HTTP/3。
- **缓存污染**：动态页面、冷缓存和热缓存混测会误判线路。每类端点单独评分，且禁止为测速把私人页面改成公共缓存。
- **共享地址不可控**：Free/Pro/Business没有专属IP，也不能要求Cloudflare轮换被ISP阻断的地址。即使候选今天可用，也没有专属SLA，必须长期保留自动回退。

#### Docker交付边界

若后续实现，按项目规则作为独立Docker模块交付：非root、只读根文件系统、仅允许出站443/DNS及访问本地DNS API；候选状态放独立volume；本地DNS API凭据通过secret/环境变量注入且不入库。容器不得使用host网络、不得持有Cloudflare DNS编辑权限、不得修改路由器端口映射。提供健康检查、结构化但不含Token/Cookie的日志，以及“删除本地rewrite恢复正常DNS”的一键回滚动作。

#### 开发前的最小验证门

在写程序前先手工完成以下闭环，避免自动化一个没有稳定收益的机制：

- [ ] 在家庭宽带连续3天、覆盖晚高峰，对默认DNS和5–10个候选各取得足够样本。
- [ ] 至少一个候选达到成功率要求，并持续将P95总耗时改善25%以上。
- [ ] 本地DNS手工rewrite后，Chrome/手机实际解析到候选，TLS、WAF/Access、AOP、API和HTTP/3均通过。
- [ ] 删除rewrite后能在TTL窗口内自动恢复默认Cloudflare地址。
- [ ] 与DNS-only直连 `:8443` 比较，确认优选IP的安全收益值得承担维护成本。

只有上述门槛通过，才值得实现守护程序；否则推荐保留Cloudflare默认入口并为性能敏感访问使用受控直连。

### 10.8 公开 IP 段、地理位置与“是否避开热门区域”（2026-08-26）

#### 结论

1. Cloudflare公开的`ips-v4`/`ips-v6`是全球共享Anycast前缀，不是按机房或国家划分的“可自选节点表”。同一IP会从全球多个数据中心宣告，客户端根据BGP路径进入某个可用数据中心；IP注册地、whois国家或GeoIP标签不能证明实际落点。
2. Cloudflare确实在中国大陆有节点，但属于JD Cloud运营的**China Network**。该服务是Enterprise计划的单独订阅，并要求每个接入根域具备有效ICP备案/许可和内容审核；普通Free/Pro/Business用户不能从全球公开IP列表中挑出一个“大陆IP”来获得这些节点。
3. Cloudflare提供的`jdcloud_cidrs`也不是可给访客使用的“大陆优选IP”。官方明确说明它们是JD Cloud数据中心连接客户源站时使用的地址，**不同于访客DNS解析得到的地址**。
4. 截至2026-08-26，没有查到Cloudflare官方证据支持“香港、台湾、日本等邻近地区因大陆用户滥用，所以应预先避开这些IP”。官方只确认共享地址可能被某个国家或ISP阻断，且Free/Pro/Business没有专属IP、也不能要求轮换；是否避开应由目标运营商的实测失败率和P95决定，而不是按地域传言建立黑名单。

#### 单纯按南京地理位置看的候选区域

Cloudflare网络地图列有下列邻近城市。以下只做**地理分层**，不代表南京联通/移动/电信的网络距离或实际BGP落点：

| 层级 | 地区 | 本项目含义 |
|---|---|---|
| 中国大陆节点 | 常州、杭州、上海、嘉兴、绍兴等 | 地理最近，但只有接入China Network的合规域名才能由JD Cloud节点正式承接；不属于普通公开IP优选范围 |
| 中国大陆外第一圈 | 台北、福冈、首尔 | 从南京直线位置较近，适合进入实测候选解释范围；不能据此预判线路最好 |
| 中国大陆外第二圈 | 高雄、香港、澳门、那霸、大阪 | 地理略远但国际出口、运营商互联可能更合适；实际表现可能反超第一圈 |
| 更远对照 | 东京、新加坡 | 地理更远，但Cloudflare Anycast/BGP和运营商出口可能把流量送到这里；本项目已实际观察到NRT和LAX，证明地理直觉不能替代测量 |

这里的“第一圈/第二圈”不是Cloudflare官方分区，也不表示能给某个公开IP贴上固定地区标签。实际入口应由Worker的`request.cf.colo`、明确语义下的`CF-Ray`、traceroute/MTR和端到端计时共同确认。

#### 是否要故意避开被大量使用的邻近地区

**不建议按地区预先避开；建议按候选的真实表现淘汰。**

可能存在但尚无本项目证据闭环的风险包括：某个共享目的IP被ISP阻断、晚高峰跨境路径拥塞、特定TCP/QUIC路径丢包，以及热门第三方脚本把大量用户集中到少数地址后造成局部抖动。这些是合理风险假设，不是已证实的Cloudflare区域策略。

候选筛选顺序应为：

1. **硬淘汰功能失败**：TLS/SNI不正确、HTTP/API/WebSocket不完整、HTTP/3异常、连续超时或证书异常。
2. **失败率优先于速度**：先比较不同运营商、家庭宽带/蜂窝、白天/晚高峰的成功率，再比较P95，最后才看P50或单次最低值。
3. **保留路径多样性**：候选池不要全部来自同一公开前缀或只落同一colo；但多样性用于容灾，不等于刻意排斥香港、台北、首尔等近端地区。
4. **用默认Cloudflare答案作基线和回退**：自选候选只在持续优于默认答案且功能等价时保留；失效时恢复正常DNS，不在未知候选间无限切换。
5. **按使用者网络分别决策**：NAS探针只能代表南京家庭出口；手机蜂窝和异地用户必须独立测量，不能共享一个“全国最优IP”。

对本项目的直接建议：候选发现阶段不要先按城市删IP；让台北、福冈、首尔、香港、大阪、东京等实际落点自然进入样本，再由南京真实三网的失败率和P95淘汰。若某个所谓“香港优选IP”实际`colo`落到LAX或NRT，就按实际链路计分，不按宣传标签计分。

### 10.9 Immich / Jellyfin 全量经 Cloudflare 与 Worker 的边界（2026-08-27）

> **后续决策说明**：本节保留 v1.2 当时对 Tailscale/VPS 的路线建议，协议、请求体和套餐边界仍有效；其入口决策已被[家庭 NAS 网络安全架构 v3.5](../../architecture/家庭NAS网络安全架构.md#修订日志)取代。当前方案为 Immich/Jellyfin 经家庭公网 `:8443` 与 NPM 直连，轻量 Web 继续经 Cloudflare。

#### 结论

**协议上可以透明转发，当前家庭方案却不应把 Immich 与 Jellyfin 的完整媒体数据面交给免费 Cloudflare Worker。**

| 服务 | 协议层可转发 | 当前免费方案的硬边界 | 本项目决策 |
|---|---|---|---|
| Immich | 是：HTTPS API、大文件 HTTP 上传/下载、WebSocket | Free/Pro 请求体上限 100 MB；请求会在 Worker 能拆分或处理之前被拒绝。Immich 官方也明确提示 Cloudflare Tunnel 的官方上限为 100 MB | 不作为完整上传主链路；优先 Tailscale，或使用公网 VPS 反代 + WireGuard 回家 |
| Jellyfin | 是：HTTPS、Range/If-Range、WebSocket；视频直放/转码仍由 HTTP 响应流承载 | Worker 响应体本身无强制大小上限，但 HLS 分片会放大请求数；Free Worker 每日 100,000 次。Free/Pro/Business CDN 条款要求视频和大文件使用适用的付费服务 | 继续 Tailscale；若要无 VPN 分享，优先 VPS 反代 + WireGuard，不用免费 Worker 承担视频数据面 |

“视频”不是脱离 Web 的特殊协议：Jellyfin 直放通常依靠带 `Range`/`If-Range` 的 HTTP 请求，转码播放通常由客户端连续请求清单与媒体分片；WebSocket用于会话状态等控制消息。Immich 的照片/视频上传是 HTTP 请求体，事件通知还需要 WebSocket。Cloudflare、Worker、Tunnel 都是在这条 HTTP/WebSocket 链路中做代理，而不是建立一个绕开 HTTP 限制的独立媒体通道。

#### “自选 IP”与真正隐藏 Origin 是两件事

自选 Cloudflare IP 只改变客户端连接的边缘地址，不改变应用协议。Immich/Jellyfin 客户端仍应配置 `https://immich.example.com` / `https://jellyfin.example.com`，由本地DNS把 hostname临时解析到候选Cloudflare IP；TLS SNI与HTTP Host继续使用域名。不能让客户端直接填写裸IP，否则证书、SNI与虚拟主机路由会失败。原生客户端是否遵循系统DNS、是否优先AAAA或内置DoH仍须实测。

如果 hostname 本身是普通橙云记录，并已有 Origin Rule 把Cloudflare 443回源到家庭8443，则自选IP路径通常不需要Worker：客户端只是在连接同一Cloudflare站点的另一个Anycast地址。Worker只在需要额外hostname映射、回源鉴权header、健康端点或复杂路由时出现。

当前 `origin-home.rokano.org` 是Worker使用的DNS-only回源hostname，`orc.rokano.org` 也是灰云入口；只要这些记录仍公开解析到家庭公网IP，就不能声称Origin IP已隐藏。真正的两种目标是：

1. **不可直接访问**：所有公开业务hostname橙云，防火墙只允许Cloudflare回源，并使用自有per-hostname AOP/mTLS或独立Secret；仅允许Cloudflare IP不构成强认证。
2. **不再需要公开入站地址**：Cloudflare Tunnel关闭家庭入站端口并删除暴露Origin的A/AAAA记录；但Tunnel仍不绕过Immich上传和媒体数据面的Cloudflare限制。

若媒体改为“公网VPS反代 → WireGuard回家”，家庭Origin同样可以关闭公网入站；此时用户看到的是VPS IP而非Cloudflare IP，但达成了更重要的家庭源站隐藏目标。

#### 为什么 Worker 不能修复 Immich 大文件上传

Cloudflare 的账户请求体上限适用于到达 Worker 的请求：Free/Pro 为 100 MB，Business 为 200 MB，Enterprise 默认 500 MB。超过限制返回 413；Worker 代码尚未取得完整请求，因而不能在内部把一个 500 MB 的 Immich 上传透明切成多个小请求。

只有以下变化能真正改变这个边界：

1. Immich 客户端与服务端共同支持小于限制的分片上传；透明 Worker 不能单方面发明协议；
2. 使用满足文件大小的 Enterprise 限额；
3. 让上传绕开 Cloudflare，改走 Tailscale/WireGuard 或 VPS 回源隧道。

Cloudflare Tunnel 可以关闭家庭公网入站端口，但公共 HTTP 请求仍经过 Cloudflare 边缘。Immich 官方 FAQ 明确记录 Tunnel 的官方文件上限为 100 MB，因此 Tunnel 解决源站暴露，不解决大文件上传上限。

#### Jellyfin 的技术可行与服务条款是两回事

Worker 可以流式返回响应，HTTP 触发的 Worker 在客户端保持连接时没有固定墙钟时长上限；Jellyfin 官方反代示例也要求保留 `Range`、`If-Range` 和 WebSocket。因此“不把整个视频读进 Worker 内存、直接转发 `request.body` / `response.body`”在协议上成立。

但 Cloudflare 2026-06-02 的 Service-Specific Terms 规定：Free、Pro、Business 的普通 CDN 用于网页；通过 CDN 提供视频或大量图片、音频和大文件，需要使用适用的付费服务（条款举例 Developer Platform、Images、Stream），Cloudflare可限制不符合条件的使用。当前免费 Worker PoC 不满足条款明确写出的“Paid Services”条件；是否购买 Workers Paid 后覆盖“透明转发自建 Jellyfin”的具体用法，不从产品名称自行推断，生产化前须取得 Cloudflare 套餐/支持确认。

因此：以下 Worker 仅用于协议兼容测试和小文件路径，不是批准 Jellyfin 免费生产转发，也不能绕过 Immich 100 MB 上限。

#### 透明流式 Worker 骨架

```javascript
export default {
  async fetch(request, env) {
    const incoming = new URL(request.url);

    const route = {
      "immich-edge.example.com": {
        origin: "https://origin-immich.example.com:8443",
        secret: env.IMMICH_ORIGIN_SECRET,
      },
      "jellyfin-edge.example.com": {
        origin: "https://origin-jellyfin.example.com:8443",
        secret: env.JELLYFIN_ORIGIN_SECRET,
      },
    }[incoming.hostname];

    if (!route) {
      return new Response("Not Found", { status: 404 });
    }

    const upstream = new URL(route.origin);
    upstream.pathname = incoming.pathname;
    upstream.search = incoming.search;

    const headers = new Headers(request.headers);
    const clientIp = request.headers.get("cf-connecting-ip") ?? "";

    // 删除客户端可伪造值，再注入仅 Worker 与 Origin 持有的值。
    headers.delete("x-worker-origin-auth");
    headers.delete("x-forwarded-for");
    headers.delete("x-real-ip");
    headers.set("x-worker-origin-auth", route.secret);
    headers.set("x-forwarded-host", incoming.hostname);
    headers.set("x-forwarded-proto", "https");
    headers.set("x-forwarded-for", clientIp);
    headers.set("x-real-ip", clientIp);

    const init = {
      method: request.method,
      headers,
      redirect: "manual",
    };

    // 不调用 arrayBuffer()/text()/json()，保持上传流式传递。
    if (request.method !== "GET" && request.method !== "HEAD") {
      init.body = request.body;
    }

    const upstreamResponse = await fetch(new Request(upstream, init), {
      cf: {
        cacheEverything: false,
        cacheTtl: 0,
      },
    });

    // WebSocket 101 响应必须原样返回，不能重新构造后丢失 webSocket。
    if (upstreamResponse.status === 101) {
      return upstreamResponse;
    }

    const responseHeaders = new Headers(upstreamResponse.headers);
    responseHeaders.set("cloudflare-cdn-cache-control", "no-store");

    // 直接转发 ReadableStream，不缓存、拼接或解析媒体正文。
    return new Response(upstreamResponse.body, {
      status: upstreamResponse.status,
      statusText: upstreamResponse.statusText,
      headers: responseHeaders,
    });
  },
};
```

配套要求：

1. 每个服务使用独立 origin hostname 与独立 Secret；NPM 先校验 Secret，再路由到对应容器。
2. NPM 要把经过校验的 `X-Forwarded-Host` 转为应用需要的公开 `Host`，并正确传递客户端IP；Jellyfin 只把 NPM 配为 Known Proxy。
3. Cloudflare 为 Immich/Jellyfin hostname 配置 Cache Bypass；不得缓存带 Cookie、Authorization、私有照片、视频或 API 响应。
4. 保留 `Range`、`If-Range`、`Upgrade`、`Connection`、Cookie 和 Authorization；不要记录完整 URL，Jellyfin 可能把 API Key 放在查询参数中。
5. Worker安全入口使用 fail closed；达到免费请求额度时不能绕过 Worker直达源站。
6. 仅允许 Cloudflare IP 回源不是充分认证。若保留公开 `8443`，继续使用独立 Secret、per-hostname AOP/mTLS 或等价条件；更彻底的源站隐藏是 Tunnel，但媒体限制仍然存在。

#### 推荐落地拓扑

```text
轻量Web：客户端 → Cloudflare/自选IP → Worker → NPM → Homeland/Stock/Seerr

Immich/Jellyfin个人设备：客户端 → Tailscale → NAS

无需安装VPN的受邀用户：客户端 → VPS HTTPS反代
                                  │
                                  └─ WireGuard/Tailscale → NAS
```

最后一种方式公开的是 VPS 地址，不是家庭公网地址；同时不让 Cloudflare 承担 Immich 大文件与 Jellyfin 视频数据面。现有 ARM VPS 可作为候选，但其带宽、流量额度、回源延迟和日志脱敏尚未验证，不能直接宣布可用。

#### 验收清单

- [ ] 110 MB、500 MB 和真实手机视频上传分别验证；确认 Cloudflare 路径的 413 边界
- [ ] Jellyfin 直放、转码、拖动进度、字幕、WebSocket、电视端与手机端分别验证
- [ ] `Range` 请求得到正确 206，且未把私有媒体写入 Cloudflare Cache
- [ ] 错误 Secret 或直接 origin 请求返回 403/TLS失败
- [ ] Worker达到额度时 fail closed，不回退为绕过安全入口
- [ ] NPM、Worker、Jellyfin 日志不记录完整 API Key、Cookie、Authorization 或私密媒体 URL
- [ ] 若评估 Workers Paid，取得当前套餐对自建视频转发的书面条款/支持确认

## 十一、来源

访问日期：原始资料为2026-08-23；10.8新增资料查阅于2026-08-26；10.9新增资料查阅于2026-08-27。

- [Docker bridge 网络](https://docs.docker.com/engine/network/drivers/bridge/)
- [Docker 端口发布](https://docs.docker.com/engine/network/port-publishing/)
- [Cloudflare DNS Proxy status](https://developers.cloudflare.com/dns/proxy-status/)
- [Cloudflare Origin Rules](https://developers.cloudflare.com/rules/origin-rules/)
- [Cloudflare Workers 限制](https://developers.cloudflare.com/workers/platform/limits/)
- [Cloudflare Tunnel](https://developers.cloudflare.com/tunnel/)
- [Cloudflare Authenticated Origin Pulls](https://developers.cloudflare.com/ssl/origin-configuration/authenticated-origin-pull/)
- [Cloudflare Origin Analytics](https://developers.cloudflare.com/speed/origin-analytics/)
- [Cloudflare Cache 入门](https://developers.cloudflare.com/cache/get-started/)
- [Cloudflare Cache Everything 风险](https://developers.cloudflare.com/cache/how-to/cache-rules/examples/cache-everything/)
- [Cloudflare Argo Smart Routing](https://developers.cloudflare.com/argo-smart-routing/)
- [Cloudflare China Network](https://developers.cloudflare.com/china-network/)
- [Cloudflare代理DNS与Anycast地址](https://developers.cloudflare.com/dns/proxy-status/)
- [Cloudflare工作原理](https://developers.cloudflare.com/fundamentals/concepts/how-cloudflare-works/)
- [Cloudflare IP地址说明](https://developers.cloudflare.com/fundamentals/concepts/cloudflare-ip-addresses/)
- [Cloudflare公开IPv4范围](https://www.cloudflare.com/ips-v4/)
- [Cloudflare公开IPv6范围](https://www.cloudflare.com/ips-v6/)
- [Cloudflare TCP连接与Anycast/BGP路径](https://developers.cloudflare.com/fundamentals/reference/tcp-connections/)
- [Cloudflare全球网络位置](https://www.cloudflare.com/network/)
- [Cloudflare China Network概览](https://developers.cloudflare.com/china-network/)
- [Cloudflare China Network接入要求](https://developers.cloudflare.com/china-network/get-started/)
- [Cloudflare China Network基础设施与JD Cloud IP边界](https://developers.cloudflare.com/china-network/reference/infrastructure/)
- [Cloudflare共享IP可能被ISP阻断](https://developers.cloudflare.com/support/troubleshooting/general-troubleshooting/potential-isp-blocking/)
- [Cloudflare for SaaS启用要求](https://developers.cloudflare.com/cloudflare-for-platforms/cloudflare-for-saas/start/enable/)
- [Cloudflare for SaaS自定义hostname限制](https://developers.cloudflare.com/cloudflare-for-platforms/cloudflare-for-saas/start/getting-started/)
- [Cloudflare Workers Routes](https://developers.cloudflare.com/workers/configuration/routing/routes/)
- [Cloudflare Workers Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)
- [Workers for Platforms hostname routing](https://developers.cloudflare.com/cloudflare-for-platforms/workers-for-platforms/configuration/hostname-routing/)
- [Cloudflare for SaaS hostname validation](https://developers.cloudflare.com/cloudflare-for-platforms/cloudflare-for-saas/domain-support/hostname-validation/)
- [Cloudflare Workers WebSockets](https://developers.cloudflare.com/workers/runtime-apis/websockets/)
- [Cloudflare WebSocket网络支持](https://developers.cloudflare.com/network/websockets/)
- [Cloudflare Workers限制](https://developers.cloudflare.com/workers/platform/limits/)
- [Cloudflare支持的代理端口](https://developers.cloudflare.com/fundamentals/reference/network-ports/)
- [Cloudflare Service-Specific Terms](https://www.cloudflare.com/service-specific-terms-application-services/)
- [Cloudflare保护Origin](https://developers.cloudflare.com/fundamentals/security/protect-your-origin-server/)
- [Cloudflare Workers Cache与Range请求](https://developers.cloudflare.com/workers/cache/configuration/)
- [Immich Reverse Proxy](https://docs.immich.app/administration/reverse-proxy/)
- [Immich FAQ：Cloudflare Tunnel文件大小](https://docs.immich.app/FAQ/)
- [Jellyfin Reverse Proxy](https://jellyfin.org/docs/general/post-install/networking/reverse-proxy/)
- [Jellyfin反代Range与WebSocket示例](https://jellyfin.org/docs/general/post-install/networking/advanced/letsencrypt/)
- [NGINX 官方镜像](https://hub.docker.com/_/nginx/)

## 修订日志

| 日期 | 版本 | 变更摘要 |
|---|---|---|
| 2026-08-27 | archive v1.3 | 当前决策改为Immich/Jellyfin经家庭公网IP与NPM直连，轻量Web继续经Cloudflare；Tailscale/VPS链路退出目标架构。保留v1.2内容作为当时方案记录，现行基线以家庭NAS网络安全架构v3.5为准 |
| 2026-08-27 | archive v1.2 | 核对Immich/Jellyfin经Cloudflare与Worker的协议、请求体、Range/WebSocket和服务条款边界；确认免费Worker不能绕过Immich 100 MB上传限制，Jellyfin完整视频数据面不作为免费生产方案；增加透明流式PoC与Tailscale/VPS回源决策 |
| 2026-08-26 | archive v1.1 | 核实公开IP是全球共享Anycast前缀而非地理节点表；区分普通全球网络与Enterprise China Network；按南京位置整理邻近地区，并明确不因“热门/滥用”传言预先屏蔽地区，改用多网络失败率与P95淘汰 |
| 2026-08-26 | archive v1.0 | 从家庭 NAS 网络安全架构主文档迁出性能样本、故障诊断与 PoC 过程；保留原章节编号和历史版本记录 |
| 2026-08-24 | v2.7 | 对橙云根域与灰云自选Worker交错实测：确认指定IP和Worker命中成功；自选入口显著改善动态API与页面P95，但完整页面P50略慢 |
| 2026-08-24 | v2.6 | 按本机路由调整后的实测修正最终根因：测试域名误经orchomev VLESS出站造成路径耦合；要求优选入口与测速流量按域名直连 |
| 2026-08-24 | v2.5 | Windows以 `--ssl-no-revoke` 完整获得200，确认连接关闭源于Schannel吊销检查离线，而非共用自选IP或Worker/NPM故障 |
| 2026-08-24 | v2.4 | 复测浏览器canceled现象：主文档、静态资源及Chrome风格HTTP/1.1/2请求均为200；将HTTP/3自选IP路径与Next.js主动取消列为待客户端A/B验证分支 |
| 2026-08-23 | v2.3 | 记录通过NPM官方API完成 `origin-home` Proxy Host配置及端到端200验证；明确Worker Secret源站防绕过仍待配置 |
| 2026-08-23 | v2.2 | 记录测试入口首次525诊断：源站8443端口可达，但NPM拒绝 `origin-home` SNI；明确补齐TLS虚拟主机/证书及分层复测顺序 |
| 2026-08-23 | v2.1 | 明确 `origin-home` 橙云在8443上技术可行但会形成二次Cloudflare代理；首轮仍推荐灰云直达，并记录同区Worker Route子请求限制 |
| 2026-08-23 | v2.0 | 为 `rokano.org/home` 制定不影响现有8443映射的灰云优选IP+Worker测试指引：拆分origin DDNS与入口DNS、独立测试hostname、NPM回源鉴权、Worker显式fetch 8443、功能/性能验证及根域回滚顺序 |
| 2026-08-23 | v1.9 | 按业主澄清将 `orc` 识别为需密钥的VLESS/v2ray入口，撤回curl空响应等于源站故障的错误判断；改为Worker边缘探测+真实VLESS客户端端到端验证，补充WebSocket与Workers限制 |
| 2026-08-23 | v1.8 | 按现网 `orchomev` Worker代码修正评估：确认灰云自选IP已能命中Worker，当前520来自 `orc` 上游空响应；给出先修源站、再以edge/origin双健康端点驱动DNS自动优选的具体方案 |
| 2026-08-23 | v1.7 | 修正此前遗漏：纳入Workers for Platforms灰云custom hostname路由；区分官方SaaS CNAME路线与非默认“灰云A优选共享IP”旁路，给出分两阶段隔离PoC及回源安全要求 |
| 2026-08-23 | v1.6 | 针对“灰云A记录直接指定CF IP”补做DNS-only hostname模拟：5个候选均未返回HTTP响应，区分TLS可达与正式代理绑定，否决灰云CF-IP DDNS进入现网 |
| 2026-08-23 | v1.5 | 详细评估类ddns-go自动优选程序：禁止修改橙云源站记录，改为NAS本地DNS覆盖控制器；定义候选、分层探测、评分、防抖、IPv6/DoH/HTTP3处理、自动回退和Docker交付边界 |
| 2026-08-23 | v1.4 | 调查Cloudflare优选IP空间：用官方IP段做客户端临时解析实测，确认存在窄幅实验收益但失败率、落点和长期稳定性不可控；仅保留自有设备A/B测试，不进入公共DNS或生产架构 |
| 2026-08-23 | v1.3 | 实测标准 443 Cloudflare入口与本机源站基线，确认当前慢点主要位于 Cloudflare 路径；增加直连对照、缓存边界、Origin Analytics、Argo试验条件及大陆低延迟替代路线 |
| 2026-08-23 | v1.2 | 增加先行架构与理想架构差距表，以及带验收标准的分级 Master Todo List |
| 2026-08-23 | v1.1 | 明确实验项目使用根域 path 聚合，成熟项目才使用独立子域；少量项目优先用 NPM Custom Locations |
| 2026-08-23 | v1.0 | 汇总家庭 NAS 安全讨论，形成兼顾大陆性能、客户端兼容和维护复杂度的最终架构 |
