#!/usr/bin/env python3
"""Read-only Codex history adapter invoked over fixed local SSH, JSON on stdin.
Dependencies: Python 3.9+, tmux, Linux /proc, adjacent nas_remote_bridge.py.
No listener, input injection, arbitrary path argument, or modification of Codex.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import sys

bridge_path = Path(__file__).with_name('nas_remote_bridge.py')
if not bridge_path.exists():
    bridge_path = Path(__file__).resolve().parents[2] / 'android/core/src/main/resources/nas_remote_bridge.py'
spec = importlib.util.spec_from_file_location('nas_remote_bridge', bridge_path)
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
MAX_WINDOW = 4 * 1024 * 1024
MAX_PAGE = 384 * 1024


def identity(ref):
    if not isinstance(ref, dict) or ref.get('kind', 'tmux') != 'tmux' or not re.fullmatch(r'\$\d{1,12}', ref.get('id', '')):
        raise b.Refused('请选择 tmux 中的 Codex 会话')
    generation = ref.get('generation', '')
    if not isinstance(generation, str) or not re.fullmatch(r'\d+:\d+:[\d.]+:\d+:\d+', generation):
        raise b.Refused('会话标识无效')
    row = b.run('tmux', 'display-message', '-p', '-t', ref['id'], '#{session_id}\t#{session_created}\t#{pid}\t#{pane_id}\t#{pane_pid}\t#{pane_dead}\t#{pane_current_command}\t#{socket_path}').strip().split('\t')
    if len(row) != 8:
        raise b.Refused('会话已变化，请重新连接')
    ident, created, server, pane, pid, dead, command, socket_path = row
    st = os.stat(socket_path)
    dev, ino, _, expected_server, expected_created = generation.split(':')
    if (ident != ref['id'] or (dev, ino, expected_server, expected_created) != (str(st.st_dev), str(st.st_ino), server, created) or dead != '0' or command != 'codex'):
        raise b.Refused('当前窗格没有运行 Codex，请使用终端历史')
    result = {'id': pane, 'pid': int(pid), 'serverPid': int(server)}
    b.target({'pane': result})
    return result


def public_message(event, offset):
    # Only completed public items. Never expose response_item/system/tool/reasoning.
    if event.get('type') != 'event_msg':
        return None
    p = event.get('payload', {})
    if p.get('type') != 'item_completed':
        return None
    item = p.get('item', {})
    role = {'UserMessage': 'user', 'AgentMessage': 'assistant'}.get(item.get('type'))
    if not role or (role == 'assistant' and item.get('phase') not in (None, '', 'commentary', 'final', 'final_answer')):
        return None
    content = item.get('content')
    if not isinstance(content, list):
        return None
    text = '\n'.join(c['text'] for c in content if isinstance(c, dict) and c.get('type') in ('text', 'Text', 'input_text', 'output_text') and isinstance(c.get('text'), str))
    if not text.strip():
        return None
    replies = b.transport_envelope(text)
    if replies:
        if role != 'user':
            return None
        text = b.readable_question_replies(replies)
    return {'id': str(offset), 'role': role, 'text': b.clean(text), 'phase': item.get('phase') or ''}


def read_page(stream, before=None):
    size = os.fstat(stream.fileno()).st_size
    end = size if before is None else before
    if type(end) is not int or end < 0 or end > size:
        raise b.Refused('历史位置已失效，请刷新对话')
    start = max(0, end - MAX_WINDOW)
    stream.seek(start)
    data = stream.read(end - start)
    skipped = False
    if start:
        # Beginning must be a complete record; previous page includes this prefix.
        cut = data.find(b'\n')
        if cut < 0:
            return {'messages': [], 'before': start, 'hasOlder': True, 'skipped': True}
        start += cut + 1
        data = data[cut + 1:]
    offset, rows = start, []
    for raw in data.splitlines(keepends=True):
        if not raw.endswith(b'\n'):
            break  # Writer has not committed this record yet.
        rows.append((offset, raw))
        offset += len(raw)
    messages, used, cursor = [], 0, start
    for offset, raw in reversed(rows):
        try:
            message = public_message(json.loads(raw), offset)
        except (ValueError, TypeError, AttributeError):
            continue
        if message is None:
            continue
        length = len(json.dumps(message, ensure_ascii=False).encode())
        if length > MAX_PAGE:
            skipped = True
            continue
        if len(messages) >= 60 or used + length > MAX_PAGE:
            cursor = offset + len(raw)
            break
        messages.append(message)
        used += length
    return {'messages': list(reversed(messages)), 'before': cursor, 'hasOlder': cursor > 0, 'skipped': skipped}


def handle(request):
    if not isinstance(request, dict) or set(request) - {'ref', 'binding', 'before'}:
        raise b.Refused('读取请求无效')
    pane = identity(request.get('ref'))
    binding, stream, _ = b.locate(pane)
    try:
        if request.get('binding') is not None and request['binding'] != binding:
            raise b.Refused('Codex 对话已切换，请刷新对话')
        if request.get('before') is not None and request.get('binding') != binding:
            raise b.Refused('历史翻页必须绑定当前对话')
        page = read_page(stream, request.get('before'))
    finally:
        stream.close()
    if identity(request['ref']) != pane:
        raise b.Refused('窗格已变化，未返回历史')
    check, stream, _ = b.locate(pane)
    stream.close()
    if check != binding:
        raise b.Refused('Codex 对话已切换，请刷新对话')
    return {'ok': True, 'binding': binding, **page}


if __name__ == '__main__':
    try:
        signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(b.Refused('读取超时，请稍后重试')))
        signal.alarm(18)
        raw = sys.stdin.buffer.read(4097)
        if len(raw) > 4096:
            raise b.Refused('请求过大')
        result = handle(json.loads(raw))
    except b.Refused as error:
        result = {'ok': False, 'error': str(error)}
    except Exception:
        result = {'ok': False, 'error': '暂时无法读取当前 Codex 对话，可切换到终端历史或稍后刷新'}
    print(json.dumps(result, ensure_ascii=False))
