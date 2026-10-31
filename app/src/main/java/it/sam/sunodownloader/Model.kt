package it.sam.sunodownloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

class StopRequested : Exception("Interrotto")

/** Controllo di un'operazione in corso: pausa, ripresa, stop (come la classe Job desktop). */
class JobCtl {
    @Volatile var paused = false
    @Volatile var stopped = false

    /** Punto di controllo: aspetta se in pausa, lancia StopRequested se fermato. */
    suspend fun check() {
        while (paused && !stopped) delay(200)
        if (stopped) throw StopRequested()
    }

    /** Come check() ma per i thread in background (conversione MP3). */
    fun checkBlocking() {
        while (paused && !stopped) Thread.sleep(200)
        if (stopped) throw StopRequested()
    }

    suspend fun sleep(ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        while (true) {
            check()
            val rem = end - SystemClock.elapsedRealtime()
            if (rem <= 0) return
            delay(minOf(300L, rem))
        }
    }
}

data class Song(
    val id: String,
    var title: String = "",
    var artist: String = "",
    var handle: String = "",
    var duration: String = "",
    var date: String = "",
    var image: String = "",
    var audio: String = "",
    var json: Boolean = false,
    var checked: Boolean = true,
) {
    val link: String get() = "https://suno.com/song/$id"
}

object Util {
    val UUID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
    val PROFILE_RE = Regex("suno\\.com/(@[^/?#\\s]+|me)(?:[/?#]|$)", RegexOption.IGNORE_CASE)
    private val BAD_CHARS = Regex("[<>:\"/\\\\|?*\\x00-\\x1f]")

    fun sanitize(name: String): String {
        val s = name.replace(BAD_CHARS, "_").trim().trimEnd('.')
        return s.take(150).ifEmpty { "suno_song" }
    }

    /** 'Abdu La by S@m | Suno' -> ('Abdu La', 'S@m') */
    fun cleanTitle(raw: String?): Pair<String, String> {
        var t = (raw ?: "").replace(Regex("\\s*[|\\-–]\\s*Suno.*$", RegexOption.IGNORE_CASE), "").trim()
        var artist = ""
        val m = Regex("^(.*?)\\s+by\\s+(.+)$").find(t)
        if (m != null) {
            t = m.groupValues[1].trim()
            artist = m.groupValues[2].trim()
        }
        return t to artist
    }

    /** Cover in JPEG, lato massimo 1400 px (come to_jpeg desktop). */
    fun toJpeg(data: ByteArray, maxSide: Int = 1400): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val big = maxOf(bmp.width, bmp.height)
        val scaled = if (big > maxSide) {
            val f = maxSide.toFloat() / big
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1),
                (bmp.height * f).toInt().coerceAtLeast(1), true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
        return out.toByteArray()
    }

    fun isAudio(b: ByteArray?): Boolean {
        if (b == null || b.size < 12) return false
        fun at(i: Int, s: String) = s.indices.all { b[i + it] == s[it].code.toByte() }
        val u0 = b[0].toInt() and 0xFF
        val u1 = b[1].toInt() and 0xFF
        return at(4, "ftyp") || at(0, "ID3") || at(0, "OggS") || at(0, "RIFF") || at(0, "fLaC") ||
            (u0 == 0xFF && (u1 and 0xE0) == 0xE0) ||
            (u0 == 0x1A && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte())
    }

    fun audioExt(b: ByteArray): String {
        fun at(i: Int, s: String) = s.indices.all { b[i + it] == s[it].code.toByte() }
        return when {
            at(4, "ftyp") -> ".m4a"
            at(0, "ID3") || (b[0].toInt() and 0xFF) == 0xFF -> ".mp3"
            at(0, "OggS") -> ".ogg"
            at(0, "RIFF") -> ".wav"
            at(0, "fLaC") -> ".flac"
            else -> ".webm"
        }
    }

    fun fmtTime(sec: Long?): String {
        if (sec == null || sec < 0) return "--:--"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
