#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
exec java --add-modules jdk.httpserver -cp out/main com.geno1024.ai.buildcount.Main "$@"
