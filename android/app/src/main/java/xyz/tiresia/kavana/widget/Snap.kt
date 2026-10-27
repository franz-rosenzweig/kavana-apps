package xyz.tiresia.kavana.widget

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import xyz.tiresia.kavana.Art
import xyz.tiresia.kavana.BuildConfig
import xyz.tiresia.kavana.KJson
import xyz.tiresia.kavana.UiState
import xyz.tiresia.kavana.titleOf
import java.io.File

/**
 * What a widget draws, as a few small numbers and words: the app saves one of these every time anything
 * changes, and every widget reads it, so drawing a widget never has to start the engine (the JavaScript
 * sandbox). Taps go through the engine as usual. Times are the clock's milliseconds; the widget works out
 * the rest (the kitten's stage, the ring) from `now` when it draws.
 */
@Serializable
data class Snap(
    val catsOn: Boolean = true,
    /** The cat who's coming (or growing): its id, name and paintings (a repainted cat's have a new number). */
    val cat: String = "miso",
    val catName: String = "Miso",
    val portrait: String = "cats/miso-1.webp",
    val kitten: String = "cats/miso-kitten-1.webp",
    val nap: String = "cats/miso-nap-1.webp",
    val theme: String = "classic",
    /** The block that's running (or paused), or null. */
    val block: SnapBlock? = null,
    /** A block that ended and is waiting for "How did it go?", or null. */
    val awaiting: SnapEnd? = null,
    /** A break's end, or null. */
    val breakEnd: Long? = null,
    val todayMin: Int = 0,
    val goalMin: Int = 0,
    val streak: Int = 0,
    val missionsDone: Int = 0,
    val missionsTotal: Int = 0,
    /** Rewards that are done and not collected yet (achievements, the starter challenge, the month): "Collect +20". */
    val collect: List<SnapReward> = emptyList(),
    val treats: Int = 0,
    val later: List<SnapLater> = emptyList(),
    /** The sounds in the mix (up to two), and whether the widget's pause has them muted. */
    val mix: List<SnapSound> = emptyList(),
    val muted: Boolean = false,
)

@Serializable
data class SnapBlock(
    val id: String,
    /** What it was called (may be empty: then the tag, or "Focus"). */
    val title: String = "",
    val start: Long,
    val end: Long,
    val total: Long,
    val paused: Long? = null,
    val pauseEnds: Long? = null,
    val stopwatch: Boolean = false,
    val over: Long? = null,
    val kitten: String = "growing",
    /** A check-in is waiting for an answer: its number. */
    val pending: Int? = null,
    val next: Long? = null,
    /** Debug builds only: the Widget lab's made-up blocks show a still clock, because a ticking one keeps a screen from settling. */
    val still: Boolean = false,
)

@Serializable
data class SnapEnd(val id: String, val title: String = "", val kitten: String, val minutes: Int, val treats: Int, val stopped: Boolean)

@Serializable
data class SnapLater(val id: String, val text: String)

@Serializable
data class SnapSound(val id: String, val name: String, val vol: Float)

@Serializable
data class SnapReward(val id: String, val reward: Int)

val Snap.collectTotal: Int get() = collect.sumOf { it.reward }

enum class WMode { READY, FOCUSING, CHECKIN, PAUSED, HOME, STOPPED, GONE, EXTRA, BREAK }

fun Snap.mode(): WMode = when {
    awaiting != null -> if (awaiting.kitten == "home") WMode.HOME else WMode.STOPPED
    block != null -> when {
        block.paused != null -> WMode.PAUSED
        block.pending != null -> WMode.CHECKIN
        block.over != null -> WMode.EXTRA
        catsOn && block.kitten == "gone" -> WMode.GONE
        else -> WMode.FOCUSING
    }
    breakEnd != null -> WMode.BREAK
    else -> WMode.READY
}

/** How far along a running block is, 0..1: a timer by its time, a stopwatch over 3 hours (as the sky's). */
fun SnapBlock.progress(now: Long): Float {
    val at = paused ?: now
    val spent = total - (end - at)
    return when {
        stopwatch -> minOf(1f, spent / (180 * 60_000f))
        over != null -> 1f
        else -> 1f - ((end - at).toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

/** How grown the kitten is, 0..1, the app's own (nap under a quarter, kitten under three quarters, then grown). */
fun SnapBlock.grow(now: Long): Float {
    val at = paused ?: now
    val spent = total - (end - at)
    return when {
        stopwatch -> when {
            spent < 600_000L -> 0.25f * spent / 600_000f
            spent < 2_400_000L -> 0.25f + 0.5f * (spent - 600_000L) / 1_800_000f
            else -> minOf(1f, 0.75f + 0.25f * (spent - 2_400_000L) / 1_200_000f)
        }
        over != null -> 1f
        else -> 1f - ((end - at).toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

/** "nap", "kitten" or "grown", from how grown the kitten is. */
fun stageOf(grow: Float) = if (grow < .25f) "nap" else if (grow < .75f) "kitten" else "grown"

/** Builds the snapshot from the app's state. */
fun snapOf(st: UiState): Snap {
    val c = st.cat(st.view.active?.cat ?: st.view.awaiting?.cat ?: st.companion)
    val a = st.view.active
    val w = st.view.awaiting
    val set = st.view.settings
    val miss = st.missions.day
    return Snap(
        catsOn = st.catsOn, cat = c.id, catName = c.name,
        portrait = Art.portrait(c.id), kitten = Art.kitten(c.id), nap = Art.nap(c.id),
        theme = set.theme,
        block = a?.let { SnapBlock(it.id, if (it.intention.isNotBlank() || it.tag != null) titleOf(it.intention, it.tag) else "", it.start, it.end, it.total, it.paused, it.pauseEnds, it.stopwatch, it.over, it.kitten, it.pending, it.next) },
        awaiting = w?.let { SnapEnd(it.id, if (it.intention.isNotBlank() || it.tag != null) titleOf(it.intention, it.tag) else "", it.kitten,
            (((it.ended ?: it.end) - it.start) / 60_000L).toInt().coerceAtLeast(1).coerceAtMost(if (it.stopwatch) 180 else maxOf(it.minutes, 1)), it.treats + it.bonus, it.stopped) },
        breakEnd = st.view.onBreak?.end,
        todayMin = st.stats.today, goalMin = set.goal, streak = st.stats.streak,
        missionsDone = miss.count { it.done }, missionsTotal = miss.size,
        collect = st.achievements.filter { it.done && !it.claimed }.map { SnapReward(it.id, it.reward) } +
            st.starter.steps.filter { it.done && !it.claimed }.map { SnapReward(it.id, it.reward) } +
            (if (st.starter.all.done && !st.starter.all.claimed) listOf(SnapReward(st.starter.all.id, st.starter.all.reward)) else emptyList()) +
            st.monthly.filter { it.done && !it.claimed }.map { SnapReward(it.id, it.reward) },
        treats = st.treats.left,
        later = st.view.later.take(4).map { SnapLater(it.id, it.text) },
    )
}

/** Where the snapshot is kept: one small file, and the last one in memory. */
object WidgetStore {
    @Volatile private var cur: Snap? = null
    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "widget.json"))

    fun get(ctx: Context): Snap = cur ?: runCatching {
        val f = file(ctx)
        if (f.baseFile.exists()) KJson.decodeFromString(Snap.serializer(), String(f.readFully())) else null
    }.getOrNull().also { cur = it ?: Snap() } ?: Snap()

    /** Keeps it; true if it's different from the one before. */
    fun put(ctx: Context, s: Snap): Boolean {
        if (get(ctx) == s) return false
        cur = s
        val f = file(ctx)
        val out = f.startWrite()
        try { out.write(KJson.encodeToString(Snap.serializer(), s).toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out) }
        return true
    }

    // Debug builds only: the Widget lab gives each widget it hosts its own made-up snapshot, to look at every state.
    private fun demoFile(ctx: Context) = File(ctx.filesDir, "widget-demo.json")
    private val DEMOS = MapSerializer(String.serializer(), Snap.serializer())
    fun demo(ctx: Context, id: Int): Snap? {
        if (!BuildConfig.DEBUG) return null
        return runCatching { KJson.decodeFromString(DEMOS, demoFile(ctx).readText())[id.toString()] }.getOrNull()
    }
    fun setDemo(ctx: Context, snaps: Map<Int, Snap>) {
        if (!BuildConfig.DEBUG) return
        demoFile(ctx).writeText(KJson.encodeToString(DEMOS, snaps.mapKeys { it.key.toString() }))
    }
}
