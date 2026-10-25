package xyz.tiresia.kavana.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.tiresia.kavana.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The Focus tab, Forest's way (the web's Round A): the café wall, a round window with the cat who's coming
// on its cushion, drag around the window for how long, one button. Everything else about the block is in
// the This Block sheet.

// ---------- the café wall ----------

data class Wall(val top: Color, val bottom: Color, val dots: Color, val dot: Dp, val lightInk: Boolean)

/** The wall for the café's theme (web style.css body.home), light or dark. */
fun wallFor(theme: String, dark: Boolean): Wall = when {
    theme == "library" -> Wall(Color(0xFF31604C), Color(0xFF29503F), Color(255, 230, 180, 31), 1.8.dp, true)
    theme == "night" -> Wall(Color(0xFF1E2240), Color(0xFF2B3054), Color(255, 240, 180, 140), 1.dp, true)
    dark -> Wall(Color(0xFF2E2733), Color(0xFF221D27), Color(255, 220, 170, 26), 1.8.dp, true)
    theme == "mint" -> Wall(Color(0xFFE8F6EE), Color(0xFFD5ECE0), Color(60, 140, 100, 51), 1.8.dp, false)
    theme == "sakura" -> Wall(Color(0xFFFDE9F0), Color(0xFFF9D8E4), Color(225, 120, 155, 77), 2.2.dp, false)
    theme == "garden" -> Wall(Color(0xFFEAF7FF), Color(0xFFCFE9FB), Color(255, 255, 255, 204), 3.dp, false)
    else -> Wall(Color(0xFFFDF5E8), Color(0xFFF5E4CB), Color(196, 150, 104, 66), 1.8.dp, false)
}

fun Modifier.wall(w: Wall) = drawBehind { drawWall(w) }

fun DrawScope.drawWall(w: Wall) {
    drawRect(Brush.verticalGradient(listOf(w.top, w.bottom)))
    val step = 26.dp.toPx(); val r = w.dot.toPx()
    var y = step / 2
    while (y < size.height) { var x = step / 2; while (x < size.width) { drawCircle(w.dots, r, Offset(x, y)); x += step }; y += step }
}

// ---------- the sky: afternoon, sunset, purple, stars ----------

// Each stop is [progress, top, bottom]; the stops are close in hue, so the sky never goes muddy (web skyAt()).
private val SKY = listOf(
    0f to (Color(0xFF8FCDF0) to Color(0xFFE7F6FF)), 0.3f to (Color(0xFF9FD2EE) to Color(0xFFFDF0D8)),
    0.48f to (Color(0xFFF7C98A) to Color(0xFFFDE6C2)), 0.64f to (Color(0xFFF2A17C) to Color(0xFFFBD0A0)),
    0.78f to (Color(0xFF8A64AD) to Color(0xFFEE9D86)), 0.9f to (Color(0xFF3D3A78) to Color(0xFF8A6A9E)),
    1f to (Color(0xFF1F2756) to Color(0xFF4A4F8C)),
)
// In dark mode it's evening already: a calm deep blue that goes on into the night (web SKY_DARK).
private val SKY_DARK = listOf(
    0f to (Color(0xFF22406A) to Color(0xFF35557E)), 0.35f to (Color(0xFF243A66) to Color(0xFF454F7E)),
    0.6f to (Color(0xFF262F5E) to Color(0xFF4F4679)), 0.8f to (Color(0xFF1D2350) to Color(0xFF3A396B)),
    1f to (Color(0xFF141A3E) to Color(0xFF2B2E5E)),
)

/**
 * White words on the sky: once it turns purple, and in dark mode the whole way. The words follow the
 * sky, never the phone's theme alone (the theme's light text on the day sky can't be read).
 */
fun skyDusk(p: Float, dark: Boolean) = dark || p > 0.74f

fun skyAt(p0: Float, dark: Boolean = false): Pair<Color, Color> {
    val p = p0.coerceIn(0f, 1f)
    val stops = if (dark) SKY_DARK else SKY
    for (i in 1 until stops.size) if (p <= stops[i].first) {
        val (a, ac) = stops[i - 1]; val (b, bc) = stops[i]
        val t = (p - a) / (b - a)
        return lerp(ac.first, bc.first, t) to lerp(ac.second, bc.second, t)
    }
    return stops.last().second
}

/** The sky in the window right now, by the hour (web homeSky()). */
fun homeSky(dark: Boolean = false): Pair<Color, Color> {
    val c = java.util.Calendar.getInstance()
    val h = c.get(java.util.Calendar.HOUR_OF_DAY) + c.get(java.util.Calendar.MINUTE) / 60f
    return skyAt(when {
        h >= 7 && h < 16 -> 0f
        h >= 16 && h < 19 -> 0.2f + (h - 16) / 3 * 0.55f
        h >= 19 && h < 21 -> 0.75f + (h - 19) / 2 * 0.25f
        h >= 5 && h < 7 -> 0.72f - (h - 5) / 2 * 0.72f
        else -> 1f
    }, dark)
}

/** How far along a running block is, for its sky: a timer by its time, a stopwatch over 3 hours. */
fun runProgress(a: Block, now: Long): Float {
    val at = a.paused ?: now
    val left = a.end - at
    val spent = a.total - left
    return when {
        a.stopwatch -> min(1f, spent / (180 * 60_000f))
        a.over != null -> 1f
        else -> 1f - (left.toFloat() / a.total.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

private val STARS = listOf(.12f to .18f, .28f to .08f, .47f to .22f, .63f to .12f, .81f to .26f, .91f to .09f, .07f to .44f, .37f to .38f, .72f to .41f, .55f to .05f, .18f to .62f, .86f to .58f)

fun Modifier.sky(p: Float, paused: Boolean, dark: Boolean) = drawBehind { drawSky(p, paused, dark) }

fun DrawScope.drawSky(p: Float, paused: Boolean, dark: Boolean = false) {
    val (a, b) = skyAt(p, dark)
    val gray = { c: Color -> if (!paused) c else lerp(c, Color((c.red + c.green + c.blue) / 3, (c.red + c.green + c.blue) / 3, (c.red + c.green + c.blue) / 3), 0.45f) }
    drawRect(Brush.verticalGradient(listOf(gray(a), gray(b))))
    // In dark mode a few stars from the start, more as the night goes on.
    val stars = if (dark) 0.35f + 0.65f * ((p - 0.4f) / 0.6f).coerceIn(0f, 1f) else ((p - 0.7f) / 0.3f).coerceIn(0f, 1f)
    if (stars > 0) STARS.forEachIndexed { i, (x, y) -> drawCircle(Color.White.copy(alpha = stars), (if (i % 3 == 0) 1.6f else 1.1f).dp.toPx(), Offset(x * size.width, y * size.height)) }
}

// ---------- the home screen ----------

/** Starts the block in the form. The title is optional, like Forest's: pick how long and press Start. */
fun startBlock(s: UiState, form: StartForm, act: MainActivity) {
    val repo = KavanaApp.repo
    val set = s.view.settings
    val mode = form.mode ?: set.mode
    val style = form.style ?: set.style
    val tag = form.tag ?: set.tag.takeIf { it in set.tags } ?: ""
    val minutes = if (mode == "stopwatch") 180 else if (form.minutes == 0) form.custom.toIntOrNull() ?: 0 else form.minutes
    if (minutes !in 1..240) { repo.toasts.tryEmit("Pick between 1 and 240 minutes."); return }
    if (!act.notificationsOn() && !repo.prefs.getBoolean("askedNotify", false)) act.askNotifications()
    repo.launch(op("start",
        "intention" to form.intention, "step" to form.step, "minutes" to minutes, "style" to style, "mode" to mode,
        "tag" to tag, "fromLater" to form.fromLater, "cat" to (if (s.catsOn) s.companion else null),
    )) { out -> if (out != null && out.error == null) { form.intention = ""; form.step = ""; form.fromLater = null } }
}

private val glass = Color.White.copy(alpha = .62f)
private val glassDark = Color.White.copy(alpha = .1f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(s: UiState, form: StartForm, act: MainActivity, wall: Wall, newsUnread: Boolean, onNews: () -> Unit, onGoals: () -> Unit, onSettings: () -> Unit) {
    val repo = KavanaApp.repo
    val resumes by act.resumes
    val notifyOn = remember(resumes) { act.notificationsOn() }
    var hideOffer by remember { mutableStateOf<String?>(null) }
    var inviting by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    var titling by remember { mutableStateOf(false) }
    val set = s.view.settings
    val mode = form.mode ?: set.mode
    val watch = mode == "stopwatch"
    val tag = form.tag ?: set.tag.takeIf { it in set.tags } ?: ""
    val len = if (form.minutes == 0) form.custom.toIntOrNull()?.coerceIn(1, 240) ?: 25 else form.minutes
    val ink = if (wall.lightInk) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (wall.lightInk) Color.White.copy(alpha = .72f) else MaterialTheme.colorScheme.onSurfaceVariant
    val chip = if (wall.lightInk) glassDark else glass
    val c = s.cat(s.companion)
    fun start() { focusManager.clearFocus(); startBlock(s, form, act) }

    // a tap anywhere else puts the title away
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
        val winSize = minOf(maxWidth * 0.74f, maxHeight * 0.44f, 420.dp)
        val boxH = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = boxH).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // the top: room for the menu button, the tag, the treats, and the title under the tag
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(54.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(chip).clickable { options = true }.padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tag.isNotEmpty()) { Box(Modifier.size(10.dp).background(tagColor(tag, set.tags), CircleShape)); Spacer(Modifier.width(7.dp)) }
                        Text(tag.ifEmpty { "Choose a tag" }, color = if (tag.isEmpty()) muted else ink, style = MaterialTheme.typography.titleSmall)
                        Icon(Icons.Rounded.ChevronRight, null, tint = muted, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (s.catsOn) Text("🐟 ${s.treats.left}", color = ink, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(chip).padding(horizontal = 12.dp, vertical = 6.dp))
                }
                // The title is optional and quiet, like Forest's "Add session title" under the tag;
                // your recent ones show only while you type one.
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicTextField(
                    form.intention, { form.intention = it.take(140); if (it.isEmpty()) form.fromLater = null },
                    textStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = ink, textAlign = TextAlign.Center),
                    singleLine = true, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().onFocusChanged { titling = it.isFocused }
                        .semantics { contentDescription = "A title for this block (optional)" },
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (!titling) Color.Transparent else if (wall.lightInk) Color.White.copy(alpha = .07f) else Color.White.copy(alpha = .55f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (form.intention.isEmpty()) Text("Add a title", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = muted, textAlign = TextAlign.Center)
                            inner()
                        }
                    },
                )
                if (titling && s.view.recent.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    s.view.recent.forEach { r ->
                        Text(r, color = ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp).clip(RoundedCornerShape(50)).background(if (wall.lightInk) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .55f))
                                .clickable { form.intention = r; focusManager.clearFocus() }.padding(horizontal = 11.dp, vertical = 5.dp))
                    }
                }
                }
                }
                // the window and Start sit in the middle of the screen
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                HomeWindow(
                    minutes = if (watch) 0 else len, fixed = watch, catId = if (s.catsOn) c.id else null, kitten = !watch && len < 25,
                    size = winSize, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                    onMinutes = { form.minutes = it }, onCat = { if (s.catsOn) inviting = true },
                )
                Text(
                    when {
                        !s.catsOn -> if (watch) "Counts up, for up to 3 hours." else "Kavana checks in while you work."
                        watch -> "Stay 10 minutes and ${c.name} can come home. Stay 25 and it comes home grown up."
                        len < 25 -> "Under 25 minutes, ${c.name} comes home a kitten."
                        else -> "${c.name} comes home grown up."
                    },
                    color = muted, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    if (watch) "0:00" else fmtClock(len * 60_000L), color = ink,
                    style = TextStyle(fontSize = (LocalConfiguration.current.screenWidthDp * 0.17f).coerceIn(56f, 76f).sp, fontWeight = FontWeight.ExtraLight, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp),
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(18.dp)).clickable { options = true }.padding(horizontal = 16.dp, vertical = 2.dp)
                        .semantics { contentDescription = "Length ${fmtMin(len)}. Tap for the block's options." },
                )
                Button(
                    onClick = { start() }, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 34.dp),
                    modifier = Modifier.padding(top = 12.dp).widthIn(min = 200.dp).height(54.dp).shadow(10.dp, RoundedCornerShape(50), ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary),
                ) { Text("Start", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                // one quiet row under Start: only the first thing that wants to say something
                val offer = s.view.breakOffer
                val rowMod = Modifier.padding(top = 18.dp).widthIn(max = 520.dp).fillMaxWidth()
                when {
                    !notifyOn -> Quiet("Reminders are off", "Check-ins can't reach you. Tap to turn them on.", "🔔", wall, rowMod) { act.askNotifications() }
                    offer != null && hideOffer != offer.id -> Quiet("Nice work. Take a break?", "Tap for ${offer.minutes} minutes. Swipe it away for later.", "☕", wall, rowMod, onDismiss = { hideOffer = offer.id }) {
                        repo.launch(op("startBreak", "minutes" to offer.minutes))
                    }
                    s.toCollect > 0 -> Quiet(if (!s.starter.finished) "Starter Challenge" else "Goals", if (s.toCollect == 1) "A reward is waiting. Tap to collect." else "${s.toCollect} rewards are waiting. Tap to collect.", "✨", wall, rowMod) { onGoals() }
                    newsUnread -> Quiet("What's new", s.news.firstOrNull()?.title ?: "", "📣", wall, rowMod) { onNews() }
                }
                }
                Spacer(Modifier.height(1.dp))
            }
        }
    }
    if (inviting) InviteSheet(s) { inviting = false }
    if (options) ThisBlockSheet(s, form, onEditTags = { options = false; onSettings() }) { options = false }
}

/** The one quiet row under Start: a little card with an emoji, a title and a line. */
@Composable
private fun Quiet(title: String, text: String, emoji: String, wall: Wall, modifier: Modifier, onDismiss: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(if (wall.lightInk) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .7f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (wall.lightInk) Color.White else MaterialTheme.colorScheme.onSurface)
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = if (wall.lightInk) Color.White.copy(alpha = .7f) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Not Now") }
        else Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/**
 * The window: a round pane with the sky of the moment, a cushion on the sill, the cat on it, and the ring
 * around it that you drag to set how long (5 to 120 minutes, in fives, like Forest's old ring). A tap on
 * the cat picks who's coming.
 */
@Composable
fun HomeWindow(minutes: Int, fixed: Boolean, catId: String?, kitten: Boolean, size: Dp, modifier: Modifier = Modifier, onMinutes: (Int) -> Unit, onCat: () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val (sky1, sky2) = remember(dark) { homeSky(dark) }
    val dens = LocalDensity.current.density
    Box(
        modifier.size(size).semantics { contentDescription = if (fixed) "Stopwatch" else "How long: ${fmtMin(minutes)}. Drag around the window to change it." }
            .pointerInput(fixed) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    fun set(p: Offset) {
                        var a = atan2((p.x - c.x).toDouble(), (-(p.y - c.y)).toDouble())
                        if (a < 0) a += 2 * PI
                        val m = ((a / (2 * PI)) * 24).roundToInt() * 5
                        onMinutes(if (m == 0) 120 else m.coerceIn(5, 120))
                    }
                    var ring = !fixed && hypot(down.position.x - c.x, down.position.y - c.y) > this.size.width * 0.36f
                    var moved = false
                    if (ring) { set(down.position); down.consume() }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { if (!ring && !moved) onCat(); break }
                        if (!moved && (ch.position - down.position).getDistance() > 8 * dens) { moved = true; if (!fixed) ring = true }
                        if (ring) { set(ch.position); ch.consume() }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val k = this.size.minDimension / 320f
            val sw = 14 * k
            val r = 148 * k
            drawCircle(Color(124, 74, 34, 41), r, style = Stroke(sw))
            if (!fixed) {
                val f = (minutes / 120f).coerceIn(0f, 1f)
                drawArc(tint, -90f, 360f * f, false, topLeft = Offset(center.x - r, center.y - r), size = Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
                val a = f * 2 * PI - PI / 2
                val kn = Offset((center.x + r * cos(a)).toFloat(), (center.y + r * sin(a)).toFloat())
                drawCircle(Color.Black.copy(alpha = .18f), 12 * k, kn + Offset(0f, 1.5f * k))
                drawCircle(Color.White, 11 * k, kn)
                drawCircle(tint, 11 * k, kn, style = Stroke(4 * k))
            }
        }
        val pane = size * 0.83f
        Box(
            Modifier.align(Alignment.Center).size(pane).shadow(12.dp, CircleShape).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(sky1, sky2))).border(pane * 0.026f, Color(0xFFFFF8EE), CircleShape)
                .drawBehind {
                    // the window's frame
                    val lw = 4.dp.toPx() * (pane / 260.dp)
                    drawRect(Color(0xFFFFF8EE).copy(alpha = .7f), Offset(this.size.width / 2 - lw / 2, 0f), Size(lw, this.size.height * 0.34f))
                    drawRect(Color(0xFFFFF8EE).copy(alpha = .7f), Offset(0f, this.size.height * 0.34f - lw / 2), Size(this.size.width, lw))
                },
        ) {
            if (catId != null) {
                CatArt("cafe/cushion-1.webp", pane * 0.72f, Modifier.align(Alignment.BottomCenter).padding(bottom = pane * 0.02f))
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = pane * 0.13f)) {
                    LiveCat(catId, if (kitten) "kitten" else "portrait", pane * 0.56f, shadow = false, interactive = false)
                }
            }
        }
    }
}

/** This Block: who's coming, the tag, timer or stopwatch, how long, check-ins, and a first step. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThisBlockSheet(s: UiState, form: StartForm, onEditTags: () -> Unit, onDone: () -> Unit) {
    val set = s.view.settings
    val mode = form.mode ?: set.mode
    val style = form.style ?: set.style
    val tag = form.tag ?: set.tag.takeIf { it in set.tags } ?: ""
    var inviting by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp).imePadding()) {
            Box(Modifier.fillMaxWidth()) {
                Text("This Block", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterEnd)) { Text("Done", fontWeight = FontWeight.SemiBold) }
            }
            if (s.catsOn) {
                val c = s.cat(s.companion)
                Group {
                    Item(c.name, sub = "grows up while you focus", onClick = { inviting = true }, leading = { LiveCat(c.id, "kitten", 48.dp, shadow = false) }) {
                        Text("Change", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Head("Tag")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                set.tags.forEach { t ->
                    FilterChip(selected = t == tag, onClick = { form.tag = if (t == tag) "" else t }, label = { Text(t) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(tagColor(t, set.tags), CircleShape)) })
                }
                TextButton(onClick = onEditTags) { Text("Edit") }
            }
            Head("Timer")
            Seg(listOf("timer" to "Timer", "stopwatch" to "Stopwatch"), mode) { form.mode = it }
            if (mode == "timer") {
                Head("How long")
                val chosen = if (form.minutes in listOf(5, 25, 50)) form.minutes else if (form.minutes == 0) 0 else -1
                Seg(listOf(5 to "5 min", 25 to "25 min", 50 to "50 min", 0 to "Custom"), chosen) { form.minutes = it }
                if (form.minutes == 0) OutlinedTextField(
                    form.custom, { v -> form.custom = v.filter { it.isDigit() }.take(3) }, singleLine = true, label = { Text("Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.padding(top = 8.dp).width(140.dp),
                )
                if (chosen == -1) Text("${form.minutes} minutes, from the ring.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            }
            Head("Check-ins")
            Seg(listOf("ask" to "Ask me", "nudge" to "Nudge me"), style) { form.style = it }
            Head("First step")
            OutlinedTextField(form.step, { form.step = it.take(140) }, placeholder = { Text("The smallest thing that gets you going") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val n = set.interval
            Text(
                (if (mode == "stopwatch") "Counts up, for up to 3 hours. Tap Done when you are; after 10 minutes your kitten comes home. " else "") +
                    when { n == 0 -> "Kavana checks in once, at the end."; style == "nudge" -> "About every $n minutes it sends a short line to keep you going."; else -> "Every $n minutes it asks if you're still on it." },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
    if (inviting) InviteSheet(s) { inviting = false }
}

@Composable
private fun Head(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    letterSpacing = 1.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp))
