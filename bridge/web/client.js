/**
 * Client del protocollo scacchi via WebSocket.
 *
 * Il server parla righe di testo; il browser riceve un oggetto per riga. Questo
 * modulo e' l'unico punto che parla col bridge, cosi' la UI non conosce il
 * protocollo e i test possono sostituirlo.
 *
 * Una cosa da tenere presente: le credenziali NON passano da qui. Il bridge
 * autentica con una firma, e la password la manda solo al server scacchi
 * attraverso il bridge, mai al browser oltre TLS.
 */

const CHALLENGE_ALGO = "ES256-challenge-v1";

export class ChessClient extends EventTarget {
  constructor({ url, username, privateKey, publicKey }) {
    super();
    this.url = url;
    this.username = username;
    this.privateKey = privateKey;
    this.publicKey = publicKey;
    this.ws = null;
    this.key = null;
    this.phase = "idle";
  }

  emit(type, detail = {}) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }

  /**
   * Apre la connessione e risolve quando la sfida e' stata risolta.
   * Le righe di protocollo successive arrivano come eventi 'line'.
   */
  async connect() {
    if (this.phase === "open" || this.phase === "connecting") return;
    this.phase = "connecting";
    this.emit("status", { state: "connecting" });

    const ws = new WebSocket(this.url);
    this.ws = ws;

    await new Promise((resolve, reject) => {
      const onOpen = () => {
        ws.removeEventListener("error", onErr);
        resolve();
      };
      const onErr = () => {
        ws.removeEventListener("open", onOpen);
        reject(new Error("connessione al bridge fallita"));
      };
      ws.addEventListener("open", onOpen, { once: true });
      ws.addEventListener("error", onErr, { once: true });
    });

    ws.addEventListener("message", (ev) => this.#onMessage(ev.data));
    ws.addEventListener("close", () => {
      const was = this.phase;
      this.phase = "closed";
      this.emit("status", { state: "closed" });
      if (was !== "closed") this.emit("closed");
    });

    this.#awaitChallenge();
  }

  async #awaitChallenge() {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("sfida scaduta")), 65_000);
      this.#once("challenge", (detail) => {
        clearTimeout(timer);
        resolve(detail);
      });
      this.#once("authFailed", (detail) => {
        clearTimeout(timer);
        reject(new Error(detail.reason || "autenticazione rifiutata"));
      });
    });
  }

  async #onMessage(raw) {
    const text = String(raw);

    if (text.startsWith("{")) {
      const msg = JSON.parse(text);
      if (msg.type === "challenge") {
        this.emit("challenge", msg);
        try {
          const sig = await this.#sign(msg.nonce);
          this.sendRaw(
            JSON.stringify({
              username: this.username,
              nonce: msg.nonce,
              publicKey: this.publicKey,
              signature: sig,
            }),
          );
        } catch (e) {
          this.emit("authFailed", { reason: e.message });
        }
        return;
      }
    }

    this.emit("line", { text });

    // WELCOME e' la prima riga: segnala che il bridge ha inoltrato al server.
    if (text.startsWith("WELCOME:")) {
      this.phase = "open";
      this.emit("status", { state: "open" });
    }
    if (text.startsWith("AUTH_OK")) {
      this.emit("authenticated", { text });
    }
    if (text.startsWith("AUTH_ERROR")) {
      this.emit("authFailed", { reason: text });
    }
  }

  async #sign(nonce) {
    if (!this.key) {
      this.key = await importPrivateKey(this.privateKey);
    }
    const challenge = new TextEncoder().encode(`chess-challenge-v1|${this.username}|${nonce}`);
    const sig = await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      this.key,
      challenge,
    );
    return base64url(new Uint8Array(sig));
  }

  /** Invia un comando. Il bridge aggiunge il terminatore di riga. */
  send(cmd) {
    this.sendRaw(String(cmd));
  }

  sendRaw(payload) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error("non connesso");
    }
    this.ws.send(payload);
  }

  /**LOGIN passa dal client al server attraverso il bridge, non al bridge. */
  login(password) {
    this.send(`LOGIN:${this.username}:${password}`);
  }

  register(password) {
    this.send(`REGISTER:${this.username}:${password}`);
  }

  guest(nickname) {
    this.send(`GUEST:${nickname}`);
  }

  close() {
    try {
      this.ws?.close();
    } catch {
      /* noop */
    }
  }

  #once(type, fn) {
    const h = (e) => {
      this.removeEventListener(type, h);
      fn(e.detail);
    };
    this.addEventListener(type, h);
  }
}

function base64url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function importPrivateKey(pem) {
  const der = pemToArrayBuffer(pem);
  return crypto.subtle.importKey("pkcs8", der, { name: "ECDSA", namedCurve: "P-256" }, false, [
    "sign",
  ]);
}

function pemToArrayBuffer(pem) {
  const body = pem
    .replace(/-----BEGIN [^-]+-----/g, "")
    .replace(/-----END [^-]+-----/g, "")
    .replace(/\s+/g, "");
  const bin = atob(body);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

export { CHALLENGE_ALGO };