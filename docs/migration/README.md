# Homeland NAS 资料迁移记录

迁移日期：2026-09-10。来源：同级 `../homeland`，提交 `298904133b47fbae0a8be1e0cf922888ce377cf5`；检查时源仓库工作区干净。

## 迁移范围与方式

已复制 13 份 NAS / 网络 / 鉴权文档、1 个媒体整理脚本、2 份媒体库存清单，共 16 个文件。另摘录 NAS 长期成本一节。原仓库文件保留，未修改或删除，避免影响 Homeland 的文档阅读页与既有引用；后续 NAS 维护资料在本项目更新，原件作为迁移前快照。

Markdown 保留原文内容和日期，调整相对链接并添加迁移说明；旧网络方案、装机计划和实验记录放入 archive，不代表当前实施方案。脚本与 CSV 保持原始字节，未运行媒体整理。原文中“本轮”“本项目”“根目录”的上下文指当时的 Homeland，不能据此在本仓库寻找前端或生产部署配置。

## 文件对照

| Homeland 原路径 | 本项目路径 |
| --- | --- |
| `docs/家庭NAS网络安全架构.md` | [docs/architecture/家庭NAS网络安全架构.md](../architecture/家庭NAS网络安全架构.md) |
| `docs/本地Docker与NPM部署记录.md` | [docs/operations/本地Docker与NPM部署记录.md](../operations/本地Docker与NPM部署记录.md) |
| `docs/Cloudflare入口与优选IP实验记录.md` | [docs/archive/network/Cloudflare入口与优选IP实验记录.md](../archive/network/Cloudflare入口与优选IP实验记录.md) |
| `docs/家庭NAS实验项目隔离沙箱与共享方案.md` | [docs/archive/network/家庭NAS实验项目隔离沙箱与共享方案.md](../archive/network/家庭NAS实验项目隔离沙箱与共享方案.md) |
| `CLOUDFLARE_ACCESS_IMPLEMENTATION.md` | [docs/security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md](../security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md) |
| `AGENTS.access-auth.md` | [docs/security/AGENTS.access-auth.md](../security/AGENTS.access-auth.md) |
| `tools/pack-tachiyomi-to-cbz.ps1` | [scripts/media/pack-tachiyomi-to-cbz.ps1](../../scripts/media/pack-tachiyomi-to-cbz.ps1) |
| `docs/nas运维/备份盘休眠监控.md` | [docs/operations/备份盘休眠监控.md](../operations/备份盘休眠监控.md) |
| `docs/nas运维/运维记录.md` | [docs/operations/运维记录.md](../operations/运维记录.md) |
| `docs/nas运维/备份盘休眠配置.md` | [docs/operations/备份盘休眠配置.md](../operations/备份盘休眠配置.md) |
| `docs/archive-个人设备NAS/NAS网络架构设计.md` | [docs/archive/setup/NAS网络架构设计.md](../archive/setup/NAS网络架构设计.md) |
| `docs/archive-个人设备NAS/NAS搭建进度.md` | [docs/archive/setup/NAS搭建进度.md](../archive/setup/NAS搭建进度.md) |
| `docs/archive-个人设备NAS/NAS装机TODO.md` | [docs/archive/setup/NAS装机TODO.md](../archive/setup/NAS装机TODO.md) |
| `docs/archive-个人设备NAS/光猫超密破解调研.md` | [docs/archive/setup/光猫超密破解调研.md](../archive/setup/光猫超密破解调研.md) |
| `references/F盘清单-marvel-TV.csv` | [references/media/F盘清单-marvel-TV.csv](../../references/media/F盘清单-marvel-TV.csv) |
| `references/F盘清单-animate.csv` | [references/media/F盘清单-animate.csv](../../references/media/F盘清单-animate.csv) |

完整来源 SHA-256 见 [homeland-files.json](homeland-files.json)，用于核对迁移来源；Markdown 因说明和链接调整，目标哈希不要求与源文件一致。

## 保留在 Homeland 的内容

- 装修预算、施工验收、弱电箱位置、家电选型与智能家居施工要求：仍属于装修档案。NAS 采购背景已包含在搭建进度中；长期成本另摘录至 [NAS长期成本历史摘录](../operations/NAS长期成本历史摘录.md)。
- Homeland 前端、`docker-compose.yml`、Dockerfile 和 Access 校验脚本：属于该应用实现。迁入文档中的代码证据链接仍指向同级 `../homeland` 的对应文件。
- Homeland 的 `AGENTS.md`、`CLAUDE.md` 和工具技能：不作为 NAS 项目的协作规则迁入；NAS 自身约定由根目录 `AGENTS.md` 管理。可复用的 Access 规则单独保存在 `docs/security/`。
- `.env`、运行数据目录与 Git 历史未复制。

跨仓库相对链接需要 `nas` 与 `homeland` 并列检出才能打开；本项目内部的 NAS 文档链接独立可用。原项目暂不删除原件、不添加跳转页，因此原站点仍展示迁移前内容。

## 原有缺项与修正

- `NAS应用部署TODO.md` 原标记“待写”，源仓库不存在：保留缺项说明，不虚构部署文档。
- 早期装机文档指向同目录装修预算、前期调研、家电必决清单的链接已经失效：改指 Homeland 的实际路径。
- 装修预算中的 NAS 专节已移除：改为预算文档入口；光猫调研的旧“执行顺序”锚点更新为现有章节。
- `/opt/nas/scripts/` 中的备份、休眠、监控脚本以及生产 Compose 在原文所述 NAS / `nas-config` 中，源仓库没有这些文件，本次未获取。暂不创建空的部署目录或凭记录重构脚本。
- 休眠配置中的 hd-idle 方案后来被自纠正 cron 取代，阅读时应连同休眠监控记录末尾的修复一起看；服务版本、端口和待办仍需现场复验。
- 媒体整理脚本原有 `-DryRun` 仍会创建输出目录并写日志，跳过已有文件也不代表校验压缩包完整性；本次保持脚本不变，实际使用说明见 [媒体脚本](../../scripts/media/README.md)。

## 验证边界

完成来源文件及脚本 / CSV 一致性核对、Markdown 相对路径与章节链接检查、格式检查和凭据模式检查。未连接 NAS，未验证外部网页、服务配置或历史技术结论；环境无 PowerShell，未执行脚本。
