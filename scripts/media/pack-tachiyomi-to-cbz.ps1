<#
.SYNOPSIS
    把 Tachiyomi 下载的散图漫画批量打包为 CBZ，供 Kavita/Komga (NAS) 使用。

.DESCRIPTION
    遍历源目录 <Src>\<Source>\<Series>\<Chapter>：
    - 章节是文件夹（散图）→ 按文件名排序，打包为 <Chapter>.cbz（ZIP Store 模式，不二次压缩）
    - 章节已是 .cbz/.zip → 直接复制
    - 输出到 <Dst>，保留 <Source>\<Series>\ 两级结构
    - 原始文件保留不动，由用户检阅后自行删除
    - 幂等：输出已存在且非空则跳过；脚本可中途打断后再次运行
    - 跳过 .nomedia 等非图片文件

.PARAMETER Src
    源目录。默认 D:\wsa\Tachiyomi\downloads

.PARAMETER Dst
    输出目录。默认 D:\manga-processed

.PARAMETER DryRun
    只列出会做什么，不实际写盘。建议第一次先 -DryRun 看一下规模。

.EXAMPLE
    # 试运行（不写盘）
    .\pack-tachiyomi-to-cbz.ps1 -DryRun

.EXAMPLE
    # 正式跑
    .\pack-tachiyomi-to-cbz.ps1

.NOTES
    日期：2026-05-09
    场景：NAS 装机前清理 Tachiyomi 漫画库，整理为 Kavita 可读格式
    输入  ~207 GB / 13k 章节，预计耗时 1-2 小时（HDD），SSD 可压到 30-60 分钟
#>

[CmdletBinding()]
param(
    [string]$Src = 'D:\wsa\Tachiyomi\downloads',
    [string]$Dst = 'D:\manga-processed',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$imageExts = @('.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp', '.avif')
$cbzExts   = @('.cbz', '.zip')

# ---- 准备输出目录 + 日志 ----
if (-not (Test-Path -LiteralPath $Src)) {
    Write-Error "源目录不存在: $Src"
    exit 1
}
if (-not (Test-Path -LiteralPath $Dst)) {
    New-Item -ItemType Directory -Path $Dst -Force | Out-Null
}
$logPath = Join-Path $Dst '_processing-log.txt'
"=== 开始: $(Get-Date) | Src=$Src | Dst=$Dst | DryRun=$DryRun ===" |
    Out-File -LiteralPath $logPath -Encoding UTF8

function Log {
    param([string]$Msg, [string]$Level = 'INFO')
    $line = "[{0}] [{1}] {2}" -f (Get-Date -Format 'HH:mm:ss'), $Level, $Msg
    Write-Host $line
    Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
}

# ---- 统计 ----
$stats = @{
    Packed          = 0   # 文件夹→cbz
    Copied          = 0   # 已 cbz 直接复制
    SkippedExisting = 0   # 输出已存在
    SkippedEmpty    = 0   # 章节文件夹无图片
    SkippedUnknown  = 0   # 既不是文件夹也不是 cbz
    Errors          = 0
    StartTime       = Get-Date
}

# ---- 主循环 ----
$sources = Get-ChildItem -LiteralPath $Src -Directory
Log "顶层 Source 目录: $($sources.Count) 个"

foreach ($srcDir in $sources) {
    Log "=== Source: $($srcDir.Name) ==="
    $seriesList = Get-ChildItem -LiteralPath $srcDir.FullName -Directory -ErrorAction SilentlyContinue
    Log "  含 $($seriesList.Count) 部漫画"

    foreach ($seriesDir in $seriesList) {
        $rel = Join-Path $srcDir.Name $seriesDir.Name
        $dstSeriesDir = Join-Path $Dst $rel

        if (-not $DryRun -and -not (Test-Path -LiteralPath $dstSeriesDir)) {
            New-Item -ItemType Directory -Path $dstSeriesDir -Force | Out-Null
        }

        $chapters = Get-ChildItem -LiteralPath $seriesDir.FullName -ErrorAction SilentlyContinue
        if (-not $chapters -or $chapters.Count -eq 0) {
            Log "  [空系列] $rel" 'WARN'
            continue
        }

        $i = 0
        foreach ($ch in $chapters) {
            $i++
            try {
                if ($ch.PSIsContainer) {
                    # ---- 散图章节 → 打包 CBZ ----
                    $cbzPath = Join-Path $dstSeriesDir ($ch.Name + '.cbz')

                    if ((Test-Path -LiteralPath $cbzPath) -and ((Get-Item -LiteralPath $cbzPath).Length -gt 0)) {
                        $stats.SkippedExisting++
                        continue
                    }

                    $images = Get-ChildItem -LiteralPath $ch.FullName -File -ErrorAction SilentlyContinue |
                              Where-Object { $imageExts -contains $_.Extension.ToLower() } |
                              Sort-Object Name

                    if (-not $images -or $images.Count -eq 0) {
                        Log "  [空章节] $rel\$($ch.Name)" 'WARN'
                        $stats.SkippedEmpty++
                        continue
                    }

                    if ($DryRun) {
                        Log "  [DRY-PACK] $rel\$($ch.Name) ← $($images.Count) 张"
                    } else {
                        # 临时文件 → 写完原子重命名，避免半途崩了留下损坏 cbz
                        $tmpPath = $cbzPath + '.tmp'
                        if (Test-Path -LiteralPath $tmpPath) { Remove-Item -LiteralPath $tmpPath -Force }

                        $zip = [System.IO.Compression.ZipFile]::Open(
                            $tmpPath,
                            [System.IO.Compression.ZipArchiveMode]::Create
                        )
                        try {
                            foreach ($img in $images) {
                                # entry 名 = 文件名（不带子目录），保证 Kavita/Komga 在根目录读到
                                [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                                    $zip,
                                    $img.FullName,
                                    $img.Name,
                                    [System.IO.Compression.CompressionLevel]::NoCompression
                                ) | Out-Null
                            }
                        } finally {
                            $zip.Dispose()
                        }
                        Move-Item -LiteralPath $tmpPath -Destination $cbzPath
                    }
                    $stats.Packed++

                } elseif ($cbzExts -contains $ch.Extension.ToLower()) {
                    # ---- 已经是 cbz/zip → 直接复制 ----
                    $cbzDst = Join-Path $dstSeriesDir $ch.Name

                    if ((Test-Path -LiteralPath $cbzDst) -and
                        ((Get-Item -LiteralPath $cbzDst).Length -eq $ch.Length)) {
                        $stats.SkippedExisting++
                        continue
                    }

                    if ($DryRun) {
                        Log "  [DRY-COPY] $rel\$($ch.Name)"
                    } else {
                        Copy-Item -LiteralPath $ch.FullName -Destination $cbzDst -Force
                    }
                    $stats.Copied++

                } else {
                    Log "  [跳过-未知类型] $rel\$($ch.Name)" 'WARN'
                    $stats.SkippedUnknown++
                }
            } catch {
                Log "  [ERROR] $rel\$($ch.Name): $_" 'ERROR'
                $stats.Errors++
            }

            if ($i % 100 -eq 0) {
                Log "  ...$rel 进度 $i/$($chapters.Count)"
            }
        }
    }
}

# ---- 汇总 ----
$elapsed = (Get-Date) - $stats.StartTime
Log "=========================================="
Log "完成 | 用时 $($elapsed.ToString('hh\:mm\:ss'))"
Log "新打包（散图→cbz）  : $($stats.Packed)"
Log "复制（已是 cbz）    : $($stats.Copied)"
Log "跳过（输出已存在）  : $($stats.SkippedExisting)"
Log "跳过（章节无图片）  : $($stats.SkippedEmpty)"
Log "跳过（未知类型）    : $($stats.SkippedUnknown)"
Log "错误                : $($stats.Errors)"
Log "=========================================="
if ($DryRun) {
    Log "[DRY-RUN] 未写任何文件，去掉 -DryRun 后正式跑"
}
