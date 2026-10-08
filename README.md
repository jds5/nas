# NAS 维护项目

集中维护 NAS 的网络决策、设备与操作记录、备份恢复和维护工具。

## 文档入口

| 文档 | 内容 |
| --- | --- |
| [山路驿站下载页](docs/operations/2026-10-08-山路驿站下载页.md) | 独立只读下载页、公网整包校验、容器网络与回滚 |
| [网络安全架构](docs/architecture/家庭NAS网络安全架构.md) | 入口、网络边界、远程维护与待验收事项 |
| [服务与端口清单](docs/operations/服务与端口清单.md) | 当前服务、绑定地址、宿主机/容器端口、预留与释放状态；开启端口前必查，变更后更新 |
| [Docker 宿主机升级验收](docs/operations/2026-10-07-Docker宿主机升级验收.md) | 用户手动升级 Docker / containerd 后的版本、39 个容器恢复与入口验证 |
| [NAS 应用批量升级](docs/operations/2026-10-07-NAS应用批量升级.md) | 16 个应用 / 17 个容器升级、业务验证、私有备份、回滚与遗留告警 |
| [剩余应用与数据库升级](docs/operations/2026-10-07-剩余应用与数据库升级.md) | Immich、Kuma 2、Redis、Playwright 配套升级，数据库恢复实测及宿主机排除范围 |
| [PS5 游戏解压与传输准备](docs/operations/2026-10-07-PS5游戏解压与传输准备.md) | RAR 解压到 SSD 缓存、FTP 连接核对与内置存储传输方法 |
| [剧集手动导入流程](docs/operations/剧集手动导入Sonarr与Jellyfin.md) | 独立下载器 → Sonarr → Jellyfin 的身份核对、复制校验、元数据、验收与回滚 |
| [《母亲》《我的恐怖妻子》导入](docs/operations/2026-10-07-母亲与我的恐怖妻子导入.md) | Gopeed 20 集经 Sonarr 入库、Jellyfin 识别与原件保留 |
| [《仁医》两季导入 Jellyfin](docs/operations/2026-10-07-仁医两季导入Jellyfin.md) | 22 集校验迁移、Sonarr 季集命名、电视剧库与本地元数据 |
| [网盘下载器最终汇总](docs/operations/2026-10-07-网盘下载器最终汇总.md) | 最终分工、当前入口与任务、SSD 备份边界、百度残片清理和维护导航 |
| [Gopeed 与 JDownloader 部署](docs/operations/2026-10-06-下载器部署.md) | 百度/夸克鉴权、SSD 备份排除及 JDownloader 卸载记录 |
| [Gopeed 使用与维护](apps/gopeed/README.md) | 夸克扩展、后台任务与续传验证；百度改走 Rust |
| [Gopeed 多层目录修复](docs/operations/2026-10-07-Gopeed多层目录白名单修复.md) | 默认路径报白名单错误的原因、配置修复、任务恢复与回滚 |
| [Gopeed 夸克下载调优](docs/operations/2026-10-07-Gopeed夸克下载调优.md) | 88VIP 权益核对、4–256 连接测速、保留原任务进度与回滚 |
| [OpenList 多网盘部署](docs/operations/2026-10-06-OpenList部署.md) | 内网 Web、百度 OAuth 接入、SSD 后台下载与备份排除 |
| [百度网盘 Web 部署](docs/operations/2026-10-06-百度网盘Web部署.md) | Rust Web v2.2.4、内网认证、SSD 落盘及验证 |
| [百度网盘 Web GUI 调研](apps/baidupcs-web/README.md) | 第三方界面对比、内网密码入口与运行配置 |
| [BaiduPCS-Go 部署准备](docs/operations/2026-10-06-BaiduPCS部署准备.md) | SSD 下载目录与备份排除核对、首次安装脚本与后续状态 |
| [断电重启服务恢复](docs/operations/2026-10-04-断电重启服务恢复.md) | NPM、网页终端与媒体反代恢复，开机依赖遗留问题 |
| [10 月 7 日断电恢复](docs/operations/2026-10-07-断电恢复.md) | 5 个服务恢复、容器网络修复与 39 个运行容器基线核对 |
| [Codex 代理更新](docs/operations/2026-10-07-Codex代理更新.md) | 宿主机 CLI 经 Docker Mihomo 代理更新、验证与回滚 |
| [Codex 10 月 4 日更新](docs/operations/2026-10-04-Codex代理更新.md) | 宿主机 CLI 更新至 0.160.0、会话恢复与回滚 |
| [Immich 视频上传排查](docs/operations/2026-09-29-Immich视频上传排查.md) | 大文件配置、历史日志与待复现原因 |
| [设备与运维记录](docs/operations/运维记录.md) | 硬件、存储、备份链、NPM/应用修复与版本记录 |
| [备份盘休眠](docs/operations/备份盘休眠.md) | 磁盘身份、自纠正 cron、监控与待核验效果 |
| [Android 安全诊断](docs/security/ANDROID_APP_SECURITY_REVIEW.md) | 安全审查、会话启动边界、测试证据与剩余风险 |
| [Android 控制端](apps/android/README.md) | 独立手机 App、构建安装、安全边界与当前限制 |
| [网页终端安全审查](docs/security/WEB_TERMINAL_SECURITY_REVIEW.md) | 同域风险、NAS 权限边界、撤销与剩余风险 |
| [网页终端 24 小时连接](docs/operations/2026-10-07-网页终端24小时连接.md) | 服务端固定连接期限、心跳容错、JWT 边界与部署回滚 |
| [网页终端断连优化](docs/operations/2026-10-07-网页终端断连排查与优化.md) | 应用心跳、输出流控、有限自动重连、关闭诊断与回滚 |
| [NAS 网页终端](apps/web-terminal/README.md) | 整站 Access 保护的 tmux / 临时 SSH、Codex Markdown 对话与部署验证 |
| [网页终端实施记录](docs/operations/2026-09-29-NAS网页终端.md) | 内部部署、真实 PTY 与浏览器测试、待完成的公网配置 |
| [手机外网连接与 SSH 配置](docs/operations/手机外网连接与SSH密钥配置.md) | 本机连接信息、手机专用密钥、公网入口与 key-only 步骤 |
| [手机接入现有 tmux 规划](docs/operations/手机接入现有tmux规划.md) | 当前会话定位、手机接入步骤与远控界面选项 |
| [Access 实施手册](docs/security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md) | 公开读/登录写与整站私有应用的统一鉴权约束 |
| [媒体整理工具](scripts/media/README.md) | Tachiyomi → CBZ 脚本、用法与限制 |
| [Codex 会话恢复](scripts/codex/README.md) | 从现有 Codex 的 `!` 命令恢复指定 tmux 会话 |
| [迁移与清理记录](docs/migration/README.md) | 来源、合并删减范围及历史找回方法 |

## 目录

```text
docs/
  architecture/       # 网络决策及统一待验收清单
  operations/         # 设备、运维与备份监控
  security/           # 鉴权实施规范
  migration/          # 迁移与清理记录
scripts/media/        # 媒体整理脚本
scripts/codex/        # 指定 tmux/Codex 会话恢复工具
apps/android/         # 独立 Android SSH/tmux 控制端
apps/web-terminal/    # 整站 Access 保护的网页 tmux 终端
```

生产配置和其他维护脚本按原记录位于 `/opt/nas` / 独立 `nas-config` 仓库，尚未收录于本项目。取得文件并核对后，再按需增加 `configs/<服务>/` 或 `scripts/<用途>/`，不预建空目录。

## 维护约定

- 资料于 2026-09-10 从 Homeland 迁入并清理，原仓库已移除迁出文件；装修资料与 Homeland 应用实现仍归 [Homeland](https://github.com/jds5/homeland)。
- 网络决策以架构文档为准；历史“已完成”、版本、端口和设备信息均需现场核对。本次文档整理不代表复验或部署。
- 已废弃方案与冗余原文只保留在 Git 历史中，不另建一套归档树。实际维护记录应注明日期、验证、回滚与遗留事项。
- 不提交密码、令牌、私钥、运行数据或敏感日志。完成必要验证后及时提交并推送，详见 [AGENTS.md](AGENTS.md)。
