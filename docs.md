# chess-server — Technical Documentation

In-depth reference for **chess-server**: a TCP chess server with a hand-written
rules engine, a terminal client, and a Swing client.

> **Snapshot:** commit `9a30136`, branch `master`, fresh single-commit clone.
> For a quick overview see [`README.md`](./README.md).

---

## Table of contents

1. [Purpose and scope](#1-purpose-and-scope)
2. [Tech stack](#2-tech-stack)
3. [Source layout](#3-source-layout)
4. [Protocol reference](#4-protocol-reference)
5. [Server internals](#5-server-internals)
6. [Game logic](#6-game-logic)
7. [Clients](#7-clients)
8. [Database and secrets](#8-database-and-secrets)
9. [Build, run, test](#9-build-run-test)
10. [Deployment](#10-deployment)
11. [Bugs and limitations](#11-bugs-and-limitations)
12. [Appendix](#12-appendix)

---

## 1. Purpose and scope

### 1.1 What it is

A two-player online chess server plus **two** graphical front-ends (a terminal UI
and a Swing desktop UI).

### 1.2 What it is not

There is **no AI engine**: no search, no evaluation function, no minimax. "Engine"
in this project means only the rules and move-generation core. There is also no
matchmaking in the competitive sense — only a **lobby** where players list public
rooms or join by a 6-character code. No ratings, no ELO, no automatic pairing.

### 1.3 Capability matrix

| Capability | Status | Where |
|---|---|---|
| Authoritative game hosting | ✅ | `ServerMain`, `ConnectionHandler`, `ChessGame` |
| Full legal-move rules | ✅ hand-written | `ChessGame.java` (1403 lines) |
| AI / search | ❌ | — |
| Lobby (browse + join by code) | ✅ | `ServerRoomHandler` |
| Automatic matchmaking, ratings | ❌ | — |
| Terminal client | ✅ (board rendering broken, §11.1) | `ClientMain` |
| Swing client | ✅ | `ClientGUI` (1067 lines), `BoardPanel` |
| Accounts | ✅ PostgreSQL only | `ServerAccountHandler` |

### 1.4 Who connects, and how

Two peers per game, over a **raw TCP socket** speaking a **newline-delimited
plain-text line protocol** — no HTTP, no WebSocket, no framing headers, no
post-handshake token. Default port **6700**, hard-coded server-side
(`Server.DEFAULT_PORT`); the Swing client honours `CHESS_HOST` / `CHESS_PORT`.

A third-party client is roughly 100 lines of `readLine`/`println`.

---

## 2. Tech stack

### 2.1 Language and build

- **Plain Java**, compiled with `javac`. No Maven, no Gradle, no Makefile.
- **No `--release`/`-source`/`-target`** anywhere, so the bytecode level is
  whatever JDK compiles it.
- **Effective minimum Java 11**, from: `String.isBlank()` (`ChessGame.java:1303`),
  `List.of()` (`GuiSnapshot.java:65`), `String.repeat()` (`TerminalTheme.java:37`).
- No annotations, no records, no sealed types, no `var`.
- No linter, no formatter, no static analysis, no CI.

### 2.2 `package.json` — scaffolding, not dependencies

```json
{
  "dependencies": { "neon": "^6.3.0" },
  "devDependencies": { "@neon/config": "^1.8.2" }
}
```

8 lines, **no `name`**, **no `version`**, **no `scripts`**.

| Package | Purpose | Used by app code? |
|---|---|---|
| `neon` | Neon serverless Postgres client SDK (HTTP/WebSocket over `pg`) | **No** — zero imports |
| `@neon/config` | `defineConfig` used by `neon.ts`, plus the `neon` CLI | Only by the 3-line `neon.ts` |

`package-lock.json` (v3) pulls a large transitive tree (`hono`, `pg`,
`openid-client`, `zod`, `add-mcp`, `yargs`, …). None of it participates in the
build. The whole `node_modules/` tree is installer residue.

### 2.3 Binary dependencies (`lib/`, vendored in-repo)

| File | Role | Needed at | Version |
|---|---|---|---|
| `lib/postgresql.jar` | PostgreSQL JDBC driver | **Runtime**, server only — loaded reflectively via `Class.forName("org.postgresql.Driver")` (`ServerAccountHandler.java:36`); no compile-time import | not recorded anywhere |
| `lib/flatlaf.jar` | FlatLaf dark Swing LAF | **Compile + runtime**, GUI only (`ClientGUI.java:3`) | not recorded anywhere |
| `lib/Comfortaa.ttf` | Google font, registered if present | GUI only (`ClientGUI.java:1036-1041`), wrapped in `try/catch` | — |

**Jar versions are unrecorded.** There is no `pom.xml`, no version file and no
MANIFEST extraction anywhere, so upgrading requires manual replacement.

### 2.4 Test framework

**None.** Hand-rolled: a static `tests.TestRunner` calls each suite's
`public static void run()`. Assertions are **bare `java.lang.assert`**, which is
why `-ea` is mandatory. `TestRunner` is fail-fast (catches `AssertionError`,
`System.exit(1)`); `tests.RunOne` runs a single suite and prints a filtered stack
trace without exiting.

### 2.5 `skills-lock.json`

A lockfile for **AI agent "skills"** — 8 entries, all from
`neondatabase/agent-skills` on GitHub, each with a SHA-256 `computedHash`:
`neon`, `neon-ai-gateway`, `neon-auth`, `neon-functions`, `neon-object-storage`,
`neon-postgres`, `neon-postgres-branches`, `neon-postgres-egress-optimizer`.

The payloads live in `.agents/skills/<name>/SKILL.md` (30 markdown files of Neon
platform documentation). None of it is used by the build or the server; it exists
because someone ran Neon's skill installer — which also explains the `add-mcp`
dependency in `node_modules`.

---

## 3. Source layout

### 3.1 `src/game/` — rules engine

| File | Lines | Responsibility |
|---|---|---|
| `ChessGame.java` | **1403** | The entire rules engine, move generator, FEN I/O, and perft. One class, no interfaces. |

Public API:

- **Nested types** — `enum Color {WHITE, BLACK}` (`.opposite()`);
  `enum PieceType {PAWN,ROOK,KNIGHT,BISHOP,QUEEN,KING}` (`.getSymbol()`,
  `.fromSymbol(char)`); `class Piece` (immutable; `.getType()`, `.getColor()`);
  `class MoveResult` (`.isSuccess()`, `.getMessage()`);
  `final class Move` (public fields `fromR/fromF/toR/toF/promotion/enPassant/castle`;
  `.fromSquare()`, `.toSquare()`); private `class AppliedMove`; private
  `class PerftUndo`.
- **Moves** — `makeMove(from, to, playerColor)`, `generateLegalMoves(color)`,
  `legalMovesFrom(square)`, `resign(color)`, `perft(depth)`.
- **State** — `getCurrentTurn()`, `isGameOver()`, `getWinner()`, `getEndReason()`,
  `isCheck()`, `getKingSquare(color)`.
- **Serialisation** — `toFEN()`, `loadFEN(String)`, `renderBoardCompact()`,
  `static parseBoardCompact(String)`, `renderBoard()` (dead, §11.3),
  `static squareName(file, rankIndex)`.

**Every instance method is `synchronized`** — one instance is shared by the two
player threads of a game.

### 3.2 `src/server/` — networking

| File | Lines | Responsibility |
|---|---|---|
| `ServerMain.java` | 274 | `main()`, TCP accept loop, thread pool, auth phase, lobby phase, room dispatch |
| `ConnectionHandler.java` | 577 | Per-room `Runnable`: lobby loop, keep-alive, two player listeners, broadcast, teardown. Inner `RoomSettings` + `GameHandler` |
| `ServerAccountHandler.java` | 142 | PostgreSQL account CRUD + PBKDF2 hashing |
| `ServerRoomHandler.java` | 137 | Room registry: two `ConcurrentHashMap`s, 6-char code generation, public listing |
| `Server.java` | 34 | Immutable config holder: `DEFAULT_PORT = 6700`, server name, handler singletons |

Public API: `Server` (`getPort`, `getServerName`, `getAccountHandler`,
`getRoomHandler`); `ServerRoomHandler` (`createRoom`, `getPublicRooms`,
`getRoomByCode`, `getRoomById`, `removeRoom`, `RoomStatus {WAITING,PLAYING,FINISHED}`);
`ConnectionHandler` (`getSettings`, `getGameHandler`, `attachGuest`,
`selfDestruct`); `ServerAccountHandler` (`register`, `login`, `deleteAccount` —
all returning `boolean`).

The server package couples to the game package **only** through `ChessGame` and
its nested types — `ConnectionHandler.GameHandler` directly owns one `ChessGame`
instance. There is no interface between them.

### 3.3 `src/client/` — two front-ends

| File | Lines | Responsibility |
|---|---|---|
| `ClientGUI.java` | **1067** | Swing client: 4 `CardLayout` screens (AUTH/LOBBY/SETTINGS/GAME), socket, protocol dispatch, click-to-move, turn clock |
| `BoardPanel.java` | 412 | Hand-painted `JComponent` board: custom `paintComponent`, arithmetic hit-testing, flip, highlights, check ring, move dots/rings, coordinate gutters |
| `ClientMain.java` | 327 | Terminal client: connect → auth menu → room menu → move loop. Owns a `LineListener` for server pushes |
| `ClientProtocolParser.java` | 133 | Static parsers for `ROOM_CREATED:`, `ROOMS_LIST:`, and the legacy multi-line `BOARD:` block |
| `ClientConfig.java` | 130 | CLI flag/positional parsing, validation, `usage()` text |
| `TerminalUI.java` | 94 | Synchronised `PrintStream` wrapper with prompt-restore (`activePrompt`) so async server events don't clobber the input line |
| `TerminalTheme.java` | 91 | ANSI/256-colour palette + unicode/emoji/compact fallbacks |
| `GuiSnapshot.java` | 76 | Offscreen renderer writing `01-auth.png` … `05-game-flipped.png` |
| `LineListener.java` | 61 | Daemon thread pumping a `BufferedReader` into a callback, with `stop()`/`awaitStop(ms)` |

`ClientGUI` exposes `showCard(String)` and `boardComponent()` as package-private
**only so `GuiSnapshot` can drive it**; everything else is private.

### 3.4 `tests/` — 8 files, 1095 lines

| File | Lines | Covers |
|---|---|---|
| `TestChessGame.java` | 436 | 11 groups: basics, promotion, checkmate, castling, en passant, draw conditions, castling-rights regression, compact protocol, perft, move generation, FEN round-trip |
| `TestNetworkIntegration.java` | 231 | Real sockets: register/room/join/match/resign, concurrent-join race, input validation. **Skips without Postgres** |
| `TestMatchFlow.java` | 171 | Real sockets with **guest auth** (no DB needed): full match incl. `SET_MODE`/`SET_COLOR`/`SET_TIMER`, colour assignment, board frame, legal + illegal move, resign |
| `TestClientComponents.java` | 89 | `ClientConfig` validation, `ROOM_CREATED`/`ROOMS_LIST` parsing, legacy `BOARD:` block, `TerminalTheme` ASCII fallback, `LineListener` shutdown |
| `TestAccountHandler.java` | 60 | register/duplicate/short-password/login/wrong-pass/unknown/null/delete. **Skips without Postgres** |
| `TestRoomHandler.java` | 49 | Room creation, public listing, code lookup, atomic `trySetGuest` + `rollbackGuestJoin`, `removeRoom` |
| `RunOne.java` | 32 | Single-suite runner |
| `TestRunner.java` | 27 | Fail-fast sequential driver |

---

## 4. Protocol reference

**Transport:** raw TCP, port 6700. **Framing:** `\n`-terminated lines.
**Encoding:** UTF-8 plain text. **Shape:** `KEY:payload`. No length prefix, no
JSON, no compression, no TLS by default.

Writes use `PrintWriter(..., true)` (autoflush).

### 4.1 Reading with a size limit

`ServerMain.readLineWithLimit` and `ConnectionHandler.readLineWithLimit` are
**duplicated implementations** (`ServerMain.java:242-261`,
`ConnectionHandler.java:525-544`). Both hand-roll char-by-char reading to throw
`IOException` above **512 bytes**, stripping `\r` as they go.

> This limit has a sharp edge: in `listenPlayer` an `IOException` is interpreted
> as a disconnect, which triggers a **resignation** (§11.5).

### 4.2 Client → server

| Command | Arguments | Effect |
|---|---|---|
| `LOGIN` | `user:pass` | Authenticate against PostgreSQL |
| `REGISTER` | `user:pass` | Create an account |
| `GUEST` | `nick` | Bypass the database entirely; blank nick becomes `Guest_<4 digits>` via `SecureRandom` |
| `DELETE_ACCOUNT` | `user:pass` | Delete after successful login. **Server-implemented but never sent by any client** |
| `LIST_ROOMS` | — | Public rooms still `WAITING` |
| `CREATE_ROOM` | `name:<bool>` | `<bool>` is private/public |
| `JOIN_ROOM` | `CODE` | 6 chars, `[A-Z2-9]` |
| `MOVE` | `from:to` | Both squares validated as `a-h`/`1-8` |
| `RESIGN` | — | Ends the game |
| `SET_MODE` | `X` | `STANDARD`/`RAPID`/`BLITZ` — **cosmetic, §11.6** |
| `SET_COLOR` | `X` | `WHITE`/`BLACK`/`RANDOM` |
| `SET_TIMER` | `N` | Seconds, clamped to 10–3600 |
| `PING` / `PONG` | — | Keep-alive, intercepted before command dispatch |

### 4.3 Server → client

| Message | Notes |
|---|---|
| `WELCOME:<serverName>` | First line, on connect |
| `AUTH_OK:<text>` / `AUTH_ERROR:<text>` | |
| `INFO:<text>` | Generic notices |
| `ERROR:<text>` | Includes `Server busy. Try again later.` and `Server overloaded.` |
| `ROOMS_LIST:<n>;CODE\|NAME\|HOST;…` | Count-prefixed, pipe-delimited |
| `ROOM_CREATED:<name>:<code>:<isPrivate>` | `:` is banned in room names so this stays parseable |
| `JOIN_OK:<text>` / `JOIN_ERROR:<text>` | |
| `MATCH_START:<text>` | |
| `ASSIGNED_COLOR:WHITE\|BLACK` | |
| `BOARD:<71 chars><w\|b>` | **78 characters on one line** — see §6.5 |
| `MOVE_OK:<text>` / `MOVE_ERROR:<text>` | Broadcast to both players |
| `GAME_OVER:<text>` | |
| `PING` / `PONG` | Keep-alive |

### 4.4 Input validation

`ServerMain` validates usernames as `^[A-Za-z0-9_-]{3,24}$` and room names as
`^[A-Za-z0-9 _-]{3,32}$`, with an explicit ban on `:`, `|`, `\n`, `\r`
(`ServerMain.java:32-33, 263-273`).

Three separate regex families coexist and happen to interoperate but are not
derived from one constant:

| Entity | Pattern | Location |
|---|---|---|
| Username | `^[A-Za-z0-9_-]{3,24}$` | `ServerMain` |
| Room name | `^[A-Za-z0-9 _-]{3,32}$` | `ServerMain` |
| Join code | `^[A-Z2-9]{6}$` | server `JOIN_ROOM` |
| Code (client parse) | `[A-Z0-9]{6}` | `ClientProtocolParser` |
| Alphabet used | `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` | `ServerRoomHandler` (no I/O/0/1) |

---

## 5. Server internals

### 5.1 Concurrency model — three tiers

**Tier 1 — accept.** `ServerMain.main:58-77` blocks on
`serverSocket.accept()` on the main thread. Each socket must first acquire a
permit from a `Semaphore(MAX_CONNECTIONS = 128)` via `tryAcquire()`; on failure
it is immediately rejected with a plaintext `ERROR:Server busy.` and closed.
Accepted sockets go to a `ThreadPoolExecutor` with:

- `THREADS = max(8, availableProcessors() * 2)`
- a 128-slot `ArrayBlockingQueue`
- 30 s keep-alive
- `AbortPolicy`, whose rejection path also sends `ERROR:Server overloaded.`

**Tier 2 — negotiation.** One pool thread per connection runs
`handleInitialClient` (auth phase, then lobby phase) and **returns** once it
hands the socket to a room. A connection therefore occupies a pool thread only
while negotiating, not for its whole lifetime.

**Tier 3 — room.** One `new Thread(ch, "room-" + code)` per room. Inside
`ConnectionHandler.run()`: an `Executors.newFixedThreadPool(2)` (one
`listenPlayer(true)`, one `listenPlayer(false)`) plus a **daemon**
`ScheduledExecutorService` for keep-alive.

**Thread cost per concurrent game: 4** (room + 2 listeners + scheduler). With
the 128-connection cap that is up to ~512 threads, unmeasured and unbounded
beyond that.

**No NIO** — no `AsynchronousSocketChannel` anywhere. Everything is blocking
socket I/O with thread-per-socket.

### 5.2 Socket timeouts

All values are applied by overwriting `SO_TIMEOUT` on the same socket:

| Phase | Value | Constant |
|---|---|---|
| Auth | 300 000 ms (5 min) | `ServerMain.AUTH_TIMEOUT_MS:28` |
| Main lobby | 600 000 ms (10 min) | `ServerMain.LOBBY_TIMEOUT_MS:29` |
| Room lobby read | 1 000 ms (polled) | `ConnectionHandler.LOBBY_SOCKET_READ_TIMEOUT_MS:166` |
| In-game read | 2 000 ms (polled) | `ConnectionHandler.GAME_SOCKET_READ_TIMEOUT_MS:167` |
| Room lobby overall | 120 000 ms (2 min) | `ConnectionHandler.LOBBY_TIMEOUT_MS:168` |
| `matchOverLatch` ceiling | 2 hours | `ConnectionHandler.java:311` |
| Ping interval / grace | 5 000 / 25 000 ms | `ConnectionHandler.java:169-170` |

Note the 1 s and 2 s values are **polled**, so the in-game read loop wakes every
2 seconds per player even when idle.

### 5.3 State management

**All game and room state is in-memory and volatile until restart.**

- `Server` holds one `ServerRoomHandler` and one `ServerAccountHandler` for the
  process lifetime.
- `ServerRoomHandler` holds two `ConcurrentHashMap<String, Room>`: `roomsById`
  and `roomsByCode`.
- `Room` fields: `final` id / name / code / isPrivate / hostSocket /
  hostUsername; `volatile` guestSocket / guestUsername / status /
  connectionHandler; `synchronized` mutators `trySetGuest`,
  `rollbackGuestJoin`, `setStatus`.
- `ConnectionHandler` holds one `ChessGame` (inside `GameHandler`), plus
  `RoomSettings`, two `CountDownLatch`es, an `AtomicBoolean cleanedUp`, and
  `volatile` stream fields.

**Nothing is persisted except accounts.** Restarting the JVM destroys every
room, in-flight game and connected client. There is no move history, no PGN, no
game log, no replay, no rating.

**Rooms are process-local.** Behind a load balancer, two instances would each
see a different room list and roughly half of all `JOIN_ROOM` calls would fail.
There is no shared state and no sticky-session configuration.

### 5.4 Sessions and authentication

**There are no sessions.** Once `LOGIN`, `REGISTER` or `GUEST` succeeds, the
connection is identified only by a local `String currentUser` and is **never
re-checked** for subsequent room commands. Whoever authenticated as a given user
*is* that user for the rest of the connection.

- No token, no cookie, no JWT.
- **No reconnect or resume.** Reconnecting means re-authenticating, and the old
  socket's disconnect resigns the game.
- `GUEST:<nick>` bypasses the database entirely.

### 5.5 Request flow — a single `MOVE`

1. Client writes `MOVE:e2:e4\n` through its autoflush `PrintWriter`.
2. That client's `listenPlayer` thread is parked in `readLineWithLimit` with a
   2 s `SO_TIMEOUT`; `SocketTimeoutException` is swallowed and the loop re-reads.
3. Line parses. `PING`/`PONG` are intercepted first and update
   `lastHostPing`/`lastGuestPing`.
4. `line.split(":", 3)`; both squares validated by `isValidSquare` (2 chars,
   `a-h`, `1-8`) → `MOVE_ERROR:Malformed move` on failure.
5. Squares lowercased; `gameHandler.handlePlayerMove(from, to, isHost)` maps
   host→`hostColor`, guest→`guestColor`, then calls
   `chessGame.makeMove(from, to, color)`.
6. `ChessGame.makeMove` (all `synchronized`): game-over guard → turn guard →
   `parseSquare` → from ≠ to → source occupied → not the opponent's piece → not
   own piece → target is not a KING → classify en passant / castling →
   `isValidPieceMove` → `applyMove` (handles the en-passant victim off-square,
   rook relocation on castling, `kingSquare` cache update) → auto-promote to
   QUEEN → `isInCheck(mover)`; if in check, `undoMove`, restore the EP window and
   reject.
7. On success: `updateCastlingRights`, set/clear the en-passant window,
   bump/reset `halfMoveClock`, then evaluate the opponent.
8. End-condition cascade in order: **no legal move** → checkmate (winner = mover)
   or stalemate; then **insufficient material**; then `halfMoveClock >= 100`; then
   `repetitionCount >= 3` (Zobrist); otherwise
   `MoveResult(true, "Move accepted: e2 -> e4"[ + " (Check)"])`.
9. Back in `listenPlayer`: update `lastTurnActionMs`, `broadcast("MOVE_OK:…")` to
   **both** sockets, `broadcastBoard()` (a second frame to both), and if the game
   is over call `endSession()`.
10. The blocked room thread wakes from `matchOverLatch.await(2, HOURS)`,
    broadcasts `GAME_OVER:`, then `selfDestruct(...)`: `AtomicBoolean.compareAndSet`
    guard → `endSession()` → `room.setStatus(FINISHED)` →
    `keepAliveScheduler.shutdownNow()` → `gameListenerPool.shutdownNow()` → close
    both sockets → `roomHandler.removeRoom(...)`.

### 5.6 Security posture

**Plaintext by default.** `ServerMain.java:50-56` prints an explicit warning:

```java
boolean tlsEnabled = Boolean.parseBoolean(System.getProperty("chess.server.tls", "false"));
...
System.out.println("[ServerMain] WARNING: TLS disabled. Credentials are sent in cleartext.");
```

`createServerSocket` does use `SSLServerSocketFactory.getDefault()`
(`ServerMain.java:83-88`), but **no keystore or truststore is configured anywhere**
in the codebase or scripts, so there is no way to supply one. As shipped, all
passwords and moves traverse the wire unprotected.

Hardening that *is* present: 512-byte line cap, strict input regexes, PBKDF2 with
120 000 iterations and per-user salt, constant-time login comparison
(`MessageDigest.isEqual`) with an explicit **dummy-hash call on unknown users to
avoid a timing oracle** (`ServerAccountHandler.java:99`).

---

## 6. Game logic

**Entirely hand-written. No third-party chess library** — no `chess.js`,
`chessground` or `stockfish`. `ChessGame` is the single source of truth for rules.

### 6.1 Representation

```java
private final Piece[][] board = new Piece[8][8];
```

Indexed `board[row][file]` with **row 0 = rank 8, row 7 = rank 1**, so White
advances by *decreasing* row. `Piece` is an immutable two-field value object
(type + colour); `Piece.toString()` yields uppercase for White, lowercase for
Black.

### 6.2 Key algorithms

| Concern | Implementation | Location |
|---|---|---|
| **Attack detection** | `isSquareAttacked` — walks rays **outward from the target square** rather than scanning all 64 pieces. Pawn offsets inline, `isFixedLeaperAttacked` for KNIGHT/KING, `rayAttack` for sliders. Rays stop at the first non-empty square. | `ChessGame.java:810-878` |
| **Move generation** | `collectPieceMoves` dispatches per type: leapers use fixed `{rowStep,fileStep}` arrays; sliders walk rays via `collectSliderMoves`; pawns use `collectPawnMoves` (push, double push, 2 captures incl. en-passant-onto-empty). Replaces an earlier 4096-candidate-pair scan. | `ChessGame.java:918-988` |
| **Legality** | `addIfLegal` is the single pipeline: bounds → not-own-piece → not-enemy-king → EP/castle classification → `isValidPieceMove` → apply move → optional promotion → `!isInCheck(color)` → undo. | `ChessGame.java:995-1023` |
| **Apply / undo** | `AppliedMove` records from/to, the captured piece **and its square** (needed because the en-passant victim is not on the destination), and the 4 rook coordinates for a castle. | `ChessGame.java:161-196, 396-461` |
| **Castling** | `isCastlingMove` — right flags, rook present and correctly coloured, intermediate squares empty, and `isSquareAttacked` on **start, transit and destination**. Rights are also cleared when a rook is *captured on its home square*. | `ChessGame.java:695-730, 667-683` |
| **En passant** | `isEnPassantCapture` — pawn, diagonal, target empty, matches `(enPassantRow, enPassantFile)`, correct forward direction. The window is set on any double push and cleared on every other move. | `ChessGame.java:685-693, 508-534` |
| **Check / mate** | `isInCheck` uses the cached `kingSquare`; **a missing king counts as check** (fail-safe). Mate/stalemate = no legal reply, with/without check. | `ChessGame.java:880-887, 542-560` |
| **Threefold repetition** | 64-bit **Zobrist hashing** into a `Map<Long,Integer>`. Keys: 12 piece types × 64 squares + side-to-move + 4 castling rights + 8 en-passant files = 80 longs, generated from the fixed xorshift seed `0x9E3779B97F4A7C15L` so keys are **identical on every run and machine**. | `ChessGame.java:198-222, 358-388` |
| **Fifty-move rule** | `halfMoveClock`, reset on a pawn move or capture, draw at `>= 100`. | `ChessGame.java:536-540, 571-578` |
| **Insufficient material** | One pass into a 12-bucket count array. Any pawn/rook/queen ⇒ not a draw; otherwise both sides `<= 1` minor ⇒ draw, plus two same-coloured bishops alone ⇒ draw. **Two knights are deliberately NOT an automatic draw** (tested). | `ChessGame.java:604-665` |
| **Perft** | `perftRec` + `applyLegal`/`undoLegal` + a `PerftUndo` scalar snapshot. **Deliberately skips repetition bookkeeping** and allocates no strings. | `ChessGame.java:1040-1132` |
| **King-square cache** | `int[] kingSquare` indexed by `Color.ordinal()`, maintained by `applyMove`/`undoMove`, rebuilt on `loadFEN`. Makes `isInCheck` O(1) instead of a 64-square scan. | `ChessGame.java:230-231, 439-441, 1389-1397` |
| **FEN** | `toFEN()` and `loadFEN()`. `loadFEN` is **transactional** — parses into a temp array and commits only on full success. `castlingRights()` derives rights from both the moved-flags *and* `hasPiece` on the home square. | `ChessGame.java:1231-1293, 1302-1402` |

### 6.3 Two documented bug fixes worth preserving

1. **Castling rights after a rook capture.** After `Rxa8`, the moved-flags said
   the black h-rook had moved and the old FEN emitted a bogus `k`. Deriving
   rights from flags **and** occupancy fixed it. Covered by the
   `castlingRightsRegression` test group.
2. **Off-by-one in the compact frame.** `parseBoardCompact` now validates length,
   slash positions, character classes and the turn character *before* indexing —
   it used to throw on every valid frame.

These are explained in Javadoc at their fix sites and are the closest thing the
project has to design documentation. They are preserved verbatim in the code and
worth keeping in any future refactor.

### 6.4 Clocks live outside the engine

`ChessGame` has **no timer field at all**. All timing is in `ConnectionHandler`:
`lastTurnActionMs` versus `settings.getTurnTimeLimitSeconds()`. There is no
increment, no accumulated remaining time per side, and no time-remaining
per colour — it is a **per-move limit, not a chess clock** (§11.7).

### 6.5 Wire format

`BOARD:` + 8 ranks of 8 characters (`Piece.toString()` or `.`) joined by `/` +
the turn character = **78 characters on a single line**.

Constants: `BOARD_PAYLOAD_LENGTH = 72`, `BOARD_TURN_INDEX = 71`.

### 6.6 Rules gaps

| Gap | Detail |
|---|---|
| **Underpromotion impossible** | Always promotes to QUEEN (`ChessGame.java:515-517`) |
| No draw offer | — |
| No claim mechanism | Threefold and fifty-move **auto-apply** rather than being claimable |
| No time-forfeit rules | — |
| FEN partially modelled | `loadFEN` requires ≥ 4 fields, reads only `parts[4]`, never reads the fullmove number |
| Insufficient material slightly strict | K+B vs K+B with **opposite-coloured** bishops is treated as an automatic draw; under FIDE 5.2.2 it is a draw only when the bishops share a colour square |

---

## 7. Clients

### 7.1 Terminal client

`ClientMain` (327 lines) runs connect → auth menu → room menu → move loop, and
owns a `LineListener` for asynchronous server pushes. `TerminalUI` restores the
prompt after async output so server events do not clobber the input line.

Presentation degrades through `TerminalTheme` (ANSI/256 colour, unicode, emoji,
compact mode), all independently disableable. `ClientConfig` validates host and
port and enforces the `1000..120000` timeout range.

**The board does not render** — see §11.1.

### 7.2 Swing client

`ClientGUI` (1067 lines) uses a 4-card `CardLayout`: AUTH → LOBBY → SETTINGS →
GAME. `BoardPanel` (412 lines) is a fully custom-painted `JComponent` — it does
not use any chess component library. Hit-testing is arithmetic on `(x, y)`;
rendering handles flip, selection, last-move highlight, a check ring, move
dots/rings and coordinate gutters.

`setUpLookAndFeel()` installs FlatLaf dark, and registers `Comfortaa.ttf` inside a
`try/catch(Exception ignored)`, so a missing font is silent.

### 7.3 `GuiSnapshot`

Writes `01-auth.png` … `05-game-flipped.png` offscreen using the app's own LAF
and card switching. **Undocumented and side-effecting:** its `main` constructs a
real `ClientGUI`, which connects in its constructor and spawns a daemon connect
thread (which will fail and pop a modal if no server is running), and it ends
with `System.exit(0)`. No wrapper script, no headless flag, and its PNG output is
not gitignored.

---

## 8. Database and secrets

### 8.1 `neon.ts`

```ts
import { defineConfig } from "@neon/config/v1";

export default defineConfig({});
```

Neon infrastructure-as-code. An empty object means "no services, default
settings". Applied by `neon checkout` / `neon deploy`. **Not read by the Java
build, `build.sh`, `nixpacks.toml` or `railway.json`.**

### 8.2 PostgreSQL — accounts only

- Driver loaded reflectively; **no compile-time import**.
- `DriverManager.getConnection` is opened and **closed per operation** — no pool,
  no HikariCP (§11.12).
- Schema, created idempotently in the constructor (`initDatabase`):

```sql
chess_users(username VARCHAR(24) PRIMARY KEY,
            salt VARCHAR(64)  NOT NULL,
            password_hash VARCHAR(128) NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
```

- **PBKDF2WithHmacSHA256, 120 000 iterations**, 16-byte `SecureRandom` salt,
  32-byte output, Base64. Minimum password length 8.
- **Failure is non-fatal**: `initDatabase` catches `Exception` and only logs
  `"[DB] PostgreSQL init failed: …"`. The server boots and serves guest games
  with no database; `login`/`register`/`deleteAccount` then return `false`.

### 8.3 Secrets

- **No secrets in the repository.** No `.env` or `.env.local` exists on disk.
  `.gitignore` excludes `bin/`, `*.class`, `repair_prompt.txt`,
  `worker_prompt.txt`, `.neon`, `.env.local`.
- `start-server.sh` loads `.env.local` and maps Neon → Java naming:

```bash
export $(grep -v '^#' .env.local | xargs)
export DB_URL=$DATABASE_URL
```

> **Gotcha:** this mapping is scoped to the *script*, not the ambient
> environment. On Railway, `DATABASE_URL` is a platform-injected variable; with
> no `.env.local` in the deployed image, `DB_URL` falls through to
> `jdbc:postgresql://localhost:5432/chess` and the injected value is **silently
> ignored**.

- No pooled-connection string, no `sslmode=require` handling.

---

## 9. Build, run, test

### 9.1 Commands

```bash
# Build (this is what nixpacks runs)
./build.sh

# Run the server — needs bin/ built; must run from the repo root
./start-server.sh
java -Dchess.server.tls=true -cp "bin:lib/postgresql.jar" server.ServerMain   # TLS unusable as shipped

# Terminal client
./start-client.sh
./start-client.sh HOST PORT --compact --no-animations

# Swing client (Linux/X11; needs a display)
./start-gui.sh
CHESS_HOST=example.com CHESS_PORT=6700 ./start-gui.sh

# Tests
./run-tests.sh
java -ea -cp "bin:lib/postgresql.jar:lib/flatlaf.jar" tests.TestRunner

# One suite
java -ea -cp bin:lib/flatlaf.jar tests.RunOne chess     # or match|network|client|room

# Offscreen GUI screenshots — undocumented, needs a display
java -cp "bin:lib/flatlaf.jar" client.GuiSnapshot [outDir]
```

### 9.2 Script inventory

| Script | Lines | Behaviour |
|---|---|---|
| `build.sh` | 3 | `mkdir -p bin` + `javac` over `src/server`, `src/client`, `src/game`. **Does not compile `tests/`.** No `set -e`, no `cd`. |
| `start-server.sh` | 14 | Loads `.env.local` (maps `DATABASE_URL`→`DB_URL`), sets DB defaults, then `java -cp "bin:lib/postgresql.jar" server.ServerMain`. **Does not build, does not `cd`.** |
| `start-client.sh` | 4 | `cd "$(dirname "$0")"` then `java -cp bin client.ClientMain "$@"`. No DB needed. |
| `start-gui.sh` | 5 | Exports `_JAVA_AWT_WM_NONREPARENTING=1`, `GDK_BACKEND=x11`; `java -Dsun.java2d.xrender=false -Dsun.java2d.opengl=false -cp "bin:lib/flatlaf.jar" client.ClientGUI`. Correctly omits `postgresql.jar`. |
| `run-tests.sh` | 12 | `set -euo pipefail`; `cd`; compiles sources **and** `tests` with both jars; runs `java -ea … tests.TestRunner`. Carries an Italian comment explaining the jars are needed at test time too. |

**Not present:** any wrapper for `GuiSnapshot`, any database bootstrap script, no
install script, no `Dockerfile`, no `Makefile`, no `.github/workflows`.

### 9.3 Test harness details

| Order | Suite | DB | Network | Notes |
|---|---|---|---|---|
| 1 | `TestAccountHandler` | required | no | Skips cleanly if `DB_URL` blank or driver unloadable |
| 2 | `TestRoomHandler` | no | loopback | Atomic double-join race check |
| 3 | `TestChessGame` | no | no | 11 groups, incl. perft to depth 4 |
| 4 | `TestMatchFlow` | no | loopback | Guest auth, full match |
| 5 | `TestNetworkIntegration` | required | loopback | Skips cleanly; also checks room auto-removal |
| 6 | `TestClientComponents` | no | pipes | Pure unit-ish |

Two structural notes:

1. **Suites 4 and 5 are coupled to a private method signature.** They boot a
   `ServerSocket(0)` and call the **private static**
   `ServerMain.handleInitialClient(Socket, Server)` by reflection with
   `setAccessible(true)` (`TestMatchFlow.java:64-66`,
   `TestNetworkIntegration.java:41-43`). Renaming that method breaks two suites.
2. **The skip check is weak.** Skipping keys only on `DB_URL` being non-blank and
   the driver being loadable — it never verifies the database actually answers.
   A wrong password therefore makes those suites **fail** rather than skip.

`TestNetworkIntegration` also contains ~8 s of dead waiting: it awaits
`"Current Turn:"` twice with 4 s socket timeouts and asserts nothing on the
result, because the server no longer emits that string (§11.3).

---

## 10. Deployment

### 10.1 Railway + Nixpacks

`railway.json`:

```json
{ "build": { "builder": "NIXPACKS" },
  "deploy": { "startCommand": "bash start-server.sh", "restartPolicyType": "ON_FAILURE" } }
```

`nixpacks.toml`:

```toml
[phases.setup]
nixPkgs = ["jdk"]

[phases.build]
cmds = ["bash build.sh"]

[start]
cmd = "bash start-server.sh"
```

Observations:

- **The JDK version is unpinned** (`nixPkgs = ["jdk"]`) — whatever Nixpacks
  defaults to. The code needs ≥ 11.
- `build.sh` does not compile `tests/`, which is correct for an image.
- **`lib/*.jar` must be committed.** `nixpacks.toml` declares no fetch step and
  `.gitignore` does not exclude them, so any clean-checkout build depends on
  those binaries being in the tree.
- `restartPolicyType: ON_FAILURE` with **no healthcheck**.
- The start command is declared twice (harmless redundancy).
- Two remote branches named `railway/fix-deploy-*` indicate prior deployment
  friction.

### 10.2 Runtime artefacts in the working tree

`server.log` (0 bytes), `gui-error.log` (0 bytes) and `server.pid` (containing a
PID) are **not** in `.gitignore` and will go stale.

---

## 11. Bugs and limitations

### 11.1 Confirmed bug — the terminal client cannot render the board

The server sends `renderBoardCompact()` → `"BOARD:" + 72 chars` (78 chars, one
line). But `ClientMain.handleServerLine` (`src/client/ClientMain.java:278-287`)
routes it to `ClientProtocolParser.parseBoardBlock(line, in)`, whose first line is:

```java
if (!"BOARD:".equals(boardHeader)) {
    throw new IllegalArgumentException("Expected BOARD header");
}
```

(`ClientProtocolParser.java:108-110`). Since the header is `"BOARD:<data>"` and
not exactly `"BOARD:"`, **every** frame throws, surfacing as
`Invalid board payload: Expected BOARD header` instead of a board.

Everything else is correct: the server, the Swing GUI (`applyBoardFrame` uses
`ChessGame.parseBoardCompact` and works), and `TestMatchFlow` (which asserts the
78-char frame). Only the TUI path is stale. The follow-on check
`board.contains("Current Turn: " + myColor)` is likewise dead.

**Consequence:** `start-client.sh` is not a usable way to play. Use
`start-gui.sh`.

### 11.2 The GUI promotion dialog is misleading

`ClientGUI.askPromotion` (`:558-570`) offers Queen/Rook/Bishop/Knight, then
**discards the answer** — `return choice != JOptionPane.CLOSED_OPTION` — while the
engine always promotes to queen (`ChessGame.java:515-517`). The code comment
concedes it: *"this at least makes the choice visible and defaults to the queen."*

### 11.3 Dead code and stale tests

- `ChessGame.renderBoard()` (multi-line ASCII, 24 lines) is referenced **only by
  tests**; the server never calls it.
- The dead `"Current Turn:"` waits in `TestNetworkIntegration` (§9.3).
- `TerminalTheme.badge()` has two identical branches (lines 48-49) — `unicode ? "[" : "["` for both `left` and `right`.

### 11.4 Disconnect means automatic loss

In `listenPlayer`, a `null` read or an `IOException` triggers
`gameHandler.resign(isHost)` and `endSession()` (`ConnectionHandler.java:388-395,
436-442`). A refresh, a dropped Wi-Fi, a `Ctrl-C` — or one over-long line (§4.1) —
loses the game. No grace period, no reconnect, no resume.

### 11.5 `SET_MODE` is cosmetic

`RoomSettings.Mode` (`STANDARD`/`RAPID`/`BLITZ`) is stored and read back only to
echo `INFO:Mode set to …` (`ConnectionHandler.java:337`). It never influences the
clock, scoring, or anything else. Only `SET_TIMER` has an effect.

### 11.6 A joining guest never learns the timer

`turnLimitSeconds` in `ClientGUI` is set **only** by the host's settings-screen
`timerBox` listener (`:824-831`). The server never broadcasts the configured
limit, so a guest has `turnLimitSeconds == 0`, `resetTurnClock()` sets the label
to `" "`, and the guest sees **no countdown at all**.

### 11.7 The clock is not a chess clock

`lastTurnActionMs` is reset on each successful move and compared against
`turnTimeLimitSeconds`. There is no increment, no accumulated remaining time per
side, and no per-side time control.

### 11.8 TLS is not usable as wired

See §5.6. `SSLServerSocketFactory.getDefault()` with no keystore configured
anywhere, and no script passes `-Dchess.server.tls=true`. Whether the failure
mode is a startup exception or a silent fallback was not verified by execution.

### 11.9 Rooms are process-local

See §5.3. Two instances behind a load balancer would each hold a different room
list, and `JOIN_ROOM` would fail roughly half the time.

### 11.10 No connection pooling

`DriverManager.getConnection` is opened and closed per `login` / `register` /
`deleteAccount` (`ServerAccountHandler.java:51-53, 58, 92, 121`).

### 11.11 `DELETE_ACCOUNT` is unreachable

`ServerMain` implements it (`:146-152`) but **no client ever sends it** — neither
the TUI nor the GUI exposes account deletion.

### 11.12 Polling instead of blocking

`ConnectionHandler.run()` polls the lobby with a 1 s `SO_TIMEOUT` purely to
re-check `guestJoinedLatch.getCount()` and the 120 s timeout. The in-game readers
likewise wake every 2 s. Code comments show the match-end wait was already
optimised once (`awaitMatchEnd` replaced a 10 Hz poll) but the lobby loop was not.

### 11.13 Mixed-language code

Italian comments in `ServerMain.java:28-29` and `run-tests.sh:7-8`; English
everywhere else. `ClientGUI.java:84` references an external personal site. Note
`Server`'s default name is `"Antigravity Chess Server"` while `ServerMain` passes
`"Main Chess Server"`, so the default is dead.

### 11.14 No quality gates

No CI, no linter, no formatter, no coverage tooling, no dependency management for
the vendored jars, no `LICENSE`, no `CONTRIBUTING`, no `.editorconfig`. The only
quality gates in the project are the Javadoc comments explaining *why* past bugs
were fixed.

---

## 12. Appendix

### 12.1 Line-count summary

| Area | Lines |
|---|---|
| `src/game/` | 1403 |
| `src/server/` | 1164 |
| `src/client/` | 2283 |
| `tests/` | 1095 |
| **Total Java** | **~5945** |

### 12.2 Glossary

| Term | Meaning here |
|---|---|
| **Lobby** | The pre-game area listing public rooms and accepting join codes. Not competitive matchmaking. |
| **Compact board frame** | The 78-character single-line `BOARD:` payload replacing the old multi-line block. |
| **Perft** | A self-check that counts leaf nodes to a given depth; validates move generation against published values. |
| **Zobrist hashing** | XOR-based position hashing used here to detect threefold repetition. |
| **PBKDF2** | Password-based key derivation; 120 000 HMAC-SHA256 iterations per user. |
| **Nixpacks** | Heroku-style buildpack system that detects a project and builds it from a `nixpacks.toml`. |
| **Guest auth** | A login path that bypasses the database entirely, for play without an account. |

### 12.3 Reference tables

| Constant | Value | Location |
|---|---|---|
| Default port | 6700 | `Server.DEFAULT_PORT:7` |
| Max connections | 128 | `ServerMain` semaphore |
| Pool threads | `max(8, cpus*2)` | `ServerMain` |
| Auth timeout | 300 000 ms | `ServerMain.java:28` |
| Lobby timeout | 600 000 ms | `ServerMain.java:29` |
| Room lobby timeout | 120 000 ms | `ConnectionHandler.java:168` |
| Match latch ceiling | 2 h | `ConnectionHandler.java:311` |
| Ping interval / grace | 5 000 / 25 000 ms | `ConnectionHandler.java:169-170` |
| Max line length | 512 bytes | `ServerMain:242`, `ConnectionHandler:525` |
| Turn timer clamp | 10–3600 s | `RoomSettings.setTurnTimeLimitSeconds` |
| PBKDF2 iterations | 120 000 | `ServerAccountHandler` |
| Zobrist seed | `0x9E3779B97F4A7C15L` | `ChessGame.java:198-222` |
| `BOARD_PAYLOAD_LENGTH` | 72 | `ChessGame.java:257-265` |
| `BOARD_TURN_INDEX` | 71 | `ChessGame.java:257-265` |

### 12.4 Perft reference values

| Depth | Nodes |
|---|---|
| 1 | 20 |
| 2 | 400 |
| 3 | 8 902 |
| 4 | 197 281 |

Validated by the `perft` group in `TestChessGame`, which also asserts
**repeatability** and that the position is **unchanged** afterwards.

### 12.5 Method

Direct source reading of all 20 Java files plus the 7 shell scripts and 5 config
files; line counts; targeted greps for I/O, crypto, thread and NIO usage;
comparison against the single-commit git history. The README's build command was
verified against `build.sh`/`run-tests.sh` and against the FlatLaf import at
`ClientGUI.java:3`. Findings that depend on runtime behaviour (the TLS failure
mode in §11.8) are explicitly marked as unverified.
