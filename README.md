# chess-server

Multiplayer chess game server and client written in Java, built with a Dual-Tier Orchestration architecture (Gemini CLI as Architect + GitHub Copilot as Worker).

## Architecture

```
chess-server/
├── src/
│   ├── server/
│   │   ├── Server.java              # Server descriptor (port, config, handlers)
│   │   ├── ServerMain.java          # Accepts connections on port 6700 → routes to ConnectionHandler
│   │   ├── ServerAccountHandler.java # SHA-256 account management (register/login/delete)
│   │   ├── ServerRoomHandler.java   # Public/private room management + unique 6-char codes
│   │   └── ConnectionHandler.java   # Room coordinator:
│   │       ├── (4a) RoomSettings    # Game mode, timer, color preference
│   │       ├── (4b) GameHandler     # Chess match lifecycle (start, move, end)
│   │       └── (4c) Keep-Alive      # Ping/pong heartbeat + self-destruct on finish
│   ├── game/
│   │   └── ChessGame.java          # Full chess engine (all 6 piece types + board rendering)
│   └── client/
│       └── ClientMain.java          # Interactive console client (auth, room selection, gameplay)
├── tests/
│   ├── TestAccountHandler.java
│   ├── TestRoomHandler.java
│   ├── TestChessGame.java
│   ├── TestNetworkIntegration.java  # End-to-end: Host creates room → Guest joins → Move → Resign
│   └── TestRunner.java
├── start-server.sh
├── start-client.sh
└── run-tests.sh
```

## How to Run

### 1. Compile
```bash
mkdir -p bin
javac -d bin src/game/*.java src/server/*.java src/client/*.java tests/*.java
```

### 2. Start the Server (port 6700)
```bash
./start-server.sh
```

### 3. Start a Client
```bash
./start-client.sh           # connects to localhost:6700
./start-client.sh HOST PORT # connects to custom host/port
```

### 4. Run All Tests
```bash
./run-tests.sh
```

## Game Flow

1. **Client 1 (Host)** connects → registers/login/guest → creates room (public or private)
2. Server generates a **6-character unique room code**
3. **Client 2 (Guest)** connects → auth → joins via public list or private code
4. `ConnectionHandler` initializes the **GameHandler** with chosen colors
5. Players exchange moves via `MOVE:e2:e4` protocol
6. Keep-alive **PING/PONG** every 5s (timeout after 25s)
7. On game end: results broadcast, connection self-destructs, room is removed

## Client Commands (in-game)

| Command | Effect |
|---|---|
| `e2 e4` or `e2e4` | Move piece from e2 to e4 |
| `resign` | Forfeit the match |
| `SET_MODE:BLITZ` | (lobby only) Set game mode |
| `SET_COLOR:BLACK` | (lobby only) Host plays as Black |
| `SET_TIMER:30` | (lobby only) Set 30s turn limit |

## Features

- ♟ Full chess rules: Pawn (with 2-step initial and diagonal capture), Knight, Bishop, Rook, Queen, King
- 🔐 SHA-256 password hashing (no plaintext credentials ever stored)
- 🌐 Concurrent multi-room support via thread pool
- 💓 Keep-alive heartbeat (self-healing disconnection detection)
- 🧨 Self-destruct on match end (room cleanup, socket close)
- 👤 Guest mode (no registration required)
- 🏠 Public room listing + Private rooms by code
- 📋 ASCII board rendering live in console
