#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
[ -d out/main ] || ./build.sh
exec java -cp out/main com.agentic.Main "$@"
