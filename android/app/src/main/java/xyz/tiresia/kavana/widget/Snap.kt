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
import xyz.tiresia.kavana.fmtMin
import xyz.tiresia.kavana.titleOf
import java.io.File
import java.util.Calendar

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
    /** The first mission of the day that isn't done, with how far along it is ("Bring 3 kittens home: 2 of 3"), or empty. */
    val missionNext: String = "",
    /** Boo, who only comes out late, has been met: only then do the widgets show Boo at night. */
    val boo: Boolean = false,
    val booArt: String = "cats/boo-1.webp",
    val booNap: String = "cats/boo-nap-1.webp",
    /** This month's blocks that came home: a star each in the constellation (the last 200). */
    val stars: List<SnapStar> = emptyList(),
    /** The last fourteen days, oldest first, today last. */
    val days: List<SnapDay> = emptyList(),
    val todayHome: Int = 0,
    val todayTreats: Int = 0,
    val monthHome: Int = 0,
    val monthMin: Int = 0,
    val monthGone: Int = 0,
    val monthTreats: Int = 0,
    /** "October". */
    val month: String = "",
    /** What the same cat looks like in its loaf (purring), or null if it has no such painting. */
    val loaf: String? = null,
    val cafeSeed: Long = 0,
    /** Debug builds only: the Widget lab sets a clock and a pose, to look at night and at a purr. */
    val demo: SnapDemo? = null,
    /** The friends' table's list (signed in, sharing, who's doing what), or null to use the saved one. The Widget lab brings its own. */
    val friends: FriendsSnap? = null,
)

@Serializable
data class SnapDemo(val at: Long = 0, val pet: Int = 0)

/** A block that came home: a star. */
@Serializable
data class SnapStar(val t: Long, val min: Int)

/** One day: its minutes, the painting of the cat whose block was longest, and the hour that block started (-1 if none). */
@Serializable
data class SnapDay(val day: Long, val min: Int, val art: String = "", val hour: Int = -1)

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
        missionNext = miss.firstOrNull { !it.done }?.let { m -> if (m.need > 1) "${m.text}: ${if (m.unit == "min") "${fmtMin(m.have)} of ${fmtMin(m.need)}" else "${m.have} of ${m.need}"}" else m.text } ?: "",
        boo = st.cats.firstOrNull { it.id == "boo" }?.met == true,
        booArt = Art.portrait("boo"), booNap = Art.nap("boo"),
        loaf = c.poses?.get("loaf"),
    ).withHistory(st)
}

/** The numbers the playful widgets draw from the history: this month's stars, the last fourteen days, today's and the month's counts. */
private fun Snap.withHistory(st: UiState): Snap {
    val cal = Calendar.getInstance().apply { timeInMillis = st.now }
    fun midnight(daysAgo: Int): Long = (cal.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val monthStart = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val today0 = midnight(0)
    val home = st.history.filter { it.kitten == "home" }
    val inMonth = home.filter { it.start >= monthStart }
    val days = (13 downTo 0).map { k ->
        val d0 = midnight(k); val d1 = if (k == 0) Long.MAX_VALUE else midnight(k - 1)
        val of = home.filter { it.start in d0 until d1 }
        val top = of.maxByOrNull { it.actual }
        SnapDay(d0, of.sumOf { it.actual }, top?.let { Art.portrait(it.cat) } ?: "", top?.let { Calendar.getInstance().apply { timeInMillis = it.start }.get(Calendar.HOUR_OF_DAY) } ?: -1)
    }
    return copy(
        stars = inMonth.takeLast(200).map { SnapStar(it.start, it.actual) },
        days = days,
        todayHome = home.count { it.start >= today0 }, todayTreats = home.filter { it.start >= today0 }.sumOf { it.treats },
        monthHome = inMonth.size, monthMin = inMonth.sumOf { it.actual },
        monthGone = st.history.count { it.kitten == "gone" && it.start >= monthStart },
        monthTreats = inMonth.sumOf { it.treats },
        month = java.text.SimpleDateFormat("MMMM", java.util.Locale.getDefault()).format(java.util.Date(st.now)),
        cafeSeed = monthStart / 86_400_000L,
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

    /**
     * What a widget draws on this tick: the lab's made-up snapshot or the real one, as a new object each time. Compose skips redrawing
     * a composable whose parameter is the very same object as last time, and a widget that goes by the clock or by a file needs to draw again.
     */
    fun fresh(ctx: Context, id: Int): Snap = (demo(ctx, id) ?: get(ctx)).copy()

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
