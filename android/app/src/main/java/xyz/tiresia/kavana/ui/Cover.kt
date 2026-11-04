package xyz.tiresia.kavana.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.LivingCat
import xyz.tiresia.kavana.Notify
import xyz.tiresia.kavana.Sound
import xyz.tiresia.kavana.UiState
import xyz.tiresia.kavana.fmtClock
import xyz.tiresia.kavana.fmtMin
import xyz.tiresia.kavana.op
import xyz.tiresia.kavana.widget.WidgetOps
import xyz.tiresia.kavana.widget.WidgetUpdater

/**
 * Chataltul on a Galaxy Flip's cover screen, the phone closed: a few pages to swipe through, like the cover's own
 * widgets. Focus (start, the kitten growing in its ring, check-ins, how it went), Today, your cat to pet, and Later.
 * The same engine as the inside screen, so a block started here is the same block when you open the phone.
 */
enum class CoverPage { Focus, Today, Pet, Later }

/**
 * Whether this activity is on a small, nearly square screen of its own: a Flip's cover screen (Samsung's sub display,
 * or Good Lock running Chataltul there). The whole display counts, not the window, so a split screen on a phone isn't one.
 */
fun isCoverScreen(act: Activity): Boolean {
    if (act.intent?.getBooleanExtra("cover", false) == true && xyz.tiresia.kavana.BuildConfig.DEBUG) return true // check-flip.py
    val b = act.windowManager.maximumWindowMetrics.bounds
    val d = act.resources.displayMetrics.density
    val w = b.width() / d
    val h = b.height() / d
    val small = minOf(w, h) < 520f && maxOf(w, h) < 640f
    val square = maxOf(w, h) / minOf(w, h).coerceAtLeast(1f) < 1.34f
    return small && square
}

/** On a Samsung cover screen the cameras sit in the bottom-left corner: the buttons down there keep out of their way. */
private fun cameraRoom(ctx: Context, w: Dp): Dp {
    val act = ctx as? Activity
    val forced = xyz.tiresia.kavana.BuildConfig.DEBUG && act?.intent?.getBooleanExtra("cameras", false) == true
    val samsungCover = Build.MANUFACTURER.equals("samsung", ignoreCase = true) && act?.display?.displayId != Display.DEFAULT_DISPLAY
    return if (forced || samsungCover) w * 0.3f else 0.dp
}

private val tnum = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun CoverScreen(start: CoverPage = CoverPage.Focus, stopAsked: Boolean = false, onStopShown: () -> Unit = {}) {
    val repo = KavanaApp.repo
    val st by repo.state.collectAsStateWithLifecycle()
    val s = st
    if (s == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val now = rememberNow(s)
    val pages = CoverPage.entries
    val pager = rememberPagerState(initialPage = start.ordinal) { pages.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(start) { if (pager.currentPage != start.ordinal) pager.scrollToPage(start.ordinal) }
    // A block that starts (from here, a widget or the watch) brings you to Focus, where it runs.
    LaunchedEffect(s.view.active?.id) { if (s.view.active != null && System.currentTimeMillis() - s.view.active.start < 5000) pager.animateScrollToPage(0) }

    // The next thing the engine has to notice: as on the inside screen (Root), for when an alarm is late.
    val a = s.view.active
    val nextAt = listOfNotNull(a?.takeIf { it.paused == null }?.let { minOf(it.next ?: it.end, it.end) }, a?.pauseEnds, s.view.onBreak?.end).minOrNull()
    LaunchedEffect(nextAt) {
        if (nextAt != null) {
            kotlinx.coroutines.delay((nextAt - System.currentTimeMillis()).coerceAtLeast(0) + 400)
            repo.act(op("tick"))
        }
    }
    // Sounds play while a block runs, here too.
    val ctx = LocalContext.current
    val playable = SoundMix.playable(s)
    val wantSound = playable.isNotEmpty() && a != null && a.paused == null
    LaunchedEffect(wantSound, playable) { if (wantSound) Sound.play(ctx, playable) else Sound.stop() }

    val dark = isSystemInDarkTheme()
    val wall = wallFor(s.view.settings.theme, dark)
    val onSky = a != null && pager.currentPage == 0
    val p = if (a != null) runProgress(a, now) else 0f
    val back = when {
        onSky -> Modifier.sky(p, a!!.paused != null, dark)
        else -> Modifier.wall(wall)
    }
    val light = if (onSky) skyDusk(p, dark) else wall.lightInk
    val ink = if (light) Color.White else Color(0xFF2B211A)
    val muted = if (light) Color.White.copy(alpha = .75f) else Color(0xFF5D5249)

    BoxWithConstraints(Modifier.fillMaxSize().then(back)) {
        val room = cameraRoom(ctx, maxWidth)
        HorizontalPager(pager, Modifier.fillMaxSize().safeDrawingPadding(), beyondViewportPageCount = 0) { i ->
            val pal = CoverPal(ink, muted, light, room)
            when (pages[i]) {
                CoverPage.Focus -> CoverFocus(s, now, pal, stopAsked, onStopShown)
                CoverPage.Today -> CoverToday(s, pal)
                CoverPage.Pet -> CoverPet(s, pal)
                CoverPage.Later -> CoverLater(s, pal) { scope.launch { pager.animateScrollToPage(0) } }
            }
        }
        // the pages, as dots at the top, like the cover's own
        Row(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pages.forEachIndexed { i, _ ->
                Box(Modifier.size(if (i == pager.currentPage) 7.dp else 5.dp).clip(CircleShape)
                    .background(ink.copy(alpha = if (i == pager.currentPage) .9f else .35f)).clickable { scope.launch { pager.animateScrollToPage(i) } })
            }
        }
    }
}

/** The words' colours on the wall or the sky behind them, and the room the cameras need at the bottom left. */
private class CoverPal(val ink: Color, val muted: Color, val light: Boolean, val room: Dp)

/** A big round-cornered button, the cover's own size for a thumb. */
@Composable
private fun CoverButton(label: String, color: Color, onColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = onColor), contentPadding = PaddingValues(horizontal = 14.dp)) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun glass(pal: CoverPal) = Color.White.copy(alpha = if (pal.light) .16f else .6f)

/** The buttons' row, at the bottom, out of the cameras' way. */
@Composable
private fun BottomRow(pal: CoverPal, content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().padding(start = 16.dp + pal.room, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)

// ---------- Focus ----------

@Composable
private fun CoverFocus(s: UiState, now: Long, pal: CoverPal, stopAsked: Boolean, onStopShown: () -> Unit) {
    val a = s.view.active
    val w = s.view.awaiting
    val b = s.view.onBreak
    when {
        w != null -> CoverOutcome(s, w, pal)
        a != null && a.pending != null && a.paused == null -> CoverCheckin(s, a, pal)
        a != null -> CoverRun(s, a, now, pal, stopAsked, onStopShown)
        b != null -> CoverBreak(b, now, pal)
        else -> CoverReady(s, pal)
    }
}

@Composable
private fun CoverReady(s: UiState, pal: CoverPal) {
    val repo = KavanaApp.repo
    var minutes by rememberSaveable { mutableIntStateOf(25) }
    var stopwatch by rememberSaveable { mutableStateOf(s.view.settings.mode == "stopwatch") }
    val c = s.cat(s.companion)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val win = minOf(maxWidth * 0.62f, maxHeight - 150.dp).coerceAtLeast(120.dp)
        Column(Modifier.fillMaxSize().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            HomeWindow(minutes, stopwatch, if (s.catsOn) c.id else null, kitten = false, size = win, onMinutes = { minutes = it }, onCat = {})
            Text(if (stopwatch) "Stopwatch" else fmtMin(minutes), style = MaterialTheme.typography.headlineSmall.merge(tnum), color = pal.ink, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            BottomRow(pal) {
                CoverButton("Start", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, Modifier.weight(1f)) {
                    repo.scope.launch { WidgetOps.start(minutes, if (stopwatch) "stopwatch" else "timer"); WidgetUpdater.refresh(KavanaApp.app) }
                }
                CoverButton(if (stopwatch) "Timer" else "Stopwatch", glass(pal), pal.ink) { stopwatch = !stopwatch }
            }
        }
    }
}

@Composable
private fun CoverRun(s: UiState, a: xyz.tiresia.kavana.Block, now: Long, pal: CoverPal, stopAsked: Boolean, onStopShown: () -> Unit) {
    val repo = KavanaApp.repo
    val k = LocalK.current
    val paused = a.paused != null
    val at = a.paused ?: now
    val left = a.end - at
    val spent = a.total - left
    val over = a.over != null
    val frac: Float
    val grow: Float
    val clock: String
    when {
        a.stopwatch -> {
            clock = fmtClock(spent)
            frac = 1f - (spent % 3_600_000L) / 3_600_000f
            grow = when {
                spent < 600_000L -> 0.25f * spent / 600_000f
                spent < 2_400_000L -> 0.25f + 0.5f * (spent - 600_000L) / 1_800_000f
                else -> minOf(1f, 0.75f + 0.25f * (spent - 2_400_000L) / 1_200_000f)
            }
        }
        over -> { clock = "+" + fmtClock(maxOf(0L, spent - a.minutes * 60_000L)); frac = 0f; grow = 1f }
        else -> { clock = fmtClock(left); frac = (left.toFloat() / a.total.coerceAtLeast(1)).coerceIn(0f, 1f); grow = 1f - frac }
    }
    var stopping by remember { mutableStateOf(false) }
    LaunchedEffect(stopAsked) { if (stopAsked) { stopping = true; onStopShown() } }
    val grace = ((a.cancelUntil - now + 999) / 1000).toInt()
    val done = grace <= 0 && (a.stopwatch || over)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ring = minOf(maxWidth * 0.56f, maxHeight - 158.dp).coerceAtLeast(110.dp)
        Column(Modifier.fillMaxSize().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val title = a.intention.ifBlank { a.tag ?: "" }
            Text(if (paused) "Paused" else title.ifEmpty { if (over) "Extra time" else if (a.stopwatch) "Stopwatch" else "Focusing" },
                color = if (paused) k.orange else pal.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(6.dp))
            Dial(frac, paused, ring, track = Color.White.copy(alpha = if (pal.light) .28f else .5f)) {
                if (s.catsOn) {
                    if (a.kitten != "gone") CatArt("cafe/cushion-1.webp", ring * 0.5f, Modifier.align(Alignment.BottomCenter).padding(bottom = ring * 0.12f))
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = ring * 0.17f)) { Kitten(a, grow, paused, ring * 0.62f) }
                } else Text(clock, style = MaterialTheme.typography.headlineMedium.merge(tnum), color = pal.ink)
            }
            if (s.catsOn) Text(clock, style = MaterialTheme.typography.headlineMedium.merge(tnum), color = pal.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.weight(1f))
            BottomRow(pal) {
                if (s.canPause || paused) CoverButton(if (paused) "Resume" else "Pause", if (paused) k.green else k.orange, Color.White, Modifier.weight(1f)) {
                    repo.launch(op(if (paused) "resume" else "pause"))
                }
                CoverButton(when { grace > 0 -> "Cancel"; done -> "Done"; else -> "Stop" }, if (done) k.green else glass(pal), if (done) Color.White else pal.ink, Modifier.weight(1f)) {
                    if (grace > 0) repo.launch(op("cancel")) else stopping = true
                }
                if (SoundMix.mix.isNotEmpty() && !paused) {
                    val on = !SoundMix.muted
                    FilledIconToggleButton(checked = on, onCheckedChange = { SoundMix.muted = !it; WidgetUpdater.changed(KavanaApp.app) }, modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconToggleButtonColors(containerColor = glass(pal), contentColor = pal.ink, checkedContainerColor = glass(pal), checkedContentColor = pal.ink)) {
                        Icon(Icons.Rounded.GraphicEq, if (on) "Sound on" else "Sound off", Modifier.size(22.dp), tint = pal.ink.copy(alpha = if (on) 1f else .4f))
                    }
                }
            }
        }
    }
    if (stopping) {
        val c = s.cat(a.cat)
        val enough = spent >= 10 * 60_000L
        AlertDialog(
            onDismissRequest = { stopping = false },
            title = { Text(if (a.stopwatch || over) "Done?" else "Stop this block?") },
            text = { if (s.catsOn && a.kitten == "growing" && !(a.stopwatch && enough) && !over) Text("${c.name} will wander off.") },
            confirmButton = { TextButton(onClick = { stopping = false; repo.launch(op("stop")) }) { Text(if (a.stopwatch || over) "Done" else "Stop") } },
            dismissButton = { TextButton(onClick = { stopping = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CoverCheckin(s: UiState, a: xyz.tiresia.kavana.Block, pal: CoverPal) {
    val repo = KavanaApp.repo
    val k = LocalK.current
    val ctx = LocalContext.current
    fun answer(ans: String) {
        repo.launch(op("answer", "id" to a.id, "n" to a.pending, "answer" to ans)) { Notify.cancel(ctx, Notify.ID_PING) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cat = minOf(maxWidth * 0.42f, maxHeight - 200.dp).coerceAtLeast(80.dp)
        Column(Modifier.fillMaxSize().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.catsOn) LiveCat(a.cat, "portrait", cat, interactive = true, onCat = { it.tilt(xyz.tiresia.kavana.Motion.HEAD_TILT, 1.6f) })
            Text("Boo! Check-in", color = k.orange, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Text(if (a.intention.isBlank()) "Still focusing?" else "Still on: ${a.intention}?", color = pal.ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Spacer(Modifier.weight(1f))
            BottomRow(pal) {
                CoverButton("Yes", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, Modifier.weight(1f)) { answer("yes") }
                CoverButton("Drifted", glass(pal), pal.ink, Modifier.weight(1f)) { answer("drifted") }
            }
        }
    }
}

@Composable
private fun CoverOutcome(s: UiState, w: xyz.tiresia.kavana.Block, pal: CoverPal) {
    val repo = KavanaApp.repo
    val k = LocalK.current
    val ctx = LocalContext.current
    val home = w.kitten == "home"
    val minutes = (((w.ended ?: w.end) - w.start) / 60_000L).toInt().coerceAtLeast(1)
    fun out(o: String) = repo.launch(op("outcome", "id" to w.id, "outcome" to o)) { Notify.cancel(ctx, Notify.ID_PING) }
    var cat by remember { mutableStateOf<LivingCat?>(null) }
    LaunchedEffect(w.id, cat) { if (home) cat?.let { it.hop(2); it.happy(1.3f); it.heart() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = minOf(maxWidth * 0.42f, maxHeight - 210.dp).coerceAtLeast(80.dp)
        Column(Modifier.fillMaxSize().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // came home: the cat, hopping; wandered off: its empty cushion, as on the inside screen
            if (s.catsOn) { if (home || w.kitten != "gone") LiveCat(w.cat, if (home) "portrait" else "kitten", size, interactive = true, onCat = { cat = it }) else CatArt(xyz.tiresia.kavana.Art.GONE, size) }
            val c = s.cat(w.cat)
            Text(when { !s.catsOn -> "Time's up"; home -> "${c.name} came home"; else -> "${c.name} wandered off" },
                color = if (home) MaterialTheme.colorScheme.primary else k.orange, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Text(fmtMin(minutes) + if (w.treats + w.bonus > 0) " · +${w.treats + w.bonus} treats" else "", color = pal.ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text("How did it go?", color = pal.muted, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            BottomRow(pal) {
                CoverButton("Done", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, Modifier.weight(1f)) { out("done") }
                CoverButton("Partly", glass(pal), pal.ink, Modifier.weight(1f)) { out("partly") }
                CoverButton("Not really", glass(pal), pal.ink, Modifier.weight(1.3f)) { out("notreally") }
            }
        }
    }
}

@Composable
private fun CoverBreak(b: xyz.tiresia.kavana.Brk, now: Long, pal: CoverPal) {
    val left = b.end - now
    val frac = (left.toFloat() / (b.end - b.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ring = minOf(maxWidth * 0.56f, maxHeight - 150.dp).coerceAtLeast(110.dp)
        Column(Modifier.fillMaxSize().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Break", color = LocalK.current.mint, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Dial(frac, false, ring, track = Color.White.copy(alpha = if (pal.light) .28f else .5f)) {
                Text(fmtClock(left), style = MaterialTheme.typography.headlineMedium.merge(tnum), color = pal.ink)
            }
            Spacer(Modifier.weight(1f))
            BottomRow(pal) { CoverButton("End break", glass(pal), pal.ink, Modifier.weight(1f)) { KavanaApp.repo.launch(op("stopBreak")) } }
        }
    }
}

// ---------- Today ----------

@Composable
private fun CoverToday(s: UiState, pal: CoverPal) {
    val repo = KavanaApp.repo
    val goal = s.view.settings.goal
    val today = s.stats.today
    val miss = s.missions.day
    val next = miss.firstOrNull { !it.done }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ring = minOf(maxWidth * 0.5f, maxHeight - 190.dp).coerceAtLeast(96.dp)
        Column(Modifier.fillMaxSize().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Dial(if (goal > 0) (today.toFloat() / goal).coerceIn(0f, 1f) else 0f, false, ring, track = Color.White.copy(alpha = if (pal.light) .2f else .55f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtMin(today), style = MaterialTheme.typography.titleLarge.merge(tnum), color = pal.ink, fontWeight = FontWeight.Bold)
                    if (goal > 0) Text("of ${fmtMin(goal)}", color = pal.muted, fontSize = 13.sp)
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Stat("${s.stats.streak}", if (s.stats.streak == 1) "day in a row" else "days in a row", pal)
                Stat("${s.treats.left}", "treats", pal)
                if (miss.isNotEmpty()) Stat("${miss.count { it.done }}/${miss.size}", "missions", pal)
            }
            if (next != null) Text(next.text, color = pal.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp))
            Spacer(Modifier.weight(1f))
            val rewards = s.achievements.filter { it.done && !it.claimed }.map { it.id to it.reward } +
                s.starter.steps.filter { it.done && !it.claimed }.map { it.id to it.reward } +
                (if (s.starter.all.done && !s.starter.all.claimed) listOf(s.starter.all.id to s.starter.all.reward) else emptyList()) +
                s.monthly.filter { it.done && !it.claimed }.map { it.id to it.reward }
            if (rewards.isNotEmpty()) BottomRow(pal) {
                CoverButton("Collect +${rewards.sumOf { it.second }}", LocalK.current.orange, Color.White, Modifier.weight(1f)) {
                    repo.scope.launch { for ((id, _) in rewards) repo.act(op("claim", "id" to id)) }
                }
            }
        }
    }
}

@Composable
private fun Stat(big: String, small: String, pal: CoverPal) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(big, color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, style = tnum)
    Text(small, color = pal.muted, fontSize = 11.sp, maxLines = 1)
}

// ---------- your cat ----------

@Composable
private fun CoverPet(s: UiState, pal: CoverPal) {
    val c = s.cat(s.view.active?.cat ?: s.companion)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = minOf(maxWidth * 0.7f, maxHeight - 90.dp).coerceAtLeast(100.dp)
        Column(Modifier.fillMaxSize().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (s.catsOn) {
                Box(contentAlignment = Alignment.BottomCenter) {
                    CatArt("cafe/cushion-1.webp", size * 0.8f)
                    Box(Modifier.padding(bottom = size * 0.08f)) { LiveCat(c.id, "portrait", size, interactive = true, label = "${c.name}. Tap, or hold to purr.") }
                }
                Text(c.name, color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            } else Text("Cats are off", color = pal.muted)
        }
    }
}

// ---------- Later ----------

@Composable
private fun CoverLater(s: UiState, pal: CoverPal, onStarted: () -> Unit) {
    val repo = KavanaApp.repo
    val ctx = LocalContext.current
    // Locked, the cover shows the clock to anyone: your list stays inside until you unlock.
    val locked = remember(s.now) { ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp)) {
        Text("Later", color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        when {
            locked -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = pal.muted, modifier = Modifier.size(18.dp))
                Text("  Locked", color = pal.muted, fontSize = 15.sp)
            }
            s.view.later.isEmpty() -> Text("Nothing for later", color = pal.muted, fontSize = 15.sp)
            else -> s.view.later.take(5).forEach { x ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp)).background(glass(pal)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(x.text, color = pal.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { repo.launch(op("doneLater", "id" to x.id)) }) { Icon(Icons.Rounded.Check, "Done", tint = pal.ink) }
                    IconButton(onClick = {
                        if (s.view.active == null) { repo.scope.launch { WidgetOps.start(25, "timer", x.text, x.id) }; onStarted() }
                    }, enabled = s.view.active == null) { Icon(Icons.Rounded.PlayArrow, "Start", tint = pal.ink.copy(alpha = if (s.view.active == null) 1f else .3f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}
