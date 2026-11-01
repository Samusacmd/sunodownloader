package it.sam.sunodownloader

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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

/** Riceve l'audio decodificato (PCM 16 bit, interleaved) e lo scrive nel formato finale. */
interface PcmSink {
    fun write(pcm: ShortArray, frames: Int, rate: Int, channels: Int)
    fun finish()
    fun abort()
}

/** MP3 con LAME. */
class LameSink(dst: File, private val bitrate: Int) : PcmSink {
    private val out = FileOutputStream(dst)
    private var lame = 0L
    private var buf = ByteArray(16384)

    override fun write(pcm: ShortArray, frames: Int, rate: Int, channels: Int) {
        if (lame == 0L) {
            lame = LameNative.create(rate, channels, bitrate, 2)
            if (lame == 0L) throw RuntimeException("LAME non si avvia ($rate Hz, $channels canali)")
        }
        val need = (frames * 1.25).toInt() + 7200
        if (buf.size < need) buf = ByteArray(need)
        if (frames > 0) {
            val n = LameNative.encode(lame, pcm, frames, buf)
            if (n < 0) throw RuntimeException("Errore LAME $n")
            out.write(buf, 0, n)
        }
    }

    override fun finish() {
        if (lame != 0L) {
            val n = LameNative.flush(lame, buf)
            if (n > 0) out.write(buf, 0, n)
        }
        abort()
    }

    override fun abort() {
        try { out.close() } catch (e: Exception) {}
        if (lame != 0L) { LameNative.close(lame); lame = 0L }
    }
}

/** WAV PCM 16 bit, con titolo/artista (LIST INFO) e tag ID3 con cover (chunk "id3 "). */
class WavSink(dst: File, private val info: Map<String, String>, private val id3: ByteArray?) : PcmSink {
    private val raf = RandomAccessFile(dst, "rw")
    private var rate = 0
    private var ch = 0
    private var dataBytes = 0L
    private var bytes = ByteArray(0)

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))   // intestazione, completata alla fine
    }

    override fun write(pcm: ShortArray, frames: Int, rate: Int, channels: Int) {
        if (this.rate == 0) { this.rate = rate; this.ch = channels }
        val n = frames * channels
        if (bytes.size < n * 2) bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        raf.write(bytes, 0, n * 2)
        dataBytes += n * 2
    }

    private fun chunk(id: String, body: ByteArray) {
        raf.write(id.toByteArray(Charsets.US_ASCII))
        raf.write(le32(body.size))
        raf.write(body)
        if (body.size % 2 == 1) raf.write(0)
    }

    override fun finish() {
        if (rate == 0) throw RuntimeException("Nessun audio decodificato")
        if (dataBytes % 2 == 1L) raf.write(0)
        // LIST/INFO: titolo, artista, software
        val list = ByteArrayOutputStream()
        list.write("INFO".toByteArray(Charsets.US_ASCII))
        for ((k, v) in info) {
            if (v.isEmpty()) continue
            val b = v.toByteArray(Charsets.UTF_8) + 0.toByte()
            list.write(k.toByteArray(Charsets.US_ASCII)); list.write(le32(b.size)); list.write(b)
            if (b.size % 2 == 1) list.write(0)
        }
        chunk("LIST", list.toByteArray())
        if (id3 != null) chunk("id3 ", id3)
        val total = raf.length()
        raf.seek(0)
        val h = ByteArrayOutputStream()
        h.write("RIFF".toByteArray(Charsets.US_ASCII)); h.write(le32((total - 8).toInt()))
        h.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); h.write(le32(16))
        h.write(le16(1)); h.write(le16(ch)); h.write(le32(rate)); h.write(le32(rate * ch * 2))
        h.write(le16(ch * 2)); h.write(le16(16))
        h.write("data".toByteArray(Charsets.US_ASCII)); h.write(le32(dataBytes.toInt()))
        raf.write(h.toByteArray())
        raf.close()
    }

    override fun abort() {
        try { raf.close() } catch (e: Exception) {}
    }

    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
    private fun le16(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte())
}

/** M4A/OGG/WEBM/MP3 -> PCM con MediaCodec (gia' in Android), poi MP3 (LAME) o WAV. */
object Mp3Converter {
    val BITRATES = listOf(128, 160, 192, 256, 320)
    val FORMATS = listOf("MP3", "WAV")

    fun convert(src: File, dst: File, bitrate: Int, ctl: JobCtl, onFrac: (Float) -> Unit) =
        decode(src, dst, LameSink(dst, bitrate), ctl, onFrac)

    fun toWav(src: File, dst: File, title: String, artist: String, id3: ByteArray?, ctl: JobCtl,
              onFrac: (Float) -> Unit) =
        decode(src, dst, WavSink(dst, linkedMapOf("INAM" to title, "IART" to artist, "ISFT" to "Suno Downloader"), id3),
            ctl, onFrac)

    private fun decode(src: File, dst: File, sink: PcmSink, ctl: JobCtl, onFrac: (Float) -> Unit) {
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
            sink.abort()
            dst.delete()
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
        var shorts = ShortArray(0)
        val info = MediaCodec.BufferInfo()
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
                        sink.write(shorts, frames, rate, outCh)
                        if (durUs > 0) onFrac((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                }
            }
            sink.finish()
        } catch (e: Exception) {
            sink.abort()
            dst.delete()
            throw e
        } finally {
            try { codec.stop() } catch (e: Exception) {}
            codec.release()
            ex.release()
        }
        if (dst.length() < 1000) {
            dst.delete()
            throw RuntimeException("Conversione fallita: file vuoto")
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
