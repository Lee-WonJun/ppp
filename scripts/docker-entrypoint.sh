#!/bin/sh
set -eu

umask 077
mkdir -p "${PPP_DATA_DIR:-/var/lib/ppp}/.native"
exec "$@"
