# 媒体整理脚本

[pack-tachiyomi-to-cbz.ps1](pack-tachiyomi-to-cbz.ps1) 从 Homeland 原样迁入，用于把 Tachiyomi 的散图章节打包为 CBZ，供 Kavita / Komga 读取。

## 依赖与用法

需要 PowerShell 及 .NET 的 `System.IO.Compression`、`System.IO.Compression.FileSystem` 程序集。原脚本面向 Windows 路径；本次环境没有 PowerShell，未执行验证。

在仓库根目录运行，显式指定输入和输出：

```powershell
# 预览待打包和复制项目
.\scripts\media\pack-tachiyomi-to-cbz.ps1 -Src 'D:\wsa\Tachiyomi\downloads' -Dst 'D:\manga-processed' -DryRun

# 确认预览后执行
.\scripts\media\pack-tachiyomi-to-cbz.ps1 -Src 'D:\wsa\Tachiyomi\downloads' -Dst 'D:\manga-processed'
```

输入结构为 `<Src>/<来源>/<系列>/<章节目录或压缩包>`；输出保留来源和系列两级目录。使用独立输出目录，源文件保留；完成后抽查压缩包和阅读器扫描结果，再自行决定原文件的处置。

## 现有行为与限制

- `-DryRun` 不生成 CBZ 或复制漫画，但仍创建输出根目录并写入 `_processing-log.txt`；原脚本“未写任何文件”的提示不准确。
- 正式执行可能覆盖目标中同名、大小不同的已有 CBZ / ZIP；每次启动会覆盖原处理日志。
- 散图打包按文件名排序；已有非空 CBZ 会跳过，复制的压缩包按大小判断是否跳过，不验证内容完整性。
- 脚本会记录章节错误并继续；不能只靠进程退出码判定全部成功，应检查日志中的错误数量。

本次只迁移脚本，不修改其行为，不处理真实媒体数据。
