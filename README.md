# Laboratorio

Client di chat AI nativo per Android, pensato per un uso personale su un telefono
riciclato (OPPO Reno4 5G / CPH2091). Interfaccia in stile terminale scuro, nessuna
dipendenza da servizi cloud: il modello gira sul telefono.

Non usa WebView: l'interfaccia è costruita con viste Android native.

## Cosa fa

- **Chat locale**: avvia `llama-server` (llama.cpp) su `127.0.0.1` con porta casuale
  e token di accesso, e parla con l'API OpenAI-compatibile `/v1/chat/completions`.
- **Dettatura offline**: riconoscimento vocale italiano con Vosk, interamente sul
  dispositivo.
- **Ricerca onion**: dato un dominio, legge l'intestazione `Onion-Location` passando
  da un processo Tor incorporato con proxy SOCKS su loopback, e mostra l'indirizzo
  verificato con un pulsante per aprirlo in Tor Browser.
- **Widget**: barra "Chat Laboratorio" per la Home, con apertura chat o dettatura.
- **Sfondo animato**: servizio live wallpaper Tokyo Night, separato dall'app.

## Struttura

```
java/it/reno/lab/
  MainActivity.java    interfaccia nativa e chat
  OnionAddress.java    validazione onion v3 (base32, versione, checksum)
  OnionLookup.java     interpretazione header e catena di redirect
  OfflineVoice.java    dettatura Vosk
  LabWidget.java       provider widget
  WidgetEntry.java     apertura chat/dettatura dal widget
  NightWallpaper.java  live wallpaper
res/                   icone, layout del widget, tema
assets/credits.txt     attribuzioni
legacy/web/            vecchia interfaccia HTML/JS, non più usata né inclusa nell'APK
test/                  test strumentali e test host-side
```

## Build

La build è volutamente senza Gradle e usa `aapt2`, `javac`, `d8` e `apksigner`.

```bash
bash build.sh          # APK firmata
bash build.sh --debug  # variante con test strumentali
```

Servono, sotto `../.tools/lab-build/`:

- Android SDK platform 35 e build-tools (`aapt2`, `d8`, `apksigner`, `zipalign`)
- JDK
- `llama/llama-b9590/` con `llama-server` e le librerie condivise
- `voice/` con Vosk, JNA e il modello `vosk-it.zip`
- `tor/libTor.so` (binario Tor, vedi attribuzioni)
- una keystore `laboratorio-signing.p12` con relativo file password

Nessuna chiave di firma, nessun modello e nessun binario di terze parti sono
inclusi in questo repository.

## Modello

Il modello atteso è `Qwen2.5-1.5B-Instruct` in formato GGUF, quantizzazione Q4_K_M,
verificato all'avvio per dimensione e SHA-256:

```
6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e
```

Va copiato in `Android/data/it.reno.lab/files/models/qwen2.5.gguf`.

## Limiti noti

- La ricerca onion è **sperimentale**. Il proxy SOCKS è su loopback e non è
  autenticato: non è isolato da altre app del dispositivo. L'assenza di perdite DNS
  non è stata verificata con una cattura di traffico.
- L'app non è una VPN e non anonymizza il resto del sistema.
- Tor Browser 16 non accetta link esterni e non può essere browser predefinito: in
  quel caso l'app copia l'indirizzo negli appunti.
- Il riconoscimento vocale usa un modello italiano piccolo e può sbagliare parole.

## Licenza

Codice dell'applicazione: GNU GPL v3.0 o successiva (vedi `LICENSE`).

Componenti di terze parti, distribuiti separatamente e non inclusi qui:

- llama.cpp — MIT
- Vosk API e modello italiano — Apache 2.0
- JNA — Apache 2.0
- Tor — BSD 3-Clause

Vedi `assets/credits.txt`.
