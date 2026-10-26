package xyz.tiresia.kavana

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One recording, decoded: interleaved 16-bit samples of exactly one seamless loop. */
class Loop(val pcm: ShortArray, val rate: Int, val channels: Int)

/**
 * The recorded background sounds: the web's web/audio/<file>.mp3 (CC0 from Freesound; docs/sounds.md
 * in the web repo has whose each one is). Like the web, the app downloads one the first time it's
 * picked, from the same server (<server>/audio/<file>.mp3, public files), and keeps it in its own
 * storage (files/audio), so the APK stays small and a sound played once plays offline after. A changed
 * recording gets a new number (crackle-2), so files no longer in Sound.KINDS are deleted. Each file is a
 * loop of a whole number of seconds with PAD seconds of its own end before it and of its own start after
 * it (web/sounds.js), so the stretch from PAD to PAD + the whole seconds is seamless whether or not the
 * decoder trims the encoder's padding. Brown and white noise have no recording (SoundLoops makes them).
 */
object Recordings {
    private const val PAD = 0.5
    private const val TAG = "Kavana"
    /** Decoded loops kept (a minute and a half of 48 kHz stereo is about 17 MB). */
    private const val KEEP = 3
    private val kept = LinkedHashMap<String, Loop>(4, 0.75f, true) // the most recently used last
    private var tidied = false

    /** Whether a sound has a recording (all but brown and white noise). */
    fun has(id: String) = Sound.byId(id)?.file != null

    private fun dir(ctx: Context) = File(ctx.filesDir, "audio").apply { mkdirs() }

    /**
     * The sound's file on the phone, downloaded now if it isn't there yet; null if it can't be (offline):
     * then the made-up one keeps playing, and the next pick tries again. Slow: not on the main thread.
     */
    @Synchronized
    private fun local(ctx: Context, file: String): File? {
        val dir = dir(ctx)
        if (!tidied) { // recordings that were replaced by a newer number, or dropped
            tidied = true
            val keep = Sound.KINDS.mapNotNull { it.file?.let { f -> "$f.mp3" } }.toSet()
            dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        }
        val f = File(dir, "$file.mp3")
        if (f.length() > 0) return f
        val url = KavanaApp.repo.sync.server + "audio/$file.mp3"
        val tmp = File(dir, "$file.mp3.part")
        val t0 = System.nanoTime()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            } finally {
                c.disconnect()
            }
            if (!tmp.renameTo(f)) throw IOException("can't keep $file")
            Log.i(TAG, "recording $file: downloaded ${f.length() / 1024} KB in ${(System.nanoTime() - t0) / 1_000_000} ms")
            f
        } catch (e: Exception) {
            Log.w(TAG, "recording $file: not downloaded (${e.message})")
            tmp.delete()
            null
        }
    }

    /** The loop for a sound if it's decoded already (then it can start at once). */
    fun ready(id: String): Loop? = synchronized(kept) { kept[id] }

    /** The loop for a sound, decoded once and kept; null if it has no recording or it won't decode. Slow: not on the main thread. */
    fun load(ctx: Context, id: String): Loop? {
        synchronized(kept) { kept[id]?.let { return it } }
        val name = Sound.byId(id)?.file ?: return null
        val f = local(ctx, name) ?: return null
        val t0 = System.nanoTime()
        val loop = runCatching { decode(f) }.onFailure { f.delete() /* a broken file: download it again next time */ }.onFailure { Log.w(TAG, "recording $name", it) }.getOrNull() ?: return null
        Log.i(TAG, "recording $name: ${loop.pcm.size / loop.channels / loop.rate} s, ${loop.channels} ch, ${loop.rate} Hz, decoded in ${(System.nanoTime() - t0) / 1_000_000} ms")
        synchronized(kept) {
            kept[id] = loop
            // Forget the ones used longest ago. One still playing keeps its own samples in its track.
            val it = kept.keys.iterator()
            while (kept.size > KEEP && it.hasNext()) { it.next(); it.remove() }
        }
        return loop
    }

    /** The whole file to 16-bit PCM with Android's own MP3 decoder, then the loop cut out of it. */
    private fun decode(f: File): Loop? {
        val ex = MediaExtractor()
        ex.setDataSource(f.path)
        try {
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val format = ex.getTrackFormat(track)
            ex.selectTrack(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var out = ShortArray(maxOf(1L, durUs * rate / 1_000_000 * channels + rate * channels).toInt())
            var n = 0
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!.asShortBuffer()
                        val count = info.size / 2
                        if (n + count > out.size) out = out.copyOf(maxOf(out.size * 3 / 2, n + count))
                        buf.get(out, n, count)
                        n += count
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            // The loop: from PAD, a whole number of seconds (web/sounds.js loopStart and loopEnd).
            val seconds = n.toDouble() / channels / rate
            if (seconds < 2 * PAD + 1) return null
            val from = (PAD * rate).roundToInt() * channels
            val len = ((seconds - 2 * PAD).roundToLong() * rate).toInt() * channels
            if (from + len > n) return null
            return Loop(out.copyOfRange(from, from + len), rate, channels)
        } finally {
            ex.release()
        }
    }
}
