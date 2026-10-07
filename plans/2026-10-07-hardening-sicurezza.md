# Hardening di sicurezza del chess-server

Due specialisti indipendenti hanno lavorato in parallelo: il project specialist di
`chess-server` (ha letto il codice e **ha eseguito probe** contro un server compilato
per verificare i rischi) e lo specialista security (threat modelling da disciplina,
senza entrare nel dettaglio implementativo). Questo file è la sintesi.

**Stack reale:** Java 17 puro, `src/server/*.java`, TCP raw su porta 6700, protocollo
testuale a righe, deploy Railway, Postgres Neon usata **solo** per `chess_users`.
Nessun HTTP, nessun browser, nessun WebSocket.

---

## La scala delle cose da fare

1. **Timeout di autenticazione da 5 minuti a 10-15 secondi** — priority: high · effort: S · owner: software-engineer · depends on: —
   `ServerMain.java:28` (`AUTH_TIMEOUT_MS = 300_000`) è applicato a `:118` **prima** di
   leggere qualunque comando. Il pool è `THREADS = Math.max(8, cores*2)` (`:33`) → 8 thread
   su macchina a 4 core, mentre il `Semaphore(128)` (`:46`) protegge 128 connessioni.
   Verificato sperimentalmente: 8 socket che leggono `WELCOME:` e restano muti rendono
   ogni client legittimo successivo irraggiungibile (read timeout). Un login reale sta
   in secondi. Da solo questo abbatte il DoS più grave del progetto.

2. **Semaforo separato per gli slot pre-autenticazione** — priority: high · effort: S · owner: software-engineer · depends on: —
   Un `Semaphore` piccolo (8-16) per le connessioni *prima* dell'auth, rilasciato a `:156`/`:166`/`:182`
   quando `authenticated = true`. Il semaforo da 128 deve proteggere le connessioni in
   partita, non la porta d'ingresso. Circa 15 righe. (Stesso item di 1, deliverato insieme.)

3. **`DELETE_ACCOUNT` fuori dal loop pre-autenticazione** — priority: high · effort: S · owner: software-engineer · depends on: —
   Il gate `MAX_AUTH_ATTEMPTS` (`:143`) copre solo `line.startsWith("LOGIN:") || "REGISTER:"`.
   `DELETE_ACCOUNT:` è gestito nello stesso loop (`:183`) ma **senza contatore**. Verificato:
   30 tentativi consecutivi sulla stessa socket → 30 risposte, zero disconnessioni (mentre
   `LOGIN` sullo stesso server chiude al 6°/7° tentativo). Doppio impatto: oracle di
   password pre-autenticazione (risposte distinte e verbatim) e amplificatore CPU, perché
   `deleteAccount` chiama `login()` → PBKDF2 120k iterazioni, **3.225 ms di CPU misurati
   per tentativo**. 5 tentativi × 128 socket ≈ 1.700 secondi di CPU per un solo attaccante.
   Fix: gate da allowlist a **denylist esplicita dei verbi che richiedono credenziali**, e
   spostare `DELETE_ACCOUNT` in un ramo post-auth con token di sessione. Rimuovere anche
   l'eccezione `null` in `deleteAccount` (`ServerAccountHandler.java:89-90` non controlla i
   null, a differenza di `login`).

4. **Rate limit delle mosse per giocatore** — priority: high · effort: S · owner: software-engineer · depends on: —
   `makeMove` è `synchronized` (`ChessGame.java:465`) e `handlePlayerMove`
   (`ConnectionHandler.java:420-438`) non ha alcun rate limit. Un giocatore autenticato
   che spamma `MOVE:` contende il monitor dell'istanza **condivisa con l'avversario
   legittimo**, il quale non riesce a giocare e finisce per `resign` (`:522-531`).
   Non è denial of servizio generico: è **integrità competitiva**, la partita distrutta
   di proposito. Validare la forma della mossa *prima* di entrare nel lock sincronizzato.

5. **Broadcast con timeout: via al thread bloccato su write** — priority: high · effort: M · owner: software-engineer · depends on: —
   `broadcast` (`ConnectionHandler.java:463-466`) scrive in sequenza su `hostOut` e
   `guestOut` con `PrintWriter` autoflush. Se un peer non legge, il buffer si riempie,
   `println` blocca — e blocca il thread del *peer buono*, non quello del colpevole.
   Il keepalive soccorre dopo 25s (`PING_GRACE_MS`), ma i thread bloccati su write **non
   vengono mai liberati**: ogni stanza sequestrata consuma thread per sempre, e con 128
   connessioni il pool si esaurisce e si blocca tutto il server. Servono write con
   timeout e task di scrittura separati dal loop di lettura.

6. **Separare `GUEST` da `REGISTER` (GUEST non scrive mai sul DB)** — priority: medium · effort: S · owner: software-engineer · depends on: —
   `GUEST:<nick>` (`:171-182`) autentica al primo colpo, a costo zero: nessuna verifica,
   nessun throttle. Ma è anche una **feature da non uccidere** — è ciò che rende il
   progetto cliccabile dal portfolio. La fix non è richiedere l'account, è vietare a GUEST
   la scrittura su DB. `REGISTER` passa dal semaforo pre-auth piccolo a una quota.
   `GUEST` resta libero e transitorio.

7. **Identità: prefisso guest e unicità** — priority: medium · effort: S · owner: software-engineer · depends on: —
   Il nickname è auto-dichiarato e non prenotato. Con `VARCHAR PRIMARY KEY`
   case-sensitive (`ServerAccountHandler.java:39`), `"Cristian"` e `"cristian"` sono
   account distinti, e `GUEST:admin` si presenta come `admin` in `ROOMS_LIST` (`:213`),
   in broadcast a ogni visitatore che apre la lista. Marchiare i nomi da GUEST
   (es. prefisso `guest-` server-side) e normalizzare la case.

8. **Contatore tentativi su `JOIN_ROOM` e reply uniforme** — priority: medium · effort: S · owner: software-engineer · depends on: —
   `JOIN_ROOM` (`:235-246`) è nel loop lobby, fuori da qualsiasi contatore. Verificato:
   2000 guess ben formati su una singola socket, nessuna disconnessione. Lo spazio dei
   codici è buono (32 caratteri × 6 = ~1,07 miliardi, `SecureRandom` senza bias) e
   l'entropia **non** è il problema — l'assenza di throttle sì. Uniformare le risposte
   "malformato" (`:238`) / "non trovato" (`:244`), che oggi sono due segnali distinguibili.
   Nota: per le stanze private il codice è **l'unico segreto**, quindi il throttle qui
   protegge dati, non solo disponibilità.

9. **DB fail-closed e rimozione delle password di fallback** — priority: medium · effort: S · owner: devops · depends on: —
   `initDatabase()` cattura l'eccezione e **solo stampa** (`ServerAccountHandler.java:46-48`):
   il server parte lo stesso e ogni login/register fallisce poi con errori generici.
   Fall-open silenzioso: deployment misconfigurato = tutti gli account non funzionano,
   tutti diventano GUEST, e nulla fallisce in modo evidente. In più `DB_PASS=postgres`
   è hardcoded come fallback (`start-server.sh:12`). Se `DB_URL` manca fuori dal locale,
   **fallire all'avvio**.

10. **Decisione di trasporto: TLS o plaintext dichiarato** — priority: high · effort: M · owner: devops · depends on: 1
    **Non è un refactor, è una scelta.** `createServerSocket` (`:88-98`) con
    `tlsEnabled=true` seleziona `SSLServerSocketFactory.getDefault()` ma **non carica
    alcun keystore**. Verificato eseguendo quel path: client in chiaro → handshake fallisce
    (corretto), ma **client TLS reale → `SSLHandshakeException: handshake_failure — No
    available authentication scheme`**. Il flag `-Dchess.server.tls=true` non è
    semplicemente "da configurare": è codice che non funziona. Il default è plaintext puro
    su porta pubblica.
    Due strade: (a) caricare un keystore da env con `SSLContext.getInstance("TLSv1.3")`
    esplicito e togliere `getDefault()`; oppure (b) **cancellare il flag** e dichiarare il
    plaintext come limite architetturale, delegando TLS a un terminator/proxy. In entrambi
    i casi il README va aggiornato: oggi dice la verità (`README.md:227-229`) e va tenuto
    così. Il bind è wildcard (`0.0.0.0/0.0.0.0` misurato).

11. **Igiene del repository** — priority: medium · effort: S · owner: devops · depends on: —
    `git ls-tree -r HEAD` → **4997 voci sotto `node_modules/`**, cioè il 98,6% del repo è
    scaffolding compilato. Il `.gitignore` aggiunge `node_modules/` ma la modifica è
    **non committata**, quindi su `origin/master` la regola non esiste. Servono
    `git rm -r --cached node_modules` + `.agents/` + `skills-lock.json`, poi il commit
    del `.gitignore` già pronto. **Nessun segreto esposto**: `.env.local` non è mai stato
    tracciato, la scansione di pattern credenziali su HEAD è pulita. Danno di superficie e
    igiene, non di esposizione. Allineare anche `README.md:232-233`, che elenca ancora
    `server.log`/`server.pid` come noto problema.

12. **Build riproducibile: checksum dei JAR, JDK pinnato** — priority: medium · effort: M · owner: devops · depends on: —
    `lib/postgresql.jar` e `lib/flatlaf.jar` sono binari tracciati in git, caricati
    direttamente nel classpath da `build.sh` e `start-server.sh`. Nessun lockfile,
    nessuna verifica di checksum, `jdk` non pinnato in `nixpacks.toml`. Chiunque possa
    pushare sul repo esegue codice in produzione, e non è rilevato da scansione dipendenze
    perché non è un dipendente npm. Mitigazione economica: congelare gli SHA-256 in un
    file verificato in CI. Migrare a Maven/Gradle è il fix vero ma è effort L e va deciso a
    parte.

13. **Audit trail: IP + azione + esito, mai password** — priority: low · effort: M · owner: software-engineer · depends on: —
    Nei log finiscono codice stanza e username (`ConnectionHandler.java:246,594`),
    **mai IP remoto, timestamp, account o esito di un comando**. Se il trasporto in chiaro
    viene sfruttato, non è ricostruibile chi si è autenticato né chi ha iniettato una mossa:
    impossibile dimostrare un imbroglio. È un limite di integrità, non solo di audit.
    (Verificato positivo: oggi le password **non** vengono loggate.)

14. **Piccoli: `MAX_MESSAGE_LENGTH` in byte, `LIST_ROOMS` bounded** — priority: low · effort: S · owner: software-engineer · depends on: —
    Il limite è misurato in caratteri, non byte (`sb.append((char) ch)`), quindi un input
    UTF-8 può essere ~3× il limite nominale (`ServerMain.java:319-320`) — impatto teorico
    con input validi ASCII, ma è una discrepanza fra nome del limite e realtà.
    `LIST_ROOMS` (`:208-215`) costruisce tutta la lista in un unico `println` senza
    limite di lunghezza in uscita (il `MAX_MESSAGE_LENGTH=512` vale solo in ingresso).

---

## Il ragionamento

### Cosa è stato pesato

Entrambi gli specialisti hanno lavorato sulla stessa base di codice ma con lenti
diverse, e il risultato più utile è la sovrapposizione: **i due critical che hanno
trovato sono lo stesso buco visto da due lati.** Il project specialist lo ha misurato
eseguendolo (8 socket muti → il server non risponde più), lo specialista security lo
aveva dedotto dal dimensionamento del pool. Una claim verificata sperimentalmente e una
claim inferita sullo stesso vettore sono la stessa cosa due volte: la probabilità che il
buco sia reale è alta, e la severità è massima perché il costo per l'attaccante è 8
connessioni e zero credenziali.

La parte di `DELETE_ACCOUNT` è l'esempio migliore del valore dell'indipendenza. Lo
specialista security l'ha trovata come T2 e l'ha descritta come bypass del contatore.
Il project specialist l'ha trovata, l'ha **misurata** (30 risposte, zero disconnessioni)
e ha aggiunto due cose che al security mancavano: l'oracle di password (le risposte sono
distinte e verbatim) e la misura del costo (3.225 ms di CPU per tentativo, che trasformano
il buco in amplificatore). La stessa finding, due gradi di profondità.

### Dove i due non erano d'accordo

**1. L'ordine: TLS prima o DoS prima?**
Lo specialista security mette TLS a P0 #1 — "prima di tutto il resto". Il project
specialist non lo mette al primo posto e per un motivo concreto: la modalità sicura è
**rotta**, non mancante, quindi non basta accenderla. Va prima decisa (keystore qui o
proxy a monte) e poi implementata. **Scelta: DoS e `DELETE_ACCOUNT` prima, TLS come
decisione di prodotto in parallelo.** Il ragionamento è che i primi due sono 30 righe,
sono verificati, e chiudono vettori che sono già aperti *oggi* in chiaro; TLS è
l'unico item che ha una vera alternativa da scegliere e che non può partire senza una
decisione. Metterlo per primo significa bloccare tutto su una domanda che va comunque
risolta, ma che non è l'unico collo di bottiglia.

**2. Rate limit per IP: sì o no?**
Divergenza netta. Lo specialista security propone bucket per IP + backoff per account
come mitigazione di T3 (l'amplificazione PBKDF2). Il project specialist mette
esplicitamente tra i trade-off **"non introdurre rate limiting globale per IP, code di
richieste o WAF"**, con tre motivi: il server è stateless e ospita ~2 connessioni per
partita, un limiter globale aggiunge stato e una finestra temporale e quindi una sua
classe di bug (race, reset, bypass), e soprattutto — dietro Railway non esiste
`X-Forwarded-For`, quindi l'IP reale non è nemmeno ottenibile e un limiter implementato
qui sarebbe **sbagliato**.
**Scelta: la posizione del project specialist.** Non aggiungiamo un limiter per IP a
questo server. Prezzi espliciti e bassi invece di meccanismi generici: il contatore per
socket (item 3) e il timeout corto (item 1) coprono lo stesso terreno da una direzione
che non può essere bypassata. Il backoff per *account* è l'unico pezzo che vale la pena
recuperare dalla posizione security, e lo tengo dentro l'item 3.

**3. Brute-force sui codici stanza: rumoroso o medio?**
Lo specialista security lo dichiara rumore da non inseguire ("1,07 miliardi, impraticabile
online"). Il project specialist lo misura e trova 2000 guess su una sola socket senza
disconnessione, e aggiunge il dettaglio che sposta la questione: per le stanze private il
codice è **l'unico segreto**, quindi il brute-force protegge dati, non solo disponibilità.
**Scelta: medio-basso (item 8), non alto.** Entrambi hanno ragione nel proprio campo —
l'entropia è irrilevante e non va toccata, il throttle manca e va aggiunto. Sono due
domande diverse che il project specialist ha separato meglio.

### Trade-off accettati (cosa NON va fatto)

- **Niente JWT / sessioni firmate / OAuth.** `ServerAccountHandler.java` è già solido e
  sobrio: PBKDF2-HMAC-SHA256 a 120k, salt 16 byte per utente, confronto a tempo costante,
  `Password` azzerata nel `finally`, hash "fittizio" sull'utente inesistente per uniformare
  il tempo, e un log che evita deliberatamente di stampare lo stack trace del driver.
  Sostituirlo significa portare dipendenze e gestione chiavi per un server che non ha
  bisogno di sessioni. **Il buco non è la crypto, è il throttle.**
- **Niente account obbligatorio per sedersi a un tavolo.** `GUEST` è una feature, non una
  dimenticanza. Item 6 la preserva e le toglie solo la scrittura su DB.
- **Niente persistenza partite / DB delle mosse / Redis "come hardening".** È la feature
  più richiesta (crash = partita persa, ammesso in `README.md:230-231`) ma è
  **disponibilità**, non sicurezza. Mescolarla all'hardening diluisce il piano e ne manda
  a fallback la priorità.

### Falsi positivi — da non inseguire

Nessuna SQL injection (tutto `PreparedStatement`, l'unica SQL concatenata è una
`CREATE TABLE IF NOT EXISTS` statica). Nessun CSRF/CORS/cookie/XSS/CSP/HSTS: non c'è HTTP
né browser, niente cookie implica niente credenziali ambientali. Nessun session hijacking
via token: l'identità è legata al socket, non esiste nulla da rubare a riposo — il rischio
equivalente è l'intercettazione del trasporto, cioè l'item 10. Nessun hijack di WebSocket:
non ci sono WebSocket. Nessuna migrazione ad Argon2id ad alta prioritità. La perdita di
Postgres Neon è limitata a username + salt + hash: nessuna PII, nessun segreto nel DB, non
è uno scenario di data breach. E `package.json`/`neon`/`node_modules` non sono superficie
d'attacco del server runtime, che è Java.

### Nota di perimetro

Non verificati da nessuno dei due: i `GRANT` effettivi sul Postgres (l'ipotesi è che oggi
il runtime abbia permessi DDL perché `initDatabase` esegue `CREATE TABLE` — va
confermato, non assunto), la concorrenza di `attachGuest`, e le rotte di input del
protocollo parser lato client. Se un hardening di permessi DB entra nel piano, va
chiesto al db-schema specialist.

---

## Il risultato

**Decisione:** si esegue un hardening in **tre onde**, non in un colpo solo. La prima
onda è tutta roba di codice ad altissimo rapporto segnale/sforzo — cinque item, sotto
`ServerMain.java` e `ConnectionHandler.java`, per una giornata buona di lavoro — e chiude
il 100% dei vettori sfruttabili *oggi* senza toccare il deploy.

**Prossima azione concreta:** **item 1 + 2 + 3 insieme**, in un unico commit, con test
di regressione. Sono la stessa modifica — una costante, un semaforo, un gate — e
`tests/TestProtocolHardening.java` contiene già la forma dei test per over-long line e
rate-limit auth, quindi il harness esiste. Da aggiungere: un caso che verifica che **8
socket muti non impediscono a un client legittimo di entrare entro 2 secondi**, e uno che
verifica che `DELETE_ACCOUNT` rispetti il limite di tentativi. Item 4 e 5 (rate limit
mosse, broadcast con timeout) sono il secondo blocco, stessa area del codice.

**Decisione da prendere in parallelo, non è un task:** item 10. Keystore in-process o
terminazione TLS a monte? Il project specialist raccomanda (b) — togliere il flag rotto e
delegare a un proxy stream — perché è la scelta con meno codice da mantenere in un progetto
che dimostra di non volere infrastruttura; il security specialista raccomanda (a) per
completezza. La scelta è di Cristian e va scritta nel README, che oggi dice correttamente la
verità e va tenuto così.

**Esplicitamente fuori perimetro:** i tre trade-off accettati qui sopra (framework di
auth, account obbligatorio, limiter per IP/WAF), la persistenza delle partite, la
migrazione a Maven/Gradle, e ogni finding classificato come falso positivo.