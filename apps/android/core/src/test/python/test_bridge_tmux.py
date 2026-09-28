"""Opt-in real tmux/proc integration using fake Codex; independent private server."""
import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from test_bridge import b


@unittest.skipUnless(os.environ.get('NAS_TMUX_TESTS') == '1', 'Set NAS_TMUX_TESTS=1')
class TmuxBridgeTests(unittest.TestCase):
    def test_new_codex_menu_and_first_message_without_rollout(self):
        socket = 'nas-startup-test-' + uuid.uuid4().hex
        original = b.run
        def run(*args, **kwargs):
            return original(args[0], '-L', socket, *args[1:], **kwargs)
        with tempfile.TemporaryDirectory(prefix='nas-startup-test-') as tmp, patch.object(b, 'run', run):
            root = Path(tmp)
            (root/'codex').symlink_to('/usr/bin/python3')
            (root/'fake.py').write_text('''import os,sys,tty
from pathlib import Path
root=Path(sys.argv[1]);tty.setraw(0)
os.write(1,b'\\x1b[?2004h\\x1b[2J\\x1b[HTrust this directory?\\n> Yes\\n  No\\x1b[2;1H')
data=b'';menu=True
while True:
 d=os.read(0,16384);data+=d
 (root/'received').write_bytes(data)
 if menu and b'\\r' in data:
  menu=False;os.write(1,b'\\x1b[2J\\x1b[H'+'› Ask Codex to do anything'.encode()+b'\\x1b[1;3H')
 elif not menu and b'\\x1b[201~' in data:
  text=data.split(b'\\x1b[200~')[-1].split(b'\\x1b[201~')[0]
  os.write(1,b'\\x1b[2J\\x1b[H'+'› '.encode()+text)
  if data.endswith(b'\\r'):
   (root/'submitted').write_bytes(text)
''')
            try:
                run('tmux', '-f', '/dev/null', 'new-session', '-d', '-x', '120', '-y', '30', '-s', 'startup',
                    str(root/'codex') + ' ' + str(root/'fake.py') + ' ' + tmp)
                pane = None
                for _ in range(50):
                    row = run('tmux','display-message','-p','-t','startup','#{pane_id}:#{pane_pid}:#{pid}:#{pane_current_command}').strip().split(':')
                    if row[-1] == 'codex':
                        pane = {'id': row[0], 'pid': int(row[1]), 'serverPid': int(row[2])}
                        break
                    time.sleep(.05)
                self.assertIsNotNone(pane)
                menu = None
                for _ in range(50):
                    menu = b.handle({'pane': pane, 'action': 'startup_snapshot'})
                    if 'Trust this directory?' in menu['screen']: break
                    time.sleep(.05)
                self.assertIn('Trust this directory?', menu['screen'])
                self.assertFalse(menu['inputReady'])
                self.assertTrue(b.handle({'pane': pane, 'action': 'startup_key', 'startupToken': menu['startupToken'],
                                          'screenToken': menu['screenToken'], 'key': 'Enter'})['ok'])
                ready = None
                for _ in range(50):
                    ready = b.handle({'pane': pane, 'action': 'startup_snapshot'})
                    if ready['inputReady']: break
                    time.sleep(.05)
                self.assertTrue(ready['inputReady'])
                text = '首条消息 $(true)'
                result = b.handle({'pane': pane, 'action': 'startup_send', 'startupToken': ready['startupToken'],
                                   'screenToken': ready['screenToken'], 'text': text})
                self.assertTrue(result.get('submitted'), (result, run('tmux','capture-pane','-p','-t',pane['id']),
                                                          run('tmux','display-message','-p','-t',pane['id'],'#{cursor_x}:#{cursor_y}')))
                for _ in range(50):
                    if (root/'submitted').exists(): break
                    time.sleep(.05)
                self.assertEqual((root/'submitted').read_bytes(), text.encode())
            finally:
                try: run('tmux','kill-server')
                except Exception: pass

    def test_exact_open_file_binding_and_one_send(self):
        socket = 'nas-bridge-test-' + uuid.uuid4().hex
        original = b.run
        def run(*args, **kwargs):
            return original(args[0], '-L', socket, *args[1:], **kwargs)
        with tempfile.TemporaryDirectory(prefix='nas-bridge-test-') as tmp, patch.object(b, 'run', run):
            root = Path(tmp)
            (root/'codex').symlink_to('/usr/bin/python3')
            (root/'fake.py').write_text('''import json,os,sys,tty
from pathlib import Path
root=Path(sys.argv[1]); ident=sys.argv[2]
f=(root/('rollout-'+ident+'.jsonl')).open('w+')
f.write(json.dumps({'type':'session_meta','payload':{'id':ident,'cwd':str(root)}})+'\\n'); f.flush()
tty.setraw(0)
os.write(1,b'\\x1b[?2004h\\x1b[2J\\x1b[H'+ '› Ask Codex to do anything'.encode()+b'\\x1b[1;3H')
received=b''
while True:
 d=os.read(0,16384);received+=d
 (root/('received-'+ident)).write_bytes(received)
 if b'\\x1b[201~' in received:
  text=received.split(b'\\x1b[200~')[-1].split(b'\\x1b[201~')[0]
  os.write(1,b'\\x1b[2J\\x1b[H'+'› '.encode()+text)
''')
            try:
                for name in ('first', 'second'):
                    run('tmux', '-f', '/dev/null', 'new-session', '-d', '-x', '120', '-y', '30', '-s', name,
                        str(root/'codex') + ' ' + str(root/'fake.py') + ' ' + tmp + ' ' + name)
                for _ in range(50):
                    if (root/'rollout-first.jsonl').exists() and (root/'rollout-second.jsonl').exists(): break
                    time.sleep(.05)
                row = run('tmux','display-message','-p','-t','first','#{pane_id}:#{pane_pid}:#{pid}').strip().split(':')
                pane={'id':row[0],'pid':int(row[1]),'serverPid':int(row[2])}
                binding, stream, meta = b.locate(pane); stream.close()
                self.assertEqual(meta['id'], 'first')  # Same cwd; newest file is irrelevant.
                request={'pane':pane,'binding':binding,'action':'send','text':'中文 $(touch SHOULD_NOT_EXIST)'}
                self.assertTrue(b.handle(request)['submitted'])
                time.sleep(.1)
                received=(root/'received-first').read_bytes()
                self.assertEqual(received, b'\x1b[200~' + request['text'].encode() + b'\x1b[201~\r')
                for key, sequence in [('S-Left', b'\x1b[1;2D'), ('M-Down', b'\x1b[1;3B'), ('BTab', b'\x1b[Z'), ('C-]', b'\x1d')]:
                    snapshot = b.handle({'pane': pane, 'action': 'snapshot', 'screen': True})
                    result = b.handle({'pane': pane, 'binding': binding, 'action': 'key', 'key': key,
                                       'screenToken': snapshot['screenToken']})
                    self.assertTrue(result['ok'])
                    time.sleep(.05)
                    received += sequence
                    self.assertEqual((root/'received-first').read_bytes(), received)
                self.assertFalse((root/'received-second').exists())
                self.assertFalse((root/'SHOULD_NOT_EXIST').exists())
                with self.assertRaises(b.Refused): b.handle(dict(request, binding='wrong'))
                self.assertNotIn('nasremote-', run('tmux', 'list-buffers'))
            finally:
                try: run('tmux','kill-server')
                except Exception: pass
