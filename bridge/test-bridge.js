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

const fakeChess = createServer((socket) => {
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
        socket.write("AUTH_OK:Logged in as test\nLOBBY:rooms=1\n");
      } else if (line.startsWith("LIST_ROOMS")) {
        socket.write("ROOMS:room1\n");
      } else if (line.startsWith("PING")) {
        socket.write("PONG\n");
      } else {
        socket.write("ERR:unknown command\n");
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
        // Righe di protocollo: WELCOME:, AUTH_OK:, LOBBY:, ROOMS:, PONG:
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
        } else if (text.startsWith("AUTH_OK") && !lines.some((l) => l.startsWith("ROOMS"))) {
          ws.send("LIST_ROOMS");
        } else if (text.startsWith("ROOMS:") && !lines.some((l) => l === "PONG")) {
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

  fakeChess.close();
  const failed = results.filter((r) => !r.pass);
  console.log(`\n${results.length - failed.length}/${results.length} verifiche superate`);
  process.exit(failed.length === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error("test fallito:", e);
  process.exit(1);
});