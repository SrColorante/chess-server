#!/usr/bin/env bash
# Compile and run all tests with gh-orchestrator verification
cd "$(dirname "$0")"
javac -d bin src/game/*.java src/server/*.java src/client/*.java tests/*.java && \
gh-orchestrator verify \
  --test-cmd "java -ea -cp bin tests.TestRunner" \
  --file src/server/ConnectionHandler.java \
  --lang "Java" \
  --repair-output repair_prompt.txt
