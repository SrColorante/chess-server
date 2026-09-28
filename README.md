# chess-server

Multiplayer chess game server and client written in Java.

## Architecture

```
chess-server/
├── src/
│   ├── server/
│   │   ├── Server.java
│   │   ├── ServerMain.java
│   │   ├── ServerAccountHandler.java
│   │   ├── ServerRoomHandler.java
│   │   └── ConnectionHandler.java
│   ├── game/
│   │   └── ChessGame.java
│   └── client/
│       ├── ClientMain.java
│       ├── ClientConfig.java
│       ├── ClientProtocolParser.java
│       ├── TerminalTheme.java
│       ├── TerminalUI.java
│       └── LineListener.java
├── tests/
│   ├── TestAccountHandler.java
│   ├── TestRoomHandler.java
│   ├── TestChessGame.java
│   ├── TestNetworkIntegration.java
│   ├── TestClientComponents.java
│   └── TestRunner.java
├── start-server.sh
├── start-client.sh
└── run-tests.sh
```

## Build and Run

### 1. Compile
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
==========================================================
                 CHESS MULTIPLAYER CLIENT 💠
==========================================================
╭─ SESSION
[HOST] localhost
[PORT] 6700
[MODE] STANDARD
╰

╭─ AUTH
 1) Login
 2) Register new account
 3) Play as Guest
 4) Exit
╰
▶ Select option [1-4]:
```

The client keeps protocol compatibility with server messages (`AUTH_*`, `ROOMS_LIST`, `ROOM_CREATED`, `JOIN_*`, `BOARD`, `MOVE_*`, `PING/PONG`, `GAME_OVER`) while adding clearer hierarchy, status badges, concise feedback and synchronized output between listener/input loops.

## Client Commands (in-game)

| Command | Effect |
|---|---|
| `e2 e4` or `e2e4` | Move piece from e2 to e4 |
| `resign` | Forfeit the match |
| `SET_MODE:BLITZ` | (lobby only) Set game mode |
| `SET_COLOR:BLACK` | (lobby only) Host plays as Black |
| `SET_TIMER:30` | (lobby only) Set 30s turn limit |
