# 百度网盘 Rust 下载器

2026-10-06：已部署 BaiduPCS-Rust v2.2.4 与专用 Nginx 密码入口，内网地址 **http://192.168.50.33:18888**。现场验证、镜像来源和限制见[部署记录](../../docs/operations/2026-10-06-百度网盘Web部署.md)。

2026-10-07 收尾核对：服务正常，任务列表及宿主下载目录为空；Gopeed 百度插件已移除，后续百度下载统一使用本服务。下方选型和迁移内容为历史记录，当前状态见[最终汇总](../../docs/operations/2026-10-07-网盘下载器最终汇总.md)。

## 选型证据

| 项目 | 核对结果 |
| --- | --- |
| [qjfoidnh/BaiduPCS-Go](https://github.com/qjfoidnh/BaiduPCS-Go) | 当前安装为 4.0.2 CLI；[Web API PR #320](https://github.com/qjfoidnh/BaiduPCS-Go/pull/320) 仍显示 Open，不能假设现有二进制已有可用 Web 服务 |
| [mayunbaba2/BaiduPCS-Go-Web](https://github.com/mayunbaba2/BaiduPCS-Go-Web) | 包装 CLI 的独立前端；后端 `index.js` 将网页命令拼接给 shell.exec，密码写在代码中，不能原样用于本机管理入口 |
| [masx200/baidupcs-web](https://github.com/masx200/baidupcs-web) | 仓库明确不再维护 |
| [linux-doc/baidupcs-web](https://github.com/linux-doc/baidupcs-web) | 文档基于旧 3.7.1 后端，尚无适配当前百度接口的本机证据 |
| [Galiathuss/BaiduPCS-WEB-UI](https://github.com/Galiathuss/BaiduPCS-WEB-UI) | 基于 3.9.5 与独立 API 分支，作者说明下载状态/位置控制不够精确；不能直接接现有 CLI |
| [komorebiCarry/BaiduPCS-Rust](https://github.com/komorebiCarry/BaiduPCS-Rust) | 独立 Rust 下载器与 Vue Web 界面，支持队列、扫码/Cookie 登录、访问密码；[v2.2.4](https://github.com/komorebiCarry/BaiduPCS-Rust/releases/tag/v2.2.4) 于 2026-09-18 发布，优先作为隔离验证候选 |

Rust 项目不是 Go 程序的前端，不能自动接管 nohup 任务或复用 Go 续传文件。2026-10-06 曾按用户授权停止并清理旧任务、卸载 Go，再由 Rust 重新创建 PPSA03671 下载，详见部署记录的任务迁移部分；该任务不在 2026-10-07 收尾时的队列中。

## 部署与存储

[compose.yaml](compose.yaml) 固定已核验的 Rust 本地 image ID 和 Nginx 仓库摘要。Rust 来自 GitHub Release 的 amd64 镜像包，经 SHA-256 校验导入；Docker Hub 指定版本不存在、GHCR 返回 denied，未以 latest 替代。重建到另一台机器前须重新取得并验证镜像包，`pull_policy: never` 不会从网络猜测同名镜像。

- 仅 `gateway` 发布 `192.168.50.33:18888`，全路径 HTTP Basic 认证，用户名 `yao`。随机密码存放于 `/opt/nas/cache/baidupcs-downloads/.web-state/access.txt`，不入 Git。
- [nginx.conf](nginx.conf) 保护页面、API 和 WebSocket，拒绝不匹配的 Origin 及跨站请求。Rust 内置 Web 认证关闭，由入口统一认证；不要在应用设置中另开内置认证，否则前端 Bearer 标头会与入口 Basic 认证冲突。
- 两个容器均使用 UID/GID 1000、去除 capabilities、禁止提权；专用 bridge，不连接 NPM 或其他业务网络。Nginx 根文件系统只读，运行临时文件在 tmpfs。
- Web 下载目录：`/opt/nas/cache/baidupcs-downloads/web` → `/app/downloads`。
- Web 状态：`/opt/nas/cache/baidupcs-downloads/.web-state/{config,data,logs,wal}`；含登录凭据，仅限 yao 访问。
- 两者都在 SSD 上、均被已核对的 restic 和配置 Git 排除，**Web 任务/账号状态也不自动备份**。
- 应用自动备份总开关关闭、备份配置为空、分享同步订阅为空；不自动上传本地数据。
- 原 CLI 已卸载、旧任务文件已清理；后续创建任务前先核对当前队列与目标目录，避免重复下载。

## 维护与回滚

运行入口在本仓库 `apps/baidupcs-web/`，生产数据不在仓库。变更前先核对任务并备份必要的小型配置到权限受限目录；不要把下载数据纳入备份。维护由助手执行，遇权限限制按 AGENTS.md 申请。

查看状态：

```bash
docker compose -f /home/yao/code/nas/apps/baidupcs-web/compose.yaml ps
```

停用本次新增服务（中断 Web 下载；不删除文件）：

```bash
docker compose -f /home/yao/code/nas/apps/baidupcs-web/compose.yaml stop gateway web
```

恢复：

```bash
docker compose -f /home/yao/code/nas/apps/baidupcs-web/compose.yaml up -d
docker compose -f /home/yao/code/nas/apps/baidupcs-web/compose.yaml restart gateway
```

后端重建导致容器 IP 变化时重启 gateway，使 Nginx 重新解析服务地址。没有配置公网转发或改动现有 NPM。入口是 LAN HTTP，使用浏览器 Basic 登录；未部署 TLS，局域网传输不加密。绑定 LAN IP 不等于已经完成路由器公网边界复扫。
