package it.sam.sunodownloader

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Login a Suno in una WebView: la sessione resta salvata nei cookie dell'app. */
class LoginActivity : AppCompatActivity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        web = findViewById(R.id.web)
        val status = findViewById<TextView>(R.id.status)
        Browser.setup(web, worker = false)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                status.text = if (SunoSession.isLoggedIn()) "✔ Sei dentro: premi Fatto" else "Fai il login, poi premi Fatto"
            }
        }
        findViewById<Button>(R.id.done).setOnClickListener {
            CookieManager.getInstance().flush()
            setResult(RESULT_OK)
            finish()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else { CookieManager.getInstance().flush(); finish() }
            }
        })
        web.loadUrl("https://suno.com/")
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
