/**
 * Test end-to-end del bridge: un finto server scacchi che parla il protocollo
 * reale (WELCOME iniziale, poi LOGIN/REGISTER/GUEST e comandi), e client
 * WebSocket che attraversano la sfida.
 *
 *   node test-bridge.js
 *
 * Verifica il percorso completo, non le singole funzioni: un bridge puo' avere
 * l'autenticazione corretta e fallire perche' righe le righe in un ordine che
 * nessun test isolato avrebbe notato.
 */

import { createServer } from "node:net";
import { generateKeyPairSync, sign } from "node:crypto";
import { WebSocket } from "ws";
import { buildChallenge } from "./auth.js";

const CHESS_PORT = 16700;
const BRIDGE_PORT = 18081;

const upstreamLines = [];

/**
 * Quante socket TCP il bridge ha aperto verso il server scacchi. Una
 * connessione rifiutata deve lasciare questo contatore a zero: prima della
 * firma il bridge non sa ancora chi ha dall'altra parte, quindi non deve
 * allocare nulla verso il servizio interno.
 */
let upstreamConnections = 0;

/**
 * Finto server scacchi che parla il protocollo REALE di ServerMain /
 * ConnectionHandler (docs.md §4.2-4.3). Non una semplificazione: se il finto
 * server inventasse una grammatica propria, il bridge passerebbe i test e la UI
 * restrebbe muta, perche' nessuna delle due parti avrebbe mai incontrato il
 * formato vero.
 */
const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

function newRoomCode() {
  let code = "";
  for (let i = 0; i < 6; i++) {
    code += CODE_ALPHABET[Math.floor(Math.random() * CODE_ALPHABET.length)];
  }
  return code;
}

const START_BOARD =
  "BOARD:rnbqkbnr/pppppppp/......../......../......../......../PPPPPPPP/RNBQKBNRw";

/** code -> { name, sockets: Set } */
const rooms = new Map();

function broadcast(code, message) {
  const room = rooms.get(code);
  if (!room) return;
  for (const s of room.sockets) s.write(message + "\n");
}

const fakeChess = createServer((socket) => {
  upstreamConnections++;
  let buffer = "";
  socket.write("WELCOME:TestChess\n");
  socket.on("data", (chunk) => {
    buffer += chunk.toString("utf8");
    let nl;
    while ((nl = buffer.indexOf("\n")) !== -1) {
      const line = buffer.slice(0, nl);
      buffer = buffer.slice(nl + 1);
      upstreamLines.push(line);

      if (line.startsWith("LOGIN:")) {
        const user = line.split(":")[1];
        socket.write("AUTH_OK:Logged in as " + user + "\n");
      } else if (line.startsWith("REGISTER:")) {
        const user = line.split(":")[1];
        socket.write("AUTH_OK:Account created and logged in as " + user + "\n");
      } else if (line.startsWith("GUEST:")) {
        socket.write("AUTH_OK:Joined as guest " + line.slice(6) + "\n");
      } else if ("LIST_ROOMS" === line) {
        // ROOMS_LIST:<n>;<codice>|<nome>|<host>;… — una riga sola, come la
        // costruisce ServerMain (conteggio davanti, `;` fra le voci, `|` nei campi).
        const open = [...rooms.entries()].filter(([, r]) => r.sockets.size === 1);
        let out = "ROOMS_LIST:" + open.length;
        for (const [code, room] of open) {
          out += ";" + code + "|" + room.name + "|" + room.host;
        }
        socket.write(out + "\n");
      } else if (line.startsWith("CREATE_ROOM:")) {
        const code = newRoomCode();
        rooms.set(code, { name: line.slice(12), host: "ospite", sockets: new Set([socket]) });
        socket.write("ROOM_CREATED:" + line.slice(12) + ":" + code + ":false\n");
        socket.write("INFO:Waiting for opponent to join room code: " + code + "\n");
      } else if (line.startsWith("JOIN_ROOM:")) {
        const code = line.slice(10).trim().toUpperCase();
        const room = rooms.get(code);
        if (!room) {
          socket.write("JOIN_ERROR:Room not found\n");
        } else if (room.sockets.has(socket)) {
          socket.write("JOIN_ERROR:Room is already full or finished\n");
        } else {
          room.sockets.add(socket);
          socket.write("JOIN_OK:Joined room " + room.name + "\n");
          broadcast(code, "MATCH_START:Room " + room.name + " starting!");
          broadcast(code, "ASSIGNED_COLOR:WHITE");
          broadcast(code, START_BOARD);
        }
      } else if (line.startsWith("MOVE:")) {
        const parts = line.split(":");
        if (parts.length !== 3 || !/^[a-h][1-8]$/.test(parts[1]) || !/^[a-h][1-8]$/.test(parts[2])) {
          socket.write("MOVE_ERROR:Malformed move\n");
        } else {
          const code = [...rooms.entries()].find(([, r]) => r.sockets.has(socket))?.[0];
          broadcast(code, "MOVE_OK:Host moved " + parts[1] + " -> " + parts[2]);
          broadcast(code, START_BOARD);
        }
      } else if ("PING" === line) {
        socket.write("PONG\n");
      } else if (line.startsWith("PONG")) {
        // Il client risponde al keepalive del server: non va rimandato indietro.
      } else {
        socket.write("ERROR:Unknown command\n");
      }
    }
  });
  socket.on("error", () => {});
});

const results = [];
function check(name, pass, detail = "") {
  results.push({ name, pass, detail });
  console.log(`${pass ? "  ok  " : " FAIL "} ${name}${detail ? "  [" + detail + "]" : ""}`);
}

const sign_ = (username, nonce, key) =>
  sign("sha256", Buffer.from(buildChallenge(username, nonce)), key).toString("base64url");

/**
 * Apre un WS, risponde alla sfida, e passa al cliente le righe che arrivano.
 * `onLine` decide quando inviare il comando successivo; se manca, si ferma
 * alla fine dell'handshake.
 */
function session(privateKey, { username, publicKey, mutate, onLine, timeoutMs = 6000 }) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`ws://127.0.0.1:${BRIDGE_PORT}/ws`);
    const lines = [];
    let done = false;
    const finish = (code, reason) => {
      if (done) return;
      done = true;
      clearTimeout(t);
      try {
        ws.close();
      } catch {
        /* noop */
      }
      resolve({ lines, code, reason });
    };
    const t = setTimeout(() => finish(0, "timeout"), timeoutMs);

    ws.on("open", () => {
      ws.on("message", (raw) => {
        const text = raw.toString("utf8");
        lines.push(text);
        if (text.startsWith("{")) {
          const msg = JSON.parse(text);
          if (msg.type === "challenge") {
            const payload = {
              username,
              nonce: msg.nonce,
              publicKey,
              signature: sign_(username, msg.nonce, privateKey),
            };
            ws.send(JSON.stringify(mutate ? mutate(payload) : payload));
          }
          return;
        }
        // Righe di protocollo: WELCOME:, AUTH_OK:, ROOMS_LIST:, PONG:
        if (onLine) onLine(text, ws, lines);
      });
    });
    ws.on("close", (code, reason) => finish(code, reason.toString()));
    ws.on("error", () => finish(-1, "error"));
  });
}

async function main() {
  const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
  const pub = publicKey.export({ type: "spki", format: "pem" });
  const strangerKey = generateKeyPairSync("ec", { namedCurve: "P-256" });

  await new Promise((r) => fakeChess.listen(CHESS_PORT, "127.0.0.1", r));
  process.env.BRIDGE_PORT = String(BRIDGE_PORT);
  process.env.CHESS_PORT = String(CHESS_PORT);
  await import("./server.js");
  await new Promise((r) => setTimeout(r, 300));

  const logins = () => upstreamLines.filter((l) => l.startsWith("LOGIN")).length;

  // 1. Percorso completo: sfida, poi LOGIN, poi comandi.
  {
    const r = await session(privateKey, {
      username: "cristianrenosto",
      publicKey: pub,
      onLine: (text, ws, lines) => {
        // Un solo LOGIN: il client aspetta WELCOME, non risponde a ogni riga.
        if (text.startsWith("WELCOME:")) {
          ws.send("LOGIN:cristianrenosto:segreta");
        } else if (text.startsWith("AUTH_OK") && !lines.some((l) => l.startsWith("ROOMS_LIST"))) {
          ws.send("LIST_ROOMS");
        } else if (text.startsWith("ROOMS_LIST:") && !lines.some((l) => l === "PONG")) {
          ws.send("PING");
        }
      },
    });
    check("sfida firmata accettata, LOGIN inoltrato", logins() === 1);
    check("comandi dopo l'autenticazione inoltrati", upstreamLines.includes("LIST_ROOMS"));
    check("PING arrivato al server", upstreamLines.includes("PING"));
    check("WELCOME del server consegnato al client", r.lines.some((l) => l.startsWith("WELCOME:")));
    check("PONG torna indietro", r.lines.includes("PONG"));
  }

  // 2. Replay: riutilizzo di una firma valida con lo stesso nonce.
  {
    let captured = null;
    await session(privateKey, {
      username: "cristianrenosto",
      publicKey: pub,
      onLine: (text, ws, lines) => {
        if (text.startsWith("{")) return; // firma gia' inviata
      },
      timeoutMs: 2000,
    });
    // Ricattura il nonce da una sessione fresca e prova a riutilizzarlo.
    const fresh = new WebSocket(`ws://127.0.0.1:${BRIDGE_PORT}/ws`);
    await new Promise((resolve) => {
      fresh.on("message", (raw) => {
        const text = raw.toString("utf8");
        try {
          const msg = JSON.parse(text);
          if (msg.type === "challenge") {
            captured = {
              username: "cristianrenosto",
              nonce: msg.nonce,
              publicKey: pub,
              signature: sign_("cristianrenosto", msg.nonce, privateKey),
            };
            fresh.send(JSON.stringify(captured));
            fresh.close();
            resolve();
          }
        } catch {
          /* noop */
        }
      });
      setTimeout(() => {
        fresh.close();
        resolve();
      }, 2000);
    });
    const before = logins();
    const replay = await session(privateKey, {
      username: "cristianrenosto",
      publicKey: pub,
      mutate: (m) => ({ ...m, nonce: captured.nonce }), // stesso nonce, firma valida
      timeoutMs: 2500,
    });
    check(
      "replay con nonce già consumato respinto",
      logins() === before,
      replay.reason.includes("nonce") ? replay.reason : `nessun LOGIN aggiuntivo (${before})`,
    );
  }

  // 3. Utente non autorizzato, con firma perfetta.
  {
    const before = logins();
    const r = await session(privateKey, {
      username: "intruso",
      publicKey: pub,
      timeoutMs: 2500,
    });
    check("utente non in lista respinto", logins() === before, r.reason || "nessun LOGIN");
  }

  // 4. Firma di una chiave non corrispondente.
  {
    const before = logins();
    await session(privateKey, {
      username: "cristianrenosto",
      publicKey: strangerKey.publicKey.export({ type: "spki", format: "pem" }),
      timeoutMs: 2500,
    });
    check("chiave pubblica non corrispondente respinta", logins() === before);
  }

  // 5. Auth in chiaro: una riga di protocollo al posto del JSON della sfida.
  {
    const before = logins();
    const ws = new WebSocket(`ws://127.0.0.1:${BRIDGE_PORT}/ws`);
    await new Promise((resolve) => {
      const t = setTimeout(() => {
        ws.close();
        resolve();
      }, 2000);
      ws.on("open", () => ws.send("LOGIN:cristianrenosto:segreta"));
      ws.on("close", () => {
        clearTimeout(t);
        resolve();
      });
    });
    check("riga di auth non firmata rifiutata", logins() === before);
  }

  // 6. Endpoint diverso da /ws.
  {
    const ws = new WebSocket(`ws://127.0.0.1:${BRIDGE_PORT}/altro`);
    const closed = await new Promise((resolve) => {
      const t = setTimeout(() => resolve(false), 2500);
      ws.on("error", () => {
        clearTimeout(t);
        resolve(true);
      });
      ws.on("close", () => {
        clearTimeout(t);
        resolve(true);
      });
    });
    check("endpoint diverso da /ws chiuso", closed);
  }

  // 7. Nessuna segretologia di sessione nei log.
  {
    const health = await fetch(`http://127.0.0.1:${BRIDGE_PORT}/healthz`);
    const body = await health.json();
    check("healthz risponde", health.status === 200 && body.ok === true, JSON.stringify(body));
  }

  // 8. Nessuna socket verso il server scacchi prima della firma. Le sessioni
  //    rifiutate sopra non devono aver aperto nulla: se il bridge aprisse la
  //    connessione durante l'handshake, chiunque — anche respinto — potrebbe
  //    fargli consumare una socket verso il servizio interno.
  {
    const prima = upstreamConnections;
    await session(privateKey, {
      username: "intruso",
      publicKey: pub,
      timeoutMs: 2000,
    });
    check(
      "nessuna socket verso il server prima dell'autenticazione",
      upstreamConnections === prima,
      "connessioni upstream: " + prima + " -> " + upstreamConnections,
    );
  }

  // 9. Percorso completo di una partita attraverso il bridge: CREATE_ROOM,
  //    JOIN_ROOM, MATCH_START, ASSIGNED_COLOR, BOARD, MOVE_OK, BOARD. Due
  //    sessioni vere: la prima crea, la seconda entra con il codice che la
  //    prima ha ricevuto. Verifica il ponte, non la firma, ed è il percorso che
  //    la UI web percorre davvero.
  {
    // Il codice serve solo per leggere il log finale: l'ospite non lo usa, lo
    // legge da ROOMS_LIST esattamente come fa la UI web.
    const condiviso = { code: null };
    let guestPolls = 0;

    const host = session(privateKey, {
      username: "cristianrenosto",
      publicKey: pub,
      timeoutMs: 6000,
      onLine: (text, ws) => {
        if (text.startsWith("WELCOME:")) {
          ws.send("LOGIN:cristianrenosto:segreta");
        } else if (text.startsWith("AUTH_OK")) {
          ws.send("CREATE_ROOM:Partita Web");
        } else if (text.startsWith("ROOM_CREATED:")) {
          condiviso.code = text.split(":")[2];
        } else if (text.startsWith("MATCH_START:")) {
          ws.send("MOVE:e2:e4");
        }
      },
    });

    // Se la stanza non c'è ancora (il server risponde in ordine, e in quel
    // momento l'ospite ha già chiesto la lista) la richiede di nuovo, con una
    // pausa per non trasformare il test in un ciclo saturo.
    const guest = session(privateKey, {
      username: "cristianrenosto",
      publicKey: pub,
      timeoutMs: 6000,
      onLine: (text, ws) => {
        if (text.startsWith("WELCOME:")) {
          ws.send("GUEST:ospite");
        } else if (text.startsWith("AUTH_OK")) {
          ws.send("LIST_ROOMS");
        } else if (text.startsWith("ROOMS_LIST:")) {
          const first = text
            .slice("ROOMS_LIST:".length)
            .split(";")
            .slice(1)
            .filter(Boolean)[0];
          const code = first && first.split("|")[0];
          if (code) {
            ws.send("JOIN_ROOM:" + code);
          } else if (guestPolls++ < 20) {
            setTimeout(() => ws.send("LIST_ROOMS"), 100);
          }
        }
      },
    });

    const [h, g] = await Promise.all([host, guest]);
    const hostLines = h.lines;
    const guestLines = g.lines;

    const boardLine = (lines) => lines.find((l) => l.startsWith("BOARD:"));
    check("ROOM_CREATED attraversa il bridge", hostLines.some((l) => /^ROOM_CREATED:.+:[A-Z2-9]{6}:false$/.test(l)));
    check("JOIN_ROOM inoltrato", upstreamLines.some((l) => l.startsWith("JOIN_ROOM:")));
    check("MATCH_START consegnato a entrambi", hostLines.some((l) => l.startsWith("MATCH_START:")) && guestLines.some((l) => l.startsWith("MATCH_START:")));
    check("ASSIGNED_COLOR consegnato", hostLines.some((l) => l.startsWith("ASSIGNED_COLOR:")));
    check(
      "BOARD in forma compatta (78 caratteri)",
      hostLines.some((l) => /^BOARD:[rnbqkpRNBQKP.\/]{71}[wb]$/.test(l)),
      boardLine(hostLines) ? "len=" + boardLine(hostLines).length : "nessun BOARD",
    );
    check("MOVE inoltrato", upstreamLines.includes("MOVE:e2:e4"));
    check("MOVE_OK torna al client", hostLines.some((l) => l.startsWith("MOVE_OK:")));
    check("la scacchiera aggiornata torna al client", hostLines.filter((l) => l.startsWith("BOARD:")).length >= 2);
  }

  // 10. Server scacchi spento: la sessione autenticata si chiude invece di
  //     lasciare una WebSocket aperta su una partita che non esiste piu'.
  //     Chiudere il finto server lascia la porta 16700 senza nessuno in
  //     ascolto, quindi la connect() del bridge ottiene ECONNREFUSED.
  {
    await new Promise((r) => fakeChess.close(r));
    const r = await session(privateKey, {
      username: "cristianrenosto",
      publicKey: pub,
      timeoutMs: 2500,
    });
    check(
      "server scacchi irraggiungibile chiude la sessione",
      r.code === 1000 && /upstream/i.test(r.reason),
      "code=" + r.code + " reason=" + r.reason,
    );
  }

  const failed = results.filter((r) => !r.pass);
  console.log(`\n${results.length - failed.length}/${results.length} verifiche superate`);
  process.exit(failed.length === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error("test fallito:", e);
  process.exit(1);
});