#!/usr/bin/env bash
# Start the Chess Backend Server on port 6700
cd "$(dirname "$0")"
echo "[*] Launching Chess Server on port 6700..."
java -cp bin server.ServerMain
