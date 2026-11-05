#!/bin/sh
# OWNER-RUN ONLY; public records only, no secret stdin on legacy PTY.
set -eu
umask 077
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec /usr/bin/env python3 "$ROOT/scripts/public_bootstrap.py"
