# NAS 维护项目

用于管理 NAS 的配置、维护脚本和运维文档，记录日常维护与故障处理过程。

## 文档入口

- [网络安全架构](docs/architecture/家庭NAS网络安全架构.md)：现行决策、目标架构与验收要求。
- [运维记录](docs/operations/运维记录.md)：设备、存储、服务与备份的历史记录。
- [备份盘休眠配置](docs/operations/备份盘休眠配置.md) / [监控与后续修复](docs/operations/备份盘休眠监控.md)。
- [Docker 与 NPM 部署记录](docs/operations/本地Docker与NPM部署记录.md)：Homeland 应用的历史部署证据。
- [Cloudflare Access 实施手册](docs/security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md) / [鉴权规则](docs/security/AGENTS.access-auth.md)。
- [迁移清单](docs/migration/README.md)：来源、文件对照、保留范围与待补资料。

## 目录结构

```text
docs/
  architecture/       # 现行架构与验收要求
  operations/         # 运维、备份、监控与部署记录
  security/           # 安全实施手册与可复用规则
  archive/
    setup/            # 早期装机、硬件与光猫调研
    network/          # 已废弃方案与网络实验
  migration/          # 迁移记录及来源校验清单
scripts/media/        # 媒体整理脚本与使用说明
references/media/     # 历史媒体库存清单
```

按实际内容建目录；后续取得生产配置或维护脚本时，再按服务增加 `configs/<服务>/` 或 `scripts/<用途>/`。

## 资料状态

2026-09-10 从 `../homeland` 复制整理 NAS 资料，原项目保留原件，后续 NAS 资料在本项目维护。历史记录中的“已完成”、磁盘名、端口、版本和待办不代表本次已复验；网络决策以现行架构文档为准，操作前确认现场状态。

生产配置与备份、休眠脚本按原记录位于 `/opt/nas` 和独立的 `nas-config` 仓库，本项目尚未收录。涉及 Homeland 应用与装修背景的链接依赖同级 `../homeland` 检出。

## 使用约定

- 按实际需要添加脚本、配置和文档，保持结构简单。
- 变更前确认目标设备及影响范围，重要操作先备份。
- 记录操作步骤、验证结果和回滚方法。
- 不提交密码、令牌、私钥等敏感信息，配置示例使用占位符。

媒体工具的依赖、用法和限制见 [scripts/media/README.md](scripts/media/README.md)，库存清单说明见 [references/media/README.md](references/media/README.md)。
