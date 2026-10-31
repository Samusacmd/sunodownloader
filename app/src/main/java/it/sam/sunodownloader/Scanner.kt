package it.sam.sunodownloader

import org.json.JSONArray
import org.json.JSONObject

/** Apre il profilo, scorre fino in fondo e raccoglie tutti i brani (porting di scan_profile). */
class Scanner(
    private val b: Browser,
    private val log: (String) -> Unit,
    private val ctl: JobCtl,
    private val onCount: (Int) -> Unit,
) {
    private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k)

    suspend fun scan(profileUrl: String, stopAfter: Int = 6, maxRounds: Int = 400): Pair<List<Song>, String?> {
        val m = Util.PROFILE_RE.find(profileUrl)
        var target: String? = m?.groupValues?.get(1)?.let { if (it.startsWith("@")) it.substring(1).lowercase() else null }
        val clips = LinkedHashMap<String, Song>()

        fun add(id: String, title: String = "", artist: String = "", handle: String = "", duration: String = "",
                date: String = "", image: String = "", audio: String = "", json: Boolean = false) {
            if (!Util.UUID_RE.matches(id)) return
            val cid = id.lowercase()
            val it = clips.getOrPut(cid) { Song(cid) }
            if (title.isNotEmpty() && (it.title.isEmpty() || json)) it.title = title
            if (artist.isNotEmpty() && it.artist.isEmpty()) it.artist = artist
            if (handle.isNotEmpty() && it.handle.isEmpty()) it.handle = handle
            if (duration.isNotEmpty() && it.duration.isEmpty()) it.duration = duration
            if (date.isNotEmpty() && it.date.isEmpty()) it.date = date
            if (image.isNotEmpty() && it.image.isEmpty()) it.image = image
            if (audio.isNotEmpty() && it.audio.isEmpty()) it.audio = audio
            if (json) it.json = true
        }

        if (target == null) {   // /me: l'utente e' quello loggato
            target = SunoSession.sessionHandle()
            if (target != null) log("   Profilo personale: @$target")
        }
        try {
            b.load(profileUrl)
            ctl.sleep(3000)
            log("   Pagina aperta: ${b.url}")
            if (Regex("sign-?in|login|accounts\\.", RegexOption.IGNORE_CASE).containsMatchIn(b.url)) {
                log("   ⚠ Suno chiede il login: usa prima 'Login Suno'.")
            }
            var still = 0
            var last = -1
            for (rnd in 0 until maxRounds) {
                ctl.check()
                onCount(clips.size)
                (b.evalJson(Js.CLIPS) as? JSONArray)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(o.s("id"), o.s("title"), o.s("artist"), o.s("handle"), o.s("duration"),
                            o.s("date"), o.s("image"), o.s("audio"), json = true)
                    }
                }
                (b.evalJson(Js.DOM_SONGS) as? JSONArray)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(o.s("id"), title = o.s("title"), handle = o.s("handle"), image = o.s("image"))
                    }
                }
                val n = if (target != null) clips.values.count { it.handle == target } else clips.size
                if (n != last) {
                    log("   Brani dell'autore trovati: $n")
                    last = n
                    still = 0
                } else {
                    still++
                    if (still >= stopAfter) break
                }
                b.eval(Js.SCROLL)
                try { b.web.pageDown(true) } catch (e: Exception) {}
                ctl.sleep(1300)
            }
        } catch (e: StopRequested) {
            log("   ⏹ Scansione interrotta: mostro i brani trovati finora.")
        }

        var items = clips.values.toList()
        var owner = target
        if (owner == null) {   // nessun handle noto: uso l'autore piu' frequente
            owner = items.filter { it.handle.isNotEmpty() }.groupingBy { it.handle }.eachCount()
                .maxByOrNull { it.value }?.key
            if (owner != null) log("   Autore dedotto: @$owner")
        }
        if (owner != null) {
            val mine = items.filter { it.handle == owner }
            val others = items.count { it.handle.isNotEmpty() && it.handle != owner }
            val unknown = items.filter { it.handle.isEmpty() }
            log("   Scartati $others brani di altri autori" +
                (if (unknown.isNotEmpty()) " e ${unknown.size} senza autore riconoscibile" else "") + ".")
            if (mine.isNotEmpty()) {
                items = mine
                items.forEach { it.checked = true }
            } else {
                log("   ⚠ Nessun brano attribuito con certezza all'autore: mostro quelli senza autore, non spuntati.")
                items = unknown
                items.forEach { it.checked = false }
            }
        } else {
            log("   ⚠ Autore del profilo non determinabile: mostro tutto, non spuntato.")
            items.forEach { it.checked = false }
        }
        items.forEach { if (it.title.isEmpty()) it.title = "(senza titolo)" }
        return items to owner
    }
}
