# Gopeed 内网下载器

部署版本 v1.9.3，入口 **http://192.168.50.33:9999**。用户名 `yao`，初始密码沿用 OpenList / 百度 Rust 网页入口密码，各服务以后修改密码不会同步。凭据与 API Token 仅保存在 `/opt/nas/cache/gopeed/private/`。

## 使用

- 百度扩展 `monkeyWie/gopeed-extension-baiduwp` v1.3.7：复用 OpenList 的百度 OAuth client_id、client_secret 和 refresh_token，刷新直连百度官方接口。
- 夸克扩展 `iGwkang/gopeed-extension-quark` v1.0.4：复用 OpenList 的网页登录 Cookie，支持自己的网盘目录和分享链接。关闭 `delete_file`，不自动清理云端转存文件；分享下载仍需要转存空间。
- 在“新建任务”粘贴网盘目录链接，解析后选择文件下载。百度示例：`https://pan.baidu.com/disk/main#/index?category=all&path=%2FPPSA03671`。该目录原有 Rust 下载任务仍存在，不要重复下载整目录。
- 夸克自己的目录链接可从 `https://pan.quark.cn` 地址栏复制；根目录示例：`https://pan.quark.cn/list#/list/all`。
- 百度扩展不支持直接解析分享链接，须先转存到自己的网盘；目录递归有深度限制。夸克分享可带 `?pwd=提取码`。
- 默认同时运行 2 个文件，HTTP 新任务默认每文件 256 个连接（2026-10-07 夸克实测后调整）。这是通用 HTTP 默认值，非按网盘自动选择；新建百度任务建议手动设为 4，已有百度任务仍为 4。当前夸克任务已保留进度并转换到 256 分片，实测约 25 MiB/s；详见[调优记录](../../docs/operations/2026-10-07-Gopeed夸克下载调优.md)。
- 下载在 NAS 后台运行，关闭网页不影响；目标容器路径 `/downloads`，宿主 `/opt/nas/cache/gopeed/downloads`。不自动解压，不自动删除已下载文件。

## 存储与网络

`storage` 保存数据库、扩展、凭据与日志，`private` 保存访问配置、安装材料。整个 `/opt/nas/cache/gopeed` 位于 SSD `/dev/nvme0n1p3`，被 restic 和 nas-config Git 排除；下载及运行数据不自动备份。重建时需另行保管配置或重新授权。

Compose 只绑定 LAN 地址，独立 bridge，不接 NPM，不发布公网。进程 UID/GID 1000，根文件系统只读，无 capabilities，无 Docker socket。原生网页登录保护 API，入口为内网 HTTP。

安装扩展时直连 GitHub 失败，临时使用已有 NAS 代理获取，完成后恢复 Gopeed 代理关闭；百度、夸克下载默认直连。日志可能含临时下载链接，整个 storage 目录权限受限，不应分享原始日志。

## 来源、验证与维护

官方发布包：`https://github.com/GopeedLab/gopeed/releases/download/v1.9.3/gopeed-web-v1.9.3-linux-amd64.zip`，40,090,273 字节，SHA-256：

```text
52444c86ffb5152c89d1c3a3b074285c4ef6dd3dabc9d4ae097cc703e0fa3e15
```

[Dockerfile](Dockerfile) 复用固定摘要 Alpine 镜像，仅运行官方静态 Gopeed 二进制。发布包保存在 `private/gopeed-web.zip`，构建输入在 `private/build/gopeed`，Compose 固定本次构建镜像 ID。

```bash
# 重建依赖已下载并校验的官方二进制，不修改其他服务
docker build --network=none --pull=false -f apps/gopeed/Dockerfile \
  -t nas-gopeed:1.9.3 /opt/nas/cache/gopeed/private/build
# 核对新 image ID 后更新 compose.yaml
docker compose -f apps/gopeed/compose.yaml up -d
# 停用/回滚本次新增服务，保留数据
docker compose -f apps/gopeed/compose.yaml stop
```

实际验证：百度目录解析 25 个文件；夸克根目录递归解析 33 个文件；夸克测试文件 248,789 字节完整下载，与另一次云端读取的 SHA-256 一致。百度测试任务下载 279,475 字节后暂停，容器重建后保持进度，续传至 834,389 字节。测试任务及本次测试文件已通过 API 清理，最终无新增下载任务。原生网页登录成功，匿名 API 返回 HTTP 401。未验证 100 GB 完整传输、长期链接过期或分享转存流程。
