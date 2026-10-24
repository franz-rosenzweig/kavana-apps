package xyz.tiresia.kavana.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.tiresia.kavana.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The Café: the room your kittens came home to (with the things you bought, in your theme), your
// focus by day, week, month or year like Forest's overview, and every block, which you can edit.

// ---------- the room ----------

/** A room's wall and floor (web/style.css .room.theme-*). */
data class RoomPalette(val wall1: Color, val wall2: Color, val dots: Color, val skirt: Color, val skirtLine: Color, val floor1: Color, val floor2: Color, val board: Color)

private fun c(hex: Long) = Color(hex)
private val PALETTES = mapOf(
    "classic" to RoomPalette(c(0xFFFDF5E8), c(0xFFF8EAD4), Color(196, 150, 104).copy(alpha = .2f), c(0xFFECD2AE), c(0xFFE2C196), c(0xFFE8BD85), c(0xFFD9A66C), Color(124, 74, 34).copy(alpha = .13f)),
    "mint" to RoomPalette(c(0xFFE8F6EE), c(0xFFD5ECE0), Color(60, 140, 100).copy(alpha = .18f), c(0xFFCFE3D6), c(0xFFB7D3C2), c(0xFFEAD4AB), c(0xFFDCC191), Color(120, 85, 40).copy(alpha = .12f)),
    "sakura" to RoomPalette(c(0xFFFDE9F0), c(0xFFF9D8E4), Color(225, 120, 155).copy(alpha = .28f), c(0xFFF3C9D6), c(0xFFEAB3C4), c(0xFFF2DDC9), c(0xFFE6CBB3), Color(150, 90, 70).copy(alpha = .1f)),
    "library" to RoomPalette(c(0xFF31604C), c(0xFF29503F), Color(255, 230, 180).copy(alpha = .1f), c(0xFF5B3B26), c(0xFF4A2F1E), c(0xFF7C5438), c(0xFF694530), Color.Black.copy(alpha = .2f)),
    "garden" to RoomPalette(c(0xFFCFE9FB), c(0xFFEAF7FF), Color.White.copy(alpha = .7f), c(0xFF8FCF7A), c(0xFF76B862), c(0xFF9FD480), c(0xFF86C266), Color(60, 120, 40).copy(alpha = .14f)),
    "night" to RoomPalette(c(0xFF1E2240), c(0xFF2B3054), Color(255, 240, 180).copy(alpha = .6f), c(0xFF3A3550), c(0xFF4A4466), c(0xFF4C3B2D), c(0xFF3E3025), Color.Black.copy(alpha = .25f)),
)
private val CLASSIC_DARK = RoomPalette(c(0xFF3A3240), c(0xFF322B38), Color(255, 220, 170).copy(alpha = .08f), c(0xFF4A3C36), c(0xFF5A483E), c(0xFF6A4E36), c(0xFF5A412C), Color.Black.copy(alpha = .18f))

/** The bright themes are dimmed in dark mode, as on the web. */
fun roomPalette(theme: String, dark: Boolean): RoomPalette {
    if (dark && theme == "classic") return CLASSIC_DARK
    val p = PALETTES[theme] ?: PALETTES.getValue("classic")
    if (!dark || theme !in setOf("mint", "sakura", "garden")) return p
    val dim = { x: Color -> lerp(Color.Black, x, 0.62f).copy(alpha = x.alpha) }
    return RoomPalette(dim(p.wall1), dim(p.wall2), p.dots, dim(p.skirt), dim(p.skirtLine), dim(p.floor1), dim(p.floor2), p.board)
}

private fun Modifier.roomBackground(p: RoomPalette) = drawBehind {
    val w = size.width; val h = size.height
    val wallH = h * 0.58f
    drawRect(Brush.verticalGradient(listOf(p.wall1, p.wall2), 0f, wallH), size = Size(w, wallH))
    val step = w * 0.055f
    var y = step / 2
    while (y < wallH * 0.91f) { var x = step / 2; while (x < w) { drawCircle(p.dots, radius = w * 0.0042f, center = Offset(x, y)); x += step }; y += step }
    val skirtTop = wallH * 0.91f
    drawRect(p.skirt, topLeft = Offset(0f, skirtTop), size = Size(w, wallH - skirtTop))
    drawRect(p.skirtLine, topLeft = Offset(0f, skirtTop), size = Size(w, w * 0.006f))
    drawRect(Brush.verticalGradient(listOf(p.floor1, p.floor2), wallH, h), topLeft = Offset(0f, wallH), size = Size(w, h - wallH))
    var x = 0f
    while (x < w) { drawRect(p.board, topLeft = Offset(x, wallH), size = Size(w * 0.0035f, h - wallH)); x += w * 0.125f }
}

/** A cat in the room: picture box centred at x %, its bottom at y %, w % wide. */
data class RoomCat(val key: String, val id: String, val pose: String, val x: Double, val y: Double, val w: Double, val z: Float, val flip: Boolean)

@Composable
fun ThemeSwatch(theme: String, modifier: Modifier = Modifier) {
    val p = roomPalette(theme, isSystemInDarkTheme())
    Box(modifier.aspectRatio(1.5f).clip(RoundedCornerShape(12.dp)).roomBackground(p))
}

/**
 * The room. `things` in place, `cats` alive on them and on the floor. `dropIn`: the things not in
 * `already` arrive one after another with a bounce (the café filling up).
 */
@Composable
fun Room(theme: String, things: List<Thing>, owned: (String) -> Boolean, cats: List<RoomCat>, modifier: Modifier = Modifier, already: Set<String> = emptySet(), dropIn: Boolean = false, overlay: @Composable BoxScope.() -> Unit = {}) {
    val p = roomPalette(theme, isSystemInDarkTheme())
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(20.dp)).roomBackground(p)) {
        val W = maxWidth; val H = maxHeight
        // Each thing and each cat keeps its own place in the composition (key), and nothing returns
        // early from the loop: a picture still loading just isn't drawn yet.
        things.forEachIndexed { i, x -> key(x.id) {
            // the snow globe needs a window sill; without a window it sits on the floor
            val at = if (x.on != null && !owned(x.on) && x.alt != null) x.alt else x.at
            val z = if (x.floor) 1f else if (x.wall && at === x.at) 2f else 10f + at[1].toFloat()
            val w = W * (at[2] / 100).toFloat()
            val anim = remember(dropIn) { Animatable(if (dropIn && x.id !in already) 0f else 1f) }
            LaunchedEffect(dropIn) { if (anim.value < 1f) { delay(70L * i); anim.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) } }
            val img = art(Art.thing(x.id))
            if (img != null) Image(
                img, x.name, contentScale = ContentScale.Fit,
                modifier = Modifier.zIndex(z).absoluteOffset(W * ((at[0] - at[2] / 2) / 100).toFloat(), H * (at[1] / 100).toFloat() - w)
                    .size(w).graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        if (x.floor) scaleY = 0.4f
                        alpha = anim.value.coerceIn(0f, 1f); translationY = (1 - anim.value) * -0.3f * size.height
                    },
            )
        } }
        cats.forEachIndexed { i, rc -> key(rc.key) {
            val w = W * (rc.w / 100).toFloat()
            Box(Modifier.zIndex(rc.z).absoluteOffset(W * ((rc.x - rc.w / 2) / 100).toFloat(), H * (rc.y / 100).toFloat() - w)
                .graphicsLayer { if (rc.flip) scaleX = -1f }) {
                // The first 16 are alive (as on the web); a busy month's crowd past that sits still.
                if (i < 16) LiveCat(rc.id, rc.pose, w, interactive = true, grid = 18, seed = rc.key.hashCode(), label = "A cat in your café")
                else CatArt(Art.portrait(rc.id), w)
            }
        } }
        overlay()
    }
}

private fun hashOf(s: String) = s.fold(7L) { h, ch -> (h * 31 + ch.code) and 0xffffffffL }

/** web/app.js seeded(): a small seeded random, so the café looks the same every time you open it. */
private fun seeded(seed: Long): () -> Double {
    var a = seed and 0xffffffffL
    return {
        a = (a + 0x6d2b79f5L) and 0xffffffffL
        var t = a.toInt()
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
    }
}

/** web/app.js floorSpots(): places on the floor for cats with no thing to sit on. */
private fun floorSpots(n: Int, rand: () -> Double): List<Triple<Double, Double, Double>> {
    val size = max(5.0, min(15.0, 15 * sqrt(4.0 / max(4, n))))
    val rows = max(1, sqrt(n / 3.0).roundToInt())
    val out = ArrayList<Triple<Double, Double, Double>>()
    var left = n
    for (r in 0 until rows) {
        if (left <= 0) break
        val inRow = Math.ceil(left.toDouble() / (rows - r)).toInt()
        left -= inRow
        val y = if (rows == 1) 89.0 else 73 + (r * 24.0) / (rows - 1)
        val depth = 0.8 + 0.3 * ((y - 73) / 24)
        for (i in 0 until inRow) {
            val x = 9 + ((i + 0.5 + (rand() - 0.5) * 0.5) * 82) / inRow
            out.add(Triple(x, y + (rand() - 0.5) * 3, size * depth))
        }
    }
    return out
}

/** The cats that came home in `home` (oldest first), on the things that have a seat and then on the floor (web/app.js drawRoom). */
fun seatCats(home: List<Pair<String, String>>, things: List<Thing>, residents: List<String>, seed: Long): List<RoomCat> {
    val shown = home.take(60)
    val perches = things.filter { it.perch != null }
    val rand = seeded(seed)
    val spots = floorSpots(max(0, shown.size - perches.size), rand)
    val perchSize = max(9.0, min(13.0, 15 * sqrt(4.0 / max(4, shown.size))))
    val out = shown.mapIndexed { i, (key, cat) ->
        val seat = perches.getOrNull(i)
        if (seat != null) RoomCat(key, cat, "portrait", seat.perch!![0], seat.perch[1], perchSize, 11f + seat.at[1].toFloat(), hashOf(key) % 2 == 1L)
        else { val (x, y, w) = spots[i - perches.size]; RoomCat(key, cat, "portrait", x, y, w, 10f + y.toFloat() + 0.5f, hashOf(key) % 2 == 1L) }
    }
    // Shelter cats you sponsor live along the back.
    val back = residents.mapIndexed { i, id -> RoomCat("res-$id", id, "portrait", listOf(33.0, 76.0, 53.0, 14.0)[i % 4], 69.0, 8.5, 79f, i % 2 == 1) }
    return out + back
}

// ---------- the tab ----------

private enum class Span(val label: String, val word: String, val vs: String) { Day("Day", "today", "yesterday"), Week("Week", "this week", "last week"), Month("Month", "this month", "last month"), Year("Year", "this year", "last year") }

private fun periodOf(span: Span, at: LocalDate): Pair<LocalDate, LocalDate> = when (span) {
    Span.Day -> at to at.plusDays(1)
    Span.Week -> at.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).let { it to it.plusDays(7) } // Sunday, like the web
    Span.Month -> at.withDayOfMonth(1).let { it to it.plusMonths(1) }
    Span.Year -> at.withDayOfYear(1).let { it to it.plusYears(1) }
}
private fun shift(span: Span, at: LocalDate, dir: Long): LocalDate = when (span) {
    Span.Day -> at.plusDays(dir); Span.Week -> at.plusWeeks(dir); Span.Month -> at.plusMonths(dir); Span.Year -> at.plusYears(dir)
}
private fun LocalDate.ms(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun periodLabel(span: Span, from: LocalDate, to: LocalDate): String {
    val today = LocalDate.now()
    val cur = periodOf(span, today).first
    val last = periodOf(span, shift(span, today, -1)).first
    return when (span) {
        Span.Day -> if (from == today) "Today" else if (from == today.minusDays(1)) "Yesterday" else from.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        Span.Week -> if (from == cur) "This Week" else if (from == last) "Last Week" else "${from.format(DateTimeFormatter.ofPattern("MMM d"))} – ${to.minusDays(1).format(DateTimeFormatter.ofPattern(if (to.minusDays(1).month == from.month) "d" else "MMM d"))}"
        Span.Month -> if (from == cur) "This Month" else from.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        Span.Year -> if (from == cur) "This Year" else from.year.toString()
    }
}

private val OUTCOME = mapOf("done" to ("🎉" to "Done"), "partly" to ("🌗" to "Partly"), "notreally" to ("🌧️" to "Not really"))

@Composable
fun CafeTab(s: UiState) {
    val repo = KavanaApp.repo
    var span by rememberSaveable { mutableStateOf(Span.Week) }
    var at by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var someday by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Hist?>(null) }
    val (from, to) = periodOf(span, LocalDate.ofEpochDay(at))
    val (bf, bt) = periodOf(span, shift(span, from, -1))
    // The state has the newest 400 blocks. A period (or the one before it) older than those is asked for.
    val capped = s.history.size >= 400 && bf.ms() < s.history.last().start
    var older by remember { mutableStateOf<List<Hist>?>(null) }
    LaunchedEffect(capped, bf, to, s.history.size) {
        older = if (!capped) null else repo.act(op("period", "from" to bf.ms(), "to" to to.ms()))?.result
            ?.let { runCatching { KJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Hist.serializer()), it) }.getOrNull() }
    }
    val source = if (capped) older.orEmpty() else s.history
    val list = source.filter { it.start >= from.ms() && it.start < to.ms() }
    val ownedThings = s.shop.items.filter { s.owns(it.id) }
    val residents = s.shop.shelter.filter { s.owns(it.id) }.map { it.id }
    val home = list.filter { it.kitten == "home" }.sortedBy { it.start }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 28.dp)) {
        LargeTitle("Café") { TreatsChip(s.treats.left) }
        Seg(Span.entries.map { it to it.label }, span, Modifier.padding(top = 8.dp)) { span = it; at = LocalDate.now().toEpochDay(); someday = false }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { at = shift(span, LocalDate.ofEpochDay(at), -1).toEpochDay() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Before") }
            Text(periodLabel(span, from, to), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = { at = shift(span, LocalDate.ofEpochDay(at), 1).toEpochDay() }, enabled = to.ms() <= System.currentTimeMillis()) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "After") }
        }

        // Someday: every regular thing and the cats you meet by focusing, dropping in (a peek at what the café becomes).
        val things = if (someday) s.shop.items.filter { it.limited == null || s.owns(it.id) } else ownedThings
        // The café in 3D (the web's cafe3d.js): the cats that came home in the period, on the things you own.
        // Someday: every regular thing and the cats you meet by focusing, dropping in.
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        val size = when (span) { Span.Day -> "day"; Span.Week -> "week"; else -> "month" }
        val scene = if (someday) cafeScene(s, emptyList(), "month", 7, dark, quiet = false).copy(
            things = things.map { it.id },
            cats = s.cats.filter { !it.adopt }.map { c -> CafeCat("someday-${c.id}", c.id, "cats/${c.id}-1.webp", "cats/${c.id}-nap-1.webp", poses = c.poses, ghost = c.ghost, title = c.name, sub = c.about) },
        ) else cafeScene(s, list, size, cafeHash("${span.name}${from.ms()}"), dark)
        Box(Modifier.fillMaxWidth().aspectRatio(1.08f).padding(top = 6.dp)) {
            CafeView(scene, cafeNames(s), Modifier.fillMaxSize())
            val word = if (periodOf(span, LocalDate.now()).first == from) span.word else "then"
            if (!someday && home.isEmpty()) Text(
                if (list.any { it.kitten == "gone" }) "No cats stayed $word. Next time, stay to the end." else "No cats came home $word.",
                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .78f)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
            if (someday) Text("Your café, someday", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .78f)).padding(horizontal = 14.dp, vertical = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = someday, onClick = { someday = !someday }, label = { Text("Peek: Someday") })
            Spacer(Modifier.weight(1f))
            val themes = s.shop.themes.filter { it.id == "classic" || s.owns("theme-${it.id}") }
            if (themes.size > 1) themes.forEach { th ->
                val on = s.view.settings.theme == th.id
                Box(Modifier.size(40.dp, 28.dp).clip(RoundedCornerShape(8.dp)).clickable { repo.launch(settings("theme" to th.id)) }
                    .then(if (on) Modifier.background(MaterialTheme.colorScheme.primary).padding(2.dp) else Modifier)) { ThemeSwatch(th.id, Modifier.fillMaxSize()) }
            }
        }

        // The period in numbers, compared with the one before (Forest's overview).
        val focused = list.sumOf { it.actual }
        val gone = list.size - home.size
        val earned = list.sumOf { it.treats }
        val was = source.filter { it.start >= bf.ms() && it.start < bt.ms() }.sumOf { it.actual }
        val tiles = buildList {
            add(home.size.toString() to if (home.size == 1) "cat came home" else "cats came home")
            add(fmtMin(focused) to "focused")
            if (gone > 0) add(gone.toString() to "wandered off")
            if (earned > 0) add("🐟 $earned" to "treats earned")
            if (was > 0 || focused > 0) { val d = focused - was; add((if (d > 0) "+" else if (d < 0) "−" else "") + fmtMin(kotlin.math.abs(d)) to if (d == 0) "same as ${span.vs}" else if (d > 0) "more than ${span.vs}" else "less than ${span.vs}") }
        }
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (big, small) -> StatTile(big, small, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (span != Span.Day && list.isNotEmpty()) HoursChart(list, from)
        if (home.isNotEmpty()) {
            Group("Who came home") {
                val counts = home.groupingBy { it.cat }.eachCount().entries.sortedByDescending { it.value }
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    counts.take(5).forEach { (cat, n) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            LiveCat(cat, "portrait", 52.dp, shadow = false)
                            Text("${s.cat(cat).name} ×$n", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(maxOf(0, 5 - counts.size)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // Every block in the period, newest first, by day. Tap one to edit it.
        list.groupBy { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, blocks) ->
            Group(if (day == LocalDate.now()) "Today" else if (day == LocalDate.now().minusDays(1)) "Yesterday" else day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))) {
                blocks.forEachIndexed { i, b ->
                    if (i > 0) GroupDivider()
                    HistRow(s, b) { editing = b }
                }
            }
        }
        if (s.history.isEmpty()) Empty(Art.nap(s.companion), "No blocks yet. Your first one shows up here.")
        else {
            val st = s.stats
            Group("Habits") {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(fmtMin(st.today), "today", Modifier.weight(1f))
                    StatTile(fmtMin(st.week), "last 7 days", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("${st.streak} 🐾", if (st.streak == 1) "day in a row" else "days in a row", Modifier.weight(1f))
                    StatTile(if (st.answered > 0) "${Math.round(100.0 * (st.answered - st.drifted) / st.answered)}%" else "–", "check-ins on track", Modifier.weight(1f))
                }
            }
            TextButton(onClick = { clearing = true }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Clear My History", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        title = { Text("Clear My History?") },
        text = { Text("Every finished block goes, on this phone. Your treats, your cats and your café stay.") },
        confirmButton = { TextButton(onClick = { clearing = false; repo.launch(op("clearHistory")) }) { Text("Clear", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } },
    )
    editing?.let { b -> EditSheet(s, b) { editing = null } }
}

@Composable
private fun HistRow(s: UiState, b: Hist, onClick: () -> Unit) {
    val (emo, word) = OUTCOME[b.outcome] ?: ("⏹️" to "No answer")
    val length = if (b.mode == "stopwatch") "stopwatch, ${b.actual} min" else if (b.actual < b.minutes) "${b.actual} of ${b.minutes} min" else "${b.minutes} min"
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emo, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.intention, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (b.tag != null) TagChip(b.tag, s.view.settings.tags, Modifier.padding(start = 6.dp))
            }
            Text(listOfNotNull(word, length, if (b.drifts > 0) "drifted ${times(b.drifts)}" else null, if (b.treats > 0) "🐟 +${b.treats}" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (b.note.isNotEmpty()) Text(b.note, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(clockTime(b.start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.catsOn) CatArt(if (b.kitten == "home") Art.portrait(b.cat) else Art.GONE, 36.dp)
        }
    }
}

/** Edit a past block: what it was, its tag, a note. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditSheet(s: UiState, b: Hist, onDone: () -> Unit) {
    val repo = KavanaApp.repo
    var intention by remember(b.id) { mutableStateOf(b.intention) }
    var tag by remember(b.id) { mutableStateOf(b.tag ?: "") }
    var note by remember(b.id) { mutableStateOf(b.note) }
    fun save() {
        if (intention.isBlank()) { repo.toasts.tryEmit("Say what the block was."); return }
        repo.launch(op("edit", "id" to b.id, "patch" to mapOf("intention" to intention, "tag" to tag, "note" to note)))
        onDone()
    }
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone) { Text("Cancel") }
                Text("Edit Block", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                TextButton(onClick = { save() }) { Text("Save") }
            }
            OutlinedTextField(intention, { intention = it.take(140) }, label = { Text("What you worked on") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            if (s.view.settings.tags.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.view.settings.tags.forEach { t ->
                    FilterChip(selected = t == tag, onClick = { tag = if (t == tag) "" else t }, label = { Text(t) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(tagColor(t, s.view.settings.tags), CircleShape)) })
                }
            }
            OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

/** When you focus: minutes by hour of the day over the period (web/app.js drawHours). */
@Composable
private fun HoursChart(list: List<Hist>, from: LocalDate) {
    val bars = IntArray(24)
    val zone = ZoneId.systemDefault()
    for (b in list) for (i in 0 until b.actual) bars[Instant.ofEpochMilli(b.start + i * 60_000L).atZone(zone).hour] += 1
    val max = maxOf(1, bars.maxOrNull() ?: 1)
    val color = MaterialTheme.colorScheme.primary
    val none = MaterialTheme.colorScheme.surfaceContainerHigh
    val top = bars.indices.maxByOrNull { bars[it] } ?: 0
    val fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    Group("When you focus", foot = "You focus most around ${java.time.LocalTime.of(top, 0).format(fmt)}.") {
        Canvas(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 14.dp, vertical = 12.dp)) {
            val w = size.width / 24
            bars.forEachIndexed { i, v ->
                val h = if (v > 0) maxOf(3f, size.height * v / max) else 3f
                drawRoundRect(if (v > 0) color else none, topLeft = Offset(i * w + w * 0.18f, size.height - h), size = Size(w * 0.64f, h), cornerRadius = CornerRadius(w * 0.2f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
            listOf(0, 6, 12, 18).forEach { hr -> Text(java.time.LocalTime.of(hr, 0).format(fmt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)) }
        }
    }
}
