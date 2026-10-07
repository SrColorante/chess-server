# Security hardening del chess-server

Data: 2026-10-07 · Specialisti: chess-server (progetto) + security (settore) · Linguaggio body: italiano

## Correzione di partenza che cambia il brief

Il SOUL.md del progetto dichiara **Node.js (package.json)**. È sbagliato, e entrambi gli specialisti
l'hanno trovato leggendo il codice:

- `package.json` è scaffolding Neon, 8 righe, nessuno `scripts`, `neon`/`@neon/config` non importati da nessun codice.
- Il server è **Java 11+ puro**, build con `javac` (`build.sh`), entrypoint `server.ServerMain`.
- Protocollo: **TCP raw, newline-delimited, testo `KEY:payload`**. Né HTTP né WebSocket. Nessun vettore
  XSS/CSRF/cookie/header injection. DB via JDBC (`lib/postgresql.jar` vendrato).
- Deploy: `railway.json` + `nixpacks.toml` ⇒ **pubblico su Railway**, bind wildcard `0.0.0.0`, porta 6700.

Conseguenza: questo piano è costruito su primitive JDK (`java.net`, `java.util.concurrent`, `java.security`)
e sui due jar vendorati. **Va aggiornato anche il SOUL.md del progetto**, che oggi fa perdere tempo a chiunque ragioni su npm/WebSocket.

## La scala delle cose da fare

1. **Chiudere il loop `DELETE_ACCOUNT` pre-auth** — priority: high · effort: S · owner: security · depends on: —
   `ServerMain.java:183-189`. Il branch `DELETE_ACCOUNT:` sta dentro `while (!authenticated)` e chiama
   `ServerAccountHandler.deleteAccount()` → `login()` → PBKDF2 120k iterazioni, ma **non passa mai dal
   check `failedAuthAttempts >= MAX_AUTH_ATTEMPTS`** che copre solo `LOGIN:`/`REGISTER:` (righe 138-149).
   Risultato: loop di login infinito e non limitato da un singolo socket, e `deleteAccount` è `synchronized`
   ⇒ 128 connessioni serializzano 128 hash su un monitor globale. Fix: sposta il branch dopo
   `authenticated == true`, richiedi che `parts[1] == currentUser`, e applicalo allo stesso contatore.

2. **Rate limit per IP su connessioni e auth** — priority: high · effort: M · owner: security · depends on: —
   `ServerMain.java:65-82, 118`. `MAX_CONNECTIONS = 128` è un semaforo **globale**, e ogni socket accettato
   occupa un thread del pool in `in.read()` con `SO_TIMEOUT = AUTH_TIMEOUT_MS = 300_000`. Attacco verificato:
   128 socket aperti, zero byte inviati, `SO_TIMEOUT` tiene 5 minuti per socket, tutti i client legittimi prendono
   `ERROR:Server busy`. Outage totale, **non autenticato, da un solo IP**. `grep` per
   `getInetAddress|getRemoteAddr|RateLimit|perIp` in `src/` ⇒ **zero occorrenze**: non esiste alcun concetto
   di IP nel server. Fix: contatore per IP in `ConcurrentHashMap` con eviction entry-scoped, tre soglie
   (connessioni contemporanee per IP, connessioni totali per finestra, chiusura immediata alla terza).
   **Il semaforo globale resta**: è difesa in profondità, non va sostituito.

3. **Test di regressione per i punti 1 e 2** — priority: high · effort: S · owner: qa-tester · depends on: 1, 2
   `tests/TestProtocolHardening.java` (oggi non tracciato in git). Copre già
   `overLongLineDoesNotForfeitTheMatch`, `injectedProtocolFieldsAreRefused`, `authAttemptsAreRateLimited`.
   Da aggiungere: `deleteAccountRespectsAuthAttemptBudget` e `connectionLimitIsEnforcedPerIp`.
   I due fix sono in un solo file e sono regression-prone: senza test il prossimo refactor li cancella.

4. **Cap su stanze/thread per partita** — priority: medium · effort: M · owner: software-engineer · depends on: 2
   `ServerRoomHandler.createRoom()` e `ServerMain.java:232`. Misurato: baseline 31 thread / 0 connessioni,
   **200 stanze ⇒ 231 thread, 200 connessioni stabilite, `ROOMS_LIST:200`** — contro `MAX_CONNECTIONS = 128`.
   Il semaforo è rilasciato quando `handleInitialClient` ritorna (righe 71-77), ma la stanza vive su un
   `new Thread(ch, "room-" + code)` dedicato, fuori dal pool. N connessioni ⇒ ~N thread, 2N in partita,
   `roomsById` è un `ConcurrentHashMap` senza tetto. Non è un leak (la pulizia funziona, verificato:
   30 stanze create/disconnesse ⇒ `ROOMS_LIST:1`, poi 0) — è un'assenza di cap durante il tempo di attacco.
   *Nota di misura:* le prime stime del project specialist erano gonfiate da un artefatto del suo harness
   (socket tenuti aperti); il numero 231 è con chiusura corretta.

5. **Mettere l'IP nei log e sanitizzare i messaggi di errore** — priority: medium · effort: S · owner: security · depends on: —
   `ServerMain.java:145, 301`; `ServerAccountHandler.java:140` (già ben fatto, niente stack trace del driver).
   Oggi **nessuna riga di log in tutto il server contiene l'IP di origine**: un attacco distribuito o un
   abuse ripetuto non è né rilevabile né attribuibile. Il messaggio di `selfDestruct` ("Session error: " +
   `e.getMessage()`) può contenere testo influenzato dal client. Una riga ciascuno.

6. **TLS: o si abilita sul serio, o si dichiara non supportato** — priority: medium · effort: M · owner: devops · depends on: 5
   `ServerMain.java:55, 88-97`; `start-server.sh`; `nixpacks.toml`. Il codice TLS c'è ma è **morto**:
   `-Dchess.server.tls=true` non è passato da nessuno script e non esiste keystore ⇒ password in chiaro su
   porta pubblica (il banner a runtime lo avvisa, `WARNING: TLS disabled`). Il SOUL vieta di proporre fix
   che rompono la feature: due strade oneste, nessuna è "attivalo e basta".
   **(a)** Provisionare un keystore e passare il flag in `start-server.sh` — serve sapere se Railway già
   termina TLS a monte, altrimenti si aggiunge un layer invece di rimuovere uno.
   **(b)** Se Railway termina TLS: dichiarare il plaintext come interno-only e rimuovere il codice TLS morto.
   *Da confermare prima di scegliere:* se esiste reverse proxy/TLS a monte su Railway. Il project specialist
   non ha potuto verificarlo.

7. **Identità guest non verificabile — decisione di design, non hardening** — priority: low · effort: L · owner: manager · depends on: —
   `ServerMain.java:171-182, 235-262`. `GUEST:<nick>` accetta qualsiasi nickname (unico, non verificato) e
   `JOIN_ROOM:<code>` richiede solo il codice a 6 caratteri (~1,07 miliardi di spazi, no I/O/0/1). Un
   attaccante con due socket può giocare **entrambi i lati** di una partita. Non è un bug: è insito nel design
   "nessun account necessario", che è il motivo per cui il progetto è dimostrabile in 30 secondi e per cui è
   in evidenza sul portfolio. **Non lo fixerei**: servirebbero seat legati all'identità, e il ritorno
   (identità onesta) non giustifica il costo su un progetto showcase. Segnalato perché è il limite di sicurezza
   più in evidenza del progetto — ma è una decisione di prodotto, non un buco.

8. **Iginiene: porta configurabile, JDK pinnato, jar documentati** — priority: low · effort: S · owner: devops · depends on: —
   - `Server.java:7` — porta 6700 hard-coded, nessun override da env/CLI.
   - `nixpacks.toml:2` — `nixPkgs = ["jdk"]` non pinnato.
   - `lib/postgresql.jar` e `lib/flatlaf.jar` committati senza checksum né manifest di versione
     (sha256 `a2644cbf…` per postgresql). Vendorati ma non verificabili.
   - `.gitignore`: aggiungere `gui-error.log` (`*.log` copre il resto). `.env.local` non è tracciato ⇒
     nessun secret in git.
   Non un'emergenza: è la differenza tra "vendorato" e "vendorato e verificabile".

9. **Aggiornare SOUL.md del progetto e README** — priority: low · effort: S · owner: workers · depends on: 1, 2, 4, 6
   Il SOUL dice Node.js/npm/WebSocket: è il primo errore che ha colpito entrambi gli specialisti. Il README è
   pubblico e linkato dal portfolio, quindi se cambia il comportamento di auth o il limite di connessioni va
   aggiornato lì. Rientra anche il known-issue "TLS disabled".

## Il ragionamento

**Il rischio reale è uno solo e domina tutto: la disponibilità.** Il server non ha nulla da rubare — un
username e una password PBKDF2 — e la correttezza degli scacchi è già difesa bene. Tutto il resto è
secondario, e questa constatazione ha fatto risparmiare al piano una voce intera.

Il project specialist ha verificato empiricamente sul server reale che l'input validation **non** è il buco
che sembra: `MAX_MESSAGE_LENGTH = 512` con drain della coda della riga sovralunga, `isValidSquare()` prima
di toccare l'engine, `NAME_PATTERN`/`ROOM_NAME_PATTERN` che escludono `:`/`|`/`\n` (quindi niente injection in
`ROOMS_LIST`), `PreparedStatement` ovunque (niente SQL injection), solo `PreparedStatement`/`ServerSocket`
come sink (niente RCE, quindi niente miner). Ha anche falsificato un suo sospetto: pensava ci fosse un leak
di stanze, e il test l'ha smentito. Entrambe le cose servono al piano: il primo evita di aggiungere roba
inutile, il secondo evita un item fantasma.

La divergenza vera fra i due specialisti è su **punto 4 (stanze/thread)**. Il project specialist lo mette a
priorità media e lo descrive come "non è un leak, è un'assenza di cap". Lo security specialist lo esclude del
suo top 3 e punta invece a spostare l'attenzione sul rate limit per IP, che è lo stesso attacco visto dalla
parte opposta: il suo scenario (#1, 128 socket idle) e il misurato del project specialist (200 stanze ⇒ 231
thread) sono due forme della stessa leva — consumare risorse per connessione. **Ho preso la parte del project
specialist**, per tre motivi: la misura è sua ed è stata ripetuta e corretta (231 thread è con chiusura
corretta), il punto 4 è l'unico item della scala che richiede di cambiare struttura (`new Thread` fuori dal
pool) invece di aggiungere un contatore, e metterlo a media invece che a low lascia aperta la decisione sulla
policy (cap assoluto? per-IP? per-utente?) senza bloccare i due fix ad alta priorità. Il security specialist ha
ragione sul merito del merito — il rate limit per IP è la stessa leva — ma la sua priorità 3 è il punto che
non si chiude scrivendo codice, e questo piano lo mette a media con la verifica che manca prima.

Seconda divergenza: **il rate limit sulle mosse**. Lo security specialist l'ha analizzato e l'ha escluso
esplicitamente, con un ragionamento che ho accettato: `makeMove` è bounded dalla dimensione della scacchiera
(~O(64) nel caso peggiore con pezzi scorrevoli), e una mossa legale **cambia turno**, quindi un attaccante non
può concatenarne. Un token bucket sulle mosse aggiungerebbe complessità e falsi positivi su un giocatore veloce
per proteggere una risorsa che non è in pericolo. Non è nel piano, e la ragione è annotata perché è una
decisione, non una dimenticanza.

Il project specialist ha invece insistito su un item che il security specialist ha declassato: il cap delle
5 tentativi come **misura anti-DoS**, non solo anti-brute-force. Il commento nel codice (righe 139-142) lo
dichiara già, ma il cap non copre il percorso `DELETE_ACCOUNT`. Entrambi i findings 1 e 2 sono in `ServerMain.java`
e si risolvono insieme: è il motivo per cui il piano apre con due fix nello stesso file.

Cosa è stato **accettato come rischio**: password in chiaro su porta pubblica finché TLS resta spento (punto 6),
e identità guest non verificata con partite giocabili da due lati (punto 7). Il primo è accettato *condizionalmente*,
in attesa di sapere se Railway termina TLS a monte; il secondo è accettato per scelta, perché il design senza
account è la feature. Entrambi sono dichiarati, non nascosti.

## Il risultato

**Decisione: partire dai punti 1 e 2, che insieme sono ~60 righe in un solo file** (`ServerMain.java`), senza
dipendenze nuove, senza infrastruttura, senza modifiche al protocollo e con rischio zero di rompere una mossa
legittima. Chiude l'attacco non autenticato da un solo IP e l'oracolo di guessing illimitato.

**Prossima azione concreta:** `software-engineer` applica i punti 1 e 2, poi `qa-tester` aggiunge i due test di
regressione (punto 3). Una volta quei due fix a terra, `devops` verifica se Railway termina TLS a monte e
sceglie la strada (a) o (b) del punto 6 — è l'unica decisione che non si chiude scrivendo Java.

Se il tempo è poco, fare **solo 1 e 2**: il progetto è comunque infinitamente più robusto di quanto lo sia oggi.

**Esplicitamente fuori scope:**
- Anti-cheat. Non è una minaccia: le partite non valgono niente (nessun rating, nessuna posta, nessuna
  ricompensa). Costruirlo è lavoro sprecato.
- Rate limiting generico per comando con token bucket: architettura da sistema di pagamento su un server da
  4 core con due giocatori per stanza.
- Header di sicurezza HTTP, XSS/CSRF/cookie flags, path traversal, XXE, SSRF, anti-deserializzazione:
  nessuno di questi sink esiste in questo codebase. Sarebbe check-list cosmetica.
- `npm audit` e hardening di `node_modules`/`package.json`: non sono nel build.
- Irrigidire `isValidSquare` o la validazione delle mosse in `ChessGame.makeMove` — **è già corretta**,
  con rollback transazionale che non sporca diritti al castling né halfmove clock. Qualunque irrigidimento
  rischia di invalidare una mossa legale o rompere castling/en passant/promozione.
- Nessuna modifica alle regole di scacchi. I due fix di sicurezza non toccano `ChessGame`.
- Aggiungere sessioni/JWT: non ci sono token da proteggere, sarebbe architettura cosmetica travestita da
  sicurezza.
- Il SOUL.md del progetto va corretto (punto 9), ma è documentazione, non codice — non blocca nulla.