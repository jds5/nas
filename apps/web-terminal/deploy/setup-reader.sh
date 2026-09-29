#!/bin/sh
# Install versioned read-only history helper for the local SSH user.
# Dependencies: Python 3.9+, tmux, existing SSH transport; run as yao from any cwd.
# No service restart, Codex input, credentials, or configuration changes.
set -eu
base=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
dest="$HOME/.local/share/nas-web-terminal/reader-0.5.0"
mkdir -p "$dest"
chmod 700 "$dest"
install -m 600 "$base/host/read_conversation.py" "$dest/read_conversation.py"
install -m 600 "$base/../android/core/src/main/resources/nas_remote_bridge.py" "$dest/nas_remote_bridge.py"
python3 -m py_compile "$dest/read_conversation.py" "$dest/nas_remote_bridge.py"
printf 'Installed read-only conversation helper: %s\n' "$dest"
