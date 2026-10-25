package xyz.tiresia.kavana

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * A background sound. `about` is its line in the shop (the free ones have none); `like`: the sound made
 * on the phone that stands in until its recording is decoded (none for the music, which waits for it).
 */
data class SoundKind(val id: String, val name: String, val about: String?, val group: String, val free: Boolean = false, val like: String? = null)

data class SoundGroup(val id: String, val name: String)

/**
 * Kavana's background sounds, like the web's sounds.js: 39 in seven groups, one free in each, the
 * others in the shop ('sound-<id>'). Up to two play at once, each with its own volume. All but brown and
 * white noise are real recordings that come with the app (Recordings: the web's own files, nothing to
 * download), each decoded once into a seamless loop and fed to a streaming AudioTrack round and round.
 * Decoding takes a few seconds the first time, so meanwhile the sound it's `like`, made on the phone,
 * plays and the recording takes over with a 2 s equal-power crossfade, as on the web; the music has
 * nothing like it and starts once it's decoded. Brown and white noise, and any recording that won't
 * decode, are made on the phone (SoundLoops: one loop, made once and kept, played by an AudioTrack that
 * loops it inside the mixer, so there's no seam and no thread feeding it). A meow when a kitten comes home.
 */
object Sound {
    val GROUPS = listOf(
        SoundGroup("cats", "Cats"), SoundGroup("water", "Rain and water"), SoundGroup("fire", "Fire"), SoundGroup("outside", "Outside"),
        SoundGroup("places", "Places"), SoundGroup("music", "Music"), SoundGroup("noise", "Noise"),
    )

    val KINDS: List<SoundKind> = listOf(
        SoundKind("purr", "Purring", null, "cats", free = true),
        SoundKind("bigpurr", "Slow purr", "Long, even breaths.", "cats", like = "purr"),
        SoundKind("deeppurr", "Deep purr", "On the sofa, very deep.", "cats", like = "purr"),
        SoundKind("sleepypurr", "Sleepy purr", "Purring in its sleep.", "cats", like = "purr"),
        SoundKind("tomasina", "Tomasina", "Purring on the bed, nose to the mic.", "cats", like = "purr"),
        SoundKind("rain", "Rain", null, "water", free = true),
        SoundKind("lightrain", "Light rain", "Soft rain on open country.", "water", like = "rain"),
        SoundKind("heavyrain", "Heavy rain", "A downpour.", "water", like = "rain"),
        SoundKind("thunder", "Thunderstorm", "Rain and rolling thunder.", "water", like = "rain"),
        SoundKind("rainforest", "Rainforest", "Drops on ferns, and a shower now and then.", "water", like = "rain"),
        SoundKind("stream", "Stream", "A brook in the Scottish Borders.", "water", like = "rain"),
        SoundKind("waves", "Waves", "Slow surf on a beach.", "water"),
        SoundKind("bigwaves", "Big waves", "The Baltic hitting the shore.", "water", like = "waves"),
        SoundKind("baltic", "Baltic beach", "Small waves on a German beach.", "water", like = "waves"),
        SoundKind("ocean", "Ocean", "Breakers coming in.", "water", like = "waves"),
        SoundKind("sauna", "Sauna stove", "A wood stove, burning steady.", "fire", free = true, like = "fire"),
        SoundKind("fire", "Fireplace", "A low fire that crackles.", "fire"),
        SoundKind("crackle", "Crackling fire", "Lots of snaps and pops.", "fire", like = "fire"),
        SoundKind("hearth", "Hearth", "A fire in the fireplace.", "fire", like = "fire"),
        SoundKind("bigroom", "Fire in a big room", "Close up, and the room rings.", "fire", like = "fire"),
        SoundKind("springbirds", "Spring birds", "A warbler, a sparrow and geese.", "outside", free = true, like = "birds"),
        SoundKind("birds", "Birds", "Dawn in a pine forest.", "outside"),
        SoundKind("birdsong", "Birdsong", "A spring day in Virginia.", "outside", like = "birds"),
        SoundKind("night", "Night", "Crickets on a warm night.", "outside"),
        SoundKind("crickets", "Crickets", "Just the crickets.", "outside", like = "night"),
        SoundKind("farstorm", "Crickets and a far storm", "A summer garden, thunder far off.", "outside", like = "night"),
        SoundKind("wind", "Wind", "Wind around the house.", "outside"),
        SoundKind("hollowwind", "Hollow wind", "Empty and far away.", "outside", like = "wind"),
        SoundKind("cafeteria", "Campus cafeteria", "Lunch at a university in Istanbul.", "places", free = true, like = "cafe"),
        SoundKind("cafe", "Café", "Murmur and the odd cup.", "places"),
        SoundKind("copenhagen", "Copenhagen café", "Sitting outside, people chatting.", "places", like = "cafe"),
        SoundKind("city", "City at night", "A quiet street, the odd car.", "places", like = "wind"),
        SoundKind("wandering", "Wandering", "Slow, calm synths.", "music", free = true),
        SoundKind("dunes", "Dunes", "Spacey and wide.", "music"),
        SoundKind("angelic", "Angelic pad", "Soft chords that drift.", "music"),
        SoundKind("bowls", "Singing bowls", "Bowls, waves and a bird.", "music"),
        SoundKind("tibetan", "Tibetan bowl", "A bowl and a gong.", "music"),
        SoundKind("brown", "Brown noise", null, "noise", free = true),
        SoundKind("white", "White noise", null, "noise", free = true),
    )

    fun byId(id: String): SoundKind? = KINDS.find { it.id == id }

    private val MEOWS = listOf("meow-a", "meow-b", "meow-c")

    /** A meow, once, when a kitten comes home (one of three at random). Not when the phone is on silent or vibrate. */
    fun meow(ctx: Context) {
        val app = ctx.applicationContext
        if (app.getSystemService(AudioManager::class.java)?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val name = Recordings.file(app, MEOWS.random()) ?: return
        runCatching {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            app.assets.openFd(name).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setVolume(0.7f, 0.7f)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.prepareAsync()
        }.onFailure { Log.w(TAG, "meow", it) }
    }

    /**
     * Samples a second. The web takes the device's rate (44.1 or 48 kHz). Half that would save memory,
     * but the makers' filters work per sample, so each would sit an octave lower and the murmur, the
     * surf and the wind would fall mostly below what a phone's speaker plays.
     */
    const val RATE = 44100

    // The web's gain curve: volume × 0.8, a 2 s rise from silence, changes that glide (0.2 s time
    // constant), and a fall (0.25 s) that is cut after 1.2 s.
    private const val TOP = 0.8f
    private const val RISE = 2f
    private const val GLIDE = 0.2f
    private const val FALL = 0.25f
    private const val CUT = 1.2f
    private const val SWAP = 2f // the crossfade from the made-up version to the recording
    private const val TICK = 16L
    private const val TAG = "Kavana"

    // All the state below is touched on `one` only (one task at a time), so play, stop and the fades
    // never race, whichever thread called. The loops themselves are made on the rest of Default.
    private val one = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + one)
    private val cache = HashMap<String, Deferred<ShortArray?>>()
    private val wanted = ArrayList<Layer>()
    private val leaving = ArrayList<Layer>()
    private var ticker: Job? = null
    @Volatile private var app: Context? = null

    private val _playing = MutableStateFlow<List<String>>(emptyList())
    /** The kinds playing (or being made to play), in the order asked for. */
    val playing: StateFlow<List<String>> = _playing.asStateFlow()

    /**
     * Plays these sounds, (id, volume 0..1), at most two, and fades out anything else. One already
     * playing glides to its new volume. Safe to call from the main thread.
     */
    fun play(ctx: Context, layers: List<Pair<String, Float>>) {
        app = ctx.applicationContext
        val want = layers.filter { byId(it.first) != null }.take(2).map { (id, v) -> id to v.coerceIn(0f, 1f) }
        _playing.value = want.map { it.first }.distinct() // now, so a screen reading it right after sees it
        scope.launch { mix(want) }
    }

    /** Fades everything out, then lets the tracks go. */
    fun stop() {
        _playing.value = emptyList()
        scope.launch { mix(emptyList()) }
    }

    /** One playing sound's volume, 0..1. */
    fun setVolume(id: String, v: Float) {
        val vol = v.coerceIn(0f, 1f)
        scope.launch { wanted.find { it.id == id }?.let { aim(it, vol) } }
    }

    /** One sound playing: its track (null while the loop is being made), and where its gain is and is going. */
    private class Layer(val id: String, vol: Float) {
        var track: AudioTrack? = null
        /** A recording's feeder, which also lets its track go once `feeding` is off. */
        var feed: Job? = null
        @Volatile var feeding = false
        /** During the crossfade to the recording: the made-up version's track, fading out, and how far it is. */
        var old: AudioTrack? = null
        var swap = 1f
        var to = vol * TOP
        var gain = 0f
        var riseLeft = 0f
        var fallen = 0f
    }

    private fun mix(want: List<Pair<String, Float>>) {
        for (l in wanted.toList()) if (want.none { it.first == l.id }) { wanted.remove(l); fade(l) }
        for ((id, v) in want) {
            val l = wanted.find { it.id == id }
            if (l != null) aim(l, v) else Layer(id, v).also { wanted += it; start(it) }
        }
        changed()
    }

    private fun changed() { _playing.value = wanted.map { it.id } }

    // During the rise the rise ends at the new volume instead; after it, the gain glides there.
    private fun aim(l: Layer, vol: Float) {
        l.to = vol * TOP
        if (l.track != null) kick()
    }

    // One that never got a track (stopped while its loop was being made) has nothing to fade.
    private fun fade(l: Layer) {
        if (l.track == null) return
        l.fallen = 0f
        leaving += l
        kick()
    }

    // The one made on the phone for a sound: its own, or the one it's like; none for the music.
    private fun madeFor(id: String): String? = if (SoundLoops.knows(id)) id else byId(id)?.like

    private fun start(l: Layer) {
        val ctx = app
        if (ctx == null || Recordings.file(ctx, l.id) == null) { made(l); return }
        Recordings.ready(l.id)?.let { if (recorded(l, it)) return }
        if (madeFor(l.id) != null) made(l) // until the recording is decoded
        scope.launch {
            val loop = withContext(Dispatchers.Default) { Recordings.load(ctx, l.id) }
            if (l !in wanted) return@launch
            // It won't decode: the made one stays (with nothing like it, there's nothing to play).
            if (loop == null || !recorded(l, loop)) { if (l.track == null && madeFor(l.id) == null) { wanted.remove(l); changed() } }
        }
    }

    // The recording plays: from the start if nothing plays yet, else crossing over from the made-up one.
    private fun recorded(l: Layer, loop: Loop): Boolean {
        val t = stream(loop) ?: return false
        val was = l.track
        l.track = t
        feed(l, t, loop)
        if (was == null) l.riseLeft = RISE else { l.old = was; l.swap = 0f }
        kick()
        return true
    }

    private fun made(l: Layer) {
        val id = madeFor(l.id) ?: return
        val made = cache.getOrPut(id) {
            scope.async(Dispatchers.Default) {
                runCatching { pcm(SoundLoops.render(id, RATE)) }.onFailure { Log.w(TAG, "sound $id", it) }.getOrNull()
            }
        }
        scope.launch {
            val pcm = made.await()
            if (pcm == null && cache[id] === made) cache.remove(id) // try again next time
            if (l !in wanted || l.track != null) return@launch // stopped, replaced, or the recording came first
            val t = pcm?.let { track(it) }
            if (t == null) { if (l.feed == null) { wanted.remove(l); changed() }; return@launch }
            l.track = t
            l.riseLeft = RISE // the rise starts when the sound does, not when it was asked for
            kick()
        }
    }

    // A static track: the whole loop handed over once and looped by the mixer, sample for sample.
    // The longest loop (waves, 20 s) is 1.7 MB, so none needs streaming.
    private fun track(pcm: ShortArray): AudioTrack? {
        val b = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
        if (Build.VERSION.SDK_INT >= 34) app?.let { b.setContext(it) }
        val t = try { b.build() } catch (e: Exception) { Log.w(TAG, "sound track", e); return null }
        return try {
            check(t.write(pcm, 0, pcm.size) == pcm.size && t.setLoopPoints(0, pcm.size, -1) == AudioTrack.SUCCESS) { "loop not taken" }
            t.setVolume(0f)
            t.play()
            t
        } catch (e: Exception) {
            Log.w(TAG, "sound track", e)
            t.release()
            null
        }
    }

    // A streaming track for a recording, at the file's own rate (the mixer resamples), about half a second of buffer.
    private fun stream(loop: Loop): AudioTrack? {
        val mask = if (loop.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(loop.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val b = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(loop.rate).setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min * 2, loop.rate * loop.channels)) // half a second of 16-bit
        if (Build.VERSION.SDK_INT >= 34) app?.let { b.setContext(it) }
        val t = try { b.build() } catch (e: Exception) { Log.w(TAG, "sound track", e); return null }
        return try { t.setVolume(0f); t.play(); t } catch (e: Exception) { Log.w(TAG, "sound track", e); t.release(); null }
    }

    // Round and round the loop, never blocking (so it always sees `feeding` go off), then the track goes.
    private fun feed(l: Layer, t: AudioTrack, loop: Loop) {
        l.feeding = true
        l.feed = scope.launch(Dispatchers.IO) {
            val pcm = loop.pcm
            val chunk = loop.rate * loop.channels / 10
            var pos = 0
            try {
                while (l.feeding) {
                    val w = t.write(pcm, pos, min(chunk, pcm.size - pos), AudioTrack.WRITE_NON_BLOCKING)
                    if (w < 0) break
                    pos += w
                    if (pos >= pcm.size) pos = 0
                    if (w == 0) delay(20)
                }
            } catch (e: Exception) {
                Log.w(TAG, "sound feed", e)
            } finally {
                runCatching { t.stop() }
                t.release()
            }
        }
    }

    // A track that faded out: a recording's feeder lets its own go; a made one's goes now.
    private fun letGo(l: Layer, t: AudioTrack) {
        if (l.feed != null) l.feeding = false else { runCatching { t.stop() }; t.release() }
    }

    private fun kick() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var last = System.nanoTime()
            do {
                delay(TICK)
                val now = System.nanoTime()
                val more = tick((now - last) / 1e9f)
                last = now
            } while (more)
        }
    }

    // Moves every gain one step on, and lets go of the tracks that have faded; false once nothing moves.
    // The mixer smooths each setVolume over its own buffer, so steps this small don't zipper.
    private fun tick(dt: Float): Boolean {
        var moving = false
        for (l in wanted) {
            val t = l.track ?: continue
            if (l.riseLeft > 0f) { // linear, like the web's linearRampToValueAtTime
                l.gain += (l.to - l.gain) * min(1f, dt / l.riseLeft)
                l.riseLeft -= dt
            } else {
                l.gain += (l.to - l.gain) * (1f - exp(-dt / GLIDE))
                if (abs(l.to - l.gain) < 0.001f) l.gain = l.to
            }
            l.old?.let { o ->
                l.swap = min(1f, l.swap + dt / SWAP)
                if (l.swap >= 1f) { runCatching { o.stop() }; o.release(); l.old = null } else moving = true
            }
            voice(l, t)
            if (l.riseLeft > 0f || l.gain != l.to) moving = true
        }
        val it = leaving.iterator()
        while (it.hasNext()) {
            val l = it.next()
            val t = l.track
            if (t == null) { it.remove(); continue }
            l.fallen += dt
            when {
                l.fallen >= CUT + 0.05f -> {
                    letGo(l, t)
                    l.old?.let { o -> runCatching { o.stop() }; o.release(); l.old = null }
                    it.remove()
                }
                l.fallen >= CUT -> { l.gain = 0f; voice(l, t) } // silent a moment before the cut, so the cut can't click
                else -> { l.gain *= exp(-dt / FALL); voice(l, t) }
            }
        }
        return moving || leaving.isNotEmpty()
    }

    // A layer's gain on its track, shared out by the crossfade while the made-up version hands over.
    private fun voice(l: Layer, t: AudioTrack) {
        val o = l.old
        if (o == null) { t.setVolume(l.gain); return }
        val x = (l.swap * PI / 2).toFloat()
        t.setVolume(l.gain * sin(x))
        o.setVolume(l.gain * cos(x))
    }

    // tanh keeps every loop under 0.91, so 16 bits never clip.
    private fun pcm(d: FloatArray) = ShortArray(d.size) { (d[it] * 32767f).roundToInt().toShort() }
}

/**
 * The loops themselves: sounds.js MAKERS line for line, with the same generators, filters, envelopes,
 * constants and lengths, so the phone sounds like the web. No Android in here, so they can be tested
 * on the JVM. Every call makes a new loop, as the web does; pass a seeded Random to get the same one.
 * Like the web's Float32Array, each sample is worked out in Double and kept as a Float.
 */
object SoundLoops {
    /** Seconds in each loop (sounds.js SECONDS). */
    val SECONDS = mapOf(
        "purr" to 6.0, "waves" to 20.0, "cafe" to 16.0, "fire" to 12.0, "birds" to 16.0,
        "night" to 8.0, "wind" to 16.0, "rain" to 6.0, "brown" to 6.0, "white" to 6.0,
    )
    /** How loud each one sits (RMS), so switching between them doesn't jump (sounds.js LEVEL). */
    val LEVEL = mapOf(
        "purr" to 0.11, "rain" to 0.1, "brown" to 0.14, "white" to 0.08, "cafe" to 0.11,
        "fire" to 0.12, "waves" to 0.12, "birds" to 0.08, "night" to 0.09, "wind" to 0.11,
    )

    fun knows(id: String) = id in SECONDS

    /** One loop of `id` at `sampleRate`, ready to play: the seam closed and the level set (sounds.js buffer()). */
    fun render(id: String, sampleRate: Int, random: Random = Random.Default): FloatArray {
        val sr = sampleRate.toDouble()
        val n = floor(sr * (SECONDS[id] ?: 8.0)).toInt()
        val d = FloatArray(n)
        make(id, d, sr, random)
        // The drift fix: tilt the whole loop so the last sample meets the first.
        val drift = d[n - 1].toDouble() - d[0]
        var sq = 0.0
        for (i in 0 until n) { d[i] = (d[i] - drift * i / n).toFloat(); sq += d[i].toDouble() * d[i] }
        val g = (LEVEL[id] ?: 0.1) / max(1e-6, sqrt(sq / n))
        for (i in 0 until n) d[i] = (tanh(d[i] * g * 1.1) / 1.1).toFloat() // soft, so a loud crackle doesn't clip
        return d
    }

    private fun make(id: String, d: FloatArray, sr: Double, r: Random) {
        when (id) {
            "purr" -> purr(d, sr, r)
            "white" -> for (i in d.indices) d[i] = (rnd(r) * 0.18).toFloat()
            "brown" -> { val g = Brown(r); for (i in d.indices) d[i] = g.next().toFloat() }
            "cafe" -> cafe(d, sr, r)
            "fire" -> fire(d, sr, r)
            "waves" -> waves(d, r)
            "birds" -> birds(d, sr, r)
            "night" -> night(d, sr, r)
            "wind" -> wind(d, r)
            else -> { val g = Pink(r); for (i in d.indices) d[i] = (g.next() * 0.45).toFloat() } // rain, and the web's fallback
        }
    }

    private fun rnd(r: Random) = r.nextDouble() * 2 - 1

    // Math.round: halves go up.
    private fun jsRound(x: Double) = floor(x + 0.5).toInt()

    // Adds to a sample the way the web's typed array does: past either end, nothing happens.
    private fun add(d: FloatArray, i: Int, x: Double) { if (i in d.indices) d[i] = (d[i] + x).toFloat() }

    // Pink noise (Paul Kellet's filter): sounds like steady rain.
    private class Pink(private val r: Random) {
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var b3 = 0.0
        private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0
        fun next(): Double {
            val w = rnd(r)
            b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.969 * b2 + w * 0.153852
            b3 = 0.8665 * b3 + w * 0.3104856; b4 = 0.55 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.016898
            val out = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11; b6 = w * 0.115926
            return out
        }
    }

    private class Brown(private val r: Random) {
        private var last = 0.0
        fun next(): Double { last = (last + 0.02 * rnd(r)) / 1.02; return last * 3.2 }
    }

    // A one-pole low-pass: smaller k = darker.
    private class Lowpass(private val k: Double) {
        private var y = 0.0
        fun step(x: Double): Double { y += k * (x - y); return y }
    }

    // Rumbly noise in fast pulses, softer breathing in, stronger out; two 3-second breaths.
    private fun purr(d: FloatArray, sr: Double, r: Random) {
        var ph = 0.0; var lp = 0.0; var last = 0.0
        for (i in d.indices) {
            val c = (i / sr) % 3
            val env = if (c < 1.3) 0.55 * sin(PI * c / 1.3) else if (c > 1.45 && c < 2.85) sin(PI * (c - 1.45) / 1.4) else 0.0
            ph += 2 * PI * (if (c < 1.4) 22 else 26) / sr
            val pulse = (0.5 + 0.5 * cos(ph)).pow(6)
            last = (last + 0.02 * rnd(r)) / 1.02
            lp += 0.14 * ((last * 3.4 + rnd(r) * 0.06) * pulse * env - lp)
            d[i] = (lp * 2.6).toFloat()
        }
    }

    // A room full of quiet talk (band-limited noise that swells), and now and then a cup.
    private fun cafe(d: FloatArray, sr: Double, r: Random) {
        val g = Pink(r); val lo = Lowpass(0.08); val hi = Lowpass(0.01)
        val n = d.size
        for (i in 0 until n) {
            val t = i / sr
            val swell = 0.75 + 0.25 * sin(2 * PI * t / (n / sr)) * sin(2 * PI * t * 0.37)
            val x = g.next(); val band = lo.step(x) - hi.step(x)
            d[i] = (band * 2.2 * swell).toFloat()
        }
        for (k in 0 until 7) { // clinks: short bright tones with a fast fade
            val at = floor((0.05 + 0.9 * r.nextDouble()) * (n - sr)).toInt()
            val f = 1800 + r.nextDouble() * 2200; val amp = 0.05 + r.nextDouble() * 0.05
            var j = 0
            while (j < sr * 0.5) { add(d, at + j, amp * sin(2 * PI * f * j / sr) * exp(-j / (sr * 0.06))); j++ }
        }
    }

    // A low roar, and crackles: tiny bursts of noise.
    private fun fire(d: FloatArray, sr: Double, r: Random) {
        val g = Brown(r); val lo = Lowpass(0.05)
        val n = d.size
        for (i in 0 until n) d[i] = (lo.step(g.next()) * 2.4).toFloat()
        val pops = jsRound(n / sr * 9)
        for (k in 0 until pops) {
            val at = floor(r.nextDouble() * (n - sr * 0.05)).toInt()
            val amp = 0.08 + r.nextDouble().pow(3) * 0.5; val len = sr * (0.002 + r.nextDouble() * 0.01)
            var j = 0
            while (j < len) { add(d, at + j, amp * rnd(r) * exp(-j / (len / 3))); j++ }
        }
    }

    // Surf: noise that rises and falls, two waves per loop.
    private fun waves(d: FloatArray, r: Random) {
        val g = Pink(r); val lo = Lowpass(0.12)
        val n = d.size
        for (i in 0 until n) {
            val c = (i.toDouble() / n * 2) % 1 // 0..1 within a wave
            val env = 0.15 + 0.85 * sin(PI * min(1.0, c * 1.6)).pow(2) * (if (c < 0.62) 1.0 else max(0.0, 1 - (c - 0.62) / 0.38))
            d[i] = (lo.step(g.next()) * 1.9 * env).toFloat()
        }
    }

    // A very soft garden hush, and chirps: quick falling and rising whistles.
    private fun birds(d: FloatArray, sr: Double, r: Random) {
        val g = Pink(r); val lo = Lowpass(0.04)
        val n = d.size
        for (i in 0 until n) d[i] = (lo.step(g.next()) * 0.35).toFloat()
        val songs = jsRound(n / sr / 1.6)
        for (k in 0 until songs) {
            var at = floor(r.nextDouble() * (n - sr * 1.2)).toInt()
            val notes = 2 + floor(r.nextDouble() * 5).toInt(); val base = 2600 + r.nextDouble() * 2400; val amp = 0.05 + r.nextDouble() * 0.06
            for (m in 0 until notes) {
                val len = floor(sr * (0.05 + r.nextDouble() * 0.08)).toInt(); val down = r.nextDouble() < 0.6
                var ph = 0.0
                var j = 0
                while (j < len && at + j < n) {
                    val p = j.toDouble() / len
                    val f = base * (if (down) 1.25 - 0.45 * p else 0.85 + 0.4 * p)
                    ph += 2 * PI * f / sr
                    add(d, at + j, amp * sin(ph) * sin(PI * p))
                    j++
                }
                at += len + floor(sr * (0.03 + r.nextDouble() * 0.06)).toInt()
            }
        }
    }

    // Each cricket: pitch, chirps a second, loudness, where in its cycle it starts.
    private val CRICKETS = listOf(doubleArrayOf(4400.0, 2.1, 0.035, 0.0), doubleArrayOf(4900.0, 1.7, 0.025, 0.31), doubleArrayOf(4100.0, 2.6, 0.02, 0.62))

    // Crickets: trains of short high pulses over a low hum of night air.
    private fun night(d: FloatArray, sr: Double, r: Random) {
        val g = Brown(r); val lo = Lowpass(0.03)
        val n = d.size
        for (i in 0 until n) d[i] = (lo.step(g.next()) * 0.9).toFloat()
        for ((f, rate, amp, off) in CRICKETS) {
            for (i in 0 until n) {
                val t = i / sr + off
                val chirp = (t * rate) % 1 // a chirp of 4 pulses at the start of each cycle
                if (chirp > 0.22) continue
                val pulse = max(0.0, sin(PI * ((chirp / 0.22) * 4 % 1)))
                d[i] = (d[i] + amp * pulse * sin(2 * PI * f * i / sr)).toFloat()
            }
        }
    }

    // Noise through a filter that slowly opens and closes, and a slow swell.
    private fun wind(d: FloatArray, r: Random) {
        val g = Pink(r)
        val n = d.size
        var y = 0.0
        for (i in 0 until n) {
            val c = i.toDouble() / n
            val k = 0.01 + 0.05 * (0.5 + 0.5 * sin(2 * PI * c * 2 + sin(2 * PI * c * 3)))
            y += k * (g.next() - y)
            d[i] = (y * (3.2 + 1.6 * sin(2 * PI * c * 2))).toFloat()
        }
    }
}
