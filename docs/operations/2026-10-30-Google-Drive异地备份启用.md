# 10 月 30 日 Google Drive 异地备份启用

时间：2026-10-30 15:09–16:05（Asia/Shanghai），主机 `ynas`。OneDrive 于 10-28 停用后，NAS 没有任何异地副本（见 [10-29 巡检](2026-10-29-NAS巡检.md)）。用户要求改用 Google Drive，并完成了 Google Cloud 授权应用和 rclone 授权；sudo 由用户临时授予（`/etc/sudoers.d/zz-maint-20261029`），用完删除。

## 方案

沿用 OneDrive 的做法：每天先校验 restic 仓库 `/mnt/critical-mirror`，再用 rclone 把整个仓库（约 1.1 TiB，restic 已加密）同步到 `gdrive:nas-backup`。

```text
restic-backup [02:00] → critical-mirror
                         ├─ restic-mirror [06:00] → secondary-mirror
                         └─ restic-gdrive [07:00，01:30 前停止] → Google Drive
```

| 项目 | 设置 |
| --- | --- |
| 账号空间 | 5 TiB，启用前已用 0 |
| 授权 | 用户自建 Google Cloud 项目，OAuth 应用已发布为正式版（测试状态的刷新令牌 7 天过期），桌面应用客户端；rclone 远端 `gdrive`，scope `drive.file`，只能访问 rclone 自己创建的文件；配置在 root 的 rclone.conf，不入库 |
| 脚本 | `/opt/nas/scripts/restic-gdrive.sh`（由 `restic-onedrive.sh` 改写，随 nas-config 每日备份） |
| 限速 | 全天 3.5 MiB/s（约 30 Mbit/s，用户指定）；家庭上行实测约 39 Mbit/s |
| 单次上限 | `--max-transfer 700G`（Google 每天上传上限 750 GB）；每天 01:30 前停止，01:30–06:30 内启动会直接跳过，避开 02:00 主备份与 prune、06:00 镜像 |
| 部分完成 | rclone 退出码 8（达到传输上限）、10（达到时长上限）记为 `PARTIAL`，下次运行继续，已传文件不重传 |
| 告警 | Kuma 复用原 `restic-onedrive` 推送地址（`uptime-kuma-urls.sh` 新增 `UPTIME_KUMA_RESTIC_GDRIVE`）；监控间隔 24 小时，重试 2 次、间隔 1 小时 |
| 定时 | root crontab `0 7 * * * /opt/nas/scripts/restic-gdrive.sh`；OneDrive 那一行保持注释 |

## 验证

- 连接：`rclone about` 显示 5 TiB；token 含 refresh_token。
- 上传：512 MiB 随机文件单线程 106 秒（约 39 Mbit/s）；4 个 128 MiB 并发也是约 38 Mbit/s，瓶颈在宽带上行。
- 下载：同一个 512 MiB 文件直连下载 918 秒（约 4 Mbit/s），`cmp` 一致；64 MiB 直连约 14 Mbit/s；**经 Mihomo 代理更慢**（64 MiB 10 分钟未完成，已中止）。并发上传的 4 个文件 `rclone check` 0 差异。
- 测试目录已 `purge`；回收站留有约 1 GiB 测试文件，30 天后自动清空。没有执行 `rclone cleanup`，因为它会清空整个账号的回收站。
- 时间窗口逻辑用 07:00、15:55、00:30、01:45、06:29、06:31 六个时刻验证：前三个分别得到 1110、575、60 分钟上限，01:45、06:29 跳过，06:31 得到 1139 分钟。
- 首次全量同步 15:53 用 `systemd-run --unit=restic-gdrive-initial` 启动（Nice 10、IO idle）：源仓库检查通过（23 个快照），上限 576 分钟，限速 3.5M。

## 首次全量上传

按 3.5 MiB/s、每天约 18 小时计算，每天约 220 GiB，1.1 TiB 需要约 5 天；首日只有约 9.5 小时。期间每天约 01:30 推送一次 `PARTIAL`，全部传完后改为 07:00 后不久推送 `OK`。进度看 `/opt/nas/scripts/restic-gdrive.log` 中每 5 分钟的统计行，以及 `Source files` / `Remote files` 计数。原 OneDrive 脚本的统计行按 INFO 级别输出，日志里看不到；16:01 已在脚本中加入 `--stats-log-level NOTICE`（原子替换，正在运行的首次同步仍用旧版本），所以首日的进度要用 `sudo rclone size gdrive:nas-backup` 查看。16:01 时已上传 127 个文件、1.37 GiB，约 3.1 MiB/s。

**全部传完后还要做一次恢复验证**：用 `restic -r rclone:gdrive:nas-backup check --read-data-subset=…` 从云端读一部分数据，证明这份备份能恢复。没有做这一步之前，不能认为异地备份已经可用。

## 恢复时注意

云端下载只有约 4–14 Mbit/s，整仓 1.1 TiB 从云端恢复需要数周。云端适合在本机两块盘都损坏时恢复照片、文档等关键数据，按路径挑着恢复；整盘恢复优先用 secondary-mirror。

## 回滚

备份在 `/home/yao/.nas-maintenance-backups/gdrive-20261030/`（不入 Git）：`root-crontab.before`、`uptime-kuma-urls.sh.before`。

1. `sudo systemctl stop restic-gdrive-initial`（如果首次同步还在跑）。
2. `sudo crontab -u root /home/yao/.nas-maintenance-backups/gdrive-20261030/root-crontab.before`。
3. 删除 `/opt/nas/scripts/restic-gdrive.sh`，从 `uptime-kuma-urls.sh` 删去 `UPTIME_KUMA_RESTIC_GDRIVE` 一行。
4. 需要撤销授权时：`sudo rclone config delete gdrive`，再到 Google 账号「第三方应用和服务」移除该应用。云端 `nas-backup` 目录另行决定是否删除。

## 未完成与注意

- **需要你在 Kuma 页面操作**：把 `restic-onedrive` 监控改名为 `restic-gdrive`，然后点「恢复」。它现在仍是暂停状态，失败不会告警。
- OneDrive 的 rclone 远端和令牌仍在 root 配置里，是否删除、吊销另行决定。
- 首次全量上传完成和云端恢复验证都还没做；完成后补记到本文。
- 上传期间 sda（critical-mirror）会一直转动，不会休眠，属于预期。
- 临时 sudoers 文件已于 16:05 删除。首次同步和以后的定时任务都以 root 身份运行，不依赖它。
- 脚本会由 04:00 的 git-backup 自动提交到 nas-config；日志 `restic-gdrive.log` 已被 `.gitignore` 排除。
