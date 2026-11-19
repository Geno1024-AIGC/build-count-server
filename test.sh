#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out/test
mkdir -p out/test

JAVAC_MAJOR=$(javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')
if [ "${JAVAC_MAJOR:-0}" -ge 9 ]; then
    RELEASE="--release 8"
else
    RELEASE=""
fi

javac $RELEASE -Xlint:-options -d out/test \
    src/main/java/com/geno1024/aigc/buildcount/*.java \
    src/test/java/com/geno1024/aigc/buildcount/*.java
java -cp out/test com.geno1024.ai.buildcount.SelfTest
