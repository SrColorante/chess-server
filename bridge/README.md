# Bridge scacchi — WebSocket davanti al server TCP

Il server di scacchi parla **TCP grezzo a righe di testo** sulla porta 6700 e non
ha TLS: manda `LOGIN:utente:password` in chiaro. Un browser non può aprire una
socket TCP, e aprire la porta 6700 su Internet significherebbe credenziali in
chiaro.

Questo bridge risolve entrambi i problemi senza toccare il protocollo: il
client Swing esistente continua a funzionare come prima.

```
browser ──wss──> cloudflared ──> bridge (127.0.0.1:8081) ──tcp──> chess-server (127.0.0.1:6700)
                        TLS          sfida firmata                chiaro, ma solo in locale
```

## Perché la cifratura è strutturale, non configurabile

Il bridge **ascolta solo su `127.0.0.1`**. Il tunnel cloudflared gira sulla stessa
macchina e ci arriva via localhost; nessun altro host della rete può aprire la
6700. Non esiste una configurazione da sbagliare: se il tunnel è su, il traffico
è cifrato, e l'unica alternativa (esporre la porta) non è implementata.

Questo è il punto: una difesa che dipende da `bind 127.0.0.1` è fragile, perché
un giorno qualcuno cambia l'indirizzo e non se ne accorge. Qui l'indirizzo è
controllato dal codice e il tunnel è l'unico modo di arrivarci.

## Perché HTTPS da solo non basta

Cloudflare cifra il trasporto browser → edge, ma quello dice **"questi due
endpoint sono loro"**, non **"questo è l'utente Cristian"**. Chiunque apra
`wss://chess.cristianrenosto.party` arriva al tuo server.

E su una WebSocket non si può usare un cookie di sessione: il browser non invia
header `Authorization` su una WS, quindi l'autenticazione deve stare nel primo
messaggio. Da qui la sfida firmata:

1. il bridge manda un `nonce` casuale di 32 byte
2. il browser firma `chess-challenge-v1|<utente>|<nonce>` con la chiave privata
   (WebCrypto, la chiave non lascia mai il dispositivo) e manda **la firma**
3. il bridge verifica con la chiave pubblica
4. il nonce si consuma: una firma intercettata non è riutilizzabile

**La password non attraversa mai il browser oltre TLS**, e non viene salvata.

## Avvio

```bash
cd bridge
npm install
node server.js
```

Variabili d'ambiente:

| Variabile | Default | Note |
|---|---|---|
| `BRIDGE_HOST` | `127.0.0.1` | non cambiarlo: vedi sopra |
| `BRIDGE_PORT` | `8081` | la porta nel tunnel |
| `CHESS_HOST` | `127.0.0.1` | server scacchi |
| `CHESS_PORT` | `6700` | server scacchi |

La UI è servita dallo stesso processo: `http://127.0.0.1:8081/` in locale,
`https://chess.cristianrenosto.party` dal tunnel.

## Test

```bash
node test-protocol.js   # la grammatica del protocollo della UI, senza rete
node test-bridge.js     # 21 verifiche end-to-end contro un finto server
```

`test-protocol.js` copre il parsing di `BOARD:`, `ROOMS_LIST:`, `ROOM_CREATED:`
e `MOVE_OK:` (`web/protocol.js`), con i valori attesi copiati da
`renderBoardCompact()` e dal costruttore di `ROOMS_LIST` in `ServerMain`.

`test-bridge.js` copre il percorso completo contro un finto server che parla il
**protocollo reale**: sfida firmata, replay del nonce, utente non autorizzato,
chiave pubblica non corrispondente, auth in chiaro, endpoint sbagliato, health,
e una partita intera (CREATE_ROOM → JOIN_ROOM → MATCH_START → BOARD → MOVE_OK).

La UI web si può provare senza browser:

```bash
node --import ./web/__ui_stub.mjs web/__ui_check.mjs
```

## Configurare il client

`web/app.js` ha la chiave pubblica e privata in chiaro, **solo per sviluppo
locale**. In produzione la chiave privata va nel keyring del browser o
arrivare da un endpoint di sessione — mai in un file servito dal server.

L'elenco degli utenti autorizzati è in `auth.js` (`ALLOWED_USERS`). Oggi c'è una
sola entry di proposito: **aggiungere un nome significa aggiungere una riga**, e
un utente che si registra da solo non deve poter giocare. Quando il vincolo
diventerà l'account centralizzato di `utenti`, si cambierà la sorgente, non la
forma del codice.

## Limiti noti

- **`verifySignature` non firma un digest ma il testo della sfida.** Così il
  client non deve ricostruire l'hashing ed è impossibile sbagliare la codifica.
  Entrambi i lati devono restare allineati a `buildChallenge`: se la stringa
  cambia da una parte sola, ogni firma viene respinta.
- **Nessuna verifica di appartenenza della stanza.** Il bridge autentica *chi*
  è, non *a che stanza* ha accesso. Chi è autenticato può entrare in qualunque
  stanza se ne conosce l'id. Va aggiunto quando le stanze diventano private.
- **Le partite non sono persistenti dal lato bridge.** `chess_games` esiste nello
  schema ma non è ancora collegata: le partite si perdono al riavvio.
- **La UI non gestisce le stanze private.** `CREATE_ROOM:nome:true` funziona (il
  server marca la stanza privata e la esclude da `ROOMS_LIST`), ma la UI non ha
  ancora un modo di passare il flag: chi crea una stanza privata la condivide
  solo a voce. La lista non le mostra per costruzione.
- **La firma nel browser è solo per sviluppo.** Con `publicKey` e `privateKey`
  vuote in `web/app.js` la sfida non può essere risolta: i due campi vanno
  riempiti in locale, o sostituiti da un endpoint di sessione in produzione.