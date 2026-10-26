package xyz.tiresia.kavana

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// What bridge.js hands back (KB.run). The shapes are engine.js's own: E.view, E.history, E.pings,
// E.treats, E.daily, E.missions, E.achievements, E.starter, E.monthly. Fields the app doesn't draw
// (friends, naps, rankings) are left out and ignored.

val KJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; isLenient = true }

@Serializable
data class Out(
    val changed: Boolean = false,
    val result: JsonElement? = null,
    val error: String? = null,
    val meets: List<String> = emptyList(),
    /** Missions and rewards that just got done, each told once. */
    val done: List<DoneNote> = emptyList(),
    val state: UiState,
)

@Serializable
data class DoneNote(val kind: String, val text: String, val pay: Int = 0, val id: String? = null)

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
    val canPause: Boolean = true,
    val missions: Missions = Missions(),
    val achievements: List<Ach> = emptyList(),
    val starter: Starter = Starter(),
    val monthly: List<Monthly> = emptyList(),
    val shop: Shop = Shop(),
    val news: List<News> = emptyList(),
    val tally: Map<String, Int> = emptyMap(),
) {
    fun cat(id: String?): CatInfo = cats.firstOrNull { it.id == id } ?: cats.firstOrNull() ?: CatInfo("miso", "Miso", "")
    val catsOn: Boolean get() = view.settings.cat
    fun owns(id: String) = id in shop.owned
    fun price(id: String) = shop.prices[id] ?: 0
    fun forSale(id: String) = id in shop.forSale
    /** Rewards done and waiting to be collected (the Cats tab's badge). */
    val toCollect: Int get() = achievements.count { it.done && !it.claimed } + starter.steps.count { it.done && !it.claimed } +
        (if (starter.all.done && !starter.all.claimed) 1 else 0) + monthly.count { it.done && !it.claimed }
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
    val mode: String = "timer",
    val pause: String = "five",
    val overtime: Boolean = false,
    val closeCounts: Boolean = true,
    val lines: List<String> = emptyList(),
    val theme: String = "classic",
)

@Serializable
data class DailySet(val on: Boolean = false, val time: String = "09:00", val days: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6))

@Serializable
data class Block(
    val id: String,
    val intention: String = "",
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
    val mode: String = "timer",
    /** Extra time: when the block's own end came (the kitten came home then); it counts on until Stop. */
    val over: Long? = null,
    /** Until when Cancel takes the block back. */
    val cancelUntil: Long = 0,
    val note: String = "",
    val pauseEnds: Long? = null,
    val pauses: Int = 0,
) {
    val stopwatch: Boolean get() = mode == "stopwatch"
}

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
    val intention: String = "",
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
    val tag: String? = null,
    val mode: String = "timer",
    val note: String = "",
)

@Serializable
data class Treats(val earned: Int = 0, val spent: Int = 0, val left: Int = 0, val missions: Int = 0, val rewards: Int = 0)

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
    val limited: String? = null,
    val price: Int = 0,
    /** For a cat you meet by focusing: how far along you are, of `need`, in `unit`. */
    val have: Int = 0,
    val need: Int = 0,
    val unit: String? = null,
    /** What it can do in the 3D café besides sit and nap: walk, loaf, stretch, wash, back (paintings). */
    val poses: Map<String, String>? = null,
    val ghost: Boolean = false,
    /** Its paintings, from cats.js: a repainted cat's have a new number (Milo's are pepper-2). */
    val portrait: String? = null,
    val kitten: String? = null,
    val nap: String? = null,
)

@Serializable
data class Mission(val id: String, val text: String, val need: Int, val unit: String? = null, val pay: Int, val have: Int, val done: Boolean)

@Serializable
data class Missions(
    val day: List<Mission> = emptyList(), val week: List<Mission> = emptyList(),
    val dayClosed: Boolean = false, val weekClosed: Boolean = false, val dayEnds: Long = 0, val weekEnds: Long = 0,
)

@Serializable
data class Ach(val id: String, val name: String, val text: String, val stat: String = "", val goal: Int, val have: Int, val done: Boolean, val claimed: Boolean, val reward: Int)

@Serializable
data class Step(val id: String, val text: String = "", val goal: Int = 1, val have: Int = 0, val done: Boolean = false, val claimed: Boolean = false, val reward: Int = 0)

@Serializable
data class Starter(val steps: List<Step> = emptyList(), val all: Step = Step("starter-all"), val finished: Boolean = false)

@Serializable
data class Monthly(val id: String, val from: Long, val to: Long, val need: Int, val have: Int, val done: Boolean, val claimed: Boolean, val reward: Int)

@Serializable
data class Season(val id: String, val name: String, val to: List<Int> = emptyList())

@Serializable
data class Soon(val id: String, val name: String, val days: Int)

/** A thing for the café (cats.js ITEMS): `at` is [center x, bottom y, width] in % of the room. */
@Serializable
data class Thing(
    val id: String, val name: String, val about: String, val at: List<Double>, val perch: List<Double>? = null,
    val wall: Boolean = false, val floor: Boolean = false, val limited: String? = null, val on: String? = null, val alt: List<Double>? = null,
    /** Its side and back paintings, for the 3D café when the room is turned. */
    val views: Map<String, String>? = null,
)

@Serializable
data class Theme(val id: String, val name: String, val about: String)

@Serializable
data class ShelterCat(val id: String, val name: String, val about: String)

@Serializable
data class Org(val name: String = "", val url: String = "")

@Serializable
data class Shop(
    val owned: List<String> = emptyList(),
    val prices: Map<String, Int> = emptyMap(),
    val season: Season? = null,
    val soon: Soon? = null,
    val forSale: List<String> = emptyList(),
    val items: List<Thing> = emptyList(),
    val themes: List<Theme> = emptyList(),
    val shelter: List<ShelterCat> = emptyList(),
    val shelterOrg: Org = Org(),
)

@Serializable
data class News(val id: String, val date: String, val title: String, val body: String, val go: String? = null)

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
/** A block's name where one has to be shown: its title, which is optional (like Forest's session title), else its tag. */
fun titleOf(intention: String, tag: String?) = intention.ifBlank { tag?.ifBlank { null } ?: "Focus" }
val Block.title get() = titleOf(intention, tag)
val Hist.title get() = titleOf(intention, tag)

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

/** The signed-in account (the server's /api/me). Accounts from before email all have a password. */
@Serializable
data class Account(
    val name: String,
    val display: String? = null,
    val admin: Boolean = false,
    val email: String? = null,
    val password: Boolean = true,
)
