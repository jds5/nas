import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { stat } from 'node:fs/promises';
import pty from 'node-pty';
const exec = promisify(execFile);

export class Tmux {
  constructor(socket = '/run/host-tmux/default') { this.socket = socket; }
  async command(args) {
    const { stdout } = await exec('/usr/bin/tmux', ['-S', this.socket, ...args], {
      timeout: 3000, maxBuffer: 1024 * 1024, env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8' },
    });
    return stdout.trimEnd();
  }
  async list() {
    const info = await stat(this.socket);
    if (!info.isSocket()) throw new Error('socket_unavailable');
    const output = await this.command(['list-sessions', '-F', '#{session_id}\t#{session_created}\t#{pid}\t#{session_windows}\t#{session_attached}']);
    const rows = output.split('\n').filter(Boolean).slice(0, 100);
    const sessions = [];
    for (const row of rows) {
      const [id, created, pid, windows, attached] = row.split('\t');
      if (!/^\$\d+$/.test(id) || ![created, pid, windows, attached].every(x => /^\d+$/.test(x))) throw new Error('invalid_session');
      const name = await this.command(['display-message', '-p', '-t', id, '#{session_name}']);
      // tmux changes socket permissions on attach/detach, which changes ctime.
      // Birth time + inode identify replacement without invalidating a live session.
      sessions.push({ id, generation: `${info.dev}:${info.ino}:${info.birthtimeMs}:${pid}:${created}`,
        name: name.replace(/[\x00-\x1f\x7f]/g, '').slice(0, 160), windows: Number(windows), attached: Number(attached) });
    }
    return sessions;
  }
  async exists(ref) {
    return Boolean(await this.snapshot(ref));
  }
  async snapshot(ref) {
    if (!ref || typeof ref.id !== 'string' || !/^\$\d+$/.test(ref.id) || typeof ref.generation !== 'string') return false;
    try {
      const info = await stat(this.socket);
      const row = await this.command(['display-message', '-p', '-t', ref.id,
        '#{session_id}\t#{session_created}\t#{pid}\t#{exit-unattached}\t#{pane_id}\t#{pane_current_command}']);
      const [id, created, pid, exitUnattached, pane, command] = row.split('\t');
      if (!info.isSocket() || id !== ref.id || exitUnattached !== '0' ||
          ref.generation !== `${info.dev}:${info.ino}:${info.birthtimeMs}:${pid}:${created}`) return false;
      return { pane, command };
    } catch { return false; }
  }
  async history(ref) {
    const before = await this.snapshot(ref);
    if (!before) throw new Error('session_unavailable');
    const text = await this.command(['capture-pane', '-p', '-e', '-J', '-t', before.pane, '-S', '-1000']);
    const after = await this.snapshot(ref);
    if (!after || after.pane !== before.pane) throw new Error('session_changed');
    return text;
  }
  attach(ref, size = { cols: 80, rows: 24 }) {
    // Only this client lives in the container. The server and its panes remain on the host.
    if (!/^\$\d+$/.test(ref.id) || !/^\d+:\d+:[\d.]+:\d+:\d+$/.test(ref.generation)) throw new Error('invalid_session');
    const [, , , pid, created] = ref.generation.split(':');
    // Recheck server/session identity inside tmux's command queue, closing the
    // gap between the HTTP existence check and attachment after a server restart.
    const matches = `#{&&:#{==:#{pid},${pid}},#{==:#{session_created},${created}}}`;
    return pty.spawn('/usr/bin/tmux', ['-S', this.socket, 'if-shell', '-F', '-t', ref.id, matches,
      `attach-session -E -t ${ref.id}`, 'display-message session-changed'], {
      name: 'xterm-256color', cols: size.cols, rows: size.rows, cwd: '/tmp',
      env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', TERM: 'xterm-256color', HOME: '/tmp' },
    });
  }
}
