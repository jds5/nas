"""Incremental recovery and interactive input safety; synthetic data only."""
import io
import tempfile
import unittest
from unittest.mock import patch
from test_bridge import b, encoded, record


class RecoveryTests(unittest.TestCase):
    def test_forward_chunks_keep_every_message(self):
        data = encoded([record('AgentMessage', str(i), '中' * 60) for i in range(180)])
        for limit in (1024, b.MAX_READ):
            with tempfile.TemporaryFile() as f, patch.object(b, 'MAX_READ', limit):
                f.write(data); f.flush()
                cursor, found = 0, []
                for _ in range(200):
                    result = b.public_messages(f, after=cursor, details=True)
                    found += [m['id'] for m in result['messages']]
                    self.assertGreater(result['next'], cursor)
                    cursor = result['next']
                    if not result['more']: break
                self.assertEqual(found, [str(i) for i in range(180)])
                self.assertEqual(cursor, len(data))

    def test_incomplete_record_is_read_after_append_without_duplicates(self):
        first = encoded([record('AgentMessage', 'first')])
        second = encoded([record('AgentMessage', 'second', '中文')])
        with tempfile.TemporaryFile() as f:
            f.write(first + second[:-3]); f.flush()
            result = b.public_messages(f, after=0, details=True)
            self.assertEqual(result['next'], len(first))
            f.seek(0, 2); f.write(second[-3:]); f.flush()
            result = b.public_messages(f, after=result['next'], details=True)
            self.assertEqual([m['id'] for m in result['messages']], ['second'])
            self.assertEqual(result['next'], len(first + second))
            self.assertFalse(b.public_messages(f, after=result['next'], details=True)['more'])

    def test_forward_bounds_and_truncation_fail_closed(self):
        with tempfile.TemporaryFile() as f:
            f.write(b'{}\n'); f.flush()
            for cursor in (-1, True, 4):
                with self.assertRaises(b.Refused): b.public_messages(f, after=cursor, details=True)
            f.seek(0); f.write(b'a' * 1024); f.flush()
            with patch.object(b, 'MAX_READ', 1024), self.assertRaises(b.Refused):
                b.public_messages(f, after=0, details=True)

    def test_question_token_tracks_question_not_background_spinner(self):
        question = '• Queued follow-up inputs\nQuestion\n› 1. Yes\n  2. No\nenter submit'
        self.assertEqual(b.screen_token('Working 1s\n' + question), b.screen_token('Working 2s\n' + question))
        self.assertNotEqual(b.screen_token(question), b.screen_token(question.replace('Question', 'Different question')))
        self.assertNotEqual(b.screen_token(question), b.screen_token(question.replace('› 1.', '  1.')))

    def operate(self, action, *, screen='question', expected='question', fail=None, **fields):
        calls = []
        def run(*args, **kwargs):
            calls.append((args, kwargs))
            if args[1] == fail: raise b.Refused('transport failure')
            return screen if args[1] == 'capture-pane' else ''
        with patch.object(b, 'target', return_value={'id': '%1'}), \
             patch.object(b, 'locate', side_effect=lambda _: ('binding', io.BytesIO(), {'id': 'test'})), \
             patch.object(b, 'run', side_effect=run):
            result = b.handle(dict(action=action, binding='binding', screenToken=b.screen_token(expected), **fields))
        return result, calls

    def test_answer_only_pastes_literal_text_without_enter(self):
        text = '中文 $(touch forbidden)\n第二行'
        result, calls = self.operate('answer', text=text)
        self.assertTrue(result['ok'])
        self.assertFalse(any(a[1] == 'send-keys' for a, _ in calls))
        self.assertEqual([k['data'] for _, k in calls if 'data' in k], [text.encode()])
        self.assertFalse(any(text in a for a, _ in calls))
        self.assertTrue(any(a[1] == 'delete-buffer' for a, _ in calls))

    def test_stale_question_and_unknown_key_refused(self):
        with self.assertRaises(b.Refused): self.operate('key', screen='next question', key='Enter')
        with self.assertRaises(b.Refused): self.operate('key', key='Enter; touch bad')
        with self.assertRaises(b.Refused): self.operate('answer', text='\x1b[31m')

    def test_interactive_transport_failure_is_uncertain(self):
        for action, fail, fields in [('key', 'send-keys', {'key': 'S-Left'}), ('answer', 'paste-buffer', {'text': 'hi'})]:
            result, _ = self.operate(action, fail=fail, **fields)
            self.assertTrue(result['uncertain'])
            self.assertFalse(result['ok'])
        with self.assertRaises(b.Refused): self.operate('answer', text='hi', fail='load-buffer')
