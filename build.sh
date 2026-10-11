#!/usr/bin/env bash
# Compiles main and test sources with plain javac (JDK 21, no dependencies).
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir -p out/main out/test
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -Xlint:all -d out/main $(find src/main -name '*.java')
"${JAVAC[@]}" -Xlint:all -d out/test -cp out/main $(find src/test -name '*.java')
echo "build ok"
