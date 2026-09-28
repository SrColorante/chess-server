#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p bin
javac -d bin src/game/*.java src/server/*.java src/client/*.java tests/*.java
java -ea -cp bin tests.TestRunner
