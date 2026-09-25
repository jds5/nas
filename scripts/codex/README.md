# tmux 中的 Codex 会话恢复

手机 App 只向已经运行 Codex 的窗格发送对话。如果某个 tmux 窗格中的 Codex 退出，窗格回到 shell，先从 NAS 上另一个仍在运行的 Codex 会话使用 `!` 恢复指定会话，再在 App 会话列表中重新选择该窗格。旧的“接回上次会话”绑定的是已经退出的执行器，不适用于这次恢复。

本工具依赖 NAS 本机 Python 3.9+、tmux、Codex CLI、Linux `/proc` 和本仓库的 Android 桥接器。运行账户须是原 Codex 会话记录和目标 tmux 的所有者。先从已保存的会话记录核对目标 UUID；不能仅凭项目目录选择“最近一次”。

在另一正常工作的 Codex 聊天框中输入（将占位符换成目标会话的精确 UUID）：

```text
!python3 /home/yao/code/nas/scripts/codex/resume_tmux.py home <会话UUID> --check
!python3 /home/yao/code/nas/scripts/codex/resume_tmux.py home <会话UUID>
```

`--check` 只核对目标，不发送按键。正式运行前会确认：保存记录的 ID 和原目录一致、tmux 中仅有一个目标窗格、窗格位于原目录且停在空的 bash 提示符、该会话没有被其他 Codex 进程占用。通过后只发送一次 `codex resume <会话UUID>`。若识别到本机 Codex CLI 的更新菜单，发送一次 Esc 跳过更新；最终通过桥接器确认运行中的会话 ID。无法确认时停止，不自动重复发送；此时先查看目标 tmux 窗格的实际画面，再决定下一步。若同一会话已在目标窗格运行，命令会直接报告“无需重复恢复”。

成功后，在手机 App 返回会话列表，重新选择 `home`。本工具不修改服务、配置或 Codex 会话记录，也不向恢复后的会话发送提问；它只改变目标窗格的前台程序。若所有 Codex 会话均已退出，无法通过 App 的 `!` 功能启动本工具，需要先通过普通 SSH 终端运行。无需保留工具时，可从 Git 中撤销此脚本；不要为撤销脚本而中断正在使用的 Codex 会话。
