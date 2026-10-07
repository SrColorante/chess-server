/**
 * UI del client scacchi.
 *
 * La UI non sa nulla del protocollo: ascolta 'line' e aggiorna lo stato. Un
 * errore tipico qui sarebbe interpretare i comandi dentro la UI, rendendo
 * impossibile cambiare il protocollo senza toccare l'interfaccia.
 */

import { ChessClient } from "./client.js";

// ── config ────────────────────────────────────────────────────────────
// Chiavi in chiaro solo in sviluppo locale. In produzione le chiavi arrivano
// dal keyring del browser o da un endpoint di sessione.
const CONFIG = {
  wsUrl: `wss://${location.host}/ws`,
  username: "cristianrenosto",
  publicKey: "",
  privateKey: "",
};

const $ = (id) => document.getElementById(id);
const panels = {
  auth: $("panel-auth"),
  lobby: $("panel-lobby"),
  game: $("panel-game"),
};

let client = null;
let board = null;

// ── tema ──────────────────────────────────────────────────────────────
function initTheme() {
  const saved = localStorage.getItem("theme");
  const prefersDark = matchMedia("(prefers-color-scheme: dark)").matches;
  const theme = saved ?? (prefersDark ? "dark" : "light");
  document.documentElement.dataset.theme = theme;
  $("theme").addEventListener("click", () => {
    const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    localStorage.setItem("theme", next);
  });
}

function show(name) {
  for (const [k, el] of Object.entries(panels)) el.hidden = k !== name;
}

function setStatus(state, text) {
  $("status").querySelector(".dot").dataset.state = state;
  $("status-text").textContent = text;
}

function log(line) {
  const li = document.createElement("li");
  li.textContent = line;
  $("log").prepend(li);
  while ($("log").childElementCount > 60) $("log").lastElementChild.remove();
}

// ── scacchiera ────────────────────────────────────────────────────────
const FILES = "abcdefgh";
const RANKS = "87654321";
const GLYPH = {
  k: "♚", q: "♛", r: "♜", b: "♝", n: "♞", p: "♟",
};

const START =
  "rnbqkbnr" +
  "pppppppp" +
  "........" +
  "........" +
  "........" +
  "........" +
  "PPPPPPPP" +
  "RNBQKBNR";

function startBoard() {
  return START.split("").map((p, i) => ({
    square: FILES[i % 8] + RANKS[Math.floor(i / 8)],
    piece: p === "." ? null : { type: p.toLowerCase(), white: p === p.toUpperCase() },
  }));
}

/** FEN -> mappa casella/pezzo. Tolto il primo campo (indice di mossa). */
function boardFromFen(fen) {
  const rows = fen.split(" ")[0].split("/");
  if (rows.length !== 8) return startBoard();
  const out = [];
  rows.forEach((row, r) => {
    let f = 0;
    for (const ch of row) {
      if (/\d/.test(ch)) {
        for (let k = 0; k < Number(ch); k++) {
          out.push({ square: FILES[f] + RANKS[r], piece: null });
          f++;
        }
      } else {
        out.push({
          square: FILES[f] + RANKS[r],
          piece: { type: ch.toLowerCase(), white: ch === ch.toUpperCase() },
        });
        f++;
      }
    }
  });
  return out;
}

function render(cells, { selected = null, last = null } = {}) {
  const host = $("board");
  host.replaceChildren();
  for (const { square, piece } of cells) {
    const file = square[0];
    const rank = square[1];
    const el = document.createElement("div");
    el.className = "sq " + ((FILES.indexOf(file) + RANKS.indexOf(rank)) % 2 ? "d" : "l");
    el.dataset.sq = square;
    if (selected === square) el.classList.add("sel");
    if (last === square) el.classList.add("last");
    if (piece) {
      const p = document.createElement("span");
      p.className = "piece " + (piece.white ? "w" : "b");
      p.dataset.k = piece.type;
      p.textContent = GLYPH[piece.type];
      el.append(p);
    }
    host.append(el);
  }
}

// ── protocollo ────────────────────────────────────────────────────────
function onLine(text) {
  log(text);

  const [verb, ...rest] = text.split(":");
  const arg = rest.join(":");

  switch (verb) {
    case "WELCOME":
      setStatus("open", "Connesso");
      show("lobby");
      break;

    case "AUTH_OK":
      show("lobby");
      log("autenticato: " + arg);
      break;

    case "AUTH_ERROR":
      show("auth");
      $("err-auth").textContent = arg || "credenziali rifiutate";
      $("err-auth").hidden = false;
      setStatus("error", "Accesso negato");
      break;

    case "ROOMS": {
      const body = $("rooms").querySelector("tbody");
      body.replaceChildren();
      for (const room of arg.split(/[;,]/).map((r) => r.trim()).filter(Boolean)) {
        const [id, players] = room.split("/");
        const tr = document.createElement("tr");
        const tdId = document.createElement("td");
        tdId.textContent = id;
        const tdP = document.createElement("td");
        tdP.textContent = players ?? "—";
        const tdA = document.createElement("td");
        const btn = document.createElement("button");
        btn.textContent = "Entra";
        btn.addEventListener("click", () => {
          client.send("JOIN:" + id);
          show("game");
        });
        tdA.append(btn);
        tr.append(tdId, tdP, tdA);
        body.append(tr);
      }
      break;
    }

    case "BOARD": {
      board = boardFromFen(arg);
      render(board);
      show("game");
      break;
    }

    case "MOVE":
    case "GAME":
      $("moves").textContent = arg || "—";
      break;

    case "PING":
      client.send("PING");
      break;
  }
}

async function connect() {
  $("err-auth").hidden = true;
  setStatus("connecting", "Connessione…");

  client = new ChessClient({
    url: CONFIG.wsUrl,
    username: CONFIG.username,
    publicKey: CONFIG.publicKey,
    privateKey: CONFIG.privateKey,
  });
  client.addEventListener("line", (e) => onLine(e.detail.text));
  client.addEventListener("closed", () => setStatus("closed", "Disconnesso"));

  try {
    await client.connect();
    log("bridge raggiunto, sfida in corso");
  } catch (e) {
    setStatus("error", "Errore");
    $("err-auth").textContent = e.message;
    $("err-auth").hidden = false;
  }
}

// ── init ──────────────────────────────────────────────────────────────
initTheme();
render(startBoard());
show("auth");
setStatus("idle", "Non connesso");
$("conn-info").textContent = new URL(CONFIG.wsUrl).host;

$("form-auth").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const password = $("password").value;
  // La password non viene mai salvata: si usa e si abbandona.
  await connect();
  if (client?.phase === "open" || client?.ws?.readyState === WebSocket.OPEN) {
    client.login(password);
  }
  $("password").value = "";
});

$("btn-register").addEventListener("click", async () => {
  const password = $("password").value;
  await connect();
  if (client?.ws?.readyState === WebSocket.OPEN) client.register(password);
  $("password").value = "";
});

$("btn-list").addEventListener("click", () => client?.send("LIST_ROOMS"));
$("btn-disconnect").addEventListener("click", () => {
  client?.close();
  show("auth");
  setStatus("closed", "Disconnesso");
});