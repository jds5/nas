"""Versioned, stateless SSH adapter. JSON stdin; no listener, eval, or user shell interpolation."""
import hashlib
import json
import os
import re
import stat
import subprocess
import selectors
import sys
import time
import uuid
from pathlib import Path

MAX_READ = 1024 * 1024
MAX_TEXT = 24000


class Refused(Exception):
    pass


def run(*args, data=None):
    # Bounded stdout while reading, with a deadline even for a stuck tmux process.
    with subprocess.Popen(list(args), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as proc:
        try:
            if data:
                proc.stdin.write(data)
            proc.stdin.close()
            output = bytearray()
            deadline = time.monotonic() + 4
            with selectors.DefaultSelector() as selector:
                selector.register(proc.stdout, selectors.EVENT_READ)
                while True:
                    if not selector.select(max(0, deadline - time.monotonic())):
                        raise Refused("tmux 操作超时")
                    chunk = os.read(proc.stdout.fileno(), 8192)
                    if not chunk:
                        break
                    output.extend(chunk)
                    if len(output) > 262144:
                        raise Refused("终端输出过大")
            if proc.wait(timeout=max(0.01, deadline - time.monotonic())):
                raise Refused("目标已变化或 tmux 操作失败，请重新选择会话")
            return output.decode("utf-8", "replace")
        finally:
            if proc.poll() is None:
                proc.kill()



def target(request):
    pane = request.get("pane", {})
    ident = pane.get("id", "")
    if not re.fullmatch(r"%[0-9]{1,12}", ident):
        raise Refused("窗格标识无效")
    if any(type(pane.get(k)) is not int or pane[k] <= 0 for k in ("pid", "serverPid")):
        raise Refused("进程标识无效")
    actual = run("tmux", "display-message", "-p", "-t", ident, "#{pane_pid}:#{pid}:#{pane_dead}:#{pane_current_command}").strip()
    if actual != f"{pane['pid']}:{pane['serverPid']}:0:codex":
        raise Refused("窗格已不再运行原 Codex，已拒绝操作")
    return pane


def process_info(pid):
    p = Path('/proc') / str(pid)
    raw = (p / 'stat').read_text()
    fields = raw[raw.rfind(')') + 2:].split()
    return int(fields[1]), fields[19], (p / 'comm').read_text().strip()


def locate(pane):
    # Follow the pane process tree, not cwd or the most recently modified session.
    todo, visited, candidates = [pane['pid']], set(), {}
    while todo and len(visited) < 128:
        pid = todo.pop()
        if pid in visited:
            continue
        visited.add(pid)
        try:
            _, start, comm = process_info(pid)
            proc = Path('/proc') / str(pid)
            children = (proc / 'task' / str(pid) / 'children').read_text().split()
            if comm == 'codex':
                for fd in (proc / 'fd').iterdir():
                    stream = None
                    try:
                        path = Path(os.readlink(fd))
                        if not re.fullmatch(r'rollout-[A-Za-z0-9T:._-]+\.jsonl', path.name):
                            continue
                        descriptor = os.open(fd, os.O_RDONLY | os.O_NONBLOCK)
                        st = os.fstat(descriptor)
                        if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid():
                            os.close(descriptor)
                            continue
                        stream = os.fdopen(descriptor, 'rb')
                        first = stream.readline(65537)
                        meta = json.loads(first)
                        info = meta.get('payload', {})
                        if meta.get('type') != 'session_meta' or not info.get('id'):
                            stream.close()
                            continue
                        binding = hashlib.sha256(f"{pid}:{start}:{st.st_dev}:{st.st_ino}:{info['id']}".encode()).hexdigest()
                        if binding in candidates:
                            stream.close()
                        else:
                            candidates[binding] = (stream, info, pid, start)
                            stream = None
                    except (OSError, ValueError, TypeError):
                        continue
                    finally:
                        if stream is not None:
                            stream.close()
                # Child agents are separate sessions; never traverse below a Codex process.
                continue
            todo.extend(int(c) for c in children)
        except (OSError, ValueError, IndexError):
            continue
    if len(candidates) != 1:
        for stream, *_ in candidates.values():
            stream.close()
        raise Refused("无法唯一关联运行中的 Codex 会话；请使用终端模式，不会猜测其他会话")
    binding, (stream, info, pid, start) = next(iter(candidates.items()))
    return binding, stream, info


def clean(text):
    return ''.join(c for c in text if c in '\n\t' or (ord(c) >= 32 and not 127 <= ord(c) <= 159 and c not in '\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069'))


def public_messages(stream, before=None, after=None, details=False):
    end = os.fstat(stream.fileno()).st_size
    if before is not None:
        if type(before) is not int or not 0 <= before <= end:
            raise Refused("历史位置无效")
        end = before
    size = end
    if after is not None:
        if type(after) is not int or not 0 <= after <= end:
            raise Refused("增量位置无效，记录可能已变化，请重新打开会话")
        start, end = after, min(end, after + MAX_READ)
    else:
        start = max(0, end - MAX_READ)
    stream.seek(start)
    data = stream.read(end - start)
    if start and after is None:
        newline = data.find(b'\n')
        if newline < 0:
            if details:
                return {'messages': [], 'status': 'unknown', 'before': start, 'next': end, 'more': end < size, 'skipped': True}
            return [], 'unknown', start
        start += newline + 1
        data = data[newline + 1:]
    cursor = start
    messages, offsets, status = [], [], 'unknown'
    consumed = start
    skipped = False
    for raw in data.splitlines(keepends=True):
        offset = cursor
        cursor += len(raw)
        if not raw.endswith(b'\n'):
            if len(data) == MAX_READ and after is not None and consumed == start:
                raise Refused('单条会话记录超过读取上限，请用终端核对或重新打开聊天')
            break
        consumed = cursor
        if len(raw) > MAX_READ:
            skipped = True
            continue
        try:
            event = json.loads(raw)
            payload = event.get('payload', {})
            if event.get('type') != 'event_msg':
                continue
            kind = payload.get('type')
            if kind in ('task_started', 'task_complete', 'turn_aborted'):
                status = {'task_started': 'working', 'task_complete': 'idle', 'turn_aborted': 'interrupted'}[kind]
            if kind != 'item_completed':
                continue
            item = payload.get('item', {})
            if item.get('type') == 'CommandExecution' and item.get('source') == 'user_shell':
                command = item.get('command', [])
                label = command[-1] if isinstance(command, list) and command and isinstance(command[-1], str) else '用户命令'
                output = item.get('aggregated_output', '')
                item = dict(item, type='UserShell', content=[{'type': 'text', 'text': '! ' + label + '\n\n' + str(output) + '\n[退出码 ' + str(item.get('exit_code', '?')) + ']'}])
            role = {'UserMessage': 'user', 'AgentMessage': 'assistant', 'UserShell': 'command'}.get(item.get('type'))
            if not role or not isinstance(item.get('content'), list):
                continue
            phase = item.get('phase', '')
            if role == 'assistant' and phase not in ('commentary', 'final_answer', 'final', None, ''):
                continue
            text = '\n'.join(c['text'] for c in item['content'] if isinstance(c, dict) and c.get('type') in ('text', 'Text', 'input_text', 'output_text') and isinstance(c.get('text'), str))
            if not text.strip():
                continue
            clipped = len(text) > MAX_TEXT
            text = clean(text[:MAX_TEXT]) + ('\n[消息过长，余下内容请在终端查看]' if clipped else '')
            mid = item.get('id')
            if not isinstance(mid, str) or not mid:
                continue
            messages.append({'id': mid[:128], 'role': role, 'text': text, 'phase': phase or '', 'offset': offset})
            offsets.append(offset)
            if after is not None and (len(messages) > 80 or len(json.dumps(messages, ensure_ascii=False).encode()) > 180000):
                messages.pop()
                consumed = offset
                break
        except (ValueError, TypeError, KeyError):
            continue
    # Bounded network / UI memory. Older history remains reachable by byte pagination.
    removed = 0
    while len(messages) > 80 or len(json.dumps(messages, ensure_ascii=False).encode()) > 180000:
        messages.pop(0)
        removed += 1
    if removed:
        start = offsets[removed]
    if details:
        return {'messages': messages, 'status': status, 'before': start, 'next': consumed, 'more': consumed < size and consumed > (after if after is not None else start), 'skipped': skipped}
    return messages, status, start


def skills(cwd):
    roots = [Path.home()/'.agents/skills', Path.home()/'.codex/skills', Path.home()/'.codex/plugins/cache']
    project = Path(cwd)
    for parent in [project, *project.parents][:12]:
        roots.extend([parent/'.agents/skills', parent/'.codex/skills'])
    result, seen, visited = [], set(), 0
    deadline = time.monotonic() + 1.0
    for root in roots:
        if not root.is_dir():
            continue
        for directory, dirs, files in os.walk(root, followlinks=False):
            visited += 1
            dirs[:] = sorted(d for d in dirs if d not in ('node_modules', '.git', 'build'))
            if visited > 1200 or time.monotonic() > deadline or len(result) >= 80:
                return result
            if len(Path(directory).relative_to(root).parts) > 8:
                dirs.clear()
                continue
            if 'SKILL.md' not in files:
                continue
            path = Path(directory)/'SKILL.md'
            try:
                if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
                    continue
                with path.open(encoding='utf-8', errors='replace') as f:
                    header = f.read(4096)
                if not header.startswith('---'):
                    continue
                front = header.split('---', 2)[1]
                names = re.findall(r'^name:\s*[\'"]?([A-Za-z0-9_.:-]+)', front, re.M)
                if not names or names[0] in seen:
                    continue
                name = names[0]
                if len(name) > 128:
                    continue
                description = re.search(r'^description:\s*(.+)', front, re.M)
                result.append({'name': name, 'description': clean(description.group(1).strip('\'"')[:160]) if description else ''})
                seen.add(name)
            except (OSError, ValueError):
                continue
    return result


def composer(pane, require_empty, text=""):
    if "\x1b" in text:
        raise Refused("控制字符无效")
    # Fail closed for menus/approvals or an existing desktop draft.
    info = run('tmux', 'display-message', '-p', '-t', pane['id'], '#{cursor_x}:#{cursor_y}').strip().split(':')
    screen = run('tmux', 'capture-pane', '-p', '-t', pane['id']).splitlines()
    x, y = map(int, info)
    if not require_empty and y < len(screen):
        # Multiline paste leaves the cursor below the first prompt row. Match the
        # rendered draft, not a selected menu item; otherwise require manual review.
        prefix = '! ' if text.startswith('!') else '› '
        expected = text[1:].lstrip() if text.startswith('!') else text
        if not text.startswith(('/', '!')) and screen[y].strip() == f'› [Pasted Content {len(text)} chars]':
            return
        for row in range(max(0, y - 100), y + 1):
            if screen[row].startswith(prefix):
                actual = '\n'.join([screen[row][2:].rstrip(), *[line[2:].rstrip() for line in screen[row + 1:y + 1]]])
                if actual == expected.rstrip() or re.sub(r'\s+', '', actual) == re.sub(r'\s+', '', expected):
                    return
        raise Refused('无法核对粘贴结果，请在控制面板检查后手动回车')
    if y >= len(screen) or not screen[y].startswith('› '):
        raise Refused('Codex 当前显示菜单或确认请求，请打开控制面板处理后再发送')
    if require_empty:
        content = screen[y][2:].strip()
        placeholders = ('Ask Codex to do anything', 'Find and fix a bug in @filename', 'Explain this codebase', 'Summarize recent commits', 'Implement {feature}', 'Write tests for @filename', 'Improve documentation in @filename', 'Use /skills to list available skills')
        if x != 2 or (content and content not in placeholders):
            raise Refused('电脑端输入框已有内容或状态无法确认，请在控制面板核对；未覆盖原输入')


def deliver(request, pane, binding):
    text = request.get('text')
    if not isinstance(text, str) or not text.strip() or len(text.encode()) > 16384:
        raise Refused('单条消息需为 1–16384 字节')
    if any((ord(c) < 32 and c not in '\n\t') or 127 <= ord(c) <= 159 for c in text):
        raise Refused('输入包含终端控制字符')
    if text.startswith(('/', '!')) and '\n' in text:
        raise Refused('命令请单行输入，多行内容请作为普通消息发送')
    composer(pane, True)
    buffer = 'nasremote-' + uuid.uuid4().hex
    touched = False
    try:
        run('tmux', 'load-buffer', '-b', buffer, '-', data=text.encode())
        target(request)
        current, stream, _ = locate(pane)
        stream.close()
        if current != binding:
            raise Refused('会话已切换，未发送')
        composer(pane, True)
        touched = True  # A transport failure from this point has an uncertain outcome.
        run('tmux', 'paste-buffer', '-p', '-d', '-b', buffer, '-t', pane['id'])
        time.sleep(0.2)
        target(request)
        current, stream, _ = locate(pane)
        stream.close()
        if current != binding:
            raise Refused('会话已切换')
        composer(pane, False, text)
        run('tmux', 'send-keys', '-t', pane['id'], 'Enter')
        return {'ok': True, 'submitted': True}
    except Exception:
        if touched:
            return {'ok': False, 'uncertain': True, 'error': '输入可能已粘贴或发送，请核对控制面板，禁止自动重试'}
        raise
    finally:
        try:
            run('tmux', 'delete-buffer', '-b', buffer)
        except Exception:
            pass


def screen_token(screen):
    # While an async question is open, background progress can animate above it.
    # Compare the complete question area (including choices and footer), not that spinner.
    marker = '• Queued follow-up inputs'
    if marker in screen:
        screen = screen[screen.rfind(marker):]
    return hashlib.sha256(screen.encode()).hexdigest()


def handle(request):
    pane = target(request)
    binding, stream, meta = locate(pane)
    try:
        expected = request.get('binding')
        if expected and expected != binding:
            raise Refused('运行会话已变化，请返回列表重新打开')
        action = request.get('action')
        if action == 'snapshot':
            reply = public_messages(stream, request.get('before'), request.get('after'), details=True)
            reply.update(ok=True, binding=binding, threadId=meta['id'])
            if request.get('screen'):
                screen = clean(run('tmux', 'capture-pane', '-p', '-t', pane['id']))[-14000:]
                reply['screen'] = screen
                reply['screenToken'] = screen_token(screen)
                footer = '\n'.join(screen.splitlines()[-12:])
                reply['questionHint'] = 'shift + ← to answer' in footer or ('enter submit' in footer and 'ctrl + ] skip' in footer)
            if request.get('includeSkills'):
                reply['skills'] = skills(meta.get('cwd', str(Path.home())))
            while reply.get('skills') and len(json.dumps(reply, ensure_ascii=False).encode()) > 250000:
                reply['skills'].pop()
            target(request)
            return reply
        if action in ('key', 'answer') and expected == binding:
            def verify_screen():
                target(request)
                current, check_stream, _ = locate(pane)
                check_stream.close()
                if current != binding:
                    raise Refused('会话已变化，未操作')
                screen = clean(run('tmux', 'capture-pane', '-p', '-t', pane['id']))[-14000:]
                if request.get('screenToken') != screen_token(screen):
                    raise Refused('终端画面已变化，请核对新画面后重新操作；没有发送按键')
            verify_screen()
            buffer, touched = None, False
            try:
                if action == 'key':
                    key = request.get('key')
                    if key not in ('Enter', 'Escape', 'Up', 'Down', 'Left', 'Right', 'S-Left', 'S-Right', 'Tab', 'BTab', 'Space', 'BSpace', 'C-u', 'C-c', 'M-Down', 'C-]'):
                        raise Refused('按键不受支持')
                    touched = True
                    run('tmux', 'send-keys', '-t', pane['id'], key)
                else:
                    text = request.get('text')
                    if not isinstance(text, str) or not text.strip() or len(text.encode()) > 16384 or any((ord(c) < 32 and c not in '\n\t') or 127 <= ord(c) <= 159 for c in text):
                        raise Refused('回答内容无效或超过 16 KiB')
                    buffer = 'nasremote-' + uuid.uuid4().hex
                    run('tmux', 'load-buffer', '-b', buffer, '-', data=text.encode())
                    verify_screen()
                    touched = True
                    run('tmux', 'paste-buffer', '-p', '-d', '-b', buffer, '-t', pane['id'])
                return {'ok': True}  # Answer paste never implicitly presses Enter.
            except Exception:
                if touched:
                    return {'ok': False, 'uncertain': True, 'error': '操作结果待确认，请重连核对；不会自动重试'}
                raise
            finally:
                if buffer:
                    try:
                        run('tmux', 'delete-buffer', '-b', buffer)
                    except Exception:
                        pass
        if action == 'send' and expected == binding:
            return deliver(request, pane, binding)
        raise Refused('请求不受支持或会话未关联')
    finally:
        stream.close()


if __name__ == '__main__':
    try:
        data = sys.stdin.buffer.read(131073)
        if len(data) > 131072:
            raise Refused('请求过大')
        print(json.dumps(handle(json.loads(data)), ensure_ascii=False))
    except Refused as e:
        print(json.dumps({'ok': False, 'error': str(e)}, ensure_ascii=False))
    except Exception:
        print(json.dumps({'ok': False, 'error': '会话适配失败；需要 Linux、Python 3.9+ 和可读取的 Codex 会话记录'}, ensure_ascii=False))
