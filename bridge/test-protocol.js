/**
 * Test del modulo di protocollo della UI web.
 *
 *   node test-protocol.js
 *
 * I valori attesi sono copiati da `renderBoardCompact()` e dal costruttore di
 * `ROOMS_LIST` in ServerMain: se una delle due parti cambia forma, questo test
 * fallisce prima che lo faccia la partita di qualcuno.
 */

import {
  parseBoardCompact,
  parseRoomsList,
  parseRoomCreated,
  lastSquareFromMoveOk,
  START_POSITION,
} from "./web/protocol.js";

let passed = 0;
let failed = 0;
function check(name, pass, detail = "") {
  if (pass) {
    passed++;
    console.log("  ok   " + name);
  } else {
    failed++;
    console.log(" FAIL  " + name + (detail ? "  [" + detail + "]" : ""));
  }
}

// ── BOARD ─────────────────────────────────────────────────────────────
{
  // La riga esatta che produce il server all'inizio di una partita.
  const line = "BOARD:rnbqkbnr/pppppppp/......../......../......../......../PPPPPPPP/RNBQKBNRw";
  check("la riga BOARD del server è lunga 78 caratteri", line.length === 78, "len=" + line.length);

  const parsed = parseBoardCompact(line.slice(6));
  check("BOARD iniziale decodificato", parsed !== null);
  if (parsed) {
    check("64 caselle", parsed.cells.length === 64, "n=" + parsed.cells.length);
    check("turno al bianco", parsed.turn === "w");
    check("la prima casella è a8 con un pezzo nero", parsed.cells[0].square === "a8" && parsed.cells[0].piece.type === "r" && parsed.cells[0].piece.white === false);
    check("l'ultima casella è h1 con un pezzo bianco", parsed.cells[63].square === "h1" && parsed.cells[63].piece.type === "r" && parsed.cells[63].piece.white === true);
    check("e4 è vuota", parsed.cells.find((c) => c.square === "e4").piece === null);
  }
}

{
  // Dopo 1. e4 il server manda il payload con il turno al nero.
  const after = "rnbqkbnr/pppp.ppp/......../....p.../....P.../......../PPPP.PPP/RNBQKBNRb";
  const parsed = parseBoardCompact(after);
  check("BOARD dopo 1.e4 decodificato", parsed !== null && parsed.turn === "b");
  if (parsed) {
    const e4 = parsed.cells.find((c) => c.square === "e4");
    const e2 = parsed.cells.find((c) => c.square === "e2");
    check("e4 ha un pedone bianco", e4.piece && e4.piece.type === "p" && e4.piece.white === true);
    check("e2 è vuota", e2.piece === null);
  }
}

check("BOARD con turno invalido respinto", parseBoardCompact("r".repeat(71) + "x") === null);
check("BOARD troppo corto respinto", parseBoardCompact("rnbqkbnr") === null);
check("BOARD con 7 file respinto", parseBoardCompact("rnbqkbn/pppppppp/......../......../......../......../PPPPPPPP/RNBQKBNRw") === null);
check("BOARD vuoto respinto", parseBoardCompact("") === null);
check("BOARD non-stringa respinto", parseBoardCompact(undefined) === null);
// Il formato compatto è un carattere per casella: le cifre sono della notazione
// FEN, non di questo frame. Accettarle creerebbe pezzi che non esistono.
check("BOARD con cifre al posto dei punti respinto", parseBoardCompact("rnbqkbnr/pppp1ppp/......../....p.../....P.../......../PPPP.PPP/RNBQKBNRw") === null);
check("BOARD con simboli estranei respinto", parseBoardCompact("rnbqkbnr/pppppppp/......../......../......../......../PPPPPPPP/RNBQKBNw") === null);
check("START_POSITION è lungo 71 caratteri", START_POSITION.length === 71, "len=" + START_POSITION.length);

// ── ROOMS_LIST ────────────────────────────────────────────────────────
{
  const rooms = parseRoomsList("2;ABC234|Partita web|ospite;XYZ789|Sala|cristianrenosto");
  check("due stanze decodificate", rooms.length === 2, "n=" + rooms.length);
  check("codice e nome della prima", rooms[0].code === "ABC234" && rooms[0].name === "Partita web");
  check("host della seconda", rooms[1].host === "cristianrenosto");
}

{
  const rooms = parseRoomsList("0");
  check("lista vuota decodificata", Array.isArray(rooms) && rooms.length === 0);
}

{
  // Il nome di una stanza non può contenere `|`: il server lo vieta, ma se il
  // conteggio non torna la risposta è corrotta e non va mostrata.
  const rooms = parseRoomsList("3;AAA111|Sala|ospite");
  check("conteggio non corrispondente respinto", rooms.length === 0, "n=" + rooms.length);
}

check("conteggio negativo respinto", parseRoomsList("-1").length === 0);
check("conteggio non numerico respinto", parseRoomsList("abc;AAA111|Sala|ospite").length === 0);
check("voce senza codice saltata", parseRoomsList("1;|Sala|ospite").length === 0);

// ── ROOM_CREATED ──────────────────────────────────────────────────────
{
  const room = parseRoomCreated("Partita Web:34QUD7:false");
  check("ROOM_CREATED decodificato", room !== null);
  if (room) {
    check("nome, codice e flag", room.name === "Partita Web" && room.code === "34QUD7" && room.isPrivate === false);
  }
}
check("ROOM_CREATED privata", parseRoomCreated("Segreta:AAAAAA:true").isPrivate === true);
check("ROOM_CREATED con flag non booleano respinto", parseRoomCreated("Sala:AAAAAA:forse") === null);
check("ROOM_CREATED con campi mancanti respinto", parseRoomCreated("Sala:AAAAAA") === null);

// ── MOVE_OK ───────────────────────────────────────────────────────────
check("ultima casella estratta da MOVE_OK", lastSquareFromMoveOk("Host moved e2 -> e4") === "e4");
check("mossa del nero riconosciuta", lastSquareFromMoveOk("Guest moved d8 -> h4") === "h4");
check("payload senza mossa: null", lastSquareFromMoveOk("Mossa rifiutata") === null);

console.log("\n" + passed + "/" + (passed + failed) + " verifiche superate");
process.exit(failed === 0 ? 0 : 1);
