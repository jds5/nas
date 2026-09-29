"""Run: python3 -m unittest discover -s apps/web-terminal/test -p 'test_*.py'."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('reader', Path(__file__).resolve().parents[1] / 'host/read_conversation.py')
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


def record(text, kind='AgentMessage', phase='final_answer'):
    return {'type': 'event_msg', 'payload': {'type': 'item_completed', 'item': {'type': kind, 'phase': phase, 'content': [{'type': 'text', 'text': text}]}}}


def stream(events, tail=b''):
    f = tempfile.TemporaryFile()
    for event in events:
        f.write(json.dumps(event, ensure_ascii=False).encode() + b'\n')
    f.write(tail); f.flush()
    return f


class ConversationTests(unittest.TestCase):
    def test_long_table_and_code_not_clipped(self):
        text = '| 列1 | 列2 |\n| --- | --- |\n' + '| 数据 | 很长的说明 |\n' * 5000
        with stream([record(text)]) as f:
            page = r.read_page(f)
        self.assertEqual(page['messages'][0]['text'], text)
        self.assertFalse(page['skipped'])

    def test_only_public_messages_and_readable_replies(self):
        reply = '<send_user_message_question_reply>' + json.dumps([{'answer': '继续', 'question': '是否继续？', 'questionItemId': 'secret-id'}]) + '</send_user_message_question_reply>'
        events = [record('PUBLIC'), record('PRIVATE', phase='analysis'), record('PRIVATE', kind='Reasoning'),
                  {'type': 'response_item', 'payload': {'role': 'system', 'content': 'PRIVATE'}}, record(reply, kind='UserMessage')]
        with stream(events) as f:
            page = r.read_page(f)
        self.assertEqual(len(page['messages']), 2)
        self.assertNotIn('PRIVATE', str(page))
        self.assertNotIn('questionItemId', str(page))
        self.assertEqual(page['messages'][-1]['text'], '回答「是否继续？」：继续')

    def test_pagination_no_duplicates_or_missing_messages(self):
        events = [record(str(i) + 'x' * 12000) for i in range(400)]
        with stream(events, b'{"partial":') as f:
            before, pages = None, []
            for _ in range(40):
                page = r.read_page(f, before)
                pages.insert(0, page['messages'])
                if not page['hasOlder']: break
                self.assertTrue(before is None or page['before'] < before)
                before = page['before']
        self.assertEqual([m['text'] for page in pages for m in page], [e['payload']['item']['content'][0]['text'] for e in events])

    def test_oversized_item_is_explicit_and_invalid_cursor_rejected(self):
        with stream([record('x' * (r.MAX_PAGE + 10))]) as f:
            self.assertTrue(r.read_page(f)['skipped'])
            for cursor in [-1, True, 999999999]:
                with self.assertRaises(r.b.Refused): r.read_page(f, cursor)

    def test_binding_and_pane_switch_rejected(self):
        pane = {'id': '%1', 'pid': 10, 'serverPid': 20}
        with stream([record('PUBLIC')]) as f, patch.object(r, 'identity', return_value=pane), patch.object(r.b, 'locate', return_value=('a'*64, f, {})):
            with self.assertRaises(r.b.Refused): r.handle({'ref': {}, 'binding': 'b'*64, 'before': 0})
        with stream([record('PUBLIC')]) as f, patch.object(r, 'identity', side_effect=[pane, dict(pane, id='%2')]), patch.object(r.b, 'locate', return_value=('a'*64, f, {})):
            with self.assertRaises(r.b.Refused): r.handle({'ref': {}})
        with self.assertRaises(r.b.Refused): r.handle({'ref': {}, 'path': '/etc/passwd'})


if __name__ == '__main__': unittest.main()
