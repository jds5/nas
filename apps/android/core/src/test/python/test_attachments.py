"""Private attachment storage: arbitrary names never become paths or commands."""
import hashlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from test_bridge import b


class AttachmentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        self.patches = [patch.object(b.Path, 'home', return_value=self.home),
                        patch.object(b, 'target'),
                        patch.object(b, 'locate', side_effect=lambda _: ('bound', io.BytesIO(), {}))]
        for p in self.patches: p.start()
        self.data = b'\x89PNG\r\n\x1a\n' + b'image test' * 100
        self.item = dict(id='a'*32, sha256=hashlib.sha256(self.data).hexdigest(), size=len(self.data),
                         name='photo $(touch forbidden).png', image=True)

    def tearDown(self):
        for p in reversed(self.patches): p.stop()
        self.temp.cleanup()

    def upload(self, data=None, item=None):
        return b.upload_attachment({'attachment': item or self.item}, {}, 'bound', io.BytesIO(self.data if data is None else data))

    def test_upload_verified_private_and_idempotent(self):
        self.assertTrue(self.upload()['ok'])
        self.assertTrue(self.upload()['ok'])
        root = self.home / '.nas-remote-uploads'
        self.assertEqual(root.stat().st_mode & 0o777, 0o700)
        files = list(root.iterdir())
        self.assertEqual(len(files), 1)
        self.assertEqual(files[0].stat().st_mode & 0o777, 0o600)
        self.assertEqual(files[0].read_bytes(), self.data)
        text = b.attachment_message({'text': '请分析', 'attachments': [self.item]}, 'bound')
        self.assertIn(str(files[0]), text)
        self.assertIn('"type": "image"', text)
        self.assertFalse((self.home / 'forbidden').exists())

    def test_partial_extra_and_wrong_hash_leave_no_file(self):
        for data in [self.data[:-1], self.data+b'x', b'x'*len(self.data)]:
            with self.assertRaises(b.Refused): self.upload(data)
            self.assertEqual(list((self.home / '.nas-remote-uploads').iterdir()), [])

    def test_changed_executor_removes_partial(self):
        with patch.object(b, 'locate', return_value=('replacement', io.BytesIO(), {})):
            with self.assertRaises(b.Refused): self.upload()
        self.assertEqual(list((self.home / '.nas-remote-uploads').iterdir()), [])

    def test_path_traversal_limits_and_invalid_digest(self):
        for changes in [dict(id='../elsewhere'), dict(size=True), dict(size=0), dict(size=b.MAX_ATTACHMENT+1), dict(sha256='bad')]:
            with self.assertRaises(b.Refused): self.upload(item=self.item | changes)
        self.assertFalse((self.home / '.nas-remote-uploads').exists())

    def test_root_symlink_or_unsafe_permissions_rejected(self):
        root = self.home / '.nas-remote-uploads'
        other = self.home / 'other'; other.mkdir()
        root.symlink_to(other)
        with self.assertRaises(OSError): self.upload()
        root.unlink(); root.mkdir(mode=0o755)
        with self.assertRaises(b.Refused): self.upload()

    def test_modified_file_symlink_and_cross_session_not_sent(self):
        self.upload()
        root = self.home / '.nas-remote-uploads'
        file = next(root.iterdir())
        request = {'attachments': [self.item]}
        with self.assertRaises(FileNotFoundError): b.attachment_message(request, 'other-session')
        file.write_bytes(b'x'*len(self.data))
        with self.assertRaises(b.Refused): b.attachment_message(request, 'bound')
        file.unlink(); file.symlink_to('/etc/passwd')
        with self.assertRaises(OSError): b.attachment_message(request, 'bound')

    def test_message_limits_command_mixing_and_names(self):
        self.upload()
        for request in [dict(text='!ls', attachments=[self.item]), dict(text='/help', attachments=[self.item]),
                        dict(attachments=[self.item]*5), dict(attachments=[self.item | dict(name='a\nb')])]:
            with self.assertRaises(b.Refused): b.attachment_message(request, 'bound')
        with patch.object(b, 'UPLOAD_QUOTA', 10):
            with self.assertRaises(b.Refused): self.upload(item=self.item | dict(id='b'*32))
        self.assertEqual(len(list((self.home / '.nas-remote-uploads').iterdir())), 1)

    def test_upload_never_sends_terminal_input(self):
        with patch.object(b, 'run') as run:
            result = b.handle({'binding': 'bound', 'action': 'upload', 'attachment': self.item}, io.BytesIO(self.data))
        self.assertTrue(result['ok'])
        run.assert_not_called()
