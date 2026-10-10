/**
 * Il protocollo in forma verificabile.
 *
 * Il server di scacchi parla `KEY:payload`, una riga per messaggio. Questo
 * modulo contiene l'unica traduzione fra quelle righe e le strutture che la UI
 * usa, cosi' la grammatica sta in un punto solo e si puo' provare con `node
 * test-protocol.js` invece di scoprirla a schermo.
 *
 * I formati sono quelli di ServerMain e ConnectionHandler (docs.md §4.2-4.3).
 */

export const FILES = "abcdefgh";
export const RANKS = "87654321";

export const PIECE_GLYPH = {
  k: "♚",
  q: "♛",
  r: "♜",
  b: "♝",
  n: "♞",
  p: "♟",
};

/** Le otto traverse della posizione iniziale, separate da `/`. */
export const START_RANKS = [
  "rnbqkbnr",
  "pppppppp",
  "........",
  "........",
  "........",
  "........",
  "PPPPPPPP",
  "RNBQKBNR",
];

/** Le stesse 71 caselle nel formato di `renderBoardCompact()`, turno escluso. */
export const START_POSITION = START_RANKS.join("/");

/**
 * `BOARD:` + 8 gruppi di 8 caratteri separati da `/` + il carattere del turno:
 * 72 caratteri di payload, 78 di riga. `Piece.toString()` del server è la
 * lettera FEN, maiuscola per il bianco e punto per la casella vuota.
 *
 * Restituisce null su qualunque forma diversa: una scacchiera mezza rotta non
 * va disegnata a metà, va rifiutata e registrata.
 */
export function parseBoardCompact(payload) {
  if (typeof payload !== "string" || payload.length !== 72) return null;
  const turn = payload[71];
  if (turn !== "w" && turn !== "b") return null;
  const rows = payload.slice(0, 71).split("/");
  if (rows.length !== 8) return null;
  const cells = [];
  for (let r = 0; r < 8; r++) {
    const row = rows[r];
    if (row.length !== 8) return null;
    for (let f = 0; f < 8; f++) {
      const ch = row[f];
      // Il formato compatto è un carattere per casella: una cifra o qualunque
      // altro simbolo non è una casella vuota, è un frame corrotto. Accettarlo
      // vorrebbe dire disegnare un pezzo che non esiste.
      if (ch !== "." && !/[a-zA-Z]/.test(ch)) return null;
      const piece = ch === "." ? null : { type: ch.toLowerCase(), white: ch === ch.toUpperCase() };
      cells.push({ square: FILES[f] + RANKS[r], piece });
    }
  }
  return { cells, turn };
}

/** `ROOMS_LIST:<n>;<codice>|<nome>|<host>;…` — conteggio davanti, `|` nei campi. */
export function parseRoomsList(payload) {
  if (typeof payload !== "string") return [];
  const entries = payload.split(";");
  const declared = Number(entries[0]);
  if (!Number.isInteger(declared) || declared < 0) return [];
  const rooms = [];
  for (const entry of entries.slice(1)) {
    if (!entry) continue;
    const [code, name, host] = entry.split("|");
    if (!code) continue;
    rooms.push({ code, name: name ?? "", host: host ?? "" });
  }
  // Il conteggio dichiarato è un contratto: se non torna, la risposta è stata
  // troncata o corrotta, e mostrare una lista parziale è peggio che nessuna.
  return rooms.length === declared ? rooms : [];
}

/** `ROOM_CREATED:<nome>:<codice>:<privata>` — i `:` sono vietati nei nomi. */
export function parseRoomCreated(payload) {
  if (typeof payload !== "string") return null;
  const parts = payload.split(":");
  if (parts.length !== 3) return null;
  const [name, code, flag] = parts;
  if (flag !== "true" && flag !== "false") return null;
  return { name, code, isPrivate: flag === "true" };
}

/**
 * `MOVE_OK:<ruolo> moved <da> -> <a>` e `MOVE_ERROR:<motivo>`. Da MOVE_OK serve
 * solo l'ultima casella, per evidenziarla: il resto è testo per il registro.
 */
export function lastSquareFromMoveOk(payload) {
  const match = /->\s*([a-h][1-8])$/.exec(String(payload ?? ""));
  return match ? match[1] : null;
}
