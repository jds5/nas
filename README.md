# NAS 维护项目

集中维护 NAS 的网络决策、设备与操作记录、备份恢复和维护工具。

## 文档入口

| 文档 | 内容 |
| --- | --- |
| [网络安全架构](docs/architecture/家庭NAS网络安全架构.md) | 入口、网络边界、远程维护与待验收事项 |
| [设备与运维记录](docs/operations/运维记录.md) | 硬件、存储、备份链、NPM/应用修复与版本记录 |
| [备份盘休眠](docs/operations/备份盘休眠.md) | 磁盘身份、自纠正 cron、监控与待核验效果 |
| [Android 控制端](apps/android/README.md) | 独立手机 App、构建安装、安全边界与当前限制 |
| [手机接入现有 tmux 规划](docs/operations/手机接入现有tmux规划.md) | 当前会话定位、手机接入步骤与远控界面选项 |
| [Access 实施手册](docs/security/CLOUDFLARE_ACCESS_IMPLEMENTATION.md) | 公开读、登录写的统一鉴权约束和测试矩阵 |
| [媒体整理工具](scripts/media/README.md) | Tachiyomi → CBZ 脚本、用法与限制 |
| [迁移与清理记录](docs/migration/README.md) | 来源、合并删减范围及历史找回方法 |

## 目录

```text
docs/
  architecture/       # 网络决策及统一待验收清单
  operations/         # 设备、运维与备份监控
  security/           # 鉴权实施规范
  migration/          # 迁移与清理记录
scripts/media/        # 媒体整理脚本
apps/android/         # 独立 Android SSH/tmux 控制端
```

生产配置和其他维护脚本按原记录位于 `/opt/nas` / 独立 `nas-config` 仓库，尚未收录于本项目。取得文件并核对后，再按需增加 `configs/<服务>/` 或 `scripts/<用途>/`，不预建空目录。

## 维护约定

- 资料于 2026-09-10 从 Homeland 迁入并清理，原仓库已移除迁出文件；装修资料与 Homeland 应用实现仍归 [Homeland](https://github.com/jds5/homeland)。
- 网络决策以架构文档为准；历史“已完成”、版本、端口和设备信息均需现场核对。本次文档整理不代表复验或部署。
- 已废弃方案与冗余原文只保留在 Git 历史中，不另建一套归档树。实际维护记录应注明日期、验证、回滚与遗留事项。
- 不提交密码、令牌、私钥、运行数据或敏感日志。完成必要验证后及时提交并推送，详见 [AGENTS.md](AGENTS.md)。
