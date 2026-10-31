package it.sam.sunodownloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** Miniature delle cover, scaricate in background con cache in memoria. */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(120)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, view: ImageView) {
        view.tag = url
        if (url.isEmpty()) { view.setImageResource(R.drawable.ic_note); return }
        cache.get(url)?.let { view.setImageBitmap(it); return }
        view.setImageResource(R.drawable.ic_note)
        pool.execute {
            val bytes = Net.get(url, 20000)
            val bmp = bytes?.let {
                val opt = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeByteArray(it, 0, it.size, opt)
            }
            if (bmp != null) {
                cache.put(url, bmp)
                main.post { if (view.tag == url) view.setImageBitmap(bmp) }
            }
        }
    }
}

/** Elenco brani del profilo con caselle di selezione (come SelectDialog desktop). */
class SongAdapter(private val onChange: () -> Unit) : RecyclerView.Adapter<SongAdapter.VH>() {
    private var all: List<Song> = emptyList()
    private var shown: List<Song> = emptyList()
    var filter: String = ""
        set(v) { field = v; refresh() }
    var onlyOwner: Boolean = false
        set(v) { field = v; refresh() }
    var owner: String? = null
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.check)
        val cover: ImageView = v.findViewById(R.id.cover)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
    }

    fun setItems(items: List<Song>, owner: String?) {
        all = items
        this.owner = owner
        refresh()
    }

    fun refresh() {
        val q = filter.trim().lowercase()
        shown = all.filter {
            (!onlyOwner || owner == null || it.handle.isEmpty() || it.handle == owner) &&
                (q.isEmpty() || "${it.title} ${it.artist} ${it.handle}".lowercase().contains(q))
        }
        notifyDataSetChanged()
        onChange()
    }

    fun setAllVisible(v: Boolean) { shown.forEach { it.checked = v }; notifyDataSetChanged(); onChange() }
    fun invertVisible() { shown.forEach { it.checked = !it.checked }; notifyDataSetChanged(); onChange() }
    fun checked(): List<Song> = all.filter { it.checked }
    fun total(): Int = all.size
    fun allItems(): List<Song> = all

    override fun getItemCount() = shown.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = shown[position]
        h.title.text = s.title
        h.sub.text = listOf(s.artist.ifEmpty { if (s.handle.isNotEmpty()) "@${s.handle}" else "" }, s.duration, s.date)
            .filter { it.isNotEmpty() }.joinToString("  ·  ")
        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = s.checked
        h.check.setOnCheckedChangeListener { _, c -> s.checked = c; onChange() }
        h.itemView.setOnClickListener { h.check.isChecked = !h.check.isChecked }
        Thumbs.load(s.image, h.cover)
    }
}
