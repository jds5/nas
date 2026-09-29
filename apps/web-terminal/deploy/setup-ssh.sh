#!/bin/bash
# 用途：为当前 NAS 的 yao 用户配置网页临时 SSH；不读取 .env 或现有私钥。
# 依赖：bash、python3、OpenSSH；以 yao 运行 bash deploy/setup-ssh.sh。
# 影响：创建独立密钥/主机公钥记录和私有 transport 目录，备份并追加 authorized_keys。
set -eu
[ "$(id -un)" = yao ] && [ "$HOME" = /home/yao ] || { echo '仅支持当前 NAS 的 yao 用户'; exit 1; }
umask 077
install -d -m 700 "$HOME/.nas-web-ssh/transport" "$HOME/.ssh"
if [ ! -f "$HOME/.nas-web-ssh/key" ]; then
  ssh-keygen -q -t ed25519 -N '' -C nas-web-terminal-ephemeral -f "$HOME/.nas-web-ssh/key"
fi
python3 - <<'PY'
from pathlib import Path
import shutil, time
home = Path.home()
p = home / '.ssh/authorized_keys'
key = (home / '.nas-web-ssh/key.pub').read_text().strip()
line = 'from="127.0.0.1",restrict,pty ' + key
existing = p.read_text() if p.exists() else ''
if line not in existing.splitlines():
    backup = home / '.nas-maintenance-backups' / ('web-ssh-' + str(time.time_ns()))
    backup.mkdir(mode=0o700, parents=True)
    if p.exists(): shutil.copy2(p, backup / 'authorized_keys.before')
    with p.open('a') as f: f.write('\n' + line + '\n')
    p.chmod(0o600)
    print('authorized_keys backup:', backup)
host = Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().split()
known = home / '.nas-web-ssh/known_hosts'
expected = 'nas-web-local ' + ' '.join(host[:2]) + '\n'
if known.exists() and known.read_text() != expected:
    raise SystemExit('主机公钥发生变化，请核对后手动更新；未覆盖 known_hosts')
known.write_text(expected)
print('Dedicated SSH credential and transport directory ready')
PY
