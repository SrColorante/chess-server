# chess-server

> Featured on **[cristianrenosto.party/projects](https://cristianrenosto.party/projects)**

Multiplayer chess game server and clients written in Java.

A TCP chess server with a **rules engine written from scratch** (no chess library),
a **terminal client**, and a **Swing desktop client**. Two players per game, over a
plain-text line protocol.

> **This is a pure Java project.** Despite the `package.json` and `node_modules/`
> in the repository root, nothing in it is used by the build or the server — see
> [Why is there a package.json?](#why-is-there-a-packagejson).

---

## Requirements

| Component | Version |
|---|---|
| JDK | 11 or newer (uses `String.isBlank`, `List.of`, `String.repeat`) |
| Build tool | None — plain `javac` |
| Database | Optional. PostgreSQL only for accounts; guest games need none |

The jars in `lib/` are vendored in the repository. No download step exists.

## Build

```bash
./build.sh
```

That is exactly:

```bash
mkdir -p bin
javac -d bin -cp "lib/postgresql.jar:lib/flatlaf.jar" \
      src/server/*.java src/client/*.java src/game/*.java
```

**The `-cp` is required.** `src/client/ClientGUI.java` imports
`com.formdev.flatlaf.FlatDarkLaf`, so compiling without `lib/flatlaf.jar` on the
classpath fails. `lib/postgresql.jar` is only needed at *runtime* by the server,
but including it at compile time is harmless and keeps the build one command.

`build.sh` does not compile `tests/` — use `run-tests.sh` for that.

## Run

### Server

```bash
./start-server.sh
```

Must be run from the repository root (the script does not `cd`). Listens on
**port 6700** by default and needs `bin/` to be built first.

### Terminal client

```bash
./start-client.sh                       # localhost:6700
./start-client.sh HOST PORT
./start-client.sh HOST PORT --compact --no-animations
./ start-client.sh HOST PORT --no-color --no-unicode --no-emoji
./start-client.sh HOST PORT --timeout-ms=45000
```

Supported flags:

| Flag | Effect |
|---|---|
| `--compact` | Narrow-terminal layout |
| `--no-color` | Disable ANSI colour (alias: `--no-ansi`) |
| `--no-unicode` | ASCII instead of box-drawing characters |
| `--no-emoji` | ASCII instead of emoji |
| `--no-animations` | Disable the connection animation |
| `--timeout-ms=<1000..120000>` | Socket connect/read timeout (default 30000) |
| `-h`, `--help` | Usage text |

> **Known bug:** the terminal client currently **cannot render the board**. The
> server sends the compact single-line `BOARD:` frame, but `ClientMain` still
> routes it to the legacy multi-line parser, so every frame raises
> `Invalid board payload: Expected BOARD header`. The Swing GUI is unaffected.
> See [`docs.md`](./docs.md) §11.

### Swing GUI client

```bash
./start-gui.sh
```

Requires a display. The script sets Linux/X11-specific workarounds
(`GDK_BACKEND=x11`, `-Dsun.java2d.xrender=false`).

Override the target with environment variables:

```bash
CHESS_HOST=example.com CHESS_PORT=6700 ./start-gui.sh
```

Keyboard shortcuts in-game: `Ctrl+F` flip board, `Ctrl+R` resign, `Esc` clear
selection.

## Tests

```bash
./run-tests.sh
```

Compiles sources *and* tests, then runs the suite. The `-ea` flag is mandatory —
every assertion is a bare `java.lang.assert`.

```bash
java -ea -cp bin:lib/flatlaf.jar tests.RunOne chess   # chess|match|network|client|room
```

`run-tests.sh` runs all six suites with **fail-fast**; `RunOne` runs a single
suite and prints a filtered stack trace without exiting.

Two suites (`TestAccountHandler`, `TestNetworkIntegration`) **skip themselves** if
`DB_URL` is unset or the PostgreSQL driver is unavailable. The other four need
neither a database nor a network.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/chess` | JDBC URL (accounts only) |
| `DB_USER` | `postgres` | Database user |
| `DB_PASS` | `postgres` | Database password |

`start-server.sh` loads `.env.local` if present and maps `DATABASE_URL` → `DB_URL`.
The server starts and serves guest games **without any database**; only
login/register fail.

Schema (created idempotently at startup):

```sql
chess_users(username VARCHAR(24) PRIMARY KEY,
            salt VARCHAR(64)  NOT NULL,
            password_hash VARCHAR(128) NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
```

Passwords are hashed with PBKDF2-HMAC-SHA256, 120 000 iterations, per-user salt.

## Project layout

```
src/game/ChessGame.java        Rules engine, move generation, FEN, perft (1403 lines)
src/server/ServerMain.java     Entry point, accept loop, auth + lobby phases
src/server/Server.java         Immutable config holder, DEFAULT_PORT = 6700
src/server/ConnectionHandler.java  Per-room game loop, keep-alive, teardown
src/server/ServerRoomHandler.java  Room registry, 6-char join codes
src/server/ServerAccountHandler.java  PostgreSQL accounts + PBKDF2
src/client/ClientMain.java     Terminal client
src/client/ClientGUI.java      Swing client (1067 lines)
src/client/BoardPanel.java     Custom-painted chessboard
src/client/…                   Protocol parser, CLI config, terminal theme/UI
tests/                         6 suites, hand-rolled runner
lib/                           postgresql.jar, flatlaf.jar, Comfortaa.ttf
```

## Protocol at a glance

Raw TCP, newline-delimited, `KEY:payload` plain text. No HTTP, no WebSocket, no
handshake token.

```
client → server   LOGIN:user:pass   REGISTER:user:pass   GUEST:nick
                  LIST_ROOMS        CREATE_ROOM:name:<private>
                  JOIN_ROOM:CODE    MOVE:from:to          RESIGN
                  SET_MODE:X        SET_COLOR:X           SET_TIMER:N
                  PING              PONG

server → client   WELCOME / AUTH_OK / AUTH_ERROR / INFO / ERROR
                  ROOMS_LIST:<n>;CODE|NAME|HOST;…
                  ROOM_CREATED:<name>:<code>:<private>
                  JOIN_OK / JOIN_ERROR
                  MATCH_START / ASSIGNED_COLOR:WHITE|BLACK
                  BOARD:<71 chars><w|b>      ← 78 chars, one line
                  MOVE_OK / MOVE_ERROR / GAME_OVER
```

Full message reference in [`docs.md`](./docs.md) §5.

## Notable rules-engine details

- Full legal-move generation with check, mate, stalemate, castling, en passant
- **Perft** validated against published values 20 / 400 / 8902 / 197281
- Threefold repetition via 64-bit **Zobrist hashing** with a fixed seed, so keys
  are identical across runs and machines
- Castling rights derived from both move flags *and* board occupancy — fixes a
  class of bogus FEN
- Single-swing `kingSquare` cache makes check detection O(1)

## Deployment

Railway, via Nixpacks:

- `nixpacks.toml` installs a JDK, runs `build.sh`, starts with `start-server.sh`
- `railway.json` selects the Nixpacks builder, `ON_FAILURE` restart policy
- **The JDK version is unpinned** — the code needs ≥ 11
- `lib/*.jar` must stay committed; no fetch step is declared

## Why is there a package.json?

`package.json` (8 lines, no `scripts`), `package-lock.json`, `node_modules/`,
`neon.ts` and `skills-lock.json` are **Neon platform scaffolding, not application
dependencies**. No application code imports them; `neon.ts` is a 3-line
`defineConfig({})`. The app talks to PostgreSQL through the JDBC driver in
`lib/postgresql.jar`.

## Known issues

1. **Terminal client cannot render the board** (see above) — `start-client.sh` is
   therefore not a usable way to play.
2. **The GUI promotion dialog is ignored.** It offers Queen/Rook/Bishop/Knight,
   then discards the choice; the engine always promotes to queen.
3. **Disconnecting loses the game.** A dropped connection or `Ctrl-C` counts as a
   resignation, with no grace period or reconnect.
4. **`SET_MODE` is cosmetic.** `STANDARD`/`RAPID`/`BLITZ` is stored and echoed
   back, but never affects the clock or scoring. Only `SET_TIMER` matters.
5. **A joining guest never sees the timer.** The server never broadcasts the
   configured limit, so the guest's clock stays at 0 and shows no countdown.
6. **Traffic is plaintext.** Passwords and moves cross the wire in the clear
   unless `-Dchess.server.tls=true` is passed — and no script passes it. No
   keystore is configured anywhere, so TLS is not usable as shipped.
7. **Game state is in-memory only.** A restart destroys every room and game.
   Only accounts are persisted.
8. **`resources/`-style runtime artefacts are not ignored**: `server.log`,
   `gui-error.log` and `server.pid` sit in the working tree.

## Documentation

| File | Content |
|---|---|
| [`docs.md`](./docs.md) | Protocol reference, engine internals, concurrency model, deployment, test suite detail |
