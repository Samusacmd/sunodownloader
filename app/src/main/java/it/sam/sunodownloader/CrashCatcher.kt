package it.sam.sunodownloader

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Salva qualunque crash in un file, per mostrarlo al riavvio (non serve un PC con adb). */
class SunoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                crashFile(this).writeText(report(this, thread, e))
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        fun crashFile(ctx: Context) = File(ctx.filesDir, "ultimo_crash.txt")

        fun report(ctx: Context, thread: Thread, e: Throwable): String {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val webview = try {
                androidx.webkit.WebViewCompat.getCurrentWebViewPackage(ctx)?.versionName ?: "?"
            } catch (t: Throwable) { "?" }
            return buildString {
                append("Suno Downloader v").append(BuildConfig.VERSION_NAME).append('\n')
                append("Data: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).format(Date())).append('\n')
                append("Telefono: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
                append("WebView: ").append(webview).append('\n')
                append("Thread: ").append(thread.name).append("\n\n")
                append(sw.toString())
            }
        }
    }
}

/** Schermata d'avvio: se l'ultima esecuzione e' crashata mostra l'errore, altrimenti apre l'app.
 *  Usa solo widget Android di base, cosi' funziona anche se il problema e' nell'interfaccia principale. */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = SunoApp.crashFile(this)
        if (!f.exists()) {
            openMain()
            return
        }
        val trace = try { f.readText() } catch (e: Throwable) { "Impossibile leggere il crash: $e" }
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(28, 28, 31))
            setPadding((16 * dp).toInt(), (40 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        root.addView(TextView(this).apply {
            text = "L'app si è chiusa per un errore.\nPremi «Copia errore» e incollalo nella chat."
            setTextColor(Color.rgb(255, 180, 84))
            textSize = 16f
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Copia errore"
            setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Crash Suno Downloader", trace))
                Toast.makeText(this@StartActivity, "Errore copiato", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Button(this).apply {
            text = "Riprova"
            setOnClickListener { f.delete(); openMain() }
        })
        root.addView(buttons)
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply {
            text = trace
            setTextColor(Color.rgb(236, 236, 241))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
