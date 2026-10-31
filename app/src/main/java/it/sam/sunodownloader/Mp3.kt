package it.sam.sunodownloader

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/** Encoder LAME 3.100 compilato dai sorgenti (libsunolame.so). */
object LameNative {
    init {
        System.loadLibrary("sunolame")
    }

    @JvmStatic external fun create(inRate: Int, channels: Int, bitrate: Int, quality: Int): Long
    @JvmStatic external fun encode(handle: Long, pcm: ShortArray, samples: Int, out: ByteArray): Int
    @JvmStatic external fun flush(handle: Long, out: ByteArray): Int
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun version(): String
}

/** M4A/OGG/WEBM -> MP3: decodifica con MediaCodec (gia' in Android), codifica con LAME. */
object Mp3Converter {
    val BITRATES = listOf(128, 160, 192, 256, 320)

    fun convert(src: File, dst: File, bitrate: Int, ctl: JobCtl, onFrac: (Float) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i; fmt = f; break
            }
        }
        if (track < 0 || fmt == null) {
            ex.release()
            throw RuntimeException("Nessuna traccia audio nel file catturato")
        }
        ex.selectTrack(track)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        var lame = 0L
        var shorts = ShortArray(0)
        var mp3buf = ByteArray(16384)
        val info = MediaCodec.BufferInfo()
        val out = FileOutputStream(dst)
        var inEos = false
        var outEos = false
        try {
            while (!outEos) {
                ctl.checkBlocking()
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(oi)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        val outCh = if (ch >= 2) 2 else 1
                        val frames: Int
                        if (pcmFloat) {
                            val fb = buf.asFloatBuffer()
                            frames = fb.remaining() / ch
                            if (shorts.size < frames * outCh) shorts = ShortArray(frames * outCh)
                            for (f in 0 until frames) for (c in 0 until outCh) {
                                val v = fb.get(f * ch + c).coerceIn(-1f, 1f)
                                shorts[f * outCh + c] = (v * 32767f).toInt().toShort()
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            frames = sb.remaining() / ch
                            if (shorts.size < frames * outCh) shorts = ShortArray(frames * outCh)
                            if (ch == outCh) {
                                sb.get(shorts, 0, frames * ch)
                            } else {   // piu' di 2 canali: tengo i primi due
                                for (f in 0 until frames) for (c in 0 until outCh) shorts[f * outCh + c] = sb.get(f * ch + c)
                            }
                        }
                        if (lame == 0L) {
                            lame = LameNative.create(rate, outCh, bitrate, 2)
                            if (lame == 0L) throw RuntimeException("LAME non si avvia ($rate Hz, $outCh canali)")
                        }
                        val need = (frames * 1.25).toInt() + 7200
                        if (mp3buf.size < need) mp3buf = ByteArray(need)
                        if (frames > 0) {
                            val n = LameNative.encode(lame, shorts, frames, mp3buf)
                            if (n < 0) throw RuntimeException("Errore LAME $n")
                            out.write(mp3buf, 0, n)
                        }
                        if (durUs > 0) onFrac((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                }
            }
            if (lame != 0L) {
                val n = LameNative.flush(lame, mp3buf)
                if (n > 0) out.write(mp3buf, 0, n)
            }
        } catch (e: Exception) {
            out.close()
            dst.delete()
            throw e
        } finally {
            try { out.close() } catch (e: Exception) {}
            try { codec.stop() } catch (e: Exception) {}
            codec.release()
            ex.release()
            if (lame != 0L) LameNative.close(lame)
        }
        if (dst.length() < 1000) {
            dst.delete()
            throw RuntimeException("Conversione MP3 fallita: file vuoto")
        }
        onFrac(1f)
    }
}

/** Tag ID3v2.3 con titolo, artista e cover (come tag_mp3 desktop). */
object Id3 {
    private fun int32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun syncsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())

    fun build(title: String, artist: String, cover: ByteArray?): ByteArray {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, body: ByteArray) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(int32(body.size))
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        fun text(s: String): ByteArray {   // codifica 1 = UTF-16 con BOM (ammessa in v2.3)
            val b = ByteArrayOutputStream()
            b.write(1)
            b.write(s.toByteArray(Charsets.UTF_16))
            return b.toByteArray()
        }
        frame("TIT2", text(title))
        if (artist.isNotEmpty()) frame("TPE1", text(artist))
        if (cover != null) {
            val b = ByteArrayOutputStream()
            b.write(0)                                            // ISO-8859-1
            b.write("image/jpeg".toByteArray(Charsets.ISO_8859_1)); b.write(0)
            b.write(3)                                            // copertina frontale
            b.write("Cover".toByteArray(Charsets.ISO_8859_1)); b.write(0)
            b.write(cover)
            frame("APIC", b.toByteArray())
        }
        val body = frames.toByteArray()
        val head = ByteArrayOutputStream()
        head.write("ID3".toByteArray(Charsets.ISO_8859_1))
        head.write(byteArrayOf(3, 0, 0))
        head.write(syncsafe(body.size))
        head.write(body)
        return head.toByteArray()
    }

    /** Lunghezza dei tag ID3v2 gia' presenti in testa a un MP3 (da saltare). */
    fun existingLength(b: ByteArray): Int {
        var off = 0
        while (b.size >= off + 10 && b[off] == 'I'.code.toByte() && b[off + 1] == 'D'.code.toByte() &&
            b[off + 2] == '3'.code.toByte()) {
            val size = ((b[off + 6].toInt() and 0x7F) shl 21) or ((b[off + 7].toInt() and 0x7F) shl 14) or
                ((b[off + 8].toInt() and 0x7F) shl 7) or (b[off + 9].toInt() and 0x7F)
            val footer = if ((b[off + 5].toInt() and 0x10) != 0) 10 else 0
            off += 10 + size + footer
        }
        return off.coerceAtMost(b.size)
    }
}
