#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out/test
mkdir -p out/test
javac --add-modules jdk.httpserver -d out/test \
    src/main/java/com/geno1024/aigc/buildcount/*.java \
    src/test/java/com/geno1024/aigc/buildcount/*.java
java --add-modules jdk.httpserver -cp out/test com.geno1024.ai.buildcount.SelfTest
