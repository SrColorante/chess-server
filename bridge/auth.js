/**
 * Challenge-response per il bridge scacchi.
 *
 * Perche' non basta HTTPS: Cloudflare cifra il trasporto browser -> edge, ma
 * chiunque apra wss://puo' arrivare al server. HTTPS dice "questi due endpoint
 * sono loro", non "questo e' il tuo utente". Su una WebSocket non si puo'
 * usare un cookie di sessione perche' il browser non manda header
 * Authorization, quindi l'autenticazione deve stare nel primo messaggio.
 *
 * Il protocollo e' una sfida firmata: il bridge emette un nonce casuale, il
 * client risponde firmandolo con la propria chiave privata, il bridge verifica
 * con la pubblica. La password non attraversa mai la rete in nessuna forma,
 * e il nonce impedisce il replay di una risposta catturata.
 */

import { createHash, createPublicKey, randomBytes, verify } from "node:crypto";

/** Username ammesso: lo stesso dominio di vincolo del DB, piu' il prefisso. */
const USERNAME_RE = /^[a-z0-9_.-]{1,24}$/i;

/** Il dominio e' parte del contratto: un account site puo' giocare, un altro no. */
const ALLOWED_EMAIL_DOMAIN = "@cristianrenosto.party";

/**
 * Non finire in `utenti`: quel dominio include anche gli indirizzi di servizio.
 * L'elenco e' esplicito perche' un filtro automatico un domani ammette
 * alias che non sono giocatori.
 */
const ALLOWED_USERS = new Set(["cristianrenosto"]);

export class AuthError extends Error {
  constructor(reason, detail) {
    super(reason);
    this.name = "AuthError";
    this.reason = reason;
    this.detail = detail;
  }
}

/** Non azzera il nonce a ogni richiesta: deve valere una volta sola. */
export class NonceStore {
  constructor({ ttlMs = 60_000, max = 512 } = {}) {
    this.ttlMs = ttlMs;
    this.max = max;
    this.seen = new Map();
  }

  issue() {
    // 32 byte casuali. Un nonce da 16 bit si indovina durante una partita.
    const nonce = randomBytes(32).toString("base64url");
    const now = Date.now();
    this.seen.set(nonce, now + this.ttlMs);
    this.#evict(now);
    return nonce;
  }

  /** `true` se il nonce era valido e non ancora usato. Consumarlo sempre. */
  consume(nonce) {
    if (typeof nonce !== "string" || nonce.length === 0) return false;
    const expires = this.seen.get(nonce);
    if (expires === undefined) return false;
    this.seen.delete(nonce);
    return Date.now() <= expires;
  }

  /** Non far crescere la mappa se il bridge riceve migliaia di handshake. */
  #evict(now) {
    for (const [k, exp] of this.seen) {
      if (exp <= now) this.seen.delete(k);
    }
    while (this.seen.size > this.max) {
      const oldest = this.seen.keys().next().value;
      this.seen.delete(oldest);
    }
  }
}

/**
 * Firma canonica: `username|nonce`. Il separatore `|` non puo' comparire in
 * uno username (c'e' il suo controllo), quindi non c'e' ambiguit'eta fra
 * ("a|b", "c") e ("a", "b|c"): il secondo username non sarebbe valido.
 */
export function buildChallenge(username, nonce) {
  return `chess-challenge-v1|${username}|${nonce}`;
}

export function challengeDigest(challenge) {
  return createHash("sha256").update(challenge, "utf8").digest("base64url");
}

/**
 * Verifica la risposta del client.
 *
 * @param {object} p
 * @param {string} p.username  username dichiarato
 * @param {string} p.nonce     nonce emesso dal bridge
 * @param {string} p.publicKey chiave pubblica PEM del client
 * @param {string} p.signature firma base64url del digest
 * @returns {boolean}
 */
export function verifySignature({ username, nonce, publicKey, signature }) {
  let key;
  try {
    key = createPublicKey(String(publicKey ?? ""));
  } catch {
    // Chiave malformata: non e' un errore da propagare, e' un auth fallito.
    return false;
  }
  // La firma copre il testo della sfida grezzo, non il digest: cosi' il client
  // non deve ricostruire l'hashing e non puo' sbagliare la codifica.
  const challenge = Buffer.from(buildChallenge(username, nonce), "utf8");
  const sig = Buffer.from(String(signature ?? ""), "base64url");
  if (sig.length === 0) return false;
  // verify() fa internamente il confronto a tempo costante.
  return verify("sha256", challenge, key, sig);
}

/**
 * Decide se un utente puo' giocare, indipendentemente dalla firma.
 * Separato dal challenge cosi' si puo' negare l'accesso per motivi di
 * autorizzazione senza toccare la verifica crittografica.
 */
export function assertUserAllowed(username) {
  if (typeof username !== "string" || !USERNAME_RE.test(username)) {
    throw new AuthError("username non valido");
  }
  const email = `${username.toLowerCase()}`.replace(/[^a-z0-9]/g, "") + ALLOWED_EMAIL_DOMAIN;
  if (!ALLOWED_USERS.has(username.toLowerCase())) {
    throw new AuthError("utente non autorizzato", email);
  }
  return username;
}

/** Non registrare mai la firma: e' derivata dalla chiave privata. */
export function safeLogUser(username) {
  return String(username).slice(0, 24).replace(/[^\w.-]/g, "_");
}