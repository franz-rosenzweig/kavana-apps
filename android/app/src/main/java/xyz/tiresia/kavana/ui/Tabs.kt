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
import androidx.compose.runtime.saveable.rememberSaveable
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
    var sheet by remember { mutableStateOf<String?>(null) }
    var account by rememberSaveable { mutableStateOf(false) }
    if (account) { AccountScreen { account = false }; return }
    fun daily(d: DailySet) = repo.launch(settings("daily" to mapOf("on" to d.on, "time" to d.time, "days" to d.days), "tz" to TimeZone.getDefault().id))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 28.dp)) {
        LargeTitle("Settings")
        AccountRow { account = true }
        Group("Focus", foot = "Your kitten grows while you focus. Stop early, or leave Chataltul for more than a few seconds, and it wanders off. Locking your phone is fine. A pause lasts 5 minutes at most. \"Count extra time\" keeps a block going past its end until you tap Done.") {
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
            GroupDivider()
            Item("Tags", Icons.Rounded.Sell, k.blue, onClick = { sheet = "tags" }) { Text("${set.tags.size}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            GroupDivider()
            Pick("Pausing", Icons.Rounded.Pause, k.orange, listOf(1 to "Up to 5 min", 0 to "Off"), if (set.pause == "off") 0 else 1) { repo.launch(settings("pause" to if (it == 0) "off" else "five")) }
            GroupDivider()
            Item("Count extra time", Icons.Rounded.Timer, k.green) { Switch(set.overtime, { repo.launch(settings("overtime" to it)) }) }
            GroupDivider()
            Item("Swiping Chataltul away counts as leaving", Icons.Rounded.SwipeUp, k.red) { Switch(set.closeCounts, { repo.launch(settings("closeCounts" to it)) }) }
            GroupDivider()
            Item("Your own lines", Icons.Rounded.FormatQuote, k.indigo, sub = "Nudges in your own words, every other one", onClick = { sheet = "lines" }) {
                Text(if (set.lines.isEmpty()) "None" else "${set.lines.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GroupDivider()
            Item("Café theme", Icons.Rounded.Palette, k.mint, onClick = { onTab(Tab.Cafe) }) {
                Text(s.shop.themes.firstOrNull { it.id == set.theme }?.name ?: "Classic", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (set.cat) Group("The cat over other apps", foot = "When you leave Chataltul in the middle of a block, or a check-in comes while you're in another app, the cat pops up over it and says Boo!") {
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
        Group("Notifications", foot = "Using Do Not Disturb or a Mode? Add Chataltul to its allowed apps so check-ins still come through.") {
            Item(if (notifyOn) "Notifications are on" else "Notifications are off", Icons.Rounded.NotificationsActive, k.red) {
                if (!notifyOn) TextButton(onClick = { act.askNotifications() }) { Text("Turn On") }
                else TextButton(onClick = {
                    Notify.ping(ctx, Ping(System.currentTimeMillis(), "checkin", title = "${if (set.cat) "🐱 Boo! " else ""}Still on: Testing?", body = "This is how a check-in looks."), null)
                }) { Text("Test") }
            }
        }
        Group("Widgets", foot = "Or press and hold on your home screen, tap Widgets, and look for Chataltul. Titles show on the home screen; with this on they don't, and the lock screen never shows them.") {
            xyz.tiresia.kavana.widget.WidgetPins.KINDS.forEach { w ->
                Item(w.name, Icons.Rounded.Widgets, k.mint, sub = w.about, onClick = { xyz.tiresia.kavana.widget.WidgetPins.request(ctx, w) }) {
                    Text("Add", color = MaterialTheme.colorScheme.primary)
                }
                GroupDivider()
            }
            Item("Quick Settings tiles", Icons.Rounded.Dashboard, k.indigo, sub = "Start, Park a thought and Focus sound", onClick = { xyz.tiresia.kavana.widget.Tiles.requestAdd(ctx) }) {
                Text("Add", color = MaterialTheme.colorScheme.primary)
            }
            GroupDivider()
            Item("Café wallpaper", Icons.Rounded.Wallpaper, k.orange, onClick = { xyz.tiresia.kavana.wallpaper.CafeWallpaperService.choose(ctx) }) {
                Text("Set", color = MaterialTheme.colorScheme.primary)
            }
            GroupDivider()
            var hide by remember { mutableStateOf(xyz.tiresia.kavana.widget.WidgetPins.hideTitles) }
            Item("Hide block titles", Icons.Rounded.VisibilityOff, k.gray, sub = "On widgets and the lock screen") {
                Switch(hide, { hide = it; xyz.tiresia.kavana.widget.WidgetPins.hideTitles = it })
            }
        }
        Group("During a block") {
            Item("Keep the screen on", Icons.Rounded.LightMode, k.orange) {
                Switch(awake, { awake = it; repo.prefs.edit().putBoolean("awake", it).apply() })
            }
        }
        Group("About", foot = "Without an account, your history stays on this phone and nothing about you is sent anywhere. The one thing Chataltul fetches is a sound's recording, the first time you pick it. Signed in, your history is backed up on Chataltul's server and syncs with the web app and your other devices.") {
            Item("Chataltul on the web", Icons.Rounded.Language, k.blue, onClick = { act.openUrl("https://stuffs.tiresia.xyz/kavana/") })
            GroupDivider()
            Item("Chataltul is open source", Icons.Rounded.Code, k.gray, onClick = { act.openUrl("https://github.com/franz-rosenzweig/kavana") })
            GroupDivider()
            Item("Version", chevron = false) { Text(BuildConfig.VERSION_NAME, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    when (sheet) {
        "tags" -> ListSheet("Tags", set.tags, 20, 12, "New tag", "Tags show on your blocks in the Café, in their own colours.", { sheet = null }) { repo.launch(settings("tags" to it)) }
        "lines" -> ListSheet("Your Own Lines", set.lines, 120, 20, "Breathe. Then the next sentence.", "With \"Nudge me\", every other nudge is one of yours.", { sheet = null }) { repo.launch(settings("lines" to it)) }
    }
}

/** A short list you edit: remove with the minus, add at the bottom (Tags, Your own lines). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListSheet(title: String, items: List<String>, maxLen: Int, maxItems: Int, hint: String, foot: String, onDismiss: () -> Unit, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun add() { val t = text.trim(); if (t.isNotEmpty() && t !in items && items.size < maxItems) onChange(items + t); text = "" }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding()) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
            Group(foot = foot) {
                items.forEachIndexed { i, t ->
                    if (i > 0) GroupDivider()
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onChange(items - t) }) { Icon(Icons.Rounded.RemoveCircle, "Remove $t", tint = MaterialTheme.colorScheme.error) }
                        Text(t, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    }
                }
                if (items.size < maxItems) {
                    if (items.isNotEmpty()) GroupDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                        Icon(Icons.Rounded.AddCircle, null, tint = MaterialTheme.colorScheme.primary)
                        TextField(text, { text = it.take(maxLen) }, placeholder = { Text(hint) }, singleLine = true, colors = plainField(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }), modifier = Modifier.weight(1f))
                        if (text.isNotBlank()) TextButton(onClick = { add() }) { Text("Add") }
                    }
                }
            }
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
