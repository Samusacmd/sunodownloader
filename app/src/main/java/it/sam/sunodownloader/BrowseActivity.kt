package it.sam.sunodownloader

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONTokener

/** Browser completo dentro l'app: navighi su Suno con la tua sessione e scegli cosa scaricare. */
class BrowseActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var btnSong: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        web = findViewById(R.id.web)
        address = findViewById(R.id.address)
        btnSong = findViewById(R.id.btnSong)
        progress = findViewById(R.id.progress)

        // niente script di cattura qui: audio normale, puoi ascoltare; versione mobile del sito
        Browser.setup(web, worker = false, desktop = false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) {}
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { showUrl(url) }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { showUrl(url) }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }
        }
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            systemDownload(url, contentDisposition, mimeType)
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<Button>(R.id.btnFwd).setOnClickListener { if (web.canGoForward()) web.goForward() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { web.reload() }
        address.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) { go(address.text.toString()); true } else false
        }
        btnSong.setOnClickListener {
            val url = web.url ?: return@setOnClickListener
            setResult(RESULT_OK, Intent().putExtra(EXTRA_MODE, MODE_SONG).putExtra(EXTRA_LINK, url))
            finish()
        }
        findViewById<Button>(R.id.btnPage).setOnClickListener { collectPage() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        web.loadUrl(intent.getStringExtra(EXTRA_LINK) ?: "https://suno.com/")
    }

    private fun showUrl(url: String?) {
        val u = url ?: return
        if (!address.hasFocus()) address.setText(u)
        btnSong.isEnabled = u.contains("/song/") && Util.UUID_RE.containsMatchIn(u)
    }

    private fun go(raw: String) {
        var t = raw.trim()
        if (t.isEmpty()) return
        if (t.startsWith("@")) t = "https://suno.com/$t"
        if (!t.startsWith("http")) t = if (t.contains(".") && !t.contains(" ")) "https://$t"
        else "https://suno.com/search?q=" + Uri.encode(t)
        web.loadUrl(t)
        address.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(address.windowToken, 0)
    }

    /** Prende i brani visibili nella pagina (profilo, playlist, ricerca...). */
    private fun collectPage() {
        web.evaluateJavascript(Js.DOM_SONGS) { r ->
            val arr = try {
                JSONArray(JSONTokener(r).nextValue() as String)
            } catch (e: Exception) {
                JSONArray()
            }
            if (arr.length() == 0) {
                Toast.makeText(this, "Nessun brano in questa pagina (scorri per caricarne altri)", Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_MODE, MODE_LIST).putExtra(EXTRA_SONGS, arr.toString())
                .putExtra(EXTRA_LINK, web.url ?: ""))
            finish()
        }
    }

    /** Download avviati dal sito stesso (es. menu Download di Suno): finiscono in Music/Suno. */
    private fun systemDownload(url: String, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http")) {
            Toast.makeText(this, "Questo download non si può salvare da qui: usa «⬇ Scarica brano»", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", web.settings.userAgentString)
                addRequestHeader("Referer", "https://suno.com/")
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Suno/$name")
            }
            getSystemService(DownloadManager::class.java).enqueue(req)
            Toast.makeText(this, "Download avviato: Music/Suno/$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download non riuscito: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_LINK = "link"
        const val EXTRA_SONGS = "songs"
        const val MODE_SONG = "song"
        const val MODE_LIST = "list"
    }
}
