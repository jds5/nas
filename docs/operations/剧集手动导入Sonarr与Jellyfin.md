# 网盘剧集手动导入 Sonarr 与 Jellyfin

适用于通过 Gopeed、OpenList 或其他独立下载器取得的剧集，将其纳入 Sonarr 管理，并由 Jellyfin 正确识别和播放。独立下载器的任务完成不等于 Sonarr 已入库；只把文件放进 Jellyfin 目录也不等于 Sonarr 已关联季集。

流程：**确认完成与身份 → 明确季集映射 → Sonarr 复制导入与命名 → 内容校验 → Jellyfin 元数据与扫描 → 验收记录**。

本手册维护方法，不代替当前设备和配置。既有实例：[《仁医》两季](2026-10-07-仁医两季导入Jellyfin.md)、[《母亲》《我的恐怖妻子》](2026-10-07-母亲与我的恐怖妻子导入.md)。

## 1. 现场核对与安全边界

先阅读 README、网络安全架构、相关历史记录；核对主机、运行容器、挂载、目录权限、源/目标磁盘及剩余空间。需要修改服务或端口时按[服务与端口清单](服务与端口清单.md)执行专项检查，不能为方便导入直接开放管理口。

2026-10-07 的映射仅作定位参考：

| 用途 | 宿主机 | 容器内 |
| --- | --- | --- |
| Gopeed 下载 | `/opt/nas/cache/gopeed/downloads` | Gopeed `/downloads`；Sonarr `/cache/gopeed/downloads` |
| 电视剧媒体 | `/mnt/pool-main/media/tv` | Sonarr、Jellyfin 均为 `/media/tv` |
| Sonarr 配置 | `/opt/nas/sonarr/config` | Sonarr `/config` |
| Jellyfin 配置 | `/opt/nas/jellyfin/config` | Jellyfin `/config` |

- API 操作用本机/内网现有入口；凭据从受控配置在内存中读取，不输出到终端、命令行参数、仓库或日志。
- 将必要快照、清单和一次性脚本放入权限 700 的私有维护目录，文件默认 600。快照不等于完整媒体备份。
- 只操作本次确认的源文件和目标系列。为旁边的下载、游戏压缩包等记录大小/mtime/inode 基线；不解压、移动或清理无关文件。
- 默认复制并保留原件。删除源文件、旧版本、重复文件或覆盖目标必须另行确认目标、影响与备份并获得授权；不能把“导入”自动扩大为“清空下载缓存”。

## 2. 确认下载完成与剧集身份

1. 在下载器 API/界面确认目标任务为完成状态；核对实际落盘路径、下载字节数与文件大小。预分配大文件、文件存在或下载速度为零都不能单独证明完成。
2. 排除下载中/暂停/失败任务、临时文件、符号链接与路径越界；记录源路径、大小、mtime、inode、任务 ID/状态。
3. 对每个视频运行 ffprobe，确认容器可读、存在视频流、分辨率/音轨/时长合理；按剧集类型判断时长，不能用固定时长阈值排除短剧。
4. 核对剧名、年份、国家/语言、TVDB/TMDB/IMDb ID，避免同名翻拍。季集总数与音轨标签只能作为证据之一，含糊时继续核对或询问用户，不能盲猜。
5. 从 Sonarr 实时获取现有系列和 episode 列表，以 TVDB ID 等稳定标识定位。用户说“已追剧”也要复核：已添加与 `monitored=true` 不等价。
6. 保存现有系列配置、监控状态、质量模板、路径及 episodeFile 清单。保持已有监控设置，不自动触发搜索或改变全库策略。若确实缺条目，确认正确版本后按导入范围补建，禁用缺集搜索；仅为整理已有文件时可设未监控，并在记录中明确。

## 3. 制定导入映射与回滚基线

逐文件建立清单：源路径、Sonarr 容器路径、seriesId、seasonNumber、episodeNumber、episodeId、质量、语言、大小、SHA-256。特殊篇、多集合并文件、拆分文件、绝对集数/播出顺序必须单独映射，不能强套一文件一集。

- 不硬编码历史 seriesId/episodeId；新任务实时查询。
- 根目录应是相应的 `/media/tv` 或 `/media/anime`。错误分类只调整目标系列，先核对已有文件、备份配置；不要直接移动整个库。
- 对照 Sonarr 与磁盘检查目标是否已存在媒体，存在时比较内容和质量，避免手动导入替换已有版本。
- 质量先看 Sonarr 预览，再对照 ffprobe。1080p 文件名不保证视频为 1080p；WEB-DL、WEBRip 等来源名称依据发行命名/已有元数据，分辨率探测不能证明发行来源。
- 确认源和目标所在文件系统；跨 SSD/媒体盘不能硬链接。预留完整复制空间，避免导入时占满系统盘。

## 4. 通过 Sonarr 导入，不直接改数据库

使用 Sonarr 的手动导入界面，或当前版本的 `/api/v3/manualimport` 预览与 `/api/v3/command`。本机 4.0.20.3014 本次预览采用 `folder`、`filterExistingFiles=false`；带 `seriesId` 时本次实际转为扫描系列媒体目录，因目录尚未创建返回 500；下载目录预览不要混用该参数。不能依赖未经当前版本验证的查询参数。

提交 `ManualImport` 时显式指定 `importMode: copy`，并逐文件填写 `path`、`seriesId`、`episodeIds`、`quality`、`languages` 等当前 API 支持的字段。使用 **Sonarr 容器路径**，不是宿主机路径。纯数字、缺季号或歧义文件名必须显式映射；不得把 Unknown Series 的预览结果直接批量接受。

- 异步 command 返回 ID 只表示任务已接收；轮询至完成，失败时先读脱敏结果，不盲目重复提交。
- 检查每集 `hasFile`、`episodeFileId`、episodeFile 目标路径、质量、大小、季集编号，任务完成不替代文件核对。
- 由 Sonarr 按既有命名模板生成 `Season 01/剧名 - S01E01 - …` 等目录/文件名；不要先自己重命名再绕过 Sonarr 登记。
- 质量确需修正时，通过 Sonarr episodeFile API 更新，再使用 `RenameFiles`；之后更新校验清单和同名侧车文件路径。
- 对复制后的每个目标计算 SHA-256，与源清单一致后再进入下一步。源文件的大小和 mtime 应保持不变。未转码就不应改变视频字节。

## 5. Jellyfin 识别与元数据

先读取当前电视剧库路径、语言、元数据提供器、NFO 读写设置，不照抄历史“刮削器关闭”等结论，不为两部剧修改整个库。

需要明确本地身份时，在目标系列目录写 `tvshow.nfo`，每集使用与视频同名的 `.nfo`：

- 剧级：显示标题、原名、年份、提供器 `uniqueid`（TVDB/TMDB/IMDb）、首播日期及可用剧情。
- 集级：集标题、剧标题、季/集编号、播出日期及可用剧情；集 ID 只能写对应单集 ID，不能把系列 ID 写成单集 ID。
- 中文标题采用已确认译名；不要为填满字段编造中文剧情。必要时仅锁定 Name，避免全条目锁定阻碍其他元数据更新。
- 已有 NFO/图片先备份，不覆盖未知内容；有 Sonarr 缓存海报时可复制到目标目录。NFO 是 XML，使用 XML 库写入和转义。
- 本地 NFO 会优先于远程元数据，因此生成内容必须准确。格式见 [Jellyfin 官方 NFO 文档](https://jellyfin.org/docs/general/server/metadata/nfo/)。

系列已被识别时优先刷新对应条目；尚未发现时刷新对应电视剧库。等待扫描结束，再检查真实结果。Sonarr 重命名后，单纯刷新元数据可能仍保留旧媒体路径；应通过 Jellyfin 的媒体变更通知（`POST /Library/Media/Updated`，传容器内目录路径和 `Modified`）触发目录检查，再核对新路径，必要时执行媒体库扫描。若初次扫描仍显示目录英文名，先核对 NFO 是否读取；必要时保存该剧元数据快照，通过 Jellyfin API 仅修正显示名和 Name 锁定，保留提供器 ID 及其他字段。不直接修改 Jellyfin 数据库。

## 6. 验收、原件与交付

| 检查 | 完成证据 |
| --- | --- |
| Sonarr | 正确系列/年份/ID、季集映射、文件数与目标路径；监控状态符合决定，无意外搜索 |
| 文件内容 | 全部目标存在、大小与 SHA-256 一致；源文件保持原状或按单独授权完成迁移 |
| Jellyfin | 正确剧名、年份、ID、季集数，无同路径重复系列或混入翻拍版本 |
| 可读性 | Jellyfin 返回的每条视频路径实际存在；每部/季代表集的 HTTP Range 为 206，片段与磁盘一致 |
| 播放边界 | 片段读取不等于完整播放、字幕/音轨体验或全片解码；未执行的项目明确记录 |
| 无关内容 | 其他下载文件基线不变，无服务/端口/全库设置意外变更 |

如果用户明确要求移动：先复制导入、逐个完整校验，再按已授权清单删除源文件和空目录；Gopeed 完成记录可保留，但旧路径失效需要说明。没有清理授权时保留原件，并告知占用空间；缓存中的原件不自动获得备份保护。

回滚优先撤销本次索引/配置及新增文件，不全库恢复或删除目录。源保留时可以恢复导入前系列配置；新建系列可经 Sonarr 删除条目且选择保留文件，再根据精确清单处理副本。此类删除执行前仍确认授权。已授权移动的任务应先复制回原路径并校验，之后再考虑撤销媒体库副本。刷新两套工具索引，并记录操作结果。

在 `docs/operations/` 新增日期记录，包含目标、实际版本/路径、命令结果、验证、私有备份位置、回滚步骤及遗留；更新 README 导航。完成差异与链接检查后，按 AGENTS.md 提交并推送。

## API 参考

- [Sonarr ManualImportCommand](https://github.com/Sonarr/Sonarr/blob/develop/src/NzbDrone.Core/MediaFiles/EpisodeImport/Manual/ManualImportCommand.cs)
- [Sonarr ManualImportFile](https://github.com/Sonarr/Sonarr/blob/develop/src/NzbDrone.Core/MediaFiles/EpisodeImport/Manual/ManualImportFile.cs)

上游 develop 字段可能变化，应以现场版本、预览结果和可用 API 定义验证，不直接复制旧一次性脚本执行。
