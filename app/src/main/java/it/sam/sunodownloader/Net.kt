package it.sam.sunodownloader

import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Richieste HTTP con gli stessi cookie e user agent della WebView. Da chiamare fuori dal thread principale. */
object Net {
    @Volatile var userAgent: String = "Mozilla/5.0"

    fun get(url: String, timeoutMs: Int = 120000, minSize: Int = 1, bearer: String? = null): ByteArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", "https://suno.com/")
            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            if (c.responseCode in 200..299) {
                val b = c.inputStream.use { it.readBytes() }
                if (b.size >= minSize) b else null
            } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** API di Suno per un brano (se risponde ci da' tutto subito). Prova con e senza token. */
    fun clip(id: String): JSONObject? {
        val url = "https://studio-api.prod.suno.com/api/clip/$id"
        val tokens: List<String?> = listOf(SunoSession.sessionToken(), null).distinct()
        for (t in tokens) {
            val b = get(url, 15000, bearer = t) ?: continue
            try {
                return JSONObject(String(b, Charsets.UTF_8))
            } catch (e: Exception) {
            }
        }
        return null
    }
}
