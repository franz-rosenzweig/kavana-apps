package xyz.tiresia.kavana.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.tiresia.kavana.*
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date

@Composable
fun FocusTab(s: UiState, now: Long, form: StartForm, act: MainActivity, newsUnread: Boolean, onBreathe: () -> Unit, onNews: () -> Unit, onSound: () -> Unit) {
    val a = s.view.active
    val brk = s.view.onBreak
    when {
        a != null -> RunScreen(s, a, now, onSound)
        brk != null -> BreakScreen(brk, now)
        else -> HomeScreen(s, form, act, newsUnread, onBreathe, onNews)
    }
}

fun clockTime(t: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))

private val tabular = TextStyle(fontFeatureSettings = "tnum")

// ---------- the start screen ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(s: UiState, form: StartForm, act: MainActivity, newsUnread: Boolean, onBreathe: () -> Unit, onNews: () -> Unit) {
    val repo = KavanaApp.repo
    val resumes by act.resumes
    val notifyOn = remember(resumes) { act.notificationsOn() }
    var hideOffer by rememberSaveable { mutableStateOf<String?>(null) }
    var inviting by remember { mutableStateOf(false) }
    val style = form.style ?: s.view.settings.style
    val focus = remember { FocusRequester() }
    val set = s.view.settings

    val mode = form.mode ?: set.mode
    val tag = form.tag ?: set.tag.takeIf { it in set.tags } ?: ""

    fun start() {
        val minutes = if (mode == "stopwatch") 180 else if (form.minutes == 0) form.custom.toIntOrNull() ?: 0 else form.minutes
        if (form.intention.isBlank()) { runCatching { focus.requestFocus() }; repo.toasts.tryEmit("Say what you're working on first."); return }
        if (minutes !in 1..240) { repo.toasts.tryEmit("Pick between 1 and 240 minutes."); return }
        if (!act.notificationsOn() && !repo.prefs.getBoolean("askedNotify", false)) act.askNotifications()
        repo.launch(op("start",
            "intention" to form.intention, "step" to form.step, "minutes" to minutes, "style" to style, "mode" to mode,
            "tag" to tag, "fromLater" to form.fromLater, "cat" to (if (s.catsOn) s.companion else null),
        )) { out -> if (out != null && out.error == null) { form.intention = ""; form.step = ""; form.fromLater = null } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 16.dp))
        LargeTitle("Focus") {
            IconButton(onClick = onBreathe) { Icon(Icons.Rounded.SelfImprovement, "A mindful minute", tint = MaterialTheme.colorScheme.primary) }
            if (s.news.isNotEmpty()) {
                IconButton(onClick = onNews) {
                    if (newsUnread) BadgedBox(badge = { Badge() }) { Icon(Icons.Rounded.Campaign, "News") } else Icon(Icons.Rounded.Campaign, "News")
                }
            }
        }

        if (!notifyOn) Banner("Reminders are off on this phone.") { TextButton(onClick = { act.askNotifications() }) { Text("Turn On") } }
        val offer = s.view.breakOffer
        if (offer != null && hideOffer != offer.id) Banner("Nice work. Take a break?") {
            FilledTonalButton(onClick = { repo.launch(op("startBreak", "minutes" to 5)) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("5 min") }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(onClick = { repo.launch(op("startBreak", "minutes" to 10)) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("10 min") }
            TextButton(onClick = { hideOffer = offer.id }) { Text("Not Now") }
        }

        Group("What are you working on?") {
            TextField(
                form.intention, { form.intention = it.take(140); if (it.isEmpty()) form.fromLater = null },
                placeholder = { Text("Write the intro") }, singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge, colors = plainField(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            GroupDivider()
            TextField(
                form.step, { form.step = it.take(140) }, placeholder = { Text("First step (optional)") }, singleLine = true,
                colors = plainField(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { start() }), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (s.view.recent.isNotEmpty() && form.intention.isBlank()) {
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.view.recent.forEach { r ->
                    SuggestionChip(onClick = { form.intention = r }, label = { Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }

        if (s.catsOn) {
            val c = s.cat(s.companion)
            Group("Who's coming") {
                Item(c.name, sub = "grows up while you focus", onClick = { inviting = true }, leading = { LiveCat(c.id, "portrait", 52.dp, shadow = false) }) {
                    Text("Change", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Group("How long", foot = if (mode == "stopwatch") "Counts up, for up to 3 hours. Tap Done when you are; after 10 minutes your kitten comes home." else null) {
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
                Seg(listOf("timer" to "Timer", "stopwatch" to "Stopwatch"), mode) { form.mode = it }
            }
            if (mode == "timer") Box(Modifier.padding(12.dp)) {
                Seg(listOf(5 to "5 min", 25 to "25 min", 50 to "50 min", 0 to "Custom"), form.minutes) { form.minutes = it }
            } else Spacer(Modifier.height(12.dp))
            if (mode == "timer" && form.minutes == 0) {
                GroupDivider()
                Item("Minutes") {
                    OutlinedTextField(
                        form.custom, { v -> form.custom = v.filter { it.isDigit() }.take(3) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                    )
                }
            }
        }

        if (set.tags.isNotEmpty()) Group("Tag") {
            FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                set.tags.forEach { t ->
                    FilterChip(selected = t == tag, onClick = { form.tag = if (t == tag) "" else t }, label = { Text(t) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(tagColor(t, set.tags), CircleShape)) })
                }
            }
        }

        val hint = when {
            set.interval == 0 -> "Just one check-in, at the end."
            style == "nudge" -> "A short line or a quote about every ${set.interval} min. Nothing to answer."
            else -> "\"Still on it?\" every ${set.interval} min."
        }
        Group("Check-ins", foot = hint) {
            Box(Modifier.padding(12.dp)) {
                Seg(listOf("ask" to "Ask me", "nudge" to "Nudge me"), style) { form.style = it }
            }
        }

        Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(56.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text("Start Focus", style = MaterialTheme.typography.titleMedium)
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(fmtMin(s.stats.today), "focused today", Modifier.weight(1f))
            StatTile(if (s.stats.streak == 1) "1 day" else "${s.stats.streak} days", "in a row", Modifier.weight(1f))
        }
        if (set.goal > 0) {
            val done = s.stats.today
            Column(Modifier.fillMaxWidth().padding(top = 14.dp, start = 4.dp, end = 4.dp)) {
                Text(if (done >= set.goal) "Daily goal reached. ${fmtMin(done)} today." else "${fmtMin(done)} of ${fmtMin(set.goal)} today",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { (done.toFloat() / set.goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), strokeCap = StrokeCap.Round, drawStopIndicator = {})
            }
        }
    }
    if (inviting) InviteSheet(s) { inviting = false }
}

@Composable
fun plainField() = TextFieldDefaults.colors(
    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
)

@Composable
fun Banner(text: String, actions: @Composable RowScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            actions()
        }
    }
}

@Composable
fun StatTile(big: String, small: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(big, style = MaterialTheme.typography.titleLarge)
            Text(small, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- a block that's running ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunScreen(s: UiState, a: Block, now: Long, onSound: () -> Unit) {
    val repo = KavanaApp.repo
    val k = LocalK.current
    val paused = a.paused != null
    val at = a.paused ?: now
    val left = a.end - at
    val spent = a.total - left // time on the block, pauses left out
    val over = a.over != null
    // The ring: what's left of a timer; a stopwatch fills it once an hour. `grow`: how grown the kitten is.
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
    val c = s.cat(a.cat)
    var parking by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var awake by remember { mutableStateOf(repo.prefs.getBoolean("awake", true)) }
    val view = LocalView.current
    DisposableEffect(awake, paused) {
        view.keepScreenOn = awake && !paused
        onDispose { view.keepScreenOn = false }
    }
    // The first 10 seconds: Cancel takes it back, no harm done.
    val grace = ((a.cancelUntil - now + 999) / 1000).toInt()
    fun stop() {
        if (grace > 0) repo.launch(op("cancel")) { out -> if (out?.error == null) repo.toasts.tryEmit("Cancelled. Nothing counted.") else repo.toasts.tryEmit("Too late to cancel. Use Stop.") }
        else stopping = true
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow(when { paused -> "Paused"; over -> "Extra time"; a.stopwatch -> "Stopwatch"; else -> "Focusing" },
            color = if (paused) k.orange else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(a.intention, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (a.step != null) Text(
            buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("First step: ") }; append(a.step) },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (a.tag != null) TagChip(a.tag, s.view.settings.tags, Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(18.dp))
        // The ring takes about a third of the screen's height, so Stop and Pause stay in view on a short phone.
        val ring = (LocalConfiguration.current.screenHeightDp * 0.3f).dp.coerceIn(200.dp, 264.dp)
        Dial(frac, paused, ring) {
            if (s.catsOn) Kitten(a, grow, paused, ring * 0.76f)
            else Text(clock, style = MaterialTheme.typography.displayMedium.merge(tabular))
        }
        if (s.catsOn) Text(clock, style = MaterialTheme.typography.displayMedium.merge(tabular), modifier = Modifier.padding(top = 10.dp))
        Text(when { paused -> "Paused"; a.stopwatch -> "Started at ${clockTime(a.start)}"; over -> "Ended at ${clockTime(a.over!!)}"; else -> "Ends at ${clockTime(a.end)}" },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.catsOn) Text(kittenSays(a, c, paused, grow, spent), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = if (a.kitten == "gone") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp))
        val meta = buildList {
            when {
                paused -> add("Starts again by itself in ${fmtClock((a.pauseEnds ?: now) - now)}")
                over -> add("Kavana counts until you tap Done")
                a.interval == 0 -> add("Check-in at the end")
                a.style == "nudge" -> add("Nudges about every ${a.interval} min")
                a.next != null && a.next > now -> add("Next check-in in ${minLeft(a.next - now)} min")
            }
            if (a.drifts > 0) add("Drifted ${times(a.drifts)}")
        }.joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        if (a.line != null && !paused && a.style == "nudge") Text(a.line, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))

        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            RoundButton(when { grace > 0 -> "Cancel"; a.stopwatch || over -> "Done"; else -> "Stop" }, if (grace <= 0 && (a.stopwatch || over)) k.green else k.gray,
                sub = if (grace > 0) "$grace s" else null) { stop() }
            if (s.canPause || paused) RoundButton(if (paused) "Resume" else "Pause", if (paused) k.green else k.orange) { repo.launch(op(if (paused) "resume" else "pause")) }
        }
        FlowRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            AssistChip(onClick = { parking = true }, label = { Text(if (a.parked.isEmpty()) "Park a thought" else "Parked (${a.parked.size})") },
                leadingIcon = { Icon(Icons.Rounded.EditNote, null, Modifier.size(18.dp)) })
            if (!paused) FilterChip(selected = SoundMix.playable(s).isNotEmpty(), onClick = onSound, label = { Text(SoundMix.label(s)) },
                leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(18.dp)) })
            if (!paused) FilterChip(selected = awake, onClick = { awake = !awake; repo.prefs.edit().putBoolean("awake", awake).apply() },
                label = { Text(if (awake) "Screen stays on" else "Screen on") }, leadingIcon = { Icon(Icons.Rounded.LightMode, null, Modifier.size(18.dp)) })
        }
    }

    if (stopping) {
        val enough = spent >= 10 * 60_000L
        val (title, text, yes, danger) = when {
            a.stopwatch -> listOf("Done?", if (!s.catsOn) "You can still say how it went." else if (enough) "${c.name} comes home with you." else "${c.name} needs 10 minutes to come home. Stop now and it wanders off.", "Done", if (!enough && s.catsOn) "y" else "")
            over -> listOf("Done?", "Your extra time counts too.", "Done", "")
            else -> listOf("Stop This Block?", (if (s.catsOn && a.kitten == "growing") "${c.name} will wander off. " else "") + "You can still say how it went.", "Stop", "y")
        }
        AlertDialog(
            onDismissRequest = { stopping = false },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { stopping = false; repo.launch(op("stop")) }) { Text(yes, color = if (danger.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
            dismissButton = { TextButton(onClick = { stopping = false }) { Text("Cancel") } },
        )
    }
    if (parking) ParkSheet { parking = false }
}

/** The ring: what's left of the block, like the Clock app's timer. */
@Composable
fun Dial(frac: Float, paused: Boolean, size: Dp = 264.dp, content: @Composable BoxScope.() -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val prog = if (paused) LocalK.current.orange else MaterialTheme.colorScheme.primary
    val shown by animateFloatAsState(frac, tween(900, easing = LinearEasing), label = "ring")
    Box(
        Modifier.size(size).drawBehind {
            val sw = 14.dp.toPx()
            drawCircle(track, radius = this.size.minDimension / 2 - sw / 2, style = Stroke(sw))
            drawArc(prog, -90f, 360f * shown, false, topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
        },
        contentAlignment = Alignment.Center, content = content,
    )
}

/**
 * The kitten in the ring, alive: napping when it arrives, a kitten in the middle, grown in the last
 * quarter, a little bigger every second. Each stage pops into the next. Home: it hops for joy. Gone: paw prints.
 * Tap it, hold it (it purrs).
 */
@Composable
fun Kitten(a: Block, p: Float, paused: Boolean, size: Dp) {
    val stage = if (a.kitten != "growing") a.kitten else if (p < .25f) "nap" else if (p < .75f) "kitten" else "grown"
    val grow by animateFloatAsState(growSize(stage, p), spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow), label = "grow")
    if (stage == "gone") { CatArt(Art.GONE, size, Modifier.graphicsLayer { scaleX = grow; scaleY = grow }); return }
    var cat by remember { mutableStateOf<LivingCat?>(null) }
    var last by remember { mutableStateOf(stage) }
    LaunchedEffect(stage) {
        if (stage == "home" && last != "home") cat?.let { it.hop(2); it.happy(1.3f); it.heart() }
        last = stage
    }
    LiveCat(
        a.cat, when (stage) { "nap" -> "nap"; "kitten" -> "kitten"; else -> "portrait" }, size,
        Modifier.graphicsLayer { scaleX = grow; scaleY = grow; transformOrigin = TransformOrigin(0.5f, 0.96f) },
        interactive = true, alpha = if (paused) .55f else 1f, label = "Your kitten. Tap it, or hold it to make it purr.", onCat = { cat = it },
    )
}

// web/app.js growSize
fun growSize(stage: String, p: Float): Float = when (stage) {
    "nap" -> 0.55f + 0.13f * (p / 0.25f)
    "kitten" -> 0.68f + 0.18f * ((p - 0.25f) / 0.5f)
    "grown" -> 0.86f + 0.14f * ((p - 0.75f) / 0.25f)
    "home" -> 1f
    else -> 0.8f
}

// web/app.js drawKitten's lines (napping together isn't in the app yet)
fun kittenSays(a: Block, c: CatInfo, paused: Boolean, p: Float, spent: Long): String {
    val stage = if (a.kitten != "growing") a.kitten else if (p < .25f) "nap" else if (p < .75f) "kitten" else "grown"
    return when {
        stage == "gone" && a.goneWhy == "away" -> "${c.name} wandered off while you were away. Your block keeps going."
        stage == "gone" -> "${c.name} wandered off."
        stage == "home" -> if (a.over != null) "${c.name} came home. Every extra minute is another treat." else "${c.name} came home. Keep going for more treats."
        paused -> "${c.name} waits for you. A pause lasts 5 minutes at most."
        a.stopwatch -> if (spent < 600_000L) "Stay 10 minutes and ${c.name} can come home." else "${c.name} can come home now. Tap Done when you're done."
        stage == "grown" -> "Almost there. ${c.name} is nearly grown up."
        stage == "nap" -> "${c.name} just arrived. Stay in Kavana and it grows."
        else -> "${c.name} is growing. Stay with it."
    }
}

private val TAG_COLORS = listOf(0xFFFF9500, 0xFF007AFF, 0xFF34C759, 0xFFAF52DE, 0xFFFF2D55, 0xFF00C7BE, 0xFF5856D6, 0xFFFFCC00, 0xFFA2845E, 0xFF30B0C7, 0xFFFF3B30, 0xFF64D2FF).map { Color(it) }

/** A tag's colour (web/app.js tagColor): by its place in your tags, so the web and the phone agree. */
fun tagColor(t: String, tags: List<String>): Color {
    val i = tags.indexOf(t)
    return TAG_COLORS[(if (i >= 0) i else t.sumOf { it.code }) % TAG_COLORS.size]
}

@Composable
fun TagChip(t: String, tags: List<String>, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(tagColor(t, tags), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(t, style = MaterialTheme.typography.labelMedium)
    }
}

// ---------- a break ----------

@Composable
fun BreakScreen(b: Brk, now: Long) {
    val left = b.end - now
    val frac = (left.toFloat() / (b.end - b.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Eyebrow("Break", color = LocalK.current.mint)
        Spacer(Modifier.height(6.dp))
        Text("Stand up, stretch, get some water.", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text("Look at something far away for a bit.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
        Dial(frac, false) { Text(fmtClock(left), style = MaterialTheme.typography.displayMedium.merge(tabular)) }
        Spacer(Modifier.height(28.dp))
        RoundButton("End", LocalK.current.gray) { KavanaApp.repo.launch(op("stopBreak")) }
    }
}
