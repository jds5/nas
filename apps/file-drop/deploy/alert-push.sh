#!/usr/bin/env bash
# 用途：把 file-drop 应用写下的“新来源 IP”事件推送到 Uptime Kuma，并作为该应用的存活心跳。
#   有新事件：推送 down（消息含 IP、国家、通道、动作、邮箱），Kuma 发出告警；处理完的事件文件删除。
#   无新事件且容器 healthy：推送 up（上一次 down 之后会收到一条“恢复”通知，属预期）。
#   容器不健康：推送 down。
# 依赖：bash、curl、jq、docker；/opt/nas/scripts/uptime-kuma-urls.sh 中的 UPTIME_KUMA_FILE_DROP（Kuma Push 地址，不入库）。
# 运行：root crontab 每分钟 `* * * * * /opt/nas/scripts/file-drop-alert-push.sh`。
# 影响：只读取并删除 /mnt/pool-main/drop/alerts/*.json，向内网 Kuma 发 HTTP 请求；不改应用数据。
set -euo pipefail
source /opt/nas/scripts/uptime-kuma-urls.sh 2>/dev/null || true
URL="${UPTIME_KUMA_FILE_DROP:-}"
[ -n "$URL" ] || { echo "UPTIME_KUMA_FILE_DROP 未配置" >&2; exit 1; }
BASE="${URL%%\?*}"
DIR=/mnt/pool-main/drop/alerts
exec 9>/run/file-drop-alert-push.lock
flock -n 9 || exit 0

push() { curl -fsS -m 10 -o /dev/null -G "$BASE" --data-urlencode "status=$1" --data-urlencode "msg=$2" --data-urlencode "ping="; }

shopt -s nullglob
files=("$DIR"/*.json)
if (( ${#files[@]} )); then
  msgs=()
  for f in "${files[@]:0:10}"; do
    msgs+=("$(jq -r '"新IP \(.ip) \(.country // "") \(.via)/\(.action) \(.email // "")"' "$f" 2>/dev/null | tr -d '\000-\037' | cut -c1-200 || echo "无法解析 $(basename "$f")")")
  done
  (( ${#files[@]} > 10 )) && msgs+=("另有 $(( ${#files[@]} - 10 )) 条")
  msg=$(printf '%s | ' "${msgs[@]}"); msg=${msg% | }
  push down "$msg"
  rm -f -- "${files[@]}"
  exit 0
fi

health=$(docker inspect -f '{{.State.Health.Status}}' nas_file_drop 2>/dev/null || echo missing)
if [ "$health" = healthy ]; then push up OK; else push down "file-drop 容器状态 $health"; fi
