package it.sam.sunodownloader

import android.util.Base64
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Sessione Suno (Clerk) letta dai cookie della WebView. */
object SunoSession {
    private val URLS = listOf("https://suno.com", "https://clerk.suno.com", "https://auth.suno.com",
        "https://studio-api.prod.suno.com")

    fun cookies(): Map<String, String> {
        val cm = CookieManager.getInstance()
        val out = LinkedHashMap<String, String>()
        for (u in URLS) {
            val s = cm.getCookie(u) ?: continue
            for (part in s.split(";")) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                val k = part.substring(0, i).trim()
                val v = part.substring(i + 1).trim()
                if (!out.containsKey(k) || out[k].isNullOrEmpty()) out[k] = v
            }
        }
        return out
    }

    /** Con sessione attiva c'e' il cookie __session oppure __client_uat > 0. */
    fun isLoggedIn(): Boolean = cookies().any { (k, v) ->
        (k.startsWith("__session") && v.isNotEmpty()) ||
            (k.startsWith("__client_uat") && v.isNotEmpty() && v != "0")
    }

    /** Token JWT della sessione (serve come Bearer per le API di Suno). */
    fun sessionToken(): String? = cookies().entries
        .firstOrNull { it.key.startsWith("__session") && it.value.count { c -> c == '.' } == 2 }?.value

    /** Handle dell'utente loggato, letto dal token __session. */
    fun sessionHandle(): String? = try {
        val part = sessionToken()!!.split(".")[1]
        val pad = part + "=".repeat((4 - part.length % 4) % 4)
        val data = JSONObject(String(Base64.decode(pad, Base64.URL_SAFE), Charsets.UTF_8))
        val h = data.optString("suno/handle").ifEmpty { data.optString("handle") }
        h.trimStart('@').lowercase().ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    fun logout() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }

    private data class Ck(val name: String, val value: String, val domain: String, val path: String,
                          val maxAge: Long, val secure: Boolean, val httpOnly: Boolean, val sameSite: String)

    /** Accetta: JSON {nome: valore} | JSON lista (Cookie-Editor / EditThisCookie) |
     *  cookies.txt Netscape | intestazione 'nome=valore; nome2=valore2'. Restituisce quanti cookie ha impostato. */
    suspend fun importCookies(raw: String): Int {
        val text = raw.trim().trimStart('﻿')
        val now = System.currentTimeMillis() / 1000
        val year = 365L * 86400
        val list = ArrayList<Ck>()

        fun mk(name: String?, value: Any?, domain: String? = ".suno.com", path: String? = "/", expires: Any? = null,
               secure: Boolean = true, httpOnly: Boolean = false, sameSite: String? = "Lax") {
            val dom = domain ?: ".suno.com"
            if (name.isNullOrEmpty() || !dom.contains("suno")) return
            var exp = (expires as? Number)?.toDouble() ?: (expires as? String)?.toDoubleOrNull() ?: 0.0
            if (exp > 1e11) exp /= 1000
            val maxAge = if (exp <= now) year else (exp - now).toLong()   // scaduto o "di sessione": persistente
            val ss = when ((sameSite ?: "lax").lowercase()) {
                "strict" -> "Strict"; "none", "no_restriction" -> "None"; else -> "Lax"
            }
            list.add(Ck(name, value?.toString() ?: "", dom, path ?: "/", maxAge, secure || ss == "None", httpOnly, ss))
        }

        val data: Any? = try { JSONTokener(text).nextValue() } catch (e: Exception) { null }
        val arr: JSONArray? = when {
            data is JSONArray -> data
            data is JSONObject && data.optJSONArray("cookies") != null -> data.getJSONArray("cookies")
            else -> null
        }
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (!c.has("name")) continue
                val exp = if (c.optBoolean("session")) null
                else c.opt("expirationDate") ?: c.opt("expires") ?: c.opt("expiry")
                mk(c.optString("name"), c.optString("value"), c.optString("domain").ifEmpty { ".suno.com" },
                    c.optString("path").ifEmpty { "/" }, exp, c.optBoolean("secure", true),
                    c.optBoolean("httpOnly", false), c.optString("sameSite").ifEmpty { null })
            }
        } else if (data is JSONObject) {
            for (k in data.keys()) {
                val v = data.get(k)
                if (v is String || v is Number) mk(k, v)
            }
        } else {
            val lines = text.lines().filter { it.isNotBlank() }
            val netscape = lines.filter { l -> l.count { it == '\t' } >= 6 }
            if (netscape.isNotEmpty()) {
                for (l in netscape) {
                    if (l.startsWith("#") && !l.startsWith("#HttpOnly_")) continue
                    val ho = l.startsWith("#HttpOnly_")
                    val f = l.replaceFirst("#HttpOnly_", "").split("\t")
                    mk(f[5], f[6].trimEnd('\r', '\n'), f[0], f[2], f[4], f[3].uppercase() == "TRUE", ho)
                }
            } else {
                val hdr = text.replaceFirst(Regex("^\\s*cookie\\s*:\\s*", RegexOption.IGNORE_CASE), "")
                for (part in hdr.split(";")) {
                    val i = part.indexOf('=')
                    if (i > 0) mk(part.substring(0, i).trim(), part.substring(i + 1).trim())
                }
            }
        }
        if (list.isEmpty()) return 0

        val cm = CookieManager.getInstance()
        // via la vecchia sessione (aspettando la fine: e' asincrono), per evitare conflitti
        suspendCancellableCoroutine<Unit> { cont -> cm.removeAllCookies { if (cont.isActive) cont.resume(Unit) } }
        for (c in list) {
            val host = c.domain.trimStart('.')
            val attrs = StringBuilder("${c.name}=${c.value}; Path=${c.path}; Max-Age=${c.maxAge}")
            if (c.domain.startsWith(".")) attrs.append("; Domain=${c.domain}")
            if (c.secure) attrs.append("; Secure")
            if (c.httpOnly) attrs.append("; HttpOnly")
            attrs.append("; SameSite=${c.sameSite}")
            cm.setCookie("https://$host", attrs.toString())
        }
        cm.flush()
        return list.size
    }
}
