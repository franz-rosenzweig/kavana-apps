package xyz.tiresia.kavana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import xyz.tiresia.kavana.*

// A mindful minute (Forest's Mindful Space): box breathing, four seconds in, hold, out, hold, and your
// cat breathes with you. Breathing once counts for the starter challenge (tally "breathes").

private val WORDS = listOf("Breathe in", "Hold", "Breathe out", "Hold")

@Composable
fun BreatheScreen(s: UiState, onSound: () -> Unit, onStart: () -> Unit, onLeave: () -> Unit) {
    val repo = KavanaApp.repo
    var minutes by rememberSaveable { mutableIntStateOf(1) }
    var run by rememberSaveable { mutableIntStateOf(0) } // a new run each time the length changes
    var done by rememberSaveable { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    var phase by remember { mutableFloatStateOf(0f) } // 0..1 within the step
    var cat by remember { mutableStateOf<LivingCat?>(null) }
    val steps = maxOf(4, Math.round(minutes * 60 / 16f)) * 4 // four seconds a step, four steps a round
    LaunchedEffect(run, minutes) {
        done = false
        val start = System.nanoTime()
        while (isActive) {
            withFrameNanos { }
            val t = (System.nanoTime() - start) / 1e9f
            val n = (t / 4f).toInt()
            if (n >= steps) {
                done = true
                cat?.guide = null
                cat?.let { it.happy(1.2f); it.heart() }
                repo.launch(op("count", "key" to "breathes"))
                break
            }
            step = n; phase = (t / 4f) - n
            // the breath: rising, full, falling, empty
            val e = phase * phase * (3 - 2 * phase)
            cat?.guide = when (n % 4) { 0 -> -0.5f + e; 1 -> 0.5f; 2 -> 0.5f - e; else -> -0.5f }
        }
    }
    DisposableEffect(Unit) {
        SoundMix.breathing = true
        onDispose { cat?.guide = null; SoundMix.breathing = false; if (KavanaApp.repo.state.value?.view?.active?.paused != null || KavanaApp.repo.state.value?.view?.active == null) Sound.stop() }
    }
    val glow = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow("A mindful minute", color = MaterialTheme.colorScheme.primary)
        Seg(listOf(1 to "1 min", 3 to "3 min", 5 to "5 min"), minutes, Modifier.padding(top = 12.dp)) { minutes = it; run++ }
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(260.dp).drawBehind {
                // a soft glow that swells with each breath in (read here, so only the drawing follows the frames)
                val e = phase * phase * (3 - 2 * phase)
                val g = if (done) 0f else when (step % 4) { 0 -> -0.5f + e; 1 -> 0.5f; 2 -> 0.5f - e; else -> -0.5f }
                val r = size.minDimension / 2 * (0.62f + 0.3f * (g + 0.5f))
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = .28f), glow.copy(alpha = 0f)), Offset(size.width / 2, size.height / 2), r), r)
            },
            contentAlignment = Alignment.Center,
        ) {
            LiveCat(s.companion, "portrait", 190.dp, interactive = true, onCat = { cat = it })
        }
        if (!done) {
            Text(WORDS[step % 4], style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 12.dp))
            Text("Four seconds in, hold, out, hold. ${steps / 4} rounds.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            LinearProgressIndicator(progress = { (step + phase) / steps }, Modifier.fillMaxWidth(0.6f).padding(top = 18.dp), drawStopIndicator = {})
            Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                RoundButton("Leave", LocalK.current.gray) { onLeave() }
                RoundButton(if (SoundMix.playable(s).isEmpty()) "Sound" else "Sound on", LocalK.current.green) { onSound() }
            }
        } else {
            Text("Do you feel calmer?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
            Text("What's next?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(54.dp)) { Text("Start Focusing") }
            TextButton(onClick = { run++ }) { Text("Again") }
            TextButton(onClick = onLeave) { Text("Home") }
        }
    }
}

/** What's new in Kavana (web/news.js), newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsSheet(s: UiState, onGo: (String?) -> Unit, onDone: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("News", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
            s.news.forEach { n ->
                Group(java.time.LocalDate.parse(n.date).format(java.time.format.DateTimeFormatter.ofPattern("MMMM d"))) {
                    Column(Modifier.padding(16.dp)) {
                        Text(n.title, style = MaterialTheme.typography.titleMedium)
                        Text(n.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        if (n.go != null) TextButton(onClick = { onGo(n.go); onDone() }, contentPadding = PaddingValues(0.dp)) { Text("Take Me There") }
                    }
                }
            }
        }
    }
}
