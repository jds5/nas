"""Lifecycle validation and isolated tmux/Git integration; never uses the default tmux socket."""
import importlib.util
import json
import os
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location('lifecycle', Path(__file__).parents[2] / 'main/resources/nas_remote_lifecycle.py')
lifecycle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lifecycle)


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='nas-lifecycle-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.code = self.root / 'code'
        self.code.mkdir()
        self.socket = 'nas-life-' + uuid.uuid4().hex
        self.env = patch.dict(os.environ, {'NAS_REMOTE_TEST_TMUX_SOCKET': self.socket})
        self.env.start()
        self.addCleanup(self.env.stop)
        for attr, value in [('HOME', self.root), ('PROJECTS', self.code), ('STATE', self.root / 'state')]:
            p = patch.object(lifecycle, attr, value)
            p.start()
            self.addCleanup(p.stop)
        self.addCleanup(lambda: subprocess.run(['tmux', '-L', self.socket, 'kill-server'],
                                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))

    def git(self, cwd, *args):
        return subprocess.check_output(['git', *args], cwd=cwd, stderr=subprocess.DEVNULL).decode().strip()

    def await_status(self, operation, wanted):
        for _ in range(100):
            result = lifecycle.handle({'action': 'inspect', 'operation': operation})
            if result['status'] in wanted:
                return result
            time.sleep(.05)
        self.fail('后台 Git 操作未结束')

    def test_empty_server_and_reject_untrusted_paths_and_urls(self):
        self.assertEqual(lifecycle.current_panes(), [])
        outside = self.root / 'outside'
        outside.mkdir()
        with self.assertRaises(lifecycle.Refused):
            lifecycle.project(str(outside))
        for url in ('file:///tmp/repo', 'ext::sh -c evil', 'https://token@example.org/repo', '-c evil'):
            with self.assertRaises(lifecycle.Refused):
                lifecycle.git_url(url)
        self.assertEqual(lifecycle.git_url('git@example.org:owner/repo.git'), 'git@example.org:owner/repo.git')

    @unittest.skipUnless(os.environ.get('NAS_TMUX_TESTS') == '1', 'Set NAS_TMUX_TESTS=1')
    def test_new_session_starts_once_and_resume_uses_exact_uuid(self):
        project = self.code / 'demo'
        project.mkdir()
        bin_dir = self.root / 'bin'
        bin_dir.mkdir()
        fake = bin_dir / 'codex'
        fake.write_text('#!/bin/sh\nsleep 5\n')
        fake.chmod(0o755)
        with patch.dict(os.environ, {'PATH': str(bin_dir) + ':' + os.environ['PATH']}):
            request = {'action': 'prepare', 'mode': 'existing', 'session': 'demo', 'path': str(project)}
            preview = lifecycle.handle(request)
            operation = str(uuid.uuid4())
            result = lifecycle.handle(dict(request, action='start', operation=operation, digest=preview['digest']))
            self.assertEqual(result['status'], 'started', result.get('error'))
            self.assertEqual(lifecycle.handle(dict(request, action='start', operation=operation))['pane'], result['pane'])
            self.assertEqual(len(lifecycle.current_panes()), 1)

        ident = str(uuid.uuid4())
        sessions = self.root / '.codex' / 'sessions' / '2026' / '09' / '28'
        sessions.mkdir(parents=True)
        (sessions / ('rollout-test-' + ident + '.jsonl')).write_text(json.dumps({
            'type': 'session_meta', 'timestamp': '2026-09-28T00:00:00Z',
            'payload': {'id': ident, 'cwd': str(project)}}) + '\n')
        request = {'action': 'prepare', 'mode': 'restore_new', 'session': 'recovered', 'thread': ident}
        preview = lifecycle.handle(request)
        self.assertEqual(preview['proposal']['thread'], ident)
        self.assertEqual(preview['proposal']['path'], str(project))
        args_file = self.root / 'codex-args'
        fake.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > ' + str(args_file) + '\nsleep 5\n')
        with patch.dict(os.environ, {'PATH': str(bin_dir) + ':' + os.environ['PATH']}):
            result = lifecycle.handle(dict(request, action='start', operation=str(uuid.uuid4()), digest=preview['digest']))
        self.assertEqual(result['status'], 'started', result.get('error'))
        for _ in range(30):
            if args_file.exists():
                break
            time.sleep(.05)
        self.assertEqual(args_file.read_text().splitlines(), ['resume', ident])

    @unittest.skipUnless(os.environ.get('NAS_TMUX_TESTS') == '1', 'Set NAS_TMUX_TESTS=1')
    def test_clone_and_fast_forward_pull_use_temp_repository(self):
        source = self.root / 'source'
        source.mkdir()
        self.git(source, 'init')
        self.git(source, 'config', 'user.name', 'Test')
        self.git(source, 'config', 'user.email', 'test@example.org')
        (source / 'README').write_text('one')
        self.git(source, 'add', '.')
        self.git(source, 'commit', '-m', 'one')
        bare = self.root / 'remote.git'
        self.git(self.root, 'clone', '--bare', str(source), str(bare))
        target = self.code / 'cloned'
        with patch.object(lifecycle, 'git_url', lambda value: value):
            request = {'action': 'prepare', 'mode': 'clone', 'session': 'music', 'path': str(target), 'url': str(bare)}
            preview = lifecycle.handle(request)
            operation = str(uuid.uuid4())
            lifecycle.handle(dict(request, action='start', operation=operation, digest=preview['digest']))
            result = self.await_status(operation, ('cloned', 'failed'))
            self.assertEqual(result['status'], 'cloned', result.get('error'))
            self.assertEqual((target / 'README').read_text(), 'one')

            (source / 'README').write_text('two')
            self.git(source, 'add', '.')
            self.git(source, 'commit', '-m', 'two')
            self.git(source, 'remote', 'add', 'origin', str(bare))
            self.git(source, 'push', 'origin', 'master')
            request = {'action': 'prepare', 'mode': 'existing', 'session': 'updated', 'path': str(target), 'pull': True}
            preview = lifecycle.handle(request)
            operation = str(uuid.uuid4())
            lifecycle.handle(dict(request, action='start', operation=operation, digest=preview['digest']))
            result = self.await_status(operation, ('pulled', 'failed'))
            self.assertEqual(result['status'], 'pulled', result.get('error'))
            self.assertEqual((target / 'README').read_text(), 'two')

            (target / 'local.txt').write_text('keep')
            with self.assertRaises(lifecycle.Refused):
                lifecycle.handle(dict(request, action='prepare'))
