"""Synthetic daemon responses; no production socket or conversation writes."""
import base64
import hashlib
import json
import os
import socket
import struct
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from test_bridge import b, encoded, record


class DaemonBridgeTests(unittest.TestCase):
    def reader(self, origins):
        def rpc(method, params):
            if method == 'thread/loaded/list':
                return {'data': list(origins), 'nextCursor': None}
            if method == 'mcpServerStatus/list':
                return {'data': [{'name': 'codex_tui', 'httpOrigin': origins[params['threadId']],
                                  'runtimeStatus': 'connected'}]}
            return {'thread': {'id': params['threadId'], 'cwd': '/same/directory'}}
        return Mock(rpc=Mock(side_effect=rpc))

    def test_matches_process_owned_endpoint_not_shared_directory(self):
        reader = self.reader({'one': 'http://127.0.0.1:10001', 'two': 'http://127.0.0.1:10002'})
        self.assertEqual(b.read_tui_thread(reader, {'http://127.0.0.1:10002'})['id'], 'two')
        self.assertTrue(all(call.args[0] in ('thread/loaded/list', 'mcpServerStatus/list', 'thread/read')
                            for call in reader.rpc.call_args_list))

    def test_ambiguous_missing_and_truncated_associations_refused(self):
        for origins in ({}, {'a': 'other'}, {'a': 'same', 'b': 'same'}):
            with self.assertRaises(b.Refused):
                b.read_tui_thread(self.reader(origins), {'same'})
        reader = Mock(rpc=Mock(return_value={'data': [], 'nextCursor': 'more'}))
        with self.assertRaises(b.Refused):
            b.read_tui_thread(reader, {'same'})

    def test_daemon_rollout_read_and_binding_follow_thread_and_backend(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / 'sessions').mkdir()
            path = home / 'sessions/rollout-test.jsonl'
            path.write_bytes(encoded([{'type': 'session_meta', 'payload': {'id': 'thread-one'}},
                                      record('AgentMessage', 'reply', '已有回复')]))
            thread = {'id': 'thread-one', 'path': str(path), '_home': home, '_backend': '123:456'}
            pane = {'id': '%1', 'pid': os.getpid(), 'serverPid': 1}
            with patch.object(b, 'process_info', return_value=(1, '100', 'codex')), \
                    patch.object(b, 'daemon_thread', return_value=thread):
                binding, stream, _ = b.locate(pane)
                with stream:
                    self.assertEqual(b.public_messages(stream)[0][0]['text'], '已有回复')
                with self.assertRaises(b.Refused):
                    b.startup_identity(pane)
                thread['_backend'] = '999:789'
                changed, stream, _ = b.locate(pane)
                stream.close()
                self.assertNotEqual(binding, changed)
                thread['id'] = 'different-thread'
                with self.assertRaises(b.Refused):
                    b.locate(pane)
                thread['path'] = str(home / 'outside.jsonl')
                with self.assertRaises(b.Refused):
                    b.locate(pane)

    def test_daemon_unavailable_cannot_be_misreported_as_new(self):
        pane = {'id': '%1', 'pid': os.getpid(), 'serverPid': 1}
        with patch.object(b, 'process_info', return_value=(1, '100', 'codex')), \
                patch.object(b, 'daemon_thread', side_effect=b.Refused('unavailable')):
            with self.assertRaises(b.Refused):
                b.startup_identity(pane)

    def test_allocated_path_without_first_turn_is_distinct_from_missing_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / 'sessions').mkdir()
            path = home / 'sessions/rollout-new.jsonl'
            thread = {'path': str(path), 'preview': '', 'status': {'type': 'idle'}, 'createdAt': 101}
            self.assertTrue(b.unrecorded_thread(thread, home, 100))
            for fields in ({'createdAt': 50}, {'preview': 'existing message'}, {'status': {'type': 'active'}},
                           {'ephemeral': True}, {'path': None}):
                self.assertFalse(b.unrecorded_thread(dict(thread, **fields), home, 100))
            path.touch()
            self.assertFalse(b.unrecorded_thread(thread, home, 100))

    def test_websocket_handshake_masking_fragments_and_read_only_methods(self):
        errors = []
        with tempfile.TemporaryDirectory() as tmp:
            path = str(Path(tmp) / 'daemon.sock')
            server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            server.bind(path)
            server.listen(1)
            server.settimeout(3)
            def serve():
                try:
                    conn, _ = server.accept()
                    with conn:
                        conn.settimeout(3)
                        def take(n):
                            data = b''
                            while len(data) < n:
                                chunk = conn.recv(n - len(data))
                                if not chunk: raise EOFError()
                                data += chunk
                            return data
                        header = b''
                        while not header.endswith(b'\r\n\r\n'): header += take(1)
                        key = next(line.split(b':', 1)[1].strip() for line in header.split(b'\r\n')
                                   if line.lower().startswith(b'sec-websocket-key:'))
                        accept = base64.b64encode(hashlib.sha1(key + b'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest())
                        conn.sendall(b'HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: ' + accept + b'\r\n\r\n')
                        for _ in range(3):
                            flags, length = take(2)
                            assert flags == 129 and length & 128
                            length &= 127
                            if length == 126: length = struct.unpack('!H', take(2))[0]
                            mask = take(4)
                            message = json.loads(bytes(v ^ mask[i % 4] for i, v in enumerate(take(length))))
                            if 'id' not in message: continue
                            result = {} if message['method'] == 'initialize' else {'data': ['one']}
                            data = json.dumps({'id': message['id'], 'result': result}).encode()
                            conn.sendall(bytes([1, 5]) + data[:5] + bytes([128, len(data) - 5]) + data[5:])
                except Exception as exc: errors.append(exc)
            worker = threading.Thread(target=serve)
            worker.start()
            try:
                reader = b.DaemonReader(path, os.getpid())
                try:
                    self.assertEqual(reader.rpc('thread/loaded/list', {})['data'], ['one'])
                    with self.assertRaises(b.Refused): reader.rpc('turn/start', {})
                finally: reader.close()
            finally:
                worker.join(4)
                server.close()
            self.assertFalse(worker.is_alive())
            self.assertEqual(errors, [])

    def test_websocket_rejects_oversized_response_before_reading_body(self):
        reader = b.DaemonReader.__new__(b.DaemonReader)
        reader.serial = 0
        reader.send = Mock()
        reader.read = Mock(side_effect=[bytes([129, 127]), struct.pack('!Q', 5 * b.MAX_READ)])
        with self.assertRaises(b.Refused): reader.rpc('thread/read', {'threadId': 'one'})
        self.assertEqual(reader.read.call_count, 2)

    def test_loopback_endpoint_owned_by_process_and_wrong_daemon_peer_rejected(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            origin = 'http://127.0.0.1:' + str(listener.getsockname()[1])
            self.assertIn(origin, b.tui_origins(os.getpid()))
        self.assertNotIn(origin, b.tui_origins(os.getpid()))
        with tempfile.TemporaryDirectory() as tmp, socket.socket(socket.AF_UNIX) as server:
            path = str(Path(tmp) / 'daemon.sock')
            server.bind(path)
            server.listen(1)
            with self.assertRaises(b.Refused):
                b.DaemonReader(path, os.getpid() + 1)
