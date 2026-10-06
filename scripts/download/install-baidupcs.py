#!/usr/bin/env python3
"""在 ynas 为 yao 首次安装 BaiduPCS-Go；用途、依赖、回滚见运维记录。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import zipfile

VERSION = '4.0.2'
DOWNLOAD = Path('/opt/nas/cache/baidupcs-downloads')
USER_HOME = Path('/home/yao')
CONFIG = USER_HOME / '.config/BaiduPCS-Go'
RELEASE = USER_HOME / '.local/share/baidupcs-go' / VERSION
ENTRY = USER_HOME / '.local/bin/BaiduPCS-Go'


def require(ok, message):
    if not ok:
        raise SystemExit(message)


def output(*args):
    return subprocess.check_output(args, text=True).strip()


def preflight():
    require(platform.node() == 'ynas' and platform.machine() == 'x86_64',
            '仅适用于已核对的 ynas x86_64。')
    require(output('id', '-un') == 'yao' and Path.home() == USER_HOME,
            '请以 yao 普通用户运行，不要 sudo 整个安装脚本。')
    for tool in ['curl', 'git', 'findmnt', 'lsblk', 'install']:
        require(shutil.which(tool), f'缺少依赖：{tool}')
    require(Path('/opt/nas/cache').resolve() == Path('/opt/nas/cache'),
            'cache 路径不能是符号链接。')
    source = output('findmnt', '-n', '-o', 'SOURCE', '-T', '/opt/nas/cache')
    require(source == '/dev/nvme0n1p3', f'挂载变化，需重新核验：{source}')
    disks = json.loads(output('lsblk', '-dJn', '-o', 'NAME,ROTA'))['blockdevices']
    require(any(d['name'] == 'nvme0n1' and not d['rota'] for d in disks),
            '目标未识别为 SSD。')
    backup = Path('/opt/nas/scripts/restic-backup.sh').read_text()
    require(re.search(r'^\s*--exclude /opt/nas/cache\s*\\\s*$', backup, re.M),
            'restic 的 cache 排除项不存在，停止。')
    subprocess.run(['git', '-C', '/opt/nas', 'check-ignore', '-q',
                    'cache/baidupcs-downloads/probe.bin'], check=True)
    for name in ['restic-mirror.sh', 'restic-onedrive.sh']:
        text = (Path('/opt/nas/scripts') / name).read_text()
        require('SOURCE="/mnt/critical-mirror"' in text,
                f'{name} 的备份源变化，需要人工复核。')
    for path in [DOWNLOAD, CONFIG, RELEASE, ENTRY]:
        require(not path.exists() and not path.is_symlink(),
                f'目标已存在，停止以保留原文件：{path}')
    require(shutil.which('BaiduPCS-Go') is None, '已有 BaiduPCS-Go，请先核对旧安装。')
    print(f'只读检查通过：{DOWNLOAD} 位于 {source}，现有 restic/Git 均排除 cache。')


def fetch(url, destination):
    subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error',
                    '--proto', '=https', '--proto-redir', '=https',
                    '--connect-timeout', '15', '--max-time', '300', '--retry', '2',
                    '--output', str(destination), url], check=True)


def install():
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='baidupcs-install-') as stage:
        stage = Path(stage)
        url = (f'https://github.com/qjfoidnh/BaiduPCS-Go/releases/download/v{VERSION}/'
               f'BaiduPCS-Go-v{VERSION}-linux-amd64.zip')
        # 来自上游 v4.0.2 发布资产页面，固定摘要以避免同标签资产被替换。
        digest = 'sha256:b5f51388b510433668ca22fa5a1cb8840fb4d9725c5edc830554645010a62339'
        fetch(url, stage / 'release.zip')
        require(hashlib.sha256((stage / 'release.zip').read_bytes()).hexdigest() == digest[7:],
                '发布包 SHA-256 不匹配。')
        with zipfile.ZipFile(stage / 'release.zip') as archive:
            candidates = [i for i in archive.infolist()
                          if not i.is_dir() and Path(i.filename).name == 'BaiduPCS-Go']
            require(len(candidates) == 1, '压缩包内程序不唯一。')
            (stage / 'BaiduPCS-Go').write_bytes(archive.read(candidates[0]))
        (stage / 'BaiduPCS-Go').chmod(0o700)
        # 所有运行验证使用临时配置，不碰用户已有配置。
        env = dict(os.environ, BAIDUPCS_GO_CONFIG_DIR=str(stage / 'config'))
        (stage / 'config').mkdir(mode=0o700)
        version = subprocess.check_output([str(stage / 'BaiduPCS-Go'), '--version'],
                                          env=env, text=True)
        require(VERSION in version, '程序报告版本不匹配。')
        subprocess.run([str(stage / 'BaiduPCS-Go'), 'config', 'set',
                        '-savedir', str(DOWNLOAD), '-max_parallel', '1',
                        '-max_download_load', '1'], env=env, check=True)
        # 新装不覆盖任何配置；仅专用下载目录需要管理员创建。
        preflight()
        subprocess.run(['sudo', 'install', '-d', '-m', '700', '-o', 'yao',
                        '-g', output('id', '-gn'), str(DOWNLOAD)], check=True)
        require(DOWNLOAD.resolve() == DOWNLOAD and DOWNLOAD.stat().st_uid == os.getuid(),
                '下载目录身份不匹配。')
        require(output('findmnt', '-n', '-o', 'SOURCE', '-T', str(DOWNLOAD)) == '/dev/nvme0n1p3',
                '下载目录不在已核对的 SSD 上。')
        RELEASE.mkdir(parents=True, mode=0o700)
        shutil.copy2(stage / 'BaiduPCS-Go', RELEASE / 'BaiduPCS-Go')
        CONFIG.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(stage / 'config', CONFIG)
        ENTRY.parent.mkdir(parents=True, exist_ok=True)
        # 固定配置目录与工作目录，避免从仓库启动时使用错误配置或默认落盘位置。
        wrapper = ('#!/bin/sh\nset -eu\numask 077\n'
                   f'export BAIDUPCS_GO_CONFIG_DIR={CONFIG}\n'
                   f'cd {DOWNLOAD}\n'
                   f'exec {RELEASE}/BaiduPCS-Go "$@"\n')
        with ENTRY.open('x') as handle:
            handle.write(wrapper)
        ENTRY.chmod(0o700)
        (RELEASE / 'source.json').write_text(json.dumps(
            {'version': VERSION, 'url': url, 'digest': digest}, indent=2) + '\n')
        subprocess.run([str(ENTRY), '--version'], check=True)
        subprocess.run([str(ENTRY), 'config'], check=True)
        print(f'安装完成，默认下载目录：{DOWNLOAD}。尚未登录或下载。')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='只读检查路径及备份排除，不下载或安装')
    args = parser.parse_args()
    preflight()
    if not args.check:
        install()
