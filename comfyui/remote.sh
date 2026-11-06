#!/usr/bin/env bash
# 用途：从 NAS 远程启动 / 停止 / 查看内网 GPU 主机（yao-home，192.168.50.20）上的 ComfyUI。
# 依赖：NAS 上已授权的 SSH 密钥（yaoch@192.168.50.20）；GPU 主机上 C:\llm\comfyui\start-comfyui.cmd；iconv、base64。
# 运行：bash comfyui/remote.sh start|stop|status
#   start：用户需已登录 Windows 桌面。建一次性计划任务在用户桌面会话里运行启动脚本（窗口出现在用户屏幕上），
#          等待 127.0.0.1:8188 监听后删除任务。直接在 SSH 会话里启动会随断开被结束，所以不这样做。
#   stop： 结束监听 8188 的进程及其所在的启动窗口。
#   status：8188 是否监听、ComfyUI 版本、显存占用、Qwen3.8 容器状态。
# 影响：只启动 / 停止 ComfyUI 进程，不改配置、模型和防火墙；不启动或停止 Qwen3.8。
set -euo pipefail
HOST=yaoch@192.168.50.20
TASK=ComfyUI-start-once

run() { timeout "${2:-60}" ssh -o BatchMode=yes -o ConnectTimeout=8 "$HOST" "$1" 2>&1 | iconv -f GBK -t UTF-8 -c; }
# PowerShell 经 cmd 传参容易被转义打乱，统一用 -EncodedCommand（UTF-16LE base64）。
ps() { run "powershell -NoProfile -NonInteractive -EncodedCommand $(printf '%s' "\$ProgressPreference='SilentlyContinue'; $1" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)" "${2:-60}"; }
listening() { ps '(Get-NetTCPConnection -LocalPort 8188 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess' | grep -o '^[0-9]*' | head -1 || true; }

case "${1:-status}" in
  start)
    if [ -n "$(listening)" ]; then echo "已在运行"; exit 0; fi
    run "query user" | grep -qi "yaoch" || { echo "yaoch 未登录 Windows 桌面，无法在桌面会话启动" >&2; exit 1; }
    run "schtasks /Create /TN $TASK /TR \"C:\\llm\\comfyui\\start-comfyui.cmd\" /SC ONCE /ST 23:59 /IT /RL LIMITED /F >nul && schtasks /Run /TN $TASK >nul" >/dev/null
    for _ in $(seq 1 36); do sleep 5; [ -n "$(listening)" ] && break; done
    run "schtasks /Delete /TN $TASK /F >nul" >/dev/null || true
    [ -n "$(listening)" ] || { echo "120 秒内未开始监听 8188，请在 GPU 主机上查看启动窗口" >&2; exit 1; }
    echo "已启动：在 GPU 主机上打开 http://127.0.0.1:8188"
    ;;
  stop)
    pid=$(listening)
    [ -n "$pid" ] || { echo "未在运行"; exit 0; }
    # 连同父进程（启动窗口 cmd）一起结束，避免留下“按任意键继续”的窗口。
    ps "\$p = Get-CimInstance Win32_Process -Filter 'ProcessId=$pid'; \$parent = Get-CimInstance Win32_Process -Filter \"ProcessId=\$(\$p.ParentProcessId)\"; if (\$parent.Name -eq 'cmd.exe') { taskkill /PID \$parent.ProcessId /T /F | Out-Null } else { taskkill /PID $pid /T /F | Out-Null }" >/dev/null
    sleep 3
    [ -z "$(listening)" ] && echo "已停止" || { echo "仍在监听 8188" >&2; exit 1; }
    ;;
  status)
    pid=$(listening)
    if [ -n "$pid" ]; then
      echo "ComfyUI：运行中（PID $pid），$(run 'curl -s http://127.0.0.1:8188/system_stats' | grep -o '"comfyui_version": "[^"]*"')"
    else
      echo "ComfyUI：未运行"
    fi
    echo "显存（已用, 总量）：$(run 'nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader' | tr -d '\r')"
    echo "Qwen3.8 容器：$(run 'docker ps --filter name=qwen38 --format "{{.Names}} {{.Status}}"' | tr -d '\r' | grep . || echo 未运行)"
    ;;
  *) echo "用法：$0 start|stop|status" >&2; exit 2 ;;
esac
