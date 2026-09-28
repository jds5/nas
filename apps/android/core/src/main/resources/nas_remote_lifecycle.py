"""Bounded tmux/Codex lifecycle commands for the NAS Remote SSH client.

JSON is read from stdin. No listener or persistent service is installed.
"""

import hashlib
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


HOME = Path.home()
PROJECTS = HOME / 'code'
STATE = HOME / '.cache' / 'nas-remote-lifecycle'
BACKGROUND = []  # Keep detached worker handles alive until this short SSH exec exits.
NAME = re.compile(r'[A-Za-z][A-Za-z0-9_-]{0,39}')
UUID = re.compile(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}')


class Refused(Exception):
    pass


def call(args, *, timeout=5, env=None):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout, env=env)
    if result.returncode:
        raise Refused((result.stderr or '远端命令失败').strip()[:240])
    return result.stdout


def tmux(*args):
    prefix = ['tmux']
    # Isolated tests use their own socket; normal SSH requests use tmux's default socket.
    socket_name = os.environ.get('NAS_REMOTE_TEST_TMUX_SOCKET')
    if socket_name:
        prefix += ['-L', socket_name]
    return call(prefix + list(args))


def valid_name(value):
    if not isinstance(value, str) or not NAME.fullmatch(value):
        raise Refused('tmux 名称须以字母开头，只含字母、数字、_、-，最多 40 字符')
    return value


def default_session(path):
    name = re.sub(r'[^A-Za-z0-9_-]', '_', Path(path).name)[:38]
    return valid_name(name if name and name[0].isalpha() else 's_' + name)


def project_input(value, *, exists=True):
    if not isinstance(value, str) or not value or len(value) > 500 or value.startswith('/') or '\x00' in value:
        raise Refused('请填写 ~/code 下的相对路径')
    parts = value.split('/')
    if any(part in ('', '.', '..') for part in parts):
        raise Refused('项目相对路径不能包含空段、. 或 ..')
    return project(str(PROJECTS.joinpath(*parts)), exists=exists)


def project(path, *, exists=True):
    if not isinstance(path, str) or len(path) > 500 or not path.startswith('/') or '\x00' in path:
        raise Refused('项目路径无效')
    root = PROJECTS.resolve(strict=True)
    item = Path(path)
    actual = item.resolve(strict=exists)
    if root not in actual.parents:
        raise Refused('项目须位于 ~/code 下')
    if exists:
        info = actual.stat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
            raise Refused('项目目录须由当前 SSH 用户拥有')
    elif actual.parent != root or item.exists() or item.is_symlink():
        raise Refused('新项目须是 ~/code 下尚不存在的一级目录')
    return actual


def clone_name(url):
    from urllib.parse import urlsplit, unquote
    path = urlsplit(url).path if '://' in url else url.split(':', 1)[1]
    name = unquote(path.rstrip('/').rsplit('/', 1)[-1])
    if name.endswith('.git'):
        name = name[:-4]
    if not name or name in ('.', '..') or '/' in name or '\\' in name or any(ord(c) < 32 for c in name):
        raise Refused('无法从 Git 地址确定安全的目录名')
    return name


def codex_binary():
    candidates = [shutil.which('codex'), str(HOME / '.local' / 'bin' / 'codex'), '/usr/local/bin/codex']
    for candidate in candidates:
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return candidate
    raise Refused('NAS 上找不到 Codex CLI；请检查 ~/.local/bin/codex')


def codex_args(value):
    if not isinstance(value, str) or len(value) > 500 or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise Refused('Codex 启动参数无效')
    try:
        args = shlex.split(value)
    except ValueError:
        raise Refused('Codex 启动参数引号未闭合')
    if len(args) > 24 or any(len(arg) > 200 for arg in args):
        raise Refused('Codex 启动参数过长')
    return args


def current_panes():
    try:
        lines = tmux('list-panes', '-a', '-F', '#{session_name}\t#{pane_id}\t#{pane_pid}\t#{pid}\t#{pane_dead}\t#{pane_current_command}\t#{pane_current_path}\t#{cursor_x}\t#{cursor_y}').splitlines()
    except Refused as exc:
        if any(mark in str(exc) for mark in ('no server running', 'failed to connect to server', 'error connecting to')):
            return []
        raise
    result = []
    for line in lines[:128]:
        fields = line.split('\t')
        if len(fields) != 9:
            raise Refused('tmux 窗格格式不受支持')
        session, ident, pid, server, dead, command, cwd, x, y = fields
        if dead == '0':
            result.append(dict(session=session, id=ident, pid=int(pid), serverPid=int(server),
                               command=command, path=cwd, x=int(x), y=int(y)))
    return result


def one_pane(session):
    panes = [p for p in current_panes() if p['session'] == session]
    if len(panes) != 1:
        raise Refused('目标 tmux 会话不存在或不止一个窗格')
    return panes[0]


def saved_threads():
    base = HOME / '.codex' / 'sessions'
    if not base.is_dir():
        return []
    found = []
    count = 0
    for path in base.rglob('rollout-*.jsonl'):
        count += 1
        if count > 3000:
            break
        try:
            info = path.stat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
                continue
            with path.open('rb') as stream:
                meta = json.loads(stream.readline(65537))
            data = meta.get('payload', {})
            ident, cwd = data.get('id'), data.get('cwd')
            if meta.get('type') != 'session_meta' or not isinstance(ident, str) or not UUID.fullmatch(ident) or not isinstance(cwd, str):
                continue
            found.append(dict(id=ident, path=cwd, created=str(meta.get('timestamp', ''))[:25],
                              file=str(path), inode=info.st_ino))
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            continue
    found.sort(key=lambda item: item['created'], reverse=True)
    return found[:80]


def thread(ident):
    if not isinstance(ident, str) or not UUID.fullmatch(ident):
        raise Refused('Codex 会话 UUID 无效')
    base = HOME / '.codex' / 'sessions'
    matches = []
    for path in base.rglob('rollout-*' + ident + '.jsonl'):
        try:
            info = path.stat()
            if info.st_uid != os.getuid() or not stat.S_ISREG(info.st_mode):
                continue
            with path.open('rb') as stream:
                meta = json.loads(stream.readline(65537))
            data = meta.get('payload', {})
            if meta.get('type') == 'session_meta' and data.get('id') == ident and isinstance(data.get('cwd'), str):
                matches.append(dict(id=ident, path=data['cwd'], inode=info.st_ino, file=str(path)))
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            continue
    if len(matches) != 1:
        raise Refused('找不到唯一的历史会话记录')
    matches[0]['path'] = str(project(matches[0]['path']))
    return matches[0]


def thread_file_open(path):
    info = Path(path).stat()
    wanted = (info.st_dev, info.st_ino)
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            if (proc / 'comm').read_text().strip() != 'codex':
                continue
            for fd in (proc / 'fd').iterdir():
                found = fd.stat()
                if (found.st_dev, found.st_ino) == wanted:
                    return True
        except (OSError, PermissionError):
            continue
    return False


def empty_shell(pane, *, owned=False):
    if pane['command'] != 'bash':
        raise Refused('目标窗格不是空闲 bash')
    children = (Path('/proc') / str(pane['pid']) / 'task' / str(pane['pid']) / 'children').read_text().strip()
    if children:
        raise Refused('目标 shell 仍有子进程')
    lines = tmux('capture-pane', '-p', '-t', pane['id']).splitlines()
    if pane['y'] >= len(lines):
        raise Refused('无法确认 shell 输入行')
    row = lines[pane['y']].rstrip()
    prefix = pwd.getpwuid(os.getuid()).pw_name + '@' + socket.gethostname().split('.')[0] + ':'
    valid = row == 'NAS_REMOTE_READY$' or (not owned and re.fullmatch(re.escape(prefix) + r'[^\n]*\$', row))
    if not valid or not len(row) <= pane['x'] <= len(row) + 2:
        raise Refused('目标 shell 有输入或提示符无法确认')


def git_url(value):
    if not isinstance(value, str) or len(value) > 500 or any(ord(c) < 33 or ord(c) > 126 for c in value):
        raise Refused('Git 地址无效')
    from urllib.parse import urlsplit
    if value.startswith('https://'):
        parsed = urlsplit(value)
        if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise Refused('HTTPS Git 地址不能含凭据、查询或片段')
    elif value.startswith('ssh://'):
        parsed = urlsplit(value)
        if not parsed.hostname or parsed.password or parsed.query or parsed.fragment:
            raise Refused('SSH Git 地址无效')
    elif not re.fullmatch(r'git@[A-Za-z0-9.-]+:[A-Za-z0-9._/~+%-]+', value):
        raise Refused('只支持 HTTPS 或 SSH Git 仓库地址')
    return value


def git_environment():
    env = os.environ.copy()
    env['GIT_TERMINAL_PROMPT'] = '0'
    env['GIT_LFS_SKIP_SMUDGE'] = '1'
    env['GIT_SSH_COMMAND'] = 'ssh -o BatchMode=yes -o StrictHostKeyChecking=yes'
    return env


def valid_branch(value):
    if value in ('', None):
        return ''
    if not isinstance(value, str) or len(value) > 128 or value.startswith('-'):
        raise Refused('Git 分支名称无效')
    call(['git', 'check-ref-format', '--branch', value])
    return value


def pull_preview(path):
    env = git_environment()
    if call(['git', '-C', path, 'rev-parse', '--show-toplevel']).strip() != path:
        raise Refused('所选路径不是 Git 工作树根目录')
    if call(['git', '-C', path, 'status', '--porcelain', '--untracked-files=normal']).strip():
        raise Refused('工作树或索引有改动，不能自动拉取')
    branch = call(['git', '-C', path, 'symbolic-ref', '--quiet', '--short', 'HEAD']).strip()
    upstream = call(['git', '-C', path, 'rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{upstream}']).strip()
    if not upstream.startswith('origin/') or upstream != 'origin/' + branch:
        raise Refused('只支持当前分支跟踪 origin 的同名分支')
    git_url(call(['git', '-C', path, 'remote', 'get-url', 'origin']).strip())
    head = call(['git', '-C', path, 'rev-parse', 'HEAD']).strip()
    remote = call(['git', '-C', path, 'ls-remote', '--exit-code', '--heads', 'origin', branch], timeout=5, env=env).split()
    if not remote or not re.fullmatch(r'[0-9a-f]{40,64}', remote[0]):
        raise Refused('无法读取远端分支位置')
    return {'branch': branch, 'head': head, 'remoteHead': remote[0]}


def proposal(req):
    mode = req.get('mode')
    if mode not in ('restore_pane', 'new_pane', 'restore_new', 'existing', 'empty', 'clone'):
        raise Refused('操作类型无效')
    codex = codex_binary()
    args = codex_args(req.get('args', ''))
    item = None
    pane = None
    url = git_url(req.get('url')) if mode == 'clone' else ''
    branch = valid_branch(req.get('branch')) if mode == 'clone' else ''
    if mode in ('restore_pane', 'new_pane'):
        session = valid_name(req.get('session'))
        pane = one_pane(session)
        if mode == 'restore_pane':
            item = thread(req.get('thread'))
            if pane['path'] != item['path']:
                raise Refused('窗格目录与历史会话原目录不符')
            path = item['path']
        else:
            path = str(project(pane['path']))
        empty_shell(pane)
    elif mode == 'restore_new':
        item = thread(req.get('thread'))
        path = item['path']
        session = valid_name(req.get('session') or default_session(path))
    elif mode == 'existing':
        path = str(project_input(req.get('path')))
        session = valid_name(req.get('session') or default_session(path))
    elif mode == 'clone':
        path = str(project_input(clone_name(url), exists=False))
        session = valid_name(req.get('session') or default_session(path))
    else:
        path = str(project_input(req.get('path'), exists=False))
        session = valid_name(req.get('session') or default_session(path))
    if mode not in ('restore_pane', 'new_pane') and any(p['session'] == session for p in current_panes()):
        raise Refused('tmux 名称已存在，请修改会话名称')
    if item and thread_file_open(item['file']):
        raise Refused('该 Codex 会话已在其他窗格运行')
    pull = req.get('pull') is True
    if pull and mode != 'existing':
        raise Refused('只可更新已有目录')
    git = pull_preview(path) if pull else None
    result = dict(mode=mode, session=session, path=path, thread=item['id'] if item else '',
                  inode=item['inode'] if item else 0, url=url, branch=branch, pull=pull, git=git,
                  args=args, codex=codex,
                  pane={k: pane[k] for k in ('id', 'pid', 'serverPid')} if pane else None)
    return result


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def state_dir():
    if STATE.is_symlink():
        raise Refused('生命周期状态目录不能是符号链接')
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = STATE.stat()
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700 or not stat.S_ISDIR(info.st_mode):
        raise Refused('生命周期状态目录权限不安全')


def status_path(ident):
    try:
        value = uuid.UUID(ident)
    except (TypeError, ValueError):
        raise Refused('操作 ID 无效')
    return STATE / (str(value) + '.json')


def write_status(path, value):
    temp = path.with_suffix('.tmp-' + uuid.uuid4().hex)
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(value, stream, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)


def read_status(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_uid != os.getuid():
        raise Refused('操作记录不存在或身份无效')
    return json.loads(path.read_text())


def launch(p):
    path = p['path']
    if p['mode'] not in ('restore_pane', 'new_pane'):
        tmux('new-session', '-d', '-s', p['session'], '-c', path,
             "PS1='NAS_REMOTE_READY$ ' exec /bin/bash --noprofile --norc -i")
        pane = one_pane(p['session'])
        identity = {k: pane[k] for k in ('id', 'pid', 'serverPid')}
        last_issue = ''
        for _ in range(60):
            try:
                pane = one_pane(p['session'])
                if {k: pane[k] for k in identity} != identity:
                    raise Refused('新窗格身份已变化')
                empty_shell(pane, owned=True)
                break
            except Refused as exc:
                last_issue = str(exc)
                time.sleep(.1)
        else:
            raise Refused('新窗格 shell 未就绪：' + last_issue)
    else:
        pane = one_pane(p['session'])
        if {k: pane[k] for k in ('id', 'pid', 'serverPid')} != p['pane']:
            raise Refused('原窗格身份已变化')
        if pane['path'] != path:
            raise Refused('原窗格目录已变化')
        empty_shell(pane)
    codex = p['codex']
    if not Path(codex).is_file() or not os.access(codex, os.X_OK):
        raise Refused('Codex CLI 已变化或不可执行')
    command = ' '.join(shlex.quote(part) for part in [codex, *p['args'],
                      *(['resume', p['thread']] if p['thread'] else [])])
    tmux('send-keys', '-l', '-t', pane['id'], command)
    tmux('send-keys', '-t', pane['id'], 'Enter')
    return {k: pane[k] for k in ('id', 'pid', 'serverPid', 'session', 'path')}


WORKER = r'''import ctypes,json,os,subprocess,sys,time,uuid
from pathlib import Path
path=Path(sys.argv[1]); state=json.loads(path.read_text()); p=state['proposal']
dest=Path(p['path']); stage=dest.parent/('.nasremote-clone-'+path.stem)
def save():
 tmp=path.with_suffix('.tmp-'+uuid.uuid4().hex)
 fd=os.open(tmp,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
 with os.fdopen(fd,'w') as f: json.dump(state,f); f.flush(); os.fsync(f.fileno())
 os.replace(tmp,path)
state['status']='pulling' if p.get('pull') else 'cloning'; save()
env=os.environ.copy();env['GIT_TERMINAL_PROMPT']='0';env['GIT_LFS_SKIP_SMUDGE']='1'
env['GIT_SSH_COMMAND']='ssh -o BatchMode=yes -o StrictHostKeyChecking=yes'
try:
 if p.get('pull'):
  g=p['git']; directory=str(dest)
  def value(*args):
   return subprocess.check_output(['git','-C',directory,*args],text=True,stderr=subprocess.DEVNULL,timeout=5).strip()
  if value('rev-parse','HEAD')!=g['head'] or value('symbolic-ref','--quiet','--short','HEAD')!=g['branch'] or value('status','--porcelain','--untracked-files=normal'):
   raise RuntimeError('仓库状态已变化，未执行拉取')
  error_fd=os.open(path.with_suffix('.stderr'),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
  with os.fdopen(error_fd,'wb') as errors:
   fetch=subprocess.run(['git','-C',directory,'fetch','-q','--','origin',g['branch']],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=errors,env=env,timeout=600)
  if fetch.returncode: raise RuntimeError('Git fetch 失败，请核对 NAS 凭据和网络')
  if value('rev-parse','FETCH_HEAD')!=g['remoteHead'] or value('status','--porcelain','--untracked-files=normal'):
   raise RuntimeError('远端或工作树状态已变化，未执行合并')
  merge=subprocess.run(['git','-C',directory,'merge','--ff-only','FETCH_HEAD'],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,env=env,timeout=60)
  if merge.returncode: raise RuntimeError('无法快速前进；未自动处理冲突')
  state['status']='pulled';state['head']=value('rev-parse','HEAD')
 else:
  if dest.exists() or stage.exists(): raise RuntimeError('目标或临时目录已存在')
  error_fd=os.open(path.with_suffix('.stderr'),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
  with os.fdopen(error_fd,'wb') as errors:
   command=['git','clone','-q']+(['--branch',p['branch']] if p.get('branch') else [])+['--',p['url'],str(stage)]
   result=subprocess.run(command,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=errors,env=env,timeout=600)
  if result.returncode: raise RuntimeError('Git 克隆失败，请检查 NAS Git 凭据和仓库地址')
  head=subprocess.run(['git','-C',str(stage),'rev-parse','--verify','HEAD'],capture_output=True,text=True,timeout=5)
  if head.returncode or not head.stdout.strip(): raise RuntimeError('克隆后无法确认 HEAD')
  if dest.exists(): raise RuntimeError('目标目录被其他操作占用')
  libc=ctypes.CDLL(None,use_errno=True)
  if libc.renameat2(-100,os.fsencode(stage),-100,os.fsencode(dest),1)!=0:
   raise OSError(ctypes.get_errno(),'目标目录被其他操作占用')
  state['status']='cloned';state['head']=head.stdout.strip()
except Exception as exc:
 state['status']='failed';state['error']=str(exc)[:200]
save()
'''


def start_operation(req):
    state_dir()
    lock_path = STATE / '.lock'
    fd = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        import fcntl
        fcntl.flock(fd, fcntl.LOCK_EX)
        return start_locked(req)
    finally:
        os.close(fd)


def start_locked(req):
    state_dir()
    path = status_path(req.get('operation'))
    if path.exists():
        state = read_status(path)
        if state.get('status') in ('cloned', 'pulled'):
            p = state['proposal']
            p = dict(p, mode='existing', url='')
            state['status'] = 'starting'
            write_status(path, state)
            try:
                pane = launch(p)
                state.update(status='started', pane=pane)
            except Exception as exc:
                state.update(status='uncertain', error=str(exc)[:200])
            write_status(path, state)
        return {'ok': True, **state}
    p = proposal(req)
    if req.get('digest') != digest(p):
        raise Refused('预检结果已变化，请重新确认')
    state = {'operation': req['operation'], 'proposal': p, 'status': 'starting'}
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(state, stream)
    if p['mode'] == 'clone' or p['pull']:
        try:
            BACKGROUND[:] = [worker for worker in BACKGROUND if worker.poll() is None]
            BACKGROUND.append(subprocess.Popen([sys.executable, '-c', WORKER, str(path)], stdin=subprocess.DEVNULL,
                                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                               start_new_session=True, close_fds=True))
            state['status'] = 'pulling' if p['pull'] else 'cloning'
        except Exception as exc:
            state.update(status='uncertain', error=str(exc)[:200])
            write_status(path, state)
        return {'ok': True, **state}
    try:
        if p['mode'] == 'empty':
            Path(p['path']).mkdir(mode=0o700)
        pane = launch(p)
        state.update(status='started', pane=pane)
    except Exception as exc:
        state.update(status='uncertain', error=str(exc)[:200])
    write_status(path, state)
    return {'ok': True, **state}


def handle(req):
    action = req.get('action')
    if action == 'panes':
        return {'ok': True, 'panes': current_panes()}
    if action == 'threads':
        return {'ok': True, 'threads': [{k: t[k] for k in ('id', 'path', 'created')} for t in saved_threads()]}
    if action == 'prepare':
        p = proposal(req)
        return {'ok': True, 'proposal': p, 'digest': digest(p)}
    if action == 'inspect':
        state_dir()
        return {'ok': True, **read_status(status_path(req.get('operation')))}
    if action == 'start':
        return start_operation(req)
    raise Refused('不支持的生命周期操作')


if __name__ == '__main__':
    try:
        raw = sys.stdin.buffer.readline(8193)
        if len(raw) > 8192:
            raise Refused('请求过大')
        print(json.dumps(handle(json.loads(raw)), ensure_ascii=False))
    except (Refused, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as exc:
        print(json.dumps({'ok': False, 'error': str(exc)[:240]}, ensure_ascii=False))
