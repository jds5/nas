#!/usr/bin/env bash
# 用途：下载 Surface Pro 5 安装 macOS 26 Tahoe 所需物料（OpenCore、kext、工具、Apple 恢复镜像），并校验/记录 SHA-256。
# 依赖：bash、curl、git、python3、unzip（仅列目录用）；GitHub 与 Apple 恢复服务器可达。
# 运行：bash surface-macos/fetch-materials.sh [目标目录]，默认 /home/yao/surface-macos-materials。可重复执行，已下载且校验通过的文件跳过。
# 影响：只写目标目录（不在 Git 仓库内）与 surface-macos/materials.lock；不改系统配置，不需要 sudo。
set -euo pipefail

DEST="${1:-/home/yao/surface-macos-materials}"
LOCK="$(cd "$(dirname "$0")" && pwd)/materials.lock"
mkdir -p "$DEST"/{opencore,kexts,tools,oclp,recovery,reference}
: > "$LOCK.tmp"

# GitHub 发布资产：仓库 标签 资产名 子目录
# 资产 SHA-256 取自 GitHub API 的 digest 字段（发布方上传时计算），下载后比对
ASSETS=(
  "acidanthera/OpenCorePkg 1.0.8 OpenCore-1.0.8-RELEASE.zip opencore"
  "acidanthera/OpenCorePkg 1.0.8 OpenCore-1.0.8-DEBUG.zip opencore"
  "acidanthera/Lilu 1.7.2 Lilu-1.7.2-RELEASE.zip kexts"
  "acidanthera/VirtualSMC 1.3.8 VirtualSMC-1.3.8-RELEASE.zip kexts"
  "acidanthera/WhateverGreen 1.7.1 WhateverGreen-1.7.1-RELEASE.zip kexts"
  "acidanthera/AppleALC 1.9.8 AppleALC-1.9.8-RELEASE.zip kexts"
  "acidanthera/NVMeFix 1.1.3 NVMeFix-1.1.3-RELEASE.zip kexts"
  "acidanthera/RestrictEvents 1.1.6 RestrictEvents-1.1.6-RELEASE.zip kexts"
  "USBToolBox/kext 1.2.0 USBToolBox-1.2.0-RELEASE.zip kexts"
  "Xiashangning/BigSurface v6.5 BigSurface.zip kexts"
  "Xiashangning/IPTSDaemon v1.4 IPTSDaemon.zip tools"
  "jwise/HoRNDIS rel9.2 HoRNDIS-9.2.pkg kexts"
  "USBToolBox/tool 0.2 Windows.zip tools"
  "dortania/OpenCore-Legacy-Patcher 3.0.0-rc.5 OpenCore-Patcher.pkg oclp"
)

for entry in "${ASSETS[@]}"; do
  read -r repo tag name sub <<<"$entry"
  meta=$(curl -fsS --max-time 30 "https://api.github.com/repos/$repo/releases/tags/$tag")
  read -r url digest < <(python3 -c '
import sys,json
d=json.load(sys.stdin)
for a in d["assets"]:
    if a["name"]==sys.argv[1]:
        print(a["browser_download_url"], (a.get("digest") or "").removeprefix("sha256:") or "-"); break
else: sys.exit("asset not found: "+sys.argv[1])' "$name" <<<"$meta")
  out="$DEST/$sub/$name"
  if [[ -f "$out" && "$digest" != "-" ]] && echo "$digest  $out" | sha256sum -c --quiet 2>/dev/null; then
    echo "skip  $sub/$name"
  else
    echo "fetch $sub/$name"
    # NAS 到 GitHub 链路慢且易断：遇错重试并断点续传
    for i in 1 2 3 4 5 6; do
      curl -fsSL --retry 5 --retry-all-errors -C - --max-time 3600 -o "$out.part" "$url" && break
      echo "retry $i: $name" >&2; sleep 5
    done
    mv "$out.part" "$out"
  fi
  actual=$(sha256sum "$out" | cut -d' ' -f1)
  if [[ "$digest" != "-" && "$digest" != "$actual" ]]; then
    echo "SHA-256 不匹配：$name（GitHub $digest，实际 $actual）" >&2; exit 1
  fi
  verified=$([[ "$digest" == "-" ]] && echo "github-no-digest" || echo "github-digest-ok")
  echo "$actual  $sub/$name  $repo@$tag  $verified" >> "$LOCK.tmp"
done

# 工具仓库（无正式发布）：按默认分支当前提交下载源码包（git 克隆在慢速链路上易断，压缩包可由 curl 重试）
for repo in corpnewt/SSDTTime corpnewt/ProperTree corpnewt/GenSMBIOS Alfie-sketch/OpenCore-EFI-Surface-Pro-5-2017-; do
  sub=tools; [[ "$repo" == Alfie-sketch/* ]] && sub=reference
  sha=$(curl -fsS --max-time 30 "https://api.github.com/repos/$repo/commits/HEAD" | python3 -c 'import sys,json;print(json.load(sys.stdin)["sha"])')
  out="$DEST/$sub/${repo#*/}-$sha.tar.gz"
  if [[ ! -f "$out" ]] || ! gzip -t "$out" 2>/dev/null; then
    echo "fetch $sub/${repo#*/}@${sha:0:12}"
    curl -fsSL --retry 5 --retry-all-errors --max-time 1800 -o "$out.part" "https://codeload.github.com/$repo/tar.gz/$sha"
    gzip -t "$out.part" && mv "$out.part" "$out"
  fi
  echo "$(sha256sum "$out" | cut -d' ' -f1)  $sub/$(basename "$out")  $repo@$sha  codeload" >> "$LOCK.tmp"
done

# Apple 恢复镜像：用 OpenCorePkg 1.0.8 自带 macrecovery.py，从 Apple 服务器下载 Tahoe 最新版并按 chunklist 校验
oczip="$DEST/opencore/OpenCore-1.0.8-RELEASE.zip"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -q "$oczip" 'Utilities/macrecovery/*' -d "$tmp"
# macrecovery 不支持续传，断流时校验失败：清空后整体重试
for i in 1 2 3 4 5 6; do
  rm -rf "$DEST/recovery/com.apple.recovery.boot"
  ( cd "$DEST/recovery" && python3 -I "$tmp/Utilities/macrecovery/macrecovery.py" \
      -b Mac-CFF7D910A743CAAF -m 00000000000000000 -os latest download ) && break
  [[ $i == 6 ]] && { echo "恢复镜像下载失败" >&2; exit 1; }
  echo "retry $i: recovery" >&2; sleep 10
done
for f in "$DEST"/recovery/com.apple.recovery.boot/*; do
  echo "$(sha256sum "$f" | cut -d' ' -f1)  recovery/com.apple.recovery.boot/$(basename "$f")  apple-macrecovery(chunklist-verified)" >> "$LOCK.tmp"
done

{ echo "# Surface Pro 5 macOS 物料清单，生成于 $(date '+%F %T %Z')，目录 $DEST"; cat "$LOCK.tmp"; } > "$LOCK"
rm -f "$LOCK.tmp"
echo "完成：$LOCK"
