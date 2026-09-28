#!/usr/bin/env bash
# Start the Interactive Chess Client
cd "$(dirname "$0")"
java -cp bin client.ClientMain "$@"
