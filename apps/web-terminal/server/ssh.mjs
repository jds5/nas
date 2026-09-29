import pty from 'node-pty';
import { access } from 'node:fs/promises';

// Only a fixed NAS endpoint is supported; no client-controlled host, user, key or command.
export class SSH {
  async exists(ref) {
    if (ref?.kind !== 'ssh' || !/^ssh-[a-f0-9]{32}$/.test(ref.id) || ref.generation !== 'ephemeral') return false;
    try { await Promise.all(['key', 'known_hosts', 'transport/ssh.sock'].map(p => access(`/run/host-ssh/${p}`))); return true; }
    catch { return false; }
  }
  attach(_ref, size) {
    return pty.spawn('/usr/bin/ssh', ['-F', '/dev/null', '-tt', '-e', 'none',
      '-i', '/run/host-ssh/key', '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
      '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=/run/host-ssh/known_hosts',
      '-o', 'GlobalKnownHostsFile=/dev/null', '-o', 'HostKeyAlias=nas-web-local',
      '-o', 'ProxyCommand=/usr/bin/socat STDIO UNIX-CONNECT:/run/host-ssh/transport/ssh.sock',
      '-o', 'ClearAllForwardings=yes', '-o', 'ForwardAgent=no', '-o', 'ControlMaster=no',
      '-o', 'ConnectTimeout=8', '-o', 'ServerAliveInterval=5', '-o', 'ServerAliveCountMax=1',
      'yao@127.0.0.1'], { name: 'xterm-256color', ...size, cwd: '/tmp',
      env: { PATH: '/usr/bin:/bin', HOME: '/tmp', LANG: 'C.UTF-8', TERM: 'xterm-256color' } });
  }
}
export class Terminals {
  constructor(tmux, ssh) { this.tmux = tmux; this.ssh = ssh; }
  async list() { try { return await this.tmux.list(); } catch { return []; } }
  backend(ref) { return ref?.kind === 'ssh' ? this.ssh : (!ref?.kind || ref.kind === 'tmux') ? this.tmux : undefined; }
  async exists(ref) { return Boolean(await this.backend(ref)?.exists(ref)); }
  attach(ref, size) { const backend = this.backend(ref); if (!backend) throw new Error('invalid_session'); return backend.attach(ref, size); }
  async snapshot(ref) { return this.backend(ref) === this.tmux ? this.tmux.snapshot(ref) : false; }
}
