#!/bin/sh
# Root-owned forced command. No shell expansion of the requested command.
exec /usr/bin/sudo -n /usr/local/sbin/orbitstorage-deploy "${SSH_ORIGINAL_COMMAND:-}"
