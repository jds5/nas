"""Claude Code adapter: synthetic transcripts plus opt-in private tmux with a fake `claude`.
Never touches ~/.claude, production tmux or a real Claude Code session.
"""
import json
import os
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from test_bridge import b, encoded


def user(ident, content, **kwargs):
    return {'type': 'user', 'uuid': ident, 'message': {'role': 'user', 'content': content}, **kwargs}


def assistant(ident, *blocks):
    return {'type': 'assistant', 'uuid': ident, 'message': {'role': 'assistant', 'content': list(blocks)}}


class ClaudeRecordTests(unittest.TestCase):
    def read(self, records, **kwargs):
        with tempfile.TemporaryFile() as f:
            f.write(encoded(records)); f.flush()
            return b.public_messages(f, details=True, agent='claude', **kwargs)

    def test_only_public_text_bash_and_command_output(self):
        result = self.read([
            {'type': 'mode', 'mode': 'normal'},
            user('meta', '<local-command-caveat>PRIVATE</local-command-caveat>', isMeta=True),
            user('u1', '你好'),
            assistant('t1', {'type': 'thinking', 'thinking': 'PRIVATE'}),
            assistant('t2', {'type': 'tool_use', 'name': 'Bash', 'input': {'command': 'PRIVATE'}}),
            user('t3', [{'type': 'tool_result', 'content': 'PRIVATE'}]),
            assistant('side', {'type': 'text', 'text': 'PRIVATE'}) | {'isSidechain': True},
            assistant('a1', {'type': 'text', 'text': '**回复**'}),
            {'type': 'system', 'subtype': 'turn_duration'},
            user('b1', '<bash-input>echo hi</bash-input>'),
            user('b2', '<bash-stdout>hi\x1b[0m</bash-stdout><bash-stderr></bash-stderr>'),
            user('c1', '<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args>opus</command-args>'),
            user('c2', '<local-command-stdout>Set model to \x1b[1mOpus\x1b[22m</local-command-stdout>'),
            user('img', [{'type': 'text', 'text': '看图'}, {'type': 'image', 'source': {}}]),
            user('stop', [{'type': 'text', 'text': '[Request interrupted by user]'}]),
        ])
        self.assertNotIn('PRIVATE', json.dumps(result, ensure_ascii=False))
        self.assertEqual([(m['id'], m['role'], m['text']) for m in result['messages']], [
            ('u1', 'user', '你好'), ('a1', 'assistant', '**回复**'), ('b1', 'user', '! echo hi'),
            ('b2', 'command', 'hi'), ('c1', 'user', '/model opus'), ('c2', 'command', 'Set model to Opus'),
            ('img', 'user', '看图\n[图片 ×1]')])
        self.assertEqual(result['status'], 'interrupted')
        self.assertEqual(self.read([user('u', 'x'), {'type': 'system', 'subtype': 'turn_duration'}])['status'], 'idle')

    def test_incremental_cursor_and_partial_line(self):
        data = encoded([user('u1', 'one')]) + b'{"type":"user"'
        with tempfile.TemporaryFile() as f:
            f.write(data); f.flush()
            result = b.public_messages(f, after=0, details=True, agent='claude')
        self.assertEqual([m['id'] for m in result['messages']], ['u1'])
        self.assertEqual(result['next'], len(encoded([user('u1', 'one')])))


FAKE = r'''import json,os,sys,tty,uuid
from pathlib import Path
home=Path(os.environ['CLAUDE_CONFIG_DIR']);cwd=sys.argv[1];sid=sys.argv[2];tty.setraw(0)
stat=Path('/proc/self/stat').read_text();start=stat[stat.rfind(')')+2:].split()[19]
(home/'sessions').mkdir(parents=True,exist_ok=True)
(home/'sessions'/(str(os.getpid())+'.json')).write_text(json.dumps({'pid':os.getpid(),'sessionId':sid,'cwd':cwd,'procStart':start,'status':'idle'}))
log=home/'projects'/'-fake'/(sid+'.jsonl');log.parent.mkdir(parents=True,exist_ok=True)
rule='─'*60
def draw(body,cursor):
 os.write(1,b'\x1b[2J\x1b[H'+(rule+'\r\n').encode()+body.encode()+('\r\n'+rule+'\r\n  ? for shortcuts').encode()+cursor.encode())
os.write(1,b'\x1b[?2004h')
draw('❯\xa0\x1b[2mTry "fix lint errors"\x1b[0m','\x1b[2;3H')
data=b''
while True:
 d=os.read(0,16384);data+=d
 if b'\x1b[201~' in data:
  text=data.split(b'\x1b[200~')[-1].split(b'\x1b[201~')[0].decode().replace('\r','\n')
  rows=(text[1:] if text.startswith('!') else text).split('\n')
  draw(('! ' if text.startswith('!') else '❯\xa0')+'\r\n  '.join(rows),'\x1b[%d;%dH'%(1+len(rows),3+len(rows[-1])))
  if data.endswith(b'\r'):
   with log.open('a') as f:
    f.write(json.dumps({'type':'user','uuid':str(uuid.uuid4()),'sessionId':sid,'cwd':cwd,'message':{'role':'user','content':text}})+'\n')
    f.write(json.dumps({'type':'assistant','uuid':str(uuid.uuid4()),'message':{'role':'assistant','content':[{'type':'text','text':'收到'}]}})+'\n')
   data=b'';draw('❯\xa0\x1b[2myes, continue\x1b[0m','\x1b[2;3H')
 elif data and not data.startswith(b'\x1b'):
  draw('❯\xa0'+data.decode(errors='replace'),'\x1b[2;%dH'%(3+len(data)))
'''


@unittest.skipUnless(os.environ.get('NAS_TMUX_TESTS') == '1', 'Set NAS_TMUX_TESTS=1')
class ClaudeTmuxTests(unittest.TestCase):
    def test_first_message_binds_transcript_and_rejects_desktop_draft(self):
        socket = 'nas-claude-test-' + uuid.uuid4().hex
        original = b.run
        def run(*args, **kwargs):
            return original(args[0], '-L', socket, *args[1:], **kwargs)
        with tempfile.TemporaryDirectory(prefix='nas-claude-test-') as tmp, patch.object(b, 'run', run):
            root, sid = Path(tmp), str(uuid.uuid4())
            (root/'claude').symlink_to('/usr/bin/python3')
            (root/'fake.py').write_text(FAKE)
            try:
                run('tmux', '-f', '/dev/null', 'new-session', '-d', '-x', '100', '-y', '20', '-s', 'cc',
                    f'env CLAUDE_CONFIG_DIR={tmp}/home {root}/claude {root}/fake.py {tmp} {sid}')
                pane = None
                for _ in range(50):
                    row = run('tmux', 'display-message', '-p', '-t', 'cc', '#{pane_id}:#{pane_pid}:#{pid}:#{pane_current_command}').strip().split(':')
                    if row[-1] == 'claude' and (root/'home/sessions').is_dir():
                        pane = {'id': row[0], 'pid': int(row[1]), 'serverPid': int(row[2])}
                        break
                    time.sleep(.05)
                self.assertIsNotNone(pane)
                time.sleep(.2)
                self.assertIn('尚未生成记录', self.refusal(lambda: b.handle({'pane': dict(pane), 'action': 'snapshot'})))
                startup = b.handle({'pane': dict(pane), 'action': 'startup_snapshot'})
                self.assertTrue(startup['inputReady'], startup['screen'])
                self.assertEqual(startup['agent'], 'claude')
                sent = b.handle({'pane': dict(pane), 'action': 'startup_send', 'startupToken': startup['startupToken'],
                                 'screenToken': startup['screenToken'], 'text': '第一条\n第二行'})
                self.assertEqual(sent, {'ok': True, 'submitted': True})
                for _ in range(50):
                    try:
                        chat = b.handle({'pane': dict(pane), 'action': 'snapshot', 'screen': True, 'includeSkills': True})
                        break
                    except b.Refused:
                        time.sleep(.05)
                self.assertEqual([m['text'] for m in chat['messages']], ['第一条\n第二行', '收到'])
                self.assertEqual((chat['agent'], chat['threadId'], chat['status'], chat['skillPrefix']), ('claude', sid, 'idle', '/'))
                self.assertTrue(chat['inputReady'], chat['screen'])  # Dim suggestion is not a draft.
                self.assertEqual(b.handle({'pane': dict(pane), 'action': 'send', 'binding': chat['binding'], 'text': '!ls'}),
                                 {'ok': True, 'submitted': True})
                run('tmux', 'send-keys', '-t', pane['id'], '-l', 'typed')
                time.sleep(.2)
                self.assertIn('已有内容', self.refusal(lambda: b.handle({'pane': dict(pane), 'action': 'send',
                                                                          'binding': chat['binding'], 'text': 'x'})))
                self.assertIn('智能体', self.refusal(lambda: b.handle({'pane': dict(pane, agent='codex'), 'action': 'snapshot'})))
            finally:
                subprocess.run(['tmux', '-L', socket, 'kill-server'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def refusal(self, call):
        with self.assertRaises(b.Refused) as caught:
            call()
        return str(caught.exception)
