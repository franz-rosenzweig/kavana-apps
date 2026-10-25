package xyz.tiresia.kavana

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One recording, decoded: interleaved 16-bit samples of exactly one seamless loop. */
class Loop(val pcm: ShortArray, val rate: Int, val channels: Int)

/**
 * The recorded background sounds: the web's web/audio/<id>-<n>.mp3 (CC0 from Freesound; docs/sounds.md
 * in the web repo has whose each one is), copied into the app's assets by tools/sync-web.sh, so they
 * play offline and nothing is downloaded. Each file is a loop of a whole number of seconds with PAD
 * seconds of its own end before it and of its own start after it (web/sounds.js), so the stretch from
 * PAD to PAD + the whole seconds is seamless whether or not the decoder trims the encoder's padding.
 * Brown and white noise have no recording; they're only made on the phone (SoundLoops).
 */
object Recordings {
    private const val PAD = 0.5
    private const val TAG = "Kavana"
    /** Decoded loops kept (a minute and a half of 48 kHz stereo is about 17 MB). */
    private const val KEEP = 3
    private val kept = LinkedHashMap<String, Loop>(4, 0.75f, true) // the most recently used last
    @Volatile private var files: Map<String, String>? = null

    /** The asset for a sound, "audio/rain-1.mp3", or null if it has none (the newest number wins). */
    fun file(ctx: Context, id: String): String? {
        val all = files ?: (ctx.assets.list("audio") ?: emptyArray()).filter { it.endsWith(".mp3") }
            .groupBy { it.substringBeforeLast('-') }
            .mapValues { (_, names) -> "audio/" + names.maxBy { it.substringAfterLast('-').substringBefore('.').toIntOrNull() ?: 0 } }
            .also { files = it }
        return all[id]
    }

    /** The loop for a sound if it's decoded already (then it can start at once). */
    fun ready(id: String): Loop? = synchronized(kept) { kept[id] }

    /** The loop for a sound, decoded once and kept; null if it has no recording or it won't decode. Slow: not on the main thread. */
    fun load(ctx: Context, id: String): Loop? {
        synchronized(kept) { kept[id]?.let { return it } }
        val name = file(ctx, id) ?: return null
        val t0 = System.nanoTime()
        val loop = runCatching { decode(ctx, name) }.onFailure { Log.w(TAG, "recording $name", it) }.getOrNull() ?: return null
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
    private fun decode(ctx: Context, name: String): Loop? {
        val ex = MediaExtractor()
        val afd = ctx.assets.openFd(name) // MP3s are stored uncompressed in the APK, so this works
        try {
            ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        } finally {
            afd.close()
        }
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
