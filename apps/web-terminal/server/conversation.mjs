import { execFile } from 'node:child_process';
import { SSH_ARGS } from './ssh.mjs';

// Shares pinned, fixed SSH endpoint. No client-provided command, path or thread ID.
export class ConversationReader {
  active = 0;
  async read(request, signal) {
    if (this.active >= 2) return { ok: false, error: '历史读取繁忙，请稍后重试' };
    this.active++;
    try {
      const raw = await new Promise((resolve, reject) => {
        const child = execFile('/usr/bin/ssh', [...SSH_ARGS.slice(0, -1), '-T', SSH_ARGS.at(-1),
          'python3 /home/yao/.local/share/nas-web-terminal/reader-0.5.0/read_conversation.py'],
        { timeout: 22000, maxBuffer: 512 * 1024, signal, env: { PATH: '/usr/bin:/bin', HOME: '/tmp', LANG: 'C.UTF-8' } },
        (error, stdout) => error ? reject(error) : resolve(stdout));
        child.stdin.on('error', () => {});
        const { id, kind, generation } = request.ref;
        child.stdin.end(JSON.stringify({ ...request, ref: { id, kind, generation } }));
      });
      const result = JSON.parse(raw);
      if (!result.ok) return { ok: false, error: typeof result.error === 'string' ? result.error.slice(0, 200) : 'Codex 历史暂不可读' };
      if (!/^[a-f0-9]{64}$/.test(result.binding) || !Array.isArray(result.messages) || result.messages.length > 60 || !Number.isSafeInteger(result.before)) throw new Error('invalid_history');
      if (result.messages.some(m => !['user', 'assistant'].includes(m.role) || typeof m.text !== 'string' || typeof m.id !== 'string')) throw new Error('invalid_message');
      return result;
    } finally { this.active--; }
  }
}
