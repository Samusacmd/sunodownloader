package it.sam.sunodownloader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/** La WebView usata come "browser automatico" (al posto di Playwright). Da usare sul thread principale. */
class Browser(val web: WebView) {

    private var pageDone: CompletableDeferred<Unit>? = null

    init {
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // WebView vecchia senza script "a inizio documento": iniezione di riserva (meno affidabile)
                if (!hookSupported()) view.evaluateJavascript(Js.HOOK, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageDone?.complete(Unit)
            }
        }
    }

    val url: String get() = web.url ?: ""

    suspend fun load(url: String, timeoutMs: Long = 45000) {
        val d = CompletableDeferred<Unit>()
        pageDone = d
        web.loadUrl(url)
        withTimeoutOrNull(timeoutMs) { d.await() }
    }

    /** Esegue JS e restituisce il risultato grezzo (codificato JSON da WebView). */
    suspend fun eval(js: String): String = suspendCancellableCoroutine { cont ->
        web.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r ?: "null") }
    }

    /** Per gli script che restituiscono una stringa. */
    suspend fun evalString(js: String): String? {
        val r = eval(js)
        return try {
            JSONTokener(r).nextValue() as? String
        } catch (e: Exception) {
            null
        }
    }

    /** Per gli script che restituiscono JSON.stringify(...): JSONObject / JSONArray. */
    suspend fun evalJson(js: String): Any? {
        val s = evalString(js) ?: return null
        return try {
            JSONTokener(s).nextValue()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** User agent desktop (stesso motore Chrome della WebView): Suno mostra la stessa pagina della versione PC. */
        fun desktopUa(ctx: Context): String {
            val def = try { WebSettings.getDefaultUserAgent(ctx) } catch (e: Exception) { "" }
            val ver = Regex("Chrome/([\\d.]+)").find(def)?.groupValues?.get(1) ?: "130.0.0.0"
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$ver Safari/537.36"
        }

        @SuppressLint("SetJavaScriptEnabled")
        fun setup(web: WebView, worker: Boolean) {
            val s = web.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.databaseEnabled = true
            s.mediaPlaybackRequiresUserGesture = false   // autoplay come --autoplay-policy
            s.userAgentString = desktopUa(web.context)
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            s.builtInZoomControls = true
            s.displayZoomControls = false
            s.javaScriptCanOpenWindowsAutomatically = true
            s.setSupportMultipleWindows(false)
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(web, true)
            // Non rivelare che e' una WebView (alcuni login, es. Google, la bloccano)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                try { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet()) } catch (e: Exception) {}
            }
            if (worker && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, Js.HOOK, setOf("*"))
            }
        }

        fun hookSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }
}
