# Distilla

App Android che trasforma una notizia web in **testo semplice** (stile `lynx`), tenendo solo il corpo dell'articolo, da leggere, copiare o condividere.

Si usa dal menu **Condividi** di qualsiasi browser o app di notizie: scegli *Leggi con Distilla* (*Read with Distilla* in inglese) e l'app scarica la pagina, individua il testo e lo mostra pulito. Con **Copia** o **Condividi** ottieni titolo + testo + link, pronto per WhatsApp, Telegram o mail. Con **Ascolta** la notizia viene letta dalla sintesi vocale di sistema.

## Cronologia

Sotto il campo dell'URL c'è l'elenco delle notizie aperte, dalla più recente: titolo, sito e quando. Un tocco la riapre; il cestino sulla riga la toglie, quello in cima all'elenco cancella tutto (con conferma). Riaprire una notizia la riporta in cima senza duplicarla. Vengono tenute le ultime 200.

## User agent

Alcuni siti mandano ai telefoni una pagina ridotta, o con il testo caricato via JavaScript, mentre ai PC mandano la pagina completa. Per questo l'user agent (l'identità del "browser" con cui Distilla scarica la pagina) si può cambiare:

- **Impostazioni → Richieste → User agent**: vale per tutti i siti. Predefinito: Chrome su Android. I pulsanti *Telefono Android* e *PC desktop* lo impostano con un tocco; vuoto = predefinito.
- **Nella regola del sito**, campo *User agent per questo sito*: vale solo per quel sito e ha la precedenza. Una regola può contenere anche **solo** l'user agent: il testo viene allora cercato con il rilevamento automatico, ma la pagina viene chiesta con quell'identità.
- Nel `sites.json` la chiave è `"userAgent"`. Cambiare solo l'user agent conta come una configurazione diversa, quindi può generare un conflitto.

La sincronizzazione del `sites.json` da Git usa sempre il proprio user agent (`Distilla/1.0`), indipendente da queste impostazioni.

## Link abbreviati

Le app spesso condividono un link abbreviato (`share.google/…`, `search.app/…`, `bit.ly/…`, `t.co/…`) invece dell'indirizzo del giornale. Distilla lo **risolve prima di tutto il resto**, quindi regole, cronologia, user agent e motore browser lavorano sull'URL vero del sito.

- **Link "contenitore"** con la destinazione già scritta dentro (`google.com/url?q=…`, `search.app?link=…`, `l.facebook.com/l.php?u=…`, `l.instagram.com/?u=…`): decodificati subito, senza richieste di rete.
- **Shortener** (Google, bit.ly, TinyURL, t.co, Facebook, Instagram, Threads, Reddit, Buffer, IFTTT, Flipboard, Apple News e altri: elenco in `UrlResolver.SHORTENERS`): seguiti un passo alla volta, leggendo il redirect HTTP o, quando rispondono con una pagina, il suo meta refresh / script. Al massimo 10 passi, con protezione dai cicli.
- Durante la risoluzione compare "Link abbreviato: ricerca dell'indirizzo reale…". In cronologia resta l'indirizzo finale; anche i normali redirect del sito aggiornano la voce.
- **Prova** nell'editor della regola accetta anche un link abbreviato e mostra a quale indirizzo porta.
- Se la risoluzione non riesce si prosegue con l'ultimo indirizzo raggiunto.

## Motore browser (siti che bloccano le app)

Alcuni siti protetti da servizi anti-bot (Cloudflare, Akamai, DataDome…) rispondono **403** a qualsiasi app, anche con l'user agent di Chrome: riconoscono il programma dall'impronta della connessione HTTPS, non dal nome che dichiara. In quel caso Distilla carica la pagina con il **motore Chrome del telefono** (Android System WebView), che per il sito è indistinguibile da un browser vero; in più esegue JavaScript, quindi vede anche il testo aggiunto dopo il caricamento.

- **Automatico**: con *Impostazioni → Richieste → Usa il motore browser se un sito blocca l'app* (attivo di default), un 403, 429 o 503 fa ripetere il caricamento con il motore browser. Durante il tentativo compare "Il sito blocca le app: caricamento con il motore browser…", e sotto il titolo dell'articolo è indicato *motore browser*.
- **Per sito**: nella regola, *Carica sempre con il motore browser* salta il download normale (più veloce per i siti che bloccano sempre). Nel `sites.json`: `"browser": true`. Una regola può contenere anche solo questa opzione.
- Le pagine di verifica ("Just a moment…", "Checking your browser", "Verifica che tu sia un essere umano") vengono riconosciute, anche quando il sito le invia con risposta 200 invece di 403: il motore aspetta che la verifica passi e prende la pagina vera.
- **Verifica a schermo**: se dopo 15 secondi la verifica è ancora lì (molte passano solo in una pagina visibile, alcune chiedono un tocco su "Sono una persona"), il lettore mostra la pagina del sito con la scritta *Verifica anti-bot*. Quando la verifica passa, la notizia si apre da sola. Il cookie che il sito rilascia resta salvato nel motore browser, quindi per un po' le notizie successive dello stesso sito, anche nella coda di ascolto, si caricano senza chiedere nulla.
- Nella **coda di ascolto** e in Android Auto la verifica non si può mostrare: la notizia viene saltata con l'avviso *«titolo»: il sito chiede una verifica anti-bot*, e **Apri** la apre nel lettore per completarla.
- Nel motore browser vale l'user agent della regola del sito, se impostato; altrimenti quello vero della WebView (senza il marcatore "wv"), coerente con la sua impronta.
- Gli errori HTTP ora indicano anche il server, per esempio *"errore HTTP 403 (server: cloudflare)"*.

## Coda di ascolto

Le notizie si possono ascoltare una dopo l'altra, come una playlist.

- **Dalla cronologia**: il pulsante ➕ accanto al cestino di ogni notizia la aggiunge in fondo alla coda (diventa ✓ se è già in coda). **Ascolta tutte**, in cima all'elenco, mette in coda tutte le notizie non ancora in coda, dalla più recente, e avvia la lettura.
- **Dalla lettura**: *Ascolta* legge subito la notizia aperta, inserendola subito dopo quella in corso.
- **Mini-lettore**: in fondo alla schermata iniziale e a quella di lettura, mostra titolo, paragrafo e lingua, con play/pausa, notizia successiva e velocità (1×, 1,5×, 2×). Toccandolo si apre la **Coda di ascolto**: notizia in corso evidenziata, spostare su/giù, togliere, saltare a una notizia, svuotare la coda.
- Ogni notizia viene scaricata poco prima di essere letta (con le regole dei siti, i link abbreviati risolti e il motore browser se serve); la successiva viene preparata in anticipo. Una notizia che non si riesce a leggere viene saltata con un avviso.
- **In background**: la lettura continua a schermo spento e con altre app aperte, con i controlli nella tendina delle notifiche e sulla schermata di blocco, e risponde ai tasti delle cuffie Bluetooth e del volante.
- **Android Auto**: Distilla compare tra le app audio dell'auto, con due cartelle, *Coda di ascolto* e *Lette di recente*: toccare una notizia la legge (se non era in coda viene aggiunta in fondo).
- La coda e il punto raggiunto vengono salvati: dopo un riavvio si riprende da lì.

Tecnicamente è un `MediaLibraryService` di Media3 (`playback/PlaybackService.kt`) con un player che legge il testo con la sintesi vocale (`playback/TtsPlayer.kt`). Per Google Play va compilata la dichiarazione del servizio in primo piano di tipo *Riproduzione multimediale* (Play Console → Contenuti dell'app → Servizi in primo piano).

## Lingua

L'interfaccia è in **inglese** di default e in **italiano** quando la lingua di sistema è l'italiano (`res/values` e `res/values-it`). Da Android 13 si può anche scegliere la lingua solo per Distilla in *Impostazioni → App → Distilla → Lingua*.
Per aggiungere una lingua basta creare `res/values-<codice>/strings.xml` e aggiungerla a `res/xml/locales_config.xml`.

## Lettura vocale

**Ascolta** usa il motore `TextToSpeech` di Android, quindi la voce, la velocità e il motore (Google, Samsung, ecc.) sono quelli scelti nelle impostazioni di sistema.

- La voce segue la **lingua in cui è scritto il testo**, non quella dell'app né quella del telefono: un articolo tedesco viene letto in tedesco anche su un telefono in italiano. La lingua è riconosciuta offline dalle parole più frequenti (italiano, inglese, tedesco, francese, spagnolo, portoghese, olandese, svedese, danese, norvegese, finlandese, polacco, ceco, rumeno, ungherese, turco, croato, catalano) e dall'alfabeto (greco, russo, ucraino, bulgaro, serbo, arabo, persiano, ebraico, cinese, giapponese, coreano, thai, hindi, georgiano, armeno).
- Ordine di scelta: lingua rilevata nel testo → lingua dichiarata dalla pagina (`<html lang>`, che porta anche la variante, es. `pt-BR`) → lingua del telefono. Viene usata la prima per cui il telefono ha una voce installata.
- Se manca la voce per la lingua del testo, l'app lo dice ("Nessuna voce installata per la lingua tedesco: leggo in italiano") e offre **Installa voci**, che apre il download delle voci del motore di sintesi.
- La lingua usata è indicata nella barra di lettura.
- **Velocità**: il pulsante `1×` nella barra di lettura passa a 1,5× e 2×. Il cambio vale subito (il paragrafo in corso riparte alla nuova velocità) e viene ricordato per le letture successive.
- **In auto / Android Auto**: la voce chiede ad Android il *focus audio* e si dichiara come parlato, altrimenti Android Auto la silenzia. In *Impostazioni → Lettura vocale* l'opzione **Leggi come un navigatore** la fa passare sul canale della navigazione: si sente sopra la radio o la musica (che vengono abbassate), anche se l'autoradio non è sulla sorgente del telefono.
- Una chiamata, l'assistente vocale o un'indicazione del navigatore mettono in pausa la lettura, che riprende da sola quando finiscono. Se un'altra app avvia la musica, la lettura resta in pausa.
- Il testo viene letto un paragrafo alla volta: la barra mostra l'avanzamento, **Pausa** ferma la lettura e **Riprendi** riparte dall'inizio del paragrafo interrotto.
- La lettura continua anche se esci dall'articolo o apri l'app su un'altra schermata: si ferma dal mini-lettore, dalla notifica o dalla coda.

## Compilare

1. Apri la cartella in **Android Studio** (Ladybug 2024.2 o successivo) con *File → Open*.
2. Attendi la sincronizzazione di Gradle (scarica Gradle 8.11, AGP 8.9, Kotlin 2.0).
3. Collega il telefono con il debug USB attivo e premi **Run**.

Per un APK da installare a mano: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. Il file è in `app/build/outputs/apk/`.
La build `release` è firmata con la chiave indicata in `keystore.properties` (vedi `keystore.properties.example`); senza quel file usa la chiave di debug, che va bene solo per uso personale.

Requisiti: Android 8.0 (API 26) o superiore.

## Come trova il testo

In ordine:

1. **Regola del sito** – se l'URL corrisponde a un pattern configurato, prende gli elementi indicati dall'XPath.
2. **Rilevamento automatico** – assegna un punteggio ai blocchi della pagina (lunghezza dei paragrafi, virgole, densità di link, classi come `article`, `testo`, `content` oppure `share`, `related`, `cookie`) e tiene il migliore, in modo simile a Readability di Firefox. Menu, banner cookie, box "leggi anche", condivisioni social, didascalie e footer vengono scartati.
3. **Dati strutturati** – se il testo trovato è scarso, usa il campo `articleBody` del JSON-LD (utile per i siti costruiti in JavaScript).
4. **Pagina intera** ripulita, come ultima risorsa, con un avviso.

Sotto il titolo l'app indica quale metodo ha usato.

## Regole per sito

In **Impostazioni** vedi tutti i siti configurati (pattern URL, XPath, origine *Locale* o *Git*) e puoi aggiungerli, modificarli o eliminarli.

L'editor della regola è a tutto schermo: **Salva** è nella barra in alto, sempre visibile anche con la tastiera aperta, e ripetuto in fondo al modulo.

Il modo più rapido per crearne una: apri una notizia, tocca **Crea regola**. L'app propone il pattern del dominio e l'XPath del blocco che ha individuato da sola; con **Prova XPath** vedi subito quanti elementi trova e un'anteprima del testo.

### Pattern URL

| Pattern | Confrontato con | Esempio che corrisponde |
|---|---|---|
| `*.ansa.it` | solo l'host (vale anche per `ansa.it`) | `https://www.ansa.it/...` |
| `www.ilpost.it/20*` | URL senza `https://` | `https://www.ilpost.it/2026/10/27/...` |
| `https://*.esempio.it/news/*` | URL completo | `https://m.esempio.it/news/123` |

`*` = qualsiasi sequenza di caratteri, `?` = un carattere; maiuscole e minuscole sono ignorate. Se più regole corrispondono vince la più specifica.

### Come la regola sceglie il testo

Ogni regola ha tre campi, tutti facoltativi (ne serve almeno uno):

| Campo | Cosa fa | Esempio |
|---|---|---|
| **XPath** | L'area della pagina da considerare. Vuoto = tutta la pagina | `//article` |
| **Includi** (CSS) | Dentro l'area tiene **solo** questi elementi | `.wp-block-paragraph, h2` |
| **Escludi** (CSS) | Toglie questi elementi | `.ad, .share-box, figure` |

Nei selettori CSS si possono mettere più direttive separate da virgola, come nei fogli di stile: `.classe`, `#id`, `tag`, `div > p`, `article p`, `[class^=wp-]`, `[data-type=testo]`. In più Distilla accetta il **jolly nelle classi**: `.wp-block-*` prende tutti gli elementi con una classe che inizia per `wp-block-`.

Esempio per [Red Hot Cyber](https://www.redhotcyber.com/): pattern `*.redhotcyber.com`, Includi `.wp-block-paragraph`. Se nella pagina ci sono paragrafi con quella classe anche fuori dall'articolo (barra laterale, box), aggiungi un XPath come `//article` o un Escludi come `.sidebar`.

**Priorità** (compare quando Includi ed Escludi sono entrambi compilati):

- *spento – prima includi*: vengono presi gli elementi inclusi, poi si tolgono gli esclusi **al loro interno**. Un paragrafo incluso resta anche se si trova dentro un'area esclusa.
- *acceso – prima escludi*: si tolgono prima le aree escluse **con tutto quello che contengono**, poi si prendono gli inclusi rimasti.

Esempio: Includi `.wp-block-paragraph`, Escludi `.sharedaddy`, con un paragrafo "Condividi su Facebook" dentro un `div.sharedaddy`. Con *prima includi* il paragrafo viene letto; con *prima escludi* no.

Se l'XPath trova più elementi (es. `//article//p`) vengono concatenati in ordine; quelli contenuti in un altro risultato non vengono ripetuti. Meglio puntare a classi o id stabili, per esempio `//div[contains(concat(' ', normalize-space(@class), ' '), ' article-body ')]`, piuttosto che percorsi posizionali come `/html/body/div[3]/div[2]` copiati dagli strumenti del browser.

Con **Prova** nell'editor della regola vedi subito il testo che ne esce su una pagina vera.

## Configurazione condivisa su Git

1. Crea un repository (GitHub, GitLab, Gitea...) con un file `sites.json` nel formato di [`sites.json`](sites.json):

   ```json
   {
     "version": 1,
     "sites": [
       { "name": "Nome sito", "pattern": "*.dominio.it", "xpath": "//div[@class='testo']" },
       { "name": "Altro sito", "pattern": "*.altro.it", "include": ".wp-block-paragraph, h2",
         "exclude": ".ad", "order": "exclude-first" }
     ]
   }
   ```

2. In **Impostazioni → Configurazione condivisa** inserisci l'URL *raw* del file, per esempio
   `https://raw.githubusercontent.com/UTENTE/distilla-config/main/sites.json`.
   Per un repository privato aggiungi un token di sola lettura: viene inviato come `Authorization: Bearer <token>`, che funziona con GitHub, GitLab e Gitea.
3. Tocca **Sincronizza ora**. Con *Aggiornamento online* attivo, l'app cerca una nuova configurazione all'avvio, al massimo una volta al giorno.

Regole di unione:

- le regole scaricate da Git vengono aggiornate o rimosse a ogni sincronizzazione, in modo da rispecchiare il repository;
- se `sites.json` contiene un URL già presente tra le regole **locali** con la **stessa configurazione** (XPath, Includi, Escludi, priorità), non succede nulla;
- se l'URL è lo stesso ma la **configurazione è diversa**, l'app chiede cosa fare, un conflitto alla volta (il dialog mostra le due configurazioni):
  - **Mantieni locale** – la regola locale resta. Lo stesso conflitto non viene più chiesto, finché su Git quella regola non cambia di nuovo;
  - **Sovrascrivi** – vince la versione di Git, e da quel momento la regola segue il repository;
  - **Mantieni locale per tutti** / **Sovrascrivi tutti** – la stessa scelta per tutti i conflitti rimasti;
  - **Disattiva aggiornamento online** – lascia tutto com'è e spegne la ricerca automatica di nuove configurazioni (riattivabile dalle Impostazioni);
  - **Decidi dopo** – rimanda; in Impostazioni compare il pulsante *Risolvi N conflitti*;
- se la sincronizzazione automatica parte mentre stai leggendo una notizia, la domanda aspetta che tu esca dalla lettura;
- modificare una regola scaricata la trasforma in locale.

L'interruttore **Aggiornamento online** in Impostazioni attiva o disattiva la ricerca automatica all'avvio; **Sincronizza ora** funziona comunque.

### Backup: esportare e importare sites.json

In Impostazioni → *Siti configurati*:

- **Esporta sites.json** apre il "Salva con nome" di Android: scegli cartella e nome (proposto `distilla-sites-AAAA-MM-GG.json`) e vengono salvate tutte le regole, nello stesso formato usato da Git. È il backup da fare prima di disinstallare o cambiare telefono.
- L'icona di condivisione in alto fa la stessa esportazione ma la manda a un'altra app (mail, chat…).

### Importare un sites.json dal telefono

In Impostazioni → *Siti configurati* il pulsante **Importa sites.json da file** apre il selettore file di Android (Download, Drive, scheda SD…). Rispetto a Git l'importazione è più prudente:

- le regole del file diventano **locali** (etichetta *Locale*), quindi la sincronizzazione Git non le cambia senza chiedere;
- **aggiunge soltanto**: le regole che hai già e che nel file non ci sono restano;
- un URL già presente con la **stessa** configurazione viene ignorato; con una configurazione **diversa** compare il dialog dei conflitti (Mantieni locale / Sovrascrivi / … per tutti). Sovrascrivere con il file rende la regola locale, anche se prima veniva da Git.

Il file esportato è pronto da committare nel repository.

## Limiti

- Le pagine vengono scaricate senza eseguire JavaScript: i siti che caricano il testo solo via JS funzionano se espongono il JSON-LD.
- Alcuni siti mostrano una pagina di consenso cookie o un paywall prima dell'articolo: in quel caso l'app legge quella pagina. Per i contenuti a pagamento resta solo l'anteprima pubblica.
- Le regole sono salvate nell'app (`files/rules.json`) e vengono perse disinstallandola; usa l'esportazione o il repository Git come copia.

## Struttura

```
app/src/main/java/it/distilla/
├── MainActivity.kt          ricezione del link condiviso
├── core/                    motore, senza dipendenze Android
│   ├── ArticleExtractor.kt  download, regole XPath, rilevamento automatico, JSON-LD
│   ├── CssSelector.kt       selettori CSS includi/escludi con jolly nelle classi
│   ├── TextRenderer.kt      HTML → testo semplice
│   ├── UrlMatcher.kt        pattern con wildcard
│   ├── SitesJson.kt         formato sites.json
│   ├── RuleMerger.kt        unione con Git e conflitti
│   ├── SpeechText.kt        testo diviso in paragrafi per la voce
│   ├── LanguageDetector.kt  lingua del testo e scelta della voce
│   ├── History.kt           cronologia delle notizie aperte
│   ├── PlayQueue.kt         coda di ascolto (logica e salvataggio)
│   ├── BrowserHints.kt      pagine di verifica anti-bot, errori HTTP
│   ├── UrlResolver.kt       risoluzione dei link abbreviati
│   └── SiteRule.kt
├── data/                    regole locali, preferenze, sincronizzazione Git, ArticleLoader, BrowserFetcher (WebView)
├── playback/                coda di ascolto: PlaybackService (Media3, Android Auto), TtsPlayer, TtsEngine
└── ui/                      schermate Compose (lettura, home, impostazioni, editor regole, conflitti)
```

Librerie: Jetpack Compose (Material 3), jsoup 1.18 per HTML e XPath, kotlinx.serialization-json.

## Pubblicazione su GitHub (Releases)

L'APK non va nel repository ma nelle **Releases**. Lo fa da solo il workflow `.github/workflows/release.yml`: a ogni tag `v*` compila, firma con la tua chiave, verifica la firma e crea la release con l'APK, il file `.sha256` e le novità prese dai changelog di `fastlane/`.

**Una volta sola: la chiave nei Secrets.** In *Settings → Secrets and variables → Actions → New repository secret* crea:

| Secret | Valore |
|---|---|
| `KEYSTORE_BASE64` | il file `.jks` codificato in base64 |
| `KEYSTORE_PASSWORD` | password del keystore |
| `KEY_ALIAS` | alias della chiave (es. `distilla`) |
| `KEY_PASSWORD` | password della chiave |

Per ottenere il base64 del `.jks`:

```bash
# Linux
base64 -w0 distilla-upload.jks > distilla-upload.b64
# macOS
base64 -i distilla-upload.jks -o distilla-upload.b64
# Windows (PowerShell): lo copia negli appunti
[Convert]::ToBase64String([IO.File]::ReadAllBytes("distilla-upload.jks")) | Set-Clipboard
```

Con la CLI `gh` si può fare tutto da terminale, senza passare per file intermedi:

```bash
base64 -w0 ../distilla-upload.jks | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS --body distilla
gh secret set KEY_PASSWORD
```

Cancella poi il file `.b64`: è la tua chiave, solo codificata.

**A ogni nuova versione:**

1. In `app/build.gradle.kts` aumenta `versionCode` (+1) e `versionName` (es. `1.1`).
2. Scrivi le novità in `fastlane/metadata/android/it-IT/changelogs/<versionCode>.txt` e `en-US/changelogs/<versionCode>.txt`.
3. Commit, poi tag e push:
   ```bash
   git commit -am "Distilla 1.1"
   git tag -a v1.1 -m "Distilla 1.1"
   git push origin master v1.1
   ```
4. In *Actions* segui la compilazione (circa 5 minuti); alla fine la release compare in *Releases*.

Il workflow si ferma senza pubblicare se il tag non corrisponde a `versionName`, se manca un secret o se l'APK risulta firmato con la chiave di debug. Da *Actions → Release APK → Run workflow* si può anche fare una compilazione di prova: l'APK si scarica dalla pagina dell'esecuzione, senza creare una release.

Note:
- Non sostituire l'APK di una versione già pubblicata: per una correzione fai una nuova versione (es. `1.1.1`).
- Nelle note di ogni release c'è l'impronta SHA-256 del certificato di firma: è la stessa per sempre e permette di verificare che l'APK sia tuo (per esempio con AppVerifier).
- Con le release fatte così, gli utenti possono ricevere gli aggiornamenti con [Obtainium](https://github.com/ImranR98/Obtainium) indicando l'indirizzo del repository.
- F-Droid compila da solo dai tag e firma con la propria chiave: chi installa da F-Droid non può aggiornare con l'APK di GitHub, e viceversa, senza prima disinstallare.

## Pubblicazione su Google Play

- `keystore.properties.example` → copialo in `keystore.properties` con i dati della tua chiave di caricamento: la build `release` viene firmata con quella (senza il file usa la chiave di debug, non accettata da Play). Il file e il `.jks` sono esclusi da Git.
- Per Play si carica un **App Bundle**: *Build → Generate Signed App Bundle or APK → Android App Bundle*, oppure `./gradlew bundleRelease` → `app/build/outputs/bundle/release/`.
- `PRIVACY.md`: informativa sulla privacy da indicare in Play Console.
- `store/`: icona 512×512, grafica in evidenza 1024×500, testi della scheda (IT/EN) e risposte ai moduli di Play Console.
- L'app ha `targetSdk 36`, richiesto da Google Play per nuove app e aggiornamenti dal 31 agosto 2026.

## Pubblicazione su F-Droid

Distilla è software libero con licenza **GPL-3.0** (file `LICENSE`) e usa solo librerie libere, quindi è adatta a F-Droid.

- `fastlane/metadata/android/` contiene titolo, descrizioni (IT/EN), icona, grafica e changelog, letti automaticamente da F-Droid. Gli screenshot vanno in `fastlane/metadata/android/en-US/images/phoneScreenshots/` come `1.png`, `2.png`…
- Il changelog di ogni versione va in `fastlane/metadata/android/<lingua>/changelogs/<versionCode>.txt` (max 500 caratteri).
- Ogni versione va **taggata** in Git come `v` + `versionName` (es. `v1.0`): F-Droid controlla i tag e compila da solo le nuove versioni.
- `versionCode` e `versionName` in `app/build.gradle.kts` devono restare scritti come valori letterali: F-Droid li legge da lì.
- `store/fdroid/io.github.renzodambrosi.distilla.yml` è il file da proporre nel repository [fdroiddata](https://gitlab.com/fdroid/fdroiddata) come `metadata/io.github.renzodambrosi.distilla.yml`.

