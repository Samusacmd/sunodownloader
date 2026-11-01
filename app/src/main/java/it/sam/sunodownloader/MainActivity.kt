package it.sam.sunodownloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView
    private lateinit var browser: Browser
    private lateinit var adapter: SongAdapter

    private lateinit var loginStatus: TextView
    private lateinit var profileUrl: EditText
    private lateinit var links: EditText
    private lateinit var count: TextView
    private lateinit var bitrate: Spinner
    private lateinit var artistFolder: CheckBox
    private lateinit var saveDir: TextView
    private var browserShown = false
    private lateinit var browserBox: FrameLayout
    private lateinit var btnScan: Button
    private lateinit var btnDownload: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button
    private lateinit var btnLogin: Button
    private lateinit var btnCookies: Button
    private lateinit var btnLogout: Button
    private lateinit var btnBrowse: Button
    private lateinit var fileLabel: TextView
    private lateinit var fileBar: LinearProgressIndicator
    private lateinit var totalLabel: TextView
    private lateinit var totalBar: LinearProgressIndicator
    private lateinit var logView: TextView
    private lateinit var logScroll: NestedScrollView

    private val logBuf = SpannableStringBuilder()
    private var logLines = 0
    private var ctl: JobCtl? = null
    private var jobStart = 0L

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshLogin()
        log(if (SunoSession.isLoggedIn()) "✔ Login riuscito, sessione salvata."
        else "⚠ Login non rilevato: riprova e premi Fatto solo dopo essere entrato.")
    }

    private val browseLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        refreshLogin()
        if (r.resultCode == RESULT_OK) handleBrowse(r.data)
    }

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                prefs.edit().putString("save_tree", uri.toString()).apply()
                updateSaveDir()
                log("📁 Salvataggio in ${Saver.label(uri)}")
            } catch (e: Exception) {
                log("✖ Cartella non utilizzabile: ${e.message}")
            }
        }
    }

    private val cookieLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCookies(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        bindViews()

        web = findViewById(R.id.web)
        Browser.setup(web, worker = true)
        browser = Browser(web)
        Net.userAgent = Browser.desktopUa(this)

        findViewById<TextView>(R.id.appTitle).text = "Suno Downloader v${BuildConfig.VERSION_NAME}"
        val lame = try { "LAME " + LameNative.version() } catch (e: Throwable) { "⚠ LAME non caricato: ${e.message}" }
        log("Suno Downloader v${BuildConfig.VERSION_NAME} — $lame")
        if (!Browser.hookSupported()) log("⚠ WebView vecchia: aggiorna \"Android System WebView\" dal Play Store per una cattura affidabile.")
        refreshLogin()
        handleShare(intent)
        findViewById<NestedScrollView>(R.id.scroll).post { findViewById<NestedScrollView>(R.id.scroll).scrollTo(0, 0) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshLogin()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onDestroy() {
        ctl?.stopped = true
        web.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI
    private fun bindViews() {
        loginStatus = findViewById(R.id.loginStatus)
        profileUrl = findViewById(R.id.profileUrl)
        links = findViewById(R.id.links)
        count = findViewById(R.id.count)
        bitrate = findViewById(R.id.bitrate)
        artistFolder = findViewById(R.id.artistFolder)
        saveDir = findViewById(R.id.saveDir)
        browserBox = findViewById(R.id.browserBox)
        btnScan = findViewById(R.id.btnScan)
        btnDownload = findViewById(R.id.btnDownload)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)
        btnLogin = findViewById(R.id.btnLogin)
        btnCookies = findViewById(R.id.btnCookies)
        btnLogout = findViewById(R.id.btnLogout)
        btnBrowse = findViewById(R.id.btnBrowse)
        fileLabel = findViewById(R.id.fileLabel)
        fileBar = findViewById(R.id.fileBar)
        totalLabel = findViewById(R.id.totalLabel)
        totalBar = findViewById(R.id.totalBar)
        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)

        adapter = SongAdapter { updateCount() }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        bitrate.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            Mp3Converter.BITRATES.map { it.toString() })
        val savedRate = prefs.getInt("bitrate", 256)
        bitrate.setSelection(Mp3Converter.BITRATES.indexOf(savedRate).coerceAtLeast(0))
        profileUrl.setText(prefs.getString("profile_url", "https://suno.com/me"))
        links.setText(prefs.getString("links", ""))
        artistFolder.isChecked = prefs.getBoolean("artist_folder", false)
        setBrowserVisible(false)
        updateSaveDir()
        findViewById<Button>(R.id.btnSaveDir).setOnClickListener { folderLauncher.launch(null) }
        findViewById<Button>(R.id.btnSaveDirReset).setOnClickListener {
            prefs.edit().remove("save_tree").apply()
            updateSaveDir()
            log("📁 Salvataggio in ${Saver.DEFAULT_LABEL}")
        }

        findViewById<EditText>(R.id.filter).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { adapter.filter = s?.toString() ?: "" }
        })
        findViewById<CheckBox>(R.id.onlyOwner).setOnCheckedChangeListener { _, c -> adapter.onlyOwner = c }
        findViewById<Button>(R.id.btnAll).setOnClickListener { adapter.setAllVisible(true) }
        findViewById<Button>(R.id.btnNone).setOnClickListener { adapter.setAllVisible(false) }
        findViewById<Button>(R.id.btnInvert).setOnClickListener { adapter.invertVisible() }

        btnLogin.setOnClickListener { loginLauncher.launch(Intent(this, LoginActivity::class.java)) }
        btnCookies.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Importa sessione Suno")
                .setItems(arrayOf(
                    "Incolla dagli appunti (Cookie-Editor su Firefox)",
                    "Da file (Esporta sessione del programma PC)",
                )) { _, which ->
                    if (which == 0) pasteCookies() else cookieLauncher.launch(arrayOf("*/*"))
                }
                .setNegativeButton("Annulla", null).show()
        }
        btnLogout.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Esci da Suno?")
                .setMessage("Cancella la sessione salvata nell'app.")
                .setPositiveButton("Esci") { _, _ -> SunoSession.logout(); refreshLogin(); log("Sessione cancellata.") }
                .setNegativeButton("Annulla", null).show()
        }
        btnScan.setOnClickListener { startScan() }
        btnBrowse.setOnClickListener { browseLauncher.launch(Intent(this, BrowseActivity::class.java)) }
        btnDownload.setOnClickListener { startDownload() }
        btnPause.setOnClickListener {
            val c = ctl ?: return@setOnClickListener
            c.paused = !c.paused
            btnPause.text = if (c.paused) "Riprendi" else "Pausa"
            log(if (c.paused) "⏸ In pausa" else "▶ Ripreso")
        }
        btnStop.setOnClickListener { ctl?.let { it.stopped = true; log("⏹ Arresto richiesto...") } }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            logBuf.clear(); logLines = 0; logView.text = ""
        }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Log Suno Downloader", logBuf.toString()))
            Toast.makeText(this, "Log copiato", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setBrowserVisible(v: Boolean) {
        val h = if (v) (380 * resources.displayMetrics.density).toInt() else 1
        browserBox.layoutParams = browserBox.layoutParams.apply { height = h }
        browserShown = v
    }

    /** Cartella scelta, solo se il permesso e' ancora valido (altrimenti Music/Suno). */
    private fun saveTreeUri(): Uri? {
        val s = prefs.getString("save_tree", null) ?: return null
        val uri = Uri.parse(s)
        val ok = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (ok) uri else null
    }

    private fun updateSaveDir() {
        saveDir.text = "Salva in: " + Saver.label(saveTreeUri())
    }

    private fun refreshLogin() {
        val ok = SunoSession.isLoggedIn()
        val h = SunoSession.sessionHandle()
        loginStatus.text = if (ok) "Sessione: ✔ attiva" + (if (h != null) " (@$h)" else "") else "Sessione: ✖ non connesso"
        loginStatus.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.warn))
    }

    private fun updateCount() {
        count.text = "${adapter.checked().size} / ${adapter.total()} spuntati"
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("profile_url", profileUrl.text.toString())
            .putString("links", links.text.toString())
            .putInt("bitrate", Mp3Converter.BITRATES[bitrate.selectedItemPosition.coerceAtLeast(0)])
            .putBoolean("artist_folder", artistFolder.isChecked)
            .apply()
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val found = Regex("https?://\\S*suno\\.com/\\S+").findAll(text).map { it.value }.toList()
        if (found.isEmpty()) { log("⚠ Nessun link Suno nel testo condiviso"); return }
        val cur = links.text.toString().trim()
        links.setText((if (cur.isEmpty()) "" else cur + "\n") + found.joinToString("\n"))
        log("🔗 Aggiunti ${found.size} link condivisi: premi Scarica.")
    }

    // ------------------------------------------------------------------ log
    fun log(msg: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { runOnUiThread { log(msg) }; return }
        val color = when {
            msg.contains("✔") -> R.color.ok
            msg.contains("✖") -> R.color.err
            msg.contains("⚠") || msg.contains("⏹") -> R.color.warn
            msg.startsWith("[") -> R.color.accent
            else -> R.color.fg
        }
        val start = logBuf.length
        logBuf.append(msg).append("\n")
        logBuf.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, color)), start, logBuf.length, 0)
        logLines++
        if (logLines > 600) {   // tiene il log leggero
            val cut = logBuf.indexOf("\n", logBuf.length / 3)
            if (cut > 0) { logBuf.delete(0, cut + 1); logLines = logBuf.count { it == '\n' } }
        }
        logView.text = logBuf
        logScroll.post { logScroll.scrollTo(0, logView.height) }
    }

    // ------------------------------------------------------------------ lavori
    private fun setRunning(running: Boolean) {
        btnScan.isEnabled = !running
        btnDownload.isEnabled = !running
        btnLogin.isEnabled = !running
        btnCookies.isEnabled = !running
        btnLogout.isEnabled = !running
        btnBrowse.isEnabled = !running
        btnPause.isEnabled = running
        btnStop.isEnabled = running
        btnPause.text = "Pausa"
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startJob(block: suspend (JobCtl) -> Unit) {
        if (ctl != null) return
        val c = JobCtl()
        ctl = c
        jobStart = SystemClock.elapsedRealtime()
        setRunning(true)
        lifecycleScope.launch {
            try {
                block(c)
            } catch (e: StopRequested) {
                log("⏹ Interrotto.")
            } catch (e: Exception) {
                log("✖ Errore: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                ctl = null
                setRunning(false)
            }
        }
    }

    private fun normalizeProfile(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) t = "https://suno.com/me"
        if (t.startsWith("@")) t = "https://suno.com/$t"
        if (!t.startsWith("http")) t = "https://$t"
        return t
    }

    private fun startScan() {
        saveSettings()
        val url = normalizeProfile(profileUrl.text.toString())
        val go = {
            startJob { c ->
                log("🔎 Scansione profilo: $url")
                totalLabel.text = "Scansione in corso..."
                try {
                    val (items, owner) = Scanner(browser, ::log, c) { n ->
                        runOnUiThread { totalLabel.text = "Scansione: $n brani visti" }
                    }.scan(url)
                    adapter.setItems(items, owner)
                    log("✔ ${items.size} brani in elenco" + (if (owner != null) " (@$owner)" else "") +
                        ". Spunta quelli da scaricare e premi Scarica.")
                } finally {
                    totalLabel.text = "Totale: -"
                }
            }
        }
        if (!SunoSession.isLoggedIn()) {
            AlertDialog.Builder(this).setTitle("Non sei connesso a Suno")
                .setMessage("Per il tuo profilo (/me) e i brani privati serve il login. " +
                    "Per un profilo pubblico (@nome) puoi continuare anche senza.")
                .setPositiveButton("Login") { _, _ -> loginLauncher.launch(Intent(this, LoginActivity::class.java)) }
                .setNeutralButton("Continua") { _, _ -> go() }
                .setNegativeButton("Annulla", null).show()
        } else go()
    }

    /** Risultato del browser: un brano da scaricare subito, oppure i brani della pagina da scegliere. */
    private fun handleBrowse(data: Intent?) {
        if (data == null) return
        when (data.getStringExtra(BrowseActivity.EXTRA_MODE)) {
            BrowseActivity.MODE_SONG -> {
                val link = data.getStringExtra(BrowseActivity.EXTRA_LINK) ?: return
                log("🌐 Dal browser: $link")
                startDownload(only = listOf(link))
            }
            BrowseActivity.MODE_LIST -> {
                val arr = try { org.json.JSONArray(data.getStringExtra(BrowseActivity.EXTRA_SONGS) ?: "[]") }
                catch (e: Exception) { org.json.JSONArray() }
                val items = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id")
                    if (!Util.UUID_RE.matches(id)) null
                    else Song(id.lowercase(), title = o.optString("title").ifEmpty { "(senza titolo)" },
                        handle = o.optString("handle"), image = o.optString("image"), checked = true)
                }
                val owner = items.filter { it.handle.isNotEmpty() }.groupingBy { it.handle }.eachCount()
                    .maxByOrNull { it.value }?.key
                adapter.setItems(items, owner)
                log("🌐 ${items.size} brani presi da ${data.getStringExtra(BrowseActivity.EXTRA_LINK)}: " +
                    "togli la spunta a quelli che non vuoi e premi Scarica.")
            }
        }
    }

    private fun startDownload(only: List<String>? = null) {
        saveSettings()
        val manual = links.text.toString().lines().map { it.trim() }
            .filter { it.contains("suno.com/") || it.contains("suno.ai/") }
        val all = only ?: (adapter.checked().map { it.link } + manual).distinct()
        if (all.isEmpty()) {
            Toast.makeText(this, "Nessun brano: cerca nel profilo e spunta, oppure incolla dei link", Toast.LENGTH_LONG).show()
            return
        }
        val rate = Mp3Converter.BITRATES[bitrate.selectedItemPosition.coerceAtLeast(0)]
        val hints = adapter.allItems().associateBy { it.id }
        val folder = artistFolder.isChecked
        startJob { c ->
            val tree = saveTreeUri()
            log("⬇ Download di ${all.size} brani — MP3 $rate kbps in ${Saver.label(tree)}")
            val d = Downloader(this, browser, ::log, c,
                fprog = { pct, phase -> runOnUiThread { fileBar.setProgressCompat(pct, true); fileLabel.text = "File: $phase ($pct%)" } },
                tprog = { done, total -> runOnUiThread { updateTotal(done, total) } },
                showBrowser = { v -> runOnUiThread { setBrowserVisible(v) } },
                saveTree = tree)
            val (ok, n) = d.run(all, rate, folder, hints, false)
            log((if (ok == n) "✔" else "⚠") + " Fine: $ok/$n scaricati in ${Saver.label(tree)}")
            setBrowserVisible(false)
            fileLabel.text = "File: -"
        }
    }

    private fun updateTotal(done: Int, total: Int) {
        val pct = if (total > 0) done * 100 / total else 0
        totalBar.setProgressCompat(pct, true)
        val elapsed = (SystemClock.elapsedRealtime() - jobStart) / 1000
        val eta = if (done > 0) elapsed * (total - done) / done else null
        totalLabel.text = "Totale: $done/$total  —  trascorso ${Util.fmtTime(elapsed)}  —  rimanente ${Util.fmtTime(eta)}"
    }

    private fun importCookies(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                }
                reportImport(SunoSession.importCookies(text, replace = true), "file")
            } catch (e: Exception) {
                log("✖ Importazione cookie fallita: ${e.message}")
            }
            refreshLogin()
        }
    }

    /** Incolla l'esportazione JSON di Cookie-Editor; si puo' ripetere (suno.com, poi clerk.suno.com). */
    private fun pasteCookies() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        if (text.isBlank()) {
            Toast.makeText(this, "Appunti vuoti: in Cookie-Editor premi Export → JSON", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                reportImport(SunoSession.importCookies(text, replace = false), "appunti")
            } catch (e: Exception) {
                log("✖ Importazione cookie fallita: ${e.message}")
            }
            refreshLogin()
        }
    }

    private fun reportImport(n: Int, from: String) {
        if (n == 0) {
            log("✖ Nessun cookie Suno riconosciuto ($from). In Cookie-Editor usa Export → JSON.")
            return
        }
        log("🍪 Importati $n cookie ($from)")
        val keys = SunoSession.keyCookies()
        log("   " + keys.entries.joinToString("   ") { (k, v) -> "$k ${if (v) "✔" else "✖"}" })
        when {
            !SunoSession.isLoggedIn() ->
                log("⚠ Sessione non valida: fai il login su suno.com nel browser ed esporta di nuovo.")
            keys["__client"] != true ->
                log("⚠ Manca __client: senza, la sessione scade in pochi minuti. In Firefox apri " +
                    "https://clerk.suno.com , esporta con Cookie-Editor e incolla anche quello.")
            else -> log("✔ Sessione Suno attiva: ora puoi usare 'Cerca brani' e scaricare.")
        }
    }
}
