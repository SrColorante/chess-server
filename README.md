# chess-server

Multiplayer chess game server and client written in Java.

## Build

```bash
mkdir -p bin
javac -d bin src/game/*.java src/server/*.java src/client/*.java tests/*.java
```

### 2. Start the server
```bash
./start-server.sh
```

### 3. Start a client
```bash
./start-client.sh
./start-client.sh HOST PORT
./start-client.sh HOST PORT --compact --no-animations
./start-client.sh HOST PORT --no-color --no-unicode --no-emoji
./start-client.sh HOST PORT --timeout-ms=45000
```

Supported client flags:
- `--compact` for narrow terminals
- `--no-color`, `--no-unicode`, `--no-emoji` for accessibility fallback
- `--no-animations` to disable connection animation
- `--timeout-ms=<1000..120000>` for socket connect/read timeout

### 4. Run tests
```bash
./run-tests.sh
# or
java -ea -cp bin tests.TestRunner
```

## UI Preview (terminal)

```text
