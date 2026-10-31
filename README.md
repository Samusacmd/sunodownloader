# Suno Downloader per Android

Versione Android di `suno_downloader.py`: scarica le tue canzoni da Suno in **MP3** con titolo, artista e cover incorporati, nella cartella **Music/Suno** del telefono.

- Android 10 o successivo
- Kotlin nativo, compilato da GitHub Actions (nessun Android Studio)
- Encoder MP3: **LAME 3.100** compilato dai sorgenti (LGPL), decodifica con MediaCodec di Android

## Come funziona

Come la versione PC, ma senza Playwright e ffmpeg:

| PC (Python) | Android |
|---|---|
| Edge/Chrome con Playwright | WebView interna (stesso motore Chrome) |
| `add_init_script(HOOK_JS)` | `addDocumentStartJavaScript` |
| `page.on("response")` | intercettazione `fetch`/`XMLHttpRequest` in pagina |
| ffmpeg `libmp3lame` | MediaCodec → LAME 3.100 via JNI |
| mutagen (ID3) | scrittore ID3v2.3 interno |

## Uso

1. **Login Suno**: fai il login nella pagina che si apre e premi **Fatto**.
   Se il login con Google viene rifiutato, usa **Importa cookie** con un file esportato dal PC (Cookie-Editor JSON, cookies.txt o intestazione `nome=valore`).
2. **Cerca brani**: lascia `https://suno.com/me` per il tuo profilo, oppure scrivi `@nome`.
3. Spunta i brani e premi **Scarica**. Puoi anche incollare link nella casella "Link manuali" oppure **condividere** un link dall'app Suno verso Suno Downloader.
4. Tieni l'app aperta durante il download (lo schermo resta acceso da solo).

## Versioni

La versione si cambia **solo** in `app/build.gradle.kts` (`appVersionName`, `appVersionCode`), secondo Semantic Versioning.
Ogni push su `main` crea `SunoDownloader_vX.Y.Z.apk` negli Artifacts della Action e nella pagina **Releases**.

## Firma dell'APK

Per poter aggiornare l'app senza disinstallarla, l'APK va firmato sempre con la stessa chiave.
Imposta questi secrets in **Settings → Secrets and variables → Actions**:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Senza secrets la build funziona lo stesso, ma con una chiave di debug che cambia a ogni build.

## Licenze

LAME è distribuito sotto LGPL (vedi `app/src/main/cpp/lame/COPYING`).
