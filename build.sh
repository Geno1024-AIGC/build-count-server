#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out/main
mkdir -p out/main
javac --add-modules jdk.httpserver -d out/main src/main/java/com/geno1024/aigc/buildcount/*.java
