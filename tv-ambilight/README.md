# 电视 Jellyfin 灯带同步（WLED）

为局域网内的 LG 电视做 DIY 氛围灯：播放 Jellyfin 内容时，灯带颜色跟随画面边缘。本目录保存方案、进度和物料脚本；**物料本体不进 Git**，放在 NAS 的 `/home/yao/tv-ambilight-materials/`。

## 当前进度

| 日期 | 状态 |
| --- | --- |
| 2026-11-03 | 评估其他 AI 给的方案（WLED + HA + Kodi/Jellyfin），结论见下文；用户放弃 Hue（App 需订阅），选择 DIY、不 root |
| 2026-11-04 | 物料已下载到 NAS 并校验（见 `materials.lock`）；**按用户要求只准备，不执行**：未建测试实例、未编译、未改正式 Jellyfin |

**下一步**（用户要求继续时）：先运行一次 `bash tv-ambilight/fetch-materials.sh` 检查有无新版本，再从[阶段 1](#阶段-1插件适配-jellyfin-124助手nas-上隔离进行) 开始。灯带硬件是否已购买，需向用户确认。

## 设备与现状

| 项目 | 值 |
| --- | --- |
| 电视 | LG **OLED55G5PCA**（2025 年 G5 系列 55 寸，国行），`192.168.50.51`；UPnP 显示内核 Linux 6.12（webOS 25 代，具体版本未登录确认） |
| 开发者模式 | 已开启，SSH 端口 9922（用户 `prisoner`，密钥登录）。私钥需在 Developer Mode App 打开 **Key Server** 后从 9991 端口下载，口令是 App 上显示的 6 位码；截至 11-04 Key Server 未打开，**尚未登录过** |
| Jellyfin 客户端 | 侧载的社区客户端 [Litefin](https://github.com/MoazSalem/litefin)（MPL-2.0），不是 LG 商店版 |
| Jellyfin 服务端 | NAS 容器 `jellyfin/jellyfin:12.4`，.NET 10；现有插件 targetAbi 为 12.x |
| root | 无，且方案不依赖 root |

## 方案评估结论（11-03）

原方案"WLED 灯带 + Home Assistant + Kodi/Jellyfin"：

- **硬件（ESP32 + WLED + 可寻址灯带）合理**，保留。灯带选 **RGB**（WS2812B 或 SK6812 RGB），不选 RGBW：插件不支持白光通道。
- **Kodi 路线不可行**：webOS 没有官方 Kodi，开发者模式应用会随会话过期被清除；Kodi 的 Hyperion 插件要先发给 Hyperion 服务器，而且在 webOS 移植版上能否截到画面没有任何证据。
- **Home Assistant 不需要**：WLED 收到实时数据时自动进入同步模式，停止后自动恢复原灯效；NAS 也没有部署 HA。
- **Hue Sync TV**：本机型（2024 年后 LG）支持，但 App 收费订阅，用户放弃。
- **HDMI 采集方案不适用**：画面来自电视自带 App，不经过 HDMI 输出。
- 电视内取色（不 root）基本不可能：Litefin 用硬件解码，视频不经过网页层，读不到像素（推断，未验证）。

## 采用的方案

**颜色预先在 NAS 上算好，播放时按进度推给 WLED**，只覆盖 Jellyfin 播放的内容。

- 取色与推送：[jellyfin-ambilight](https://github.com/gabrielprat/jellyfin-ambilight)（GPL-3.0，v2.5.0）。Jellyfin 服务端插件，用 ffmpeg 预先分析每部影片的画面边缘（每部电影约 100–350 MB），播放时以会话事件同步，通过 UDP（Hyperion raw RGB，端口 19446）推给 WLED。每个 WLED 最多 490 颗灯珠；按设备映射决定哪个客户端触发哪条灯带；有延迟补偿设置。
  - **不能直接装**：它基于 Jellyfin 10.10 / .NET 8 编译，NAS 是 12.4 / .NET 10。NuGet 已有 `Jellyfin.Controller 12.4.0`，需要改依赖版本、修 API 变化后重新编译。
  - **成熟度低**：40 星，作者自称"Vibe coding - Agent coding driven, be alert!"。它在 Jellyfin 进程内运行 ffmpeg，所以先在隔离的测试实例验证，再决定是否装到正式服务。
- 同步精度：Litefin 在开始、暂停、继续、拖动进度时立即上报，平时每 10 秒上报一次；插件自己计时，收到上报再校正。
- 可选增强：Litefin 有插件系统，能拿到高频播放进度。如果同步不够准，可以写一个 Litefin 插件把进度直接发给 NAS。代价是要维护自己构建的 Litefin，原作者每次更新都要重新合并。

## 硬件清单（建议）

- ESP32 开发板（经典 ESP32 即可，对应固件 `WLED_16.0.1_ESP32.bin`；如买 S3/C3 需换对应固件）
- RGB 可寻址灯带 WS2812B 或 SK6812 RGB，60 珠/米；55 寸三边或四边约 3.5–4 米，约 220 颗
- 5V 电源 10A 以上，灯带两端供电（注入点），电源地与 ESP32 地相连
- 可选：电平转换芯片（74AHCT125 等）或短数据线，提高数据线稳定性

## 物料

`fetch-materials.sh` 下载并校验，结果写入 `materials.lock`：

- jellyfin-ambilight 源码（固定提交）与 v2.5.0 发布包（对照用）
- Litefin 源码（固定提交）与 `Litefin-2.0.20-webOS-Normal.ipk`（对照用；电视上实际版本待确认）
- WLED 16.0.1 经典 ESP32 固件（备用；刷机优先用官方网页刷写工具 install.wled.me）
- `mcr.microsoft.com/dotnet/sdk:10.0` 镜像（编译插件用）

## 实施步骤（未执行）

### 阶段 1：插件适配 Jellyfin 12.4（助手，NAS 上隔离进行）

1. 在 .NET 10 SDK 容器里把插件依赖改为 `Jellyfin.Controller/Model 12.4.0`、目标框架 `net10.0`，修复编译错误，`targetAbi` 设为 12.4。
2. 起一个独立的 Jellyfin 12.4 测试容器：独立配置目录，只读挂载一个短测试视频，只监听 `127.0.0.1`，不开放端口给局域网。开端口前按 `docs/operations/服务与端口清单.md` 核对。
3. 装入插件，提取测试视频颜色，用一个模拟 WLED 的 UDP 接收程序检查颜色流和时间轴。
4. 结果写入运维记录，测试实例删除；**是否装到正式 Jellyfin 由用户决定**。

### 阶段 2：灯带硬件（用户安装，助手配置）

刷 WLED、接入局域网（建议固定 IP）、设置灯珠数量与起点方向、确认实时 UDP 接收已开启。

### 阶段 3：联调

正式 Jellyfin 装插件（或改为独立容器方案）、为电视上的 Litefin 设备建立映射、提取片库颜色（低优先级，避开备份时间窗口）、调延迟补偿；同步不够准再考虑 Litefin 插件。

## 回滚

- 阶段 1 只有测试容器与本地文件，删除即可，不影响正式服务。
- 正式 Jellyfin 装插件前先备份 `/config/plugins` 与插件配置；回滚为删除插件目录并重启 Jellyfin。颜色数据目录可直接删除。
