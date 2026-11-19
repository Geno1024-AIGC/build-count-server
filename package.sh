#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
mkdir -p out
rm -f out/build-count-server.jar
jar cfe out/build-count-server.jar com.geno1024.ai.buildcount.Main -C out/main .
echo "built: out/build-count-server.jar"
