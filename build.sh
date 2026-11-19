#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out/main
mkdir -p out/main

# Compile for Java 8 so the classes also run on 8/11/17/21. JDK 9+ uses
# --release; a JDK 8 javac has no such flag and already targets 8.
JAVAC_MAJOR=$(javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')
if [ "${JAVAC_MAJOR:-0}" -ge 9 ]; then
    RELEASE="--release 8"
else
    RELEASE=""
fi

javac $RELEASE -Xlint:-options -d out/main \
    src/main/java/com/geno1024/aigc/buildcount/*.java
