# OpenList 内网多网盘管理

2026-10-06 已部署 OpenList **v4.2.6**，内网入口 **http://192.168.50.33:5244**。用户名 `yao`，密码沿用 BaiduPCS-Rust 网页入口密码，私有记录位于 `/opt/nas/cache/openlist/private/access.txt`。这是 OpenList 自身登录，不是单点登录；两边以后改密码不会自动同步。

## 使用

- `/百度网盘`：已完成 OAuth 授权，使用官方接口，可浏览自己的网盘。
- `/SSD下载`：NAS 的 `/opt/nas/cache/openlist/downloads`，在 SSD 上，排除 NAS 自动备份。
- 要让 NAS 后台下载：在百度目录选中文件或目录，选择 **复制**，目的地选 `/SSD下载`；在任务管理中查看复制进度。关闭网页不影响后台任务。
- 浏览器的普通“下载”按钮会下载到浏览器所在设备，不等同于保存到 NAS。
- 现有 `/PPSA03671` 仍由 BaiduPCS-Rust 下载，本次未迁移或重复创建。不要在 OpenList 再复制整个目录，除非确实要另开一份下载。
- 当前下载、转存、复制任务工作数设为 1，三类任务启用持久化；任务持久化不保证所有云盘大文件都能按字节断点恢复。
- `/迅雷云盘`：国内 `Thunder` 驱动，已完成账号密码登录与短信设备验证；根目录及下级目录读取通过。
- `/夸克网盘`：已准备普通 `Quark` 驱动，暂时禁用，等待用户在管理页填写网页登录 Cookie。关闭转码地址与仅列出视频选项，启用本地代理；尚未完成鉴权或下载验证。

## 部署与鉴权

[compose.yaml](compose.yaml) 仅绑定 NAS LAN IP 的 5244 端口，独立 Docker 网络，UID/GID 1000，根文件系统只读，无额外 capabilities，无 Docker socket。FTP、SFTP、S3 服务关闭，guest 禁用，下载签名开启。登录页和公开站点设置可匿名读取，目录、管理 API 需要登录；持有效下载签名的链接可以下载，不应对外传播。

使用 OpenList 内置登录而非叠加 HTTP Basic，避免与其 Authorization 令牌冲突。内网 HTTP 未加 TLS；没有修改 NPM、路由器或公网入口。

Rust 保存的百度 Cookie 无法直接替代 OpenList 的 OAuth refresh_token。百度 OAuth 接口拒绝了自动登录尝试，随后由用户在百度官方页面完成授权。使用 OpenList 文档提供的 ES 文件浏览器客户端参数；令牌交换、刷新均直接访问百度官方域名，`use_online_api=false`，不向第三方令牌中转服务提交账号凭据。

数据目录 `/opt/nas/cache/openlist/data` 包含配置、SQLite、云盘令牌和临时文件；`private` 保存访问凭据、部署材料及配置备份。整个 `/opt/nas/cache/openlist` 均被 NAS restic 与 nas-config Git 排除，不自动备份；恢复服务须另行保管凭据或重新登录。

## 镜像来源与重建

官方源码标签 `v4.2.6`，提交 `2bdf16d5967d0a403f67d809efd5a418b8f5bd30`。

Docker Hub 下载缓慢、GHCR denied，改用 GitHub 官方 Release 的 `openlist-linux-musl-amd64.tar.gz`，49,885,472 字节，SHA-256：

```text
bacc55bc6f308aa4b37bad18a24c05f0c05c943bfb066a8d59f48b9340f2e2d2
```

发布包经已有本机代理取得，未修改系统代理配置。已核对 GitHub Release 元数据摘要和本机计算值一致。包保存在 `private/openlist-proxy.tar.gz`，已解压二进制在 `private/build/openlist`。

[Dockerfile](Dockerfile) 复用 NAS 已有、固定摘要的 Nginx Alpine 镜像作为运行基础，仅启动 OpenList；Nginx 不启动。版本命令核验后端与 Web 均为 v4.2.6。Compose 固定本次构建的 image ID，`pull_policy: never`。

重建命令（依赖 Docker、已校验的官方 musl amd64 二进制，仅新建本地镜像）：

```bash
docker build --network=none --pull=false \
  -f /home/yao/code/nas/apps/openlist/Dockerfile \
  -t nas-openlist:4.2.6 /opt/nas/cache/openlist/private/build
```

重建后核对 image ID 并更新 Compose，不假设不同机器会生成相同摘要。

## 维护与回滚

```bash
# 查看状态
docker compose -f /home/yao/code/nas/apps/openlist/compose.yaml ps
# 停用本次服务（会中断 OpenList 任务，保留所有数据）
docker compose -f /home/yao/code/nas/apps/openlist/compose.yaml stop
# 恢复
docker compose -f /home/yao/code/nas/apps/openlist/compose.yaml up -d
```

开放 LAN 前的小型配置备份位于 `private/before-lan-20261006-210300/`，不含下载数据。若需仅撤销任务持久化配置，先停服务，将该目录的 `config.json` 复制回 `data/config.json`，再 `up -d`；任务线程还需在后台设置中改回。数据库备份仅供人工恢复，不应直接覆盖后续新任务或刷新的令牌。

本次现场验证与限制见[运维记录](../../docs/operations/2026-10-06-OpenList部署.md)。
