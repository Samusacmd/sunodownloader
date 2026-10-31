package it.sam.sunodownloader

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Salvataggio in Music/Suno tramite MediaStore (Android 10+, nessun permesso necessario). */
object Saver {
    /** Scrive tag ID3 + audio MP3 (saltando `skip` byte iniziali del file sorgente). Restituisce il percorso visibile. */
    fun saveMp3(ctx: Context, baseName: String, subFolder: String?, title: String, artist: String,
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
            resolver.openOutputStream(uri)!!.use { os ->
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
