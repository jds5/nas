#!/usr/bin/env bash
# 用途：下载电视灯带（WLED + Jellyfin 预计算取色）方案所需物料：插件与 Litefin 源码、发布包、WLED 固件、.NET 构建镜像，并记录 SHA-256。
# 依赖：bash、curl、git、python3、docker；GitHub 与 mcr.microsoft.com 可达。
# 运行：bash tv-ambilight/fetch-materials.sh [目标目录]，默认 /home/yao/tv-ambilight-materials。可重复执行。
# 影响：只写目标目录（不在 Git 仓库内）与 tv-ambilight/materials.lock，并拉取一个 .NET SDK 镜像；不启动容器，不碰正式 Jellyfin，不需要 sudo。
set -euo pipefail

DEST="${1:-/home/yao/tv-ambilight-materials}"
LOCK="$(cd "$(dirname "$0")" && pwd)/materials.lock"
mkdir -p "$DEST"/{src,release,firmware}
: > "$LOCK.tmp"

# GitHub 发布资产：仓库 标签 资产名 子目录（SHA-256 与 GitHub API 的 digest 比对）
ASSETS=(
  "gabrielprat/jellyfin-ambilight v2.5.0 jellyfin-plugin-ambilight_2.5.0.zip release"
  "MoazSalem/litefin v2.0.20 Litefin-2.0.20-webOS-Normal.ipk release"
  "wled/WLED v16.0.1 WLED_16.0.1_ESP32.bin firmware"
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

# 源码：插件需针对 Jellyfin 12.4 重新编译；Litefin 用于后续可选的高频进度插件
# 按默认分支当前提交下载源码包（git 克隆在 NAS 到 GitHub 的慢速链路上易断，压缩包可由 curl 重试）
for repo in gabrielprat/jellyfin-ambilight MoazSalem/litefin; do
  sha=$(curl -fsS --max-time 30 "https://api.github.com/repos/$repo/commits/HEAD" | python3 -c 'import sys,json;print(json.load(sys.stdin)["sha"])')
  out="$DEST/src/${repo#*/}-$sha.tar.gz"
  if [[ ! -f "$out" ]] || ! gzip -t "$out" 2>/dev/null; then
    echo "fetch src/${repo#*/}@${sha:0:12}"
    curl -fsSL --retry 5 --retry-all-errors --max-time 1800 -o "$out.part" "https://codeload.github.com/$repo/tar.gz/$sha"
    gzip -t "$out.part" && mv "$out.part" "$out"
  fi
  echo "$(sha256sum "$out" | cut -d' ' -f1)  src/$(basename "$out")  $repo@$sha  codeload" | tee -a "$LOCK.tmp"
done

# 构建镜像：Jellyfin 12.4 为 .NET 10（net10.0），插件需用 .NET 10 SDK 编译
docker pull -q mcr.microsoft.com/dotnet/sdk:10.0 >/dev/null
echo "image $(docker image inspect -f '{{index .RepoDigests 0}}' mcr.microsoft.com/dotnet/sdk:10.0)" | tee -a "$LOCK.tmp"

{ echo "# 电视灯带物料清单，生成于 $(date '+%F %T %Z')，目录 $DEST"; cat "$LOCK.tmp"; } > "$LOCK"
rm -f "$LOCK.tmp"
echo "完成：$LOCK"
