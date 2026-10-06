# JDownloader 2 内网下载器

入口 **http://192.168.50.33:5800**，用户名 `yao`，初始密码沿用 OpenList / 百度 Rust 网页入口密码。各服务以后修改密码不会同步。浏览器显示 HTTP Basic 登录框，随后进入 JDownloader 桌面。无需注册 MyJDownloader，即可在内网操作。

## 使用与存储

在 LinkGrabber（链接抓取器）添加国外网盘链接，解析后移入 Downloads 队列。需要账户的网盘在 Settings → Account Manager 中自行登录，账户限制仍由网盘决定。关闭网页不影响后台下载，容器配置自动重启。

默认下载目录 `/output` 对应宿主 SSD `/opt/nas/cache/jdownloader/downloads`。配置、下载队列和插件在 `/opt/nas/cache/jdownloader/config`，入口凭据与安装材料在 `private`。整个目录被现有 restic 和 nas-config Git 规则排除，下载文件不自动备份；配置也不备份，重装前应单独保管必要配置。

仅 gateway 发布 `192.168.50.33:5800`。应用 VNC 5900、MyJDownloader 3129 没有发布到宿主。独立 bridge，无 Docker socket，不接公网入口；网页文件管理和网页终端关闭。网关使用只读根文件系统、UID 1000、无 capabilities。应用启动器需 root，Java 与桌面进程实际运行 UID 1000。

首次更新直连较慢，Connection Manager 已配置现有 NAS HTTP 代理 `192.168.50.33:7890`，由现有代理规则决定出口。原 InternetConnectionSettings 文件保存在 `private/pre-proxy/`。若以后停用该代理，应在 Connection Manager 切回直连；或停止 JDownloader，将上述备份的两个 JSON 复制回 `config/cfg/` 再启动。此设置不影响其他应用。

## 来源与复现

采用社区维护的 [jlesage/docker-jdownloader-2](https://github.com/jlesage/docker-jdownloader-2) 容器包装，内部运行 JDownloader 2。2026-10-06 从官方 Docker Hub 仓库解析 amd64 镜像，下载每层并校验 SHA-256。上游 manifest 摘要：

```text
sha256:b5e40d7e313f43db2a02124949703c45c4e28d45db601236010f3db71ac2d39f
```

由于 Docker 拉取大层持续阻塞，改用已有 NAS 代理分段获取同一上游 blob，校验后导入本地 Docker。归档位于 `/opt/nas/cache/jdownloader/private/image/image.tar`，本地标签 `jlesage/jdownloader-2:nas-20261006`。Compose 固定导入后的 image ID；JDownloader 自身仍会在首次启动与后续检查时更新核心及插件。

```bash
# 仅在镜像丢失时从已校验归档恢复
docker load -i /opt/nas/cache/jdownloader/private/image/image.tar
docker compose -f apps/jdownloader/compose.yaml config --quiet
docker compose -f apps/jdownloader/compose.yaml up -d
# 停用/回滚本次新增服务，保留配置和下载文件
docker compose -f apps/jdownloader/compose.yaml stop
```

升级前先停止服务，备份 `config` 到权限受限目录，再更新镜像；回滚须同时恢复匹配配置和镜像。不要删除下载目录。

实际验证：首次核心与插件更新完成，浏览器进入 Downloads / LinkGrabber 主界面；入口匿名请求 401、正确密码 200，远程桌面 WebSocket 正常。Java 运行 UID 1000，默认下载路径 `/output`。未配置国外网盘账户，未验证其具体下载速度。
