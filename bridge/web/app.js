/**
 * UI del client scacchi.
 *
 * La UI non implementa le regole: ascolta le righe che il bridge inoltra dal
 * server e le traduce in stato. Un errore tipico qui sarebbe interpretare i
 * comandi dentro la UI, rendendo impossibile cambiare il protocollo senza
 * toccare l'interfaccia.
 *
 * I verbi sono quelli del server reale (vedi docs.md §4): LIST_ROOMS risponde
 * ROOMS_LIST, si entra con JOIN_ROOM, la scacchiera arriva come BOARD: a
 * 78 caratteri, e a ogni PING si risponde PONG. Una versione precedente di
 * questo file usava una grammatica inventata (ROOMS:, JOIN:, MOVE:, BOARD: in
 * FEN) che nessun server scacchiera ha mai parlato.
 */

import { ChessClient } from "./client.js";
import {
  FILES,
  RANKS,
  PIECE_GLYPH,
  START_POSITION,
  parseBoardCompact,
  parseRoomsList,
  parseRoomCreated,
  lastSquareFromMoveOk,
} from "./protocol.js";

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
/** Griglia di 64 celle {square, piece} nell'ordine di renderBoardCompact: rank 8 per primo. */
let cells = [];
/** Colore assegnato dal server con ASSIGNED_COLOR. */
let myColor = null;
let selected = null;
let lastMove = null;
let state = "idle";

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

function setStatus(next, text) {
  state = next;
  $("status").querySelector(".dot").dataset.state = next;
  $("status-text").textContent = text;
}

function setGameStatus(text) {
  $("game-status").textContent = text;
}

function log(line) {
  const li = document.createElement("li");
  li.textContent = line;
  $("log").prepend(li);
  while ($("log").childElementCount > 60) $("log").lastElementChild.remove();
}

// ── scacchiera ────────────────────────────────────────────────────────
/**
 * Posizione iniziale: nessun pezzo mosso, nessuna casella da evidenziare.
 * La stringa è quella che il server stampa in `renderBoardCompact()`.
 */
function startCells() {
  const parsed = parseBoardCompact(START_POSITION + "w");
  return parsed ? parsed.cells : [];
}

/** Griglia vuota, solo per il bordo: caselle senza pezzi. */
function emptyBoard() {
  const out = [];
  for (let r = 0; r < 8; r++) {
    for (let f = 0; f < 8; f++) {
      out.push({ square: FILES[f] + RANKS[r], piece: null });
    }
  }
  return out;
}

function render() {
  const host = $("board");
  host.replaceChildren();
  const bySquare = new Map(cells.map((c) => [c.square, c.piece]));
  for (let r = 0; r < 8; r++) {
    for (let f = 0; f < 8; f++) {
      const square = FILES[f] + RANKS[r];
      const piece = bySquare.get(square);
      const el = document.createElement("div");
      el.className = "sq " + ((f + r) % 2 ? "d" : "l");
      el.dataset.sq = square;
      if (selected === square) el.classList.add("sel");
      if (lastMove === square) el.classList.add("last");
      if (piece) {
        const p = document.createElement("span");
        p.className = "piece " + (piece.white ? "w" : "b");
        p.dataset.k = piece.type;
        p.textContent = PIECE_GLYPH[piece.type] ?? "•";
        el.append(p);
      }
      host.append(el);
    }
  }
}

/**
 * Un click su una casella: se c'e' un pezzo del proprio colore si seleziona,
 * se c'e' una selezione si manda `MOVE:from:to`. Il server convalida la mossa:
 * la UI non genera mosse legali, altrimenti dovrebbe reimplementare le regole.
 */
function onSquareClick(square) {
  if (!client || client.phase !== "open") return;
  if (state !== "playing") return;

  const piece = cells.find((c) => c.square === square)?.piece;
  if (selected === null) {
    if (!piece) return;
    if (myColor !== null && piece.white !== myColor) return;
    selected = square;
    render();
    return;
  }
  if (selected === square) {
    selected = null;
    render();
    return;
  }
  if (piece && myColor !== null && piece.white === myColor) {
    selected = square;
    render();
    return;
  }
  const from = selected;
  selected = null;
  render();
  log("mossa " + from + "-" + square + " inviata");
  client.send("MOVE:" + from + ":" + square);
}

$("board").addEventListener("click", (ev) => {
  const sq = ev.target.closest(".sq");
  if (sq) onSquareClick(sq.dataset.sq);
});

// ── protocollo ────────────────────────────────────────────────────────
function renderRooms(rooms) {
  const body = $("rooms").querySelector("tbody");
  body.replaceChildren();
  if (rooms.length === 0) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = 4;
    td.textContent = "Nessuna stanza aperta. Creane una.";
    tr.append(td);
    body.append(tr);
    return;
  }
  for (const room of rooms) {
    const tr = document.createElement("tr");
    for (const value of [room.code, room.name || "—", room.host || "—"]) {
      const td = document.createElement("td");
      td.textContent = value;
      tr.append(td);
    }
    const td = document.createElement("td");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = "Entra";
    btn.addEventListener("click", () => joinRoom(room.code));
    td.append(btn);
    tr.append(td);
    body.append(tr);
  }
}

function joinRoom(code) {
  if (!client || client.phase !== "open") return;
  client.send("JOIN_ROOM:" + code);
  setGameStatus("ingresso nella stanza " + code + "…");
  log("JOIN_ROOM:" + code);
}

function onLine(text) {
  log(text);

  const colon = text.indexOf(":");
  const verb = colon === -1 ? text : text.slice(0, colon);
  const payload = colon === -1 ? "" : text.slice(colon + 1);

  switch (verb) {
    case "WELCOME":
      setStatus("open", "Connesso a " + (payload || "server"));
      show("lobby");
      client.send("LIST_ROOMS");
      break;

    case "AUTH_OK":
      show("lobby");
      log("autenticato: " + payload);
      setStatus("open", "Autenticato");
      break;

    case "AUTH_ERROR":
      show("auth");
      $("err-auth").textContent = payload || "credenziali rifiutate";
      $("err-auth").hidden = false;
      setStatus("error", "Accesso negato");
      break;

    case "ERROR":
      setGameStatus(payload || "errore");
      break;

    case "INFO":
      setGameStatus(payload || "");
      break;

    case "ROOMS_LIST":
      renderRooms(parseRoomsList(payload));
      break;

    case "ROOM_CREATED": {
      // `ROOM_CREATED:<nome>:<codice>:<privata>` — l'ultimo campo è un booleano.
      const room = parseRoomCreated(payload);
      show("game");
      if (room) {
        setGameStatus("stanza " + room.code + ", in attesa dell'avversario…");
        log("stanza " + room.code + " (" + room.name + ")");
      } else {
        setGameStatus("in attesa dell'avversario…");
        log("ROOM_CREATED non riconosciuto: " + payload);
      }
      break;
    }

    case "JOIN_OK":
      setGameStatus(payload || "in partita");
      break;

    case "JOIN_ERROR":
      show("lobby");
      setGameStatus(payload || "impossibile entrare");
      break;

    case "MATCH_START":
      state = "playing";
      selected = null;
      show("game");
      setGameStatus("partita in corso");
      break;

    case "ASSIGNED_COLOR":
      myColor = payload === "BLACK" ? false : true;
      $("my-color").textContent = payload === "BLACK" ? "Nero" : "Bianco";
      break;

    case "BOARD": {
      const parsed = parseBoardCompact(payload);
      if (parsed) {
        cells = parsed.cells;
        render();
      } else {
        log("BOARD non valido: " + payload);
      }
      break;
    }

    case "MOVE_OK":
      // `MOVE_OK:<ruolo> moved <da> -> <a>` — il turno è gia' cambiato.
      lastMove = lastSquareFromMoveOk(payload);
      $("moves").textContent = payload || "—";
      // Il BOARD aggiornato arriva subito dopo, ma ridisegnare qui evita che
      // l'evidenziazione dell'ultima mossa resti indietro di un fotogramma.
      render();
      break;

    case "MOVE_ERROR":
      setGameStatus(payload || "mossa rifiutata");
      break;

    case "GAME_OVER":
      state = "finished";
      selected = null;
      setStatus("open", "Partita conclusa");
      setGameStatus(payload || "partita conclusa");
      log("fine partita: " + payload);
      break;

    case "PING":
      // Il server fa da keepalive e si aspetta PONG, non PING: rimandarglielo
      // indietro lo farebbe contare come il battito del proprio terminale.
      client?.send("PONG");
      break;

    case "PONG":
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
  client.addEventListener("closed", () => {
    setStatus("closed", "Disconnesso");
    state = "idle";
    myColor = null;
    selected = null;
    show("auth");
  });

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
cells = startCells();
render();
show("auth");
setStatus("idle", "Non connesso");
$("conn-info").textContent = new URL(CONFIG.wsUrl).host;
setGameStatus("in attesa");

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

$("btn-list").addEventListener("click", () => {
  client?.send("LIST_ROOMS");
  show("lobby");
});

$("form-create").addEventListener("submit", (ev) => {
  ev.preventDefault();
  if (!client || client.phase !== "open") return;
  const name = $("room-name").value.trim().slice(0, 32);
  if (!name) return;
  client.send("CREATE_ROOM:" + name);
  setGameStatus("creazione della stanza…");
  log("CREATE_ROOM:" + name);
});

$("form-join").addEventListener("submit", (ev) => {
  ev.preventDefault();
  joinRoom($("room-code").value.trim().toUpperCase());
});

$("btn-leave").addEventListener("click", () => {
  if (!client) return;
  client.send("RESIGN");
  selected = null;
  state = "finished";
  setGameStatus("partita abbandonata");
});

$("btn-disconnect").addEventListener("click", () => {
  client?.close();
  state = "idle";
  myColor = null;
  selected = null;
  show("auth");
  setStatus("closed", "Disconnesso");
});
