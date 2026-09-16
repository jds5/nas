"""Synthetic fixtures only. Python 3.9+; no SSH, production tmux, or real session writes.
Run: python3 -m unittest discover -s apps/android/core/src/test/python -v
"""
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('bridge', Path(__file__).parents[2] / 'main/resources/nas_remote_bridge.py')
b = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(b)


def record(kind, ident, text='hello', **kwargs):
    return {'type': 'event_msg', 'payload': {'type': 'item_completed', 'item': {
        'type': kind, 'id': ident, 'content': [{'type': 'Text', 'text': text}], **kwargs}}}


def encoded(records):
    return b''.join(json.dumps(r, ensure_ascii=False).encode() + b'\n' for r in records)


class BridgeTests(unittest.TestCase):
    def read(self, data, before=None):
        with tempfile.TemporaryFile() as f:
            f.write(data); f.flush()
            return b.public_messages(f, before)

    def test_only_public_messages_and_explicit_user_shell(self):
        data = encoded([record('Reasoning', 'r', 'PRIVATE'), record('CommandExecution', 't', 'PRIVATE', source='agent'),
                        record('AgentMessage', 'a', 'reply'), record('UserMessage', 'u', 'question'),
                        record('AgentMessage', 'x', 'PRIVATE', phase='analysis'),
                        record('CommandExecution', 'c', source='user_shell', command=['bash', '-lc', 'id'], aggregated_output='uid=1000', exit_code=0),
                        {'type': 'response_item', 'payload': {'role': 'system', 'content': 'PRIVATE'}}])
        messages, _, _ = self.read(data)
        self.assertEqual([m['id'] for m in messages], ['a', 'u', 'c'])
        self.assertNotIn('PRIVATE', str(messages))
        self.assertIn('uid=1000', messages[-1]['text'])

    def test_duplicate_text_retains_distinct_ids(self):
        messages, _, _ = self.read(encoded([record('AgentMessage', '1'), record('AgentMessage', '2')]))
        self.assertEqual(len(messages), 2)

    def test_incomplete_last_record_and_control_filter(self):
        data = encoded([record('AgentMessage', '1', 'text\x1b\u202e')]) + b'{"unfinished":'
        messages, _, _ = self.read(data)
        self.assertEqual(messages[0]['text'], 'text')

    def test_byte_pagination_does_not_lose_boundary_message(self):
        data = encoded([record('AgentMessage', str(i), 'a' * 200) for i in range(30)])
        with patch.object(b, 'MAX_READ', 1024):
            before, found = None, []
            for _ in range(50):
                messages, _, before = self.read(data, before)
                found = [m['id'] for m in messages] + found
                if before == 0: break
        self.assertEqual(found, [str(i) for i in range(30)])

    def test_count_and_byte_limits_preserve_pagination(self):
        for size in (10, 20000):
            data = encoded([record('AgentMessage', str(i), '中' * size) for i in range(85)])
            before, found = None, []
            for _ in range(100):
                messages, _, before = self.read(data, before)
                self.assertLessEqual(len(json.dumps(messages, ensure_ascii=False).encode()), 180000)
                found = [m['id'] for m in messages] + found
                if before == 0: break
            self.assertEqual(found, [str(i) for i in range(85)])

    def test_invalid_cursor(self):
        for cursor in (-1, True, 10000):
            with self.assertRaises(b.Refused): self.read(b'{}\n', cursor)

    def test_status_comes_from_events_not_text(self):
        records = [record('AgentMessage', '1', 'task complete'), {'type': 'event_msg', 'payload': {'type': 'task_started'}}]
        self.assertEqual(self.read(encoded(records))[1], 'working')

    def test_target_rejects_injection_before_tmux(self):
        for ident in ('%1;touch /tmp/unsafe', '-a', '%1\n'):
            with patch.object(b, 'run') as run:
                with self.assertRaises(b.Refused): b.target({'pane': {'id': ident, 'pid': 1, 'serverPid': 2}})
                run.assert_not_called()

    def test_target_replaced(self):
        with patch.object(b, 'run', return_value='1:2:0:bash'):
            with self.assertRaises(b.Refused): b.target({'pane': {'id': '%1', 'pid': 1, 'serverPid': 2}})

    def test_draft_and_menu_refuse_before_paste(self):
        for position, screen in [('5:0', '› existing draft'), ('2:0', '› 1. Approve'), ('2:0', 'Menu')]:
            with patch.object(b, 'run', side_effect=[position, screen]):
                with self.assertRaises(b.Refused): b.composer({'id': '%1'}, True)

    def test_multiline_wrapped_and_collapsed_composer(self):
        for text, position, screen in [
            ('中文\n第二行', '8:1', '› 中文\n  第二行'),
            ('a' * 1500, '29:0', '› [Pasted Content 1500 chars]'),
            ('hello world', '7:1', '› hello\n  world'),
            ('!id', '4:0', '! id'),
        ]:
            with patch.object(b, 'run', side_effect=[position, screen]):
                b.composer({'id': '%1'}, False, text)
        with patch.object(b, 'run', side_effect=['29:0', '› [Pasted Content 1499 chars]']):
            with self.assertRaises(b.Refused): b.composer({'id': '%1'}, False, 'a' * 1500)

    def test_single_send_and_control_rejection(self):
        pane = {'id': '%1', 'pid': 1, 'serverPid': 2}
        calls = []
        with patch.object(b, 'run', side_effect=lambda *a, **k: calls.append((a, k)) or ''), \
             patch.object(b, 'target', return_value=pane), patch.object(b, 'composer'), \
             patch.object(b, 'locate', side_effect=lambda _: ('binding', io.BytesIO(), {})), \
             patch.object(b.subprocess, 'run'), patch.object(b.time, 'sleep'):
            text = '中文\n$(touch /tmp/unsafe)'
            self.assertTrue(b.deliver({'text': text}, pane, 'binding')['submitted'])
            self.assertEqual(sum(a[-1] == 'Enter' for a, _ in calls), 1)
            self.assertFalse(any(text in a for a, _ in calls))
            self.assertEqual(calls[0][1]['data'], text.encode())
            for text in ('\x03', '!id\nls', '/status\nhello'):
                with self.assertRaises(b.Refused): b.deliver({'text': text}, pane, 'binding')

    def test_session_change_after_paste_is_uncertain_without_enter(self):
        calls = []
        with patch.object(b, 'run', side_effect=lambda *a, **k: calls.append(a) or ''), \
             patch.object(b, 'target'), patch.object(b, 'composer'), \
             patch.object(b, 'locate', side_effect=[('binding', io.BytesIO(), {}), ('changed', io.BytesIO(), {})]), \
             patch.object(b.subprocess, 'run'), patch.object(b.time, 'sleep'):
            result = b.deliver({'text': 'hello'}, {'id': '%1'}, 'binding')
            self.assertTrue(result['uncertain'])
            self.assertFalse(any(a[-1] == 'Enter' for a in calls))


if __name__ == '__main__':
    unittest.main()
