#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p bin

# Il driver PostgreSQL e FlatLaf servono anche in fase di test: senza il
# classpath la suite non compilava.
CP="lib/postgresql.jar:lib/flatlaf.jar"

javac -d bin -cp "$CP" src/game/*.java src/server/*.java src/client/*.java tests/*.java
java -ea -cp "bin:$CP" tests.TestRunner
