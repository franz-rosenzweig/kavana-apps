package xyz.tiresia.kavana

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// What bridge.js hands back (KB.run). The shapes are engine.js's own: E.view, E.history, E.pings,
// E.treats, E.daily. Fields the app doesn't draw yet (friends, naps) are left out and ignored.

val KJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; isLenient = true }

@Serializable
data class Out(
    val changed: Boolean = false,
    val result: JsonElement? = null,
    val error: String? = null,
    val meets: List<String> = emptyList(),
    val state: UiState,
)

@Serializable
data class UiState(
    val now: Long,
    val view: View,
    val history: List<Hist> = emptyList(),
    val treats: Treats = Treats(),
    val stats: Stats = Stats(),
    val cats: List<CatInfo> = emptyList(),
    val companion: String = "miso",
    val pings: List<Ping> = emptyList(),
    val daily: Daily = Daily(),
) {
    fun cat(id: String?): CatInfo = cats.firstOrNull { it.id == id } ?: cats.firstOrNull() ?: CatInfo("miso", "Miso", "")
    val catsOn: Boolean get() = view.settings.cat
}

@Serializable
data class View(
    val now: Long = 0,
    val settings: Settings = Settings(),
    val active: Block? = null,
    val awaiting: Block? = null,
    val onBreak: Brk? = null,
    val breakOffer: BreakOffer? = null,
    val recent: List<String> = emptyList(),
    val later: List<LaterItem> = emptyList(),
)

@Serializable
data class Settings(
    val interval: Int = 15,
    val style: String = "ask",
    val goal: Int = 0,
    val daily: DailySet = DailySet(),
    val cat: Boolean = true,
    val companion: String = "miso",
    val tags: List<String> = emptyList(),
    val tag: String = "",
)

@Serializable
data class DailySet(val on: Boolean = false, val time: String = "09:00", val days: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6))

@Serializable
data class Block(
    val id: String,
    val intention: String,
    val minutes: Int,
    val interval: Int = 0,
    val start: Long,
    val end: Long,
    val ended: Long? = null,
    val stopped: Boolean = false,
    val drifts: Int = 0,
    val pending: Int? = null,
    val next: Long? = null,
    val style: String = "ask",
    val line: String? = null,
    val step: String? = null,
    val paused: Long? = null,
    val total: Long = 1,
    val parked: List<String> = emptyList(),
    val cat: String = "miso",
    val tag: String? = null,
    val kitten: String = "growing",
    val goneWhy: String? = null,
    val goneBy: String? = null,
    val treats: Int = 0,
    val bonus: Int = 0,
)

@Serializable
data class Brk(val minutes: Int, val start: Long, val end: Long)

@Serializable
data class BreakOffer(val id: String, val minutes: Int)

@Serializable
data class LaterItem(val id: String, val text: String)

@Serializable
data class Hist(
    val id: String,
    val start: Long,
    val intention: String,
    val minutes: Int,
    val actual: Int = 1,
    val stopped: Boolean = false,
    val outcome: String? = null,
    val drifts: Int = 0,
    val checkins: Int = 0,
    val answered: Int = 0,
    val parked: List<String> = emptyList(),
    val cat: String = "miso",
    val kitten: String = "home",
    val goneWhy: String? = null,
    val treats: Int = 0,
)

@Serializable
data class Treats(val earned: Int = 0, val spent: Int = 0, val left: Int = 0, val missions: Int = 0)

@Serializable
data class Stats(
    val today: Int = 0,
    val week: Int = 0,
    val weekBlocks: Int = 0,
    val streak: Int = 0,
    val answered: Int = 0,
    val drifted: Int = 0,
)

@Serializable
data class CatInfo(
    val id: String,
    val name: String,
    val about: String,
    val how: String? = null,
    val adopt: Boolean = false,
    val met: Boolean = false,
)

@Serializable
data class Ping(
    val at: Long,
    val kind: String,
    val tag: String = "",
    val title: String,
    val body: String,
    val block: String? = null,
    val n: Int? = null,
    val cat: Int? = null,
) {
    /** The same ping in two lists (a check-in is its block and number; the end, its block and time). */
    val key: String get() = "$kind:${block ?: ""}:${n ?: ""}:$at"
}

@Serializable
data class Daily(val on: Boolean = false, val time: String = "09:00", val days: List<Int> = emptyList(), val body: String = "", val cat: Boolean = true)

/** "25 min", "1 h 5 min" (web/app.js fmtMin). */
fun fmtMin(m: Int): String = if (m < 60) "$m min" else "${m / 60} h" + (if (m % 60 != 0) " ${m % 60} min" else "")

/** "04:59", "1:02:03" (web/app.js fmtClock). */
fun fmtClock(ms: Long): String {
    val s = maxOf(0L, (ms + 999) / 1000)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

fun times(n: Int) = if (n == 1) "once" else "$n times"
fun minLeft(ms: Long) = maxOf(1L, Math.round(ms / 60000.0))
