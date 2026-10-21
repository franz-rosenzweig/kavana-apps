package xyz.tiresia.kavana.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.tiresia.kavana.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.TimeZone

// ---------- Later ----------

@Composable
fun LaterTab(s: UiState, onStart: (LaterItem) -> Unit) {
    val repo = KavanaApp.repo
    var text by remember { mutableStateOf("") }
    fun add() { if (text.isNotBlank()) { repo.launch(op("addLater", "text" to text)); text = "" } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 28.dp)) {
        LargeTitle("Later")
        Lede("Things to get to, and thoughts you parked during a block.")
        Group {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Icon(Icons.Rounded.AddCircle, null, tint = MaterialTheme.colorScheme.primary)
                TextField(text, { text = it.take(200) }, placeholder = { Text("New item") }, singleLine = true, colors = plainField(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }), modifier = Modifier.weight(1f))
                if (text.isNotBlank()) TextButton(onClick = { add() }) { Text("Add") }
            }
            s.view.later.forEach { x ->
                GroupDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 8.dp)) {
                    IconButton(onClick = { repo.launch(op("doneLater", "id" to x.id)) }) {
                        Icon(Icons.Rounded.RadioButtonUnchecked, "Done", tint = MaterialTheme.colorScheme.outline)
                    }
                    Text(x.text, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    TextButton(onClick = { onStart(x) }) { Text("Start") }
                }
            }
        }
        if (s.view.later.isEmpty()) Empty(Art.nap(s.companion), "Nothing here yet.")
    }
}

// ---------- Café: what came home, and your history ----------

private fun dayLabel(t: Long): String {
    val d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEEE, MMMM d" else "MMMM d, yyyy"))
    }
}

private val OUTCOMES = mapOf("done" to "Done 🎉", "partly" to "Partly 🌗", "notreally" to "Not really 🌧️")

@Composable
fun CafeTab(s: UiState) {
    val repo = KavanaApp.repo
    var clearing by remember { mutableStateOf(false) }
    val st = s.stats
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 28.dp)) {
        LargeTitle("Café") { TreatsChip(s.treats.left) }
        val home = s.history.count { it.kitten == "home" }
        if (s.catsOn && s.history.isNotEmpty()) Lede(if (home == 1) "1 cat came home to your café." else "$home cats came home to your café.")
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(fmtMin(st.today), "today", Modifier.weight(1f))
            StatTile(fmtMin(st.week), "last 7 days", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${st.streak}", if (st.streak == 1) "day in a row" else "days in a row", Modifier.weight(1f))
            StatTile(if (st.answered > 0) "${Math.round(100.0 * (st.answered - st.drifted) / st.answered)}%" else "–", "check-ins on track", Modifier.weight(1f))
        }
        if (s.history.isEmpty()) Empty(Art.nap(s.companion), "No blocks yet. Your first one shows up here.")
        s.history.groupBy { dayLabel(it.start) }.forEach { (day, list) ->
            Group(day) {
                list.forEachIndexed { i, b ->
                    if (i > 0) GroupDivider()
                    val c = s.cat(b.cat)
                    Item(
                        b.intention,
                        sub = listOfNotNull(
                            fmtMin(b.actual), b.outcome?.let { OUTCOMES[it] }, if (b.stopped) "Stopped early" else null,
                            if (b.drifts > 0) "Drifted ${times(b.drifts)}" else null,
                        ).joinToString(" · "),
                        leading = if (s.catsOn) ({ CatArt(if (b.kitten == "home") Art.portrait(c.id) else Art.GONE, 44.dp) }) else null,
                    ) {
                        if (b.treats > 0) Text("+${b.treats} 🐟", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (s.history.isNotEmpty()) TextButton(onClick = { clearing = true }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Clear My History", color = MaterialTheme.colorScheme.error)
        }
    }
    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        title = { Text("Clear My History?") },
        text = { Text("Every finished block goes, on this phone. Your treats stay.") },
        confirmButton = { TextButton(onClick = { clearing = false; repo.launch(op("clearHistory")) }) { Text("Clear", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } },
    )
}

// ---------- Cats ----------

@Composable
fun CatsTab(s: UiState) {
    val repo = KavanaApp.repo
    val shown = s.cats.filter { !it.adopt || it.met }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 28.dp)) {
        LargeTitle("Cats") { TreatsChip(s.treats.left) }
        Lede("Meet them by focusing. Tap one you've met to bring it to your next block.")
        shown.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { c ->
                    val coming = c.id == s.companion
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (coming) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.weight(1f).then(if (c.met && !coming) Modifier.clickable {
                            repo.launch(settings("companion" to c.id)); repo.toasts.tryEmit("${c.name} comes to your next block.")
                        } else Modifier),
                    ) {
                        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CatArt(Art.portrait(c.id), 112.dp, locked = !c.met)
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (c.met) c.about else c.how ?: "", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (coming) Text("Coming next", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------- Settings ----------

private val GOALS = listOf(0 to "Off", 30 to "30 min", 60 to "1 hour", 90 to "1½ hours", 120 to "2 hours", 180 to "3 hours")
private val INTERVALS = listOf(10 to "10 min", 15 to "15 min", 20 to "20 min", 30 to "30 min", 45 to "45 min", 60 to "60 min", 0 to "Only at the end")
private val DAYS = listOf("S", "M", "T", "W", "T", "F", "S")

@Composable
fun SettingsTab(s: UiState, act: MainActivity, onTab: (Tab) -> Unit) {
    val repo = KavanaApp.repo
    val ctx = LocalContext.current
    val k = LocalK.current
    val set = s.view.settings
    val resumes by act.resumes
    val notifyOn = remember(resumes) { act.notificationsOn() }
    val overlayOn = remember(resumes) { act.overlayOn() }
    var awake by remember { mutableStateOf(repo.prefs.getBoolean("awake", true)) }
    fun daily(d: DailySet) = repo.launch(settings("daily" to mapOf("on" to d.on, "time" to d.time, "days" to d.days), "tz" to TimeZone.getDefault().id))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 28.dp)) {
        LargeTitle("Settings")
        Group("Focus", foot = "Your kitten grows while you focus. Stop early, or leave Kavana for more than a few seconds, and it wanders off. Locking your phone is fine.") {
            Pick("Daily goal", Icons.Rounded.Flag, MaterialTheme.colorScheme.primary, GOALS, set.goal) { repo.launch(settings("goal" to it)) }
            GroupDivider()
            Pick("Check in every", Icons.Rounded.Notifications, k.orange, INTERVALS, set.interval) { repo.launch(settings("interval" to it)) }
            GroupDivider()
            Item("Cat mode", Icons.Rounded.Pets, k.pink) { Switch(set.cat, { repo.launch(settings("cat" to it)) }) }
            if (set.cat) {
                GroupDivider()
                Item("Next kitten", Icons.Rounded.Favorite, k.pink, onClick = { onTab(Tab.Cats) }) {
                    Text(s.cat(s.companion).name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (set.cat) Group("The cat over other apps", foot = "When you leave Kavana in the middle of a block, or a check-in comes while you're in another app, the cat pops up over it and says Boo!") {
            Item("Cat over other apps", Icons.Rounded.Layers, k.indigo, sub = if (overlayOn) "On" else "Needs your OK in Android's settings") {
                if (!overlayOn) TextButton(onClick = { act.askOverlay() }) { Text("Allow") }
            }
            if (overlayOn) {
                GroupDivider()
                Item("Show me the cat", onClick = { CatOverlay.test(ctx, s.companion) })
            }
        }
        Group("Daily reminder", foot = "It won't ping you if you're already in a block.") {
            Item("Remind me to start a block", Icons.Rounded.Alarm, k.indigo) { Switch(set.daily.on, { daily(set.daily.copy(on = it)) }) }
            if (set.daily.on) {
                GroupDivider()
                val t = runCatching { LocalTime.parse(set.daily.time) }.getOrDefault(LocalTime.of(9, 0))
                Item("Time", onClick = {
                    TimePickerDialog(ctx, { _, h, m -> daily(set.daily.copy(time = "%02d:%02d".format(h, m))) }, t.hour, t.minute, AndroidDateFormat.is24HourFormat(ctx)).show()
                }) { Text(t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                GroupDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    DAYS.forEachIndexed { i, d ->
                        val on = i in set.daily.days
                        FilterChip(selected = on, onClick = {
                            daily(set.daily.copy(days = if (on) set.daily.days - i else (set.daily.days + i).sorted()))
                        }, label = { Text(d) })
                    }
                }
            }
        }
        Group("Notifications", foot = "Using Do Not Disturb or a Mode? Add Kavana to its allowed apps so check-ins still come through.") {
            Item(if (notifyOn) "Notifications are on" else "Notifications are off", Icons.Rounded.NotificationsActive, k.red) {
                if (!notifyOn) TextButton(onClick = { act.askNotifications() }) { Text("Turn On") }
                else TextButton(onClick = {
                    Notify.ping(ctx, Ping(System.currentTimeMillis(), "checkin", title = "${if (set.cat) "🐱 Boo! " else ""}Still on: Testing?", body = "This is how a check-in looks."), null)
                }) { Text("Test") }
            }
        }
        Group("During a block") {
            Item("Keep the screen on", Icons.Rounded.LightMode, k.orange) {
                Switch(awake, { awake = it; repo.prefs.edit().putBoolean("awake", it).apply() })
            }
        }
        Group("About", foot = "Everything stays on this phone. Nothing is sent anywhere.") {
            Item("Kavana on the web", Icons.Rounded.Language, k.blue, onClick = { act.openUrl("https://stuffs.tiresia.xyz/kavana/") })
            GroupDivider()
            Item("Kavana is open source", Icons.Rounded.Code, k.gray, onClick = { act.openUrl("https://github.com/franz-rosenzweig/kavana") })
            GroupDivider()
            Item("Version", chevron = false) { Text(BuildConfig.VERSION_NAME, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A setting with a few values, picked from a menu. */
@Composable
private fun Pick(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tile: androidx.compose.ui.graphics.Color, options: List<Pair<Int, String>>, value: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Item(label, icon, tile, onClick = { open = true }, chevron = false) {
        Box {
            Text(options.firstOrNull { it.first == value }?.second ?: "$value", color = MaterialTheme.colorScheme.primary)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onPick(v) }) }
            }
        }
    }
}
