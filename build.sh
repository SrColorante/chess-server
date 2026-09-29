#!/bin/bash
mkdir -p bin
javac -d bin -cp "lib/postgresql.jar:lib/flatlaf.jar" src/server/*.java src/client/*.java src/game/*.java
