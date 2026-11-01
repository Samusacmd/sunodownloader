package it.sam.sunodownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** Salvataggio: Music/Suno tramite MediaStore (predefinito) oppure nella cartella scelta dall'utente. */
object Saver {
    const val DEFAULT_LABEL = "Music/Suno"

    /** Nome leggibile di una cartella scelta, es. "Download/Suno". */
    fun label(tree: Uri?): String {
        if (tree == null) return DEFAULT_LABEL
        return try {
            val id = DocumentsContract.getTreeDocumentId(tree)
            val path = id.substringAfter(':', "")
            when {
                id.startsWith("primary:") -> path.ifEmpty { "Memoria interna" }
                else -> "SD/" + path
            }
        } catch (e: Exception) {
            tree.toString()
        }
    }

    fun save(ctx: Context, tree: Uri?, baseName: String, subFolder: String?, title: String, artist: String,
             tag: ByteArray, mp3: File, skip: Long): String {
        if (tree == null) return saveMp3(ctx, baseName, subFolder, title, artist, tag, mp3, skip)
        val root = DocumentFile.fromTreeUri(ctx, tree)
        if (root == null || !root.canWrite()) throw RuntimeException("Cartella non accessibile: sceglila di nuovo con «Cambia»")
        var dir: DocumentFile = root
        if (subFolder != null) {
            dir = root.findFile(subFolder)?.takeIf { it.isDirectory } ?: root.createDirectory(subFolder)
                ?: throw RuntimeException("Impossibile creare la cartella $subFolder")
        }
        val f = dir.createFile("audio/mpeg", "$baseName.mp3")
            ?: throw RuntimeException("Impossibile creare il file in ${label(tree)}")
        try {
            ctx.contentResolver.openOutputStream(f.uri)!!.use { os -> writeBody(os, tag, mp3, skip) }
        } catch (e: Exception) {
            f.delete()
            throw e
        }
        val shown = label(tree) + (if (subFolder != null) "/$subFolder" else "") + "/" + (f.name ?: "$baseName.mp3")
        // fa comparire subito il brano nei lettori musicali
        try {
            val id = DocumentsContract.getTreeDocumentId(tree)
            if (id.startsWith("primary:")) {
                val abs = Environment.getExternalStorageDirectory().absolutePath + "/" + shown.removePrefix("Memoria interna/")
                MediaScannerConnection.scanFile(ctx, arrayOf(abs), arrayOf("audio/mpeg"), null)
            }
        } catch (e: Exception) {
        }
        return shown
    }

    private fun writeBody(os: java.io.OutputStream, tag: ByteArray, mp3: File, skip: Long) {
        os.write(tag)
        mp3.inputStream().use { input ->
            var left = skip
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) break
                left -= s
            }
            input.copyTo(os, 64 * 1024)
        }
    }

    /** Scrive tag ID3 + audio MP3 (saltando `skip` byte iniziali del file sorgente). Restituisce il percorso visibile. */
    private fun saveMp3(ctx: Context, baseName: String, subFolder: String?, title: String, artist: String,
                tag: ByteArray, mp3: File, skip: Long): String {
        val rel = Environment.DIRECTORY_MUSIC + "/Suno" + (if (subFolder != null) "/$subFolder" else "")
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$baseName.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, rel)
            put(MediaStore.Audio.Media.TITLE, title)
            if (artist.isNotEmpty()) put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(coll, values) ?: throw RuntimeException("Impossibile creare il file in $rel")
        try {
            resolver.openOutputStream(uri)!!.use { os -> writeBody(os, tag, mp3, skip) }
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // nome finale (Android aggiunge " (1)" se esiste gia')
        var name = "$baseName.mp3"
        resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        return "$rel/$name"
    }
}
