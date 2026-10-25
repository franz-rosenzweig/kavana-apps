package xyz.tiresia.kavana.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.update
import xyz.tiresia.kavana.*

// The sheets that come up over the tabs: the check-in, "How did it go?", a cat met, who's coming, park.

private val WHYS = listOf(
    "phone" to ("📱" to "Phone"), "messages" to ("💬" to "Messages"), "email" to ("✉️" to "Email"),
    "web" to ("🌐" to "The web"), "thoughts" to ("💭" to "Thoughts"), "people" to ("🙋" to "Someone"),
    "tired" to ("😴" to "Tired"), "hungry" to ("🍎" to "Hungry"), "other" to ("✨" to "Other"),
)

@Composable
fun Sheets(s: UiState, act: MainActivity, wall: Wall, form: StartForm) {
    val repo = KavanaApp.repo
    val a = s.view.active
    var hidden by remember { mutableStateOf<String?>(null) } // a check-in put away with a swipe ("id:n")
    var why by remember { mutableStateOf<Pair<String, Int>?>(null) } // after Drifted: what pulled you away?
    val pending = a?.takeIf { it.pending != null && it.paused == null }
    val key = pending?.let { "${it.id}:${it.pending}" }
    if (why != null || (pending != null && hidden != key)) {
        CheckinSheet(s, pending ?: a, why,
            onAnswer = { ans ->
                val b = pending ?: return@CheckinSheet
                repo.launch(op("answer", "id" to b.id, "n" to b.pending, "answer" to ans))
                if (ans == "drifted") why = b.id to (b.pending ?: 0)
            },
            onWhy = { w ->
                val (id, n) = why ?: return@CheckinSheet
                if (w != null) repo.launch(op("answer", "id" to id, "n" to n, "answer" to "drifted", "why" to w))
                why = null
            },
            onHide = { hidden = key; why = null },
        )
    }
    s.view.awaiting?.let { OutcomeSheet(s, it, act, wall) }
    val meets by repo.meets.collectAsStateWithLifecycle()
    if (s.view.awaiting == null && pending == null && why == null) meets.firstOrNull()?.let { id ->
        MeetSheet(s, s.cat(id)) { repo.meets.update { it.drop(1) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CheckinSheet(s: UiState, a: Block?, why: Pair<String, Int>?, onAnswer: (String) -> Unit, onWhy: (String?) -> Unit, onHide: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onHide, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.catsOn && a != null) LiveCat(a.cat, "portrait", 96.dp, interactive = true, onCat = { it.tilt(Motion.HEAD_TILT, 1.6f) })
            if (why == null) {
                Eyebrow("Check-in")
                Text(if (a == null || a.intention.isBlank()) "Still focusing?" else "Still on: ${a.intention}?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Choice("👍", "Yes", Modifier.weight(1f), strong = true) { onAnswer("yes") }
                    Choice("🌀", "Drifted", Modifier.weight(1f)) { onAnswer("drifted") }
                }
            } else {
                Eyebrow("No worries")
                Text("What pulled you away?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                FlowRow(maxItemsInEachRow = 3, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WHYS.forEach { (id, e) -> Choice(e.first, e.second, Modifier.weight(1f)) { onWhy(id) } }
                }
                TextButton(onClick = { onWhy(null) }, modifier = Modifier.padding(top = 8.dp)) { Text("Skip") }
            }
        }
    }
}

/**
 * The end of a block. When the kitten came home, the homecoming: today's café comes up behind the card,
 * the cat drops onto its spot and its treats float up, and after two seconds (or a tap) the card comes up.
 * Otherwise "How did it go?" as a sheet. Again starts the same block in one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutcomeSheet(s: UiState, w: Block, act: MainActivity, wall: Wall) {
    val repo = KavanaApp.repo
    var note by remember(w.id) { mutableStateOf(w.note) }
    fun out(o: String?) {
        // A note (like Forest's session notes) goes on the block first, then the answer.
        if (note.trim() != w.note) repo.launch(op("edit", "id" to w.id, "patch" to mapOf("note" to note)))
        repo.launch(op("outcome", "id" to w.id, "outcome" to o)) { Notify.cancel(act, Notify.ID_PING) }
    }
    // Again: file this one without an answer and start the same block, with nothing in between.
    fun again() {
        out(null)
        repo.launch(op("start",
            "intention" to w.intention, "step" to (w.step ?: ""), "minutes" to (if (w.stopwatch) 180 else w.minutes), "style" to w.style,
            "mode" to (if (w.stopwatch) "stopwatch" else "timer"), "tag" to (w.tag ?: ""), "cat" to (if (s.catsOn) s.companion else null),
        ))
    }
    val h = s.history.firstOrNull { it.id == w.id }
    if (s.catsOn && w.kitten == "home" && h != null) {
        Homecoming(s, w, h, wall) { OutcomeBody(s, w, act, note, { note = it }, showCat = false, onOut = ::out, onAgain = ::again) }
        return
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = {}, sheetState = state) {
        OutcomeBody(s, w, act, note, { note = it }, showCat = true, onOut = ::out, onAgain = ::again)
    }
}

@Composable
private fun Homecoming(s: UiState, w: Block, h: Hist, wall: Wall, body: @Composable () -> Unit) {
    var arriving by remember(w.id) { mutableStateOf(true) }
    LaunchedEffect(w.id) { kotlinx.coroutines.delay(2300); arriving = false }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scene = remember(w.id) {
        val today = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        cafeScene(s, s.history.filter { it.start >= today && it.id != w.id }, "day", cafeHash("home$today"), dark, quiet = false)
    }
    val arrival = remember(w.id) { CafeArrival(cafeCat(s, h).copy(title = "${s.cat(w.cat).name} came home"), "🐟 +${w.treats + w.bonus}") }
    androidx.activity.compose.BackHandler { arriving = false }
    Box(
        Modifier.fillMaxSize().wall(wall)
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { arriving = false },
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            CafeView(scene, cafeNames(s), Modifier.fillMaxWidth().weight(1f).padding(top = 14.dp), arrival = arrival, margin = 6f)
            androidx.compose.animation.AnimatedVisibility(
                !arriving,
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            ) {
                Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.7f).dp)) {
                    body()
                }
            }
        }
    }
}

@Composable
private fun OutcomeBody(s: UiState, w: Block, act: MainActivity, note: String, onNote: (String) -> Unit, showCat: Boolean, onOut: (String?) -> Unit, onAgain: () -> Unit) {
    val repo = KavanaApp.repo
    val c = s.cat(w.cat)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = if (showCat) 0.dp else 20.dp, bottom = 24.dp).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Eyebrow(when { w.stopwatch -> if (w.stopped) "Stopwatch, under 10 minutes" else "Stopwatch"; w.over != null -> "Extra time"; w.stopped -> "Stopped early"; else -> "Time's up" }, color = MaterialTheme.colorScheme.primary)
        Text("How did it go?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
        // no title: its tag, or nothing (the title is optional)
        w.intention.ifBlank { w.tag ?: "" }.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        if (s.catsOn) {
            if (showCat) {
                if (w.kitten == "home") LiveCat(c.id, "portrait", 132.dp, Modifier.padding(top = 10.dp), interactive = true, onCat = { it.hop(2); it.happy(1.2f); it.heart() })
                else CatArt(Art.GONE, 132.dp, Modifier.padding(top = 10.dp))
            }
            Text(
                when {
                    w.kitten == "home" -> "${c.name} came home to your café. 🐟 +${w.treats + w.bonus}"
                    w.goneWhy == "away" -> "${c.name} wandered off while you were away."
                    w.goneWhy == "short" -> "${c.name} needs 10 minutes to come home."
                    else -> "${c.name} wandered off. Next time, stay to the end."
                }, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
                color = if (w.kitten == "home") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice("🎉", "Done", Modifier.weight(1f), strong = true) { onOut("done") }
            Choice("🌗", "Partly", Modifier.weight(1f)) { onOut("partly") }
            Choice("🌧️", "Not really", Modifier.weight(1f)) { onOut("notreally") }
        }
        if (!w.stopped && !w.stopwatch && w.over == null) {
            val more = if (w.minutes <= 10) listOf(10, 20) else listOf(5, 10) // after "Just start", offer a real block
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("In the flow?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                more.forEach { m -> FilledTonalButton(onClick = { repo.launch(op("extend", "id" to w.id, "more" to m)) }) { Text("+$m min") } }
            }
        }
        OutlinedTextField(
            note, { onNote(it.take(500)) }, placeholder = { Text("Add a note") }, minLines = 1, maxLines = 4,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        if (w.parked.isNotEmpty()) {
            Group("You parked") {
                w.parked.forEachIndexed { i, t -> if (i > 0) GroupDivider(); Text(t, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            }
            TextButton(onClick = { act.copy(w.parked.joinToString("\n")); repo.toasts.tryEmit("Copied.") }) { Text("Copy Them") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { onOut(null) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Skip") }
            Button(onClick = onAgain, modifier = Modifier.weight(1f).height(48.dp)) { Text("Again") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetSheet(s: UiState, c: CatInfo, onDone: () -> Unit) {
    val repo = KavanaApp.repo
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Eyebrow("New friend")
            Text("You met ${c.name}!", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
            LiveCat(c.id, "portrait", 180.dp, Modifier.padding(vertical = 8.dp), interactive = true, onCat = { it.hop(1); it.happy(1f); it.sparkle() })
            Text(c.about, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { repo.launch(settings("companion" to c.id)); onDone() }, modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp)) {
                Text("Make ${c.name} My Companion")
            }
            TextButton(onClick = onDone) { Text("Nice!") }
        }
    }
}

/** Who's coming: the cats you've met; the one picked grows in the next block. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InviteSheet(s: UiState, onDone: () -> Unit) {
    val repo = KavanaApp.repo
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Who's Coming?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
            FlowRow(Modifier.padding(top = 14.dp), maxItemsInEachRow = 3, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                s.cats.filter { it.met }.forEach { c ->
                    val on = c.id == s.companion
                    Surface(
                        shape = RoundedCornerShape(18.dp), color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (on) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.weight(1f).clickable { repo.launch(settings("companion" to c.id)); onDone() },
                    ) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            LiveCat(c.id, "portrait", 72.dp, shadow = false)
                            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        }
                    }
                }
            }
            Text("Meet more cats by focusing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParkSheet(onDone: () -> Unit) {
    val repo = KavanaApp.repo
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun park() {
        if (text.isBlank()) return
        repo.launch(op("park", "text" to text))
        onDone()
    }
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone) { Text("Cancel") }
                Text("Park a Thought", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                TextButton(onClick = { park() }, enabled = text.isNotBlank()) { Text("Park") }
            }
            OutlinedTextField(
                text, { text = it.take(200) }, placeholder = { Text("Reply to that email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { park() }),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
            )
            Text("Write it down so it stops pulling at you. It goes on your Later list.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
