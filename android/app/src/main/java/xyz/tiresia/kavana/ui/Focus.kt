package xyz.tiresia.kavana.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PlayArrow
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
fun FocusTab(s: UiState, now: Long, form: StartForm, act: MainActivity) {
    val a = s.view.active
    val brk = s.view.onBreak
    when {
        a != null -> RunScreen(s, a, now)
        brk != null -> BreakScreen(brk, now)
        else -> HomeScreen(s, form, act)
    }
}

fun clockTime(t: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))

private val tabular = TextStyle(fontFeatureSettings = "tnum")

// ---------- the start screen ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(s: UiState, form: StartForm, act: MainActivity) {
    val repo = KavanaApp.repo
    val resumes by act.resumes
    val notifyOn = remember(resumes) { act.notificationsOn() }
    var hideOffer by rememberSaveable { mutableStateOf<String?>(null) }
    var inviting by remember { mutableStateOf(false) }
    val style = form.style ?: s.view.settings.style
    val focus = remember { FocusRequester() }
    val set = s.view.settings

    fun start() {
        val minutes = if (form.minutes == 0) form.custom.toIntOrNull() ?: 0 else form.minutes
        if (form.intention.isBlank()) { runCatching { focus.requestFocus() }; repo.toasts.tryEmit("Say what you're working on first."); return }
        if (minutes !in 1..240) { repo.toasts.tryEmit("Pick between 1 and 240 minutes."); return }
        if (!act.notificationsOn() && !repo.prefs.getBoolean("askedNotify", false)) act.askNotifications()
        repo.launch(op("start",
            "intention" to form.intention, "step" to form.step, "minutes" to minutes, "style" to style,
            "fromLater" to form.fromLater, "cat" to (if (s.catsOn) s.companion else null),
        )) { out -> if (out != null && out.error == null) { form.intention = ""; form.step = ""; form.fromLater = null } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 16.dp))
        LargeTitle("Focus")

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
                Item(c.name, sub = "grows up while you focus", onClick = { inviting = true }, leading = { CatArt(Art.portrait(c.id), 44.dp) }) {
                    Text("Change", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Group("How long") {
            Box(Modifier.padding(12.dp)) {
                Seg(listOf(5 to "5 min", 25 to "25 min", 50 to "50 min", 0 to "Custom"), form.minutes) { form.minutes = it }
            }
            if (form.minutes == 0) {
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

@Composable
fun RunScreen(s: UiState, a: Block, now: Long) {
    val repo = KavanaApp.repo
    val k = LocalK.current
    val paused = a.paused != null
    val left = a.end - (a.paused ?: now)
    val frac = (left.toFloat() / a.total.coerceAtLeast(1)).coerceIn(0f, 1f)
    val c = s.cat(a.cat)
    var parking by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var awake by remember { mutableStateOf(repo.prefs.getBoolean("awake", true)) }
    val view = LocalView.current
    DisposableEffect(awake, paused) {
        view.keepScreenOn = awake && !paused
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow(if (paused) "Paused" else "Focusing", color = if (paused) k.orange else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(a.intention, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (a.step != null) Text(
            buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("First step: ") }; append(a.step) },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(18.dp))
        // The ring takes about a third of the screen's height, so Stop and Pause stay in view on a short phone.
        val ring = (LocalConfiguration.current.screenHeightDp * 0.3f).dp.coerceIn(200.dp, 264.dp)
        Dial(frac, paused, ring) {
            if (s.catsOn) Kitten(a, 1f - frac, paused, ring * 0.76f)
            else Text(fmtClock(left), style = MaterialTheme.typography.displayMedium.merge(tabular))
        }
        if (s.catsOn) Text(fmtClock(left), style = MaterialTheme.typography.displayMedium.merge(tabular), modifier = Modifier.padding(top = 10.dp))
        Text(if (paused) "Paused" else "Ends at ${clockTime(a.end)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.catsOn) Text(kittenSays(a, c, paused, 1f - frac), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = if (a.kitten == "gone") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp))
        val meta = buildList {
            when {
                paused -> add("The clock and the check-ins wait for you")
                a.interval == 0 -> add("Check-in at the end")
                a.style == "nudge" -> add("Nudges about every ${a.interval} min")
                a.next != null && a.next > now -> add("Next check-in in ${minLeft(a.next - now)} min")
            }
            if (a.drifts > 0) add("Drifted ${times(a.drifts)}")
        }.joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        if (a.line != null && !paused && a.style == "nudge") Text(a.line, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))

        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            RoundButton("Stop", k.gray) { stopping = true }
            RoundButton(if (paused) "Resume" else "Pause", if (paused) k.green else k.orange) { repo.launch(op(if (paused) "resume" else "pause")) }
        }
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AssistChip(onClick = { parking = true }, label = { Text(if (a.parked.isEmpty()) "Park a thought" else "Parked (${a.parked.size})") },
                leadingIcon = { Icon(Icons.Rounded.EditNote, null, Modifier.size(18.dp)) })
            if (!paused) FilterChip(selected = awake, onClick = { awake = !awake; repo.prefs.edit().putBoolean("awake", awake).apply() },
                label = { Text(if (awake) "Screen stays on" else "Screen on") }, leadingIcon = { Icon(Icons.Rounded.LightMode, null, Modifier.size(18.dp)) })
        }
    }

    if (stopping) AlertDialog(
        onDismissRequest = { stopping = false },
        title = { Text("Stop This Block?") },
        text = { Text((if (s.catsOn && a.kitten == "growing") "${c.name} will wander off. " else "") + "You can still say how it went.") },
        confirmButton = { TextButton(onClick = { stopping = false; repo.launch(op("stop")) }) { Text("Stop", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { stopping = false }) { Text("Cancel") } },
    )
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

/** The kitten in the ring: napping when it arrives, a kitten in the middle, grown in the last quarter. */
@Composable
fun Kitten(a: Block, p: Float, paused: Boolean, size: Dp) {
    val stage = if (a.kitten != "growing") a.kitten else if (p < .25f) "nap" else if (p < .75f) "kitten" else "grown"
    val path = when (stage) { "gone" -> Art.GONE; "nap" -> Art.nap(a.cat); "kitten" -> Art.kitten(a.cat); else -> Art.portrait(a.cat) }
    val grow by animateFloatAsState(growSize(stage, p), tween(800), label = "grow")
    Crossfade(path, label = "kitten") { pic ->
        CatArt(pic, size, Modifier.graphicsLayer { scaleX = grow; scaleY = grow }, alpha = if (paused) .55f else 1f)
    }
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
fun kittenSays(a: Block, c: CatInfo, paused: Boolean, p: Float): String {
    val stage = if (a.kitten != "growing") a.kitten else if (p < .25f) "nap" else if (p < .75f) "kitten" else "grown"
    return when {
        stage == "gone" && a.goneWhy == "away" -> "${c.name} wandered off while you were away. Your block keeps going."
        stage == "gone" -> "${c.name} wandered off."
        stage == "home" -> "${c.name} came home. Keep going for more treats."
        paused -> "${c.name} waits for you."
        stage == "grown" -> "Almost there. ${c.name} is nearly grown up."
        stage == "nap" -> "${c.name} just arrived. Stay in Kavana and it grows."
        else -> "${c.name} is growing. Stay with it."
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
