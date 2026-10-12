#!/bin/sh
set -eu
if ! command -v python3 >/dev/null 2>&1; then
    echo 'Python 3.10+ is required. Install python3, then run this command again.' >&2
    exit 1
fi
exec python3 "$(dirname "$0")/setup.py" "$@"
