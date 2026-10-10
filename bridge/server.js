/**
 * Bridge WebSocket -> TCP per chess-server.
 *
 * Il server di scacchi parla TCP grezzo a righe di testo sulla porta 6700 e
 * non ha TLS: manda "LOGIN:utente:password" in chiaro. Non lo cambio, perche'
 * il client Swing esistente deve continuare a funzionare cosi'. Invece lo
 * chiudo in modo che l'unico modo per arrivarci sia questo bridge:
 *
 *   - ascolta SOLO su 127.0.0.1, mai su 0.0.0.0. Il tunnel cloudflared gira
 *     sulla stessa macchina e ci arriva via localhost; nessun altro host
 *     della rete puo' aprire la 6700.
 *   - il traffico in arrivo e' wss, quindi la cifratura e' garantita dal
 *     tunnel, non dipende da una configurazione che qualcuno puo' dimenticare.
 *   - ogni connessione deve rispondere a una sfida firmata: HTTPS da solo
 *     autentica il trasporto, non la persona.
 *
 * Uso:
 *   node server.js
 * Env:
 *   BRIDGE_HOST        default 127.0.0.1
 *   BRIDGE_PORT        default 8081
 *   CHESS_HOST         default 127.0.0.1
 *   CHESS_PORT         default 6700
 */

import { createServer } from "node:http";
import { connect } from "node:net";
import { readFile } from "node:fs/promises";
import { dirname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer } from "ws";
import {
  AuthError,
  NonceStore,
  assertUserAllowed,
  safeLogUser,
  verifySignature,
} from "./auth.js";

const BRIDGE_HOST = process.env.BRIDGE_HOST ?? "127.0.0.1";
const BRIDGE_PORT = Number(process.env.BRIDGE_PORT ?? 8081);
const CHESS_HOST = process.env.CHESS_HOST ?? "127.0.0.1";
const CHESS_PORT = Number(process.env.CHESS_PORT ?? 6700);

/** Stesso limite del server: una riga piu' lunga e' un tentativo di esaurire memoria. */
const MAX_LINE_BYTES = 64 * 1024;

/** Una partita non dura all'infinito: senza tetto, una socket dimenticata resta aperta. */
const SESSION_IDLE_MS = 2 * 60 * 60 * 1000;
const SESSION_MAX_MS = 6 * 60 * 60 * 1000;

const nonces = new NonceStore({ ttlMs: 60_000 });
const stats = { connections: 0, rejected: 0, authFailures: 0 };

function log(...args) {
  const ts = new Date().toISOString().slice(11, 19);
  console.log(`[${ts}]`, ...args);
}

/**
 * Sessione autenticata: WebSocket <-> socket TCP.
 *
 * La chiama {@link handleConnection} quando la firma regge. Da quel momento
 * questo modulo possiede la connessione: ogni messaggio in arrivo e' una riga,
 * ogni riga che arriva dal server viene inoltrata come messaggio. Nessun framing
 * testo: il protocollo e' gia' a righe e raddoppiare l'encoding aggiungerebbe
 * solo punti in cui i due lati possono disaccordarsi.
 *
 * Tetto di vita e di inattività stanno qui perche' questo è l'unico punto che
 * sa se una sessione è davvero finita o solo silenziosa.
 */
function bridgeSession(ws, username) {
  const socket = connect({ host: CHESS_HOST, port: CHESS_PORT });
  socket.setNoDelay(true);

  let open = true;
  let closedReason = null;
  let buffer = "";
  let idleTimer = null;
  let maxTimer = null;

  const finish = (reason) => {
    if (!open) return;
    open = false;
    closedReason = reason;
    clearTimeout(idleTimer);
    clearTimeout(maxTimer);
    try {
      socket.destroy();
    } catch {
      /* gia' chiuso */
    }
    try {
      ws.close(1000, reason);
    } catch {
      /* gia' chiuso */
    }
  };

  const armIdle = () => {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => finish("idle timeout"), SESSION_IDLE_MS);
  };
  const armMax = () => {
    clearTimeout(maxTimer);
    maxTimer = setTimeout(() => finish("session lifetime reached"), SESSION_MAX_MS);
  };

  socket.on("connect", () => {
    log(`sessione aperta per ${safeLogUser(username)}`);
    armIdle();
    armMax();
  });

  socket.on("data", (chunk) => {
    if (!open) return;
    buffer += chunk.toString("utf8");
    // Un singolo chunk puo' contenere piu' righe: si svuota tutto, non solo la prima.
    let nl;
    while ((nl = buffer.indexOf("\n")) !== -1) {
      const line = buffer.slice(0, nl);
      buffer = buffer.slice(nl + 1);
      armIdle();
      if (ws.readyState !== ws.OPEN) break;
      ws.send(line);
    }
    if (buffer.length > MAX_LINE_BYTES) {
      log(`riga troppo lunga da ${CHESS_HOST}:${CHESS_PORT}, chiudo`);
      finish("line too long from server");
    }
  });

  socket.on("error", (err) => {
    log(`errore socket verso ${CHESS_HOST}:${CHESS_PORT}: ${err.message}`);
    finish("upstream error");
  });

  socket.on("close", () => finish("server closed the connection"));

  ws.on("message", (data, isBinary) => {
    if (!open) return;
    // Un client che invia binari sta parlando un protocollo diverso: non
    // inoltrare, o trasformerebbe una riga in byte arbitrari sul socket.
    if (isBinary) {
      finish("binary frames not allowed");
      return;
    }
    armIdle();
    const line = data.toString("utf8").replace(/\r?\n$/, "");
    if (line.length > MAX_LINE_BYTES) {
      finish("line too long from client");
      return;
    }
    socket.write(line + "\n");
  });

  ws.on("close", () => finish("client closed"));
  ws.on("error", () => finish("websocket error"));
}

const WEB_DIR = join(dirname(fileURLToPath(import.meta.url)), "web");

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml",
  ".json": "application/json; charset=utf-8",
  ".ico": "image/x-icon",
};

/**
 * Un solo processo per il bridge e la UI: il tunnel ha cosi' una sola regola
 * e non serve un secondo servizio statico da configurare.
 */
async function serveStatic(req, res) {
  const url = new URL(req.url, "http://x");
  const rel = url.pathname === "/" ? "index.html" : url.pathname.slice(1);
  // normalize + verifica del prefisso: senza, "../" uscirebbe da web/.
  const target = normalize(join(WEB_DIR, rel));
  if (!target.startsWith(WEB_DIR)) {
    res.writeHead(403).end("forbidden\n");
    return;
  }
  const ext = target.slice(target.lastIndexOf("."));
  try {
    const body = await readFile(target);
    res.writeHead(200, {
      "content-type": MIME[ext] ?? "application/octet-stream",
      // La UI non ha segreti, ma non deve nemmeno finire in cache dopo un deploy.
      "cache-control": "no-cache",
    });
    res.end(body);
  } catch {
    res.writeHead(404, { "content-type": "text/plain" }).end("not found\n");
  }
}

const http = createServer(async (req, res) => {
  // Diagnostica. Nessun dato di gioco passa da qui.
  if (req.url === "/healthz") {
    const body = JSON.stringify({
      ok: true,
      chess: `${CHESS_HOST}:${CHESS_PORT}`,
      ...stats,
    });
    res.writeHead(200, { "content-type": "application/json" });
    res.end(body);
    return;
  }
  await serveStatic(req, res);
});

const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_LINE_BYTES });

http.on("upgrade", (req, socket, head) => {
  if (new URL(req.url, "http://x").pathname !== "/ws") {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    stats.connections++;
    handleConnection(ws);
  });
});

/**
 * Macchina a due stati. Il client non puo' saltare il primo scambio: le righe
 * successive le interpreta il server TCP, ed è lui che decide se sono un
 * LOGIN legittimo.
 *
 * La socket verso il server TCP si apre **dopo** la firma, non prima. Prima di
 * allora il bridge non sa ancora chi è dall'altra parte, e chiunque — anche chi
 * verrà respinto — riuscirebbe a fargli aprire una connessione verso il
 * servizio interno.
 *
 * Quando la firma regge, il lavoro passa a {@link bridgeSession}, che possiede
 * la sessione da lì in poi: questo handler smette di ascoltare, altrimenti ogni
 * riga di protocollo verrebbe interpretata due volte.
 */
function handleConnection(ws) {
  let listening = true;

  const reject = (reason) => {
    if (!listening) return;
    listening = false;
    stats.rejected++;
    log(`rifiutato: ${reason}`);
    try {
      ws.close(4001, reason);
    } catch {
      /* noop */
    }
  };

  // Registrato prima di mandare la sfida: un frame che arriva durante
  // l'handshake non deve trovare nessuno in ascolto.
  ws.on("message", function onChallenge(data, isBinary) {
    if (isBinary) {
      reject("binary frames not allowed");
      return;
    }

    let msg;
    try {
      msg = JSON.parse(data.toString("utf8"));
    } catch {
      reject("expected a JSON auth frame");
      return;
    }

    try {
      assertUserAllowed(msg.username);
      if (!nonces.consume(msg.nonce)) {
        // Nonce gia' usato o scaduto: replay, o semplicemente una sfida vecchia.
        throw new AuthError("nonce non valido o gia' usato");
      }
      if (!verifySignature(msg)) {
        stats.authFailures++;
        throw new AuthError("firma non valida");
      }
    } catch (err) {
      reject(err instanceof AuthError ? err.reason : "auth failed");
      return;
    }

    const username = msg.username;
    listening = false;
    log(`autenticato ${safeLogUser(username)}, inoltro al server scacchi`);
    ws.off("message", onChallenge);
    bridgeSession(ws, username);
  });

  ws.on("close", () => {
    listening = false;
  });

  // Il ws è già aperto: handleUpgrade chiama questo callback dopo avere emesso
  // 'open', quindi un listener su 'open' qui non scatterebbe mai.
  ws.send(
    JSON.stringify({
      type: "challenge",
      nonce: nonces.issue(),
      algorithm: "ES256-challenge-v1",
      expiresInMs: 60_000,
    }),
  );
}

http.listen(BRIDGE_PORT, BRIDGE_HOST, () => {
  log(`bridge in ascolto su http://${BRIDGE_HOST}:${BRIDGE_PORT}/ws`);
  log(`inoltro verso tcp://${CHESS_HOST}:${CHESS_PORT}`);
  if (BRIDGE_HOST !== "127.0.0.1" && BRIDGE_HOST !== "localhost") {
    log("ATTENZIONE: ascolto su un indirizzo non-localhost. Il traffico su wss");
    log("diventa necessario, altrimenti le credenziali passano in chiaro.");
  }
});