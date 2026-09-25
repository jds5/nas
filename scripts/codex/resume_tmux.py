#!/usr/bin/env python3
"""Resume an exact saved Codex thread in one idle tmux session.

Dependencies: Python 3.9+, tmux, codex, Linux /proc, and the NAS Remote bridge.
Usage: python3 scripts/codex/resume_tmux.py home SESSION_UUID [--check]
Effect: without --check, types one `codex resume SESSION_UUID` command into the
verified empty shell prompt and skips only a recognized CLI update menu. It
never sends a prompt to Codex or retries input.
"""

import argparse
import importlib.util
import json
import os
import pwd
import re
import shlex
import shutil
import socket
import stat
import subprocess
import sys
import time
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / 'apps/android/core/src/main/resources/nas_remote_bridge.py'


class Refused(Exception):
    pass


def tmux(*args):
    result = subprocess.run(('tmux', *args), text=True, capture_output=True, timeout=5)
    if result.returncode:
        raise Refused('tmux 检查或操作失败，请在原终端核对')
    return result.stdout


def saved_thread(thread_id):
    files = list((Path.home() / '.codex/sessions').rglob('rollout-*' + thread_id + '.jsonl'))
    if len(files) != 1:
        raise Refused('无法唯一找到指定 Codex 会话记录')
    path = files[0]
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
        raise Refused('会话记录身份无效')
    with path.open('rb') as stream:
        meta = json.loads(stream.readline(65537))
    payload = meta.get('payload', {})
    cwd = payload.get('cwd')
    if meta.get('type') != 'session_meta' or payload.get('id') != thread_id or not isinstance(cwd, str):
        raise Refused('会话元数据与指定 ID 不符')
    if not Path(cwd).is_dir() or not Path(cwd).is_absolute():
        raise Refused('原项目目录不存在')
    return path, cwd


def pane_for(session):
    rows = []
    for line in tmux('list-panes', '-a', '-F', '#{session_name}\t#{pane_id}\t#{pane_pid}\t#{pid}\t#{pane_dead}\t#{pane_current_command}\t#{pane_current_path}\t#{cursor_x}\t#{cursor_y}').splitlines():
        fields = line.split('\t')
        if len(fields) == 9 and fields[0] == session:
            rows.append(fields)
    if len(rows) != 1:
        raise Refused('目标 tmux 会话不存在或不止一个窗格')
    _, ident, pane_pid, server_pid, dead, command, cwd, x, y = rows[0]
    if dead != '0' or not re.fullmatch(r'%[0-9]+', ident):
        raise Refused('目标窗格已退出或标识无效')
    return {'id': ident, 'pid': int(pane_pid), 'serverPid': int(server_pid),
            'command': command, 'cwd': cwd, 'x': int(x), 'y': int(y)}


def bridge_thread(pane):
    spec = importlib.util.spec_from_file_location('nas_remote_bridge', BRIDGE)
    bridge = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bridge)
    binding, stream, meta = bridge.locate(pane)
    stream.close()
    return meta['id'], binding


def idle_prompt(pane):
    if pane['command'] != 'bash':
        raise Refused('目标窗格不是空闲 bash；不会覆盖正在运行的程序')
    pid = pane['pid']
    children = (Path('/proc') / str(pid) / 'task' / str(pid) / 'children').read_text().strip()
    if children:
        raise Refused('目标 shell 仍有子进程，未发送命令')
    screen = tmux('capture-pane', '-p', '-t', pane['id']).splitlines()
    y = pane['y']
    if y >= len(screen):
        raise Refused('无法确认 shell 输入行')
    row = screen[y].rstrip()
    user = pwd.getpwuid(os.getuid()).pw_name
    host = socket.gethostname().split('.')[0]
    if not re.fullmatch(re.escape(user + '@' + host + ':') + r'[^\n]*\$', row):
        raise Refused('shell 提示符或现有输入无法确认')
    if not len(row) <= pane['x'] <= len(row) + 2:
        raise Refused('shell 光标不在空输入行末尾')


def thread_open_elsewhere(path):
    wanted = (path.stat().st_dev, path.stat().st_ino)
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            if (proc / 'comm').read_text().strip() != 'codex':
                continue
            for fd in (proc / 'fd').iterdir():
                info = fd.stat()
                if (info.st_dev, info.st_ino) == wanted:
                    return int(proc.name)
        except (OSError, PermissionError):
            continue
    return None


def update_menu(pane):
    screen = tmux('capture-pane', '-p', '-t', pane['id'])
    return ('Update available' in screen and '› 1. Update now' in screen
            and '  2. Skip' in screen and '  enter continue · esc skip' in screen)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tmux_session', help='tmux 会话名，例如 home')
    parser.add_argument('session_id', help='要继续的精确 Codex UUID')
    parser.add_argument('--check', action='store_true', help='只检查，不向 tmux 发送按键')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', args.tmux_session):
        raise Refused('tmux 会话名无效')
    try:
        thread_id = str(uuid.UUID(args.session_id))
    except ValueError as exc:
        raise Refused('Codex 会话 ID 无效') from exc
    path, cwd = saved_thread(thread_id)
    pane = pane_for(args.tmux_session)
    if pane['cwd'] != cwd:
        raise Refused('tmux 工作目录与原 Codex 会话目录不一致')
    if pane['command'] == 'codex':
        try:
            running_id, _ = bridge_thread(pane)
        except Exception as exc:
            raise Refused('目标已有 Codex，但无法确认其会话身份') from exc
        if running_id != thread_id:
            raise Refused('目标已运行另一个 Codex 会话')
        print('指定 Codex 会话已经在目标窗格运行；无需重复恢复')
        return
    idle_prompt(pane)
    owner = thread_open_elsewhere(path)
    if owner is not None:
        raise Refused('该 Codex 会话记录仍由进程 %d 使用' % owner)
    codex = shutil.which('codex')
    if not codex:
        raise Refused('PATH 中找不到 codex')
    if args.check:
        print('检查通过：%s %s，原目录 %s；未发送按键' % (args.tmux_session, pane['id'], cwd))
        return
    command = shlex.quote(codex) + ' resume ' + thread_id
    tmux('send-keys', '-l', '-t', pane['id'], command)
    tmux('send-keys', '-t', pane['id'], 'Enter')
    deadline = time.monotonic() + 30
    skipped_update = False
    while time.monotonic() < deadline:
        current = pane_for(args.tmux_session)
        if current['id'] != pane['id'] or current['pid'] != pane['pid'] or current['serverPid'] != pane['serverPid']:
            raise Refused('命令已发送，但 tmux 窗格身份变化；请手动核对，勿重试')
        if current['command'] == 'codex':
            try:
                running_id, binding = bridge_thread(current)
                if running_id == thread_id:
                    print('恢复成功：%s %s，Codex 会话 %s，App 绑定 %s' % (args.tmux_session, pane['id'], thread_id, binding[:12]))
                    return
                raise Refused('命令已发送，但前台 Codex 会话 ID 不匹配')
            except Refused:
                raise
            except Exception:
                if not skipped_update and update_menu(current):
                    tmux('send-keys', '-t', current['id'], 'Escape')
                    skipped_update = True
                # The CLI may not have opened the rollout yet.
        time.sleep(0.25)
    raise Refused('命令已发送，但未能在 30 秒内确认恢复；请查看原窗格，勿自动重试')


if __name__ == '__main__':
    try:
        main()
    except (Refused, OSError, ValueError, json.JSONDecodeError) as exc:
        print('未确认恢复：' + str(exc), file=sys.stderr)
        sys.exit(1)
