package it.sam.sunodownloader

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Scarica i brani: porting di process_song / capture_decrypted / worker della versione desktop. */
class Downloader(
    private val ctx: Context,
    private val b: Browser,
    private val log: (String) -> Unit,
    private val ctl: JobCtl,
    private val fprog: (Int, String) -> Unit,          // avanzamento file (0-100, fase)
    private val tprog: (Int, Int) -> Unit,             // avanzamento totale (fatti, totale)
    private val showBrowser: (Boolean) -> Unit,
    private val saveTree: android.net.Uri? = null,      // cartella scelta (null = Music/Suno)
    private val format: String = "MP3",                 // "MP3" oppure "WAV"
) {
    private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k)

    /** Restituisce (scaricati, totale). */
    suspend fun run(links: List<String>, bitrate: Int, artistFolder: Boolean, hints: Map<String, Song>,
                    startVisible: Boolean): Pair<Int, Int> {
        var ok = 0
        val n = links.size
        var visible = startVisible
        try {
            for ((i, link) in links.withIndex()) {
                ctl.check()
                tprog(i, n)
                log("[${i + 1}/$n] $link")
                try {
                    var res = processSong(link, bitrate, artistFolder, hints, visible)
                    if (!res && !visible) {
                        log("   Audio non intercettato in background, riprovo col browser visibile...")
                        visible = true
                        showBrowser(true)
                        res = processSong(link, bitrate, artistFolder, hints, visible)
                    }
                    if (res) ok++ else log("   ✖ Audio non trovato. Se la canzone e' privata usa 'Login Suno'.")
                } catch (e: StopRequested) {
                    throw e
                } catch (e: Exception) {
                    log("   ✖ Errore: ${e.message ?: e.javaClass.simpleName}")
                }
                tprog(i + 1, n)
            }
        } catch (e: StopRequested) {
            log("⏹ Download interrotto dall'utente.")
        }
        return ok to n
    }

    private suspend fun processSong(link: String, bitrate: Int, artistFolder: Boolean, hints: Map<String, Song>,
                                    visible: Boolean): Boolean {
        fprog(3, "Apertura pagina")
        ctl.check()
        var songId = Util.UUID_RE.find(link)?.value
        b.load(link)
        ctl.sleep(2500)
        fprog(15, "Lettura informazioni")
        // link corto tipo suno.com/s/xxxx: l'ID si legge dall'indirizzo finale
        if (songId == null) songId = Util.UUID_RE.find(b.url)?.value
        val id: String = songId?.lowercase() ?: throw RuntimeException("ID canzone non trovato nel link")

        var title = ""
        var artist = ""
        var image = ""
        var audioUrl = ""
        val clip = withContext(Dispatchers.IO) { Net.clip(id) }
        if (clip != null) {
            title = clip.s("title")
            artist = clip.s("display_name")
            image = clip.s("image_large_url").ifEmpty { clip.s("image_url") }
            audioUrl = clip.s("audio_url")
        }
        val meta = b.evalJson(Js.META) as? JSONObject
        if (title.isEmpty()) {
            val (t, a2) = Util.cleanTitle(meta?.s("title")?.ifEmpty { null } ?: meta?.s("doc"))
            title = t
            if (artist.isEmpty()) artist = a2
        }
        if (image.isEmpty()) image = meta?.s("image") ?: ""
        val hint = hints[id]
        if (artist.isEmpty()) artist = hint?.artist ?: ""
        if (artist.isEmpty()) {   // link all'autore nella pagina del brano
            artist = b.evalString(Js.ARTIST) ?: ""
            if (artist.isEmpty()) artist = hint?.handle ?: ""
        }
        if (title.isEmpty()) title = id
        log("   Titolo: $title" + (if (artist.isNotEmpty()) "  —  $artist" else ""))

        ctl.check()
        fprog(25, "Cattura audio")
        var audio: ByteArray? = null
        for (u in listOf(audioUrl, hint?.audio ?: "").filter { it.isNotEmpty() }.distinct()) {
            val bytes = withContext(Dispatchers.IO) { Net.get(u, minSize = 20000) }
            if (Util.isAudio(bytes)) { audio = bytes; break }
        }
        if (audio == null) {
            audio = capture(visible) { f -> fprog((25 + 35 * f).toInt(), "Cattura audio") }
        }
        val data: ByteArray = audio ?: return false
        if (!Util.isAudio(data)) throw RuntimeException("Dati catturati non riconosciuti come audio")

        val ext = Util.audioExt(data)
        ctl.check()
        fprog(62, "Download cover")
        var cover: ByteArray? = null
        if (image.isNotEmpty()) {
            cover = withContext(Dispatchers.IO) { Net.get(image)?.let { Util.toJpeg(it) } }
            if (cover == null) log("   ⚠ Cover non leggibile")
        } else {
            log("   ⚠ Cover non trovata")
        }

        val base = Util.sanitize(title)
        val sub = if (artistFolder) Util.sanitize(artist.ifEmpty { "Artista sconosciuto" }) else null
        if (format == "WAV") {
            saveWav(id, data, ext, base, sub, title, artist, cover)
            fprog(100, "Completato")
            return true
        }
        val mp3 = File(ctx.cacheDir, "$id.mp3")
        var skip = 0L
        try {
            if (ext != ".mp3") {
                val tmp = File(ctx.cacheDir, "$id.tmp$ext")
                try {
                    withContext(Dispatchers.IO) { tmp.writeBytes(data) }
                    log("   Conversione in MP3 $bitrate kbps...")
                    fprog(70, "Conversione MP3 $bitrate kbps")
                    withContext(Dispatchers.IO) {
                        Mp3Converter.convert(tmp, mp3, bitrate, ctl) { f ->
                            fprog((70 + 27 * f).toInt(), "Conversione MP3 $bitrate kbps")
                        }
                    }
                } finally {
                    tmp.delete()
                }
            } else {
                log("   Sorgente gia' MP3: salvata senza ricodifica")
                fprog(80, "Salvataggio")
                withContext(Dispatchers.IO) { mp3.writeBytes(data) }
                skip = Id3.existingLength(data).toLong()
            }
            fprog(97, "Scrittura titolo e cover")
            val tag = Id3.build(title, artist, cover)
            val sizeMb = (mp3.length() - skip + tag.size) / 1024.0 / 1024.0
            val path = withContext(Dispatchers.IO) { Saver.save(ctx, saveTree, base, ".mp3", "audio/mpeg", sub, title, artist, tag, mp3, skip) }
            log("   ✔ Salvato: $path  (${"%.2f".format(sizeMb)} MB)")
        } finally {
            mp3.delete()
        }
        fprog(100, "Completato")
        return true
    }

    /** WAV PCM 16 bit: decodifica la sorgente (anche MP3) e scrive titolo, artista e cover nel file. */
    private suspend fun saveWav(id: String, data: ByteArray, ext: String, base: String, sub: String?,
                                title: String, artist: String, cover: ByteArray?) {
        val tmp = File(ctx.cacheDir, "$id.src$ext")
        val wav = File(ctx.cacheDir, "$id.wav")
        try {
            withContext(Dispatchers.IO) { tmp.writeBytes(data) }
            log("   Conversione in WAV...")
            fprog(70, "Conversione WAV")
            withContext(Dispatchers.IO) {
                Mp3Converter.toWav(tmp, wav, title, artist, Id3.build(title, artist, cover), ctl) { f ->
                    fprog((70 + 27 * f).toInt(), "Conversione WAV")
                }
            }
            fprog(97, "Salvataggio")
            val sizeMb = wav.length() / 1024.0 / 1024.0
            val path = withContext(Dispatchers.IO) {
                Saver.save(ctx, saveTree, base, ".wav", "audio/x-wav", sub, title, artist, ByteArray(0), wav, 0)
            }
            log("   ✔ Salvato: $path  (${"%.1f".format(sizeMb)} MB)")
        } finally {
            tmp.delete()
            wav.delete()
        }
    }

    /** Fa partire la riproduzione e attende l'audio decifrato dal player. */
    private suspend fun capture(visible: Boolean, onFrac: (Float) -> Unit): ByteArray? {
        val maxIter = ((if (visible) 90 else 30) / 1.5).toInt()   // a iterazioni: la pausa non consuma il timeout
        var attempt = 0
        var asked = false
        var lastSize = -1
        var stats: String? = null
        for (it in 0 until maxIter) {
            ctl.check()
            onFrac(minOf(1f, it / 12f))
            val res = b.evalJson(Js.GRAB) as? JSONObject
            stats = res?.optJSONObject("stats")?.toString()
            val best = res?.optJSONObject("best")
            if (best != null) {
                val size = best.optInt("size")
                if (size == lastSize) {   // dimensione stabile: dati completi
                    log("   Audio decifrato catturato (${best.s("kind")}, ${size / 1024} KB)")
                    return readBest(size)
                }
                lastSize = size
            } else if (attempt < 6) {
                b.eval(Js.play(attempt))
                attempt++
            } else if (visible && !asked) {
                log("   ▶ Premi Play nel riquadro del browser...")
                asked = true
            }
            ctl.sleep(1500)
        }
        log("   (diagnostica intercettazioni: $stats)")
        return null
    }

    /** Copia l'audio catturato dalla pagina a pezzi da 768 KB. */
    private suspend fun readBest(size: Int): ByteArray {
        val out = ByteArrayOutputStream(size)
        val chunk = 768 * 1024
        var off = 0
        while (off < size) {
            ctl.check()
            val s = b.evalString(Js.chunk(off, chunk)) ?: throw RuntimeException("Lettura audio interrotta")
            if (s.isEmpty()) throw RuntimeException("Audio catturato non piu' disponibile")
            out.write(withContext(Dispatchers.Default) { Base64.decode(s, Base64.DEFAULT) })
            off += chunk
        }
        return out.toByteArray()
    }
}
