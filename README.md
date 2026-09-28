# chess-server

Multiplayer chess server/client in Java.

## Build

```bash
mkdir -p bin
javac -d bin src/game/*.java src/server/*.java src/client/*.java tests/*.java
```

## Run

Server (plain TCP):

```bash
./start-server.sh
```

Server with TLS (requires JVM SSL keystore configured externally):

```bash
java -Dchess.server.tls=true -cp bin server.ServerMain
```

Client:

```bash
./start-client.sh
./start-client.sh HOST PORT
```

## Test

```bash
./run-tests.sh
```

`run-tests.sh` compiles and runs tests with assertions enabled (`-ea`).

## Security notes

- Account passwords are stored with salted PBKDF2 (`PBKDF2WithHmacSHA256`) only.
- Login checks use constant-time hash comparison and include lightweight backoff.
- User/room input is validated (length/characters), and protocol delimiters are rejected in critical fields.
- Server enforces bounded worker threads, connection caps, socket/read timeouts, and max line lengths.
- Room codes use `SecureRandom`.
- **Important:** if TLS is not enabled (`-Dchess.server.tls=true`), credentials travel in cleartext.

## Functional scope

Implemented chess legality core includes:

- turn enforcement and legal movement checks
- no degenerate same-square moves
- no direct king capture
- self-check prevention
- checkmate and stalemate detection
- pawn promotion (auto-promote to queen)

Not implemented yet: castling, en passant, repetition/50-move draw logic.
